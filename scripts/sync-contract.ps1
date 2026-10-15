# Sync contract/ byte-for-byte from the Mycelium Windows repo, verify checksums.json,
# and rewrite contract/UPSTREAM.md.
# Usage: powershell -File scripts/sync-contract.ps1 -Source <windows-repo-root>
# Bash equivalent: scripts/sync-contract.sh
param(
    [Parameter(Mandatory = $true)]
    [string]$Source
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$sourceContract = Join-Path $Source "contract"
$destContract = Join-Path $repoRoot "contract"

if (-not (Test-Path -LiteralPath $sourceContract -PathType Container)) {
    Write-Error "source contract directory not found: $sourceContract"
    exit 2
}
if (-not (Test-Path -LiteralPath (Join-Path $sourceContract "checksums.json") -PathType Leaf)) {
    Write-Error "source contract has no checksums.json"
    exit 2
}

$commit = (& git -C $Source rev-parse HEAD)
if ($LASTEXITCODE -ne 0) { exit 2 }
$commit = "$commit".Trim()
# Windows PowerShell 5.1 decodes native stdout with the console code page, so a
# UTF-8 subject must be written by git itself and read back as UTF-8.
$subjectFile = Join-Path ([IO.Path]::GetTempPath()) ("mycelium-contract-subject-" + [guid]::NewGuid().ToString("N") + ".txt")
try {
    & git -C $Source -c "i18n.logOutputEncoding=utf-8" log -1 --format="%s" "--output=$subjectFile"
    if ($LASTEXITCODE -ne 0) { exit 2 }
    $subject = [IO.File]::ReadAllText($subjectFile, [Text.UTF8Encoding]::new($false)).Trim()
}
finally {
    if (Test-Path -LiteralPath $subjectFile) { Remove-Item -LiteralPath $subjectFile -Force }
}

New-Item -ItemType Directory -Force -Path $destContract | Out-Null

$copied = 0
Get-ChildItem -LiteralPath $sourceContract -Recurse -File | ForEach-Object {
    $relative = $_.FullName.Substring($sourceContract.Length).TrimStart('\', '/')
    $relative = $relative -replace '\\', '/'
    if ($relative -eq "UPSTREAM.md") { return }
    $target = Join-Path $destContract ($relative -replace '/', [IO.Path]::DirectorySeparatorChar)
    $parent = Split-Path -Parent $target
    if (-not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    [IO.File]::WriteAllBytes($target, [IO.File]::ReadAllBytes($_.FullName))
    $script:copied++
}

$python = @"
import hashlib, json, sys
from pathlib import Path
root = Path(sys.argv[1])
checksums = json.loads((root / "checksums.json").read_text(encoding="utf-8"))
failed = False
for name, expected in sorted(checksums.items()):
    path = root / name
    if not path.is_file():
        print(f"missing file: {name}", file=sys.stderr)
        failed = True
        continue
    actual = hashlib.sha256(path.read_bytes()).hexdigest()
    if actual != expected:
        print(f"checksum mismatch: {name}\n  expected {expected}\n  actual {actual}", file=sys.stderr)
        failed = True
if failed:
    raise SystemExit(1)
print(f"verified {len(checksums)} files")
"@
$python | & python - $destContract
if ($LASTEXITCODE -ne 0) { exit 1 }

& python (Join-Path $PSScriptRoot "record-contract-upstream.py") $destContract $Source $commit $subject "scripts/sync-contract.ps1"
if ($LASTEXITCODE -ne 0) { exit 1 }

Write-Output "sync complete: source $commit, copied $copied files."
