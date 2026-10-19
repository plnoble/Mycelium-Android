# Combined host-side guards for an `am instrument` run (PowerShell 5.1 / ASCII only).
#
#  1. Freezer guard: the Red Magic / Nubia ROM freezes the instrumentation process while it runs
#     (every thread parks in `do_freezer_trap`), which is what looked like a 16-minute hang. When
#     that is detected, `am unfreeze --sticky` is issued and recorded.
#  2. Hard bound: if the log stops growing for -StaleMinutes, the run is declared stuck, the case
#     that was running is recorded, and both debug packages are force-stopped.
#
# Usage:
#   & .\guards.ps1 -LogPath <log> -ReportPath <report> -Package com.xavierxia.mycelium.debug `
#                  -StaleMinutes 3 -PollSeconds 15 -Seconds 2400
param(
    [Parameter(Mandatory = $true)][string]$LogPath,
    [Parameter(Mandatory = $true)][string]$ReportPath,
    [string]$Package = 'com.xavierxia.mycelium.debug',
    [int]$StaleMinutes = 3,
    [int]$PollSeconds = 15,
    [int]$Seconds = 2400
)

function Read-Lines([string]$path) {
    if (-not (Test-Path $path)) { return @() }
    return [System.IO.File]::ReadAllLines($path, [System.Text.Encoding]::UTF8)
}

$end = (Get-Date).AddSeconds($Seconds)
$lastSize = -1
$deadline = (Get-Date).AddMinutes($StaleMinutes)
while ((Get-Date) -lt $end) {
    Start-Sleep -Seconds $PollSeconds

    # 1. freezer guard
    $pidText = (& adb shell pidof $Package 2>$null | Out-String).Trim()
    if ($pidText) {
        $pid = ($pidText -split '\s+')[0]
        $rows = @(& adb shell run-as $Package ps -T -p $pid -o WCHAN 2>$null)
        $threadRows = @($rows | Where-Object { $_ -match '^\s*\d+\s' })
        if ($threadRows.Count -gt 0) {
            $frozen = @($threadRows | Where-Object { $_ -match 'do_freezer_trap' }).Count
            if ($frozen -eq $threadRows.Count) {
                Add-Content -Path $ReportPath -Encoding ascii -Value "FREEZER $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') $Package pid=$pid all $($threadRows.Count) threads in do_freezer_trap -> am unfreeze --sticky"
                & adb shell am unfreeze --sticky $Package | Out-Null
            }
        }
    }

    # 2. hard bound
    $item = Get-Item $LogPath -ErrorAction SilentlyContinue
    $size = if ($item) { $item.Length } else { 0 }
    if ($size -ne $lastSize) {
        $lastSize = $size
        $deadline = (Get-Date).AddMinutes($StaleMinutes)
        continue
    }
    if ((Get-Date) -lt $deadline) { continue }
    if (@(Read-Lines $LogPath | Where-Object { $_ -match 'INSTRUMENTATION_CODE:' }).Count -gt 0) {
        Add-Content -Path $ReportPath -Encoding ascii -Value "DONE $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') run finished normally; guards stop"
        exit 0
    }

    $started = New-Object System.Collections.ArrayList
    $finished = New-Object System.Collections.ArrayList
    $current = '(unknown)'
    foreach ($line in (Read-Lines $LogPath)) {
        if ($line -match 'INSTRUMENTATION_STATUS: test=(.+)$') { $current = $Matches[1] }
        elseif ($line -match 'INSTRUMENTATION_STATUS_CODE: 1$') { [void]$started.Add($current) }
        elseif ($line -match 'INSTRUMENTATION_STATUS_CODE: (-?\d+)$' -and $Matches[1] -ne '1') { [void]$finished.Add($current) }
    }
    $running = @($started | Where-Object { $finished -notcontains $_ })
    Add-Content -Path $ReportPath -Encoding ascii -Value @"
WATCHDOG $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') no log growth for $StaleMinutes min (size $size bytes)
started  : $($started -join ' | ')
finished : $($finished -join ' | ')
stuck    : $($running -join ' | ')
action   : force-stop $Package and com.xavierxia.mycelium.debug.test
"@
    Add-Content -Path $LogPath -Value "`n== WATCHDOG: no log growth for $StaleMinutes min; stuck case: $($running -join ' | '); force-stopped =="
    & adb shell am force-stop $Package
    & adb shell am force-stop com.xavierxia.mycelium.debug.test
    exit 3
}
