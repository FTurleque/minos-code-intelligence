[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $InstallRoot,

    [Parameter(Mandatory = $true)]
    [string] $DataRoot,

    [ValidateSet('local', 'postgresql')]
    [string] $StorageBackend = 'local',

    [ValidateSet('disabled', 'local-hash', 'ollama')]
    [string] $SemanticProvider = 'disabled',

    [string] $SemanticModel = 'nomic-embed-text',

    [ValidateRange(32, 16384)]
    [int] $SemanticDimensions = 768,

    [string] $PostgresUser = 'minos',
    [string] $PostgresDatabase = 'minos',
    [string] $PostgresSchema = 'minos',

    [string] $PostgresImage = 'pgvector/pgvector:0.8.2-pg17',
    [string] $OllamaImage = 'ollama/ollama:0.32.0',

    [switch] $ProvisionOllamaModel,
    [switch] $Start
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($env:OS -ne 'Windows_NT') {
    throw 'The packaged M30 Docker service configurator currently targets Windows hosts.'
}

function Require-Identifier([string] $Value, [string] $Name) {
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch '^[A-Za-z_][A-Za-z0-9_]{0,62}$') {
        throw "$Name must be a safe identifier (letter/underscore first, then letters/digits/underscore)."
    }
}

function Read-KeyValueFile([string] $Path) {
    $Values = [ordered]@{}
    if (Test-Path -LiteralPath $Path -PathType Leaf) {
        foreach ($Line in [System.IO.File]::ReadAllLines($Path, [System.Text.Encoding]::UTF8)) {
            if ($Line -match '^([^#][^=]*)=(.*)$') {
                $Values[$Matches[1].Trim()] = $Matches[2].Trim().Trim('"')
            }
        }
    }
    return $Values
}

function Write-EnvironmentFile([string] $Path, [System.Collections.IDictionary] $Values) {
    $Lines = foreach ($Key in $Values.Keys) {
        $Value = [string]$Values[$Key]
        if ($Value.Contains(' ') -or $Value.Contains(':\') -or $Value.Contains('/')) {
            $Escaped = $Value.Replace('"', '\"')
            "$Key=`"$Escaped`""
        } else {
            "$Key=$Value"
        }
    }
    [System.IO.File]::WriteAllLines($Path, $Lines, [System.Text.UTF8Encoding]::new($false))
}

function Write-MinosProperties([string] $Path, [System.Collections.IDictionary] $Values) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path) | Out-Null
    $Lines = @('# MINOS runtime configuration managed by the Windows installer')
    foreach ($Key in $Values.Keys) {
        $Value = ([string]$Values[$Key]).Replace('\', '\\')
        $Lines += "$Key=$Value"
    }
    [System.IO.File]::WriteAllLines($Path, $Lines, [System.Text.UTF8Encoding]::new($false))
}

function New-ManagedPassword([string] $Path) {
    if (Test-Path -LiteralPath $Path -PathType Leaf) {
        $Existing = [System.IO.File]::ReadAllText($Path, [System.Text.Encoding]::UTF8).Trim()
        if ([string]::IsNullOrWhiteSpace($Existing)) { throw "Managed PostgreSQL secret is empty: $Path" }
        return
    }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path) | Out-Null
    $Bytes = New-Object byte[] 36
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($Bytes)
    $Password = [Convert]::ToBase64String($Bytes).TrimEnd('=')
    [System.IO.File]::WriteAllText($Path, $Password + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))
    $Sid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    & icacls.exe $Path /inheritance:r /grant:r "*${Sid}:F" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Unable to restrict PostgreSQL secret ACL: $Path" }
}

function Ensure-ManagedVolume([string] $Name, [string] $Plane) {
    & docker volume inspect $Name *> $null
    if ($LASTEXITCODE -eq 0) { return }
    & docker volume create `
        --label "io.minos.installation=$ComposeProject" `
        --label "io.minos.runtime-plane=$Plane" `
        $Name | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Unable to create managed Docker volume: $Name" }
}

function Invoke-Compose([string[]] $Arguments, [string[]] $Profiles = @()) {
    $Base = @('compose', '--project-directory', $RuntimeRoot, '--env-file', $EnvironmentFile, '-f', $ComposeFile)
    foreach ($Profile in $Profiles) { $Base += @('--profile', $Profile) }
    & docker @Base @Arguments
    if ($LASTEXITCODE -ne 0) { throw "docker compose failed: $($Arguments -join ' ')" }
}

function Find-Network([string] $Policy) {
    $Result = @(& docker network ls `
        --filter "label=io.minos.installation=$ComposeProject" `
        --filter "label=io.minos.network-policy=$Policy" `
        --format '{{.ID}}')
    if ($LASTEXITCODE -ne 0 -or $Result.Count -ne 1) {
        throw "Unable to resolve unique MINOS Docker network policy '$Policy'."
    }
    return [string]$Result[0]
}

$InstallRoot = [System.IO.Path]::GetFullPath($InstallRoot)
$DataRoot = [System.IO.Path]::GetFullPath($DataRoot)
Require-Identifier $PostgresUser 'PostgreSQL user'
Require-Identifier $PostgresDatabase 'PostgreSQL database'
Require-Identifier $PostgresSchema 'PostgreSQL schema'

$Docker = Get-Command docker -ErrorAction SilentlyContinue
if (-not $Docker) { throw 'Docker Desktop is required for managed PostgreSQL/Ollama services.' }
& $Docker.Source version --format '{{.Server.Version}}' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Docker Desktop daemon does not respond.' }

$RuntimeRoot = Join-Path $InstallRoot 'runtime'
$EnvironmentFile = Join-Path $RuntimeRoot '.env'
$ComposeFile = Join-Path $RuntimeRoot 'compose.mcp.prod.yaml'
$ConnectedTemplate = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\compose.mcp.connected.yaml'))
foreach ($Required in @($EnvironmentFile, $ConnectedTemplate)) {
    if (-not (Test-Path -LiteralPath $Required -PathType Leaf)) { throw "MINOS Docker runtime is incomplete: $Required" }
}

$Environment = Read-KeyValueFile $EnvironmentFile
$ComposeProject = [string]$Environment['MINOS_COMPOSE_PROJECT']
if ([string]::IsNullOrWhiteSpace($ComposeProject)) { throw 'MINOS Docker runtime .env has no MINOS_COMPOSE_PROJECT.' }
$ContainerName = [string]$Environment['MINOS_CONTAINER_NAME']
if ([string]::IsNullOrWhiteSpace($ContainerName)) { throw 'MINOS Docker runtime .env has no MINOS_CONTAINER_NAME.' }

$NeedsConnectedRuntime = $StorageBackend -eq 'postgresql' -or $SemanticProvider -eq 'ollama'
if (-not $NeedsConnectedRuntime) {
    Write-Host 'M30 managed Docker services are not required for the selected local configuration.' -ForegroundColor Green
    return
}

Copy-Item -LiteralPath $ConnectedTemplate -Destination $ComposeFile -Force
New-Item -ItemType Directory -Force -Path $DataRoot | Out-Null

$PostgresVolume = "$ComposeProject-postgres-data"
$OllamaVolume = "$ComposeProject-ollama-models"
$Environment['MINOS_POSTGRES_VOLUME'] = $PostgresVolume
$Environment['MINOS_OLLAMA_VOLUME'] = $OllamaVolume

$Configuration = [ordered]@{
    'minos.storage.backend' = $StorageBackend
    'minos.semantic.provider' = $SemanticProvider
}

if ($StorageBackend -eq 'postgresql') {
    Ensure-ManagedVolume -Name $PostgresVolume -Plane 'storage'
    $SecretFile = Join-Path $DataRoot 'secrets\postgres.password'
    New-ManagedPassword $SecretFile
    $Configuration['minos.postgres.url'] = "jdbc:postgresql://minos-postgres:5432/$PostgresDatabase"
    $Configuration['minos.postgres.user'] = $PostgresUser
    $Configuration['minos.postgres.passwordFile'] = 'secrets/postgres.password'
    $Configuration['minos.postgres.schema'] = $PostgresSchema
    $Environment['MINOS_POSTGRES_USER'] = $PostgresUser
    $Environment['MINOS_POSTGRES_DATABASE'] = $PostgresDatabase
    $Environment['MINOS_POSTGRES_PASSWORD_FILE'] = $SecretFile.Replace('\', '/')
    $Environment['MINOS_POSTGRES_IMAGE'] = $PostgresImage
}

if ($SemanticProvider -eq 'ollama') {
    Ensure-ManagedVolume -Name $OllamaVolume -Plane 'semantic'
    if ([string]::IsNullOrWhiteSpace($SemanticModel)) { throw 'SemanticModel must not be blank for Ollama.' }
    $Configuration['minos.semantic.model'] = $SemanticModel
    $Configuration['minos.semantic.dimensions'] = [string]$SemanticDimensions
    $Configuration['minos.semantic.endpoint'] = 'http://minos-ollama:11434/api/embed'
    $Environment['MINOS_SEMANTIC_PROVIDER'] = 'ollama'
    $Environment['MINOS_SEMANTIC_MODEL'] = $SemanticModel
    $Environment['MINOS_SEMANTIC_DIMENSIONS'] = [string]$SemanticDimensions
    $Environment['MINOS_SEMANTIC_ENDPOINT'] = 'http://minos-ollama:11434/api/embed'
    $Environment['MINOS_OLLAMA_IMAGE'] = $OllamaImage
} else {
    $Environment['MINOS_SEMANTIC_PROVIDER'] = $SemanticProvider
}

Write-MinosProperties -Path (Join-Path $DataRoot 'config\minos.properties') -Values $Configuration
Write-EnvironmentFile -Path $EnvironmentFile -Values $Environment
Invoke-Compose -Arguments @('config', '--quiet')

$Profiles = @()
if ($StorageBackend -eq 'postgresql') {
    $Profiles += 'postgresql'
    Invoke-Compose -Arguments @('up', '-d', '--wait', 'minos-postgres') -Profiles @('postgresql')
}
if ($SemanticProvider -eq 'ollama') {
    $Profiles += 'ollama'
    Invoke-Compose -Arguments @('up', '-d', '--wait', 'minos-ollama') -Profiles @('ollama')
    if ($ProvisionOllamaModel) {
        $ComposeBase = @('compose', '--project-directory', $RuntimeRoot, '--env-file', $EnvironmentFile,
            '-f', $ComposeFile, '--profile', 'ollama', 'ps', '-q', 'minos-ollama')
        $OllamaContainer = ((& docker @ComposeBase) | Out-String).Trim()
        if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($OllamaContainer)) {
            throw 'Unable to resolve managed Ollama container.'
        }
        $EgressNetwork = Find-Network 'admin-dependency-egress'
        $ConnectedForProvisioning = $false
        try {
            & docker network connect $EgressNetwork $OllamaContainer
            if ($LASTEXITCODE -ne 0) { throw 'Unable to attach temporary Ollama provisioning egress.' }
            $ConnectedForProvisioning = $true
            & docker exec $OllamaContainer ollama pull $SemanticModel
            if ($LASTEXITCODE -ne 0) { throw "Ollama model provisioning failed: $SemanticModel" }
        }
        finally {
            if ($ConnectedForProvisioning) {
                & docker network disconnect $EgressNetwork $OllamaContainer 2>$null | Out-Null
            }
        }
    }
}

if ($Start) {
    Invoke-Compose -Arguments @('up', '-d', '--force-recreate', 'minos-mcp') -Profiles $Profiles
}

Write-Host 'MINOS M30 managed Docker services configured.' -ForegroundColor Green
Write-Host "Storage : $StorageBackend"
Write-Host "Semantic: $SemanticProvider"
if ($SemanticProvider -eq 'ollama') { Write-Host "Model   : $SemanticModel ($SemanticDimensions dimensions)" }
