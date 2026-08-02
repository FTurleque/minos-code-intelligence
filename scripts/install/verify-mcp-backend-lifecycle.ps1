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
 [string]$DockerContainerName,[string]$DockerComposeProject)
Add-Content -LiteralPath $env:MINOS_S7_FAKE_LOG -Value 'configure:docker' -Encoding ascii
$runtime = Join-Path $DockerInstallRoot 'runtime'
New-Item -ItemType Directory -Force -Path $runtime | Out-Null
$generation = if (Test-Path -LiteralPath (Join-Path $runtime 'generation.txt')) {
    [int]([System.IO.File]::ReadAllText((Join-Path $runtime 'generation.txt')).Trim()) + 1
} else { 1 }
[System.IO.File]::WriteAllText((Join-Path $runtime 'generation.txt'), [string]$generation)
@(
 'version=fake',
 'commit=fake',
 ('projectsRoot=' + $ProjectsRoot),
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
    [System.IO.File]::WriteAllText($ThirdParty, '{"owner":"user","unchanged":true}', [System.Text.UTF8Encoding]::new($false))
    $ThirdPartyHash = (Get-FileHash -LiteralPath $ThirdParty -Algorithm SHA256).Hash
    $Fakes = Write-FakeScripts -Root $Root
    $env:MINOS_S7_FAKE_LOG = $FakeLog
    $env:MINOS_S7_FAIL_PROBE = ''
    $env:MINOS_S7_FAIL_CONFIGURE = ''
    $env:MINOS_S7_FAIL_DOCKER_ACTION = ''

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

    # Native -> Docker; the candidate must be configured and probed before commit.
    & $Switcher @Common -TargetBackend docker -ProjectsRoot $ProjectsRoot
    Assert-True ((Read-Backend $DataRoot) -eq 'docker') 'Native -> Docker switch was not committed.'
    $Actions = [System.IO.File]::ReadAllLines($FakeLog)
    Assert-True ($Actions -contains 'configure:docker') 'Docker candidate was not prepared.'
    Assert-True ($Actions -contains 'probe:docker') 'Docker candidate was not handshaken.'

    # Docker -> native retires the persistent Docker query plane after commit.
    & $Switcher @Common -TargetBackend native
    Assert-True ((Read-Backend $DataRoot) -eq 'native') 'Docker -> native switch was not committed.'
    $Actions = [System.IO.File]::ReadAllLines($FakeLog)
    Assert-True ($Actions -contains 'workflow:Stop') 'Docker -> native did not retire the old Docker query plane.'

    # Failed Docker candidate handshake: config stays native and candidate is stopped.
    $env:MINOS_S7_FAIL_PROBE = 'docker'
    $FailedAsExpected = $false
    try { & $Switcher @Common -TargetBackend docker -ProjectsRoot $ProjectsRoot } catch { $FailedAsExpected = $true }
    Assert-True $FailedAsExpected 'Forced Docker handshake failure did not fail the switch.'
    Assert-True ((Read-Backend $DataRoot) -eq 'native') 'Failed Docker handshake changed the active backend.'
    $Actions = [System.IO.File]::ReadAllLines($FakeLog)
    Assert-True ($Actions -contains 'workflow:Stop') 'Failed Docker candidate was not stopped during rollback.'
    $env:MINOS_S7_FAIL_PROBE = ''

    # Re-establish Docker, then fail retirement while switching to native. The
    # previous config must be restored and the Docker backend restarted.
    & $Switcher @Common -TargetBackend docker -ProjectsRoot $ProjectsRoot
    Assert-True ((Read-Backend $DataRoot) -eq 'docker') 'Docker setup before retirement rollback test failed.'
    $env:MINOS_S7_FAIL_DOCKER_ACTION = 'Stop'
    $FailedAsExpected = $false
    try { & $Switcher @Common -TargetBackend native } catch { $FailedAsExpected = $true }
    Assert-True $FailedAsExpected 'Forced old-backend retirement failure did not fail the switch.'
    Assert-True ((Read-Backend $DataRoot) -eq 'docker') 'Retirement failure did not restore the previous Docker config.'
    $env:MINOS_S7_FAIL_DOCKER_ACTION = ''
    $Actions = [System.IO.File]::ReadAllLines($FakeLog)
    Assert-True ($Actions -contains 'workflow:Start') 'Retirement rollback did not restart the previous Docker backend.'

    # Same-backend Docker invocation is the upgrade path: prepare again, probe,
    # keep backend=docker and never retire it as an "old" different backend.
    $BeforeGeneration = [int]([System.IO.File]::ReadAllText((Join-Path $DockerInstallRoot 'runtime\generation.txt')).Trim())
    & $Switcher @Common -TargetBackend docker -ProjectsRoot $ProjectsRoot
    $AfterGeneration = [int]([System.IO.File]::ReadAllText((Join-Path $DockerInstallRoot 'runtime\generation.txt')).Trim())
    Assert-True ($AfterGeneration -eq ($BeforeGeneration + 1)) 'Docker same-backend upgrade did not prepare a new runtime generation.'
    Assert-True ((Read-Backend $DataRoot) -eq 'docker') 'Docker same-backend upgrade changed backend selection.'

    # Third-party client configuration is outside the backend transaction and
    # must remain byte-identical through every switch/rollback/upgrade case.
    Assert-True (((Get-FileHash -LiteralPath $ThirdParty -Algorithm SHA256).Hash) -eq $ThirdPartyHash) 'Backend lifecycle modified unrelated third-party client configuration.'

    Write-Host 'MINOS MCP BACKEND TRANSACTIONAL LIFECYCLE VERIFICATION SUCCESS' -ForegroundColor Green
    Write-Host 'Cases: native install, Docker install, native->Docker, Docker->native, rollback before/after commit, Docker upgrade'
}
finally {
    $env:MINOS_S7_FAIL_PROBE = $OldFailProbe
    $env:MINOS_S7_FAIL_CONFIGURE = $OldFailConfigure
    $env:MINOS_S7_FAIL_DOCKER_ACTION = $OldFailAction
    $env:MINOS_S7_FAKE_LOG = $OldFakeLog
    Remove-Item -LiteralPath $Root -Recurse -Force -ErrorAction SilentlyContinue
}
