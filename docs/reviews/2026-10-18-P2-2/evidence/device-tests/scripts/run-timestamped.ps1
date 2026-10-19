# Host-side runner for one `am instrument` invocation, with the two guards this device needs.
#
#  1. force-stop both debug packages (so the run starts clean), then `am unfreeze --sticky` the
#     debug package: the Red Magic / Nubia ROM freezes instrumentation processes (see README
#     section 3), and the sticky unfreeze is what keeps a run from stalling. It is process/UID
#     level, lives in memory only, and a later force-stop clears it.
#  2. stream the raw adb output line by line and prefix every line with a host-side
#     `HH:mm:ss.fff` timestamp, which is where the per-case durations come from
#     (feed the file to `extract-timings.ps1`).
#
# ASCII-only (PowerShell 5.1 has no UTF-8 default); the device text is written as UTF-8 without
# BOM. Note: the JUnit failure *messages* come back through the Windows adb pipe mangled
# (UTF-8 bytes decoded as GBK) - see `04e-follow-class-rerun.md` section 5. The ASCII structure
# and all numbers are unaffected.
#
# Usage (from the repo root, PATH must not need adb):
#   $env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
#   # one class
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\run-timestamped.ps1 `
#       -Class com.xavierxia.mycelium.hearth.HearthTimelineFollowDeviceTest -LogPath <log>
#   # whole suite (omit -Class)
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\run-timestamped.ps1 -LogPath <log>
#
# Run `scripts\guards.ps1` in parallel against the same -LogPath for the freezer guard and the
# host-side hard bound.
param(
    [string]$Class = '',
    [Parameter(Mandatory = $true)][string]$LogPath
)
$ErrorActionPreference = 'Continue'
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
$selector = if ($Class -ne '') { '-e class ' + $Class + ' ' } else { '' }

& $adb shell am force-stop com.xavierxia.mycelium.debug | Out-Null
& $adb shell am force-stop com.xavierxia.mycelium.debug.test | Out-Null
Start-Sleep -Seconds 1
& $adb shell am unfreeze --sticky com.xavierxia.mycelium.debug | Out-Null
& $adb shell am unfreeze --sticky com.xavierxia.mycelium.debug.test | Out-Null

$out = New-Object System.IO.StreamWriter($LogPath, $false, (New-Object System.Text.UTF8Encoding($false)))
$out.WriteLine(((Get-Date).ToString('HH:mm:ss.fff') + ' == start selector=[' + $selector + '] =='))
$out.Flush()

$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $adb
$psi.Arguments = 'shell am instrument -w -r ' + $selector + 'com.xavierxia.mycelium.debug.test/androidx.test.runner.AndroidJUnitRunner'
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.UseShellExecute = $false
$psi.StandardOutputEncoding = New-Object System.Text.UTF8Encoding($false)
$psi.StandardErrorEncoding = New-Object System.Text.UTF8Encoding($false)
$p = [System.Diagnostics.Process]::Start($psi)
while (-not $p.StandardOutput.EndOfStream) {
    $line = $p.StandardOutput.ReadLine()
    if ($null -ne $line) {
        $out.WriteLine(((Get-Date).ToString('HH:mm:ss.fff') + ' ' + $line))
        $out.Flush()
    }
}
$p.WaitForExit()
$out.WriteLine(((Get-Date).ToString('HH:mm:ss.fff') + ' == adb exit ' + $p.ExitCode + ' =='))
$out.Close()
