# Sample-driven self-test for guards.ps1 (PowerShell 5.1 / ASCII only).
#
# TEXT LEVEL ONLY: no real adb, no device. Phase 1 runs every scenario under
# ../guards-samples/scenarios/*.json through the real guards.ps1 (`-ReplayDir`, deterministic
# clock, no sleeping) and asserts:
#   - the exit code (0 DONE / 2 CRASH / 3 FREEZE_TIMEOUT / 4 STALL / 5 UNKNOWN),
#   - required report lines (regex),
#   - forbidden report lines (regex) -- e.g. `run finished normally` must never appear for a
#     crash or an unclassifiable tail,
#   - the adb actions the guard would have issued (unfreeze / force-stop), read from
#     <report>.commands.txt,
#   - and, for every unreadable/unparsable probe, that the report says PROBE-UNKNOWN and the run
#     ends as UNKNOWN instead of being folded into "not frozen".
#
# The scenario count is read from the directory, so the summary always matches what ran. Two
# families exist because of r6 review items 1 and 2: 12-done-timestamped feeds the guard the
# timestamped tail that run-timestamped.ps1 actually writes (verbatim from the 04c archive), and
# 13/14/15 drive the WCHAN column-count, `-` and blank-cell shapes that README section 4 claims.
#
# Phase 2 runs the *live* loop of the same guards.ps1 for a few seconds with `-Adb` pointing at
# guards-samples/fake-adb.ps1 (a fixture that always reports "no device"), to prove the live wiring
# (adb capture -> probe -> report -> time cap) is not bypassed by the replay path. It still touches
# no device: no real adb is called.
#
# What this does NOT cover: that the real `adb shell pidof` / `run-as ... ps -T` commands on a
# real ROM return the shapes the fixtures assume, and that a real freeze is actually detected on
# the device. Those need a device run; see ../README.md section 4.
#
# Usage:  pwsh -NoProfile -File .\guards-selftest.ps1
# Exit:   0 = all scenarios passed, 1 = at least one failed.

$ErrorActionPreference = 'Stop'

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$guard = Join-Path $here 'guards.ps1'
$sampleRoot = Join-Path (Split-Path -Parent $here) 'guards-samples'
$scenarioDir = Join-Path $sampleRoot 'scenarios'

if (-not (Test-Path $guard)) { throw "guards-selftest: guards.ps1 not found next to this script" }
if (-not (Test-Path $scenarioDir)) { throw "guards-selftest: scenario dir not found: $scenarioDir" }

$tmpRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('guards-selftest-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tmpRoot | Out-Null

$pass = 0
$fail = 0
$failures = New-Object System.Collections.ArrayList

Write-Output "guards-selftest: text-level replay only (no adb, no device); samples: $sampleRoot"

$scenarioFiles = @(Get-ChildItem -Path $scenarioDir -Filter '*.json' | Sort-Object Name)
$scenarioCount = $scenarioFiles.Count

foreach ($file in $scenarioFiles) {
    $scenario = Get-Content -Path $file.FullName -Raw -Encoding UTF8 | ConvertFrom-Json
    $report = Join-Path $tmpRoot ($file.BaseName + '.report.txt')
    $trace = "$report.commands.txt"

    $null = & $guard -ReportPath $report -ReplayDir $file.FullName
    $code = $LASTEXITCODE

    $reportText = if (Test-Path $report) { (Get-Content -Path $report -Raw -Encoding UTF8) } else { '' }
    $traceText = if (Test-Path $trace) { (Get-Content -Path $trace -Raw -Encoding UTF8) } else { '' }
    $problems = New-Object System.Collections.ArrayList

    if (-not (Test-Path $report)) { [void]$problems.Add('report file was not written') }
    if ($code -ne [int]$scenario.expectExit) {
        [void]$problems.Add("exit code expected $($scenario.expectExit), got $code")
    }
    foreach ($pattern in @($scenario.expectReport)) {
        if ($null -eq $pattern -or "$pattern" -eq '') { continue }
        # Multiline: report patterns are anchored per line (`^DONE `, `^VERDICT ...`).
        if (-not [regex]::IsMatch($reportText, $pattern, [System.Text.RegularExpressions.RegexOptions]::Multiline)) {
            [void]$problems.Add("missing report line: $pattern")
        }
    }
    foreach ($pattern in @($scenario.expectNoReport)) {
        if ($null -eq $pattern -or "$pattern" -eq '') { continue }
        if ([regex]::IsMatch($reportText, $pattern, [System.Text.RegularExpressions.RegexOptions]::Multiline)) {
            [void]$problems.Add("forbidden report line: $pattern")
        }
    }
    foreach ($pattern in @($scenario.expectCommands)) {
        if ($null -eq $pattern -or "$pattern" -eq '') { continue }
        if ($traceText -notmatch [regex]::Escape($pattern)) { [void]$problems.Add("missing adb action: $pattern") }
    }

    if ($problems.Count -eq 0) {
        $pass++
        Write-Output ("PASS {0} ({1})" -f $file.BaseName, $scenario.name)
    } else {
        $fail++
        [void]$failures.Add(($file.BaseName, $problems))
        Write-Output ("FAIL {0} ({1})" -f $file.BaseName, $scenario.name)
        foreach ($p in $problems) { Write-Output ("     - " + $p) }
    }
}

# --- phase 2: the live loop itself, driven by a fixture adb (still text level: no device) ---
# This is what keeps -ReplayDir from being a parallel code path nobody checks: same guards.ps1,
# the real live loop (sleep, clock, log file, report), only the adb executable is a fixture that
# always reports "no device".
$fakeAdb = Join-Path $sampleRoot 'fake-adb.ps1'
if (-not (Test-Path $fakeAdb)) {
    $fail++
    Write-Output "FAIL live-adb-error (phase 2): fixture not found: $fakeAdb"
} else {
    $liveReport = Join-Path $tmpRoot 'live-adb-error.report.txt'
    $liveLog = Join-Path $sampleRoot 'log-running.txt'
    $null = & $guard -LogPath $liveLog -ReportPath $liveReport -Package 'com.example.guards-selftest.none' `
        -StaleMinutes 1 -PollSeconds 1 -Seconds 4 -Adb $fakeAdb
    $code = $LASTEXITCODE
    $liveText = if (Test-Path $liveReport) { (Get-Content -Path $liveReport -Raw -Encoding UTF8) } else { '' }
    $problems = New-Object System.Collections.ArrayList
    if ($code -ne 5) { [void]$problems.Add("exit code expected 5, got $code") }
    if (-not [regex]::IsMatch($liveText, '^PROBE-UNKNOWN .*no devices/emulators found', 'Multiline')) {
        [void]$problems.Add('missing PROBE-UNKNOWN line for the adb error text')
    }
    if (-not [regex]::IsMatch($liveText, '^UNKNOWN .*total cap', 'Multiline')) {
        [void]$problems.Add('missing the time-cap UNKNOWN line')
    }
    if ([regex]::IsMatch($liveText, 'force-stop', 'Multiline')) {
        [void]$problems.Add('live loop force-stopped although it never saw a stall')
    }
    if ($problems.Count -eq 0) {
        $pass++
        Write-Output 'PASS live-adb-error (phase 2: real live loop, fixture adb, no device)'
    } else {
        $fail++
        Write-Output 'FAIL live-adb-error (phase 2: real live loop, fixture adb, no device)'
        foreach ($p in $problems) { Write-Output ("     - " + $p) }
    }
}

Write-Output ""
Write-Output ("guards-selftest: {0} passed, {1} failed (scenarios in {2})" -f $pass, $fail, $scenarioDir)
Write-Output ("note: {0} replay scenarios + 1 live-loop smoke test, all text level; the real device paths still need a device run." -f $scenarioCount)
if ($fail -gt 0) { exit 1 }
exit 0
