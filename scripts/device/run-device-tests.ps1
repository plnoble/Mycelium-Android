# Runs the whole on-device instrumentation suite in ONE invocation and archives the raw output.
# Work package 1.3.1: the output goes straight to a file; we check the line count, the trailer
# marker and the test count instead of trusting a terminal capture.
#
# The repository is the one this script lives in (scripts/device/..), so the archived result
# belongs to the checkout under review. ASCII only.
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if (-not $env:JAVA_HOME) { throw 'set JAVA_HOME to the JDK Gradle should use' }
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:PATH"
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
$work = if ($env:MYCELIUM_DEVICE_EVIDENCE) { $env:MYCELIUM_DEVICE_EVIDENCE } else { Join-Path $env:TEMP 'mycelium-device-evidence' }
New-Item -ItemType Directory -Force -Path $work | Out-Null

if (-not (& $adb devices | Select-String -Pattern '\sdevice$')) { throw 'no device attached' }

$head = (& 'git' -C $repo rev-parse HEAD).Trim()
"head: $head" | Out-File (Join-Path $work 'run-head.txt') -Encoding utf8

& cmd /c "cd /d `"$repo`" && .\gradlew.bat --console=plain :app:assembleDebug :app:assembleDebugAndroidTest --offline" 2>&1 |
    Select-String -Pattern '^e: |FAILED|BUILD ' | ForEach-Object { $_.Line.Trim() }
if ($LASTEXITCODE -ne 0) { throw "gradle assemble failed with exit $LASTEXITCODE" }

& $adb install -r (Join-Path $repo 'app\build\outputs\apk\debug\app-debug.apk')
if ($LASTEXITCODE -ne 0) { throw "installing the debug APK failed with exit $LASTEXITCODE" }
& $adb install -r (Join-Path $repo 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk')
if ($LASTEXITCODE -ne 0) { throw "installing the instrumentation APK failed with exit $LASTEXITCODE" }

$out = Join-Path $work 'instrument-full.txt'
& $adb shell am instrument -w -r com.xavierxia.mycelium.debug.test/androidx.test.runner.AndroidJUnitRunner 2>&1 |
    Out-File $out -Encoding utf8

$lines = Get-Content $out
"lines=$($lines.Count)"
$lines | Select-Object -Last 3
if ($lines -match 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED') {
    "run reported failures: this run is NOT evidence"
    exit 1
}
$trailer = $lines | Select-String -Pattern '^OK \((\d+) tests?\)' | Select-Object -Last 1
if (-not $trailer) { "NO 'OK (n tests)' trailer: this run is NOT evidence"; exit 1 }
$ran = [int]$trailer.Matches[0].Groups[1].Value
$declared = $lines | Select-String -Pattern 'INSTRUMENTATION_STATUS: numtests=(\d+)' | Select-Object -First 1
$declaredCount = if ($declared) { [int]$declared.Matches[0].Groups[1].Value } else { 0 }
$skipped = ($lines | Select-String -Pattern 'INSTRUMENTATION_STATUS_CODE: -3' | Measure-Object).Count
"tests run=$ran declared=$declaredCount skipped=$skipped"
if ($ran -lt 30) { "only $ran tests ran: expected the full suite"; exit 1 }
"archived: $out"