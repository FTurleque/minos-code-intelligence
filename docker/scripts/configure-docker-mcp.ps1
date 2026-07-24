[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $InstallRoot,

    [Parameter(Mandatory = $true)]
    [string] $ProjectsRoot,

    [switch] $Start,
    [switch] $Strict,

    [string] $LogPath = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Fail-Or-Warn([string] $Message) {
    if ($Strict) {
        throw $Message
    }
    Write-Warning $Message
}

$InstallRoot = [System.IO.Path]::GetFullPath($InstallRoot)
$ProjectsRoot = [System.IO.Path]::GetFullPath($ProjectsRoot)

if ([string]::IsNullOrWhiteSpace($LogPath)) {
    $LocalAppData = [Environment]::GetFolderPath('LocalApplicationData')
    $LogPath = Join-Path $LocalAppData 'MINOS\docker-setup.log'
}
$LogPath = [System.IO.Path]::GetFullPath($LogPath)
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $LogPath) | Out-Null

$TranscriptStarted = $false
try {
    try {
        Start-Transcript -LiteralPath $LogPath -Append | Out-Null
        $TranscriptStarted = $true
    }
    catch {
        # A transcript is useful but must never be a prerequisite for setup.
    }

    $VersionFile = Join-Path $InstallRoot 'VERSION'
    $Jar = Join-Path $InstallRoot 'lib\minos.jar'
    $DockerScript = Join-Path $InstallRoot 'docker\scripts\prod-mcp-release.ps1'

    foreach ($Required in @($VersionFile, $Jar, $DockerScript)) {
        if (-not (Test-Path -LiteralPath $Required -PathType Leaf)) {
            Fail-Or-Warn "MINOS Docker MCP cannot be configured: missing $Required"
            return
        }
    }
    if (-not (Test-Path -LiteralPath $ProjectsRoot -PathType Container)) {
        Fail-Or-Warn "MINOS Docker MCP projects root does not exist: $ProjectsRoot"
        return
    }

    $Docker = Get-Command docker -ErrorAction SilentlyContinue
    if (-not $Docker) {
        Fail-Or-Warn 'Docker Desktop is not installed or docker.exe is not in PATH. MINOS itself is installed; configure Docker MCP later.'
        return
    }

    & $Docker.Source version --format '{{.Server.Version}}' | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Fail-Or-Warn 'Docker Desktop is installed but its daemon does not respond. MINOS itself is installed; start Docker Desktop and configure Docker MCP later.'
        return
    }

    $Metadata = @{}
    foreach ($Line in Get-Content -LiteralPath $VersionFile) {
        if ($Line -match '^([^=]+)=(.*)$') {
            $Metadata[$Matches[1].Trim()] = $Matches[2].Trim()
        }
    }
    $Version = $Metadata['version']
    $Commit = $Metadata['commit']
    if ([string]::IsNullOrWhiteSpace($Version)) {
        Fail-Or-Warn "MINOS VERSION file does not contain a version: $VersionFile"
        return
    }
    if ([string]::IsNullOrWhiteSpace($Commit)) {
        $Commit = 'unknown'
    }

    & $DockerScript `
        -Action Install `
        -Jar $Jar `
        -Version $Version `
        -Commit $Commit `
        -ProjectsRoot $ProjectsRoot
    if ($LASTEXITCODE -ne 0) {
        Fail-Or-Warn "MINOS Docker MCP installation failed with exit code $LASTEXITCODE. See $LogPath"
        return
    }

    if ($Start) {
        & $DockerScript -Action Start
        if ($LASTEXITCODE -ne 0) {
            Fail-Or-Warn "MINOS Docker MCP start failed with exit code $LASTEXITCODE. See $LogPath"
            return
        }
        & $DockerScript -Action Validate
        if ($LASTEXITCODE -ne 0) {
            Fail-Or-Warn "MINOS Docker MCP validation failed with exit code $LASTEXITCODE. See $LogPath"
            return
        }
    }

    Write-Host 'MINOS Docker MCP setup SUCCESS' -ForegroundColor Green
    Write-Host "Projects : $ProjectsRoot"
    Write-Host "Log      : $LogPath"
}
catch {
    if ($Strict) {
        throw
    }
    Write-Warning "MINOS is installed, but Docker MCP configuration failed: $($_.Exception.Message)"
    Write-Warning "See $LogPath"
}
finally {
    if ($TranscriptStarted) {
        try { Stop-Transcript | Out-Null } catch { }
    }
}
