[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $InstallRoot,

    [Parameter(Mandatory = $true)]
    [ValidateSet('native', 'docker')]
    [string] $TargetBackend,

    [string] $ProjectsRoot = '',
    [string] $DataRoot = '',
    [string] $DockerInstallRoot = '',
    [string] $DockerDataRoot = '',
    [string] $DockerImageTag = '',

    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9_.-]+$')]
    [string] $DockerContainerName = 'minos-mcp-prod',

    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9_.-]+$')]
    [string] $DockerComposeProject = 'minos-mcp-prod',

    [ValidateRange(1, 120)]
    [int] $ProbeTimeoutSeconds = 20,

    # Qualification/test injection points. Production setup does not override them.
    [string] $LauncherPath = '',
    [string] $HandshakeProbePath = '',
    [string] $DockerConfiguratorPath = '',
    [string] $DockerWorkflowPath = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

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
if (-not [string]::IsNullOrWhiteSpace($ProjectsRoot)) {
    $ProjectsRoot = [System.IO.Path]::GetFullPath($ProjectsRoot)
}

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
    if ([string]::IsNullOrWhiteSpace($ProjectsRoot)) {
        throw '-ProjectsRoot is required when switching to the Docker backend.'
    }
    if (-not (Test-Path -LiteralPath $ProjectsRoot -PathType Container)) {
        throw "MINOS Docker projects root does not exist: $ProjectsRoot"
    }
}

$RuntimeRoot = Join-Path $DataRoot 'runtime'
$BackendFile = Join-Path $RuntimeRoot 'backend.properties'
$SwitchStateFile = Join-Path $RuntimeRoot 'backend-switch.json'
$SwitchLog = Join-Path $RuntimeRoot 'backend-switch.log'
$ManagedMarker = Join-Path $InstallRoot '.docker-mcp-managed'
New-Item -ItemType Directory -Force -Path $RuntimeRoot | Out-Null

function Write-SwitchLog([string] $Message) {
    $Line = '{0} {1}' -f ([DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')), $Message
    Add-Content -LiteralPath $SwitchLog -Value $Line -Encoding UTF8
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
    [ValidateSet('Start', 'Stop')] [string] $Action,
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

$PreviousBackend = Read-CurrentBackend
$BackendFileExisted = Test-Path -LiteralPath $BackendFile -PathType Leaf
$BackendFileBytes = if ($BackendFileExisted) { [System.IO.File]::ReadAllBytes($BackendFile) } else { [byte[]]@() }
$PreviousDockerRuntime = if ($PreviousBackend -eq 'docker') { Resolve-DockerRuntimeParameters } else { $null }
$DockerRuntimeSnapshot = if ($PreviousBackend -eq 'docker' -and $TargetBackend -eq 'docker') {
    New-DockerRuntimeSnapshot -Runtime $PreviousDockerRuntime
} else { $null }
$CandidateHome = Join-Path ([System.IO.Path]::GetTempPath()) ('minos-m29-backend-probe-' + [Guid]::NewGuid())
$Committed = $false

Write-SwitchLog "BEGIN previous=$PreviousBackend target=$TargetBackend"
try {
    # PREPARE + VALIDATE. Docker setup performs install/build, start and its
    # provider/query validation before the shared backend selection changes.
    if ($TargetBackend -eq 'docker') {
        $DockerParameters = @{
            InstallRoot = $InstallRoot
            ProjectsRoot = $ProjectsRoot
            Start = $true
            Strict = $true
            DockerInstallRoot = $DockerInstallRoot
            DockerDataRoot = $DockerDataRoot
            DockerContainerName = $DockerContainerName
            DockerComposeProject = $DockerComposeProject
        }
        if (-not [string]::IsNullOrWhiteSpace($DockerImageTag)) {
            $DockerParameters['DockerImageTag'] = $DockerImageTag
        }
        & $DockerConfiguratorPath @DockerParameters
        Write-SwitchLog 'PREPARE target=docker result=success'
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

    & $HandshakeProbePath -LauncherPath $LauncherPath -CandidateHome $CandidateHome -TimeoutSeconds $ProbeTimeoutSeconds
    Write-SwitchLog "HANDSHAKE target=$TargetBackend result=success"

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
        projectsRoot = $ProjectsRoot
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

    if ($PreviousBackend -eq 'docker' -and $TargetBackend -eq 'docker') {
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
    elseif ($TargetBackend -eq 'docker') {
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
