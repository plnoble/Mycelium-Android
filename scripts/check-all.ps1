<#
.SYNOPSIS
  Full check for the Mycelium Android repository: unit tests for every module, debug and release
  lint, and the debug / release / androidTest packages.

.DESCRIPTION
  One command runs everything, prints a summary table (per module and per test task: cases and
  failures; lint errors, warnings and hints; every APK with its path, size and signature state)
  and decides the exit code like this:

    * Gradle exit code != 0 (failing tests, compile errors, lint errors, ...)  -> failure
      Gradle is always invoked with --continue, so one failed task does not hide the rest;
      the exit code is still non-zero and every failure is listed in the summary
    * compile warnings, lint warnings or Gradle deprecation warnings           -> failure
    * failing or errored test cases                                           -> failure
    * an expected task did not run, or an expected report / APK is missing     -> failure
      (a missing report is never counted as zero)
    * a lint report without a summary line                                     -> failure
      (a report is never silently counted as clean)
    * lint errors or lint warnings                                             -> failure
    * lint hints (informational)                                               -> NOT a failure
      counted and shown in the lint table (ruling 2026-10-19: both gates must agree that an
      informational hint does not fail the build)

  Only a fully clean run returns 0.

  Task contract (aggregate selectors without a leading colon, so new modules are picked up without
  editing this script):
    test                              unit tests of every subproject (Android modules aggregate
                                      their enabled variants)
    :<app>:lintDebug / lintRelease    both lint variants; AGP's plain "lint" only covers the
                                      default variant, so it cannot replace these
    :<app>:assembleDebug / assembleRelease / assembleDebugAndroidTest
                                      debug package, release package (unsigned when no signing
                                      environment variable is set) and the instrumentation package

  llama.cpp sources and headers are pinned third-party code, so their warnings are exempt (see
  llm/README.md); lines whose path contains llm/vendor are counted separately from the warnings
  that must be fixed.

  The script works on Windows PowerShell 5.1 and PowerShell 7: Gradle output is captured with
  Start-Process straight into files, never through a pipeline, because in 5.1 a pipeline turns a
  native command's stderr into a terminating NativeCommandError. This file is deliberately pure
  ASCII (like the other scripts in scripts/): Windows PowerShell 5.1 reads a BOM-less file as ANSI,
  which would corrupt non-ASCII text. The summary is always printed, and failures are listed.

.PARAMETER LlamaSourceRepository
  Local clone containing the pinned llama.cpp commit (5e03bdd...), used to prepare
  llm/vendor/llama.cpp offline. The environment variable MYCELIUM_LLAMA_SOURCE works too.

.PARAMETER SkipLlamaPrepare
  Skip preparing the llama.cpp source (already prepared trees). A missing source still fails; the
  script never switches to network mode by itself.

.PARAMETER AllowNetwork
  Let Gradle reach the network (CI). Local runs default to --offline because Maven Central
  handshakes fail on this machine.

.PARAMETER Tasks
  Gradle task selectors for a subset run (debugging only). The default is the full contract above.

.PARAMETER SelfTest
  Only run the lint summary parser / gate self test and exit: no environment check, no Gradle. It
  drives the real parser with sample lint report texts (hints only, one warning, errors, "No issues
  found", no summary line) and asserts that hints never fail the gate while errors and warnings do.
  Any extra paths in SelfTestReport are parsed as well. It also checks that the Gradle arguments
  this script builds contain --continue, so one failed task cannot hide the rest.

.PARAMETER SelfTestReport
  Extra lint report files (for example a real build/reports/lint-results-debug.txt) to parse in the
  -SelfTest run; a comma-separated list works. Each one must produce a summary line and must reach
  the outcome its own counts imply, and appending a hint to its summary must not change that
  outcome. A report whose summary line cannot be found fails the self test.

.EXAMPLE
  ./scripts/check-all.ps1 -SelfTest
#>
[CmdletBinding()]
param(
    [string]$LlamaSourceRepository = $env:MYCELIUM_LLAMA_SOURCE,
    [switch]$SkipLlamaPrepare,
    [switch]$AllowNetwork,
    [string[]]$Tasks = @(),
    [switch]$SelfTest,
    [string[]]$SelfTestReport = @()
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$logDir = Join-Path $root "build\check-all"
$subsetRun = $PSBoundParameters.ContainsKey("Tasks") -and $Tasks.Count -gt 0

# Anything unexpected still has to end the run with a clear message and a non-zero exit code instead
# of a half-printed report.
trap {
    Write-Host "check-all: unexpected error: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "check-all: Gradle log (if it ran): $(Join-Path $logDir 'gradle.log')"
    exit 1
}

function Stop-WithError {
    param([string]$Message)
    Write-Host "check-all: $Message" -ForegroundColor Red
    exit 1
}

# --- lint summary parsing (shared by the real run and by -SelfTest) ----------
#
# AGP's lint text report ends with either "No issues found." or a summary line. When informational
# issues exist the summary has a third group, "<n> errors, <n> warnings, <n> hints" (and "1 hint" /
# "1 warning" / "1 error" in the singular). Ruling 2026-10-19: only errors and warnings fail the
# gate; a hint is counted and printed in the lint table but is not a failure. A report without a
# summary line is never treated as clean (this is what made this script disagree with the .sh one).
function Get-LintSummary {
    param([string]$Text)
    $errors = 0; $warnings = 0; $hints = 0; $parsed = $false
    foreach ($line in @($Text -split "`r?`n")) {
        if ($line -match '^\s*(\d+) errors?, (\d+) warnings?(?:, (\d+) hints?)?\s*$') {
            $errors = [int]$Matches[1]
            $warnings = [int]$Matches[2]
            $hints = if ($Matches[3] -eq "") { 0 } else { [int]$Matches[3] }
            $parsed = $true
        }
    }
    if (!$parsed -and $Text -match 'No issues found') { $parsed = $true }
    return [pscustomobject]@{ Errors = $errors; Warnings = $warnings; Hints = $hints; Parsed = $parsed }
}

# The gate for a single report: no summary line fails, errors and warnings fail, hints never do.
function Test-LintSummary {
    param([object]$Summary)
    if (!$Summary.Parsed) { return $false }
    return ($Summary.Errors -eq 0 -and $Summary.Warnings -eq 0)
}

# Rewrites the summary line of a lint report so that it advertises a single informational hint. Used
# by -SelfTest to prove with real report text that a hint cannot change the outcome.
function Add-LintHintToSummary {
    param([string]$Text)
    $lines = @($Text -split "`r?`n")
    $index = -1
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match '^\s*\d+ errors?, \d+ warnings?(?:, \d+ hints?)?\s*$') { $index = $i }
        elseif ($lines[$i] -match 'No issues found') { $index = $i }
    }
    if ($index -lt 0) { return $Text }
    if ($lines[$index] -match '^\s*(\d+) errors?, (\d+) warnings?') {
        $lines[$index] = "$($Matches[1]) errors, $($Matches[2]) warnings, 1 hint"
    } else {
        $lines[$index] = "0 errors, 0 warnings, 1 hint"
    }
    return ($lines -join "`r`n")
}

# The argument list actually handed to gradlew. Kept next to the real invocation so the self test
# and the run cannot drift apart: dropping --continue here is what this check fails on.
function Get-GradleArguments {
    param([bool]$Offline, [string[]]$TaskList)
    $built = @("/c", ".\gradlew.bat", "--console=plain", "--warning-mode", "all", "--continue")
    if ($Offline) { $built += "--offline" }
    $built += $TaskList
    return $built
}

function Invoke-LintSelfTest {
    param([string[]]$ExtraReport = @())

    # Sample texts covering the real AGP wording, both number forms and the missing summary line.
    $cases = @(
        @{ Name = "hints only, singular (0 errors, 0 warnings, 1 hint)";   Text = "0 errors, 0 warnings, 1 hint`n"; Pass = $true },
        @{ Name = "hints only, plural (0 errors, 0 warnings, 3 hints)";    Text = "0 errors, 0 warnings, 3 hints`n"; Pass = $true },
        @{ Name = "hints only, CRLF line ending (1 hint)";                 Text = "0 errors, 0 warnings, 1 hint`r`n"; Pass = $true },
        @{ Name = "no issues (No issues found.)";                          Text = "No issues found.`n"; Pass = $true },
        @{ Name = "zero of everything (0 errors, 0 warnings, 0 hints)";    Text = "0 errors, 0 warnings, 0 hints`n"; Pass = $true },
        @{ Name = "one warning, singular (0 errors, 1 warning, 0 hints)";  Text = "0 errors, 1 warning, 0 hints`n"; Pass = $false },
        @{ Name = "one warning, singular, no hints group (0 errors, 1 warning)"; Text = "0 errors, 1 warning`n"; Pass = $false },
        @{ Name = "two warnings, real AGP form (0 errors, 2 warnings)";    Text = "0 errors, 2 warnings`n"; Pass = $false },
        @{ Name = "one error, singular (1 error, 0 warnings, 0 hints)";    Text = "1 error, 0 warnings, 0 hints`n"; Pass = $false },
        @{ Name = "errors plus hints (2 errors, 0 warnings, 3 hints)";     Text = "2 errors, 0 warnings, 3 hints`n"; Pass = $false },
        @{ Name = "errors and warnings plus hints (1 error, 4 warnings, 2 hints)"; Text = "1 error, 4 warnings, 2 hints`n"; Pass = $false },
        @{ Name = "no summary line, issue text only";                      Text = "app/src/main/java/Foo.kt:12: Warning: unused [UnusedResources]`n"; Pass = $false },
        @{ Name = "empty report (no summary line)";                        Text = ""; Pass = $false }
    )

    $failed = 0
    Write-Host "check-all self test: lint summary parser and gate"
    Write-Host ""
    foreach ($case in $cases) {
        $summary = Get-LintSummary -Text $case.Text
        $passed = Test-LintSummary -Summary $summary
        $ok = ($passed -eq $case.Pass)
        if (!$ok) { $failed++ }
        $verdict = if ($ok) { "PASS" } else { "FAIL" }
        $got = if ($passed) { "pass" } else { "fail" }
        $want = if ($case.Pass) { "pass" } else { "fail" }
        Write-Host "$verdict  $($case.Name)"
        Write-Host "      errors=$($summary.Errors) warnings=$($summary.Warnings) hints=$($summary.Hints) parsed=$($summary.Parsed) -> $got (expected $want)"
    }

    # Real report text: the summary line must be found (the regression that made this script FAIL
    # where the .sh one passed) and appending a hint must not change the outcome. A single argument
    # may carry several comma-separated paths ("-File" cannot bind an array).
    $reportPaths = @($ExtraReport | ForEach-Object { $_ -split ',' } | Where-Object { !([string]::IsNullOrWhiteSpace($_)) })
    foreach ($path in $reportPaths) {
        if (!(Test-Path -LiteralPath $path -PathType Leaf)) {
            Write-Host "FAIL  real report not found: $path"
            $failed++
            continue
        }
        $text = Get-Content -LiteralPath $path -Raw -Encoding UTF8
        $summary = Get-LintSummary -Text $text
        $realVerdict = Test-LintSummary -Summary $summary
        $hintSummary = Get-LintSummary -Text (Add-LintHintToSummary -Text $text)
        $hintVerdict = Test-LintSummary -Summary $hintSummary
        $ok = ($summary.Parsed -and $hintSummary.Parsed -and $hintSummary.Hints -ge 1 -and $hintVerdict -eq $realVerdict)
        if (!$ok) { $failed++ }
        $verdict = if ($ok) { "PASS" } else { "FAIL" }
        $real = if ($realVerdict) { "pass" } else { "fail" }
        $hinted = if ($hintVerdict) { "pass" } else { "fail" }
        Write-Host "$verdict  real report: $path"
        Write-Host "      errors=$($summary.Errors) warnings=$($summary.Warnings) hints=$($summary.Hints) parsed=$($summary.Parsed) -> $real"
        Write-Host "      same text with '1 hint' in the summary: errors=$($hintSummary.Errors) warnings=$($hintSummary.Warnings) hints=$($hintSummary.Hints) parsed=$($hintSummary.Parsed) -> $hinted (must match)"
    }

    # --continue must be present whether or not the run is offline, and it must not depend on which
    # tasks were asked for. A script that only adds the flag on the happy path fails here.
    $offlineArgs = Get-GradleArguments -Offline $true -TaskList @("test")
    $onlineArgs = Get-GradleArguments -Offline $false -TaskList @(":net:test", ":app:testDebugUnitTest")
    $continueOk = ($offlineArgs -contains "--continue") -and ($onlineArgs -contains "--continue")
    if ($continueOk) {
        Write-Host "PASS  gradle arguments contain --continue (offline and online)"
    } else {
        $failed++
        Write-Host "FAIL  gradle arguments contain --continue (offline and online)" -ForegroundColor Red
        Write-Host "      offline: $($offlineArgs -join ' ')"
        Write-Host "      online:  $($onlineArgs -join ' ')"
    }

    Write-Host ""
    if ($failed -eq 0) {
        Write-Host "check-all self test: PASS ($($cases.Count) samples, $($reportPaths.Count) real report(s))"
        exit 0
    }
    Write-Host "check-all self test: FAIL ($failed case(s))" -ForegroundColor Red
    exit 1
}

if ($SelfTest) {
    Invoke-LintSelfTest -ExtraReport $SelfTestReport
    exit 0
}

# Runs a native command with the error preference relaxed for its duration. In Windows PowerShell
# 5.1 a native command's stderr becomes a terminating NativeCommandError while the preference is
# "Stop", which would abort the run before the summary is printed (apksigner and git both write to
# stderr on a normal, successful call).
function Invoke-NativeQuiet {
    param([string]$FilePath, [string[]]$Arguments)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & $FilePath @Arguments 2>$null
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    return [pscustomobject]@{ Output = ($output -join "`n"); ExitCode = $code }
}

function Format-Size {
    param([long]$Bytes)
    if ($Bytes -ge 1048576) { return ("{0:N1} MiB" -f ($Bytes / 1048576.0)) }
    if ($Bytes -ge 1024) { return ("{0:N1} KiB" -f ($Bytes / 1024.0)) }
    return "$Bytes B"
}

# Signature state via apksigner (authoritative for v1/v2/v3). The META-INF fallback only sees v1
# signatures, so it is used solely when apksigner is unavailable.
function Get-ApkSignatureState {
    param([string]$Path, [string]$AndroidHome)
    $apksigner = Get-ChildItem -Path (Join-Path $AndroidHome "build-tools\*\apksigner.bat") -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending | Select-Object -First 1
    if ($apksigner) {
        $result = Invoke-NativeQuiet -FilePath $apksigner.FullName -Arguments @("verify", "--print-certs", $Path)
        if ($result.ExitCode -eq 0) { return "signed" }
        return "unsigned"
    }
    try {
        Add-Type -AssemblyName System.IO.Compression.FileSystem | Out-Null
        $zip = [System.IO.Compression.ZipFile]::OpenRead($Path)
        try {
            $v1 = @($zip.Entries | Where-Object { $_.FullName -match '^META-INF/.*\.(RSA|DSA|EC)$' }).Count -gt 0
        } finally {
            $zip.Dispose()
        }
        if ($v1) { return "signed (v1 only check)" }
        return "unsigned or v2+ (no apksigner)"
    } catch {
        return "unreadable"
    }
}

# --- 1. environment ----------------------------------------------------------

if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    Stop-WithError "JAVA_HOME is not set (it should point at the Android Studio JBR)"
}
if (!(Test-Path -LiteralPath (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
    Stop-WithError "JAVA_HOME has no bin\java.exe: $($env:JAVA_HOME)"
}

$androidHome = $env:ANDROID_HOME
if ([string]::IsNullOrWhiteSpace($androidHome)) { $androidHome = $env:ANDROID_SDK_ROOT }
if ([string]::IsNullOrWhiteSpace($androidHome)) {
    Stop-WithError "ANDROID_HOME (or ANDROID_SDK_ROOT) is not set"
}
if (!(Test-Path -LiteralPath $androidHome -PathType Container)) {
    Stop-WithError "ANDROID_HOME is not a directory: $androidHome"
}

# --- 2. module list (defines the reports and artifacts that must exist) -------

$settingsText = Get-Content -LiteralPath (Join-Path $root "settings.gradle.kts") -Raw -Encoding UTF8
$modules = @()
foreach ($call in [regex]::Matches($settingsText, 'include\(([^)]*)\)')) {
    foreach ($quoted in [regex]::Matches($call.Groups[1].Value, '"(:[^"]+)"')) {
        $modules += $quoted.Groups[1].Value
    }
}
$modules = @($modules | Sort-Object -Unique)
if ($modules.Count -eq 0) { Stop-WithError "no module found in settings.gradle.kts" }

$moduleInfo = @{}
foreach ($module in $modules) {
    $directoryName = if ($module -eq ":contract") { "contract-lib" } else { $module.TrimStart(':') }
    $directory = Join-Path $root $directoryName
    $buildFile = Join-Path $directory "build.gradle.kts"
    if (!(Test-Path -LiteralPath $buildFile)) { Stop-WithError "$module has no build.gradle.kts: $buildFile" }
    $buildText = Get-Content -LiteralPath $buildFile -Raw -Encoding UTF8
    $kind = "jvm"
    if ($buildText -match 'com\.android\.application') { $kind = "app" }
    elseif ($buildText -match 'com\.android\.library') { $kind = "android-library" }
    $moduleInfo[$module] = [pscustomobject]@{
        Module        = $module
        DirectoryName = $directoryName
        Directory     = $directory
        Kind          = $kind
        HasTests      = (Test-Path -LiteralPath (Join-Path $directory "src\test"))
    }
}

$appModules = @($moduleInfo.Values | Where-Object { $_.Kind -eq "app" } | Sort-Object Module)
if ($appModules.Count -eq 0) { Stop-WithError "no application module found" }

$expectedTasks = @()
foreach ($info in $moduleInfo.Values) {
    if ($info.Kind -eq "app") {
        $expectedTasks += "$($info.Module):testDebugUnitTest", "$($info.Module):lintDebug", "$($info.Module):lintRelease",
            "$($info.Module):assembleDebug", "$($info.Module):assembleRelease", "$($info.Module):assembleDebugAndroidTest"
    } elseif ($info.Kind -eq "android-library") {
        $expectedTasks += "$($info.Module):testDebugUnitTest"
    } else {
        $expectedTasks += "$($info.Module):test"
    }
}

if (!$subsetRun) {
    # "test" has no leading colon on purpose: it selects the same-named task in every subproject,
    # so a new module is covered without touching this script. The app tasks are derived from
    # settings.gradle.kts for the same reason.
    $Tasks = @("test")
    foreach ($info in $appModules) {
        $Tasks += "$($info.Module):lintDebug", "$($info.Module):lintRelease",
            "$($info.Module):assembleDebug", "$($info.Module):assembleRelease", "$($info.Module):assembleDebugAndroidTest"
    }
}

# --- 3. llama.cpp source ------------------------------------------------------

$vendor = Join-Path $root "llm\vendor\llama.cpp"
if ($SkipLlamaPrepare) {
    if (!(Test-Path -LiteralPath $vendor -PathType Container)) {
        Stop-WithError "asked to skip preparation, but llm/vendor/llama.cpp does not exist"
    }
} else {
    if ([string]::IsNullOrWhiteSpace($LlamaSourceRepository)) {
        Stop-WithError "pass -LlamaSourceRepository or set MYCELIUM_LLAMA_SOURCE (a local clone with the pinned llama.cpp commit)"
    }
    try {
        & (Join-Path $PSScriptRoot "prepare-llama.ps1") -SourceRepository $LlamaSourceRepository
    } catch {
        Stop-WithError "preparing the llama.cpp source failed: $($_.Exception.Message)"
    }
}

# --- 4. run Gradle ------------------------------------------------------------

New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$stdoutPath = Join-Path $logDir "gradle-stdout.log"
$stderrPath = Join-Path $logDir "gradle-stderr.log"
$combinedPath = Join-Path $logDir "gradle.log"
$summaryPath = Join-Path $logDir "summary.md"

# ".\" is required: with NoDefaultCurrentDirectoryInExePath set (hardened shells, some sandboxes and
# CI images) cmd.exe does not look in the working directory for a bare "gradlew.bat".
# --continue: one module's test failure must not skip the remaining modules. The exit code stays
# non-zero, and the summary below still lists every failed task. Get-GradleArguments is what the
# self test checks, so the flag cannot be dropped here without that test going red.
$arguments = Get-GradleArguments -Offline (!$AllowNetwork) -TaskList $Tasks

Write-Host "check-all: repository $root"
Write-Host "check-all: gradlew.bat $(($arguments | Select-Object -Skip 2) -join ' ')"
try {
    # No -Wait here: in Windows PowerShell 5.1, Start-Process -Wait also waits for every descendant
    # of the started process. When this run has to start a new Gradle daemon, that daemon is such a
    # descendant and stays alive (idle timeout: hours), so the gate hung after Gradle had already
    # finished (2026-10-24). WaitForExit() waits for cmd.exe/gradlew only. Touching .Handle first
    # makes PowerShell keep the handle, otherwise ExitCode can come back empty after the exit.
    $process = Start-Process -FilePath "cmd.exe" -ArgumentList $arguments -WorkingDirectory $root `
        -NoNewWindow -PassThru -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
    $null = $process.Handle
    $process.WaitForExit()
    $gradleExit = $process.ExitCode
} catch {
    Stop-WithError "starting Gradle failed: $($_.Exception.Message)"
}

$stdout = if (Test-Path -LiteralPath $stdoutPath) { Get-Content -LiteralPath $stdoutPath -Raw -Encoding UTF8 } else { "" }
$stderr = if (Test-Path -LiteralPath $stderrPath) { Get-Content -LiteralPath $stderrPath -Raw -Encoding UTF8 } else { "" }
if ($null -eq $stdout) { $stdout = "" }
if ($null -eq $stderr) { $stderr = "" }
Set-Content -LiteralPath $combinedPath -Value ($stdout + "`r`n" + $stderr) -Encoding UTF8
$lines = @(($stdout + "`r`n" + $stderr) -split "`r?`n")

# --- 5. summary: task states, tests, lint, APKs, warnings ---------------------

$taskStates = @{}
foreach ($line in $lines) {
    if ($line -match '^> Task (:\S+) (UP-TO-DATE|SKIPPED|FROM-CACHE|FAILED|NO-SOURCE)$') {
        $taskStates[$Matches[1]] = $Matches[2]
    } elseif ($line -match '^> Task (:\S+)$') {
        $taskStates[$Matches[1]] = "executed"
    }
}

# Reports are read only for tasks that took part in this run: a report left over from an earlier
# run must never be counted (and a missing report is never zero).
$testRows = @()
$unreadableReports = @()
foreach ($file in @(Get-ChildItem -Path (Join-Path $root "*\build\test-results\*\TEST-*.xml") -ErrorAction SilentlyContinue)) {
    $relative = $file.FullName.Substring($root.Length + 1)
    $parts = $relative -split '\\'
    $module = ":$($parts[0])"
    if ($parts[0] -eq "contract-lib") { $module = ":contract" }
    $taskName = $parts[3]
    $fullTaskName = "$($module):$taskName"
    if (!$taskStates.ContainsKey($fullTaskName)) { continue }
    $tests = 0; $failures = 0; $errors = 0; $skipped = 0
    try {
        $document = New-Object System.Xml.XmlDocument
        $document.Load($file.FullName)
        $suites = @()
        if ($document.testsuites) { $suites = @($document.testsuites.testsuite) }
        elseif ($document.testsuite) { $suites = @($document.testsuite) }
        foreach ($suite in $suites) {
            $tests += [int]$suite.tests
            $failures += [int]$suite.failures
            $errors += [int]$suite.errors
            $skipped += [int]$suite.skipped
        }
    } catch {
        $unreadableReports += $relative
    }
    $existing = $testRows | Where-Object { $_.Module -eq $module -and $_.Task -eq $taskName }
    if ($existing) {
        $existing.Tests += $tests; $existing.Failures += $failures; $existing.Errors += $errors; $existing.Skipped += $skipped
    } else {
        $testRows += [pscustomobject]@{ Module = $module; Task = $taskName; Tests = $tests; Failures = $failures; Errors = $errors; Skipped = $skipped }
    }
}
$testRows = @($testRows | Sort-Object Module, Task)

$lintRows = @()
$lintIssueLines = @()
foreach ($file in @(Get-ChildItem -Path (Join-Path $root "*\build\reports\lint-results-*.txt" -ErrorAction SilentlyContinue))) {
    $relative = $file.FullName.Substring($root.Length + 1)
    $parts = $relative -split '\\'
    $module = ":$($parts[0])"
    if ($parts[0] -eq "contract-lib") { $module = ":contract" }
    $variant = ($file.BaseName -replace '^lint-results-', '')
    $variantSuffix = $variant.Substring(0, 1).ToUpper() + $variant.Substring(1)
    $fullTaskName = "$($module):lint$variantSuffix"
    if (!$taskStates.ContainsKey($fullTaskName)) { continue }
    $text = Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8
    $summary = Get-LintSummary -Text $text
    foreach ($issue in @($text -split "`r?`n" | Where-Object { $_ -match ': (Error|Warning): ' })) {
        $lintIssueLines += "$module($variant): $issue"
    }
    $lintRows += [pscustomobject]@{
        Module   = $module
        Variant  = $variant
        Errors   = $summary.Errors
        Warnings = $summary.Warnings
        Hints    = $summary.Hints
        Parsed   = $summary.Parsed
        Path     = $file.FullName
    }
}
$lintRows = @($lintRows | Sort-Object Module, Variant)

$apks = @()
foreach ($info in $appModules) {
    foreach ($file in @(Get-ChildItem -Path (Join-Path $info.Directory "build\outputs\apk") -Recurse -Filter "*.apk" -ErrorAction SilentlyContinue | Sort-Object FullName)) {
        $apks += [pscustomobject]@{
            Module    = $info.Module
            Name      = $file.Name
            Size      = $file.Length
            Signature = (Get-ApkSignatureState -Path $file.FullName -AndroidHome $androidHome)
            Path      = $file.FullName
        }
    }
}

# Warning scan: llama.cpp (llm/vendor) is exempt, everything else must be fixed.
$vendorPattern = 'llm[\\/]vendor'
$warningPatterns = @(
    '^w: ',                                  # Kotlin compiler warning
    'warning:',                              # javac / clang / CMake warning
    'CMake Warning',
    'has been deprecated',
    'is deprecated',
    'Deprecated Gradle features were used'
)
$warningLines = @()
$exemptLines = @()
$errorLines = @()
foreach ($line in $lines) {
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    if ($line -match '^e: ') { $errorLines += $line; continue }
    $matched = $false
    foreach ($pattern in $warningPatterns) {
        if ($line -match $pattern) { $matched = $true; break }
    }
    if (!$matched) { continue }
    if ($line -match $vendorPattern) { $exemptLines += $line } else { $warningLines += $line }
}

# --- 6. verify that expected tasks ran and expected artifacts exist -----------

$problems = @()
if ($gradleExit -ne 0) { $problems += "Gradle exit code $gradleExit (build or tests failed)" }

# A failed task is always reported, whether this is a full run or a subset run.
foreach ($task in @($taskStates.Keys | Sort-Object)) {
    if ($taskStates[$task] -eq "FAILED") { $problems += "task failed: $task" }
}

# Gradle 9 folds compiler diagnostics into its problems report and the plain console only shows the
# summary line, so the failure reason is taken from the "What went wrong" block instead.
$failureReason = @()
$inReason = $false
foreach ($line in $lines) {
    if ($line -match '^\* What went wrong:') { $inReason = $true; continue }
    if ($inReason) {
        if ($line -match '^\* Try:') { break }
        if ($line.Trim().Length -gt 0) { $failureReason += $line.Trim() }
    }
}
foreach ($line in $lines) {
    if ($line -match '^Problem found: ') { $failureReason += $line.Trim() }
}

if (!$subsetRun) {
    foreach ($task in $expectedTasks) {
        if (!$taskStates.ContainsKey($task)) { $problems += "expected task did not run: $task" }
    }
    foreach ($info in $moduleInfo.Values) {
        if ($info.HasTests) {
            $rows = @($testRows | Where-Object { $_.Module -eq $info.Module })
            if ($rows.Count -eq 0) { $problems += "test sources but no test report: $($info.Module)" }
        }
    }
    foreach ($info in $appModules) {
        $moduleApks = @($apks | Where-Object { $_.Module -eq $info.Module })
        foreach ($expectation in @(
                @{ Name = "debug APK"; Pattern = "outputs\apk\debug\" },
                @{ Name = "release APK"; Pattern = "outputs\apk\release\" },
                @{ Name = "androidTest APK"; Pattern = "outputs\apk\androidTest\debug\" })) {
            if (@($moduleApks | Where-Object { $_.Path -like "*$($expectation.Pattern)*" }).Count -eq 0) {
                $problems += "$($info.Module) is missing the $($expectation.Name) ($($expectation.Pattern))"
            }
        }
        foreach ($variant in @("debug", "release")) {
            $variantSuffix = $variant.Substring(0, 1).ToUpper() + $variant.Substring(1)
            $lintTask = "$($info.Module):lint$variantSuffix"
            if ($taskStates.ContainsKey($lintTask) -and $taskStates[$lintTask] -ne "SKIPPED") {
                $report = Join-Path $info.Directory "build\reports\lint-results-$variant.txt"
                if (!(Test-Path -LiteralPath $report)) { $problems += "lint ran but wrote no report: $lintTask ($report)" }
            }
        }
    }
}

foreach ($row in $lintRows) {
    if (!$row.Parsed) { $problems += "lint report has no summary line: $($row.Path)" }
}
foreach ($report in $unreadableReports) { $problems += "unreadable test report: $report" }

$totalTests = 0; $totalFailures = 0; $totalErrors = 0
foreach ($row in $testRows) { $totalTests += $row.Tests; $totalFailures += $row.Failures; $totalErrors += $row.Errors }
$totalLintErrors = 0; $totalLintWarnings = 0; $totalLintHints = 0
foreach ($row in $lintRows) {
    $totalLintErrors += $row.Errors
    $totalLintWarnings += $row.Warnings
    $totalLintHints += $row.Hints
}

if ($totalFailures -gt 0) { $problems += "$totalFailures failing test cases" }
if ($totalErrors -gt 0) { $problems += "$totalErrors errored test cases" }
# Hints are informational: counted and printed in the lint table, never added to $problems
# (ruling 2026-10-19). Errors and warnings keep failing the gate as before.
if ($totalLintErrors -gt 0) { $problems += "$totalLintErrors lint errors" }
if ($totalLintWarnings -gt 0) { $problems += "$totalLintWarnings lint warnings" }
if ($warningLines.Count -gt 0) { $problems += "$($warningLines.Count) warnings in the build output" }

$passed = $problems.Count -eq 0

# --- 7. print the summary -----------------------------------------------------

$executedTasks = @($taskStates.Keys | Where-Object { $taskStates[$_] -eq "executed" }).Count
$upToDateTasks = @($taskStates.Keys | Where-Object { $taskStates[$_] -eq "UP-TO-DATE" }).Count

$report = New-Object System.Collections.Generic.List[string]
$report.Add("# check-all summary ($(Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))")
$report.Add("")
$report.Add("- repository: $root")
$report.Add("- commit: $((Invoke-NativeQuiet -FilePath "git" -Arguments @("-C", $root, "rev-parse", "HEAD")).Output)")
$report.Add("- worktree: $(if ((Invoke-NativeQuiet -FilePath "git" -Arguments @("-C", $root, "status", "--porcelain")).Output.Trim().Length -eq 0) { 'clean' } else { 'has uncommitted changes' })")
$report.Add("- run: $(if ($subsetRun) { 'SUBSET (not the full gate)' } else { 'full check' })")
$report.Add("- mode: $(if ($AllowNetwork) { 'network allowed' } else { 'offline --offline' })")
$report.Add("- JAVA_HOME: $($env:JAVA_HOME)")
$report.Add("- ANDROID_HOME: $androidHome")
$report.Add("- gradle tasks: $($Tasks -join ' ')")
$report.Add("- gradle exit code: $gradleExit")
$report.Add("- tasks: $executedTasks executed, $upToDateTasks up-to-date")
$report.Add("")
$report.Add("## unit tests")
$report.Add("")
$report.Add("| module | test task | cases | failures | errors | skipped |")
$report.Add("|---|---|---|---|---|---|")
foreach ($row in $testRows) {
    $report.Add("| $($row.Module) | $($row.Task) | $($row.Tests) | $($row.Failures) | $($row.Errors) | $($row.Skipped) |")
}
$report.Add("| **total** | | **$totalTests** | **$totalFailures** | **$totalErrors** | |")
$report.Add("")
$report.Add("## lint")
$report.Add("")
$report.Add("| module | variant | errors | warnings | hints |")
$report.Add("|---|---|---|---|---|")
foreach ($row in $lintRows) {
    $report.Add("| $($row.Module) | $($row.Variant) | $($row.Errors) | $($row.Warnings) | $($row.Hints) |")
}
$report.Add("| **total** | | **$totalLintErrors** | **$totalLintWarnings** | **$totalLintHints** |")
$report.Add("")
$report.Add("- lint hints (informational, counted and shown above, never a failure): $totalLintHints")
$report.Add("")
$report.Add("## artifacts")
$report.Add("")
$report.Add("| APK | size | signature | path |")
$report.Add("|---|---|---|---|")
foreach ($apk in $apks) {
    $report.Add("| $($apk.Name) | $(Format-Size $apk.Size) | $($apk.Signature) | $($apk.Path) |")
}
$report.Add("")
$report.Add("## expected task states")
$report.Add("")
$report.Add("| task | state |")
$report.Add("|---|---|")
foreach ($task in $expectedTasks) {
    $state = if ($taskStates.ContainsKey($task)) { $taskStates[$task] } else { "did not run" }
    $report.Add("| $task | $state |")
}
$report.Add("")
$report.Add("## warnings")
$report.Add("")
$report.Add("- warnings to fix: $($warningLines.Count)")
$report.Add("- llama.cpp warnings (agreed exemption): $($exemptLines.Count)")
$report.Add("- Kotlin compiler error lines: $($errorLines.Count)")
$report.Add("")
$report.Add("## result")
$report.Add("")
if ($passed) {
    $report.Add("**PASS**: 0 failures, 0 warnings, exit code 0 (lint hints: $totalLintHints, informational, not a failure).")
} else {
    $report.Add("**FAIL**:")
    foreach ($problem in $problems) { $report.Add("- $problem") }
}
if ($failureReason.Count -gt 0) {
    $report.Add("")
    $report.Add("## failure reason (from the Gradle output)")
    $report.Add("")
    foreach ($line in @($failureReason | Select-Object -Unique | Select-Object -First 25)) { $report.Add("- $line") }
    $report.Add("")
    $report.Add("Kotlin diagnostics with file and line numbers need --info; Gradle also writes")
    $report.Add("build/reports/problems/problems-report.html.")
}
$report.Add("")
$report.Add("full Gradle output: $combinedPath")
$reportText = $report -join "`r`n"
Set-Content -LiteralPath $summaryPath -Value $reportText -Encoding UTF8
Write-Host ""
Write-Host $reportText

if ($warningLines.Count -gt 0) {
    Write-Host ""
    Write-Host "== warnings to fix (first 20) =="
    $warningLines | Select-Object -First 20 | ForEach-Object { Write-Host "  $_" }
}
if ($failureReason.Count -gt 0) {
    Write-Host ""
    Write-Host "== failure reason (first 25 lines) =="
    $failureReason | Select-Object -Unique | Select-Object -First 25 | ForEach-Object { Write-Host "  $_" }
}
if ($errorLines.Count -gt 0) {
    Write-Host ""
    Write-Host "== compiler errors (first 20) =="
    $errorLines | Select-Object -First 20 | ForEach-Object { Write-Host "  $_" }
}
if ($lintIssueLines.Count -gt 0) {
    Write-Host ""
    Write-Host "== lint issues (first 20) =="
    $lintIssueLines | Select-Object -First 20 | ForEach-Object { Write-Host "  $_" }
}
if ($exemptLines.Count -gt 0) {
    Write-Host ""
    Write-Host "== llama.cpp warnings (exempt, first 5) =="
    $exemptLines | Select-Object -First 5 | ForEach-Object { Write-Host "  $_" }
}

if ($passed) { exit 0 } else { exit 1 }