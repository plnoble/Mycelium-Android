<#
.SYNOPSIS
  Pre-push public-content check for the Mycelium Android repository.

.DESCRIPTION
  Scans everything that would become public with the next push:

    * every file tracked at HEAD (its content and its file name), read from HEAD itself: the run
      refuses to report when the working tree is dirty, because a dirty tree can hide what HEAD
      contains;
    * every commit in origin/main..HEAD (the diff text and the changed file names), so a value
      that only ever existed somewhere in the branch history still fails.

  The run fails (exit code 1) on:

    * private key headers (PEM, OpenSSH, PGP);
    * long API tokens (sk-, hf_, ghp_, github_pat_, glpat-, AKIA...);
    * paths that carry a user name: [drive]:\Users\<name>, /home/<name>, /Users/<name>;
    * tracked files whose name is a keystore, *.jks, *.gguf or *.apk (or another secret file
      name such as .env, local.properties, keystore.properties, id_rsa);
    * every line that matches the local denylist (.local/public-denylist.txt, never committed).

  Other local absolute paths ([drive]:\...) are only listed, so the coordinator can look at them
  one by one; they do not change the exit code.

  Output never prints the text that matched: only the category, the file and the line number.
  A local denylist hit is reported as a line number only, without the word. The report prints the
  baseline ref and its SHA, the HEAD SHA and the number of commits in the range, so an empty range
  (HEAD equals the baseline) cannot be mistaken for proof that the published history is clean.

  The script is deliberately pure ASCII (like the other scripts in scripts/): Windows PowerShell
  5.1 reads a BOM-less file as ANSI, which would corrupt non-ASCII text. It runs on Windows
  PowerShell 5.1 and PowerShell 7 - no ternary (? :) and no null-coalescing (??) operator.

  Usage:
    powershell -File scripts/check-public.ps1
      the real check, run before pushing
    powershell -File scripts/check-public.ps1 -SelfTest
      prove that deliberately bad samples fail and clean samples pass (temporary directory only)

.PARAMETER SelfTest
  Build deliberately bad and deliberately clean samples in a temporary directory, run this script
  against them in separate PowerShell processes and assert the exit codes and the reported
  categories. Nothing is written inside the repository, and the samples are deleted afterwards.

.PARAMETER ScanDirectory
  Test-only override: scan this file or directory tree instead of the repository. The git history
  is not scanned in this mode. The self-test uses it.

.PARAMETER DenylistFile
  Local word list, one word per line, '#' starts a comment. Default:
  <repository>\.local\public-denylist.txt. The file is never committed.

.PARAMETER Full
  Print every finding instead of the first 20 lines of each category.
#>
[CmdletBinding()]
param(
    [switch]$SelfTest,
    [string]$ScanDirectory = "",
    [string]$DenylistFile = "",
    [switch]$Full
)

$ErrorActionPreference = "Stop"

$scriptPath = $PSCommandPath
$repoRoot = Split-Path -Parent $PSScriptRoot
$printLimit = 20
if ($Full) { $printLimit = 100000 }
if ([string]::IsNullOrWhiteSpace($DenylistFile)) {
    $DenylistFile = Join-Path $repoRoot ".local\public-denylist.txt"
}
$directoryMode = -not [string]::IsNullOrWhiteSpace($ScanDirectory)

# Anything unexpected ends the run with a clear message and exit code 1 instead of a half-printed
# report that might look like a pass.
trap {
    Write-Host "check-public: unexpected error: $($_.Exception.Message)" -ForegroundColor Red
    $position = $_.InvocationInfo.PositionMessage
    if ($null -ne $position) {
        foreach ($line in @($position -split "`r?`n")) {
            if ($line.Trim().Length -gt 0) { Write-Host ("check-public: at " + $line.Trim()) -ForegroundColor Red }
        }
    }
    exit 1
}

function Stop-WithError {
    param([string]$Message)
    Write-Host "check-public: $Message" -ForegroundColor Red
    exit 1
}

# --- patterns -----------------------------------------------------------------
#
# The patterns are assembled from fragments, or use a character class that cannot occur inside
# this file's own source text. A scanner that carries its own pattern verbatim matches itself: the
# /home/ and /Users/ prefixes are therefore built from $slash, and the user name class is narrow
# enough that documented pattern text such as "/home/[^/\s]+" does not look like a real path.
# This matters twice over, because this file is tracked and the commit that adds it is inside
# origin/main..HEAD.

$slash = "/"
# Windows user names may start with non-ASCII characters (a Chinese name, for example), so the name
# class accepts the ASCII name characters plus every non-ASCII character, instead of only
# [A-Za-z0-9._-]. Punctuation stays out, which keeps documented pattern text such as
# "[home]/[^/\s]+" or the trailing "`" of a "C:\Users\" example from looking like a real name.
$nameChars = '(?:[A-Za-z0-9._-]|[^\x00-\x7F])+'
$privateKeyPattern = "-{5}BEGIN[A-Z ]*PRIVATE KEY[A-Z ]*-{5}"
$tokenPatterns = @(
    "sk-[A-Za-z0-9_-]{20,}",
    "hf_[A-Za-z0-9]{20,}",
    "ghp_[A-Za-z0-9]{20,}",
    "github_pat_[A-Za-z0-9_]{20,}",
    "glpat-[A-Za-z0-9_-]{20,}",
    "AKIA[0-9A-Z]{16}"
)
$userPathPatterns = @(
    ("[A-Za-z]:\\Users\\" + $nameChars),
    ($slash + "home" + $slash + $nameChars),
    ($slash + "Users" + $slash + $nameChars)
)
$scanPattern = "(?<privatekey>" + $privateKeyPattern + ")|" +
    "(?<token>" + ($tokenPatterns -join "|") + ")|" +
    "(?<userpath>" + ($userPathPatterns -join "|") + ")|" +
    "(?<localpath>[A-Za-z]:\\(?!Users\\))"

$sensitiveExtensions = @(".keystore", ".jks", ".gguf", ".apk", ".pem", ".p12", ".pfx", ".key")
$sensitiveNames = @(
    "local.properties", "keystore.properties", "signing.properties", "secrets.properties",
    "id_rsa", "id_ed25519"
)

$categoryOrder = @("private-key", "token", "user-path", "sensitive-file", "local-denylist", "unreadable", "local-path")
$categoryBlocking = @{
    "private-key"    = $true
    "token"          = $true
    "user-path"      = $true
    "sensitive-file" = $true
    "local-denylist" = $true
    "unreadable"     = $true
    "local-path"     = $false
}

# --- findings -----------------------------------------------------------------

$script:findings = @()
$script:seenFindings = @{}

function Add-Finding {
    param([string]$Category, [string]$Location)
    $key = $Category + "|" + $Location
    if ($script:seenFindings.ContainsKey($key)) { return }
    $script:seenFindings[$key] = $true
    $script:findings += [pscustomobject]@{ Category = $Category; Location = $Location }
}

# --- helpers ------------------------------------------------------------------

function Invoke-Git {
    param([string[]]$Arguments)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & git -C $repoRoot -c core.quotepath=false @Arguments 2>$null
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    if ($null -eq $code) { $code = -1 }
    # @($null) is an array with one element, which would make an empty git answer look like a
    # one-line answer.
    return [pscustomobject]@{ Output = @($output | Where-Object { $null -ne $_ }); ExitCode = $code }
}

# True when the file name itself must not be published (keystore, model, package, secret file).
function Test-SensitiveName {
    param([string]$Path)
    $name = $Path
    $index = [Math]::Max($Path.LastIndexOf("\"), $Path.LastIndexOf("/"))
    if ($index -ge 0) { $name = $Path.Substring($index + 1) }
    if ($name.Length -eq 0) { return $false }
    $lower = $name.ToLowerInvariant()
    foreach ($extension in $sensitiveExtensions) {
        if ($lower.EndsWith($extension)) { return $true }
    }
    if ($lower -eq ".env" -or $lower.StartsWith(".env.")) { return $true }
    foreach ($sensitiveName in $sensitiveNames) {
        if ($lower -eq $sensitiveName) { return $true }
    }
    return $false
}

# Reads one file as scan units (one unit per line). A file with a NUL byte in its first 8 KiB is
# counted as binary and skipped; such a file is reported through its name, not through its bytes.
function Read-ScanUnits {
    param([string]$FullPath, [string]$DisplayPath, [ref]$SkippedBinary)
    try {
        $bytes = [System.IO.File]::ReadAllBytes($FullPath)
    } catch {
        Add-Finding "unreadable" $DisplayPath
        return @()
    }
    $probe = [Math]::Min($bytes.Length, 8000)
    for ($i = 0; $i -lt $probe; $i++) {
        if ($bytes[$i] -eq 0) {
            $SkippedBinary.Value = $SkippedBinary.Value + 1
            return @()
        }
    }
    $text = [System.Text.Encoding]::UTF8.GetString($bytes)
    $lines = @($text -split "`r?`n")
    $units = New-Object System.Collections.Generic.List[object]
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $units.Add([pscustomobject]@{ Location = $DisplayPath; Number = ($i + 1); Text = $lines[$i] })
    }
    return $units.ToArray()
}

# Scans a batch of units. Every unit knows where it came from, so a finding is reported as
# "location:line" and never as the matching text.
function Test-ScanUnits {
    param([object[]]$Units, [string[]]$DenyWords)
    foreach ($unit in $Units) {
        $text = $unit.Text
        if ([string]::IsNullOrEmpty($text)) { continue }
        $location = "$($unit.Location):$($unit.Number)"
        foreach ($match in [regex]::Matches($text, $scanPattern)) {
            if ($match.Groups["privatekey"].Success) { Add-Finding "private-key" $location }
            elseif ($match.Groups["token"].Success) { Add-Finding "token" $location }
            elseif ($match.Groups["userpath"].Success) { Add-Finding "user-path" $location }
            elseif ($match.Groups["localpath"].Success) { Add-Finding "local-path" $location }
        }
        if ($DenyWords.Count -gt 0) {
            foreach ($word in $DenyWords) {
                if ($text.IndexOf($word, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
                    Add-Finding "local-denylist" $location
                    break
                }
            }
        }
    }
}

function Read-Denylist {
    param([string]$Path)
    if (!(Test-Path -LiteralPath $Path -PathType Leaf)) { return @() }
    $words = New-Object System.Collections.Generic.List[string]
    foreach ($line in @(Get-Content -LiteralPath $Path -Encoding UTF8)) {
        $word = $line.Trim()
        if ($word.Length -eq 0) { continue }
        if ($word.StartsWith("#")) { continue }
        $words.Add($word)
    }
    return $words.ToArray()
}

# --- self test ----------------------------------------------------------------
#
# Every sample is built from pieces at run time, so this file never contains the patterns it
# hunts: a sample that lived in this source text would turn the repository scan into a hit.

function Invoke-SelfTest {
    $selfTestRoot = Join-Path $env:TEMP ("check-public-selftest-" + [guid]::NewGuid().ToString("N"))
    $checks = New-Object System.Collections.Generic.List[object]
    $hostExeName = "powershell.exe"
    if ($PSVersionTable.PSVersion.Major -ge 6) { $hostExeName = "pwsh.exe" }
    $hostExe = Join-Path $PSHOME $hostExeName
    if (!(Test-Path -LiteralPath $hostExe)) { $hostExe = $hostExeName }

    try {
        New-Item -ItemType Directory -Force -Path $selfTestRoot | Out-Null

        function New-Sample {
            param([string]$RelativePath, [string]$Content)
            # Neither name is $full: PowerShell variable names are case-insensitive, and $full
            # would be the [switch]$Full parameter of this script.
            $samplePath = Join-Path $selfTestRoot $RelativePath
            $parent = Split-Path -Parent $samplePath
            if (!(Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
            # UTF-8 without a BOM, written through .NET: Set-Content -Encoding UTF8 would add a BOM
            # on Windows PowerShell 5.1.
            [System.IO.File]::WriteAllText($samplePath, $Content, (New-Object System.Text.UTF8Encoding($false)))
        }

        $five = "-" * 5
        $sampleUser = "sample" + "user"
        $sampleToken = "sk" + "-" + ("A" * 40)
        $sampleGithubToken = "ghp" + "_" + ("B" * 36)
        $sampleDenyWord = "sample" + "deny" + "word"
        $sampleDrive = "D"

        $privateKeyHeader = $five + "BEGIN RSA PRIVATE KEY" + $five
        # The key is fake, but the header is the real shape: that is all the scan looks for.
        $privateKeySample = $privateKeyHeader + "`n" +
            "MIIEfakefakefakefakefakefakefakefakefakefakefakefake" + "`n" +
            $five + "END RSA PRIVATE KEY" + $five + "`n"
        $tokenSample = "tokens that must never be pushed" + "`n" + $sampleToken + "`n" + $sampleGithubToken + "`n"
        $userPathForbid = $sampleDrive + ":" + "\" + "Users" + "\" + $sampleUser
        $userPathSample = $userPathForbid + "\" + "project" + "\" + "readme.txt" + "`n" +
            $slash + "home" + $slash + $sampleUser + "/work/app.log" + "`n"
        $listedPathForbid = $sampleDrive + ":" + "\" + "work"
        $listedPathSample = $listedPathForbid + "\" + "notes.txt" + "`n"
        # A Windows user name that starts with non-ASCII characters (a Chinese name): the class used
        # to be [A-Za-z0-9._-], which missed these entirely. Built from code points so this file
        # stays pure ASCII.
        $chineseUser = [string]([char]0x5F20) + [string]([char]0x4F1F)
        $chineseUserForbid = $sampleDrive + ":" + "\" + "Users" + "\" + $chineseUser
        $chineseUserSample = $chineseUserForbid + "\" + "project" + "`n"

        New-Sample "clean/notes.md" "A clean note.`nNothing to see here.`n"
        New-Sample "clean/second.txt" "Second clean file.`n"
        # Non-ASCII content written from character codes, so that this script stays pure ASCII
        # while the sample itself is a real BOM-less UTF-8 file with Chinese text.
        $chineseSample = "UTF-8 sample line: " + [char]0x4E2D + [char]0x6587 + " / " +
            [char]0x6D4B + [char]0x8BD5 + "`n" + "All clear.`n"
        New-Sample "clean-chinese/notes.txt" $chineseSample
        New-Sample "listed-path/sample.txt" $listedPathSample
        New-Sample "dirty-private-key/note.txt" $privateKeySample
        New-Sample "dirty-token/note.txt" $tokenSample
        New-Sample "dirty-user-path/note.txt" $userPathSample
        New-Sample "dirty-user-path-chinese/note.txt" $chineseUserSample
        New-Sample "dirty-name-keystore/release.keystore" ""
        New-Sample "dirty-name-jks/keystore.jks" ""
        New-Sample "dirty-name-gguf/model.gguf" ""
        New-Sample "dirty-name-apk/app.apk" ""
        New-Sample "deny-hit/note.txt" ("a line with " + $sampleDenyWord + " inside" + "`n")
        New-Sample "deny-clean/note.txt" ("a clean line" + "`n")

        $denyFilePath = Join-Path $selfTestRoot "denylist.txt"
        [System.IO.File]::WriteAllText($denyFilePath, ("# comment line`n" + $sampleDenyWord + "`n"),
            (New-Object System.Text.UTF8Encoding($false)))

        function Invoke-ScanChild {
            param([string]$Target, [string]$Deny, [int]$Index)
            $outFile = Join-Path $selfTestRoot ("out-" + $Index + ".txt")
            $errFile = Join-Path $selfTestRoot ("err-" + $Index + ".txt")
            $arguments = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", ('"' + $scriptPath + '"'),
                "-ScanDirectory", ('"' + $Target + '"'))
            if (![string]::IsNullOrWhiteSpace($Deny)) {
                $arguments += @("-DenylistFile", ('"' + $Deny + '"'))
            }
            $process = Start-Process -FilePath $hostExe -ArgumentList $arguments -NoNewWindow -Wait -PassThru `
                -RedirectStandardOutput $outFile -RedirectStandardError $errFile
            $text = ""
            if (Test-Path -LiteralPath $outFile) {
                $stdout = Get-Content -LiteralPath $outFile -Raw -Encoding UTF8
                if ($null -ne $stdout) { $text = $stdout }
            }
            if (Test-Path -LiteralPath $errFile) {
                $stderr = Get-Content -LiteralPath $errFile -Raw -Encoding UTF8
                if ($null -ne $stderr) { $text = $text + "`n" + $stderr }
            }
            return [pscustomobject]@{ ExitCode = $process.ExitCode; Text = $text }
        }

        $samples = @(
            @{ Name = "clean-files-pass"; Dir = "clean"; Deny = $false; Expect = 0; Category = ""; Forbid = "" },
            @{ Name = "clean-utf8-chinese-file-passes"; Dir = "clean-chinese"; Deny = $false; Expect = 0; Category = ""; Forbid = "" },
            @{ Name = "listed-local-path-passes"; Dir = "listed-path"; Deny = $false; Expect = 0; Category = "local-path"; Forbid = $listedPathForbid },
            @{ Name = "fake-private-key-fails"; Dir = "dirty-private-key"; Deny = $false; Expect = 1; Category = "private-key"; Forbid = $privateKeyHeader },
            @{ Name = "fake-token-fails"; Dir = "dirty-token"; Deny = $false; Expect = 1; Category = "token"; Forbid = $sampleToken },
            @{ Name = "user-name-path-fails"; Dir = "dirty-user-path"; Deny = $false; Expect = 1; Category = "user-path"; Forbid = $userPathForbid },
            @{ Name = "chinese-user-name-path-fails"; Dir = "dirty-user-path-chinese"; Deny = $false; Expect = 1; Category = "user-path"; Forbid = $chineseUserForbid },
            @{ Name = "tracked-keystore-fails"; Dir = "dirty-name-keystore"; Deny = $false; Expect = 1; Category = "sensitive-file"; Forbid = "" },
            @{ Name = "tracked-jks-fails"; Dir = "dirty-name-jks"; Deny = $false; Expect = 1; Category = "sensitive-file"; Forbid = "" },
            @{ Name = "tracked-gguf-fails"; Dir = "dirty-name-gguf"; Deny = $false; Expect = 1; Category = "sensitive-file"; Forbid = "" },
            @{ Name = "tracked-apk-fails"; Dir = "dirty-name-apk"; Deny = $false; Expect = 1; Category = "sensitive-file"; Forbid = "" },
            @{ Name = "denylist-word-fails"; Dir = "deny-hit"; Deny = $true; Expect = 1; Category = "local-denylist"; Forbid = $sampleDenyWord },
            @{ Name = "denylist-miss-passes"; Dir = "deny-clean"; Deny = $true; Expect = 0; Category = ""; Forbid = $sampleDenyWord }
        )

        # Known-clean real files: the scanner itself and the two documents that quote its own
        # patterns. A pass here is the proof that the scanner does not match its own pattern text.
        $knownFiles = @(
            @{ Name = "scanner-does-not-match-itself"; Path = $scriptPath },
            @{ Name = "plan-document-passes"; Path = (Join-Path $repoRoot "docs\reviews\2026-10-18-P2-2b\PLAN.md") },
            @{ Name = "work-package-document-passes"; Path = (Join-Path $repoRoot "docs\work-packages\P2-2b-release-ready.md") }
        )
        foreach ($known in $knownFiles) {
            if (Test-Path -LiteralPath $known.Path -PathType Leaf) {
                $samples += @{ Name = $known.Name; Dir = ""; File = $known.Path; Deny = $false; Expect = 0; Category = ""; Forbid = "" }
            }
        }

        $index = 0
        foreach ($sample in $samples) {
            $index = $index + 1
            $target = Join-Path $selfTestRoot $sample.Dir
            if ($sample.ContainsKey("File")) { $target = $sample.File }
            $denyForCase = ""
            if ($sample.Deny) { $denyForCase = $denyFilePath }
            $result = Invoke-ScanChild -Target $target -Deny $denyForCase -Index $index
            $passed = $true
            $detail = "exit code $($result.ExitCode), expected $($sample.Expect)"
            if ($result.ExitCode -ne $sample.Expect) { $passed = $false }
            if ($passed -and $sample.Category.Length -gt 0) {
                if ($result.Text.IndexOf($sample.Category, [System.StringComparison]::Ordinal) -lt 0) {
                    $passed = $false
                    $detail = "exit code $($result.ExitCode) as expected, but category '$($sample.Category)' was not reported"
                }
            }
            if ($passed -and $sample.Forbid.Length -gt 0) {
                if ($result.Text.IndexOf($sample.Forbid, [System.StringComparison]::Ordinal) -ge 0) {
                    $passed = $false
                    $detail = "exit code $($result.ExitCode) as expected, but the matched text was printed"
                }
            }
            $checks.Add([pscustomobject]@{ Name = $sample.Name; Passed = $passed; Detail = $detail; Output = $result.Text })
        }

        # The samples must not survive the test: a sample left on disk would be a secret-like file
        # sitting in the temporary directory.
        Remove-Item -LiteralPath $selfTestRoot -Recurse -Force -ErrorAction SilentlyContinue
        $checks.Add([pscustomobject]@{
                Name = "temporary-samples-removed"; Passed = (-not (Test-Path -LiteralPath $selfTestRoot))
                Detail = "the sample directory no longer exists"; Output = ""
            })
    } finally {
        if (Test-Path -LiteralPath $selfTestRoot) {
            Remove-Item -LiteralPath $selfTestRoot -Recurse -Force -ErrorAction SilentlyContinue
        }
    }

    Write-Host "check-public: self test"
    Write-Host ""
    foreach ($check in $checks) {
        $status = "PASS"
        if (-not $check.Passed) { $status = "FAIL" }
        Write-Host ("  {0}  {1,-34} {2}" -f $status, $check.Name, $check.Detail)
    }
    $failed = @($checks | Where-Object { -not $_.Passed })
    if ($failed.Count -gt 0) {
        Write-Host ""
        Write-Host "== child output of the failed samples =="
        foreach ($check in $failed) {
            Write-Host ""
            Write-Host ("-- " + $check.Name + " --")
            foreach ($line in @($check.Output -split "`r?`n")) {
                if ($line.Trim().Length -gt 0) { Write-Host ("   " + $line) }
            }
        }
    }
    Write-Host ""
    $passedCount = $checks.Count - $failed.Count
    Write-Host ("check-public: self test: {0} checks, {1} passed, {2} failed" -f $checks.Count, $passedCount, $failed.Count)
    Write-Host "check-public: the samples were built outside the repository and deleted afterwards"
    if ($failed.Count -gt 0) { return 1 }
    return 0
}

if ($SelfTest) {
    exit (Invoke-SelfTest)
}

# --- scan target --------------------------------------------------------------

$skippedBinary = 0
$scannedFiles = 0
$denyWords = @(Read-Denylist -Path $DenylistFile)

if ($directoryMode) {
    Write-Host "check-public: mode: directory scan (test-only)"
    Write-Host ("check-public: target: " + (Split-Path -Leaf (Split-Path -Parent $ScanDirectory)) + "\" + (Split-Path -Leaf $ScanDirectory))
    Write-Host ""
    $resolved = (Resolve-Path -LiteralPath $ScanDirectory).ProviderPath
    if (Test-Path -LiteralPath $resolved -PathType Leaf) {
        $root = Split-Path -Parent $resolved
        $files = @($resolved)
    } else {
        $root = $resolved
        $files = @(Get-ChildItem -LiteralPath $resolved -Recurse -File -Force | ForEach-Object { $_.FullName })
    }
    foreach ($file in $files) {
        $display = $file.Substring($root.Length).TrimStart([char[]]@("/", "\"))
        if (Test-SensitiveName -Path $display) { Add-Finding "sensitive-file" $display }
        $units = @(Read-ScanUnits -FullPath $file -DisplayPath $display -SkippedBinary ([ref]$skippedBinary))
        if ($units.Count -gt 0) { $scannedFiles = $scannedFiles + 1 }
        Test-ScanUnits -Units $units -DenyWords $denyWords
    }
    Write-Host "check-public: files: $($files.Count) ($scannedFiles scanned, $skippedBinary skipped as binary)"
} else {
    if ($null -eq (Get-Command git -ErrorAction SilentlyContinue)) {
        Stop-WithError "git is not on PATH; this check needs the repository history"
    }
    Write-Host "check-public: repository $repoRoot"
    $status = Invoke-Git @("status", "--porcelain")
    if ($status.ExitCode -ne 0) { Stop-WithError "git status failed in $repoRoot" }
    if ($status.Output.Count -gt 0) {
        # Reading tracked files out of a dirty working tree can hide what HEAD really contains, so
        # this gate refuses to produce a result instead of judging something that is not the commit
        # under test. Commit or stash first; -ScanDirectory stays available for single files.
        Stop-WithError "the working tree has uncommitted changes; this check reports on HEAD and refuses to run on a dirty tree (commit or stash first)"
    }

    # The file list comes from HEAD, not from the index: together with the dirty-tree refusal above,
    # the working tree is identical to HEAD here, so a file's content is the content of the commit
    # under test.
    $listed = Invoke-Git @("ls-tree", "-r", "--name-only", "HEAD")
    if ($listed.ExitCode -ne 0) { Stop-WithError "git ls-tree failed for HEAD" }
    $trackedFiles = @($listed.Output | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    foreach ($relative in $trackedFiles) {
        if (Test-SensitiveName -Path $relative) { Add-Finding "sensitive-file" $relative }
        # Not named $full: PowerShell variable names are case-insensitive, so $full would be the
        # [switch]$Full parameter of this script and assigning a string to it would fail.
        $fullPath = Join-Path $repoRoot $relative
        $units = @(Read-ScanUnits -FullPath $fullPath -DisplayPath $relative -SkippedBinary ([ref]$skippedBinary))
        if ($units.Count -gt 0) { $scannedFiles = $scannedFiles + 1 }
        Test-ScanUnits -Units $units -DenyWords $denyWords
    }

    $base = ""
    foreach ($candidate in @("origin/main", "main")) {
        $probe = Invoke-Git @("rev-parse", "--verify", "--quiet", $candidate)
        if ($probe.ExitCode -eq 0) { $base = $candidate; break }
    }
    if ([string]::IsNullOrWhiteSpace($base)) {
        Stop-WithError "neither origin/main nor main resolves; run git fetch origin before this check"
    }
    $range = Invoke-Git @("log", "--format=%H", ($base + "..HEAD"))
    if ($range.ExitCode -ne 0) { Stop-WithError "git log failed for $base..HEAD" }
    $commits = @($range.Output | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    foreach ($sha in $commits) {
        $shortSha = $sha.Substring(0, 7)
        $diff = Invoke-Git @("show", "--format=", "--unified=0", "--no-color", $sha)
        if ($diff.ExitCode -ne 0) { Stop-WithError "git show failed for $shortSha" }
        $units = New-Object System.Collections.Generic.List[object]
        $currentPath = ""
        $newLine = 0
        $oldLine = 0
        foreach ($line in $diff.Output) {
            if ($line -match '^diff --git a/(.+) b/(.+)$') {
                $currentPath = $Matches[2]
                if (Test-SensitiveName -Path $currentPath) { Add-Finding "sensitive-file" "$shortSha $currentPath" }
                $newLine = 0
                $oldLine = 0
                continue
            }
            if ($line -match '^diff --cc (.+)$') {
                $currentPath = $Matches[1]
                if (Test-SensitiveName -Path $currentPath) { Add-Finding "sensitive-file" "$shortSha $currentPath" }
                $newLine = 0
                $oldLine = 0
                continue
            }
            $number = "-"
            if ($line -match '^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@') {
                $oldLine = [int]$Matches[1]
                $newLine = [int]$Matches[2]
            } elseif ($line.StartsWith("+") -and -not $line.StartsWith("+++")) {
                $number = $newLine
                $newLine = $newLine + 1
            } elseif ($line.StartsWith("-") -and -not $line.StartsWith("---")) {
                $number = $oldLine
                $oldLine = $oldLine + 1
            } elseif ($line.StartsWith(" ")) {
                $number = $newLine
                $newLine = $newLine + 1
                $oldLine = $oldLine + 1
            }
            if ($line.Length -eq 0) { continue }
            $location = "$shortSha $currentPath"
            $units.Add([pscustomobject]@{ Location = $location; Number = $number; Text = $line })
        }
        Test-ScanUnits -Units $units.ToArray() -DenyWords $denyWords
    }
    Write-Host "check-public: tracked files: $($trackedFiles.Count) ($scannedFiles scanned, $skippedBinary skipped as binary)"
    Write-Host ("check-public: baseline: " + $base + " " + (@((Invoke-Git @("rev-parse", $base)).Output) | Select-Object -First 1))
    Write-Host ("check-public: HEAD: " + (@((Invoke-Git @("rev-parse", "HEAD")).Output) | Select-Object -First 1))
    Write-Host "check-public: history: $($commits.Count) commit(s) in $base..HEAD"
    if ($commits.Count -eq 0) {
        Write-Host "check-public: note: the range is empty because HEAD equals the baseline; an empty range says nothing about the history that was already published"
    } else {
        Write-Host "check-public: note: the range covers every commit between the baseline and HEAD; a commit outside it is not judged here"
    }
}

Write-Host ""
$denyReport = "not configured (create .local/public-denylist.txt in the repository)"
if ($denyWords.Count -gt 0) { $denyReport = "$($denyWords.Count) word(s)" }
Write-Host "check-public: local denylist: $denyReport"

# --- report -------------------------------------------------------------------

$blockingFindings = @($script:findings | Where-Object { $categoryBlocking[$_.Category] })
$listedFindings = @($script:findings | Where-Object { -not $categoryBlocking[$_.Category] })

Write-Host ""
Write-Host "== blocking findings =="
if ($blockingFindings.Count -eq 0) {
    Write-Host "  (none)"
} else {
    foreach ($category in $categoryOrder) {
        $entries = @($blockingFindings | Where-Object { $_.Category -eq $category })
        if ($entries.Count -eq 0) { continue }
        Write-Host ("  [{0}] {1} finding(s)" -f $category, $entries.Count)
        $shown = 0
        foreach ($entry in $entries) {
            if ($shown -ge $printLimit) { break }
            Write-Host ("    " + $entry.Location)
            $shown = $shown + 1
        }
        if ($entries.Count -gt $shown) {
            Write-Host ("    ... and {0} more in [{1}] (rerun with -Full to print all)" -f ($entries.Count - $shown), $category)
        }
    }
}

Write-Host ""
Write-Host "== listed for manual review (not a failure) =="
if ($listedFindings.Count -eq 0) {
    Write-Host "  (none)"
} else {
    foreach ($category in $categoryOrder) {
        $entries = @($listedFindings | Where-Object { $_.Category -eq $category })
        if ($entries.Count -eq 0) { continue }
        Write-Host ("  [{0}] {1} finding(s)" -f $category, $entries.Count)
        $shown = 0
        foreach ($entry in $entries) {
            if ($shown -ge $printLimit) { break }
            Write-Host ("    " + $entry.Location)
            $shown = $shown + 1
        }
        if ($entries.Count -gt $shown) {
            Write-Host ("    ... and {0} more in [{1}] (rerun with -Full to print all)" -f ($entries.Count - $shown), $category)
        }
    }
}

Write-Host ""
Write-Host "== summary =="
Write-Host ("  {0,-16} {1,-8} {2,5}" -f "category", "verdict", "count")
foreach ($category in $categoryOrder) {
    $entries = @($script:findings | Where-Object { $_.Category -eq $category })
    $verdict = "listed"
    if ($categoryBlocking[$category]) { $verdict = "blocks" }
    Write-Host ("  {0,-16} {1,-8} {2,5}" -f $category, $verdict, $entries.Count)
}

Write-Host ""
if ($blockingFindings.Count -gt 0) {
    Write-Host ("check-public: FAIL: {0} blocking finding(s) must be fixed before pushing" -f $blockingFindings.Count) -ForegroundColor Red
    if ($listedFindings.Count -gt 0) {
        Write-Host ("check-public: {0} local path(s) are listed for review and are not a failure" -f $listedFindings.Count)
    }
    exit 1
}
Write-Host ("check-public: PASS: no blocking finding; {0} local path(s) listed for review" -f $listedFindings.Count)
exit 0