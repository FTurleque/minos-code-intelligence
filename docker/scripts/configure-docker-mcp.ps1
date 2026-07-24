[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $InstallRoot,

    [string] $ProjectsRoot = '',

    [switch] $Start,
    [switch] $Stop,
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
if (-not [string]::IsNullOrWhiteSpace($ProjectsRoot)) {
    $ProjectsRoot = [System.IO.Path]::GetFullPath($ProjectsRoot)
}
$ManagedMarker = Join-Path $InstallRoot '.docker-mcp-managed'

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

    $DockerScript = Join-Path $InstallRoot 'docker\scripts\prod-mcp-release.ps1'
    if (-not (Test-Path -LiteralPath $DockerScript -PathType Leaf)) {
        Fail-Or-Warn "MINOS Docker MCP helper is missing: $DockerScript"
        return
    }

    $Docker = Get-Command docker -ErrorAction SilentlyContinue
    if (-not $Docker) {
        Fail-Or-Warn 'Docker Desktop is not installed or docker.exe is not in PATH. MINOS native installation is unaffected.'
        return
    }

    & $Docker.Source version --format '{{.Server.Version}}' | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Fail-Or-Warn 'Docker Desktop is installed but its daemon does not respond. MINOS native installation is unaffected.'
        return
    }

    if ($Stop) {
        if (-not (Test-Path -LiteralPath $ManagedMarker -PathType Leaf)) {
            Write-Host 'MINOS Docker MCP was not managed by this setup; nothing to stop.'
            return
        }
        & $DockerScript -Action Stop
        Write-Host 'MINOS Docker MCP stopped before uninstall.' -ForegroundColor Green
        return
    }

    if ([string]::IsNullOrWhiteSpace($ProjectsRoot)) {
        Fail-Or-Warn '-ProjectsRoot is required when configuring Docker MCP.'
        return
    }
    if (-not (Test-Path -LiteralPath $ProjectsRoot -PathType Container)) {
        Fail-Or-Warn "MINOS Docker MCP projects root does not exist: $ProjectsRoot"
        return
    }

    $VersionFile = Join-Path $InstallRoot 'VERSION'
    $Jar = Join-Path $InstallRoot 'lib\minos.jar'
    foreach ($Required in @($VersionFile, $Jar)) {
        if (-not (Test-Path -LiteralPath $Required -PathType Leaf)) {
            Fail-Or-Warn "MINOS Docker MCP cannot be configured: missing $Required"
            return
        }
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

    @"
version=$Version
commit=$Commit
projectsRoot=$ProjectsRoot
configuredAt=$([DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ'))
"@ | Set-Content -LiteralPath $ManagedMarker -Encoding ascii

    if ($Start) {
        & $DockerScript -Action Start
        & $DockerScript -Action Validate
    }

    Write-Host 'MINOS Docker MCP setup SUCCESS' -ForegroundColor Green
    Write-Host "Projects : $ProjectsRoot"
    Write-Host "Log      : $LogPath"
}
catch {
    if ($Strict) {
        throw
    }
    Write-Warning "MINOS native installation is valid, but Docker MCP management failed: $($_.Exception.Message)"
    Write-Warning "See $LogPath"
}
finally {
    if ($TranscriptStarted) {
        try { Stop-Transcript | Out-Null } catch { }
    }
}
