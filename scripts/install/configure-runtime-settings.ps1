[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $DataRoot,

    [ValidateSet('standard', 'advanced')]
    [string] $SetupMode = 'standard',

    [ValidateSet('native', 'docker', 'none')]
    [string] $McpBackend = 'native',

    [ValidatePattern('^[A-Za-z][A-Za-z0-9_-]{0,63}$')]
    [string] $McpServerName = 'minos',

    [ValidateSet('local', 'postgresql')]
    [string] $StorageBackend = 'local',

    [string] $PostgresUrl = '',
    [string] $PostgresUser = '',
    [string] $PostgresPasswordSourcePath = '',
    [string] $PostgresSchema = 'minos',

    [ValidateSet('disabled', 'local-hash', 'ollama')]
    [string] $SemanticProvider = 'disabled',

    [string] $SemanticModel = 'nomic-embed-text',

    [ValidateRange(32, 16384)]
    [int] $SemanticDimensions = 768,

    [string] $SemanticEndpoint = 'http://127.0.0.1:11434/api/embed',

    [ValidateRange(1, 300)]
    [int] $SemanticTimeoutSeconds = 30,

    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9_.-]+$')]
    [string] $DockerInstanceName = 'minos-mcp-prod',

    [string] $DockerDataRoot = '',

    [switch] $ManagedDockerPostgres,

    [switch] $ProvisionOllamaModel,

    [string] $InstallerStatePath = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($env:OS -ne 'Windows_NT') {
    throw 'MINOS installer runtime settings currently target Windows hosts.'
}

function Require-Identifier([string] $Value, [string] $Name) {
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch '^[A-Za-z_][A-Za-z0-9_]{0,62}$') {
        throw "$Name must be a safe identifier."
    }
}

function Write-Properties([string] $Path, [System.Collections.IDictionary] $Values) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path) | Out-Null
    $Lines = @('# MINOS runtime configuration managed by installer')
    foreach ($Key in $Values.Keys) {
        $Value = ([string]$Values[$Key]).Replace('\', '\\')
        $Lines += "$Key=$Value"
    }
    [System.IO.File]::WriteAllLines($Path, $Lines, [System.Text.UTF8Encoding]::new($false))
}

function Restrict-FileToCurrentUser([string] $Path) {
    $Identity = [System.Security.Principal.WindowsIdentity]::GetCurrent()
    $Sid = $Identity.User.Value
    & icacls.exe $Path /inheritance:r /grant:r "*${Sid}:F" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Unable to restrict file ACL: $Path" }
}

function Write-Json([string] $Path, [object] $Value) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path) | Out-Null
    $Json = $Value | ConvertTo-Json -Depth 8
    [System.IO.File]::WriteAllText($Path, $Json + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))
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

function Test-LoopbackHost([string] $HostValue) {
    if ([string]::IsNullOrWhiteSpace($HostValue)) { return $false }
    $Normalized = $HostValue.ToLowerInvariant()
    if ($Normalized -in @('localhost', '::1', '0:0:0:0:0:0:0:1')) { return $true }
    $Address = $null
    if ([System.Net.IPAddress]::TryParse($Normalized, [ref]$Address)) {
        return [System.Net.IPAddress]::IsLoopback($Address)
    }
    return $false
}

function Assert-ExternalPostgresUrl([string] $JdbcUrl) {
    if ([string]::IsNullOrWhiteSpace($JdbcUrl) -or -not $JdbcUrl.StartsWith('jdbc:postgresql://')) {
        throw 'PostgresUrl must use jdbc:postgresql:// when PostgreSQL storage is selected.'
    }
    try { $Uri = [Uri]$JdbcUrl.Substring(5) }
    catch { throw 'PostgresUrl is not a valid PostgreSQL JDBC URL.' }
    if ($Uri.Scheme -ne 'postgresql' -or [string]::IsNullOrWhiteSpace($Uri.DnsSafeHost)) {
        throw 'PostgresUrl must contain a valid PostgreSQL host.'
    }
    if ([string]::IsNullOrWhiteSpace($Uri.AbsolutePath) -or $Uri.AbsolutePath -eq '/') {
        throw 'PostgresUrl must contain a database name.'
    }
    if (-not [string]::IsNullOrWhiteSpace($Uri.UserInfo)) {
        throw 'PostgresUrl must not contain credentials in user-info.'
    }
    if (-not [string]::IsNullOrWhiteSpace($Uri.Fragment)) {
        throw 'PostgresUrl must not contain a URI fragment.'
    }

    $Parameters = @{}
    if (-not [string]::IsNullOrWhiteSpace($Uri.Query)) {
        foreach ($Pair in $Uri.Query.TrimStart('?').Split('&')) {
            if ([string]::IsNullOrEmpty($Pair)) {
                throw 'PostgresUrl contains an empty query parameter name.'
            }
            $Parts = $Pair.Split('=', 2)
            if ($Parts[0] -match '%(?![0-9A-Fa-f]{2})' -or
                    ($Parts.Length -gt 1 -and $Parts[1] -match '%(?![0-9A-Fa-f]{2})')) {
                throw 'PostgresUrl contains invalid query encoding.'
            }
            $Key = [Uri]::UnescapeDataString($Parts[0].Replace('+', ' ')).ToLowerInvariant()
            $Value = if ($Parts.Length -gt 1) { [Uri]::UnescapeDataString($Parts[1].Replace('+', ' ')) } else { '' }
            if ([string]::IsNullOrWhiteSpace($Key)) { throw 'PostgresUrl contains an empty query parameter name.' }
            if ($Parameters.ContainsKey($Key)) { throw "PostgresUrl contains duplicate parameter: $Key" }
            $Parameters[$Key] = $Value
        }
    }
    foreach ($Parameter in $Parameters.Keys) {
        if ($Parameter -notin @('sslmode')) {
            throw "PostgresUrl contains unsupported parameter: $Parameter"
        }
    }
    if ($Parameters.ContainsKey('sslmode') -and
            $Parameters['sslmode'].ToLowerInvariant() -notin @('disable', 'allow', 'prefer', 'require', 'verify-ca', 'verify-full')) {
        throw 'PostgresUrl contains an unsupported sslmode.'
    }
    if (-not (Test-LoopbackHost $Uri.DnsSafeHost)) {
        if (-not $Parameters.ContainsKey('sslmode') -or $Parameters['sslmode'] -ine 'verify-full') {
            throw 'External PostgreSQL requires sslmode=verify-full.'
        }
    }
}

function Test-LoopbackOllamaEndpoint([string] $Endpoint) {
    try { $Uri = [Uri]$Endpoint } catch { return $false }
    if ($Uri.Scheme -notin @('http', 'https') -or [string]::IsNullOrWhiteSpace($Uri.AbsolutePath)) { return $false }
    return Test-LoopbackHost $Uri.DnsSafeHost
}

$DataRoot = [System.IO.Path]::GetFullPath($DataRoot)
if ([string]::IsNullOrWhiteSpace($InstallerStatePath)) {
    $InstallerStatePath = Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'MINOS\installer-state.json'
}
$InstallerStatePath = [System.IO.Path]::GetFullPath($InstallerStatePath)
New-Item -ItemType Directory -Force -Path $DataRoot | Out-Null

# Seed backend.properties before any AI client can invoke minos.exe mcp and trigger
# McpBackendConfigurationStore.loadOrMigrate(), which would create a native default.
# switch-mcp-backend.ps1 unconditionally overwrites this seed with the committed backend.
$RuntimeDirectory = Join-Path $DataRoot 'runtime'
$SeedBackendFile = Join-Path $RuntimeDirectory 'backend.properties'
if (-not (Test-Path -LiteralPath $SeedBackendFile -PathType Leaf)) {
    New-Item -ItemType Directory -Force -Path $RuntimeDirectory | Out-Null
    [System.IO.File]::WriteAllLines(
        $SeedBackendFile,
        [string[]]@('# MINOS MCP backend configuration v1', 'formatVersion=1',
            'backend=native', "docker.containerName=$DockerInstanceName",
            'docker.probeTimeoutMillis=20000'),
        [System.Text.UTF8Encoding]::new($false))
}

$Configuration = [ordered]@{
    'minos.storage.backend' = $StorageBackend
    'minos.semantic.provider' = $SemanticProvider
}
$SecretPath = $null

if ($StorageBackend -eq 'postgresql') {
    if ($ManagedDockerPostgres.IsPresent) {
        # Managed Docker PostgreSQL: connection settings are written by configure-m30-docker-services.ps1.
        # Record the backend selection only; do not validate external URL/credentials here.
        $Configuration['minos.postgres.managed'] = 'true'
    } else {
        Assert-ExternalPostgresUrl $PostgresUrl
        Require-Identifier $PostgresUser 'PostgreSQL user'
        Require-Identifier $PostgresSchema 'PostgreSQL schema'
        if ([string]::IsNullOrWhiteSpace($PostgresPasswordSourcePath)) {
            throw 'PostgresPasswordSourcePath is required when PostgreSQL storage is selected.'
        }
        $SourceSecret = [System.IO.Path]::GetFullPath($PostgresPasswordSourcePath)
        if (-not (Test-Path -LiteralPath $SourceSecret -PathType Leaf)) {
            throw "PostgreSQL password source file does not exist: $SourceSecret"
        }
        $Secret = (Read-BoundedUtf8 -Path $SourceSecret -MaximumBytes 65536 -Label 'PostgreSQL password source').Trim()
        if ([string]::IsNullOrWhiteSpace($Secret)) { throw 'PostgreSQL password must not be blank.' }
        $SecretPath = Join-Path $DataRoot 'secrets\postgres.password'
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $SecretPath) | Out-Null
        [System.IO.File]::WriteAllText($SecretPath, $Secret + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))
        Restrict-FileToCurrentUser $SecretPath
        $Configuration['minos.postgres.managed'] = 'false'
        $Configuration['minos.postgres.url'] = $PostgresUrl
        $Configuration['minos.postgres.user'] = $PostgresUser
        $Configuration['minos.postgres.passwordFile'] = 'secrets/postgres.password'
        $Configuration['minos.postgres.schema'] = $PostgresSchema
    }
}

if ($SemanticProvider -eq 'ollama') {
    if ([string]::IsNullOrWhiteSpace($SemanticModel)) { throw 'SemanticModel must not be blank for Ollama.' }
    if ($McpBackend -eq 'docker') {
        $SemanticEndpoint = 'http://minos-ollama:11434/api/embed'
    } elseif (-not (Test-LoopbackOllamaEndpoint $SemanticEndpoint)) {
        throw 'Native Ollama endpoint must be loopback-only (localhost/127.0.0.0/8/::1).'
    }
    $Configuration['minos.semantic.model'] = $SemanticModel
    $Configuration['minos.semantic.dimensions'] = [string]$SemanticDimensions
    $Configuration['minos.semantic.endpoint'] = $SemanticEndpoint
    $Configuration['minos.semantic.timeoutSeconds'] = [string]$SemanticTimeoutSeconds
}

$ConfigurationPath = Join-Path $DataRoot 'config\minos.properties'
Write-Properties -Path $ConfigurationPath -Values $Configuration

$State = [ordered]@{
    formatVersion = 1
    updatedAt = [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
    setupMode = $SetupMode
    dataRoot = $DataRoot
    dockerDataRoot = if (-not [string]::IsNullOrWhiteSpace($DockerDataRoot)) { [System.IO.Path]::GetFullPath($DockerDataRoot) } else { $null }
    mcpServerName = $McpServerName
    mcpBackend = $McpBackend
    storageBackend = $StorageBackend
    managedDockerPostgres = $ManagedDockerPostgres.IsPresent
    provisionOllamaModel = $ProvisionOllamaModel.IsPresent
    postgres = [ordered]@{
        url = if ($StorageBackend -eq 'postgresql' -and -not $ManagedDockerPostgres.IsPresent) { $PostgresUrl } else { $null }
        user = if ($StorageBackend -eq 'postgresql' -and -not $ManagedDockerPostgres.IsPresent) { $PostgresUser } else { $null }
        schema = if ($StorageBackend -eq 'postgresql' -and -not $ManagedDockerPostgres.IsPresent) { $PostgresSchema } else { $null }
        passwordFile = if ($StorageBackend -eq 'postgresql' -and -not $ManagedDockerPostgres.IsPresent) { $SecretPath } else { $null }
    }
    semantic = [ordered]@{
        provider = $SemanticProvider
        model = if ($SemanticProvider -eq 'ollama') { $SemanticModel } else { $null }
        dimensions = if ($SemanticProvider -eq 'ollama') { $SemanticDimensions } else { $null }
        endpoint = if ($SemanticProvider -eq 'ollama') { $SemanticEndpoint } else { $null }
        timeoutSeconds = if ($SemanticProvider -eq 'ollama') { $SemanticTimeoutSeconds } else { $null }
    }
    dockerInstanceName = $DockerInstanceName
    configurationFile = $ConfigurationPath
}
Write-Json -Path $InstallerStatePath -Value $State

Write-Host 'MINOS runtime settings configured.' -ForegroundColor Green
Write-Host "Mode     : $SetupMode"
Write-Host "Data     : $DataRoot"
if (-not [string]::IsNullOrWhiteSpace($DockerDataRoot)) { Write-Host "Docker   : $DockerDataRoot" }
Write-Host "MCP      : $McpBackend / $McpServerName"
Write-Host "Storage  : $StorageBackend$(if ($ManagedDockerPostgres.IsPresent) { ' (managed Docker)' })"
Write-Host "Semantic : $SemanticProvider$(if ($ProvisionOllamaModel.IsPresent) { ' (provision model)' })"
