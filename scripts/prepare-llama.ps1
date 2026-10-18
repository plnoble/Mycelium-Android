# Prepare the pinned llama.cpp source in llm/vendor/llama.cpp.
#
# Local development (default, offline only): pass a local clone that contains the exact audited
# commit. Nothing is downloaded.
#
#   .\scripts\prepare-llama.ps1 -SourceRepository '<local llama.cpp clone>'
#
# CI (-FromUpstream): fetch the pinned commit from a Git remote. This is the only mode that uses
# the network; it always verifies the commit hash and the pristine state afterwards. Linux CI uses
# the equivalent scripts/prepare-llama.sh.
#
#   .\scripts\prepare-llama.ps1 -FromUpstream
[CmdletBinding(DefaultParameterSetName = "Local")]
param(
    [Parameter(Mandatory = $true, ParameterSetName = "Local")]
    [string]$SourceRepository,

    [Parameter(Mandatory = $true, ParameterSetName = "Upstream")]
    [switch]$FromUpstream,

    # Only used by the CI mode; override to fetch through a mirror.
    [Parameter(ParameterSetName = "Upstream")]
    [string]$UpstreamRepository = "https://github.com/ggml-org/llama.cpp"
)
$ErrorActionPreference = "Stop"
$commit = "5e03bdd8700948b9c41c54dd1b00f28a2aebc03f"
$root = Split-Path $PSScriptRoot -Parent
$destination = Join-Path $root "llm/vendor/llama.cpp"

if ($PSCmdlet.ParameterSetName -eq "Local") {
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
} else {
    if (!(Test-Path -LiteralPath $destination)) {
        New-Item -ItemType Directory -Force -Path (Split-Path $destination -Parent) | Out-Null
        git init --quiet -- $destination
        if ($LASTEXITCODE -ne 0) { throw "git init failed" }
        git -C $destination remote add origin $UpstreamRepository
        if ($LASTEXITCODE -ne 0) { throw "Adding the upstream remote failed" }
        git -C $destination fetch --depth 1 origin $commit
        if ($LASTEXITCODE -ne 0) { throw "Fetching the pinned commit from $UpstreamRepository failed" }
        git -C $destination checkout --detach FETCH_HEAD
        if ($LASTEXITCODE -ne 0) { throw "Pinned checkout failed" }
    }
}

$actual = git -C $destination rev-parse HEAD
if ($LASTEXITCODE -ne 0 -or $actual -ne $commit) { throw "Unexpected llama.cpp revision: $actual" }
$dirty = git -C $destination status --porcelain --untracked-files=all
if ($LASTEXITCODE -ne 0 -or $dirty) { throw "llama.cpp source must be pristine" }
Write-Output "llama.cpp verified at $actual"