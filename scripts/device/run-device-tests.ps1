# Runs the whole on-device instrumentation suite in ONE invocation and archives the raw output.
# Work package 1.3.1: the output goes straight to a file; we check the line count and the trailer
# marker instead of trusting a terminal capture. ASCII only.
$ErrorActionPreference = 'Stop'
$work = 'D:\Agent\Worktrees\Mycelium-Android\p2-2-work\device-evidence'
New-Item -ItemType Directory -Force -Path $work | Out-Null
$appRepo = 'D:\Agent\Project\Nivra'
$env:JAVA_HOME = 'D:\Development\Android-studio\jbr'
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:PATH"
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'

if (-not (& $adb devices | Select-String -Pattern '\sdevice$')) { throw 'no device attached' }

# Build both APKs from the committed tree first (debug + instrumentation).
& cmd /c "cd /d `"$appRepo`" && .\gradlew.bat --console=plain :app:assembleDebug :app:assembleDebugAndroidTest --offline" 2>&1 |
    Select-String -Pattern '^e: |FAILED|BUILD ' | ForEach-Object { $_.Line.Trim() }
if ($LASTEXITCODE -ne 0) { throw "gradle assemble failed with exit $LASTEXITCODE" }

& $adb install -r (Join-Path $appRepo 'app\build\outputs\apk\debug\app-debug.apk') | Select-Object -Last 1
& $adb install -r (Join-Path $appRepo 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk') | Select-Object -Last 1

$out = Join-Path $work 'instrument-full.txt'
& $adb shell am instrument -w -r com.xavierxia.mycelium.debug.test/androidx.test.runner.AndroidJUnitRunner 2>&1 |
    Out-File $out -Encoding utf8

$lines = (Get-Content $out).Count
$tail = Get-Content $out | Select-Object -Last 3
"lines=$lines"
$tail
$ok = $tail | Select-String -Pattern '^OK \('
if (-not $ok) { "NO 'OK (n tests)' trailer: this run is NOT evidence" ; exit 1 }
"archived: $out"