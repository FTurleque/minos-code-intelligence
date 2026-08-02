[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $ExpectedHead,

    [string] $ProjectsRoot = '',

    [switch] $SkipMavenVerify
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($env:OS -ne 'Windows_NT') {
    throw 'M29-S7 qualification targets Windows + Docker Desktop.'
}

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
if ([string]::IsNullOrWhiteSpace($ProjectsRoot)) {
    $ProjectsRoot = Split-Path -Parent $RepoRoot
}
$ProjectsRoot = [System.IO.Path]::GetFullPath($ProjectsRoot)
if (-not (Test-Path -LiteralPath $ProjectsRoot -PathType Container)) {
    throw "M29-S7 projects root does not exist: $ProjectsRoot"
}

function Invoke-NativeCapture {
    param(
        [Parameter(Mandatory = $true)][string] $File,
        [Parameter(Mandatory = $true)][string[]] $Arguments
    )
    $Previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $Captured = @(& $File @Arguments 2>&1)
        $ExitCode = $LASTEXITCODE
        $Output = (($Captured | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine).Trim()
    }
    finally { $ErrorActionPreference = $Previous }
    return [pscustomobject]@{ ExitCode = $ExitCode; Output = $Output }
}

function Assert-NativeSuccess {
    param(
        [Parameter(Mandatory = $true)][string] $File,
        [Parameter(Mandatory = $true)][string[]] $Arguments,
        [Parameter(Mandatory = $true)][string] $Failure
    )
    $Result = Invoke-NativeCapture -File $File -Arguments $Arguments
    if ($Result.ExitCode -ne 0) { throw "$Failure (exit=$($Result.ExitCode)): $($Result.Output)" }
    if (-not [string]::IsNullOrWhiteSpace($Result.Output)) { Write-Host $Result.Output }
    return $Result.Output
}

function Read-Backend([string] $Home) {
    $Path = Join-Path $Home 'runtime\backend.properties'
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return '' }
    $Line = [System.IO.File]::ReadAllLines($Path, [System.Text.Encoding]::UTF8) |
        Where-Object { $_ -like 'backend=*' } |
        Select-Object -First 1
    if ([string]::IsNullOrWhiteSpace($Line)) { return '' }
    return ($Line -split '=', 2)[1].Trim()
}

function Assert-Backend([string] $Home, [string] $Expected) {
    $Actual = Read-Backend -Home $Home
    if ($Actual -ne $Expected) {
        throw "M29-S7 backend mismatch: expected $Expected, found '$Actual' in $Home"
    }
}

$Git = (Get-Command git -ErrorAction Stop).Source
$Head = (Assert-NativeSuccess -File $Git -Arguments @('-C', $RepoRoot, 'rev-parse', 'HEAD') -Failure 'Unable to resolve M29 HEAD').Trim()
if ($Head -ne $ExpectedHead.Trim()) {
    throw "M29-S7 exact-head mismatch: expected $ExpectedHead, found $Head"
}
$Dirty = (Assert-NativeSuccess -File $Git -Arguments @('-C', $RepoRoot, 'status', '--porcelain') -Failure 'Unable to inspect git status').Trim()
if (-not [string]::IsNullOrWhiteSpace($Dirty)) {
    throw "M29-S7 requires a clean worktree. Dirty entries:`n$Dirty"
}
Write-Host "M29-S7 exact HEAD: $Head" -ForegroundColor Cyan

$ScriptsToParse = @(
    'scripts\install\probe-mcp-backend.ps1',
    'scripts\install\switch-mcp-backend.ps1',
    'scripts\install\verify-mcp-backend-lifecycle.ps1',
    'scripts\install\verify-mcp-client-backend-routing.ps1',
    'scripts\install\verify-installer-template.ps1',
    'scripts\install\install-windows.ps1',
    'scripts\release\build-windows-distribution.ps1',
    'scripts\release\build-windows-installer.ps1',
    'scripts\m29\run-s7.ps1'
)
foreach ($Relative in $ScriptsToParse) {
    $Path = Join-Path $RepoRoot $Relative
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "M29-S7 required script is missing: $Path"
    }
    $Tokens = $null
    $Errors = $null
    [System.Management.Automation.Language.Parser]::ParseFile($Path, [ref]$Tokens, [ref]$Errors) | Out-Null
    if ($Errors.Count -gt 0) {
        throw "M29-S7 PowerShell parse failed for ${Relative}: $($Errors[0].Message)"
    }
}
Write-Host 'M29-S7 PowerShell parse preflight SUCCESS' -ForegroundColor Cyan

# Docker capability is cheap to probe and a hard requirement for this S7 gate.
$Docker = Get-Command docker -ErrorAction SilentlyContinue
if (-not $Docker) {
    throw 'M29-S7 BLOCKED: docker.exe is not installed or not present in PATH.'
}
$DockerServer = Invoke-NativeCapture -File $Docker.Source -Arguments @('version', '--format', '{{.Server.Version}}')
if ($DockerServer.ExitCode -ne 0) {
    throw "M29-S7 BLOCKED: Docker Desktop Linux daemon is unavailable. Probe: $($DockerServer.Output)"
}
Assert-NativeSuccess -File $Docker.Source -Arguments @('compose', 'version') -Failure 'Docker Compose is unavailable' | Out-Null
Write-Host "Docker server: $($DockerServer.Output)" -ForegroundColor Cyan

$Version = '1.0.1-SNAPSHOT'
$Suffix = $Head.Substring(0, [Math]::Min(12, $Head.Length))
$QualificationRoot = Join-Path $env:TEMP "minos-m29-s7-$Suffix"
$OutputRoot = Join-Path $RepoRoot "target\m29\s7-dist-$Suffix"
$Distribution = Join-Path $OutputRoot "minos-$Version-windows-x64"
$NativeInstallRoot = Join-Path $QualificationRoot 'native-install'
$NativeDataRoot = Join-Path $QualificationRoot 'native-data'
$DockerAppRoot = Join-Path $QualificationRoot 'docker-install'
$DockerHome = Join-Path $QualificationRoot 'docker-home'
$DockerRuntimeRoot = Join-Path $QualificationRoot 'docker-runtime'
$DockerDataRoot = Join-Path $QualificationRoot 'docker-data'
$ContainerName = "minos-m29-s7-$Suffix"
$ComposeProject = "minos-m29-s7-$Suffix"
$ReportRoot = Join-Path $RepoRoot 'target\m29'
$ReportPath = Join-Path $ReportRoot "s7-qualification-$Head.json"
$StartedAt = [DateTime]::UtcNow
$Passed = $false
$DockerInstalled = $false
$SmokeSetup = ''
$PreserveSentinel = Join-Path $DockerDataRoot 's7-preserve-sentinel.txt'

try {
    Remove-Item -LiteralPath $QualificationRoot -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $OutputRoot -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $QualificationRoot, $ReportRoot | Out-Null

    Push-Location $RepoRoot
    try {
        # Fail-fast gates first. These are intentionally before Maven and before
        # any Docker image build so a static/doc/transaction defect is cheap.
        $Python = Get-Command python -ErrorAction SilentlyContinue
        if (-not $Python) { $Python = Get-Command python3 -ErrorAction Stop }
        & $Python.Source '.\scripts\docs\check-current-docs.py'
        if ($LASTEXITCODE -ne 0) {
            throw "M29-S7 documentation consistency failed with exit code $LASTEXITCODE"
        }

        & '.\scripts\install\verify-mcp-backend-lifecycle.ps1'
        & '.\scripts\install\verify-mcp-client-backend-routing.ps1'
        & '.\scripts\install\verify-installer-template.ps1'
        Write-Host 'M29-S7 cheap contract gates SUCCESS' -ForegroundColor Cyan

        if (-not $SkipMavenVerify) {
            & '.\mvnw.cmd' clean verify
            if ($LASTEXITCODE -ne 0) {
                throw "M29-S7 Maven qualification failed with exit code $LASTEXITCODE"
            }
        }

        # Build the real Windows distribution and compile an isolated smoke
        # setup so packaging plus Inno Pascal syntax are part of the gate.
        & '.\scripts\release\build-windows-distribution.ps1' `
            -Version $Version `
            -OutputRoot $OutputRoot `
            -SkipVerify
        & '.\scripts\release\build-windows-installer.ps1' `
            -Version $Version `
            -DistributionRoot $Distribution `
            -OutputRoot $OutputRoot `
            -Smoke
    }
    finally { Pop-Location }

    $SmokeSetup = Join-Path $OutputRoot ".smoke\MINOS-$Version-windows-x64-smoke-setup.exe"
    if (-not (Test-Path -LiteralPath $SmokeSetup -PathType Leaf)) {
        throw "M29-S7 smoke setup was not produced: $SmokeSetup"
    }

    # 1) Native-only installation from the built distribution.
    & (Join-Path $RepoRoot 'scripts\install\install-windows.ps1') `
        -Package $Distribution `
        -InstallRoot $NativeInstallRoot `
        -McpBackend native `
        -DataRoot $NativeDataRoot `
        -DockerInstallRoot (Join-Path $QualificationRoot 'native-unused-docker-runtime') `
        -DockerDataRoot (Join-Path $QualificationRoot 'native-unused-docker-data') `
        -DockerContainerName ($ContainerName + '-native-unused') `
        -DockerComposeProject ($ComposeProject + '-native-unused')
    Assert-Backend -Home $NativeDataRoot -Expected 'native'
    Write-Host 'M29-S7 native-only install SUCCESS' -ForegroundColor Green

    # 2) Deterministic same-version native ZIP upgrade.
    & (Join-Path $RepoRoot 'scripts\install\install-windows.ps1') `
        -Package $Distribution `
        -InstallRoot $NativeInstallRoot `
        -McpBackend native `
        -DataRoot $NativeDataRoot `
        -DockerInstallRoot (Join-Path $QualificationRoot 'native-unused-docker-runtime') `
        -DockerDataRoot (Join-Path $QualificationRoot 'native-unused-docker-data') `
        -DockerContainerName ($ContainerName + '-native-unused') `
        -DockerComposeProject ($ComposeProject + '-native-unused')
    Assert-Backend -Home $NativeDataRoot -Expected 'native'
    $NativeBackups = @(Get-ChildItem -LiteralPath $QualificationRoot -Directory -Filter 'native-install.backup-*' -ErrorAction SilentlyContinue)
    if ($NativeBackups.Count -lt 1) {
        throw 'M29-S7 native ZIP upgrade did not preserve a previous installation backup.'
    }
    Write-Host 'M29-S7 ZIP upgrade SUCCESS' -ForegroundColor Green

    # 3) Docker-only fresh installation. This is the single real Docker image
    # build/install in S7 and uses only isolated qualification roots.
    & (Join-Path $RepoRoot 'scripts\install\install-windows.ps1') `
        -Package $Distribution `
        -InstallRoot $DockerAppRoot `
        -McpBackend docker `
        -ProjectsRoot $ProjectsRoot `
        -DataRoot $DockerHome `
        -DockerInstallRoot $DockerRuntimeRoot `
        -DockerDataRoot $DockerDataRoot `
        -DockerContainerName $ContainerName `
        -DockerComposeProject $ComposeProject
    $DockerInstalled = $true
    Assert-Backend -Home $DockerHome -Expected 'docker'
    New-Item -ItemType Directory -Force -Path $DockerDataRoot | Out-Null
    [System.IO.File]::WriteAllText($PreserveSentinel, 'preserve-me', [System.Text.UTF8Encoding]::new($false))
    Write-Host 'M29-S7 Docker-only install SUCCESS' -ForegroundColor Green

    $Switcher = Join-Path $DockerAppRoot 'integration\switch-mcp-backend.ps1'
    $SwitchCommon = @{
        InstallRoot = $DockerAppRoot
        DataRoot = $DockerHome
        DockerInstallRoot = $DockerRuntimeRoot
        DockerDataRoot = $DockerDataRoot
        DockerContainerName = $ContainerName
        DockerComposeProject = $ComposeProject
    }

    # 4) Docker -> native commits native then retires Docker, preserving data.
    & $Switcher @SwitchCommon -TargetBackend native
    Assert-Backend -Home $DockerHome -Expected 'native'
    if (-not (Test-Path -LiteralPath $PreserveSentinel -PathType Leaf)) {
        throw 'M29-S7 Docker -> native switch deleted persistent Docker data.'
    }
    Write-Host 'M29-S7 Docker -> native SUCCESS' -ForegroundColor Green

    # 5) Native -> Docker must reuse the exact same managed runtime, proving
    # Start + Validate + MCP handshake without a second image build.
    & $Switcher @SwitchCommon -TargetBackend docker -ProjectsRoot $ProjectsRoot
    Assert-Backend -Home $DockerHome -Expected 'docker'
    $SwitchState = Get-Content -Raw -LiteralPath (Join-Path $DockerHome 'runtime\backend-switch.json') | ConvertFrom-Json
    if (-not [bool]$SwitchState.reusedDockerRuntime) {
        throw 'M29-S7 native -> Docker did not reuse the qualified managed Docker runtime.'
    }
    Write-Host 'M29-S7 native -> Docker reuse SUCCESS' -ForegroundColor Green

    # 6) End with native selected and persistent Docker data still present.
    & $Switcher @SwitchCommon -TargetBackend native
    Assert-Backend -Home $DockerHome -Expected 'native'

    # 7) Runtime uninstall must preserve Docker data by default.
    $DockerWorkflow = Join-Path $DockerAppRoot 'docker\scripts\prod-mcp-release.ps1'
    & $DockerWorkflow `
        -Action Uninstall `
        -InstallRoot $DockerRuntimeRoot `
        -DataRoot $DockerDataRoot `
        -ContainerName $ContainerName `
        -ComposeProject $ComposeProject
    $DockerInstalled = $false
    if (-not (Test-Path -LiteralPath $PreserveSentinel -PathType Leaf)) {
        throw 'M29-S7 Docker runtime uninstall deleted persistent data without explicit purge.'
    }
    Write-Host 'M29-S7 uninstall-preserve SUCCESS' -ForegroundColor Green

    # 8) Destructive purge is explicit and separate from runtime uninstall.
    Remove-Item -LiteralPath $DockerHome -Recurse -Force -ErrorAction Stop
    Remove-Item -LiteralPath $DockerDataRoot -Recurse -Force -ErrorAction Stop
    if ((Test-Path -LiteralPath $DockerHome) -or (Test-Path -LiteralPath $DockerDataRoot)) {
        throw 'M29-S7 explicit qualification purge did not remove isolated MINOS data roots.'
    }
    Write-Host 'M29-S7 explicit purge SUCCESS' -ForegroundColor Green

    $FinalDirty = (Assert-NativeSuccess -File $Git -Arguments @('-C', $RepoRoot, 'status', '--porcelain') -Failure 'Unable to inspect final git status').Trim()
    if (-not [string]::IsNullOrWhiteSpace($FinalDirty)) {
        throw "M29-S7 qualification modified the source checkout:`n$FinalDirty"
    }

    $Passed = $true
    Write-Host 'M29-S7 INSTALLER SWITCHING AND LIFECYCLE QUALIFICATION SUCCESS' -ForegroundColor Green
}
finally {
    if ($DockerInstalled -and (Test-Path -LiteralPath (Join-Path $DockerAppRoot 'docker\scripts\prod-mcp-release.ps1') -PathType Leaf)) {
        try {
            & (Join-Path $DockerAppRoot 'docker\scripts\prod-mcp-release.ps1') `
                -Action Uninstall `
                -InstallRoot $DockerRuntimeRoot `
                -DataRoot $DockerDataRoot `
                -ContainerName $ContainerName `
                -ComposeProject $ComposeProject
        }
        catch { Write-Warning "M29-S7 Docker cleanup warning: $($_.Exception.Message)" }
    }

    New-Item -ItemType Directory -Force -Path $ReportRoot | Out-Null
    [ordered]@{
        formatVersion = 1
        milestone = 'M29-S7'
        head = $Head
        startedAt = $StartedAt.ToString('o')
        finishedAt = [DateTime]::UtcNow.ToString('o')
        result = if ($Passed) { 'PASS' } else { 'FAIL_OR_BLOCKED' }
        dockerServer = [string]$DockerServer.Output
        projectsRoot = $ProjectsRoot
        windowsDistribution = $Distribution
        smokeSetup = $SmokeSetup
        nativeInstallRoot = $NativeInstallRoot
        dockerInstallRoot = $DockerAppRoot
        backendSequence = @('native-only', 'native-upgrade', 'docker-only', 'native', 'docker-reuse', 'native')
        uninstallPreservedData = $Passed
        explicitPurge = $Passed
    } | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $ReportPath -Encoding utf8
    Write-Host "M29-S7 report: $ReportPath"

    Remove-Item -LiteralPath $QualificationRoot -Recurse -Force -ErrorAction SilentlyContinue
}
