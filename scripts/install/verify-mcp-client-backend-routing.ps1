[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($env:OS -ne 'Windows_NT') {
    throw 'MINOS MCP backend-agnostic client verification must run on Windows.'
}

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$Manager = Join-Path $RepoRoot 'scripts\install\configure-mcp-clients.ps1'
$CodexManager = Join-Path $RepoRoot 'scripts\install\configure-codex-mcp.ps1'
foreach ($Required in @($Manager, $CodexManager)) {
    if (-not (Test-Path -LiteralPath $Required -PathType Leaf)) {
        throw "MCP integration helper not found: $Required"
    }
}

function Assert-True([bool] $Condition, [string] $Message) {
    if (-not $Condition) { throw $Message }
}

function Write-Utf8Json([string] $Path, [object] $Value) {
    $Parent = Split-Path -Parent $Path
    New-Item -ItemType Directory -Force -Path $Parent | Out-Null
    [System.IO.File]::WriteAllText(
        $Path,
        (($Value | ConvertTo-Json -Depth 16) + [Environment]::NewLine),
        [System.Text.UTF8Encoding]::new($false))
}

function New-FakeMcpCli([string] $Directory, [string] $Name) {
    $Path = Join-Path $Directory "$Name.cmd"
    @"
@echo off
setlocal
set "STATE=%~dp0$Name.state"
if /I "%~1"=="mcp" if /I "%~2"=="get" (
  if exist "%STATE%" (
    type "%STATE%"
    exit /b 0
  )
  exit /b 1
)
if /I "%~1"=="mcp" if /I "%~2"=="add" (
  >"%STATE%" echo %*
  exit /b 0
)
if /I "%~1"=="mcp" if /I "%~2"=="remove" (
  if exist "%STATE%" del /q "%STATE%"
  exit /b 0
)
exit /b 2
"@ | Set-Content -LiteralPath $Path -Encoding ascii
    return $Path
}

function Get-Sha256([string] $Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Write-BackendConfiguration([string] $DataRoot, [ValidateSet('native', 'docker')] [string] $Backend) {
    $Runtime = Join-Path $DataRoot 'runtime'
    New-Item -ItemType Directory -Force -Path $Runtime | Out-Null
    $Path = Join-Path $Runtime 'backend.properties'
    $Lines = @(
        '# MINOS MCP backend configuration v1',
        'formatVersion=1',
        "backend=$Backend",
        'docker.containerName=minos-mcp-prod',
        'docker.probeTimeoutMillis=10000'
    )
    [System.IO.File]::WriteAllLines($Path, $Lines, [System.Text.UTF8Encoding]::new($false))
    return $Path
}

$Root = Join-Path ([System.IO.Path]::GetTempPath()) ('minos-m29-s6-clients-' + [Guid]::NewGuid())
$OldPath = $env:Path
try {
    $InstallRoot = Join-Path $Root 'MINOS install with spaces'
    $AppRoot = Join-Path $InstallRoot 'app'
    $FakeBin = Join-Path $Root 'fake-bin'
    $DataRoot = Join-Path $Root 'data'
    $StatePath = Join-Path $Root 'state\mcp-client-integrations.json'
    $LogPath = Join-Path $Root 'logs\mcp-clients.log'
    $BackupRoot = Join-Path $Root 'backups'
    $CopilotConfig = Join-Path $Root 'copilot-jetbrains\mcp.json'
    $ClaudeDesktopConfig = Join-Path $Root 'claude-desktop\claude_desktop_config.json'
    $CodexDesktopConfig = Join-Path $Root 'codex-desktop\config.toml'
    $CodexDesktopState = Join-Path $Root 'state\codex-desktop.json'

    New-Item -ItemType Directory -Force -Path $AppRoot, $FakeBin, $DataRoot | Out-Null
    New-Item -ItemType File -Force -Path (Join-Path $AppRoot 'minos.exe') | Out-Null
    foreach ($Name in @('copilot', 'claude', 'codex')) {
        New-FakeMcpCli -Directory $FakeBin -Name $Name | Out-Null
    }
    $env:Path = "$FakeBin;$OldPath"

    Write-Utf8Json -Path $CopilotConfig -Value ([pscustomobject][ordered]@{ servers = [pscustomobject]@{} })
    Write-Utf8Json -Path $ClaudeDesktopConfig -Value ([pscustomobject][ordered]@{ mcpServers = [pscustomobject]@{} })
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $CodexDesktopConfig) | Out-Null
    [System.IO.File]::WriteAllText($CodexDesktopConfig, "model = `"gpt-5.6`"`r`n", [System.Text.UTF8Encoding]::new($false))

    & $Manager `
        -InstallRoot $InstallRoot `
        -CopilotJetBrains `
        -CopilotCli `
        -ClaudeCode `
        -ClaudeDesktop `
        -Codex `
        -Strict `
        -DataRoot $DataRoot `
        -StatePath $StatePath `
        -LogPath $LogPath `
        -BackupRoot $BackupRoot `
        -CopilotJetBrainsConfigPath $CopilotConfig `
        -ClaudeDesktopConfigPath $ClaudeDesktopConfig

    & $CodexManager `
        -InstallRoot $InstallRoot `
        -Action Install `
        -Mode desktop `
        -Strict `
        -DataRoot $DataRoot `
        -ConfigPath $CodexDesktopConfig `
        -StatePath $CodexDesktopState `
        -LogPath $LogPath `
        -BackupRoot $BackupRoot

    $ExpectedExe = Join-Path $AppRoot 'minos.exe'
    $Copilot = Get-Content -Raw -LiteralPath $CopilotConfig | ConvertFrom-Json
    $ClaudeDesktop = Get-Content -Raw -LiteralPath $ClaudeDesktopConfig | ConvertFrom-Json
    Assert-True ([string]$Copilot.servers.minos.command -eq $ExpectedExe) 'Copilot JetBrains does not target the stable MINOS launcher.'
    Assert-True ([string]$Copilot.servers.minos.args[0] -eq 'mcp') 'Copilot JetBrains does not use the stable mcp subcommand.'
    Assert-True ([string]$Copilot.servers.minos.env.MINOS_HOME -eq $DataRoot) 'Copilot JetBrains MINOS_HOME is not the backend-selection home.'
    Assert-True ([string]$ClaudeDesktop.mcpServers.minos.command -eq $ExpectedExe) 'Claude Desktop does not target the stable MINOS launcher.'
    Assert-True ([string]$ClaudeDesktop.mcpServers.minos.args[0] -eq 'mcp') 'Claude Desktop does not use the stable mcp subcommand.'
    Assert-True ([string]$ClaudeDesktop.mcpServers.minos.env.MINOS_HOME -eq $DataRoot) 'Claude Desktop MINOS_HOME is not the backend-selection home.'

    foreach ($Name in @('copilot', 'claude', 'codex')) {
        $State = Join-Path $FakeBin "$Name.state"
        Assert-True (Test-Path -LiteralPath $State -PathType Leaf) "$Name CLI integration did not create a managed server."
        $Text = [System.IO.File]::ReadAllText($State, [System.Text.Encoding]::ASCII)
        Assert-True ($Text.IndexOf($ExpectedExe, [StringComparison]::OrdinalIgnoreCase) -ge 0) "$Name CLI does not target minos.exe."
        Assert-True ($Text.IndexOf($DataRoot, [StringComparison]::OrdinalIgnoreCase) -ge 0) "$Name CLI does not preserve MINOS_HOME."
        Assert-True ($Text -match '(?i)\bminos\b.*\bmcp\b') "$Name CLI does not use the mcp subcommand."
    }

    $CodexText = [System.IO.File]::ReadAllText($CodexDesktopConfig, [System.Text.Encoding]::UTF8)
    Assert-True ($CodexText.Contains('[mcp_servers.minos]')) 'Codex Desktop MINOS section is missing.'
    Assert-True ($CodexText.IndexOf($ExpectedExe, [StringComparison]::OrdinalIgnoreCase) -ge 0) 'Codex Desktop does not target minos.exe.'
    Assert-True ($CodexText.Contains('args = ["mcp"]')) 'Codex Desktop does not use the stable mcp subcommand.'
    Assert-True ($CodexText.IndexOf($DataRoot, [StringComparison]::OrdinalIgnoreCase) -ge 0) 'Codex Desktop does not preserve MINOS_HOME.'

    $ClientArtifacts = @(
        $CopilotConfig,
        $ClaudeDesktopConfig,
        (Join-Path $FakeBin 'copilot.state'),
        (Join-Path $FakeBin 'claude.state'),
        (Join-Path $FakeBin 'codex.state'),
        $CodexDesktopConfig
    )
    foreach ($Artifact in $ClientArtifacts) {
        $Text = [System.IO.File]::ReadAllText($Artifact)
        Assert-True ($Text.IndexOf('docker exec', [StringComparison]::OrdinalIgnoreCase) -lt 0) "Client config leaked Docker transport: $Artifact"
        Assert-True ($Text.IndexOf('minos-mcp-prod', [StringComparison]::OrdinalIgnoreCase) -lt 0) "Client config leaked Docker container identity: $Artifact"
    }

    $NativeConfig = Write-BackendConfiguration -DataRoot $DataRoot -Backend native
    Assert-True ((Get-Content -Raw -LiteralPath $NativeConfig) -match '(?m)^backend=native$') 'Native backend config was not written under MINOS_HOME.'
    $Before = @{}
    foreach ($Artifact in $ClientArtifacts) { $Before[$Artifact] = Get-Sha256 $Artifact }

    $DockerConfig = Write-BackendConfiguration -DataRoot $DataRoot -Backend docker
    Assert-True ($DockerConfig -eq $NativeConfig) 'Backend switch changed the configuration location.'
    Assert-True ((Get-Content -Raw -LiteralPath $DockerConfig) -match '(?m)^backend=docker$') 'Docker backend config was not written under MINOS_HOME.'
    foreach ($Artifact in $ClientArtifacts) {
        Assert-True ((Get-Sha256 $Artifact) -eq $Before[$Artifact]) "Backend switch rewrote client configuration: $Artifact"
    }

    $ManagerSource = [System.IO.File]::ReadAllText($Manager, [System.Text.Encoding]::UTF8)
    $CodexSource = [System.IO.File]::ReadAllText($CodexManager, [System.Text.Encoding]::UTF8)
    Assert-True ($ManagerSource.Contains("args = @('mcp')")) 'JSON client contract no longer targets minos.exe mcp.'
    Assert-True ($ManagerSource.IndexOf('docker exec', [StringComparison]::OrdinalIgnoreCase) -lt 0) 'Generic MCP client manager contains Docker transport logic.'
    Assert-True ($CodexSource.Contains('args = ["mcp"]')) 'Codex Desktop contract no longer targets minos.exe mcp.'
    Assert-True ($CodexSource.IndexOf('docker exec', [StringComparison]::OrdinalIgnoreCase) -lt 0) 'Codex MCP manager contains Docker transport logic.'

    Write-Host 'MINOS MCP BACKEND-AGNOSTIC CLIENT ROUTING VERIFICATION SUCCESS' -ForegroundColor Green
    Write-Host 'Clients : Copilot JetBrains, Copilot CLI, Claude Code, Claude Desktop, Codex CLI/Desktop'
    Write-Host 'Entry   : minos.exe mcp + MINOS_HOME'
    Write-Host 'Switch  : native -> docker changes backend.properties only; client configs remain byte-identical'
}
finally {
    $env:Path = $OldPath
    Remove-Item -LiteralPath $Root -Recurse -Force -ErrorAction SilentlyContinue
}
