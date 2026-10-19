# Combined host-side guards for an `am instrument` run (PowerShell 5.1 / ASCII only).
#
#  1. Freezer guard: the Red Magic / Nubia ROM freezes the instrumentation process while it runs
#     (every thread parks in `do_freezer_trap`), which is what looked like a 16-minute hang. When
#     that is detected, `am unfreeze --sticky` is issued and recorded.
#  2. Hard bound: if the log stops growing for -StaleMinutes, the run is declared stuck, the case
#     that was running is recorded, and both debug packages are force-stopped.
#  3. Classification: the guard never treats "some INSTRUMENTATION_CODE appeared" as a normal
#     finish, and a freeze probe that fails to parse is NEVER silently read as "not frozen".
#
# Exit codes (semantically distinct; see the table in ../README.md section 4):
#   0 DONE            the log ends with `INSTRUMENTATION_CODE: -1` -> this invocation finished
#                     (whether the tests passed is in the tail, not this code)
#   2 CRASH           `INSTRUMENTATION_CODE` other than -1 (e.g. 0 together with
#                     `INSTRUMENTATION_RESULT: shortMsg=Process crashed.`) or a crash marker
#   3 FREEZE_TIMEOUT  the log stopped growing AND the probe confirmed a freeze
#                     (every thread in do_freezer_trap)
#   4 STALL           the log stopped growing, the probe parsed fine and saw no freeze
#   5 UNKNOWN         cannot classify: probe unusable/unparsable, target gone with no terminal
#                     marker, unrecognized INSTRUMENTATION_CODE, or the total -Seconds cap hit
#                     with no terminal marker
#
# Usage (live; needs adb + a device):
#   & .\guards.ps1 -LogPath <log> -ReportPath <report> -Package com.xavierxia.mycelium.debug `
#                  -StaleMinutes 3 -PollSeconds 15 -Seconds 2400
#   -Adb overrides the adb command (default 'adb'); it exists so the self-test can drive the live
#   loop with a fixture instead of a device.
#
# Usage (text-level replay; no adb, no waiting, deterministic clock):
#   & .\guards.ps1 -ReportPath <report> -ReplayDir <scenario.json>
#   Samples live in ../guards-samples/ and are driven by .\guards-selftest.ps1.
param(
    [string]$LogPath = '',
    [Parameter(Mandatory = $true)][string]$ReportPath,
    [string]$Package = 'com.xavierxia.mycelium.debug',
    [int]$StaleMinutes = 3,
    [int]$PollSeconds = 15,
    [int]$Seconds = 2400,
    [string]$ReplayDir = '',
    [string]$Adb = 'adb'
)

$script:Adb = $Adb

function Read-TextFile([string]$path) {
    if (-not (Test-Path $path)) { return '' }
    return [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)
}

# Run one adb command and return its merged stdout+stderr as text. A missing/broken adb is turned
# into error text (so the probe reports UNKNOWN) instead of killing the guard.
function Invoke-AdbCapture([string[]]$Arguments) {
    try {
        $raw = @(& $script:Adb @Arguments 2>&1)
        return (@($raw | ForEach-Object { $_.ToString() }) -join "`n").Trim()
    } catch {
        return ("error: cannot run adb ('{0}'): {1}" -f $script:Adb, $_.Exception.Message)
    }
}

function Resolve-Sample([string]$baseDir, [string]$name) {
    foreach ($candidate in @((Join-Path $baseDir $name), (Join-Path (Split-Path $baseDir -Parent) $name))) {
        if (Test-Path $candidate) { return (Resolve-Path $candidate).Path }
    }
    throw "guards: sample file not found: $name (looked in $baseDir and its parent)"
}

function Write-Report([string]$text) {
    Add-Content -Path $ReportPath -Encoding ascii -Value $text
}

# ---------------------------------------------------------------------------
# `adb shell pidof <pkg>` -> target pid, or an explicit reason why we cannot tell.
# Returns @{ Pid = '<digits>' | ''; Error = '' | '<text>' }.
# Empty text + no error = pidof exit 1 = the process is not running (normal transient state).
# Any non-numeric output (adb "error: device offline", "no devices/emulators found", ...) is an
# error, never a silent "no process".
# ---------------------------------------------------------------------------
function Select-TargetPid([string]$PidOfText) {
    $t = ([string]$PidOfText).Trim()
    if ($t -eq '') { return @{ Pid = ''; Error = '' } }
    if ($t -match '^\d+(\s+\d+)*$') {
        $first = @($t -split '\s+')[0]
        return @{ Pid = $first; Error = '' }
    }
    return @{ Pid = ''; Error = ($t -split "\r?\n")[0] }
}

# ---------------------------------------------------------------------------
# Freeze probe. Parses the REAL shape of `ps -T -p <pid> -o PID,TID,STAT,WCHAN`
# (header row + one row per thread), never a "does any line start with digits" guess.
#
# Returns @{ State; Pid; ThreadCount; FrozenCount; Reason }
#   State = 'FROZEN' | 'NOT_FROZEN' | 'NO_PID' | 'UNKNOWN'
# UNKNOWN is returned for every shape we cannot read with confidence (missing WCHAN column,
# column-count mismatch, empty WCHAN, `-`/`?` WCHAN, non-numeric PID cell, no thread rows,
# adb/ps error text). UNKNOWN is never folded into "not frozen".
# ---------------------------------------------------------------------------
function Get-FreezeProbe([string]$PidOfText, [string]$PsText) {
    $r = [pscustomobject]@{
        State = 'UNKNOWN'; Pid = ''; ThreadCount = 0; FrozenCount = 0; Reason = ''
    }

    $sel = Select-TargetPid $PidOfText
    if ($sel.Error -ne '') {
        $r.Reason = "pidof output is not a pid: $($sel.Error)"
        return $r
    }
    if ($sel.Pid -eq '') { $r.State = 'NO_PID'; $r.Reason = 'pidof printed nothing (process not running)'; return $r }
    $r.Pid = $sel.Pid

    $trimmed = ([string]$PsText).Trim()
    if ($trimmed -eq '') { $r.Reason = 'ps printed nothing'; return $r }
    $rows = @($trimmed -split "\r?\n" | Where-Object { $_.Trim().Length -gt 0 })
    if ($rows.Count -eq 0) { $r.Reason = 'ps printed only blank lines'; return $r }

    $header = $rows[0].Trim()
    $cols = @($header -split '\s+')
    $pidIdx = -1
    $wchanIdx = -1
    for ($i = 0; $i -lt $cols.Count; $i++) {
        if ($cols[$i] -ieq 'PID') { $pidIdx = $i }
        if ($cols[$i] -ieq 'WCHAN') { $wchanIdx = $i }
    }
    if ($wchanIdx -lt 0) { $r.Reason = "ps header has no WCHAN column (header='$header')"; return $r }
    if ($pidIdx -lt 0) { $r.Reason = "ps header has no PID column (header='$header')"; return $r }

    $threads = 0
    $frozen = 0
    for ($n = 1; $n -lt $rows.Count; $n++) {
        $line = $rows[$n].Trim()
        $cells = @($line -split '\s+')
        if ($cells.Count -le $wchanIdx) {
            $r.Reason = "ps row $($n + 1) has fewer than $($wchanIdx + 1) columns (no WCHAN value): '$line'"
            return $r
        }
        if ($cells[$pidIdx] -notmatch '^\d+$') {
            $r.Reason = "ps row $($n + 1) PID column is not numeric: '$line'"
            return $r
        }
        $wchan = $cells[$wchanIdx]
        if ($wchan -eq '-' -or $wchan -eq '?' -or $wchan -eq '') {
            $r.Reason = "ps row $($n + 1) WCHAN unavailable ('$wchan'): '$line'"
            return $r
        }
        $threads++
        if ($wchan -match 'do_freezer_trap') { $frozen++ }
    }

    $r.ThreadCount = $threads
    $r.FrozenCount = $frozen
    if ($threads -eq 0) { $r.Reason = 'ps printed a WCHAN header but no thread rows'; return $r }
    if ($frozen -eq $threads) { $r.State = 'FROZEN'; $r.Reason = "all $threads threads in do_freezer_trap" }
    else { $r.State = 'NOT_FROZEN'; $r.Reason = "$frozen of $threads threads in do_freezer_trap" }
    return $r
}

# ---------------------------------------------------------------------------
# Terminal state of the instrument log (scanned from the bottom).
#   DONE    : INSTRUMENTATION_CODE: -1
#   CRASH   : any other code together with a crash marker, or a crash marker alone
#   UNKNOWN : any other code with no crash marker (e.g. a lone `INSTRUMENTATION_CODE: 0`)
#   RUNNING : no terminal marker yet
# ---------------------------------------------------------------------------
function Get-RunTerminal([string[]]$Lines) {
    $code = $null
    for ($i = $Lines.Count - 1; $i -ge 0; $i--) {
        $m = [regex]::Match([string]$Lines[$i], '^INSTRUMENTATION_CODE:\s*(-?\d+)\s*$')
        if ($m.Success) { $code = [int]$m.Groups[1].Value; break }
    }
    $marker = ''
    foreach ($line in $Lines) {
        $s = [string]$line
        if ($s -match 'shortMsg=Process crashed' -or $s -match 'Process crashed while executing' -or
            $s -match 'FATAL EXCEPTION' -or $s -match 'INSTRUMENTATION_FAILED') {
            $marker = $s.Trim()
            break
        }
    }

    if ($null -ne $code) {
        if ($code -eq -1) { return @{ State = 'DONE'; Code = $code; Reason = 'INSTRUMENTATION_CODE: -1' } }
        if ($marker -ne '') { return @{ State = 'CRASH'; Code = $code; Reason = "$marker (with INSTRUMENTATION_CODE: $code)" } }
        return @{ State = 'UNKNOWN'; Code = $code; Reason = "INSTRUMENTATION_CODE: $code with no crash marker" }
    }
    if ($marker -ne '') { return @{ State = 'CRASH'; Code = $null; Reason = "$marker (no INSTRUMENTATION_CODE line)" } }
    return @{ State = 'RUNNING'; Code = $null; Reason = 'no terminal marker yet' }
}

function Get-RunProgress([string[]]$Lines) {
    $started = New-Object System.Collections.ArrayList
    $finished = New-Object System.Collections.ArrayList
    $current = '(unknown)'
    foreach ($line in $Lines) {
        $s = [string]$line
        if ($s -match 'INSTRUMENTATION_STATUS: test=(.+)$') { $current = $Matches[1] }
        elseif ($s -match 'INSTRUMENTATION_STATUS_CODE: 1$') { [void]$started.Add($current) }
        elseif ($s -match 'INSTRUMENTATION_STATUS_CODE: (-?\d+)$' -and $Matches[1] -ne '1') { [void]$finished.Add($current) }
    }
    $running = @($started | Where-Object { $finished -notcontains $_ })
    return @{ Started = @($started); Finished = @($finished); Running = $running }
}

function New-GuardState {
    return @{
        LastSize = [long](-1)
        Deadline = [datetime]::MinValue
        Frozen = $false
        FreezeAttempts = 0
        LastProbeState = ''
        LastProbeUnknown = ''
        LogMarkers = New-Object System.Collections.Generic.List[string]
        Commands = New-Object System.Collections.Generic.List[string]
    }
}

# ---------------------------------------------------------------------------
# One polling step, shared by the live loop and the replay driver.
# Returns @{ Action = 'CONTINUE' | 'EXIT'; ExitCode = <int> }
# ---------------------------------------------------------------------------
function Invoke-GuardStep {
    param(
        [hashtable]$State,
        [string]$ReportPath,
        [string]$Package,
        [object]$Probe,
        [string]$LogText,
        [long]$LogSize,
        [datetime]$Now,
        [int]$StaleMinutes,
        [scriptblock]$OnUnfreeze,
        [scriptblock]$OnForceStop,
        [scriptblock]$OnLogMarker
    )
    $script:ReportPath = $ReportPath
    $stamp = $Now.ToString('yyyy-MM-dd HH:mm:ss')
    $lines = @($LogText -split "\r?\n")

    # 1. freezer guard (a broken probe is reported, never read as "not frozen")
    switch ($Probe.State) {
        'FROZEN' {
            if (-not $State.Frozen) {
                $State.Frozen = $true
                $State.FreezeAttempts++
                $State.Deadline = $Now.AddMinutes($StaleMinutes)
                Write-Report ("FREEZER {0} {1} pid={2} all {3} threads in do_freezer_trap -> am unfreeze --sticky" -f `
                    $stamp, $Package, $Probe.Pid, $Probe.ThreadCount)
                & $OnUnfreeze $Package
                [void]$State.Commands.Add("unfreeze --sticky $Package")
            }
            $State.LastProbeState = 'FROZEN'
            $State.LastProbeUnknown = ''
        }
        'UNKNOWN' {
            if ($State.LastProbeUnknown -ne $Probe.Reason) {
                $State.LastProbeUnknown = $Probe.Reason
                Write-Report ("PROBE-UNKNOWN {0} {1} pid={2} reason={3}" -f $stamp, $Package, $Probe.Pid, $Probe.Reason)
            }
            $State.LastProbeState = 'UNKNOWN'
        }
        'NOT_FROZEN' {
            $State.Frozen = $false
            $State.LastProbeState = 'NOT_FROZEN'
            $State.LastProbeUnknown = ''
        }
        default {
            $State.LastProbeState = 'NO_PID'
            $State.LastProbeUnknown = ''
        }
    }

    # 2. terminal state, checked on every poll (not only after a stall)
    $term = Get-RunTerminal $lines
    if ($term.State -eq 'DONE') {
        Write-Report ("DONE {0} run finished normally (INSTRUMENTATION_CODE: -1); guards stop" -f $stamp)
        return @{ Action = 'EXIT'; ExitCode = 0 }
    }
    if ($term.State -eq 'CRASH') {
        Write-Report ("CRASH {0} {1}; guards stop" -f $stamp, $term.Reason)
        return @{ Action = 'EXIT'; ExitCode = 2 }
    }
    if ($term.State -eq 'UNKNOWN') {
        Write-Report ("UNKNOWN {0} reason={1}; guards stop" -f $stamp, $term.Reason)
        return @{ Action = 'EXIT'; ExitCode = 5 }
    }

    # 3. hard bound: log growth resets the deadline
    if ($LogSize -ne $State.LastSize) {
        $State.LastSize = $LogSize
        $State.Deadline = $Now.AddMinutes($StaleMinutes)
        return @{ Action = 'CONTINUE'; ExitCode = 0 }
    }
    if ($Now -lt $State.Deadline) { return @{ Action = 'CONTINUE'; ExitCode = 0 } }

    $probeState = $State.LastProbeState
    $progress = Get-RunProgress $lines
    Write-Report @"
WATCHDOG $stamp no log growth for $StaleMinutes min (size $LogSize bytes); probe=$probeState; freeze attempts=$($State.FreezeAttempts)
started  : $($progress.Started -join ' | ')
finished : $($progress.Finished -join ' | ')
stuck    : $($progress.Running -join ' | ')
action   : force-stop $Package and $Package.test
"@
    & $OnLogMarker "`n== WATCHDOG: no log growth for $StaleMinutes min; probe=$probeState; stuck case: $($progress.Running -join ' | '); force-stopped =="
    [void]$State.LogMarkers.Add("WATCHDOG probe=$probeState")
    & $OnForceStop $Package
    [void]$State.Commands.Add("force-stop $Package")
    [void]$State.Commands.Add("force-stop $Package.test")

    if ($probeState -eq 'FROZEN') {
        Write-Report ("VERDICT FREEZE_TIMEOUT exit=3 probe=FROZEN")
        return @{ Action = 'EXIT'; ExitCode = 3 }
    }
    if ($probeState -eq 'NOT_FROZEN') {
        Write-Report ("VERDICT STALL exit=4 probe=NOT_FROZEN")
        return @{ Action = 'EXIT'; ExitCode = 4 }
    }
    Write-Report ("VERDICT UNKNOWN exit=5 probe=$probeState")
    return @{ Action = 'EXIT'; ExitCode = 5 }
}

function Get-TimeCapVerdict {
    param(
        [string]$LogText,
        [string]$ReportPath,
        [datetime]$Now,
        [int]$Seconds,
        [hashtable]$State
    )
    $script:ReportPath = $ReportPath
    $stamp = $Now.ToString('yyyy-MM-dd HH:mm:ss')
    $term = Get-RunTerminal @($LogText -split "\r?\n")
    if ($term.State -eq 'DONE') {
        Write-Report ("DONE {0} run finished normally (INSTRUMENTATION_CODE: -1); guards stop" -f $stamp)
        return 0
    }
    if ($term.State -eq 'CRASH') {
        Write-Report ("CRASH {0} {1}; guards stop" -f $stamp, $term.Reason)
        return 2
    }
    Write-Report ("UNKNOWN {0} reason=total cap (-Seconds $Seconds) reached with no terminal marker (probe=$($State.LastProbeState))" -f $stamp)
    return 5
}

# ---------------------------------------------------------------------------
# Text-level replay: fixture files instead of adb, deterministic clock, no sleeping.
# Expected by guards-selftest.ps1.
# ---------------------------------------------------------------------------
function Invoke-GuardReplay {
    param([string]$ScenarioPath, [string]$ReportPath)

    $scenario = Get-Content -Path $ScenarioPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $dir = Split-Path -Parent (Resolve-Path $ScenarioPath).Path
    $localStale = $StaleMinutes
    $localPoll = $PollSeconds
    $localSeconds = $Seconds
    if ($scenario.args) {
        if ($scenario.args.StaleMinutes) { $localStale = [int]$scenario.args.StaleMinutes }
        if ($scenario.args.PollSeconds) { $localPoll = [int]$scenario.args.PollSeconds }
        if ($scenario.args.Seconds) { $localSeconds = [int]$scenario.args.Seconds }
    }

    $state = New-GuardState
    $onUnfreeze = { param($pkg) }
    $onForceStop = { param($pkg) }
    $onLogMarker = { param($text) }

    $start = Get-Date '2026-01-01T00:00:00'
    $end = $start.AddSeconds($localSeconds)
    $stepNo = 0
    $lastLog = ''
    $exitCode = $null
    foreach ($step in @($scenario.steps)) {
        $stepNo++
        $now = $start.AddSeconds($stepNo * $localPoll)
        if ($now -ge $end) { break }
        $lastLog = Read-TextFile (Resolve-Sample $dir ([string]$step.log))
        $pidText = ''
        if ($null -ne $step.pidof) { $pidText = [string]$step.pidof }
        if ($step.pidofError) { $pidText = [string]$step.pidofError }
        $psText = ''
        if ($step.ps) { $psText = Read-TextFile (Resolve-Sample $dir ([string]$step.ps)) }
        if ($step.psError) { $psText = [string]$step.psError }
        $probe = Get-FreezeProbe -PidOfText $pidText -PsText $psText
        $r = Invoke-GuardStep -State $state -ReportPath $ReportPath -Package $Package -Probe $probe `
            -LogText $lastLog -LogSize ([long]$lastLog.Length) -Now $now -StaleMinutes $localStale `
            -OnUnfreeze $onUnfreeze -OnForceStop $onForceStop -OnLogMarker $onLogMarker
        if ($r.Action -eq 'EXIT') { $exitCode = $r.ExitCode; break }
    }
    if ($null -eq $exitCode) {
        $finalNow = $start.AddSeconds(($stepNo + 1) * $localPoll)
        $exitCode = Get-TimeCapVerdict -LogText $lastLog -ReportPath $ReportPath -Now $finalNow -Seconds $localSeconds -State $state
    }
    $tracePath = "$ReportPath.commands.txt"
    Set-Content -Path $tracePath -Encoding ascii -Value @(
        "exit=$exitCode",
        "commands=$($state.Commands -join '; ')",
        "steps=$stepNo"
    )
    return $exitCode
}

# ---------------------------------------------------------------------------
# Live loop (adb + device).
# ---------------------------------------------------------------------------
function Invoke-GuardLive {
    if ($LogPath -eq '') { throw 'guards: -LogPath is required in live mode (omit only with -ReplayDir)' }

    $state = New-GuardState
    $end = (Get-Date).AddSeconds($Seconds)
    $lastLog = ''
    $onUnfreeze = { param($pkg) [void](Invoke-AdbCapture @('shell', 'am', 'unfreeze', '--sticky', $pkg)) }
    $onForceStop = {
        param($pkg)
        [void](Invoke-AdbCapture @('shell', 'am', 'force-stop', $pkg))
        [void](Invoke-AdbCapture @('shell', 'am', 'force-stop', "$pkg.test"))
    }
    $onLogMarker = { param($text) Add-Content -Path $LogPath -Value $text }

    while ((Get-Date) -lt $end) {
        Start-Sleep -Seconds $PollSeconds

        $pidText = Invoke-AdbCapture @('shell', 'pidof', $Package)
        $psText = ''
        $sel = Select-TargetPid $pidText
        if ($sel.Error -eq '' -and $sel.Pid -ne '') {
            $psText = Invoke-AdbCapture @('shell', 'run-as', $Package, 'ps', '-T', '-p', $sel.Pid, '-o', 'PID,TID,STAT,WCHAN')
        }
        $probe = Get-FreezeProbe -PidOfText $pidText -PsText $psText

        $lastLog = Read-TextFile $LogPath
        $item = Get-Item $LogPath -ErrorAction SilentlyContinue
        $size = if ($item) { [long]$item.Length } else { [long]0 }

        $r = Invoke-GuardStep -State $state -ReportPath $ReportPath -Package $Package -Probe $probe `
            -LogText $lastLog -LogSize $size -Now (Get-Date) -StaleMinutes $StaleMinutes `
            -OnUnfreeze $onUnfreeze -OnForceStop $onForceStop -OnLogMarker $onLogMarker
        if ($r.Action -eq 'EXIT') { exit $r.ExitCode }
    }

    $code = Get-TimeCapVerdict -LogText $lastLog -ReportPath $ReportPath -Now (Get-Date) -Seconds $Seconds -State $state
    exit $code
}

if ($ReplayDir -ne '') {
    exit (Invoke-GuardReplay -ScenarioPath $ReplayDir -ReportPath $ReportPath)
}
Invoke-GuardLive
