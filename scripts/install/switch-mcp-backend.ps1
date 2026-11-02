[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $InstallRoot,

    [Parameter(Mandatory = $true)]
    [ValidateSet('native', 'docker')]
    [string] $TargetBackend,

    # Ignored with a warning: the Docker runtime mounts no projects root, only the projects exposed with
    # prod-mcp-release.ps1 -Action AddProject. Kept so that scripted installs of earlier releases still run.
    [string] $ProjectsRoot = '',
    [string] $DataRoot = '',
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
    [switch] $ReplaceDockerProjectsRoot,

    [ValidateRange(1, 120)]
    [int] $ProbeTimeoutSeconds = 20,

    # Qualification/test injection points. Production setup does not override them.
    [string] $LauncherPath = '',
    [string] $HandshakeProbePath = '',
    [string] $DockerConfiguratorPath = '',
    [string] $DockerWorkflowPath = '',

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

if ($env:OS -ne 'Windows_NT') {
    throw 'MINOS MCP backend switching currently targets Windows hosts.'
}

$InstallRoot = [System.IO.Path]::GetFullPath($InstallRoot)
$LocalAppData = [Environment]::GetFolderPath('LocalApplicationData')
if ([string]::IsNullOrWhiteSpace($DataRoot)) {
    $DataRoot = Join-Path $LocalAppData 'MINOS\data'
}
$DataRoot = [System.IO.Path]::GetFullPath($DataRoot)
if ([string]::IsNullOrWhiteSpace($DockerInstallRoot)) {
    $DockerInstallRoot = Join-Path $LocalAppData 'MINOS\docker'
}
$DockerInstallRoot = [System.IO.Path]::GetFullPath($DockerInstallRoot)
if ([string]::IsNullOrWhiteSpace($DockerDataRoot)) {
    $DockerDataRoot = Join-Path $LocalAppData 'MINOS\docker-data'
}
$DockerDataRoot = [System.IO.Path]::GetFullPath($DockerDataRoot)

if ([string]::IsNullOrWhiteSpace($LauncherPath)) {
    $LauncherPath = Join-Path $InstallRoot 'app\minos.exe'
}
$LauncherPath = [System.IO.Path]::GetFullPath($LauncherPath)
if ([string]::IsNullOrWhiteSpace($HandshakeProbePath)) {
    $HandshakeProbePath = Join-Path $InstallRoot 'integration\probe-mcp-backend.ps1'
}
$HandshakeProbePath = [System.IO.Path]::GetFullPath($HandshakeProbePath)
if ([string]::IsNullOrWhiteSpace($DockerConfiguratorPath)) {
    $DockerConfiguratorPath = Join-Path $InstallRoot 'docker\scripts\configure-docker-mcp.ps1'
}
$DockerConfiguratorPath = [System.IO.Path]::GetFullPath($DockerConfiguratorPath)
if ([string]::IsNullOrWhiteSpace($DockerWorkflowPath)) {
    $DockerWorkflowPath = Join-Path $InstallRoot 'docker\scripts\prod-mcp-release.ps1'
}
$DockerWorkflowPath = [System.IO.Path]::GetFullPath($DockerWorkflowPath)

foreach ($Required in @($LauncherPath, $HandshakeProbePath)) {
    if (-not (Test-Path -LiteralPath $Required -PathType Leaf)) {
        throw "MINOS backend switching helper is missing: $Required"
    }
}
if ($TargetBackend -eq 'docker') {
    foreach ($Required in @($DockerConfiguratorPath, $DockerWorkflowPath)) {
        if (-not (Test-Path -LiteralPath $Required -PathType Leaf)) {
            throw "MINOS Docker backend switching helper is missing: $Required"
        }
    }
}

$RuntimeRoot = Join-Path $DataRoot 'runtime'
$BackendFile = Join-Path $RuntimeRoot 'backend.properties'
$SwitchStateFile = Join-Path $RuntimeRoot 'backend-switch.json'
$SwitchLog = Join-Path $RuntimeRoot 'backend-switch.log'
$ManagedMarker = Join-Path $InstallRoot '.docker-mcp-managed'
$VersionFile = Join-Path $InstallRoot 'VERSION'
New-Item -ItemType Directory -Force -Path $RuntimeRoot | Out-Null

function Write-SwitchLog([string] $Message) {
    $Line = '{0} {1}' -f ([DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')), $Message
    Add-Content -LiteralPath $SwitchLog -Value $Line -Encoding UTF8
}

function Assert-RuntimeFileWritable([string] $Path) {
    # An existing file is opened for writing and closed untouched: a DACL that denies the user is found
    # here, before PREPARE, not after COMMIT once a Docker build has run. A missing file is created later
    # in the runtime directory, which New-Item above has just proven usable.
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return }
    try {
        $Stream = [System.IO.File]::Open($Path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::ReadWrite)
        $Stream.Dispose()
    }
    catch {
        throw "MINOS runtime file is not writable by the current user: $Path. Restore the owner's access (icacls `"$Path`" /grant:r `"$($env:USERNAME):F`") and run the installer again."
    }
}

function Read-KeyValueFile([string] $Path) {
    $Values = @{}
    if (Test-Path -LiteralPath $Path -PathType Leaf) {
        foreach ($Line in [System.IO.File]::ReadAllLines($Path, [System.Text.Encoding]::UTF8)) {
            if ($Line -match '^([^=]+)=(.*)$') {
                $Values[$Matches[1].Trim()] = $Matches[2].Trim()
            }
        }
    }
    return $Values
}

function Read-CurrentBackend {
    if (-not (Test-Path -LiteralPath $BackendFile -PathType Leaf)) {
        # Pre-M29 homes migrate to native on first launcher use. Treat the missing
        # file as the same pre-switch state without writing anything yet.
        return 'native'
    }
    $Values = Read-KeyValueFile -Path $BackendFile
    $Backend = [string]$Values['backend']
    if ($Backend -notin @('native', 'docker')) {
        throw "MINOS backend configuration is invalid: expected backend=native|docker in $BackendFile"
    }
    return $Backend
}

function Test-ReusableDockerRuntime {
    if (-not (Test-Path -LiteralPath $ManagedMarker -PathType Leaf)) { return $false }
    if (-not (Test-Path -LiteralPath $VersionFile -PathType Leaf)) { return $false }
    if (-not (Test-Path -LiteralPath (Join-Path $DockerInstallRoot 'runtime') -PathType Container)) { return $false }

    $Marker = Read-KeyValueFile -Path $ManagedMarker
    $Identity = Read-KeyValueFile -Path $VersionFile
    $ExpectedVersion = [string]$Identity['version']
    $ExpectedCommit = [string]$Identity['commit']
    if ([string]::IsNullOrWhiteSpace($ExpectedVersion) -or [string]::IsNullOrWhiteSpace($ExpectedCommit)) { return $false }

    # A marker written by an earlier release also carries projectsRoot: it is no longer read.
    try {
        $MarkerInstall = [System.IO.Path]::GetFullPath([string]$Marker['dockerInstallRoot'])
        $MarkerData = [System.IO.Path]::GetFullPath([string]$Marker['dockerDataRoot'])
    }
    catch { return $false }

    return [string]$Marker['version'] -eq $ExpectedVersion -and
        [string]$Marker['commit'] -eq $ExpectedCommit -and
        $MarkerInstall -eq $DockerInstallRoot -and
        $MarkerData -eq $DockerDataRoot -and
        [string]$Marker['containerName'] -eq $DockerContainerName -and
        [string]$Marker['composeProject'] -eq $DockerComposeProject
}

function Write-BackendConfiguration([string] $Backend) {
    New-Item -ItemType Directory -Force -Path $RuntimeRoot | Out-Null
    $Temporary = Join-Path $RuntimeRoot ('backend.properties.' + [Guid]::NewGuid().ToString('N') + '.tmp')
    $Lines = @(
        '# MINOS MCP backend configuration v1',
        'formatVersion=1',
        "backend=$Backend",
        "docker.containerName=$DockerContainerName",
        ('docker.probeTimeoutMillis=' + ($ProbeTimeoutSeconds * 1000))
    )
    try {
        [System.IO.File]::WriteAllLines($Temporary, $Lines, [System.Text.UTF8Encoding]::new($false))
        Move-Item -LiteralPath $Temporary -Destination $BackendFile -Force
    }
    finally {
        Remove-Item -LiteralPath $Temporary -Force -ErrorAction SilentlyContinue
    }
}

function Restore-BackendConfiguration([bool] $Existed, [byte[]] $Bytes) {
    if (-not $Existed) {
        Remove-Item -LiteralPath $BackendFile -Force -ErrorAction SilentlyContinue
        return
    }
    $Temporary = Join-Path $RuntimeRoot ('backend.properties.rollback.' + [Guid]::NewGuid().ToString('N') + '.tmp')
    try {
        [System.IO.File]::WriteAllBytes($Temporary, $Bytes)
        Move-Item -LiteralPath $Temporary -Destination $BackendFile -Force
    }
    finally {
        Remove-Item -LiteralPath $Temporary -Force -ErrorAction SilentlyContinue
    }
}

function Resolve-DockerRuntimeParameters {
    $Marker = Read-KeyValueFile -Path $ManagedMarker
    $ResolvedInstallRoot = if (-not [string]::IsNullOrWhiteSpace([string]$Marker['dockerInstallRoot'])) {
        [string]$Marker['dockerInstallRoot']
    } else { $DockerInstallRoot }
    $ResolvedDataRoot = if (-not [string]::IsNullOrWhiteSpace([string]$Marker['dockerDataRoot'])) {
        [string]$Marker['dockerDataRoot']
    } else { $DockerDataRoot }
    $ResolvedContainer = if (-not [string]::IsNullOrWhiteSpace([string]$Marker['containerName'])) {
        [string]$Marker['containerName']
    } else { $DockerContainerName }
    $ResolvedProject = if (-not [string]::IsNullOrWhiteSpace([string]$Marker['composeProject'])) {
        [string]$Marker['composeProject']
    } else { $DockerComposeProject }
    return [pscustomobject]@{
        InstallRoot = [System.IO.Path]::GetFullPath($ResolvedInstallRoot)
        DataRoot = [System.IO.Path]::GetFullPath($ResolvedDataRoot)
        ContainerName = $ResolvedContainer
        ComposeProject = $ResolvedProject
    }
}

function Invoke-DockerRuntimeAction(
    [ValidateSet('Start', 'Stop', 'Validate')] [string] $Action,
    [object] $Runtime = $null
) {
    if (-not (Test-Path -LiteralPath $DockerWorkflowPath -PathType Leaf)) {
        throw "MINOS Docker workflow is missing: $DockerWorkflowPath"
    }
    if ($null -eq $Runtime) { $Runtime = Resolve-DockerRuntimeParameters }
    & $DockerWorkflowPath `
        -Action $Action `
        -InstallRoot $Runtime.InstallRoot `
        -DataRoot $Runtime.DataRoot `
        -ContainerName $Runtime.ContainerName `
        -ComposeProject $Runtime.ComposeProject
}

function Stop-CandidateDockerBestEffort {
    if ($TargetBackend -ne 'docker') { return }
    try {
        Invoke-DockerRuntimeAction -Action Stop
        Write-SwitchLog 'ROLLBACK candidate=docker action=stop result=success'
    }
    catch {
        Write-SwitchLog ("ROLLBACK candidate=docker action=stop result=warning message='" + $_.Exception.Message.Replace("'", "''") + "'")
    }
}

function New-DockerRuntimeSnapshot([object] $Runtime) {
    $SnapshotRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('minos-m29-docker-runtime-rollback-' + [Guid]::NewGuid())
    New-Item -ItemType Directory -Force -Path $SnapshotRoot | Out-Null
    $RuntimeSource = Join-Path $Runtime.InstallRoot 'runtime'
    $RuntimeBackup = Join-Path $SnapshotRoot 'runtime'
    $RuntimeExisted = Test-Path -LiteralPath $RuntimeSource -PathType Container
    if ($RuntimeExisted) {
        Copy-Item -LiteralPath $RuntimeSource -Destination $RuntimeBackup -Recurse -Force
    }
    $MarkerExisted = Test-Path -LiteralPath $ManagedMarker -PathType Leaf
    $MarkerBytes = if ($MarkerExisted) { [System.IO.File]::ReadAllBytes($ManagedMarker) } else { [byte[]]@() }
    return [pscustomobject]@{
        Root = $SnapshotRoot
        Runtime = $Runtime
        RuntimeExisted = $RuntimeExisted
        RuntimeBackup = $RuntimeBackup
        MarkerExisted = $MarkerExisted
        MarkerBytes = $MarkerBytes
    }
}

function Restore-DockerRuntimeSnapshot([object] $Snapshot) {
    $RuntimeDestination = Join-Path $Snapshot.Runtime.InstallRoot 'runtime'
    Remove-Item -LiteralPath $RuntimeDestination -Recurse -Force -ErrorAction SilentlyContinue
    if ($Snapshot.RuntimeExisted) {
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $RuntimeDestination) | Out-Null
        Copy-Item -LiteralPath $Snapshot.RuntimeBackup -Destination $RuntimeDestination -Recurse -Force
    }
    if ($Snapshot.MarkerExisted) {
        [System.IO.File]::WriteAllBytes($ManagedMarker, $Snapshot.MarkerBytes)
    }
    else {
        Remove-Item -LiteralPath $ManagedMarker -Force -ErrorAction SilentlyContinue
    }
}

function New-ProjectRootSnapshot {
    # The project mapping of the Docker data root (format 1: one projects root; format 2: the mount table) and the
    # registry entries, kept in memory: the install migrates a format 1 mapping to the mount table, and a failed
    # switch writes them back over the same files, which keeps their access rules. A mapping that did not exist is
    # recorded as $null, so that the one a failed install created is removed.
    $Mapping = Join-Path $DockerDataRoot 'runtime\project-paths.properties'
    $Files = @($Mapping)
    $Projects = Join-Path $DockerDataRoot 'registry\projects'
    if (Test-Path -LiteralPath $Projects -PathType Container) {
        $Files += @(Get-ChildItem -LiteralPath $Projects -File |
            Where-Object { $_.Name -like '*.properties' } | ForEach-Object { $_.FullName })
    }
    $Snapshot = @{}
    foreach ($File in $Files) {
        if (Test-Path -LiteralPath $File -PathType Leaf) { $Snapshot[$File] = [System.IO.File]::ReadAllBytes($File) }
    }
    if (-not $Snapshot.ContainsKey($Mapping)) { $Snapshot[$Mapping] = $null }
    return $Snapshot
}

function Restore-ProjectRootSnapshot([hashtable] $Snapshot) {
    # Each file is written to a temporary of its folder, then swapped in by File.Replace, which keeps the access
    # rules of the file it replaces: a failure leaves every file either restored or as the install left it.
    foreach ($File in @($Snapshot.Keys)) {
        if ($null -eq $Snapshot[$File]) {
            if (Test-Path -LiteralPath $File -PathType Leaf) { Remove-Item -LiteralPath $File -Force }
            continue
        }
        $Temporary = $File + '.' + [Guid]::NewGuid().ToString('N') + '.restore.tmp'
        try {
            [System.IO.File]::WriteAllBytes($Temporary, $Snapshot[$File])
            if (Test-Path -LiteralPath $File -PathType Leaf) { [System.IO.File]::Replace($Temporary, $File, [NullString]::Value) }
            else { [System.IO.File]::Move($Temporary, $File) }
        }
        finally {
            Remove-Item -LiteralPath $Temporary -Force -ErrorAction SilentlyContinue
        }
    }
}

$PreviousBackend = Read-CurrentBackend
$BackendFileExisted = Test-Path -LiteralPath $BackendFile -PathType Leaf
$BackendFileBytes = if ($BackendFileExisted) { [System.IO.File]::ReadAllBytes($BackendFile) } else { [byte[]]@() }
$ReuseDockerRuntime = $TargetBackend -eq 'docker' -and (Test-ReusableDockerRuntime)
$ProjectRootSnapshot = $null
$PreviousDockerRuntime = if ($PreviousBackend -eq 'docker') { Resolve-DockerRuntimeParameters } else { $null }
$DockerRuntimeSnapshot = if ($PreviousBackend -eq 'docker' -and $TargetBackend -eq 'docker' -and -not $ReuseDockerRuntime) {
    New-DockerRuntimeSnapshot -Runtime $PreviousDockerRuntime
} else { $null }
$CandidateHome = Join-Path ([System.IO.Path]::GetTempPath()) ('minos-m29-backend-probe-' + [Guid]::NewGuid())
$Committed = $false

Assert-RuntimeFileWritable -Path $SwitchLog
Assert-RuntimeFileWritable -Path $BackendFile
Assert-RuntimeFileWritable -Path $SwitchStateFile
Write-SwitchLog "BEGIN previous=$PreviousBackend target=$TargetBackend reuseDocker=$ReuseDockerRuntime"
# The legacy parameters are reported, never refused: neither mounts a folder nor reaches the Docker configurator.
# The warning carries no path.
if (-not [string]::IsNullOrWhiteSpace($ProjectsRoot)) {
    $IgnoredWarning = 'The -ProjectsRoot parameter is ignored: the MINOS Docker runtime mounts no projects root. ' +
        'Expose each project with prod-mcp-release.ps1 -Action AddProject -ProjectPath <folder>.'
    Write-Warning $IgnoredWarning
    Write-SwitchLog ("WARNING ignored=ProjectsRoot message='" + $IgnoredWarning + "'")
}
if ($ReplaceDockerProjectsRoot) {
    $IgnoredWarning = 'The -ReplaceDockerProjectsRoot switch is ignored: the MINOS Docker runtime has no projects root to replace.'
    Write-Warning $IgnoredWarning
    Write-SwitchLog ("WARNING ignored=ReplaceDockerProjectsRoot message='" + $IgnoredWarning + "'")
}
try {
    # PREPARE + VALIDATE. Reuse a same-version managed Docker runtime when
    # switching back to it; only a new/mismatched release goes through install.
    if ($TargetBackend -eq 'docker') {
        if ($ReuseDockerRuntime) {
            $ReusableRuntime = Resolve-DockerRuntimeParameters
            Invoke-DockerRuntimeAction -Action Start -Runtime $ReusableRuntime
            Invoke-DockerRuntimeAction -Action Validate -Runtime $ReusableRuntime
            Write-SwitchLog 'PREPARE target=docker mode=reuse result=success'
            # The same-version runtime is started as it is: image and managed services are not rebuilt.
            Write-SetupProgress -Step 'docker-image' -State 'skipped'
            Write-SetupProgress -Step 'postgres' -State 'skipped'
            Write-SetupProgress -Step 'ollama' -State 'skipped'
            Write-SetupProgress -Step 'ollama-model' -State 'skipped'
        }
        else {
            $DockerParameters = @{
                InstallRoot = $InstallRoot
                Start = $true
                Strict = $true
                DockerInstallRoot = $DockerInstallRoot
                DockerDataRoot = $DockerDataRoot
                DockerContainerName = $DockerContainerName
                DockerComposeProject = $DockerComposeProject
                StorageBackend = $StorageBackend
                SemanticProvider = $SemanticProvider
                SemanticModel = $SemanticModel
                SemanticDimensions = $SemanticDimensions
            }
            if (-not [string]::IsNullOrWhiteSpace($DockerImageTag)) {
                $DockerParameters['DockerImageTag'] = $DockerImageTag
            }
            if ($ProvisionOllamaModel) {
                $DockerParameters['ProvisionOllamaModel'] = $true
            }
            # Passed only when set, so an injected test configurator without the parameter stays callable.
            if (-not [string]::IsNullOrWhiteSpace($ProgressPath)) {
                $DockerParameters['ProgressPath'] = $ProgressPath
            }
            # Every install or upgrade may migrate a format 1 mapping (one projects root) to the mount table.
            $ProjectRootSnapshot = New-ProjectRootSnapshot
            Write-SwitchLog 'PREPARE target=docker projectMounts=saved'
            & $DockerConfiguratorPath @DockerParameters
            Write-SwitchLog 'PREPARE target=docker mode=install result=success'
        }
    }
    else {
        Write-SwitchLog 'PREPARE target=native result=success'
    }

    # HANDSHAKE against an isolated candidate MINOS_HOME. This exercises the
    # same stable launcher/router path as clients without mutating the active
    # backend.properties before the candidate proves initialize + tools/list.
    New-Item -ItemType Directory -Force -Path (Join-Path $CandidateHome 'runtime') | Out-Null
    $CandidateBackendFile = Join-Path $CandidateHome 'runtime\backend.properties'
    @(
        '# MINOS MCP backend configuration v1',
        'formatVersion=1',
        "backend=$TargetBackend",
        "docker.containerName=$DockerContainerName",
        ('docker.probeTimeoutMillis=' + ($ProbeTimeoutSeconds * 1000))
    ) | Set-Content -LiteralPath $CandidateBackendFile -Encoding ascii

    Write-SetupProgress -Step 'handshake' -State 'running'
    & $HandshakeProbePath -LauncherPath $LauncherPath -CandidateHome $CandidateHome -TimeoutSeconds $ProbeTimeoutSeconds
    Write-SwitchLog "HANDSHAKE target=$TargetBackend result=success"
    Write-SetupProgress -Step 'handshake' -State 'done'

    # COMMIT only after the new backend has proved readiness.
    Write-BackendConfiguration -Backend $TargetBackend
    $Committed = $true
    Write-SwitchLog "COMMIT backend=$TargetBackend result=success"

    # RETIRE the old persistent query plane only after commit. Native MCP has no
    # persistent process to retire. Docker is stopped, not uninstalled, so a
    # later switch can reuse its persisted data and setup-owned runtime.
    if ($PreviousBackend -eq 'docker' -and $TargetBackend -eq 'native') {
        Invoke-DockerRuntimeAction -Action Stop -Runtime $PreviousDockerRuntime
        Write-SwitchLog 'RETIRE backend=docker action=stop result=success'
    }

    [ordered]@{
        formatVersion = 1
        switchedAt = [DateTime]::UtcNow.ToString('o')
        previousBackend = $PreviousBackend
        backend = $TargetBackend
        launcher = $LauncherPath
        dataRoot = $DataRoot
        dockerInstallRoot = $DockerInstallRoot
        dockerDataRoot = $DockerDataRoot
        dockerContainerName = $DockerContainerName
        dockerComposeProject = $DockerComposeProject
        reusedDockerRuntime = $ReuseDockerRuntime
        transaction = 'prepare -> validate -> handshake -> commit -> retire'
    } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $SwitchStateFile -Encoding utf8

    Write-SwitchLog "SUCCESS previous=$PreviousBackend target=$TargetBackend"
    Write-Host "MINOS MCP BACKEND SWITCH SUCCESS: $PreviousBackend -> $TargetBackend" -ForegroundColor Green
    Write-Host "Config : $BackendFile"
}
catch {
    $Failure = $_
    Write-SwitchLog ("FAIL previous=$PreviousBackend target=$TargetBackend committed=$Committed message='" + $Failure.Exception.Message.Replace("'", "''") + "'")

    if ($Committed) {
        try {
            Restore-BackendConfiguration -Existed $BackendFileExisted -Bytes $BackendFileBytes
            Write-SwitchLog "ROLLBACK config=$PreviousBackend result=success"
        }
        catch {
            Write-SwitchLog ("ROLLBACK config=$PreviousBackend result=failure message='" + $_.Exception.Message.Replace("'", "''") + "'")
            throw "MINOS backend switch failed and backend configuration rollback also failed: $($Failure.Exception.Message); rollback: $($_.Exception.Message)"
        }
    }

    # Before any previous runtime starts again: it reads the mapping and the registry it was installed with.
    if ($null -ne $ProjectRootSnapshot) {
        try {
            Restore-ProjectRootSnapshot -Snapshot $ProjectRootSnapshot
            Write-SwitchLog 'ROLLBACK projectMounts=restored result=success'
        }
        catch {
            Write-SwitchLog ("ROLLBACK projectMounts=restored result=failure message='" + $_.Exception.Message.Replace("'", "''") + "'")
            throw ('MINOS backend switch failed and the Docker project mapping could not be restored: ' +
                "$($Failure.Exception.Message); restore: $($_.Exception.Message)")
        }
    }

    if ($PreviousBackend -eq 'docker' -and $TargetBackend -eq 'docker' -and -not $ReuseDockerRuntime) {
        try {
            Stop-CandidateDockerBestEffort
            Restore-DockerRuntimeSnapshot -Snapshot $DockerRuntimeSnapshot
            Invoke-DockerRuntimeAction -Action Start -Runtime $PreviousDockerRuntime
            Write-SwitchLog 'ROLLBACK upgrade=docker runtime=restored action=start result=success'
        }
        catch {
            Write-SwitchLog ("ROLLBACK upgrade=docker result=failure message='" + $_.Exception.Message.Replace("'", "''") + "'")
            throw "MINOS Docker upgrade failed and the previous Docker runtime could not be restored: $($Failure.Exception.Message); rollback: $($_.Exception.Message)"
        }
    }
    elseif ($PreviousBackend -eq 'docker' -and $TargetBackend -eq 'native') {
        try {
            Invoke-DockerRuntimeAction -Action Start -Runtime $PreviousDockerRuntime
            Write-SwitchLog 'ROLLBACK backend=docker action=start result=success'
        }
        catch {
            Write-SwitchLog ("ROLLBACK backend=docker action=start result=failure message='" + $_.Exception.Message.Replace("'", "''") + "'")
            throw "MINOS backend switch failed and the previous Docker backend could not be restarted: $($Failure.Exception.Message); restart: $($_.Exception.Message)"
        }
    }
    elseif ($TargetBackend -eq 'docker' -and $PreviousBackend -ne 'docker') {
        Stop-CandidateDockerBestEffort
    }

    throw $Failure
}
finally {
    Remove-Item -LiteralPath $CandidateHome -Recurse -Force -ErrorAction SilentlyContinue
    if ($null -ne $DockerRuntimeSnapshot) {
        Remove-Item -LiteralPath $DockerRuntimeSnapshot.Root -Recurse -Force -ErrorAction SilentlyContinue
    }
}
