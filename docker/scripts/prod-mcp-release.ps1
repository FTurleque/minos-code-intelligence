[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Install', 'Start', 'Attach', 'Status', 'Validate', 'Stop', 'Uninstall')]
    [string] $Action,

    [string] $Jar = '',
    [string] $Version = '',
    [string] $Commit = 'unknown',
    [string] $InstallRoot = '',
    [string] $DataRoot = '',
    [string] $ProjectsRoot = '',
    [string] $ImageTag = '',

    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9_.-]+$')]
    [string] $ContainerName = 'minos-mcp-prod',

    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9_.-]+$')]
    [string] $ComposeProject = 'minos-mcp-prod'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($env:OS -ne 'Windows_NT') {
    throw 'The packaged MINOS Docker workflow currently targets Windows hosts.'
}
$DockerCommand = Get-Command docker -ErrorAction SilentlyContinue
if (-not $DockerCommand) {
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
if ([string]::IsNullOrWhiteSpace($DataRoot)) {
    $DataRoot = Join-Path $LocalAppData 'MINOS\docker-data'
}
$DataRoot = [System.IO.Path]::GetFullPath($DataRoot)
if ([string]::IsNullOrWhiteSpace($ProjectsRoot)) {
    $ProjectsRoot = Split-Path -Parent $RepoRoot
}
$ProjectsRoot = [System.IO.Path]::GetFullPath($ProjectsRoot)

$RuntimeRoot = Join-Path $InstallRoot 'runtime'
$BackupsRoot = Join-Path $InstallRoot 'backups'
$ComposeFile = Join-Path $RuntimeRoot 'compose.mcp.prod.yaml'
$EnvironmentFile = Join-Path $RuntimeRoot '.env'
$MetadataFile = Join-Path $RuntimeRoot 'installation.json'

function ConvertTo-DockerPath([string] $Path) {
    return ([System.IO.Path]::GetFullPath($Path)).Replace('\', '/')
}

function Compose([string[]] $Arguments) {
    & docker compose --project-directory $RuntimeRoot --env-file $EnvironmentFile -f $ComposeFile @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose failed: $($Arguments -join ' ')"
    }
}

function Invoke-DockerAllowFailure([string[]] $Arguments) {
    $PreviousErrorActionPreference = $ErrorActionPreference
    try {
        # Windows PowerShell 5.1 can surface native stderr as ErrorRecord objects.
        # Cleanup commands must inspect the native exit code instead of failing on
        # harmless diagnostic output.
        $ErrorActionPreference = 'Continue'
        $Output = ((& $DockerCommand.Source @Arguments 2>&1) | Out-String).Trim()
        $ExitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $PreviousErrorActionPreference
    }
    return [pscustomobject]@{
        ExitCode = $ExitCode
        Output = $Output
    }
}

function Assert-DockerJavaRuntime([string] $Image, [string] $Failure) {
    # `java -version` writes to stderr on success. Capture both native streams
    # directly so Windows PowerShell 5.1 never promotes the banner to a red
    # NativeCommandError record.
    $ProcessInfo = New-Object System.Diagnostics.ProcessStartInfo
    $ProcessInfo.FileName = $DockerCommand.Source
    $ProcessInfo.Arguments = 'run --rm --network none --entrypoint java "{0}" -version' -f $Image
    $ProcessInfo.UseShellExecute = $false
    $ProcessInfo.CreateNoWindow = $true
    $ProcessInfo.RedirectStandardOutput = $true
    $ProcessInfo.RedirectStandardError = $true

    $Process = New-Object System.Diagnostics.Process
    $Process.StartInfo = $ProcessInfo
    try {
        if (-not $Process.Start()) {
            throw "$Failure (process did not start)"
        }
        $StandardOutput = $Process.StandardOutput.ReadToEnd().Trim()
        $StandardError = $Process.StandardError.ReadToEnd().Trim()
        $Process.WaitForExit()
        $ExitCode = $Process.ExitCode
    }
    finally {
        $Process.Dispose()
    }
    $Output = @($StandardError, $StandardOutput) |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
        ForEach-Object { $_.Trim() }
    $Output = $Output -join [Environment]::NewLine
    if ($ExitCode -ne 0) {
        throw "$Failure (exit=$ExitCode): $Output"
    }
    if (-not [string]::IsNullOrWhiteSpace($Output)) {
        Write-Host $Output
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
            $ShortCommit = $Commit.Substring(0, [Math]::Min(12, $Commit.Length))
            $ImageTag = "$SafeVersion-$ShortCommit"
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
        Assert-DockerJavaRuntime -Image $Image -Failure 'The MINOS Docker image does not expose a valid Java runtime.'
        [ordered]@{
            formatVersion = 2
            installedAt = $Timestamp
            image = $Image
            version = $Version
            gitCommit = $Commit
            dataRoot = $DataRoot
            projectsRoot = $ProjectsRoot
            containerProjectsRoot = '/workspace/projects'
            containerName = $ContainerName
            composeProject = $ComposeProject
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
        Assert-DockerJavaRuntime -Image $Metadata.image -Failure 'MINOS Docker validation failed.'
        Write-Host 'MINOS Docker configuration validated.' -ForegroundColor Green
    }
    'Stop' {
        Require-Installed
        Compose @('stop', '--timeout', '10', 'minos-mcp')
        Write-Host 'MINOS Docker MCP stopped.' -ForegroundColor Green
    }
    'Uninstall' {
        Require-Installed
        $Metadata = Get-Content -Raw -LiteralPath $MetadataFile | ConvertFrom-Json
        $ManagedImage = [string] $Metadata.image

        # `down` removes the setup-managed container rather than merely leaving
        # it stopped in Docker Desktop. Persistent MINOS data is a bind mount
        # outside this runtime directory and is intentionally preserved.
        Compose @('down', '--timeout', '10', '--remove-orphans')

        if (-not [string]::IsNullOrWhiteSpace($ManagedImage)) {
            $ImageRemoval = Invoke-DockerAllowFailure -Arguments @('image', 'rm', $ManagedImage)
            if ($ImageRemoval.ExitCode -ne 0) {
                Write-Warning "MINOS Docker container was removed, but image '$ManagedImage' could not be removed: $($ImageRemoval.Output)"
            }
        }

        Remove-Item -LiteralPath $InstallRoot -Recurse -Force -ErrorAction SilentlyContinue
        Write-Host 'MINOS Docker MCP container and runtime configuration removed.' -ForegroundColor Green
        Write-Host "Persistent data preserved: $DataRoot"
    }
}
