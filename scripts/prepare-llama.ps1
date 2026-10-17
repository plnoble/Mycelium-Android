# Offline only. Supply a local clone containing the exact audited commit.
param([Parameter(Mandatory = $true)][string]$SourceRepository)
$ErrorActionPreference = "Stop"
$commit = "5e03bdd8700948b9c41c54dd1b00f28a2aebc03f"
$root = Split-Path $PSScriptRoot -Parent
$destination = Join-Path $root "llm/vendor/llama.cpp"
if (!(Test-Path -LiteralPath $SourceRepository -PathType Container)) { throw "A local source repository is required" }
$source = (Resolve-Path -LiteralPath $SourceRepository).Path
git -C $source cat-file -e "$commit^{commit}"
if ($LASTEXITCODE -ne 0) { throw "Pinned commit is absent from the local source" }
if (!(Test-Path -LiteralPath $destination)) {
    New-Item -ItemType Directory -Force -Path (Split-Path $destination -Parent) | Out-Null
    git clone --no-hardlinks --no-checkout -- $source $destination
    if ($LASTEXITCODE -ne 0) { throw "Local clone failed" }
    git -C $destination checkout --detach $commit
    if ($LASTEXITCODE -ne 0) { throw "Pinned checkout failed" }
}
$actual = git -C $destination rev-parse HEAD
if ($LASTEXITCODE -ne 0 -or $actual -ne $commit) { throw "Unexpected llama.cpp revision: $actual" }
$dirty = git -C $destination status --porcelain --untracked-files=all
if ($LASTEXITCODE -ne 0 -or $dirty) { throw "llama.cpp source must be pristine" }
Write-Output "llama.cpp verified at $actual"

