# Prepares the walkthrough APK for work package 1.3.2: the same debug package
# (com.xavierxia.mycelium.debug) but with versionCode 6, so the app can install it over the
# versionCode 5 debug build. The APK is never committed; it goes outside the repo and
# then into the app's own files dir on the phone (via run-as, no storage permission needed).
#
# ASCII only: PowerShell 5.1 reads .ps1 as ANSI and Chinese comments break the parser.
$ErrorActionPreference = 'Stop'
# The repository is the one this script lives in (scripts/device/..): the prepared APK then
# belongs to the checkout under review, not to some other clone.
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$work = if ($env:MYCELIUM_DEVICE_EVIDENCE) { $env:MYCELIUM_DEVICE_EVIDENCE } else { Join-Path $env:TEMP 'mycelium-device-evidence' }
New-Item -ItemType Directory -Force -Path $work | Out-Null
$apkOut = Join-Path $work 'update-flow-v6-debug.apk'
$gradle = Join-Path $repo 'app\build.gradle.kts'
$backup = Join-Path $work 'app-build-gradle.kts.before-v6'

if (-not $env:JAVA_HOME) { throw 'set JAVA_HOME to the JDK Gradle should use' }
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:PATH"

Copy-Item $gradle $backup -Force
$text = [System.IO.File]::ReadAllText($gradle)
$bumped = [regex]::Replace($text, 'versionCode = \d+', 'versionCode = 6', 1)
if ($bumped -eq $text) { throw 'could not find versionCode in app/build.gradle.kts' }
[System.IO.File]::WriteAllText($gradle, $bumped, (New-Object System.Text.UTF8Encoding($false)))
try {
    & cmd /c "cd /d `"$repo`" && .\gradlew.bat --console=plain :app:assembleDebug --offline" 2>&1 |
        Select-String -Pattern '^e: |FAILED|BUILD ' | ForEach-Object { $_.Line.Trim() }
    if ($LASTEXITCODE -ne 0) { throw "assembleDebug failed with exit $LASTEXITCODE" }
    Copy-Item (Join-Path $repo 'app\build\outputs\apk\debug\app-debug.apk') $apkOut -Force
    "built: $apkOut ($([math]::Round((Get-Item $apkOut).Length / 1MB, 1)) MiB)"
} finally {
    Copy-Item $backup $gradle -Force
    'app/build.gradle.kts restored'
}

# Push to the phone only when one is attached.
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
$devices = & $adb devices | Select-String -Pattern '\sdevice$'
if (-not $devices) {
    'no device attached: skipping the on-device copy (rerun with a phone connected)'
    exit 0
}
& $adb push $apkOut /data/local/tmp/update-flow-v6.apk | Select-Object -Last 1
& $adb shell run-as com.xavierxia.mycelium.debug cp /data/local/tmp/update-flow-v6.apk files/update-flow-v6.apk
& $adb shell run-as com.xavierxia.mycelium.debug ls -l files/update-flow-v6.apk