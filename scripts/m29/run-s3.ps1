[CmdletBinding()]
param(
    [string] $ProjectsRoot = '',
    [string] $FixtureRelativePath = 'minos-code-intelligence/fixtures/java/java-multi-module',
    [string] $ExpectedHead = '',
    [switch] $SkipMavenVerify,
    [switch] $KeepArtifacts
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($env:OS -ne 'Windows_NT') {
    throw 'M29-S3 qualification currently targets the packaged Windows host + Docker Desktop path.'
}

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
if ([string]::IsNullOrWhiteSpace($ProjectsRoot)) {
    $ProjectsRoot = Split-Path -Parent $RepoRoot
}
$ProjectsRoot = [System.IO.Path]::GetFullPath($ProjectsRoot)
$FixtureHostPath = [System.IO.Path]::GetFullPath((Join-Path $ProjectsRoot $FixtureRelativePath))
if (-not (Test-Path -LiteralPath $FixtureHostPath -PathType Container)) {
    throw "M29-S3 fixture project does not exist: $FixtureHostPath"
}
if (-not (Test-Path -LiteralPath (Join-Path $FixtureHostPath 'pom.xml') -PathType Leaf)) {
    throw "M29-S3 fixture must be the controlled Maven project used to qualify scip-java: $FixtureHostPath"
}

function Invoke-NativeCapture {
    param(
        [Parameter(Mandatory = $true)][string] $File,
        [Parameter(Mandatory = $true)][string[]] $Arguments
    )
    $Previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $Output = ((& $File @Arguments 2>&1) | Out-String).Trim()
        $ExitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $Previous
    }
    return [pscustomobject]@{ ExitCode = $ExitCode; Output = $Output }
}

function Assert-NativeSuccess {
    param(
        [Parameter(Mandatory = $true)][string] $File,
        [Parameter(Mandatory = $true)][string[]] $Arguments,
        [Parameter(Mandatory = $true)][string] $Failure
    )
    $Result = Invoke-NativeCapture -File $File -Arguments $Arguments
    if ($Result.ExitCode -ne 0) {
        throw "$Failure (exit=$($Result.ExitCode)): $($Result.Output)"
    }
    if (-not [string]::IsNullOrWhiteSpace($Result.Output)) {
        Write-Host $Result.Output
    }
    return $Result.Output
}

$Git = (Get-Command git -ErrorAction Stop).Source
$Head = (Assert-NativeSuccess -File $Git -Arguments @('-C', $RepoRoot, 'rev-parse', 'HEAD') -Failure 'Unable to resolve M29 HEAD').Trim()
if (-not [string]::IsNullOrWhiteSpace($ExpectedHead) -and $Head -ne $ExpectedHead.Trim()) {
    throw "M29-S3 exact-head mismatch: expected $ExpectedHead, found $Head"
}
$Dirty = (Assert-NativeSuccess -File $Git -Arguments @('-C', $RepoRoot, 'status', '--porcelain') -Failure 'Unable to inspect git status').Trim()
if (-not [string]::IsNullOrWhiteSpace($Dirty)) {
    throw "M29-S3 requires a clean worktree. Dirty entries:`n$Dirty"
}
Write-Host "M29-S3 exact HEAD: $Head" -ForegroundColor Cyan
Write-Host "M29-S3 controlled fixture: $FixtureRelativePath" -ForegroundColor Cyan

if (-not $SkipMavenVerify) {
    Push-Location $RepoRoot
    try {
        & '.\mvnw.cmd' clean verify
        if ($LASTEXITCODE -ne 0) {
            throw "M29-S3 Maven qualification failed with exit code $LASTEXITCODE"
        }
        $Python = Get-Command python -ErrorAction SilentlyContinue
        if (-not $Python) { $Python = Get-Command python3 -ErrorAction Stop }
        & $Python.Source '.\scripts\docs\check-current-docs.py'
        if ($LASTEXITCODE -ne 0) {
            throw "M29-S3 documentation consistency failed with exit code $LASTEXITCODE"
        }
    }
    finally {
        Pop-Location
    }
}

$Docker = Get-Command docker -ErrorAction SilentlyContinue
if (-not $Docker) {
    throw 'M29-S3 BLOCKED: docker.exe is not installed or not present in PATH.'
}
$DockerServer = Invoke-NativeCapture -File $Docker.Source -Arguments @('version', '--format', '{{.Server.Version}}')
if ($DockerServer.ExitCode -ne 0) {
    throw "M29-S3 BLOCKED: Docker Desktop Linux daemon is unavailable. Start Docker Desktop and wait until the desktop-linux engine is running. Probe: $($DockerServer.Output)"
}
Write-Host "Docker server: $($DockerServer.Output)" -ForegroundColor Cyan
Assert-NativeSuccess -File $Docker.Source -Arguments @('compose', 'version') -Failure 'Docker Compose is unavailable' | Out-Null

$Jar = Join-Path $RepoRoot 'target\minos-code-intelligence-1.0.1-SNAPSHOT-all.jar'
if (-not (Test-Path -LiteralPath $Jar -PathType Leaf)) {
    throw "M29-S3 shaded JAR is missing after qualification: $Jar"
}
$Workflow = Join-Path $RepoRoot 'docker\scripts\prod-mcp-release.ps1'
$Smoke = Join-Path $RepoRoot 'scripts\m14\MinosNativeMcpSmoke.java'
foreach ($Required in @($Workflow, $Smoke)) {
    if (-not (Test-Path -LiteralPath $Required -PathType Leaf)) {
        throw "M29-S3 required qualification asset is missing: $Required"
    }
}

$Suffix = $Head.Substring(0, [Math]::Min(12, $Head.Length))
$InstallRoot = Join-Path $env:TEMP "minos-m29-s3-runtime-$Suffix"
$DataRoot = Join-Path $env:TEMP "minos-m29-s3-data-$Suffix"
$ReportRoot = Join-Path $RepoRoot 'target\m29'
$ReportPath = Join-Path $ReportRoot "s3-qualification-$Head.json"
$ContainerName = "minos-m29-s3-$Suffix"
$ComposeProject = "minos-m29-s3-$Suffix"
$StartedAt = [DateTime]::UtcNow
$Passed = $false

function Invoke-Workflow {
    param(
        [Parameter(Mandatory = $true)][ValidateSet('Install', 'Start', 'Attach', 'Admin', 'Status', 'Validate', 'Stop', 'Uninstall')][string] $Action,
        [string[]] $MinosArguments = @(),
        [switch] $Install
    )
    $Parameters = @{
        Action = $Action
        InstallRoot = $InstallRoot
        DataRoot = $DataRoot
        ContainerName = $ContainerName
        ComposeProject = $ComposeProject
    }
    if ($Install) {
        $Parameters['Jar'] = $Jar
        $Parameters['Version'] = '1.0.1-SNAPSHOT'
        $Parameters['Commit'] = $Head
        $Parameters['ProjectsRoot'] = $ProjectsRoot
    }
    if ($MinosArguments.Count -gt 0) {
        $Parameters['MinosArguments'] = $MinosArguments
    }
    & $Workflow @Parameters
}

function Invoke-McpHandshake {
    $Wrapper = Join-Path $InstallRoot 'm29-s3-mcp.cmd'
    $PowerShell = (Get-Command powershell.exe -ErrorAction Stop).Source
    $EscapedWorkflow = $Workflow.Replace('"', '""')
    $EscapedInstall = $InstallRoot.Replace('"', '""')
    $EscapedData = $DataRoot.Replace('"', '""')
    $EscapedContainer = $ContainerName.Replace('"', '""')
    $EscapedProject = $ComposeProject.Replace('"', '""')
    @"
@echo off
"$PowerShell" -NoProfile -ExecutionPolicy Bypass -File "$EscapedWorkflow" -Action Attach -InstallRoot "$EscapedInstall" -DataRoot "$EscapedData" -ContainerName "$EscapedContainer" -ComposeProject "$EscapedProject"
"@ | Set-Content -LiteralPath $Wrapper -Encoding ascii

    $Java = Get-Command java -ErrorAction Stop
    & $Java.Source $Smoke $Wrapper $DataRoot
    if ($LASTEXITCODE -ne 0) {
        throw "M29-S3 MCP STDIO handshake failed with exit code $LASTEXITCODE"
    }
}

try {
    Remove-Item -LiteralPath $InstallRoot -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $DataRoot -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $ReportRoot | Out-Null

    Invoke-Workflow -Action Install -Install
    Invoke-Workflow -Action Validate

    Invoke-Workflow -Action Admin -MinosArguments @('project', 'list', '--format', 'json')
    $ContainerFixture = '/workspace/projects/' + ($FixtureRelativePath.Replace('\', '/').Trim('/'))
    Invoke-Workflow -Action Admin -MinosArguments @('project', 'add', $ContainerFixture, '--name', 'm29-s3-fixture', '--format', 'json')
    Invoke-Workflow -Action Admin -MinosArguments @('project', 'inspect', 'm29-s3-fixture', '--format', 'json')

    # S3 deliberately uses the controlled Java multi-module fixture instead of the MINOS repository
    # root. The gate here is the Docker administration/indexing plane itself: scip-java must compile
    # and index a read-only Maven project from writable MINOS staging with packaged Maven and bounded
    # executable tmpfs. Polyglot monorepo module-root routing is qualified separately in M29-S5.
    Invoke-Workflow -Action Admin -MinosArguments @('index', 'm29-s3-fixture', '--format', 'json')
    Invoke-Workflow -Action Admin -MinosArguments @('index-status', 'm29-s3-fixture', '--format', 'json')
    Invoke-Workflow -Action Admin -MinosArguments @('semantic', 'status', 'm29-s3-fixture', '--format', 'json')
    Invoke-Workflow -Action Admin -MinosArguments @('hybrid', 'status', 'm29-s3-fixture', '--format', 'json')

    Invoke-Workflow -Action Start
    Invoke-McpHandshake

    # Force a real recreate and prove that the bind-mounted business state survives it.
    Invoke-Workflow -Action Start
    Invoke-Workflow -Action Admin -MinosArguments @('project', 'inspect', 'm29-s3-fixture', '--format', 'json')
    Invoke-Workflow -Action Admin -MinosArguments @('index-status', 'm29-s3-fixture', '--format', 'json')
    Invoke-McpHandshake

    $Passed = $true
    Write-Host 'M29-S3 DOCKER ADMINISTRATION QUALIFICATION SUCCESS' -ForegroundColor Green
}
finally {
    [ordered]@{
        formatVersion = 1
        milestone = 'M29-S3'
        head = $Head
        startedAt = $StartedAt.ToString('o')
        finishedAt = [DateTime]::UtcNow.ToString('o')
        result = if ($Passed) { 'PASS' } else { 'FAIL_OR_BLOCKED' }
        dockerServer = [string] $DockerServer.Output
        projectsRoot = $ProjectsRoot
        fixtureRelativePath = $FixtureRelativePath
        installRoot = $InstallRoot
        dataRoot = $DataRoot
        containerName = $ContainerName
        composeProject = $ComposeProject
    } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $ReportPath -Encoding utf8
    Write-Host "M29-S3 report: $ReportPath"

    if ($Passed -and -not $KeepArtifacts) {
        try { Invoke-Workflow -Action Uninstall } catch { Write-Warning $_.Exception.Message }
        Remove-Item -LiteralPath $DataRoot -Recurse -Force -ErrorAction SilentlyContinue
    } elseif (-not $Passed) {
        Write-Host "M29-S3 diagnostic artifacts preserved: $InstallRoot ; $DataRoot" -ForegroundColor Yellow
    }
}
