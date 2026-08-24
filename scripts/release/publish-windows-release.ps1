[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$')]
    [string] $Version,

    [string] $TargetCommit = '',

    [switch] $SkipBuild,
    [switch] $ValidateOnly,
    [switch] $PublishOnly,

    # Forwarded verbatim to build-windows-installer.ps1. See that script for the rationale: on the
    # release/CI path these must be the compiler explicitly qualified by the caller, never one
    # discovered ambiguously via PATH.
    [string] $IsccPath = '',
    [string] $RequiredIsccVersion = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($ValidateOnly -and $PublishOnly) {
    throw 'ValidateOnly and PublishOnly are mutually exclusive.'
}

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
if ($env:OS -ne 'Windows_NT') {
    throw 'MINOS Windows releases must be built and validated on Windows.'
}

function Invoke-NativeChecked {
    param(
        [Parameter(Mandatory = $true)][string] $File,
        [Parameter(Mandatory = $true)][string[]] $Arguments,
        [Parameter(Mandatory = $true)][string] $Failure
    )
    $Previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $File @Arguments
        $Exit = $LASTEXITCODE
    }
    finally { $ErrorActionPreference = $Previous }
    if ($Exit -ne 0) { throw "$Failure (exit=$Exit)" }
}

function Invoke-ProcessChecked {
    param(
        [Parameter(Mandatory = $true)][string] $File,
        [Parameter(Mandatory = $true)][string[]] $Arguments,
        [Parameter(Mandatory = $true)][string] $Failure
    )
    $Process = Start-Process -FilePath $File -ArgumentList $Arguments -Wait -PassThru
    if ($Process.ExitCode -ne 0) { throw "$Failure (exit=$($Process.ExitCode))" }
}

function Invoke-PowerShellScriptChecked {
    param(
        [Parameter(Mandatory = $true)][string] $Script,
        [Parameter(Mandatory = $true)][hashtable] $Parameters,
        [Parameter(Mandatory = $true)][string] $Failure
    )
    try { & $Script @Parameters }
    catch { throw "$Failure`: $($_.Exception.Message)" }
}

function Invoke-MinosVersion {
    param([string] $Launcher, [string] $Failure)
    $Info = New-Object System.Diagnostics.ProcessStartInfo
    $Info.FileName = $env:ComSpec
    $Info.Arguments = '/d /s /c ""{0}" --version"' -f $Launcher
    $Info.WorkingDirectory = Split-Path -Parent $Launcher
    $Info.UseShellExecute = $false
    $Info.CreateNoWindow = $true
    $Info.RedirectStandardOutput = $true
    $Info.RedirectStandardError = $true
    $Process = New-Object System.Diagnostics.Process
    $Process.StartInfo = $Info
    try {
        if (-not $Process.Start()) { throw "$Failure (process did not start)" }
        $Out = $Process.StandardOutput.ReadToEnd().Trim()
        $Err = $Process.StandardError.ReadToEnd().Trim()
        $Process.WaitForExit()
        if ($Process.ExitCode -ne 0) { throw "$Failure (exit=$($Process.ExitCode)): $Err" }
        return $Out
    }
    finally { $Process.Dispose() }
}

function Invoke-McpHandshake {
    param(
        [Parameter(Mandatory = $true)][string] $Launcher,
        [Parameter(Mandatory = $true)][string] $McpHome,
        [Parameter(Mandatory = $true)][string] $Context
    )
    New-Item -ItemType Directory -Force -Path $McpHome | Out-Null
    Invoke-NativeChecked -File $script:Java -Arguments @($script:McpSmokeSource, $Launcher, $McpHome) `
        -Failure "$Context native MCP initialize/tools-list handshake failed"
}

function Assert-VersionProvenance {
    param([string] $VersionFile, [string] $ExpectedVersion, [string] $ExpectedCommit, [string] $Context)
    if (-not (Test-Path -LiteralPath $VersionFile -PathType Leaf)) { throw "$Context provenance file not found: $VersionFile" }
    $Metadata = @{}
    foreach ($Line in Get-Content -LiteralPath $VersionFile) {
        if ($Line -match '^([^=]+)=(.*)$') { $Metadata[$Matches[1].Trim()] = $Matches[2].Trim() }
    }
    if ($Metadata['version'] -ne $ExpectedVersion) { throw "$Context version provenance mismatch: expected=$ExpectedVersion actual=$($Metadata['version'])" }
    if ($Metadata['commit'] -ne $ExpectedCommit) { throw "$Context commit provenance mismatch: expected=$ExpectedCommit actual=$($Metadata['commit'])" }
    if ([string]::IsNullOrWhiteSpace($Metadata['runtimeModules']) -or $Metadata['runtimeModules'] -notmatch '(^|,)java\.xml(,|$)') {
        throw "$Context runtime provenance does not include java.xml."
    }
}

function Assert-RuntimeModuleEvidence([string] $Root, [string] $Context) {
    $Path = Join-Path $Root 'RUNTIME-MODULES.txt'
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "$Context runtime module evidence missing: $Path" }
    $Modules = @([System.IO.File]::ReadAllLines($Path, [System.Text.Encoding]::ASCII))
    if ($Modules -notcontains 'java.xml') { throw "$Context packaged runtime is missing java.xml." }
}

function Verify-Sha256([string] $Artifact, [string] $Checksum) {
    if (-not (Test-Path -LiteralPath $Artifact -PathType Leaf)) { throw "Release artifact not found: $Artifact" }
    if (-not (Test-Path -LiteralPath $Checksum -PathType Leaf)) { throw "Release checksum not found: $Checksum" }
    $Expected = ((Get-Content -LiteralPath $Checksum | Select-Object -First 1) -split '\s+')[0]
    $Actual = (Get-FileHash -LiteralPath $Artifact -Algorithm SHA256).Hash.ToLowerInvariant()
    if ([string]::IsNullOrWhiteSpace($Expected) -or $Expected.ToLowerInvariant() -ne $Actual) {
        throw "Release checksum mismatch for $Artifact`: expected=$Expected actual=$Actual"
    }
    return $Actual
}

$Git = Get-Command git -ErrorAction SilentlyContinue
if (-not $Git) { throw 'git is required to build or publish a MINOS release.' }
$Head = ((& $Git.Source -C $RepoRoot rev-parse HEAD) | Select-Object -First 1).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($Head)) { throw 'Unable to resolve the current Git HEAD.' }
$Dirty = @(& $Git.Source -C $RepoRoot status --porcelain=v1 --untracked-files=all)
if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect the Git worktree.' }
if ($Dirty.Count -gt 0) { throw "Release validation requires a clean worktree. Dirty entries:`n$($Dirty -join "`n")" }
if ([string]::IsNullOrWhiteSpace($TargetCommit)) { $TargetCommit = $Head }
if ($TargetCommit -ne $Head) { throw "Release target must be the exact commit used to build the assets. HEAD=$Head target=$TargetCommit" }

if (-not $PublishOnly) {
    # Build and smoke-test prerequisites. PublishOnly consumes artifacts a prior, unprivileged
    # build/validate job already produced and verified, so it never needs a JDK or the MCP smoke
    # harness to publish them.
    $JavaHome = $env:JAVA_HOME
    if ([string]::IsNullOrWhiteSpace($JavaHome)) { throw 'JAVA_HOME must point to JDK 24 for release validation.' }
    $script:Java = Join-Path $JavaHome 'bin\java.exe'
    $script:McpSmokeSource = Join-Path $RepoRoot 'scripts\m14\MinosNativeMcpSmoke.java'
    foreach ($Required in @($script:Java, $script:McpSmokeSource)) {
        if (-not (Test-Path -LiteralPath $Required -PathType Leaf)) { throw "Release MCP smoke prerequisite missing: $Required" }
    }
}

$Gh = $null
$Repository = ''
if (-not $ValidateOnly) {
    $Gh = Get-Command gh -ErrorAction SilentlyContinue
    if (-not $Gh) { throw 'GitHub CLI (gh) is required to publish a MINOS release.' }
    Invoke-NativeChecked -File $Gh.Source -Arguments @('auth','status') -Failure 'GitHub CLI is not authenticated'
    $Repository = $env:GITHUB_REPOSITORY
    if ([string]::IsNullOrWhiteSpace($Repository)) {
        $RepoJson = (& $Gh.Source repo view --json nameWithOwner | Out-String).Trim()
        if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($RepoJson)) { throw 'Unable to resolve GitHub repository.' }
        $Repository = ($RepoJson | ConvertFrom-Json).nameWithOwner
    }
}

if (-not $PublishOnly) {
    $BuildDistribution = Join-Path $RepoRoot 'scripts\release\build-windows-distribution.ps1'
    $BuildInstaller = Join-Path $RepoRoot 'scripts\release\build-windows-installer.ps1'
    $InstallerParameters = @{ Version=$Version }
    if (-not [string]::IsNullOrWhiteSpace($IsccPath)) { $InstallerParameters['IsccPath'] = $IsccPath }
    if (-not [string]::IsNullOrWhiteSpace($RequiredIsccVersion)) { $InstallerParameters['RequiredIsccVersion'] = $RequiredIsccVersion }
    if (-not $SkipBuild) {
        Invoke-PowerShellScriptChecked -Script $BuildDistribution -Parameters @{ Version=$Version } -Failure 'Windows release distribution build failed'
        Invoke-PowerShellScriptChecked -Script $BuildInstaller -Parameters $InstallerParameters -Failure 'Windows release setup build failed'
    }
    # Always compile a non-shippable setup with a distinct AppId for automated install/uninstall smoke.
    $SmokeInstallerParameters = $InstallerParameters.Clone()
    $SmokeInstallerParameters['Smoke'] = $true
    Invoke-PowerShellScriptChecked -Script $BuildInstaller -Parameters $SmokeInstallerParameters -Failure 'Isolated Windows smoke setup build failed'
}

$DistributionName = "minos-$Version-windows-x64"
$Zip = Join-Path $RepoRoot "target\dist\$DistributionName.zip"
$ZipChecksum = "$Zip.sha256"
$Setup = Join-Path $RepoRoot "target\dist\MINOS-$Version-windows-x64-setup.exe"
$SetupChecksum = "$Setup.sha256"
$SmokeSetup = Join-Path $RepoRoot "target\dist\.smoke\MINOS-$Version-windows-x64-smoke-setup.exe"
$Sbom = Join-Path $RepoRoot "target\dist\minos-$Version.cdx.json"
$SbomChecksum = "$Sbom.sha256"
$Notices = Join-Path $RepoRoot "target\dist\MINOS-$Version-THIRD-PARTY-NOTICES.txt"
$NoticesChecksum = "$Notices.sha256"
$RequiredInstalledFiles = @(
    'minos.cmd','minos-mcp.cmd','VERSION','RUNTIME-MODULES.txt','RELEASE-MANIFEST.json','install.ps1',
    'app\minos.exe','app\runtime\bin\java.exe','app\runtime\bin\server\jvm.dll','app\runtime\lib\modules',
    'lib\minos.jar','supply-chain\minos.cdx.json','supply-chain\THIRD-PARTY-NOTICES.txt',
    'integration\configure-mcp-clients.ps1','integration\configure-mcp-clients-setup.ps1',
    'integration\configure-codex-mcp.ps1','integration\detect-mcp-clients.ps1','integration\uninstall-mcp-clients.ps1',
    'integration\update-installation.ps1','integration\switch-mcp-backend.ps1','integration\probe-mcp-backend.ps1',
    'docker\Dockerfile.mcp.release','docker\compose.mcp.prod.yaml',
    'docker\scripts\prod-mcp-release.ps1','docker\scripts\configure-docker-mcp.ps1'
)

$ZipHash = Verify-Sha256 $Zip $ZipChecksum
$SetupHash = Verify-Sha256 $Setup $SetupChecksum
$SbomHash = Verify-Sha256 $Sbom $SbomChecksum
$NoticesHash = Verify-Sha256 $Notices $NoticesChecksum

if (-not $PublishOnly) {
    if (-not (Test-Path -LiteralPath $SmokeSetup -PathType Leaf)) { throw "Isolated smoke setup missing: $SmokeSetup" }

    # ZIP: extract, install through the shipped portable installer, validate provenance,
    # runtime modules, version, and a real MCP initialize + tools/list handshake.
    $ZipSmokeRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('minos-release-zip-smoke-' + [Guid]::NewGuid())
    $ExtractRoot = Join-Path $ZipSmokeRoot 'package'
    $ZipInstallRoot = Join-Path $ZipSmokeRoot 'installed'
    try {
        New-Item -ItemType Directory -Force -Path $ExtractRoot | Out-Null
        Expand-Archive -LiteralPath $Zip -DestinationPath $ExtractRoot -Force
        $PackagedRoot = Join-Path $ExtractRoot $DistributionName
        $PackagedInstaller = Join-Path $PackagedRoot 'install.ps1'
        if (-not (Test-Path -LiteralPath $PackagedInstaller -PathType Leaf)) { throw "Packaged portable installer not found: $PackagedInstaller" }
        Assert-VersionProvenance (Join-Path $PackagedRoot 'VERSION') $Version $TargetCommit 'ZIP'
        Assert-RuntimeModuleEvidence $PackagedRoot 'ZIP'
        Invoke-PowerShellScriptChecked -Script $PackagedInstaller -Parameters @{ Package=$Zip; InstallRoot=$ZipInstallRoot } -Failure 'Packaged portable installer smoke test failed'
        foreach ($Required in $RequiredInstalledFiles) {
            if (-not (Test-Path -LiteralPath (Join-Path $ZipInstallRoot $Required) -PathType Leaf)) { throw "Portable installer did not install required file: $Required" }
        }
        $InstalledMinos = Join-Path $ZipInstallRoot 'minos.cmd'
        $VersionOutput = Invoke-MinosVersion $InstalledMinos 'Portable MINOS --version failed'
        if ($VersionOutput -ne "MINOS $Version") { throw "Portable MINOS version mismatch: expected='MINOS $Version' actual='$VersionOutput'" }
        Assert-VersionProvenance (Join-Path $ZipInstallRoot 'VERSION') $Version $TargetCommit 'Portable installation'
        Assert-RuntimeModuleEvidence $ZipInstallRoot 'Portable installation'
        Invoke-McpHandshake $InstalledMinos (Join-Path $ZipSmokeRoot 'mcp-home') 'Portable installation'
    }
    finally { Remove-Item -LiteralPath $ZipSmokeRoot -Recurse -Force -ErrorAction SilentlyContinue }

    # Setup: automated install/uninstall uses ONLY the isolated smoke setup. It has a
    # different AppId and skips all global cleanup hooks, so local validation cannot
    # alter a real MINOS installation, PATH, Docker state or MCP client state.
    $SetupSmokeRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('minos-release-setup-smoke-' + [Guid]::NewGuid())
    $SetupInstallRoot = Join-Path $SetupSmokeRoot 'installed with spaces'
    $SetupInstalled = $false
    $SetupUninstalled = $false
    try {
        New-Item -ItemType Directory -Force -Path $SetupSmokeRoot | Out-Null
        Invoke-ProcessChecked -File $SmokeSetup -Arguments @('/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART',('/DIR="{0}"' -f $SetupInstallRoot),'/TASKS="!addtopath"') -Failure 'MINOS isolated setup silent installation failed'
        $SetupInstalled = $true
        foreach ($Required in $RequiredInstalledFiles) {
            if (-not (Test-Path -LiteralPath (Join-Path $SetupInstallRoot $Required) -PathType Leaf)) { throw "MINOS isolated setup did not install required file: $Required" }
        }
        $InstalledMinos = Join-Path $SetupInstallRoot 'minos.cmd'
        $VersionOutput = Invoke-MinosVersion $InstalledMinos 'Isolated setup MINOS --version failed'
        if ($VersionOutput -ne "MINOS $Version") { throw "Isolated setup version mismatch: expected='MINOS $Version' actual='$VersionOutput'" }
        Assert-VersionProvenance (Join-Path $SetupInstallRoot 'VERSION') $Version $TargetCommit 'Isolated setup installation'
        Assert-RuntimeModuleEvidence $SetupInstallRoot 'Isolated setup installation'
        Invoke-McpHandshake $InstalledMinos (Join-Path $SetupSmokeRoot 'mcp-home') 'Isolated setup installation'

        $Uninstaller = Get-ChildItem -LiteralPath $SetupInstallRoot -File -Filter 'unins*.exe' | Sort-Object Name | Select-Object -First 1 -ExpandProperty FullName
        if ([string]::IsNullOrWhiteSpace($Uninstaller)) { throw "Isolated setup did not register an uninstaller under $SetupInstallRoot" }
        Invoke-ProcessChecked -File $Uninstaller -Arguments @('/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART') -Failure 'MINOS isolated setup silent uninstall failed'
        $SetupUninstalled = $true
        # Inno's own uninstaller self-deletes (unins*.exe/.dat) via a detached
        # helper process that starts only after the waited-on process this
        # call already blocked on has exited -- give it a moment before
        # checking, or this flakes on a race that has nothing to do with
        # RemoveMinosProgramPayload's own cleanup already having completed.
        Start-Sleep -Seconds 2
        if (Test-Path -LiteralPath $SetupInstallRoot) { throw "MINOS isolated setup uninstall left the program directory behind: $SetupInstallRoot" }
    }
    finally {
        if ($SetupInstalled -and -not $SetupUninstalled) {
            try {
                $Rollback = Get-ChildItem -LiteralPath $SetupInstallRoot -File -Filter 'unins*.exe' -ErrorAction SilentlyContinue | Sort-Object Name | Select-Object -First 1 -ExpandProperty FullName
                if ($Rollback) { Invoke-ProcessChecked -File $Rollback -Arguments @('/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART') -Failure 'MINOS isolated setup rollback uninstall failed' }
            } catch { Write-Warning "Isolated setup rollback failed: $($_.Exception.Message)" }
        }
        Remove-Item -LiteralPath $SetupSmokeRoot -Recurse -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath (Join-Path $RepoRoot 'target\dist\.smoke') -Recurse -Force -ErrorAction SilentlyContinue
    }
}

if ($ValidateOnly) {
    Write-Host ''
    Write-Host 'MINOS Windows release VALIDATION SUCCESS' -ForegroundColor Green
    Write-Host "Commit        : $TargetCommit"
    Write-Host "Setup         : $Setup"
    Write-Host "Setup SHA-256 : $SetupHash"
    Write-Host "ZIP           : $Zip"
    Write-Host "ZIP SHA-256   : $ZipHash"
    Write-Host "MCP handshake : PASS (ZIP + isolated setup)"
    Write-Host "SBOM SHA-256  : $SbomHash"
    Write-Host "Notices SHA   : $NoticesHash"
    return
}

$Tag = "v$Version"
$Previous = $ErrorActionPreference
try {
    $ErrorActionPreference = 'Continue'
    $ReleaseProbeOutput = ((& $Gh.Source release view $Tag --repo $Repository 2>&1) | Out-String).Trim()
    $ReleaseProbeExitCode = $LASTEXITCODE
}
finally { $ErrorActionPreference = $Previous }
if ($ReleaseProbeExitCode -eq 0) { throw "GitHub Release $Tag already exists. Releases are immutable; publish a new version instead." }
if ($ReleaseProbeOutput -notmatch '(?i)release not found') { throw "Unable to check GitHub Release $Tag (exit=$ReleaseProbeExitCode): $ReleaseProbeOutput" }
$ExistingTag = @(& $Git.Source -C $RepoRoot ls-remote --tags origin "refs/tags/$Tag")
if ($LASTEXITCODE -ne 0) { throw "Unable to check whether tag $Tag already exists on origin." }
if ($ExistingTag.Count -gt 0) { throw "Git tag $Tag already exists on origin without a release. Resolve that tag explicitly before publishing." }

$ReleaseArguments = @(
    'release','create',$Tag,$Setup,$SetupChecksum,$Zip,$ZipChecksum,$Sbom,$SbomChecksum,$Notices,$NoticesChecksum,
    '--repo',$Repository,'--target',$TargetCommit,'--title',"MINOS $Version",'--generate-notes'
)
if ($Version -match '-') { $ReleaseArguments += '--prerelease' }
Invoke-NativeChecked -File $Gh.Source -Arguments $ReleaseArguments -Failure "GitHub Release $Tag publication failed"

Write-Host ''
Write-Host 'MINOS GitHub Release SUCCESS' -ForegroundColor Green
Write-Host "Repository    : $Repository"
Write-Host "Tag           : $Tag"
Write-Host "Commit        : $TargetCommit"
Write-Host "Setup SHA-256 : $SetupHash"
Write-Host "ZIP SHA-256   : $ZipHash"
Write-Host "MCP handshake : PASS (ZIP + isolated setup)"
Write-Host "SBOM SHA-256  : $SbomHash"
Write-Host "Notices SHA   : $NoticesHash"
