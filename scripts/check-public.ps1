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

  The coordinator accepted that a known set of commits already recorded in this branch's history
  stay there without being rewritten. Those commits are listed, one full commit id per line, in an
  in-repository list file (default: scripts\accepted-history-commits.txt, see
  -AcceptedHistoryFile). A hit inside one of them is reported as [accepted-history] and is listed,
  not blocking. The list covers the history only: every file tracked at HEAD is still checked
  strictly, so a hit at HEAD blocks even when the commit that introduced the text is in the list.
  A missing list file, a line that is not a full commit id, a list that holds no id, or an id that
  names no commit of this repository ends the run with exit code 1: reading any of those as
  "nothing accepted" would turn an accepted history back into a blocking failure without a reason.
  The list file carries the acceptance date and the reason in '#' comment lines, so a comment never
  affects the parse.

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

.PARAMETER AcceptedHistoryFile
  In-repository list of commits whose historical changes are accepted: one full 40-character commit
  id per line, '#' starts a comment (a whole line, or a trailing one that carries the acceptance
  date and the reason). Default: <repository>\scripts\accepted-history-commits.txt. Read only when
  the git history is scanned; -ScanDirectory does not use it. A missing list, a malformed line, an
  empty list or an id that names no commit here is an error, never a silently empty list.

.PARAMETER Full
  Print every finding instead of the first 20 lines of each category.
#>
[CmdletBinding()]
param(
    [switch]$SelfTest,
    [string]$ScanDirectory = "",
    [string]$DenylistFile = "",
    [string]$AcceptedHistoryFile = "",
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
if ([string]::IsNullOrWhiteSpace($AcceptedHistoryFile)) {
    $AcceptedHistoryFile = Join-Path $repoRoot "scripts\accepted-history-commits.txt"
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

$categoryOrder = @("private-key", "token", "user-path", "sensitive-file", "local-denylist", "unreadable", "local-path", "accepted-history")
$categoryBlocking = @{
    "private-key"      = $true
    "token"            = $true
    "user-path"        = $true
    "sensitive-file"   = $true
    "local-denylist"   = $true
    "unreadable"       = $true
    "local-path"       = $false
    "accepted-history" = $false
}

# --- findings -----------------------------------------------------------------

$script:findings = @()
$script:seenFindings = @{}

# Where the current scan unit comes from ("head", "history" or "directory"), and whether the commit
# under scan is on the accepted-history list. Both travel with the finding, so the report can tell
# an accepted historical hit from a hit that still blocks. Only a history finding can be accepted,
# and the accepted list is only consulted for a category that would otherwise block.
$script:findingScope = "head"
$script:findingAccepted = $false

function Add-Finding {
    param([string]$Category, [string]$Location)
    # Mask before recording or printing (M10): the location itself can carry the sensitive text
    # (a denylist word inside a file name, or a file name that is a token). The report may say
    # which file and line, but it must not copy the sensitive text into the report.
    $safeLocation = Hide-SensitiveText -Text $Location
    $key = $Category + "|" + $safeLocation
    if ($script:seenFindings.ContainsKey($key)) { return }
    $script:seenFindings[$key] = $true
    $script:findings += [pscustomobject]@{
        Category = $Category
        Location = $safeLocation
        Scope    = $script:findingScope
        Accepted = $script:findingAccepted
    }
}

# Replaces the sensitive text with '*': used for the matched text and for the file name itself (M10).
function Hide-SensitiveText {
    param([string]$Text)
    if ([string]::IsNullOrEmpty($Text)) { return $Text }
    $hidden = [regex]::Replace($Text, $scanPattern, "*")
    foreach ($word in @($denyWords)) {
        if ([string]::IsNullOrEmpty($word)) { continue }
        $hidden = [regex]::Replace($hidden, [regex]::Escape($word), "*", "IgnoreCase")
    }
    return $hidden
}

# A finding is reported under its own category, except an accepted historical hit: that one is
# reported under the "accepted-history" category, so the report says "this hit is on the accepted
# list" instead of "this whole category is tolerated".
function Get-FindingBucket {
    param([object]$Finding)
    if ($Finding.Accepted) { return "accepted-history" }
    return $Finding.Category
}

# True when the finding must fail the run: its category blocks and the hit is not an accepted
# historical one. A hit at HEAD is never accepted, because the list is never consulted there.
function Test-FindingBlocking {
    param([object]$Finding)
    if ($Finding.Accepted) { return $false }
    return [bool]$categoryBlocking[$Finding.Category]
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

# A file NAME can carry the sensitive text too (a token pasted into a name, or a denylist word).
# The extension check above only says what kind of file it is; this one says what the name says.
function Test-SensitiveNameWord {
    param([string]$Path, [string[]]$Words)
    if ($null -eq $Words -or $Words.Count -eq 0) { return $false }
    foreach ($word in $Words) {
        if ([string]::IsNullOrEmpty($word)) { continue }
        if ($Path.IndexOf($word, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) { return $true }
    }
    return $false
}

# Decides how a byte array should be read as text, or returns $null when it is not text we can read.
# A BOM is authoritative. Without one, a file whose NUL bytes all sit on one side of the byte pairs
# is UTF-16 (that is what "ASCII text saved as UTF-16" looks like); anything else with a NUL is
# binary. This is what keeps a gate log saved as UTF-16 from being skipped as a binary file.
function ConvertFrom-ScanText {
    param([byte[]]$Bytes)
    if ($Bytes.Length -ge 3 -and $Bytes[0] -eq 0xEF -and $Bytes[1] -eq 0xBB -and $Bytes[2] -eq 0xBF) {
        return [System.Text.Encoding]::UTF8.GetString($Bytes, 3, $Bytes.Length - 3)
    }
    if ($Bytes.Length -ge 2 -and $Bytes[0] -eq 0xFF -and $Bytes[1] -eq 0xFE) {
        return [System.Text.Encoding]::Unicode.GetString($Bytes, 2, $Bytes.Length - 2)
    }
    if ($Bytes.Length -ge 2 -and $Bytes[0] -eq 0xFE -and $Bytes[1] -eq 0xFF) {
        return [System.Text.Encoding]::BigEndianUnicode.GetString($Bytes, 2, $Bytes.Length - 2)
    }
    $probe = [Math]::Min($Bytes.Length, 8000)
    $nul = 0
    $nulAtEven = 0
    for ($i = 0; $i -lt $probe; $i++) {
        if ($Bytes[$i] -eq 0) {
            $nul = $nul + 1
            if (($i % 2) -eq 0) { $nulAtEven = $nulAtEven + 1 }
        }
    }
    if ($nul -eq 0) { return [System.Text.Encoding]::UTF8.GetString($Bytes) }
    # BOM-less UTF-16: every other byte is NUL, and always on the same side.
    if ($nul -ge [Math]::Max(2, [int]($probe / 8)) -and ($nulAtEven -eq 0 -or $nulAtEven -eq $nul)) {
        if ($nulAtEven -eq 0) { return [System.Text.Encoding]::Unicode.GetString($Bytes) }
        return [System.Text.Encoding]::BigEndianUnicode.GetString($Bytes)
    }
    return $null
}

# One unit per line, 1-based line numbers. Shared by files, git blobs and the self-test.
function ConvertTo-ScanUnits {
    param([string]$Text, [string]$Location)
    $lines = @($Text -split "`r?`n")
    $units = New-Object System.Collections.Generic.List[object]
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $units.Add([pscustomobject]@{ Location = $Location; Number = ($i + 1); Text = $lines[$i] })
    }
    return $units.ToArray()
}

# Reads one file as scan units (one unit per line). The encoding is settled before the file is
# called binary: UTF-8 and UTF-16 (either endianness, with or without a BOM) are text even when they
# contain NUL bytes, so such a file is scanned instead of skipped. Only a file that is neither is
# counted as binary, and such a file is reported through its name, not through its bytes.
function Read-ScanUnits {
    param([string]$FullPath, [string]$DisplayPath, [ref]$SkippedBinary)
    try {
        $bytes = [System.IO.File]::ReadAllBytes($FullPath)
    } catch {
        Add-Finding "unreadable" $DisplayPath
        return @()
    }
    $text = ConvertFrom-ScanText -Bytes $bytes
    if ($null -eq $text) {
        $SkippedBinary.Value = $SkippedBinary.Value + 1
        return @()
    }
    return (ConvertTo-ScanUnits -Text $text -Location $DisplayPath)
}

# Reads a git blob straight out of git as bytes and scans it. The history side needs this because a
# text file saved as UTF-16 is shown by git as "Binary files ... differ": its lines never reach the
# diff, so without this the account name inside such a log would stay invisible to the history scan.
function Read-GitBlobUnits {
    param([string]$RepoRoot, [string]$Spec, [string]$Location)
    $temp = [System.IO.Path]::GetTempFileName()
    try {
        $null = cmd /c "git -C `"$RepoRoot`" cat-file -p `"$Spec`" > `"$temp`" 2>NUL"
        if ($LASTEXITCODE -ne 0) { return @() }
        $bytes = [System.IO.File]::ReadAllBytes($temp)
        if ($bytes.Length -eq 0) { return @() }
        $text = ConvertFrom-ScanText -Bytes $bytes
        if ($null -eq $text) { return @() }
        return (ConvertTo-ScanUnits -Text $text -Location $Location)
    } finally {
        if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Force -ErrorAction SilentlyContinue }
    }
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

# Reads the accepted-history list: one full 40-character commit id per line, with '#' starting a
# comment (the whole line, or a trailing one that carries the acceptance date and the reason; the
# parse drops everything from the first '#'). Nothing here is silent: a missing file, a line that is
# not a commit id, a list that holds no id, and an id that names no commit of this repository all
# end the run with a clear message. A list that quietly read as empty would turn the accepted
# history back into a blocking failure with no hint about why, which is exactly what this list
# exists to prevent.
function Read-AcceptedHistory {
    param([string]$Path)
    if (!(Test-Path -LiteralPath $Path -PathType Leaf)) {
        Stop-WithError "accepted-history list not found: $Path (create it, or pass -AcceptedHistoryFile)"
    }
    $accepted = @{}
    $order = New-Object System.Collections.Generic.List[string]
    $number = 0
    foreach ($line in @(Get-Content -LiteralPath $Path -Encoding UTF8)) {
        $number = $number + 1
        $text = $line
        $commentIndex = $text.IndexOf("#")
        if ($commentIndex -ge 0) { $text = $text.Substring(0, $commentIndex) }
        $text = $text.Trim()
        if ($text.Length -eq 0) { continue }
        if ($text -notmatch '^[0-9a-fA-F]{40}$') {
            Stop-WithError ("accepted-history list " + $Path + " line " + $number + " is not a full 40-character commit id")
        }
        $id = $text.ToLowerInvariant()
        if ($accepted.ContainsKey($id)) { continue }
        $accepted[$id] = $true
        $order.Add($id)
    }
    if ($order.Count -eq 0) {
        Stop-WithError ("accepted-history list " + $Path + " holds no commit id; an empty list is refused instead of being read as 'nothing is accepted'")
    }
    # A well-formed id that names no commit is a typo in the list, not an accepted commit: say so
    # instead of letting it match nothing and have the run fail on that commit later.
    foreach ($id in $order) {
        $probe = Invoke-Git @("rev-parse", "--verify", "--quiet", ($id + "^{commit}"))
        if ($probe.ExitCode -ne 0) {
            Stop-WithError ("accepted-history list " + $Path + " names " + $id + ", which is not a commit of this repository")
        }
    }
    return [pscustomobject]@{ Path = $Path; Map = $accepted; Order = $order.ToArray() }
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
        New-Sample ("deny-name/" + $sampleDenyWord + ".txt") ("clean contents" + "`n")
        New-Sample "deny-clean/note.txt" ("a clean line" + "`n")

        # Files in encodings other than UTF-8. A gate log saved as UTF-16 carries the same text but
        # every other byte is NUL, which is exactly what used to get it skipped as "binary".
        function New-SampleBytes {
            param([string]$RelativePath, [byte[]]$Bytes)
            $samplePath = Join-Path $selfTestRoot $RelativePath
            $parent = Split-Path -Parent $samplePath
            if (!(Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
            [System.IO.File]::WriteAllBytes($samplePath, $Bytes)
        }
        $utf16Text = $userPathForbid + "\" + "project" + "`n" + $slash + "home" + $slash + $sampleUser + "/work/app.log" + "`n"
        New-SampleBytes "dirty-utf16le/note.txt" ([byte[]](@(0xFF, 0xFE) + [System.Text.Encoding]::Unicode.GetBytes($utf16Text)))
        New-SampleBytes "dirty-utf16be/note.txt" ([byte[]](@(0xFE, 0xFF) + [System.Text.Encoding]::BigEndianUnicode.GetBytes($utf16Text)))
        # The same text without a BOM: recognised by the NUL parity, not by a marker.
        New-SampleBytes "dirty-utf16le-nobom/note.txt" ([System.Text.Encoding]::Unicode.GetBytes($utf16Text))
        New-SampleBytes "clean-utf16/notes.txt" ([System.Text.Encoding]::Unicode.GetBytes("UTF-16 sample line: clean" + "`n"))
        # A genuinely binary file that happens to contain the pattern text. Its NUL bytes are neither
        # absent nor all on one side of the byte pairs, so it must stay skipped instead of producing
        # a hit (or the skip rule would be untestable).
        $binarySample = "PNG" + "`0`0" + $userPathForbid + "`0`0" + "tail"
        New-SampleBytes "clean-binary/asset.bin" ([System.Text.Encoding]::ASCII.GetBytes($binarySample))

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
            @{ Name = "utf16le-user-name-path-fails"; Dir = "dirty-utf16le"; Deny = $false; Expect = 1; Category = "user-path"; Forbid = $userPathForbid },
            @{ Name = "utf16be-user-name-path-fails"; Dir = "dirty-utf16be"; Deny = $false; Expect = 1; Category = "user-path"; Forbid = $userPathForbid },
            @{ Name = "utf16le-without-bom-fails"; Dir = "dirty-utf16le-nobom"; Deny = $false; Expect = 1; Category = "user-path"; Forbid = $userPathForbid },
            @{ Name = "clean-utf16-passes"; Dir = "clean-utf16"; Deny = $false; Expect = 0; Category = ""; Forbid = "" },
            @{ Name = "binary-with-pattern-text-is-skipped"; Dir = "clean-binary"; Deny = $false; Expect = 0; Category = ""; Forbid = "" },
            @{ Name = "tracked-keystore-fails"; Dir = "dirty-name-keystore"; Deny = $false; Expect = 1; Category = "sensitive-file"; Forbid = "" },
            @{ Name = "tracked-jks-fails"; Dir = "dirty-name-jks"; Deny = $false; Expect = 1; Category = "sensitive-file"; Forbid = "" },
            @{ Name = "tracked-gguf-fails"; Dir = "dirty-name-gguf"; Deny = $false; Expect = 1; Category = "sensitive-file"; Forbid = "" },
            @{ Name = "tracked-apk-fails"; Dir = "dirty-name-apk"; Deny = $false; Expect = 1; Category = "sensitive-file"; Forbid = "" },
            @{ Name = "denylist-word-fails"; Dir = "deny-hit"; Deny = $true; Expect = 1; Category = "local-denylist"; Forbid = $sampleDenyWord },
            # M10: a denylist word inside the file name must hit too, and the printed location must
            # not contain the original text (the masking in Add-Finding is the proof).
            @{ Name = "denylist-word-in-file-name-fails"; Dir = "deny-name"; Deny = $true; Expect = 1; Category = "local-denylist"; Forbid = $sampleDenyWord },
            @{ Name = "denylist-miss-passes"; Dir = "deny-clean"; Deny = $true; Expect = 0; Category = ""; Forbid = $sampleDenyWord }
        )

        # Known-clean real files: the scanner itself, the two documents that quote its own patterns and
        # the accepted-history list. A pass here is the proof that the scanner does not match its own
        # pattern text or the list file.
        $knownFiles = @(
            @{ Name = "scanner-does-not-match-itself"; Path = $scriptPath },
            @{ Name = "plan-document-passes"; Path = (Join-Path $repoRoot "docs\reviews\2026-10-18-P2-2b\PLAN.md") },
            @{ Name = "accepted-history-list-passes"; Path = $AcceptedHistoryFile },
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

        # --- the accepted-history list against a real git history -----------------
        #
        # -ScanDirectory never scans a history, so the list is proven against small throwaway git
        # repositories: this script is copied into each one (so it finds that repository as its own),
        # the sample commits are made there and the list files stay outside the repository, where
        # they cannot make the working tree dirty. The bad text is built from the same fragments as
        # the samples above, so this file still never carries the pattern it hunts.

        function Read-ChildLog {
            param([string]$OutFile, [string]$ErrFile)
            $text = ""
            if (Test-Path -LiteralPath $OutFile) {
                $stdout = Get-Content -LiteralPath $OutFile -Raw -Encoding UTF8
                if ($null -ne $stdout) { $text = $stdout }
            }
            if (Test-Path -LiteralPath $ErrFile) {
                $stderr = Get-Content -LiteralPath $ErrFile -Raw -Encoding UTF8
                if ($null -ne $stderr) { $text = $text + "`n" + $stderr }
            }
            return $text
        }

        function Invoke-GitInRepo {
            param([string]$RepoPath, [string[]]$Arguments)
            $previous = $ErrorActionPreference
            $ErrorActionPreference = "Continue"
            try {
                $output = & git -C $RepoPath @Arguments 2>$null
                $code = $LASTEXITCODE
            } finally {
                $ErrorActionPreference = $previous
            }
            if ($null -eq $code) { $code = -1 }
            return [pscustomobject]@{ Output = @($output | Where-Object { $null -ne $_ }); ExitCode = $code }
        }

        function Invoke-RepoChild {
            param([string]$RepoPath, [string]$AcceptedFile, [int]$Index)
            $outFile = Join-Path $selfTestRoot ("repo-out-" + $Index + ".txt")
            $errFile = Join-Path $selfTestRoot ("repo-err-" + $Index + ".txt")
            $childScript = Join-Path $RepoPath "scripts\check-public.ps1"
            $arguments = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", ('"' + $childScript + '"'))
            if (![string]::IsNullOrWhiteSpace($AcceptedFile)) {
                $arguments += @("-AcceptedHistoryFile", ('"' + $AcceptedFile + '"'))
            }
            $process = Start-Process -FilePath $hostExe -ArgumentList $arguments -NoNewWindow -Wait -PassThru `
                -RedirectStandardOutput $outFile -RedirectStandardError $errFile
            return [pscustomobject]@{
                ExitCode = $process.ExitCode
                Text = (Read-ChildLog -OutFile $outFile -ErrFile $errFile)
            }
        }

        # One throwaway repository with this script inside it: the copy is committed with the base
        # commit, so the tree stays clean afterwards (a dirty tree is refused before the history is
        # scanned, which would make every case here fail for the wrong reason).
        function New-SampleRepo {
            param([string]$Name)
            $repoPath = Join-Path $selfTestRoot $Name
            New-Item -ItemType Directory -Force -Path (Join-Path $repoPath "scripts") | Out-Null
            Copy-Item -LiteralPath $scriptPath -Destination (Join-Path $repoPath "scripts\check-public.ps1") -Force
            foreach ($arguments in @(
                    @("init", "-q"),
                    @("config", "user.email", "check-public-selftest@example.invalid"),
                    @("config", "user.name", "check-public self test"),
                    @("config", "core.autocrlf", "false"),
                    @("config", "commit.gpgsign", "false"),
                    @("symbolic-ref", "HEAD", "refs/heads/main"),
                    @("add", "-A"),
                    @("commit", "-q", "-m", "base"),
                    @("checkout", "-q", "-b", "work"))) {
                $result = Invoke-GitInRepo -RepoPath $repoPath -Arguments $arguments
                if ($result.ExitCode -ne 0) { throw ("self test: git " + ($arguments -join " ") + " failed in " + $Name) }
            }
            return $repoPath
        }

        function Save-SampleCommit {
            param([string]$RepoPath, [string]$Message)
            foreach ($arguments in @(@("add", "-A"), @("commit", "-q", "-m", $Message))) {
                $result = Invoke-GitInRepo -RepoPath $RepoPath -Arguments $arguments
                if ($result.ExitCode -ne 0) { throw ("self test: git " + ($arguments -join " ") + " failed in " + $RepoPath) }
            }
            $head = Invoke-GitInRepo -RepoPath $RepoPath -Arguments @("rev-parse", "HEAD")
            return (@($head.Output) | Select-Object -First 1).Trim()
        }

        function Add-SampleCommit {
            param([string]$RepoPath, [string]$RelativePath, [string]$Content, [string]$Message)
            $samplePath = Join-Path $RepoPath $RelativePath
            $parent = Split-Path -Parent $samplePath
            if (!(Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
            [System.IO.File]::WriteAllText($samplePath, $Content, (New-Object System.Text.UTF8Encoding($false)))
            return Save-SampleCommit -RepoPath $RepoPath -Message $Message
        }

        function Remove-SampleCommit {
            param([string]$RepoPath, [string]$RelativePath, [string]$Message)
            $removed = Invoke-GitInRepo -RepoPath $RepoPath -Arguments @("rm", "-q", "--", $RelativePath)
            if ($removed.ExitCode -ne 0) { throw ("self test: git rm failed for " + $RelativePath) }
            return Save-SampleCommit -RepoPath $RepoPath -Message $Message
        }

        # A list file for one case: comment lines, then one id per line, some with a trailing comment,
        # because the parse must survive both kinds of comment.
        function New-Manifest {
            param([string]$Name, [string[]]$Lines)
            $manifestPath = Join-Path $selfTestRoot $Name
            $text = "# accepted-history self-test sample`n" + "# 2026-10-19 a comment never changes the parse`n"
            foreach ($line in $Lines) { $text = $text + $line + "`n" }
            [System.IO.File]::WriteAllText($manifestPath, $text, (New-Object System.Text.UTF8Encoding($false)))
            return $manifestPath
        }

        # Repository A: the bad line is added and removed again, so HEAD is clean and the hit exists in
        # the history only - the shape of the commits this list exists for.
        $historyRepo = New-SampleRepo -Name "accepted-history-repo"
        $historyForbid = $sampleDrive + ":" + "\" + "Users" + "\" + $sampleUser + "\" + "history-only.txt"
        $addedSha = Add-SampleCommit -RepoPath $historyRepo -RelativePath "notes/history-only.txt" `
            -Content ($historyForbid + "`n") -Message "add history sample"
        $removedSha = Remove-SampleCommit -RepoPath $historyRepo -RelativePath "notes/history-only.txt" -Message "remove history sample"

        # Repository B: the bad line is added and stays at HEAD. The adding commit is on the list, so
        # the run must still fail: the list never covers a tracked file at HEAD.
        $headRepo = New-SampleRepo -Name "head-hit-repo"
        $headForbid = $sampleDrive + ":" + "\" + "Users" + "\" + $sampleUser + "\" + "head-only.txt"
        $headSha = Add-SampleCommit -RepoPath $headRepo -RelativePath "notes/head-only.txt" `
            -Content ($headForbid + "`n") -Message "add head sample"

        # Repository C: four shapes the diff parser used to miss (M10). Each one puts the same bad
        # shape in a place a naive line-based scan loses: far-apart hunks, a rename, a UTF-16 file that
        # git calls binary, and a side branch brought in by a merge.
        $shapeRepo = New-SampleRepo -Name "shape-repo"
        $shapeForbid = $sampleDrive + ":" + "\" + "Users" + "\" + $sampleUser
        $bulk = @()
        for ($i = 0; $i -lt 40; $i++) { $bulk += ("clean line " + $i) }
        $bulkWithBad = @($bulk[0..9]) + ($shapeForbid + "\" + "hunk.txt") + @($bulk[10..39])
        Add-SampleCommit -RepoPath $shapeRepo -RelativePath "docs/bulk.txt" `
            -Content (($bulkWithBad -join "`n") + "`n") -Message "multi-hunk sample" | Out-Null
        Add-SampleCommit -RepoPath $shapeRepo -RelativePath "docs/old-name.txt" `
            -Content ("clean rename source" + "`n") -Message "rename source" | Out-Null
        $renamed = Invoke-GitInRepo -RepoPath $shapeRepo -Arguments @("mv", "docs/old-name.txt", "docs/new-name.txt")
        if ($renamed.ExitCode -ne 0) { throw "self test: git mv failed" }
        Save-SampleCommit -RepoPath $shapeRepo -Message "rename only" | Out-Null
        Add-SampleCommit -RepoPath $shapeRepo -RelativePath "docs/new-name.txt" `
            -Content ($shapeForbid + "\" + "renamed.txt" + "`n") -Message "rename then edit" | Out-Null
        $utf16Target = Join-Path $shapeRepo "docs/utf16-log.txt"
        $utf16Parent = Split-Path -Parent $utf16Target
        if (!(Test-Path -LiteralPath $utf16Parent)) { New-Item -ItemType Directory -Force -Path $utf16Parent | Out-Null }
        [System.IO.File]::WriteAllBytes($utf16Target, [byte[]](@(0xFF, 0xFE) + [System.Text.Encoding]::Unicode.GetBytes($shapeForbid + "\" + "utf16.txt" + "`n")))
        Save-SampleCommit -RepoPath $shapeRepo -Message "utf16 sample" | Out-Null
        $branch = Invoke-GitInRepo -RepoPath $shapeRepo -Arguments @("checkout", "-q", "-b", "side")
        if ($branch.ExitCode -ne 0) { throw "self test: git checkout -b side failed" }
        Add-SampleCommit -RepoPath $shapeRepo -RelativePath "docs/merged.txt" `
            -Content ($shapeForbid + "\" + "merged.txt" + "`n") -Message "side branch adds the bad line" | Out-Null
        $back = Invoke-GitInRepo -RepoPath $shapeRepo -Arguments @("checkout", "-q", "main")
        if ($back.ExitCode -ne 0) { throw "self test: git checkout main failed" }
        $merged = Invoke-GitInRepo -RepoPath $shapeRepo -Arguments @("merge", "--no-ff", "-q", "-m", "merge side", "side")
        if ($merged.ExitCode -ne 0) { throw "self test: git merge failed" }

        # Repository D: nothing between the baseline and HEAD, so the range is empty and the scan must
        # say so instead of quietly reporting clean history.
        $emptyRangeRepo = New-SampleRepo -Name "empty-range-repo"
        Add-SampleCommit -RepoPath $emptyRangeRepo -RelativePath "notes/clean.txt" `
            -Content ("nothing sensitive here" + "`n") -Message "clean commit" | Out-Null

        $baseSha = (@((Invoke-GitInRepo -RepoPath $historyRepo -Arguments @("rev-parse", "main")).Output) | Select-Object -First 1).Trim()
        $manifestAccepted = New-Manifest -Name "manifest-accepted.txt" -Lines @(
            ($addedSha + "  # 2026-10-19 accepted: the reason lives in this comment"),
            ($removedSha + "  # 2026-10-19 accepted: the removal diff carries the same line"))
        $manifestUnlisted = New-Manifest -Name "manifest-unlisted.txt" -Lines @($baseSha)
        $manifestHead = New-Manifest -Name "manifest-head.txt" -Lines @($headSha)
        $manifestBadFormat = New-Manifest -Name "manifest-bad-format.txt" -Lines @("not-a-commit-id")
        $manifestEmpty = New-Manifest -Name "manifest-empty.txt" -Lines @()
        $manifestUnknown = New-Manifest -Name "manifest-unknown.txt" -Lines @("f" * 40)
        $manifestAbsent = Join-Path $selfTestRoot "manifest-absent.txt"
        # The list is per-repository: a commit id from another repo is refused before the scan runs,
        # so these two repos need manifests that actually resolve in them.
        $shapeBase = (@((Invoke-GitInRepo -RepoPath $shapeRepo -Arguments @("rev-list", "--max-parents=0", "HEAD")).Output) | Select-Object -First 1).Trim()
        $manifestShape = New-Manifest -Name "manifest-shape.txt" -Lines @($shapeBase)
        $emptyBase = (@((Invoke-GitInRepo -RepoPath $emptyRangeRepo -Arguments @("rev-parse", "main")).Output) | Select-Object -First 1).Trim()
        $manifestEmptyRange = New-Manifest -Name "manifest-empty-range.txt" -Lines @($emptyBase)

        $repoCases = @(
            @{ Name = "accepted-history-hit-is-listed"; Repo = $historyRepo; Manifest = $manifestAccepted; Expect = 0
                MustHave = @("accepted-history"); Forbid = @($historyForbid) },
            @{ Name = "history-hit-outside-list-fails"; Repo = $historyRepo; Manifest = $manifestUnlisted; Expect = 1
                MustHave = @("[user-path]"); Forbid = @($historyForbid) },
            @{ Name = "head-hit-fails-even-if-listed"; Repo = $headRepo; Manifest = $manifestHead; Expect = 1
                MustHave = @("accepted-history", "[user-path]"); Forbid = @($headForbid) },
            @{ Name = "missing-accepted-list-fails"; Repo = $historyRepo; Manifest = $manifestAbsent; Expect = 1
                MustHave = @("accepted-history list not found"); Forbid = @() },
            @{ Name = "malformed-accepted-list-fails"; Repo = $historyRepo; Manifest = $manifestBadFormat; Expect = 1
                MustHave = @("is not a full 40-character commit id"); Forbid = @() },
            @{ Name = "empty-accepted-list-fails"; Repo = $historyRepo; Manifest = $manifestEmpty; Expect = 1
                MustHave = @("holds no commit id"); Forbid = @() },
            @{ Name = "unknown-commit-in-list-fails"; Repo = $historyRepo; Manifest = $manifestUnknown; Expect = 1
                MustHave = @("is not a commit of this repository"); Forbid = @() },
            @{ Name = "multi-hunk-history-hit-fails"; Repo = $shapeRepo; Manifest = $manifestShape; Expect = 1
                MustHave = @("[user-path]"); Forbid = @($shapeForbid) },
            @{ Name = "renamed-file-history-hit-fails"; Repo = $shapeRepo; Manifest = $manifestShape; Expect = 1
                MustHave = @("new-name.txt"); Forbid = @($shapeForbid) },
            @{ Name = "utf16-binary-history-hit-fails"; Repo = $shapeRepo; Manifest = $manifestShape; Expect = 1
                MustHave = @("utf16-log.txt"); Forbid = @($shapeForbid) },
            @{ Name = "merge-history-hit-fails"; Repo = $shapeRepo; Manifest = $manifestShape; Expect = 1
                MustHave = @("merged.txt"); Forbid = @($shapeForbid) },
            # Empty range: the baseline is HEAD, so there is no history to judge and the run must not
            # block. Only the exit code is asserted here (the note line is printed by the main flow
            # and is not part of the child log this harness captures).
            @{ Name = "empty-range-does-not-block"; Repo = $emptyRangeRepo; Manifest = $manifestEmptyRange; Expect = 0
                MustHave = @(); Forbid = @() }
        )

        foreach ($case in $repoCases) {
            $index = $index + 1
            $result = Invoke-RepoChild -RepoPath $case.Repo -AcceptedFile $case.Manifest -Index $index
            $passed = $true
            $detail = "exit code $($result.ExitCode), expected $($case.Expect)"
            if ($result.ExitCode -ne $case.Expect) { $passed = $false }
            if ($passed) {
                foreach ($needle in $case.MustHave) {
                    if ($result.Text.IndexOf($needle, [System.StringComparison]::Ordinal) -lt 0) {
                        $passed = $false
                        $detail = "exit code $($result.ExitCode) as expected, but '$needle' was not reported"
                        break
                    }
                }
            }
            if ($passed -and $case.Forbid.Length -gt 0) {
                foreach ($forbidden in $case.Forbid) {
                    if ($result.Text.IndexOf($forbidden, [System.StringComparison]::Ordinal) -ge 0) {
                        $passed = $false
                        $detail = "exit code $($result.ExitCode) as expected, but the matched text was printed"
                        break
                    }
                }
            }
            $checks.Add([pscustomobject]@{ Name = $case.Name; Passed = $passed; Detail = $detail; Output = $result.Text })
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
        if (Test-SensitiveNameWord -Path $display -Words $denyWords) { Add-Finding "local-denylist" $display }
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

    # The accepted-history list is read, and refused when it is missing or malformed, before any
    # scan runs: a list that failed silently would look like "nothing is accepted".
    $acceptedHistory = Read-AcceptedHistory -Path $AcceptedHistoryFile

    # The file list comes from HEAD, not from the index: together with the dirty-tree refusal above,
    # the working tree is identical to HEAD here, so a file's content is the content of the commit
    # under test.
    $listed = Invoke-Git @("ls-tree", "-r", "--name-only", "HEAD")
    if ($listed.ExitCode -ne 0) { Stop-WithError "git ls-tree failed for HEAD" }
    $trackedFiles = @($listed.Output | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    foreach ($relative in $trackedFiles) {
        if (Test-SensitiveName -Path $relative) { Add-Finding "sensitive-file" $relative }
        if (Test-SensitiveNameWord -Path $relative -Words $denyWords) { Add-Finding "local-denylist" $relative }
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
    # Only the history is judged against the accepted list, and only for the commits on it: a hit
    # inside one of those is listed, every other commit keeps its old, blocking verdict.
    $acceptedInRange = @($commits | Where-Object { $acceptedHistory.Map.ContainsKey($_) })
    $script:findingScope = "history"
    foreach ($sha in $commits) {
        $shortSha = $sha.Substring(0, 7)
        $script:findingAccepted = $acceptedHistory.Map.ContainsKey($sha)
        $diff = Invoke-Git @("show", "--format=", "--unified=0", "--no-color", $sha)
        if ($diff.ExitCode -ne 0) { Stop-WithError "git show failed for $shortSha" }
        $units = New-Object System.Collections.Generic.List[object]
        $currentPath = ""
        $newLine = 0
        $oldLine = 0
        $binaryPaths = New-Object System.Collections.Generic.List[string]
        foreach ($line in $diff.Output) {
            if ($line -match '^diff --git a/(.+) b/(.+)$') {
                $currentPath = $Matches[2]
                if (Test-SensitiveName -Path $currentPath) { Add-Finding "sensitive-file" "$shortSha $currentPath" }
                if (Test-SensitiveNameWord -Path $currentPath -Words $denyWords) { Add-Finding "local-denylist" "$shortSha $currentPath" }
                $newLine = 0
                $oldLine = 0
                continue
            }
            if ($line -match '^diff --cc (.+)$') {
                $currentPath = $Matches[1]
                if (Test-SensitiveName -Path $currentPath) { Add-Finding "sensitive-file" "$shortSha $currentPath" }
                if (Test-SensitiveNameWord -Path $currentPath -Words $denyWords) { Add-Finding "local-denylist" "$shortSha $currentPath" }
                $newLine = 0
                $oldLine = 0
                continue
            }
            $number = "-"
            # git shows a text file saved as UTF-16 as "Binary files ... differ": no line of it
            # reaches the diff. Remember the path so the blob itself can be read and scanned below.
            if ($line -match '^Binary files .+ differ$') {
                if (![string]::IsNullOrEmpty($currentPath)) { $binaryPaths.Add($currentPath) }
                continue
            }
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
        # Text that git called binary (UTF-16 and friends) is read from the blob instead, on both
        # sides of the change; a genuinely binary blob is not text and stays unread.
        foreach ($binaryPath in $binaryPaths) {
            foreach ($blobSpec in @("${sha}:${binaryPath}", "${sha}^:${binaryPath}")) {
                $blobUnits = @(Read-GitBlobUnits -RepoRoot $repoRoot -Spec $blobSpec -Location ("$shortSha $binaryPath"))
                if ($blobUnits.Count -gt 0) { Test-ScanUnits -Units $blobUnits -DenyWords $denyWords }
            }
        }
    }
    $script:findingScope = "head"
    $script:findingAccepted = $false
    Write-Host "check-public: tracked files: $($trackedFiles.Count) ($scannedFiles scanned, $skippedBinary skipped as binary)"
    Write-Host ("check-public: baseline: " + $base + " " + (@((Invoke-Git @("rev-parse", $base)).Output) | Select-Object -First 1))
    Write-Host ("check-public: HEAD: " + (@((Invoke-Git @("rev-parse", "HEAD")).Output) | Select-Object -First 1))
    Write-Host "check-public: history: $($commits.Count) commit(s) in $base..HEAD"
    Write-Host ("check-public: accepted-history list: " + $acceptedHistory.Path + " (" + $acceptedHistory.Order.Count + " commit(s), " + $acceptedInRange.Count + " inside this range)")
    if ($commits.Count -eq 0) {
        Write-Host "check-public: note: the range is empty because HEAD equals the baseline; an empty range says nothing about the history that was already published"
    } else {
        Write-Host "check-public: note: the range covers every commit between the baseline and HEAD; a commit outside it is not judged here"
    }
    Write-Host "check-public: note: the accepted-history list covers those commits in the history only; every tracked file at HEAD is still checked strictly"
}

Write-Host ""
$denyReport = "not configured (create .local/public-denylist.txt in the repository)"
if ($denyWords.Count -gt 0) { $denyReport = "$($denyWords.Count) word(s)" }
Write-Host "check-public: local denylist: $denyReport"

# --- report -------------------------------------------------------------------
#
# A finding blocks when its category blocks and it is not an accepted historical hit. Accepted hits
# share one bucket ("accepted-history") in the listings below; the counts by original category are
# printed at the end, so "user-path" keeps its own number next to the accepted ones.

$blockingFindings = @($script:findings | Where-Object { Test-FindingBlocking $_ })
$listedFindings = @($script:findings | Where-Object { -not (Test-FindingBlocking $_) })
$acceptedCount = @($script:findings | Where-Object { $_.Accepted }).Count
$historyFindings = @($script:findings | Where-Object { $_.Scope -eq "history" })
$headFindings = @($script:findings | Where-Object { $_.Scope -eq "head" })
$historyOutside = @($historyFindings | Where-Object { -not $_.Accepted }).Count

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
        $entries = @($listedFindings | Where-Object { (Get-FindingBucket $_) -eq $category })
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
    $entries = @($script:findings | Where-Object { (Get-FindingBucket $_) -eq $category })
    $verdict = "listed"
    if ($categoryBlocking[$category]) { $verdict = "blocks" }
    Write-Host ("  {0,-16} {1,-8} {2,5}" -f $category, $verdict, $entries.Count)
}

# The accepted-history row above is one bucket; the accepted hits still belong to the category whose
# rule they matched, so that count is printed separately instead of disappearing into the bucket.
$acceptedByCategory = @()
foreach ($category in $categoryOrder) {
    if ($category -eq "accepted-history") { continue }
    $count = @($script:findings | Where-Object { $_.Accepted -and $_.Category -eq $category }).Count
    if ($count -gt 0) { $acceptedByCategory += ($category + " " + $count) }
}
Write-Host ""
Write-Host ("check-public: findings by scope: HEAD {0}, history {1}" -f $headFindings.Count, $historyFindings.Count)
Write-Host ("check-public: accepted-history hits: {0}; history hits outside the list: {1}" -f $acceptedCount, $historyOutside)
if ($acceptedByCategory.Count -gt 0) {
    Write-Host ("check-public: accepted-history hits by category: " + ($acceptedByCategory -join ", "))
}

Write-Host ""
if ($blockingFindings.Count -gt 0) {
    Write-Host ("check-public: FAIL: {0} blocking finding(s) must be fixed before pushing" -f $blockingFindings.Count) -ForegroundColor Red
    if ($listedFindings.Count -gt 0) {
        Write-Host ("check-public: {0} finding(s) are listed for review and are not a failure" -f $listedFindings.Count)
    }
    exit 1
}
Write-Host ("check-public: PASS: no blocking finding; {0} finding(s) listed for review" -f $listedFindings.Count)
exit 0