[CmdletBinding()]
param(
    [ValidatePattern('^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$')]
    [string] $Version = '1.0.1',

    [switch] $SkipMavenVerify,

    # Build the provider-complete image once on the maintainer workstation. The
    # setup then reuses the exact version/commit-labelled image instead of
    # downloading four toolchains and rebuilding it during installation.
    [switch] $PrepareDockerImage
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($env:OS -ne 'Windows_NT') {
    throw 'The local MINOS Windows candidate must be built on Windows.'
}

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$BuildDistribution = Join-Path $RepoRoot 'scripts\release\build-windows-distribution.ps1'
$BuildInstaller = Join-Path $RepoRoot 'scripts\release\build-windows-installer.ps1'
$McpProbe = Join-Path $RepoRoot 'scripts\install\probe-mcp-backend.ps1'
foreach ($Required in @($BuildDistribution, $BuildInstaller, $McpProbe)) {
    if (-not (Test-Path -LiteralPath $Required -PathType Leaf)) { throw "Missing candidate-build input: $Required" }
}

$Java = if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { '' }
if ([string]::IsNullOrWhiteSpace($Java) -or -not (Test-Path -LiteralPath $Java -PathType Leaf)) {
    throw 'JAVA_HOME must point to a JDK 24 installation before building the Windows candidate.'
}
$Python = @('python.exe','python','python3.exe','python3') |
    ForEach-Object { Get-Command $_ -ErrorAction SilentlyContinue } |
    Where-Object { $_ } |
    Select-Object -First 1
if (-not $Python) {
    throw 'Python 3 is required for Product Facts, documentation and supply-chain validation.'
}

$Head = (git -C $RepoRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($Head)) { throw 'Unable to resolve repository HEAD.' }
$Dirty = @(git -C $RepoRoot status --porcelain=v1 --untracked-files=all)
if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect repository worktree.' }
if ($Dirty.Count -gt 0) {
    throw "Local candidate build requires a clean worktree so VERSION provenance is unambiguous. Dirty entries:`n$($Dirty -join "`n")"
}

Write-Host '=== MINOS local Windows candidate ===' -ForegroundColor Cyan
Write-Host "Version : $Version"
Write-Host "HEAD    : $Head"
Write-Host "Docker  : $(if ($PrepareDockerImage) { 'prebuild exact image' } else { 'not prebuilt' })"
Write-Host 'Network publication: DISABLED (this script never creates tags/releases or invokes GitHub Actions).'

Push-Location $RepoRoot
try {
    & $Python.Source 'scripts/docs/product-facts.py' '--check'
    if ($LASTEXITCODE -ne 0) { throw 'Product Facts are stale; refusing to build a release candidate.' }
    & $Python.Source 'scripts/docs/check-current-docs.py'
    if ($LASTEXITCODE -ne 0) { throw 'Current documentation/release contract is inconsistent; refusing to build a release candidate.' }
}
finally {
    Pop-Location
}

$DistributionParameters = @{ Version = $Version }
if ($SkipMavenVerify) { $DistributionParameters['SkipVerify'] = $true }
& $BuildDistribution @DistributionParameters

$DistRoot = Join-Path $RepoRoot "target\dist\minos-$Version-windows-x64"
$Launcher = Join-Path $DistRoot 'minos.cmd'
$RuntimeModules = Join-Path $DistRoot 'RUNTIME-MODULES.txt'
if (-not (Test-Path -LiteralPath $Launcher -PathType Leaf)) { throw "Candidate launcher missing: $Launcher" }
if (-not (Test-Path -LiteralPath $RuntimeModules -PathType Leaf)) { throw "Runtime module evidence missing: $RuntimeModules" }
$Modules = @([System.IO.File]::ReadAllLines($RuntimeModules, [System.Text.Encoding]::ASCII))
if ($Modules -notcontains 'java.xml') { throw 'Candidate runtime does not contain java.xml.' }

$SmokeHome = Join-Path ([System.IO.Path]::GetTempPath()) ('minos-local-candidate-mcp-' + [Guid]::NewGuid())
try {
    New-Item -ItemType Directory -Force -Path $SmokeHome | Out-Null
    & $McpProbe -LauncherPath $Launcher -CandidateHome $SmokeHome -TimeoutSeconds 30
    if ($LASTEXITCODE -ne 0) { throw "Native MCP handshake failed for local candidate (exit=$LASTEXITCODE)." }
}
finally {
    Remove-Item -LiteralPath $SmokeHome -Recurse -Force -ErrorAction SilentlyContinue
}

$PreparedImage = ''
if ($PrepareDockerImage) {
    $Docker = Get-Command docker -ErrorAction SilentlyContinue
    if (-not $Docker) { throw 'Docker Desktop is required by -PrepareDockerImage.' }
    & $Docker.Source version --format '{{.Server.Version}}' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Docker Desktop is installed but its daemon does not respond.' }

    $Jar = Join-Path $DistRoot 'lib\minos.jar'
    $Dockerfile = Join-Path $DistRoot 'docker\Dockerfile.mcp.release'
    foreach ($Required in @($Jar, $Dockerfile)) {
        if (-not (Test-Path -LiteralPath $Required -PathType Leaf)) { throw "Docker prebuild input missing: $Required" }
    }

    $SafeVersion = $Version.ToLowerInvariant().Replace('+', '-').Replace('SNAPSHOT', 'snapshot')
    $ShortCommit = $Head.Substring(0, [Math]::Min(12, $Head.Length))
    $PreparedImage = "minos-code-intelligence:$SafeVersion-$ShortCommit"
    $Inspect = & $Docker.Source image inspect $PreparedImage --format '{{ index .Config.Labels "org.opencontainers.image.version" }}|{{ index .Config.Labels "org.opencontainers.image.revision" }}|{{ index .Config.Labels "io.minos.providers.prepared" }}' 2>$null
    $ExactImageExists = $LASTEXITCODE -eq 0 -and ([string]$Inspect).Trim() -eq "$Version|$Head|true"
    if ($ExactImageExists) {
        Write-Host "Exact Docker image already prepared: $PreparedImage" -ForegroundColor Cyan
    }
    else {
        $Context = Join-Path ([System.IO.Path]::GetTempPath()) ('minos-local-docker-image-' + [Guid]::NewGuid())
        try {
            New-Item -ItemType Directory -Force -Path $Context | Out-Null
            Copy-Item -LiteralPath $Jar -Destination (Join-Path $Context 'minos.jar') -Force
            $Timestamp = [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
            & $Docker.Source build --file $Dockerfile --tag $PreparedImage `
                --build-arg "MINOS_VERSION=$Version" `
                --build-arg "MINOS_GIT_COMMIT=$Head" `
                --build-arg "MINOS_BUILD_TIMESTAMP=$Timestamp" $Context
            if ($LASTEXITCODE -ne 0) { throw 'Local MINOS Docker image prebuild failed.' }
        }
        finally {
            Remove-Item -LiteralPath $Context -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}

& $BuildInstaller -Version $Version

$Setup = Join-Path $RepoRoot "target\dist\MINOS-$Version-windows-x64-setup.exe"
$SetupChecksum = "$Setup.sha256"
$Zip = Join-Path $RepoRoot "target\dist\minos-$Version-windows-x64.zip"
$ZipChecksum = "$Zip.sha256"
foreach ($Artifact in @($Setup, $SetupChecksum, $Zip, $ZipChecksum)) {
    if (-not (Test-Path -LiteralPath $Artifact -PathType Leaf)) { throw "Candidate artifact missing: $Artifact" }
}

Write-Host ''
Write-Host 'MINOS LOCAL WINDOWS CANDIDATE SUCCESS' -ForegroundColor Green
Write-Host "HEAD          : $Head"
Write-Host "Version       : $Version"
Write-Host "Setup         : $Setup"
Write-Host "Setup SHA-256 : $((Get-FileHash -LiteralPath $Setup -Algorithm SHA256).Hash.ToLowerInvariant())"
Write-Host "ZIP           : $Zip"
Write-Host "ZIP SHA-256   : $((Get-FileHash -LiteralPath $Zip -Algorithm SHA256).Hash.ToLowerInvariant())"
if (-not [string]::IsNullOrWhiteSpace($PreparedImage)) {
    Write-Host "Docker image  : $PreparedImage"
    Write-Host 'Installer Docker build: SKIPPED when this exact image remains in Docker Desktop.'
}
Write-Host 'Publication   : NOT PERFORMED'
Write-Host ''
Write-Host 'Next human gate: launch the setup.exe above, inspect the MCP detection page, then verify a real client connects to MINOS before publication.' -ForegroundColor Yellow
