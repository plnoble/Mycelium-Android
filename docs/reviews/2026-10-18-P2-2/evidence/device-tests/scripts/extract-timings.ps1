# Turn a host-timestamped instrument log into a per-case markdown table.
#
# The host timestamps (HH:mm:ss.fff prefix) are added while the run streams, so the delta between
# a case's "status code 1" line and its result line is that case's wall-clock duration.
#
# Usage:
#   & .\extract-timings.ps1 -TimedLog <file> -Out <markdown file> -Title "single class"
param(
    [Parameter(Mandatory = $true)][string]$TimedLog,
    [Parameter(Mandatory = $true)][string]$Out,
    [string]$Title = 'device tests'
)

$lines = [System.IO.File]::ReadAllLines($TimedLog, [System.Text.Encoding]::UTF8)
$rows = New-Object System.Collections.ArrayList
$class = '(unknown)'
$name = '(unknown)'
$startedAt = $null
$index = 0

foreach ($line in $lines) {
    if ($line -match '^(\d\d:\d\d:\d\d\.\d\d\d) INSTRUMENTATION_STATUS: class=(.+)$') {
        $class = $Matches[2]
    } elseif ($line -match '^(\d\d:\d\d:\d\d\.\d\d\d) INSTRUMENTATION_STATUS: test=(.+)$') {
        $name = $Matches[2]
    } elseif ($line -match '^(\d\d:\d\d:\d\d\.\d\d\d) INSTRUMENTATION_STATUS_CODE: (-?\d+)$') {
        $stamp = $Matches[1]
        $code = [int]$Matches[2]
        if ($code -eq 1) {
            $startedAt = $stamp
            $index++
        } elseif ($null -ne $startedAt) {
            $result = switch ($code) {
                0 { 'passed' }
                -2 { 'FAILED' }
                -3 { 'skipped/assumption' }
                default { "code=$code" }
            }
            $ms = [int]([TimeSpan]::Parse($stamp) - [TimeSpan]::Parse($startedAt)).TotalMilliseconds
            [void]$rows.Add("| $index | $class#$name | $result | ${ms}ms |")
            $startedAt = $null
        }
    }
}

$header = @(
    "# Per-case timings - $Title",
    '',
    "Source: host-timestamped instrument log ``$(Split-Path -Leaf $TimedLog)`` (timestamps added while streaming; the run's own output is otherwise unchanged).",
    '',
    '| # | case | result | duration |',
    '| --- | --- | --- | --- |'
)
$text = ($header + $rows) -join "`n"
[System.IO.File]::WriteAllText($Out, $text + "`n", [System.Text.UTF8Encoding]::new($false))
"wrote $Out ($($rows.Count) cases)"
