[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Install', 'Start', 'Attach', 'Status', 'Stop', 'Validate')]
    [string]$Action,
    [string]$InstallRoot = '',
    [string]$ProjectsRoot = '',
    [string]$ImageTag = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0

$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $scriptDirectory '..\..'))
. (Join-Path $projectRoot 'scripts\windows\MinosWindows.ps1')

if ([string]::IsNullOrWhiteSpace($InstallRoot)) {
    $localAppData = [Environment]::GetFolderPath('LocalApplicationData')
    if ([string]::IsNullOrWhiteSpace($localAppData)) {
        throw 'LOCALAPPDATA est introuvable. Utilisez -InstallRoot.'
    }
    $InstallRoot = Join-Path $localAppData 'MINOS'
}
$InstallRoot = [System.IO.Path]::GetFullPath($InstallRoot)
if ([string]::IsNullOrWhiteSpace($ProjectsRoot)) {
    $ProjectsRoot = Split-Path -Parent $projectRoot
}
$ProjectsRoot = [System.IO.Path]::GetFullPath($ProjectsRoot)

$runtimeDirectory = Join-Path $InstallRoot 'runtime'
$dataDirectory = Join-Path $InstallRoot 'data'
$backupDirectory = Join-Path $InstallRoot 'backups'
$composeFile = Join-Path $runtimeDirectory 'compose.mcp.prod.yaml'
$environmentFile = Join-Path $runtimeDirectory '.env'
$metadataFile = Join-Path $runtimeDirectory 'installation.json'
$composeProject = 'minos-mcp-prod'
$containerName = 'minos-mcp-prod'

function Assert-CommandAvailable {
    param([Parameter(Mandatory = $true)][string]$Name)

    if ($null -eq (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Commande requise introuvable : $Name"
    }
}

function Assert-MinosDocker {
    $serverVersion = & docker version --format '{{.Server.Version}}'
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($serverVersion)) {
        throw 'Docker Desktop ne repond pas.'
    }
}

function Initialize-MinosProdLayout {
    foreach ($directory in @($InstallRoot, $runtimeDirectory, $dataDirectory, $backupDirectory)) {
        [System.IO.Directory]::CreateDirectory($directory) | Out-Null
    }
}

function Test-DirectoryHasContent {
    param([Parameter(Mandatory = $true)][string]$Path)

    return (Test-Path -LiteralPath $Path -PathType Container) -and
        (@(Get-ChildItem -LiteralPath $Path -Force | Select-Object -First 1).Count -gt 0)
}

function New-MinosProdBackup {
    if (-not (Test-DirectoryHasContent -Path $dataDirectory) -and
        -not (Test-DirectoryHasContent -Path $runtimeDirectory)) {
        return $null
    }

    $destination = Join-Path $backupDirectory ([DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss'))
    [System.IO.Directory]::CreateDirectory($destination) | Out-Null
    if (Test-DirectoryHasContent -Path $dataDirectory) {
        Copy-Item -LiteralPath $dataDirectory -Destination (Join-Path $destination 'data') -Recurse
    }
    if (Test-DirectoryHasContent -Path $runtimeDirectory) {
        Copy-Item -LiteralPath $runtimeDirectory -Destination (Join-Path $destination 'runtime') -Recurse
    }
    return $destination
}

function ConvertTo-MinosEnvValue {
    param([Parameter(Mandatory = $true)][string]$Value)

    return '"' + $Value.Replace('$', '$$').Replace('"', '\"') + '"'
}

function Get-MinosComposeArguments {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    return @(
        'compose', '--project-directory', $runtimeDirectory,
        '--env-file', $environmentFile,
        '-f', $composeFile
    ) + $Arguments
}

function Invoke-MinosCompose {
    param(
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$FailureMessage
    )

    Invoke-MinosNative -FilePath 'docker' -ArgumentList (Get-MinosComposeArguments -Arguments $Arguments) `
        -FailureMessage $FailureMessage
}

function Assert-MinosProdInstalled {
    foreach ($requiredFile in @($composeFile, $environmentFile, $metadataFile)) {
        if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
            throw "MINOS PROD n'est pas installe sous $InstallRoot. Lancez [MINOS Prod] Install."
        }
    }
}

function Get-MinosProdContainerId {
    $ids = @(& docker ps -a --filter "name=^/$containerName$" --format '{{.ID}}')
    if ($LASTEXITCODE -ne 0) {
        throw "Impossible d'inspecter le conteneur MINOS PROD."
    }
    return $ids | Select-Object -First 1
}

function Assert-MinosProdContainerOwned {
    param([Parameter(Mandatory = $true)][string]$ContainerId)

    $inspectionJson = & docker inspect $ContainerId
    if ($LASTEXITCODE -ne 0) {
        throw "Impossible d'inspecter le conteneur MINOS PROD."
    }
    $inspection = $inspectionJson | Out-String | ConvertFrom-Json
    $owner = $inspection[0].Config.Labels.'io.minos.installation'
    if (([string]$owner).Trim() -ne $composeProject) {
        throw "Le conteneur $containerName existe mais n'appartient pas a l'installation $composeProject."
    }
}

function Assert-NoRunningMinosProdContainer {
    $containerId = Get-MinosProdContainerId
    if (-not [string]::IsNullOrWhiteSpace($containerId)) {
        Assert-MinosProdContainerOwned -ContainerId $containerId
        $running = & docker inspect --format '{{.State.Running}}' $containerId
        if ($LASTEXITCODE -ne 0) {
            throw "Impossible de lire l'etat du conteneur MINOS PROD."
        }
        if (([string]$running).Trim() -eq 'true') {
            throw 'MINOS PROD est actif. Lancez [MINOS Prod] Stop avant une reinstallation.'
        }
        Invoke-MinosNative -FilePath 'docker' -ArgumentList @('rm', $containerId) `
            -FailureMessage "Impossible de retirer l'ancien conteneur MINOS PROD arrete"
    }
}

function Invoke-MinosDockerSmoke {
    $java = Resolve-MinosJava24
    $smokeSource = Join-Path $projectRoot 'docker\scripts\MinosDockerMcpSmoke.java'
    Invoke-MinosNative -FilePath $java.JavaExecutable -ArgumentList @($smokeSource, $composeFile, $environmentFile) `
        -FailureMessage 'Le handshake MCP STDIO dans Docker a echoue'
}

Assert-CommandAvailable -Name 'docker'

switch ($Action) {
    'Install' {
        Assert-CommandAvailable -Name 'git'
        if (-not (Test-Path -LiteralPath $ProjectsRoot -PathType Container)) {
            throw "La racine des projets est introuvable : $ProjectsRoot"
        }
        Assert-MinosDocker
        Initialize-MinosProdLayout
        Assert-NoRunningMinosProdContainer
        $backup = New-MinosProdBackup

        $java = Resolve-MinosJava24
        $env:JAVA_HOME = $java.JavaHome
        $env:Path = "$($java.JavaHome)\bin;$env:Path"
        Push-Location $projectRoot
        try {
            Invoke-MinosNative -FilePath '.\mvnw.cmd' -ArgumentList @('-q', '-DskipTests', 'clean', 'package') `
                -FailureMessage 'Le packaging MINOS sous Java 24 a echoue'
        } finally {
            Pop-Location
        }

        [xml]$pom = Get-Content -LiteralPath (Join-Path $projectRoot 'pom.xml')
        $version = [string]$pom.project.version
        $commit = ((& git -C $projectRoot rev-parse --short=12 HEAD) | Select-Object -First 1).Trim()
        if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($commit)) {
            throw 'Le SHA Git MINOS est introuvable.'
        }
        $buildTimestamp = [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
        if ([string]::IsNullOrWhiteSpace($ImageTag)) {
            $safeVersion = $version.ToLowerInvariant().Replace('+', '-').Replace('SNAPSHOT', 'snapshot')
            $ImageTag = "$safeVersion-$commit-$([DateTime]::UtcNow.ToString('yyyyMMddHHmmss'))"
        }
        $image = "minos-code-intelligence:$ImageTag"
        $dockerfile = Join-Path $projectRoot 'docker\Dockerfile.mcp'
        $buildContext = Join-Path $projectRoot 'target'
        Invoke-MinosNative -FilePath 'docker' -ArgumentList @(
            'build', '--file', $dockerfile,
            '--tag', $image,
            '--build-arg', "MINOS_VERSION=$version",
            '--build-arg', "MINOS_GIT_COMMIT=$commit",
            '--build-arg', "MINOS_BUILD_TIMESTAMP=$buildTimestamp",
            $buildContext
        ) -FailureMessage "La construction de l'image Docker MINOS a echoue"

        Copy-Item -LiteralPath (Join-Path $projectRoot 'docker\compose.mcp.prod.yaml') `
            -Destination $composeFile -Force
        $environmentContent = @"
MINOS_COMPOSE_PROJECT=$composeProject
MINOS_CONTAINER_NAME=$containerName
MINOS_IMAGE=$image
MINOS_DATA_DIR=$(ConvertTo-MinosEnvValue -Value (ConvertTo-MinosDockerPath -Path $dataDirectory))
MINOS_PROJECTS_DIR=$(ConvertTo-MinosEnvValue -Value (ConvertTo-MinosDockerPath -Path $ProjectsRoot))
MINOS_VERSION=$version
MINOS_GIT_COMMIT=$commit
"@
        Write-MinosUtf8File -Path $environmentFile -Content $environmentContent.TrimStart()
        Invoke-MinosCompose -Arguments @('config', '--quiet') `
            -FailureMessage 'La configuration Docker Compose MINOS est invalide'
        Invoke-MinosNative -FilePath 'docker' -ArgumentList @(
            'run', '--rm', '--network', 'none', '--entrypoint', 'java', $image, '-version'
        ) -FailureMessage "Le runtime Java 24 de l'image MINOS est invalide"
        Invoke-MinosDockerSmoke

        $previousInstalledAt = $null
        if (Test-Path -LiteralPath $metadataFile -PathType Leaf) {
            $previous = Get-Content -LiteralPath $metadataFile | Out-String | ConvertFrom-Json
            $previousInstalledAt = $previous.installedAt
        }
        $metadata = [ordered]@{
            formatVersion = 1
            installedAt = if ([string]::IsNullOrWhiteSpace($previousInstalledAt)) { $buildTimestamp } else { $previousInstalledAt }
            updatedAt = [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
            installRoot = $InstallRoot
            dataRoot = $dataDirectory
            projectsRoot = $ProjectsRoot
            containerProjectsRoot = '/workspace/projects'
            composeProject = $composeProject
            image = $image
            version = $version
            gitCommit = $commit
            java = $java.VersionLine
            lastBackup = $backup
        }
        Write-MinosUtf8File -Path $metadataFile -Content ($metadata | ConvertTo-Json)

        Write-Host ''
        Write-Host 'MINOS MCP PROD installation SUCCESS' -ForegroundColor Green
        Write-Host "Image    : $image"
        Write-Host "Java     : $($java.VersionLine)"
        Write-Host "Data     : $dataDirectory -> /var/lib/minos"
        Write-Host "Projects : $ProjectsRoot -> /workspace/projects (lecture seule)"
        Write-Host "Network  : none"
        if ($null -ne $backup) {
            Write-Host "Backup   : $backup"
        }
        Write-Host 'Next     : lancez [MINOS Prod] Start, puis [MINOS Prod] MCP.'
    }
    'Validate' {
        Assert-MinosProdInstalled
        Invoke-MinosCompose -Arguments @('config', '--quiet') `
            -FailureMessage 'La configuration Docker Compose MINOS est invalide'
        Invoke-MinosDockerSmoke
    }
    'Start' {
        Assert-MinosProdInstalled
        Assert-MinosDocker
        $existing = Get-MinosProdContainerId
        if (-not [string]::IsNullOrWhiteSpace($existing)) {
            Assert-MinosProdContainerOwned -ContainerId $existing
            $running = & docker inspect --format '{{.State.Running}}' $existing
            if ($LASTEXITCODE -ne 0) {
                throw "Impossible de lire l'etat du conteneur MINOS PROD."
            }
            if (([string]$running).Trim() -eq 'true') {
                Write-Host 'MINOS MCP PROD est deja demarre.'
                return
            }
        }
        Invoke-MinosCompose -Arguments @('up', '-d', '--force-recreate', 'minos-mcp') `
            -FailureMessage 'Le demarrage du conteneur MINOS MCP PROD a echoue'
        $containerId = Get-MinosProdContainerId
        if ([string]::IsNullOrWhiteSpace($containerId)) {
            throw 'Le conteneur MINOS MCP PROD est absent apres le demarrage.'
        }
        Assert-MinosProdContainerOwned -ContainerId $containerId
        Write-Host 'MINOS MCP PROD demarre.' -ForegroundColor Green
        Write-Host 'Utilisez [MINOS Prod] MCP pour attacher un client STDIO.'
    }
    'Attach' {
        Assert-MinosProdInstalled
        $containerId = Get-MinosProdContainerId
        if ([string]::IsNullOrWhiteSpace($containerId)) {
            throw 'MINOS MCP PROD est arrete. Lancez [MINOS Prod] Start.'
        }
        Assert-MinosProdContainerOwned -ContainerId $containerId
        $running = & docker inspect --format '{{.State.Running}}' $containerId
        $exitCode = $LASTEXITCODE
        if ($exitCode -ne 0 -or ([string]$running).Trim() -ne 'true') {
            throw 'MINOS MCP PROD est arrete. Lancez [MINOS Prod] Start.'
        }
        & docker exec -i $containerName java -cp /opt/minos/minos.jar com.minos.mcp.MinosMcpServer
        $exitCode = $LASTEXITCODE
        if ($exitCode -ne 0 -and $exitCode -ne 130) {
            throw "La session MCP STDIO a echoue (code $exitCode)."
        }
    }
    'Status' {
        Assert-MinosProdInstalled
        $metadata = Get-Content -LiteralPath $metadataFile | Out-String | ConvertFrom-Json
        $imageJson = & docker image inspect $metadata.image
        if ($LASTEXITCODE -ne 0) {
            throw "L'image MINOS PROD installee est absente."
        }
        $imageDetails = $imageJson | Out-String | ConvertFrom-Json
        $containerId = Get-MinosProdContainerId
        Write-Host ''
        Write-Host "Image    : $($metadata.image)"
        Write-Host "Image ID : $($imageDetails[0].Id)"
        Write-Host "Commit   : $($metadata.gitCommit)"
        Write-Host "Data     : $($metadata.dataRoot)"
        Write-Host "Projects : $($metadata.projectsRoot) (lecture seule)"
        if ([string]::IsNullOrWhiteSpace($containerId)) {
            Write-Host 'Session  : inactive'
        } else {
            Assert-MinosProdContainerOwned -ContainerId $containerId
            Invoke-MinosNative -FilePath 'docker' -ArgumentList @('ps', '-a', '--filter', "id=$containerId") `
                -FailureMessage 'Impossible de lire la session MINOS PROD'
        }
    }
    'Stop' {
        Assert-MinosProdInstalled
        $containerId = Get-MinosProdContainerId
        if ([string]::IsNullOrWhiteSpace($containerId)) {
            Write-Host 'MINOS MCP PROD est deja arrete.'
            return
        }
        Assert-MinosProdContainerOwned -ContainerId $containerId
        Invoke-MinosCompose -Arguments @('stop', '--timeout', '10', 'minos-mcp') `
            -FailureMessage "Impossible d'arreter MINOS MCP PROD"
        Write-Host 'MINOS MCP PROD arrete.' -ForegroundColor Green
    }
}
