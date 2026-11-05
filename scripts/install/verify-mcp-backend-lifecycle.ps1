[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($env:OS -ne 'Windows_NT') {
    throw 'MINOS MCP backend lifecycle verification must run on Windows.'
}

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$Switcher = Join-Path $RepoRoot 'scripts\install\switch-mcp-backend.ps1'
if (-not (Test-Path -LiteralPath $Switcher -PathType Leaf)) {
    throw "MINOS backend switcher not found: $Switcher"
}

function Assert-True([bool] $Condition, [string] $Message) {
    if (-not $Condition) { throw $Message }
}

function Read-Backend([string] $DataRoot) {
    $Path = Join-Path $DataRoot 'runtime\backend.properties'
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return '' }
    $Line = [System.IO.File]::ReadAllLines($Path, [System.Text.Encoding]::UTF8) |
        Where-Object { $_ -like 'backend=*' } |
        Select-Object -First 1
    if ([string]::IsNullOrWhiteSpace($Line)) { return '' }
    return ($Line -split '=', 2)[1].Trim()
}

function Write-Identity([string] $InstallRoot, [string] $Version, [string] $Commit) {
    @(
        "version=$Version",
        "commit=$Commit"
    ) | Set-Content -LiteralPath (Join-Path $InstallRoot 'VERSION') -Encoding ascii
}

function Write-FakeScripts([string] $Root) {
    $Probe = Join-Path $Root 'fake-probe.ps1'
    $Configurator = Join-Path $Root 'fake-docker-configurator.ps1'
    $Workflow = Join-Path $Root 'fake-docker-workflow.ps1'

    @'
param([string]$LauncherPath,[string]$CandidateHome,[int]$TimeoutSeconds)
$backend = ''
$path = Join-Path $CandidateHome 'runtime\backend.properties'
foreach ($line in [System.IO.File]::ReadAllLines($path)) {
    if ($line -like 'backend=*') { $backend = ($line -split '=',2)[1].Trim() }
}
Add-Content -LiteralPath $env:MINOS_S7_FAKE_LOG -Value ("probe:" + $backend) -Encoding ascii
if ($env:MINOS_S7_FAIL_PROBE -eq $backend) { throw ("forced probe failure:" + $backend) }
'@ | Set-Content -LiteralPath $Probe -Encoding ascii

    @'
param(
 [string]$InstallRoot,[string]$ProjectsRoot,[switch]$Start,[switch]$Strict,
 [string]$DockerInstallRoot,[string]$DockerDataRoot,[string]$DockerImageTag,
 [string]$DockerContainerName,[string]$DockerComposeProject,[switch]$ReplaceProjectsRoot)
Add-Content -LiteralPath $env:MINOS_S7_FAKE_LOG -Value 'configure:docker' -Encoding ascii
# The switcher forwards no projects root and no replacement: either one reaching the configurator is a defect.
if (-not [string]::IsNullOrWhiteSpace($ProjectsRoot)) {
    Add-Content -LiteralPath $env:MINOS_S7_FAKE_LOG -Value 'configure:projectsRoot' -Encoding ascii
}
if ($ReplaceProjectsRoot) { Add-Content -LiteralPath $env:MINOS_S7_FAKE_LOG -Value 'configure:replace' -Encoding ascii }
# Stands for mcp-lifecycle.ps1 -Action Install: a format 1 mapping (one projects root) is migrated to the
# format 2 mount table; MINOS_S7_FAIL_MIGRATION=1 fails the migration after it rewrote the mapping.
$mapping = Join-Path $DockerDataRoot 'runtime\project-paths.properties'
$format1 = (Test-Path -LiteralPath $mapping -PathType Leaf) -and
    (@([System.IO.File]::ReadAllLines($mapping)) -contains 'formatVersion=1')
if ($format1 -or ($env:MINOS_S7_CREATE_MAPPING -eq '1')) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $mapping) | Out-Null
    @('formatVersion=2', 'containerRoot=/workspace/projects', 'mount.0.name=app') |
        Set-Content -LiteralPath $mapping -Encoding ascii
    Add-Content -LiteralPath $env:MINOS_S7_FAKE_LOG -Value 'configure:migrate' -Encoding ascii
    if ($env:MINOS_S7_FAIL_MIGRATION -eq '1') { throw 'forced project mount migration failure' }
}
$runtime = Join-Path $DockerInstallRoot 'runtime'
New-Item -ItemType Directory -Force -Path $runtime | Out-Null
$generation = if (Test-Path -LiteralPath (Join-Path $runtime 'generation.txt')) {
    [int]([System.IO.File]::ReadAllText((Join-Path $runtime 'generation.txt')).Trim()) + 1
} else { 1 }
[System.IO.File]::WriteAllText((Join-Path $runtime 'generation.txt'), [string]$generation)
$identity = @{}
foreach ($line in [System.IO.File]::ReadAllLines((Join-Path $InstallRoot 'VERSION'))) {
    if ($line -match '^([^=]+)=(.*)$') { $identity[$Matches[1].Trim()] = $Matches[2].Trim() }
}
@(
 ('version=' + [string]$identity['version']),
 ('commit=' + [string]$identity['commit']),
 ('dockerInstallRoot=' + $DockerInstallRoot),
 ('dockerDataRoot=' + $DockerDataRoot),
 ('containerName=' + $DockerContainerName),
 ('composeProject=' + $DockerComposeProject)
) | Set-Content -LiteralPath (Join-Path $InstallRoot '.docker-mcp-managed') -Encoding ascii
if ($env:MINOS_S7_FAIL_CONFIGURE -eq '1') { throw 'forced docker configure failure' }
'@ | Set-Content -LiteralPath $Configurator -Encoding ascii

    @'
param([string]$Action,[string]$InstallRoot,[string]$DataRoot,[string]$ContainerName,[string]$ComposeProject)
Add-Content -LiteralPath $env:MINOS_S7_FAKE_LOG -Value ("workflow:" + $Action) -Encoding ascii
if ($env:MINOS_S7_FAIL_DOCKER_ACTION -eq $Action) { throw ("forced docker workflow failure:" + $Action) }
'@ | Set-Content -LiteralPath $Workflow -Encoding ascii

    return [pscustomobject]@{ Probe=$Probe; Configurator=$Configurator; Workflow=$Workflow }
}

$Root = Join-Path ([System.IO.Path]::GetTempPath()) ('minos-m29-s7-lifecycle-' + [Guid]::NewGuid())
$OldFailProbe = $env:MINOS_S7_FAIL_PROBE
$OldFailConfigure = $env:MINOS_S7_FAIL_CONFIGURE
$OldFailAction = $env:MINOS_S7_FAIL_DOCKER_ACTION
$OldFakeLog = $env:MINOS_S7_FAKE_LOG
$OldFailMigration = $env:MINOS_S7_FAIL_MIGRATION
$OldCreateMapping = $env:MINOS_S7_CREATE_MAPPING
try {
    $InstallRoot = Join-Path $Root 'install'
    $DataRoot = Join-Path $Root 'data'
    $DockerInstallRoot = Join-Path $Root 'docker-runtime'
    $DockerDataRoot = Join-Path $Root 'docker-data'
    $ProjectsRoot = Join-Path $Root 'projects'
    $Launcher = Join-Path $InstallRoot 'app\minos.exe'
    $ThirdParty = Join-Path $Root 'third-party-client.json'
    $FakeLog = Join-Path $Root 'fake-actions.log'
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Launcher), $DataRoot, $DockerDataRoot, $ProjectsRoot | Out-Null
    New-Item -ItemType File -Force -Path $Launcher | Out-Null
    Write-Identity -InstallRoot $InstallRoot -Version '1.0.1-test1' -Commit 'commit-1'
    [System.IO.File]::WriteAllText($ThirdParty, '{"owner":"user","unchanged":true}', [System.Text.UTF8Encoding]::new($false))
    $ThirdPartyHash = (Get-FileHash -LiteralPath $ThirdParty -Algorithm SHA256).Hash
    $Fakes = Write-FakeScripts -Root $Root
    $env:MINOS_S7_FAKE_LOG = $FakeLog
    $env:MINOS_S7_FAIL_PROBE = ''
    $env:MINOS_S7_FAIL_CONFIGURE = ''
    $env:MINOS_S7_FAIL_DOCKER_ACTION = ''
    $env:MINOS_S7_FAIL_MIGRATION = ''
    $env:MINOS_S7_CREATE_MAPPING = ''

    $Common = @{
        InstallRoot = $InstallRoot
        DataRoot = $DataRoot
        DockerInstallRoot = $DockerInstallRoot
        DockerDataRoot = $DockerDataRoot
        DockerContainerName = 'minos-s7-fake'
        DockerComposeProject = 'minos-s7-fake'
        LauncherPath = $Launcher
        HandshakeProbePath = $Fakes.Probe
        DockerConfiguratorPath = $Fakes.Configurator
        DockerWorkflowPath = $Fakes.Workflow
    }

    # Fresh native-only install.
    & $Switcher @Common -TargetBackend native
    Assert-True ((Read-Backend $DataRoot) -eq 'native') 'Fresh native backend selection was not committed.'

    # Native -> Docker initial install; candidate must be configured and probed.
    & $Switcher @Common -TargetBackend docker
    Assert-True ((Read-Backend $DataRoot) -eq 'docker') 'Native -> Docker switch was not committed.'
    $Generation1 = [int]([System.IO.File]::ReadAllText((Join-Path $DockerInstallRoot 'runtime\generation.txt')).Trim())
    $Actions = [System.IO.File]::ReadAllLines($FakeLog)
    Assert-True ($Actions -contains 'configure:docker') 'Docker candidate was not prepared.'
    Assert-True ($Actions -contains 'probe:docker') 'Docker candidate was not handshaken.'

    # Docker -> native retires the persistent Docker query plane after commit.
    & $Switcher @Common -TargetBackend native
    Assert-True ((Read-Backend $DataRoot) -eq 'native') 'Docker -> native switch was not committed.'
    $Actions = [System.IO.File]::ReadAllLines($FakeLog)
    Assert-True ($Actions -contains 'workflow:Stop') 'Docker -> native did not retire the old Docker query plane.'

    # Same-version native -> Docker must reuse the managed runtime instead of
    # rebuilding it. Start + Validate + handshake prove readiness before commit.
    # The marker an earlier release wrote still carries a projects root: it is read, not compared.
    $Marker = Join-Path $InstallRoot '.docker-mcp-managed'
    Assert-True (-not (@([System.IO.File]::ReadAllLines($Marker)) -like 'projectsRoot=?*')) 'The Docker ownership marker still records a projects root.'
    Add-Content -LiteralPath $Marker -Value ('projectsRoot=' + (Join-Path $Root 'legacy-projects')) -Encoding ascii
    & $Switcher @Common -TargetBackend docker
    $GenerationAfterReuse = [int]([System.IO.File]::ReadAllText((Join-Path $DockerInstallRoot 'runtime\generation.txt')).Trim())
    Assert-True ($GenerationAfterReuse -eq $Generation1) 'Same-version native -> Docker rebuilt the managed runtime instead of reusing it.'
    Assert-True ((Read-Backend $DataRoot) -eq 'docker') 'Reusable Docker runtime was not selected.'
    $Actions = [System.IO.File]::ReadAllLines($FakeLog)
    Assert-True ($Actions -contains 'workflow:Validate') 'Reusable Docker runtime was not validated before commit.'

    # Return to native, then force candidate handshake failure on the reusable
    # Docker path: config stays native and the started candidate is stopped.
    & $Switcher @Common -TargetBackend native
    $env:MINOS_S7_FAIL_PROBE = 'docker'
    $FailedAsExpected = $false
    try { & $Switcher @Common -TargetBackend docker } catch { $FailedAsExpected = $true }
    Assert-True $FailedAsExpected 'Forced Docker handshake failure did not fail the switch.'
    Assert-True ((Read-Backend $DataRoot) -eq 'native') 'Failed Docker handshake changed the active backend.'
    $env:MINOS_S7_FAIL_PROBE = ''

    # Re-establish Docker, then fail retirement while switching to native. The
    # previous config must be restored and the Docker backend restarted.
    & $Switcher @Common -TargetBackend docker
    Assert-True ((Read-Backend $DataRoot) -eq 'docker') 'Docker setup before retirement rollback test failed.'
    $env:MINOS_S7_FAIL_DOCKER_ACTION = 'Stop'
    $FailedAsExpected = $false
    try { & $Switcher @Common -TargetBackend native } catch { $FailedAsExpected = $true }
    Assert-True $FailedAsExpected 'Forced old-backend retirement failure did not fail the switch.'
    Assert-True ((Read-Backend $DataRoot) -eq 'docker') 'Retirement failure did not restore the previous Docker config.'
    $env:MINOS_S7_FAIL_DOCKER_ACTION = ''
    $Actions = [System.IO.File]::ReadAllLines($FakeLog)
    Assert-True ($Actions -contains 'workflow:Start') 'Retirement rollback did not restart the previous Docker backend.'

    # Changing the package identity is a real Docker upgrade: runtime generation
    # must advance and the marker must be replaced only after preparation.
    Write-Identity -InstallRoot $InstallRoot -Version '1.0.1-test2' -Commit 'commit-2'
    $BeforeGeneration = [int]([System.IO.File]::ReadAllText((Join-Path $DockerInstallRoot 'runtime\generation.txt')).Trim())
    & $Switcher @Common -TargetBackend docker
    $AfterGeneration = [int]([System.IO.File]::ReadAllText((Join-Path $DockerInstallRoot 'runtime\generation.txt')).Trim())
    Assert-True ($AfterGeneration -eq ($BeforeGeneration + 1)) 'Docker package upgrade did not prepare a new runtime generation.'
    Assert-True ((Read-Backend $DataRoot) -eq 'docker') 'Docker package upgrade changed backend selection.'

    # A failed next-version upgrade must restore both previous Docker runtime
    # generation and active query plane, not merely leave backend=docker.
    $StableGeneration = $AfterGeneration
    Write-Identity -InstallRoot $InstallRoot -Version '1.0.1-test3' -Commit 'commit-3'
    $env:MINOS_S7_FAIL_PROBE = 'docker'
    $FailedAsExpected = $false
    try { & $Switcher @Common -TargetBackend docker } catch { $FailedAsExpected = $true }
    Assert-True $FailedAsExpected 'Forced Docker upgrade handshake failure did not fail the upgrade.'
    $env:MINOS_S7_FAIL_PROBE = ''
    $RolledBackGeneration = [int]([System.IO.File]::ReadAllText((Join-Path $DockerInstallRoot 'runtime\generation.txt')).Trim())
    Assert-True ($RolledBackGeneration -eq $StableGeneration) 'Failed Docker upgrade did not restore the previous runtime generation.'
    Assert-True ((Read-Backend $DataRoot) -eq 'docker') 'Failed Docker upgrade changed the active backend selection.'
    $Actions = [System.IO.File]::ReadAllLines($FakeLog)
    Assert-True ($Actions -contains 'workflow:Start') 'Failed Docker upgrade did not restart the restored Docker runtime.'

    # The Docker runtime of an earlier release mounted one projects root (mapping format 1, one registered
    # project). The root differs from the one passed below: neither is compared, refused or forwarded.
    $OtherRoot = Join-Path $Root 'other-projects'
    $Mapping = Join-Path $DockerDataRoot 'runtime\project-paths.properties'
    $Entry = Join-Path $DockerDataRoot ('registry\projects\' + [Guid]::NewGuid() + '.properties')
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Mapping), (Split-Path -Parent $Entry) | Out-Null
    @('formatVersion=1', ('hostRoot=' + $OtherRoot.Replace('\', '/').Replace(':', '\:')),
      'containerRoot=/workspace/projects') | Set-Content -LiteralPath $Mapping -Encoding ascii
    @('id=fixture', 'rootRelativePath=app') | Set-Content -LiteralPath $Entry -Encoding ascii
    $MappingBytes = [System.IO.File]::ReadAllBytes($Mapping)
    $EntryBytes = [System.IO.File]::ReadAllBytes($Entry)
    $SwitchLog = Join-Path $DataRoot 'runtime\backend-switch.log'

    # Upgrade whose migration fails after rewriting the mapping: the format 1 mapping and the registry entry
    # are written back byte for byte, the previous runtime is restored and the backend stays docker.
    Write-Identity -InstallRoot $InstallRoot -Version '1.0.1-test4' -Commit 'commit-4'
    $env:MINOS_S7_FAIL_MIGRATION = '1'
    $FailedAsExpected = $false
    try { & $Switcher @Common -TargetBackend docker -ProjectsRoot $ProjectsRoot -ReplaceDockerProjectsRoot } catch { $FailedAsExpected = $true }
    $env:MINOS_S7_FAIL_MIGRATION = ''
    Assert-True $FailedAsExpected 'A forced project mount migration failure did not fail the upgrade.'
    Assert-True (([System.IO.File]::ReadAllLines($FakeLog)) -contains 'configure:migrate') 'The upgrade did not reach the project mount migration.'
    Assert-True ([Convert]::ToBase64String([System.IO.File]::ReadAllBytes($Mapping)) -eq [Convert]::ToBase64String($MappingBytes)) 'A failed migration did not restore the format 1 mapping byte for byte.'
    Assert-True ([Convert]::ToBase64String([System.IO.File]::ReadAllBytes($Entry)) -eq [Convert]::ToBase64String($EntryBytes)) 'A failed migration did not restore the registry entry byte for byte.'
    Assert-True ((Read-Backend $DataRoot) -eq 'docker') 'A failed migration changed the active backend selection.'

    # Upgrade whose handshake fails after a successful migration: the format 1 mapping is restored too.
    $env:MINOS_S7_FAIL_PROBE = 'docker'
    $FailedAsExpected = $false
    try { & $Switcher @Common -TargetBackend docker } catch { $FailedAsExpected = $true }
    $env:MINOS_S7_FAIL_PROBE = ''
    Assert-True $FailedAsExpected 'A forced handshake failure after the migration did not fail the upgrade.'
    Assert-True ([Convert]::ToBase64String([System.IO.File]::ReadAllBytes($Mapping)) -eq [Convert]::ToBase64String($MappingBytes)) 'A failed upgrade did not restore the migrated format 1 mapping byte for byte.'

    # Legacy parameters: ignored with a warning in the switch log; nothing is forwarded to the configurator.
    & $Switcher @Common -TargetBackend docker -ProjectsRoot $ProjectsRoot -ReplaceDockerProjectsRoot
    Assert-True ((Read-Backend $DataRoot) -eq 'docker') 'The upgrade with legacy parameters was not committed.'
    Assert-True (@([System.IO.File]::ReadAllLines($Mapping)) -contains 'formatVersion=2') 'The committed upgrade did not keep the migrated mapping.'
    $LogText = [System.IO.File]::ReadAllText($SwitchLog)
    Assert-True ($LogText.Contains('WARNING ignored=ProjectsRoot')) 'A legacy -ProjectsRoot did not reach the switch log as a warning.'
    Assert-True ($LogText.Contains('WARNING ignored=ReplaceDockerProjectsRoot')) 'A legacy -ReplaceDockerProjectsRoot did not reach the switch log as a warning.'
    Assert-True (-not $LogText.Contains('projectsRoot=replace')) 'The switcher still prepared a projects root replacement.'
    $Forwarded = @([System.IO.File]::ReadAllLines($FakeLog) | Where-Object { $_ -eq 'configure:projectsRoot' -or $_ -eq 'configure:replace' })
    Assert-True ($Forwarded.Count -eq 0) 'The switcher forwarded a projects root or a replacement to the Docker configurator.'
    $State = [System.IO.File]::ReadAllText((Join-Path $DataRoot 'runtime\backend-switch.json'))
    Assert-True (-not $State.Contains('projectsRoot')) 'The switch state still records a projects root.'

    # Install whose configurator creates the mount table and then fails: the table it created is removed.
    Remove-Item -LiteralPath $Mapping -Force
    Write-Identity -InstallRoot $InstallRoot -Version '1.0.1-test5' -Commit 'commit-5'
    $env:MINOS_S7_CREATE_MAPPING = '1'
    $env:MINOS_S7_FAIL_MIGRATION = '1'
    $FailedAsExpected = $false
    try { & $Switcher @Common -TargetBackend docker } catch { $FailedAsExpected = $true }
    $env:MINOS_S7_CREATE_MAPPING = ''
    $env:MINOS_S7_FAIL_MIGRATION = ''
    Assert-True $FailedAsExpected 'A forced failure after creating the mount table did not fail the upgrade.'
    Assert-True (-not (Test-Path -LiteralPath $Mapping)) 'A failed install left the mount table it created.'

    # Third-party client configuration is outside backend transaction and must
    # remain byte-identical through every switch/rollback/upgrade case.
    Assert-True (((Get-FileHash -LiteralPath $ThirdParty -Algorithm SHA256).Hash) -eq $ThirdPartyHash) 'Backend lifecycle modified unrelated third-party client configuration.'

    Write-Host 'MINOS MCP BACKEND TRANSACTIONAL LIFECYCLE VERIFICATION SUCCESS' -ForegroundColor Green
    Write-Host 'Cases: native install, Docker install, reuse (legacy marker), native<->Docker, rollback, Docker upgrade + upgrade rollback, project mount migration (failed, restored, committed), legacy projects root parameters ignored'
}
finally {
    $env:MINOS_S7_FAIL_MIGRATION = $OldFailMigration
    $env:MINOS_S7_CREATE_MAPPING = $OldCreateMapping
    $env:MINOS_S7_FAIL_PROBE = $OldFailProbe
    $env:MINOS_S7_FAIL_CONFIGURE = $OldFailConfigure
    $env:MINOS_S7_FAIL_DOCKER_ACTION = $OldFailAction
    $env:MINOS_S7_FAKE_LOG = $OldFakeLog
    Remove-Item -LiteralPath $Root -Recurse -Force -ErrorAction SilentlyContinue
}
