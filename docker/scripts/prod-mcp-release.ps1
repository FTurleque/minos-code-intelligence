[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Install', 'Start', 'Attach', 'Status', 'Validate', 'Stop')]
    [string] $Action,

    [string] $Jar = '',
    [string] $Version = '',
    [string] $Commit = 'unknown',
    [string] $InstallRoot = '',
    [string] $ProjectsRoot = '',
    [string] $ImageTag = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($env:OS -ne 'Windows_NT') {
    throw 'The packaged MINOS Docker workflow currently targets Windows hosts.'
}
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw 'Docker is required.'
}
& docker version --format '{{.Server.Version}}' | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw 'Docker Desktop does not respond.'
}

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$LocalAppData = [Environment]::GetFolderPath('LocalApplicationData')
if ([string]::IsNullOrWhiteSpace($InstallRoot)) {
    $InstallRoot = Join-Path $LocalAppData 'MINOS\docker'
}
$InstallRoot = [System.IO.Path]::GetFullPath($InstallRoot)
if ([string]::IsNullOrWhiteSpace($ProjectsRoot)) {
    $ProjectsRoot = Split-Path -Parent $RepoRoot
}
$ProjectsRoot = [System.IO.Path]::GetFullPath($ProjectsRoot)

$RuntimeRoot = Join-Path $InstallRoot 'runtime'
$DataRoot = Join-Path $LocalAppData 'MINOS\docker-data'
$BackupsRoot = Join-Path $InstallRoot 'backups'
$ComposeFile = Join-Path $RuntimeRoot 'compose.mcp.prod.yaml'
$EnvironmentFile = Join-Path $RuntimeRoot '.env'
$MetadataFile = Join-Path $RuntimeRoot 'installation.json'
$ContainerName = 'minos-mcp-prod'
$ComposeProject = 'minos-mcp-prod'

function ConvertTo-DockerPath([string] $Path) {
    $Full = [System.IO.Path]::GetFullPath($Path)
    if ($Full -match '^([A-Za-z]):\\(.*)$') {
        return '/' + $Matches[1].ToLowerInvariant() + '/' + ($Matches[2] -replace '\\', '/')
    }
    return ($Full -replace '\\', '/')
}

function Compose([string[]] $Arguments) {
    & docker compose --project-directory $RuntimeRoot --env-file $EnvironmentFile -f $ComposeFile @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose failed: $($Arguments -join ' ')"
    }
}

function Require-Installed {
    foreach ($File in @($ComposeFile, $EnvironmentFile, $MetadataFile)) {
        if (-not (Test-Path -LiteralPath $File -PathType Leaf)) {
            throw "MINOS Docker PROD is not installed: missing $File"
        }
    }
}

switch ($Action) {
    'Install' {
        if ([string]::IsNullOrWhiteSpace($Jar)) {
            throw '-Jar is required for Install. Use the shaded JAR from the same MINOS release.'
        }
        $Jar = (Resolve-Path -LiteralPath $Jar).Path
        if ([string]::IsNullOrWhiteSpace($Version)) {
            throw '-Version is required for Install.'
        }
        if (-not (Test-Path -LiteralPath $ProjectsRoot -PathType Container)) {
            throw "Projects root does not exist: $ProjectsRoot"
        }

        New-Item -ItemType Directory -Force -Path $RuntimeRoot, $DataRoot, $BackupsRoot | Out-Null
        if (Test-Path -LiteralPath $MetadataFile) {
            $Backup = Join-Path $BackupsRoot ([DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss'))
            New-Item -ItemType Directory -Force -Path $Backup | Out-Null
            if (Test-Path -LiteralPath $RuntimeRoot) {
                Copy-Item -LiteralPath $RuntimeRoot -Destination (Join-Path $Backup 'runtime') -Recurse
            }
        }

        $BuildContext = Join-Path $RuntimeRoot 'build'
        Remove-Item -LiteralPath $BuildContext -Recurse -Force -ErrorAction SilentlyContinue
        New-Item -ItemType Directory -Force -Path $BuildContext | Out-Null
        Copy-Item -LiteralPath $Jar -Destination (Join-Path $BuildContext 'minos.jar')
        $Dockerfile = Join-Path $RepoRoot 'docker\Dockerfile.mcp.release'
        $Timestamp = [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
        if ([string]::IsNullOrWhiteSpace($ImageTag)) {
            $SafeVersion = $Version.ToLowerInvariant().Replace('+', '-').Replace('SNAPSHOT', 'snapshot')
            $ImageTag = "$SafeVersion-$($Commit.Substring(0, [Math]::Min(12, $Commit.Length)))"
        }
        $Image = "minos-code-intelligence:$ImageTag"
        & docker build --file $Dockerfile --tag $Image `
            --build-arg "MINOS_VERSION=$Version" `
            --build-arg "MINOS_GIT_COMMIT=$Commit" `
            --build-arg "MINOS_BUILD_TIMESTAMP=$Timestamp" $BuildContext
        if ($LASTEXITCODE -ne 0) {
            throw 'MINOS Docker image build failed.'
        }
        Remove-Item -LiteralPath $BuildContext -Recurse -Force -ErrorAction SilentlyContinue

        Copy-Item -LiteralPath (Join-Path $RepoRoot 'docker\compose.mcp.prod.yaml') -Destination $ComposeFile -Force
        @"
MINOS_COMPOSE_PROJECT=$ComposeProject
MINOS_CONTAINER_NAME=$ContainerName
MINOS_IMAGE=$Image
MINOS_DATA_DIR="$(ConvertTo-DockerPath $DataRoot)"
MINOS_PROJECTS_DIR="$(ConvertTo-DockerPath $ProjectsRoot)"
MINOS_VERSION=$Version
MINOS_GIT_COMMIT=$Commit
"@ | Set-Content -LiteralPath $EnvironmentFile -Encoding ascii

        Compose @('config', '--quiet')
        & docker run --rm --network none --entrypoint java $Image -version
        if ($LASTEXITCODE -ne 0) {
            throw 'The MINOS Docker image does not expose a valid Java runtime.'
        }
        [ordered]@{
            formatVersion = 2
            installedAt = $Timestamp
            image = $Image
            version = $Version
            gitCommit = $Commit
            dataRoot = $DataRoot
            projectsRoot = $ProjectsRoot
            containerProjectsRoot = '/workspace/projects'
        } | ConvertTo-Json | Set-Content -LiteralPath $MetadataFile -Encoding utf8

        Write-Host 'MINOS packaged Docker installation SUCCESS' -ForegroundColor Green
        Write-Host "Image    : $Image"
        Write-Host "Data     : $DataRoot -> /var/lib/minos"
        Write-Host "Projects : $ProjectsRoot -> /workspace/projects (read-only)"
        Write-Host 'Network  : none'
    }
    'Start' {
        Require-Installed
        Compose @('up', '-d', '--force-recreate', 'minos-mcp')
        Write-Host 'MINOS Docker MCP started.' -ForegroundColor Green
    }
    'Attach' {
        Require-Installed
        & docker exec -i $ContainerName java -cp /opt/minos/minos.jar com.minos.mcp.MinosMcpServer
        if ($LASTEXITCODE -ne 0 -and $LASTEXITCODE -ne 130) {
            throw "MCP STDIO session failed with exit code $LASTEXITCODE"
        }
    }
    'Status' {
        Require-Installed
        Get-Content -LiteralPath $MetadataFile
        & docker ps -a --filter "name=^/$ContainerName$"
    }
    'Validate' {
        Require-Installed
        Compose @('config', '--quiet')
        $Metadata = Get-Content -Raw -LiteralPath $MetadataFile | ConvertFrom-Json
        & docker run --rm --network none --entrypoint java $Metadata.image -version
        if ($LASTEXITCODE -ne 0) {
            throw 'MINOS Docker validation failed.'
        }
        Write-Host 'MINOS Docker configuration validated.' -ForegroundColor Green
    }
    'Stop' {
        Require-Installed
        Compose @('stop', '--timeout', '10', 'minos-mcp')
        Write-Host 'MINOS Docker MCP stopped.' -ForegroundColor Green
    }
}
