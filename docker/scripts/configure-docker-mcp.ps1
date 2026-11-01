[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $InstallRoot,

    # Ignored with a warning: the Docker runtime mounts no projects root (see mcp-lifecycle.ps1 -Action AddProject).
    [string] $ProjectsRoot = '',
    [switch] $Start,
    [switch] $Stop,
    [switch] $Strict,

    [string] $LogPath = '',
    [string] $DockerInstallRoot = '',
    [string] $DockerDataRoot = '',
    [string] $DockerImageTag = '',

    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9_.-]+$')]
    [string] $DockerContainerName = 'minos-mcp-prod',

    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9_.-]+$')]
    [string] $DockerComposeProject = 'minos-mcp-prod',

    [ValidateSet('local', 'postgresql')]
    [string] $StorageBackend = 'local',

    [ValidateSet('disabled', 'local-hash', 'ollama')]
    [string] $SemanticProvider = 'disabled',

    [string] $SemanticModel = 'nomic-embed-text',

    [ValidateRange(32, 16384)]
    [int] $SemanticDimensions = 768,

    [switch] $ProvisionOllamaModel,

    # Ignored with a warning: there is no projects root to replace.
    [switch] $ReplaceProjectsRoot,

    # Installer progress file; empty (the default) writes no progress.
    [string] $ProgressPath = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# Installer progress protocol, read by the Windows installer while this script runs (-ProgressPath).
# A line carries only a step id, a state and a number: no path, URL, model name or secret can reach it.
function Write-SetupProgress([string] $Step, [string] $State, [int] $Percent = -1) {
    if ([string]::IsNullOrWhiteSpace($ProgressPath)) { return }
    if ($Step -cnotmatch '^[a-z][a-z0-9-]{0,47}\z') { return }
    if (@('running', 'done', 'skipped') -cnotcontains $State) { return }
    if ($Percent -lt -1 -or $Percent -gt 100) { return }
    $Line = "MINOS-PROGRESS step=$Step state=$State"
    if ($Percent -ge 0) { $Line += " percent=$Percent" }
    for ($Attempt = 1; $Attempt -le 5; $Attempt++) {
        try {
            [System.IO.File]::AppendAllText($ProgressPath, $Line + "`r`n", [System.Text.Encoding]::ASCII)
            return
        }
        catch { Start-Sleep -Milliseconds 50 }
    }
}

function Fail-Or-Warn([string] $Message) {
    if ($Strict) { throw $Message }
    Write-Warning $Message
}

function Read-KeyValueFile([string] $Path) {
    $Values = @{}
    if (Test-Path -LiteralPath $Path -PathType Leaf) {
        foreach ($Line in Get-Content -LiteralPath $Path) {
            if ($Line -match '^([^=]+)=(.*)$') { $Values[$Matches[1].Trim()] = $Matches[2].Trim() }
        }
    }
    return $Values
}

$InstallRoot = [System.IO.Path]::GetFullPath($InstallRoot)
$LocalAppData = [Environment]::GetFolderPath('LocalApplicationData')
if ([string]::IsNullOrWhiteSpace($DockerInstallRoot)) { $DockerInstallRoot = Join-Path $LocalAppData 'MINOS\docker' }
if ([string]::IsNullOrWhiteSpace($DockerDataRoot)) { $DockerDataRoot = Join-Path $LocalAppData 'MINOS\docker-data' }
$DockerInstallRoot = [System.IO.Path]::GetFullPath($DockerInstallRoot)
$DockerDataRoot = [System.IO.Path]::GetFullPath($DockerDataRoot)
$ManagedMarker = Join-Path $InstallRoot '.docker-mcp-managed'

if ([string]::IsNullOrWhiteSpace($LogPath)) { $LogPath = Join-Path $LocalAppData 'MINOS\docker-setup.log' }
$LogPath = [System.IO.Path]::GetFullPath($LogPath)
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $LogPath) | Out-Null

$TranscriptStarted = $false
try {
    try { Start-Transcript -LiteralPath $LogPath -Append | Out-Null; $TranscriptStarted = $true } catch { }

    $DockerScript = Join-Path $InstallRoot 'docker\scripts\prod-mcp-release.ps1'
    $M30Services = Join-Path $InstallRoot 'docker\scripts\configure-m30-docker-services.ps1'
    if (-not (Test-Path -LiteralPath $DockerScript -PathType Leaf)) {
        Fail-Or-Warn "MINOS Docker MCP helper is missing: $DockerScript"; return
    }

    function Invoke-DockerWorkflow([string] $Action, [hashtable] $AdditionalParameters = @{}) {
        $Parameters = @{
            Action = $Action
            ContainerName = $DockerContainerName
            ComposeProject = $DockerComposeProject
            InstallRoot = $DockerInstallRoot
            DataRoot = $DockerDataRoot
        }
        if (-not [string]::IsNullOrWhiteSpace($DockerImageTag)) { $Parameters['ImageTag'] = $DockerImageTag }
        foreach ($Key in $AdditionalParameters.Keys) { $Parameters[$Key] = $AdditionalParameters[$Key] }
        & $DockerScript @Parameters
    }

    $Docker = Get-Command docker -ErrorAction SilentlyContinue
    if (-not $Docker) {
        Fail-Or-Warn 'Docker Desktop is not installed or docker.exe is not in PATH. MINOS native installation is unaffected.'; return
    }
    & $Docker.Source version --format '{{.Server.Version}}' | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Fail-Or-Warn 'Docker Desktop is installed but its daemon does not respond. MINOS native installation is unaffected.'; return
    }

    if ($Stop) {
        if (-not (Test-Path -LiteralPath $ManagedMarker -PathType Leaf)) {
            Write-Host 'MINOS Docker MCP was not managed by this setup; nothing to remove.'; return
        }
        $Managed = Read-KeyValueFile -Path $ManagedMarker
        if (-not [string]::IsNullOrWhiteSpace($Managed['dockerInstallRoot'])) { $DockerInstallRoot = $Managed['dockerInstallRoot'] }
        if (-not [string]::IsNullOrWhiteSpace($Managed['dockerDataRoot'])) { $DockerDataRoot = $Managed['dockerDataRoot'] }
        if (-not [string]::IsNullOrWhiteSpace($Managed['containerName'])) { $DockerContainerName = $Managed['containerName'] }
        if (-not [string]::IsNullOrWhiteSpace($Managed['composeProject'])) { $DockerComposeProject = $Managed['composeProject'] }
        Invoke-DockerWorkflow -Action Uninstall
        Remove-Item -LiteralPath $ManagedMarker -Force
        Write-Host 'MINOS Docker MCP removed before uninstall.' -ForegroundColor Green
        return
    }

    if (-not [string]::IsNullOrWhiteSpace($ProjectsRoot) -or $ReplaceProjectsRoot) {
        Write-Warning 'The projects root is ignored: the MINOS Docker runtime mounts no projects root, only the projects exposed with prod-mcp-release.ps1 -Action AddProject -ProjectPath <folder>.'
    }

    $VersionFile = Join-Path $InstallRoot 'VERSION'
    $Jar = Join-Path $InstallRoot 'lib\minos.jar'
    foreach ($Required in @($VersionFile, $Jar)) {
        if (-not (Test-Path -LiteralPath $Required -PathType Leaf)) {
            Fail-Or-Warn "MINOS Docker MCP cannot be configured: missing $Required"; return
        }
    }

    $Metadata = Read-KeyValueFile -Path $VersionFile
    $Version = $Metadata['version']
    $Commit = $Metadata['commit']
    if ([string]::IsNullOrWhiteSpace($Version)) { Fail-Or-Warn "MINOS VERSION file does not contain a version: $VersionFile"; return }
    if ([string]::IsNullOrWhiteSpace($Commit)) { $Commit = 'unknown' }

    # M29's base workflow installs and qualifies the image/provider payload first.
    # M30 then upgrades the runtime compose to the connected internal-service profile
    # only when PostgreSQL and/or Ollama is selected.
    $BaseSemanticProvider = if ($SemanticProvider -eq 'local-hash') { 'local-hash' } else { 'disabled' }

    # The base profile has no PostgreSQL/Ollama service, yet its Install runs real minos-admin
    # commands against the persisted MINOS configuration in the Docker data root. A leftover
    # `minos.storage.backend=postgresql` there -- from a previous successful run, or from a run
    # whose managed services were later torn down -- makes those commands fail with "unable to
    # initialize MINOS PostgreSQL backend" before M30 ever gets a chance to bring PostgreSQL up.
    # Neutralise the persisted storage/semantic selection to what the base profile can actually
    # serve; M30 rewrites the full connected configuration immediately afterwards when needed.
    # This mirrors the existing $BaseSemanticProvider handling, which already does exactly this
    # for the semantic side when passing SemanticProvider to the base workflow.
    $BaseProperties = Join-Path $DockerDataRoot 'config\minos.properties'
    # A runtime that still mounts one projects root migrates to one mount per registered project during the base
    # Install, which reads the registry. With PostgreSQL storage, the registry is only reachable once the connected
    # services run, after that Install: the migration would see no project. It is refused before anything changes.
    $MappingFile = Join-Path $DockerDataRoot 'runtime\project-paths.properties'
    $PreviousPostgresql = (Test-Path -LiteralPath $BaseProperties -PathType Leaf) -and
        (@(Get-Content -LiteralPath $BaseProperties) -match '^\s*minos\.storage\.backend\s*[=:]\s*postgresql\s*$').Count -gt 0
    $SingleRootMapping = (Test-Path -LiteralPath $MappingFile -PathType Leaf) -and
        (@(Get-Content -LiteralPath $MappingFile) -match '^\s*formatVersion\s*[=:]\s*1\s*$').Count -gt 0
    if ($PreviousPostgresql -and $SingleRootMapping) {
        Fail-Or-Warn ('This MINOS Docker runtime stores its projects in PostgreSQL and still mounts one projects root: ' +
            'its migration to one mount per registered project is not supported by this installer. Nothing was changed.')
        return
    }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $BaseProperties) | Out-Null
    @(
        '# MINOS runtime configuration managed by the Windows installer',
        'minos.storage.backend=local',
        "minos.semantic.provider=$BaseSemanticProvider"
    ) | Set-Content -LiteralPath $BaseProperties -Encoding ascii

    Write-SetupProgress -Step 'docker-image' -State 'running'
    $InstallParameters = @{
        Jar = $Jar
        Version = $Version
        Commit = $Commit
        SemanticProvider = $BaseSemanticProvider
    }
    Invoke-DockerWorkflow -Action Install -AdditionalParameters $InstallParameters
    Write-SetupProgress -Step 'docker-image' -State 'done'

    $NeedsM30Services = $StorageBackend -eq 'postgresql' -or $SemanticProvider -eq 'ollama'
    if ($NeedsM30Services) {
        if (-not (Test-Path -LiteralPath $M30Services -PathType Leaf)) {
            throw "MINOS M30 Docker service configurator is missing: $M30Services"
        }
        $ServiceParameters = @{
            InstallRoot = $DockerInstallRoot
            DataRoot = $DockerDataRoot
            StorageBackend = $StorageBackend
            SemanticProvider = $SemanticProvider
            SemanticModel = $SemanticModel
            SemanticDimensions = $SemanticDimensions
            Start = $Start
        }
        if ($ProvisionOllamaModel) { $ServiceParameters['ProvisionOllamaModel'] = $true }
        if (-not [string]::IsNullOrWhiteSpace($ProgressPath)) { $ServiceParameters['ProgressPath'] = $ProgressPath }
        & $M30Services @ServiceParameters
    }

    @"
version=$Version
commit=$Commit
dockerInstallRoot=$DockerInstallRoot
dockerDataRoot=$DockerDataRoot
containerName=$DockerContainerName
composeProject=$DockerComposeProject
storageBackend=$StorageBackend
semanticProvider=$SemanticProvider
semanticModel=$SemanticModel
semanticDimensions=$SemanticDimensions
configuredAt=$([DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ'))
"@ | Set-Content -LiteralPath $ManagedMarker -Encoding ascii

    if ($Start -and -not $NeedsM30Services) {
        # Validate re-runs the exact same data/tools/admin sequence Install just ran
        # against the same unchanged image and volume (see prod-mcp-release.ps1's
        # 'Install' and 'Validate' actions) -- calling it here would just repeat ~8
        # ephemeral container create/run/remove cycles for zero new information.
        # The persistent query container Start just (re)created is proven for real
        # by switch-mcp-backend.ps1's MCP protocol handshake that always follows.
        Invoke-DockerWorkflow -Action Start
    }

    Write-Host 'MINOS Docker MCP setup SUCCESS' -ForegroundColor Green
    Write-Host 'Projects : none mounted until exposed with prod-mcp-release.ps1 -Action AddProject -ProjectPath <folder>'
    Write-Host "Storage  : $StorageBackend"
    Write-Host "Semantic : $SemanticProvider"
    Write-Host "Log      : $LogPath"
}
catch {
    if ($Strict) { throw }
    Write-Warning "MINOS native installation is valid, but Docker MCP management failed: $($_.Exception.Message)"
    Write-Warning "See $LogPath"
}
finally {
    if ($TranscriptStarted) { try { Stop-Transcript | Out-Null } catch { } }
}
