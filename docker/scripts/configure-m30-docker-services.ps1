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

# Declared before anything that can fail: PowerShell hoists the rollback `trap` below to the
# whole script scope, so it also runs for errors raised before the snapshot is taken. Under
# Set-StrictMode that trap would then fault on an undefined variable and mask the real error.
$RuntimeStateSnapshot = $null

if ($env:OS -ne 'Windows_NT') {
    throw 'The packaged M30 Docker service configurator currently targets Windows hosts.'
}

function Require-Identifier([string] $Value, [string] $Name) {
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch '^[A-Za-z_][A-Za-z0-9_]{0,62}$') {
        throw "$Name must be a safe identifier (letter/underscore first, then letters/digits/underscore)."
    }
}

function Read-BoundedUtf8([string] $Path, [long] $MaximumBytes, [string] $Label) {
    if ($MaximumBytes -lt 1) { throw 'MaximumBytes must be positive.' }
    $Stream = [System.IO.File]::Open(
        $Path,
        [System.IO.FileMode]::Open,
        [System.IO.FileAccess]::Read,
        [System.IO.FileShare]::Read)
    $Output = [System.IO.MemoryStream]::new()
    try {
        $Buffer = New-Object byte[] 4096
        while (($ReadCount = $Stream.Read($Buffer, 0, $Buffer.Length)) -gt 0) {
            if ($Output.Length + $ReadCount -gt $MaximumBytes) {
                throw "$Label exceeds byte limit: $MaximumBytes"
            }
            $Output.Write($Buffer, 0, $ReadCount)
        }
        $Utf8 = [System.Text.UTF8Encoding]::new($false, $true)
        return $Utf8.GetString($Output.ToArray())
    }
    finally {
        $Output.Dispose()
        $Stream.Dispose()
    }
}

function Read-KeyValueFile([string] $Path) {
    $Values = [ordered]@{}
    if (Test-Path -LiteralPath $Path -PathType Leaf) {
        $Content = Read-BoundedUtf8 -Path $Path -MaximumBytes 262144 -Label 'MINOS Docker environment file'
        foreach ($Line in ($Content -split "\r?\n")) {
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
        $Existing = (Read-BoundedUtf8 -Path $Path -MaximumBytes 65536 -Label 'Managed PostgreSQL secret').Trim()
        if ([string]::IsNullOrWhiteSpace($Existing)) { throw "Managed PostgreSQL secret is empty: $Path" }
        return
    }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path) | Out-Null
    $Bytes = New-Object byte[] 36
    # RandomNumberGenerator::Fill is .NET 5+, so it exists under pwsh but NOT under the
    # Windows PowerShell 5.1 (.NET Framework) that the installer actually uses -- it failed
    # there with "does not contain a method named 'Fill'". Create()/GetBytes is the same CSPRNG
    # and is present on both runtimes.
    $Rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $Rng.GetBytes($Bytes) } finally { $Rng.Dispose() }
    $Password = [Convert]::ToBase64String($Bytes).TrimEnd('=')
    [System.IO.File]::WriteAllText($Path, $Password + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))
    $Sid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    & icacls.exe $Path /inheritance:r /grant:r "*${Sid}:F" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Unable to restrict PostgreSQL secret ACL: $Path" }
}

function Test-DockerVolumeExists([string] $Name) {
    # Same Windows PowerShell 5.1 stderr-is-terminating hazard as Invoke-DockerIgnoringFailure:
    # `docker volume inspect` on an absent volume writes to stderr, which would abort the script
    # on a first-ever install (it only survived until now because the volumes already existed).
    $Previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & docker volume inspect $Name 2>&1 | Out-Null
        return ($LASTEXITCODE -eq 0)
    }
    finally { $ErrorActionPreference = $Previous }
}

function Ensure-ManagedVolume([string] $Name, [string] $Plane) {
    if (Test-DockerVolumeExists $Name) { return }
    & docker volume create `
        --label "io.minos.installation=$ComposeProject" `
        --label "io.minos.runtime-plane=$Plane" `
        $Name | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Unable to create managed Docker volume: $Name" }
}

function Invoke-Compose([string[]] $Arguments, [string[]] $Profiles = @()) {
    $Base = @('compose', '--project-directory', $RuntimeRoot, '--env-file', $EnvironmentFile, '-f', $ComposeFile)
    foreach ($Profile in $Profiles) { $Base += @('--profile', $Profile) }
    # Stdin MUST be a non-terminal pipe here, for the same reason as prod-mcp-release.ps1's
    # Compose helper: some invocation contexts (notably an installer's inherited console)
    # leave stdin attached to a handle that satisfies isatty() with no human able to answer.
    # Compose only prompts ("... Recreate (data will be lost)?") when it believes stdin is a
    # real terminal; forcing a pipe guarantees non-interactive, fail-fast behavior instead of
    # an indefinite hang.
    $null | & docker @Base @Arguments
    if ($LASTEXITCODE -ne 0) { throw "docker compose failed: $($Arguments -join ' ')" }
}

# Ollama's steady-state plane is minos-runtime, which is `internal: true` (no egress by
# design). Pulling the embedding model needs outbound access exactly once, at provisioning
# time. The compose-declared minos-admin-egress network cannot serve that here: it is
# attached only to the ephemeral minos-admin service, so Compose has not materialized it at
# this point (the base compose.mcp.prod.yaml profile declares no networks at all, and the
# connected profile's up commands only bring up minos-runtime members). Own a dedicated
# provisioning network instead -- created immediately before the pull and removed right
# after, so no persistent egress path is left attached to the runtime topology.
# Windows PowerShell 5.1 turns anything a native command writes to stderr into an ErrorRecord,
# which $ErrorActionPreference='Stop' then escalates to a terminating error -- so a best-effort
# `docker network rm` of an absent network aborts the whole script there. (pwsh 7 dropped that
# behaviour, which is why this only shows up under the installer's shell.) Mirrors
# prod-mcp-release.ps1's Invoke-DockerAllowFailure.
function Invoke-DockerIgnoringFailure([string[]] $Arguments) {
    $Previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & docker @Arguments 2>&1 | Out-Null
    }
    finally { $ErrorActionPreference = $Previous }
}

# POSTGRES_PASSWORD_FILE is only consulted by initdb, i.e. the very first time the data
# directory is created. The data volume is declared `external: true`, so it deliberately
# survives `docker compose down --volumes` and an uninstall that purges MINOS's own data root.
# Once the secret file is regenerated without the volume being destroyed -- an uninstall that
# keeps the volume, a rolled-back run, or the user clearing %LOCALAPPDATA%\MINOS -- the stored
# role password and the secret permanently disagree, and every later run dies with an opaque
# "unable to initialize MINOS PostgreSQL backend". Make the secret file authoritative instead:
# the container's own local socket is trust-authenticated, so the role password can be
# reconciled without knowing the old one and without touching the stored data.
function Sync-ManagedPostgresPassword([string] $SecretFile) {
    $Password = (Read-BoundedUtf8 -Path $SecretFile -MaximumBytes 65536 -Label 'Managed PostgreSQL secret').Trim()
    if ([string]::IsNullOrWhiteSpace($Password)) { throw "Managed PostgreSQL secret is empty: $SecretFile" }

    $Base = @('compose', '--project-directory', $RuntimeRoot, '--env-file', $EnvironmentFile,
        '-f', $ComposeFile, '--profile', 'postgresql', 'ps', '-q', 'minos-postgres')
    $Container = ((& docker @Base) | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($Container)) {
        throw 'Unable to resolve the managed PostgreSQL container.'
    }

    # Piped through stdin rather than passed as an argument so the secret never appears in the
    # container's command line. Single quotes are doubled per SQL string-literal escaping.
    $Escaped = $Password.Replace("'", "''")
    $Sql = "ALTER ROLE `"$PostgresUser`" WITH LOGIN PASSWORD '$Escaped';"
    $Previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $Output = ($Sql | & docker exec -i $Container psql -v ON_ERROR_STOP=1 -q `
            -U $PostgresUser -d $PostgresDatabase -f - 2>&1 | Out-String)
    }
    finally { $ErrorActionPreference = $Previous }
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to reconcile the managed PostgreSQL role password.`n$($Output.Trim())"
    }
}

function New-ProvisioningEgressNetwork {
    $Name = "$ComposeProject-ollama-provisioning-egress"
    Invoke-DockerIgnoringFailure @('network', 'rm', $Name)
    & docker network create `
        --label "io.minos.installation=$ComposeProject" `
        --label 'io.minos.network-policy=ollama-provisioning-egress' `
        $Name | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Unable to create MINOS Ollama provisioning egress network: $Name" }
    return $Name
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

# This script commits three pieces of durable state (the connected compose file, the runtime
# .env, and MINOS's own minos.properties) BEFORE the managed services are proven healthy. A
# failure after those writes used to leave them behind: minos.properties would still declare
# storage=postgresql pointing at a PostgreSQL that the base profile does not run, so the very
# next install attempt failed early with "unable to initialize MINOS PostgreSQL backend" --
# an unrecoverable-looking state produced by a merely-failed optional step. Snapshot the three
# files first and restore them on any failure, matching the transactional discipline
# switch-mcp-backend.ps1 already applies to the Docker runtime generation.
$PropertiesPath = Join-Path $DataRoot 'config\minos.properties'

function New-RuntimeStateSnapshot([string[]] $Paths) {
    return @($Paths | ForEach-Object {
        [pscustomobject]@{
            Path = $_
            Existed = Test-Path -LiteralPath $_ -PathType Leaf
            Bytes = if (Test-Path -LiteralPath $_ -PathType Leaf) { [System.IO.File]::ReadAllBytes($_) } else { $null }
        }
    })
}

function Restore-RuntimeStateSnapshot([object[]] $Snapshot) {
    foreach ($Entry in $Snapshot) {
        try {
            if ($Entry.Existed) {
                New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Entry.Path) | Out-Null
                [System.IO.File]::WriteAllBytes($Entry.Path, $Entry.Bytes)
            }
            else {
                Remove-Item -LiteralPath $Entry.Path -Force -ErrorAction SilentlyContinue
            }
        }
        catch {
            Write-Warning "MINOS M30 rollback could not restore '$($Entry.Path)': $($_.Exception.Message)"
        }
    }
}

$RuntimeStateSnapshot = New-RuntimeStateSnapshot @($ComposeFile, $EnvironmentFile, $PropertiesPath)
trap {
    if ($null -ne $RuntimeStateSnapshot) {
        Restore-RuntimeStateSnapshot $RuntimeStateSnapshot
        Write-Warning 'MINOS M30 managed-service configuration failed; previous runtime state restored.'
    }
    break
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
    $Configuration['minos.postgres.managed'] = 'true'
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

Write-MinosProperties -Path $PropertiesPath -Values $Configuration
Write-EnvironmentFile -Path $EnvironmentFile -Values $Environment
Invoke-Compose -Arguments @('config', '--quiet')

$Profiles = @()
if ($StorageBackend -eq 'postgresql') {
    $Profiles += 'postgresql'
    Invoke-Compose -Arguments @('up', '-d', '--wait', 'minos-postgres') -Profiles @('postgresql')
    Sync-ManagedPostgresPassword -SecretFile $SecretFile
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
        $EgressNetwork = New-ProvisioningEgressNetwork
        $ConnectedForProvisioning = $false
        try {
            & docker network connect $EgressNetwork $OllamaContainer
            if ($LASTEXITCODE -ne 0) { throw 'Unable to attach temporary Ollama provisioning egress.' }
            $ConnectedForProvisioning = $true
            # `ollama pull` streams its download progress on stderr even when it succeeds, and
            # Windows PowerShell 5.1 escalates native stderr to a terminating error under
            # $ErrorActionPreference='Stop'. Fold stderr into stdout for the duration and judge
            # the result solely by the exit code, which is the actual success signal.
            $PullPreference = $ErrorActionPreference
            try {
                $ErrorActionPreference = 'Continue'
                # Captured rather than discarded: this is the only place the actual reason a
                # pull failed (network, disk, unknown model) is reported, and swallowing it
                # leaves nothing but "provisioning failed" to debug from.
                $PullOutput = (& docker exec $OllamaContainer ollama pull $SemanticModel 2>&1 | Out-String)
            }
            finally { $ErrorActionPreference = $PullPreference }
            if ($LASTEXITCODE -ne 0) {
                throw "Ollama model provisioning failed: $SemanticModel`n$($PullOutput.Trim())"
            }
            $ModelLines = @(& docker exec $OllamaContainer ollama list)
            if ($LASTEXITCODE -ne 0) { throw "Unable to list Ollama models after provisioning: $SemanticModel" }
            $ModelPresent = $ModelLines | Select-Object -Skip 1 | Where-Object { $_ -match "^$([regex]::Escape($SemanticModel))" }
            if (-not $ModelPresent) { throw "Ollama model '$SemanticModel' not found after pull - provisioning incomplete." }
        }
        finally {
            # Both are best-effort cleanup and must never mask the real failure, so they go
            # through the stderr-tolerant helper (see Invoke-DockerIgnoringFailure).
            if ($ConnectedForProvisioning) {
                Invoke-DockerIgnoringFailure @('network', 'disconnect', $EgressNetwork, $OllamaContainer)
            }
            # Remove the provisioning network unconditionally: it is created per-run, so a
            # leftover on a failed pull would be both a stray artifact and a lingering
            # egress path for the next attempt to reuse.
            Invoke-DockerIgnoringFailure @('network', 'rm', $EgressNetwork)
        }
    }
}

# Warm up the ephemeral admin plane against the freshly written connected configuration,
# before the persistent query plane is (re)created. Two things depend on it:
#   * PostgresCodeKnowledgeSnapshotStore creates MINOS_HOME/postgresql-snapshot-scratch on
#     construction. The query plane mounts MINOS data read-only and overlays a tmpfs there,
#     and runc cannot create that mountpoint inside a read-only bind -- so the directory has
#     to exist beforehand. The admin plane runs as uid 10001 with the data mount writable, so
#     letting MINOS create it here is what also gets the 0700/owner invariant right (neither
#     the Windows host side nor root-without-DAC_OVERRIDE can).
#   * It surfaces a bad PostgreSQL/Ollama configuration here, with MINOS's own diagnostics,
#     instead of as an opaque MCP handshake timeout later.
# The base workflow's own admin runs all happen before this script rewrites the storage
# settings, so none of them can serve this purpose.
Invoke-Compose -Arguments @('run', '--rm', '--no-deps', 'minos-admin', 'tools', 'list', '--format', 'json') -Profiles $Profiles

if ($Start) {
    Invoke-Compose -Arguments @('up', '-d', '--force-recreate', 'minos-mcp') -Profiles $Profiles
}

Write-Host 'MINOS M30 managed Docker services configured.' -ForegroundColor Green
Write-Host "Storage : $StorageBackend"
Write-Host "Semantic: $SemanticProvider"
if ($SemanticProvider -eq 'ollama') { Write-Host "Model   : $SemanticModel ($SemanticDimensions dimensions)" }
