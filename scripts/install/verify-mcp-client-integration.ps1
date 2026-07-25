[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($env:OS -ne 'Windows_NT') {
    throw 'MINOS MCP client integration verification must run on Windows.'
}

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$Manager = Join-Path $RepoRoot 'scripts\install\configure-mcp-clients.ps1'
if (-not (Test-Path -LiteralPath $Manager -PathType Leaf)) {
    throw "MCP client integration manager not found: $Manager"
}

function Assert-True([bool] $Condition, [string] $Message) {
    if (-not $Condition) {
        throw $Message
    }
}

function Read-Json([string] $Path) {
    return Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
}

function Write-Utf8Json([string] $Path, [object] $Value) {
    $Parent = Split-Path -Parent $Path
    New-Item -ItemType Directory -Force -Path $Parent | Out-Null
    $Json = $Value | ConvertTo-Json -Depth 32
    [System.IO.File]::WriteAllText($Path, $Json + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))
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

$Root = Join-Path ([System.IO.Path]::GetTempPath()) ("minos-mcp-clients-" + [Guid]::NewGuid())
$OldPath = $env:Path
try {
    $InstallRoot = Join-Path $Root 'MINOS install with spaces'
    $AppRoot = Join-Path $InstallRoot 'app'
    $FakeBin = Join-Path $Root 'fake-bin'
    $DataRoot = Join-Path $Root 'data'
    $StatePath = Join-Path $Root 'state\mcp-client-integrations.json'
    $LogPath = Join-Path $Root 'logs\mcp-clients.log'
    $BackupRoot = Join-Path $Root 'backups'
    $CopilotConfig = Join-Path $Root 'copilot\mcp.json'
    $ClaudeDesktopConfig = Join-Path $Root 'claude-desktop\claude_desktop_config.json'

    New-Item -ItemType Directory -Force -Path $AppRoot, $FakeBin, $DataRoot | Out-Null
    New-Item -ItemType File -Force -Path (Join-Path $AppRoot 'minos.exe') | Out-Null
    New-FakeMcpCli -Directory $FakeBin -Name 'copilot' | Out-Null
    New-FakeMcpCli -Directory $FakeBin -Name 'claude' | Out-Null
    New-FakeMcpCli -Directory $FakeBin -Name 'codex' | Out-Null
    $env:Path = "$FakeBin;$OldPath"

    Write-Utf8Json -Path $CopilotConfig -Value ([pscustomobject][ordered]@{
        servers = [pscustomobject][ordered]@{
            memory = [pscustomobject][ordered]@{ command = 'npx'; args = @('memory-server') }
        }
        keepMe = 'copilot-value'
    })
    Write-Utf8Json -Path $ClaudeDesktopConfig -Value ([pscustomobject][ordered]@{
        mcpServers = [pscustomobject][ordered]@{
            filesystem = [pscustomobject][ordered]@{ command = 'npx'; args = @('filesystem-server') }
        }
        keepMe = 'claude-value'
    })

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

    $Copilot = Read-Json -Path $CopilotConfig
    $ClaudeDesktopValue = Read-Json -Path $ClaudeDesktopConfig
    $State = Read-Json -Path $StatePath
    $ExpectedExe = Join-Path $AppRoot 'minos.exe'

    Assert-True ($Copilot.keepMe -eq 'copilot-value') 'Copilot unrelated root properties were not preserved.'
    Assert-True ($null -ne $Copilot.servers.memory) 'Copilot pre-existing MCP server was not preserved.'
    Assert-True ($Copilot.servers.minos.command -eq $ExpectedExe) 'Copilot MINOS command is incorrect.'
    Assert-True ($Copilot.servers.minos.args[0] -eq 'mcp') 'Copilot MINOS args are incorrect.'
    Assert-True ($Copilot.servers.minos.env.MINOS_HOME -eq $DataRoot) 'Copilot MINOS_HOME is incorrect.'

    Assert-True ($ClaudeDesktopValue.keepMe -eq 'claude-value') 'Claude Desktop unrelated root properties were not preserved.'
    Assert-True ($null -ne $ClaudeDesktopValue.mcpServers.filesystem) 'Claude Desktop pre-existing MCP server was not preserved.'
    Assert-True ($ClaudeDesktopValue.mcpServers.minos.command -eq $ExpectedExe) 'Claude Desktop MINOS command is incorrect.'
    Assert-True (@($State.clients).Count -eq 5) 'Expected five managed MCP client integrations.'

    # JSON written by Windows PowerShell 5.1 must remain human-readable. The
    # previous implementation inherited ConvertTo-Json's excessive indentation
    # when serializing parsed PSCustomObjects.
    $CopilotRaw = [System.IO.File]::ReadAllText($CopilotConfig, [System.Text.Encoding]::UTF8)
    $ClaudeDesktopRaw = [System.IO.File]::ReadAllText($ClaudeDesktopConfig, [System.Text.Encoding]::UTF8)
    Assert-True ($CopilotRaw -match '(?m)^  "servers": \{$') 'Copilot JSON is not formatted with two-space indentation.'
    Assert-True ($CopilotRaw -match '(?m)^    "minos": \{$') 'Copilot MINOS JSON entry indentation is incorrect.'
    Assert-True ($ClaudeDesktopRaw -match '(?m)^  "mcpServers": \{$') 'Claude Desktop JSON is not formatted with two-space indentation.'
    Assert-True ($ClaudeDesktopRaw -match '(?m)^    "minos": \{$') 'Claude Desktop MINOS JSON entry indentation is incorrect.'
    Assert-True (-not ($CopilotRaw -match '(?m)^ {20,}\S')) 'Copilot JSON contains excessive indentation.'
    Assert-True (-not ($ClaudeDesktopRaw -match '(?m)^ {20,}\S')) 'Claude Desktop JSON contains excessive indentation.'

    $CopilotState = Get-Content -Raw -LiteralPath (Join-Path $FakeBin 'copilot.state')
    $ClaudeState = Get-Content -Raw -LiteralPath (Join-Path $FakeBin 'claude.state')
    $CodexState = Get-Content -Raw -LiteralPath (Join-Path $FakeBin 'codex.state')
    Assert-True ($CopilotState.Contains($ExpectedExe) -and $CopilotState.Contains($DataRoot)) 'Copilot CLI did not receive the MINOS command/environment.'
    Assert-True ($ClaudeState.Contains($ExpectedExe) -and $ClaudeState.Contains($DataRoot)) 'Claude Code did not receive the MINOS command/environment.'
    Assert-True ($ClaudeState -match 'mcp add --scope user --env .* minos -- ') 'Claude Code options are not placed before the server name.'
    Assert-True ($CodexState.Contains($ExpectedExe) -and $CodexState.Contains($DataRoot)) 'Codex did not receive the MINOS command/environment.'
    Assert-True ((Get-ChildItem -LiteralPath $BackupRoot -File -Recurse).Count -ge 2) 'Expected JSON configuration backups.'

    # Re-running installation with the same selection must be idempotent: no
    # duplicate MINOS server, no rewrite and no additional JSON backup.
    $CopilotBeforeSecondInstall = [System.IO.File]::ReadAllText($CopilotConfig, [System.Text.Encoding]::UTF8)
    $ClaudeBeforeSecondInstall = [System.IO.File]::ReadAllText($ClaudeDesktopConfig, [System.Text.Encoding]::UTF8)
    $BackupCountBeforeSecondInstall = (Get-ChildItem -LiteralPath $BackupRoot -File -Recurse).Count

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

    $CopilotAfterSecondInstall = Read-Json -Path $CopilotConfig
    $ClaudeAfterSecondInstall = Read-Json -Path $ClaudeDesktopConfig
    Assert-True (@($CopilotAfterSecondInstall.servers.PSObject.Properties | Where-Object { $_.Name -eq 'minos' }).Count -eq 1) 'Copilot contains duplicate MINOS entries after reinstall.'
    Assert-True (@($ClaudeAfterSecondInstall.mcpServers.PSObject.Properties | Where-Object { $_.Name -eq 'minos' }).Count -eq 1) 'Claude Desktop contains duplicate MINOS entries after reinstall.'
    Assert-True ([System.IO.File]::ReadAllText($CopilotConfig, [System.Text.Encoding]::UTF8) -eq $CopilotBeforeSecondInstall) 'Copilot configuration was unnecessarily rewritten on idempotent reinstall.'
    Assert-True ([System.IO.File]::ReadAllText($ClaudeDesktopConfig, [System.Text.Encoding]::UTF8) -eq $ClaudeBeforeSecondInstall) 'Claude Desktop configuration was unnecessarily rewritten on idempotent reinstall.'
    Assert-True ((Get-ChildItem -LiteralPath $BackupRoot -File -Recurse).Count -eq $BackupCountBeforeSecondInstall) 'Idempotent reinstall created unnecessary JSON backups.'

    & $Manager `
        -InstallRoot $InstallRoot `
        -Action Uninstall `
        -Strict `
        -DataRoot $DataRoot `
        -StatePath $StatePath `
        -LogPath $LogPath `
        -BackupRoot $BackupRoot `
        -CopilotJetBrainsConfigPath $CopilotConfig `
        -ClaudeDesktopConfigPath $ClaudeDesktopConfig

    $CopilotAfter = Read-Json -Path $CopilotConfig
    $ClaudeAfter = Read-Json -Path $ClaudeDesktopConfig
    Assert-True ($null -eq $CopilotAfter.servers.PSObject.Properties['minos']) 'Copilot MINOS entry remained after uninstall.'
    Assert-True ($null -ne $CopilotAfter.servers.memory) 'Copilot pre-existing MCP server was removed.'
    Assert-True ($CopilotAfter.keepMe -eq 'copilot-value') 'Copilot unrelated property changed on uninstall.'
    Assert-True ($null -eq $ClaudeAfter.mcpServers.PSObject.Properties['minos']) 'Claude Desktop MINOS entry remained after uninstall.'
    Assert-True ($null -ne $ClaudeAfter.mcpServers.filesystem) 'Claude Desktop pre-existing MCP server was removed.'
    Assert-True ($ClaudeAfter.keepMe -eq 'claude-value') 'Claude Desktop unrelated property changed on uninstall.'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $FakeBin 'copilot.state'))) 'Copilot CLI entry remained after uninstall.'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $FakeBin 'claude.state'))) 'Claude Code entry remained after uninstall.'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $FakeBin 'codex.state'))) 'Codex entry remained after uninstall.'
    Assert-True (-not (Test-Path -LiteralPath $StatePath)) 'Managed integration state remained after clean uninstall.'

    # A compatible pre-existing MINOS entry is already connected. It must be
    # adopted as a successful selection without creating a second entry, and it
    # must remain untouched during MINOS uninstall because MINOS did not create it.
    $PreexistingConfig = Join-Path $Root 'preexisting\mcp.json'
    $PreexistingState = Join-Path $Root 'preexisting\state.json'
    Write-Utf8Json -Path $PreexistingConfig -Value ([pscustomobject][ordered]@{
        servers = [pscustomobject][ordered]@{
            minos = [pscustomobject][ordered]@{
                command = $ExpectedExe
                args = @('mcp')
                env = [pscustomobject][ordered]@{ MINOS_HOME = $DataRoot }
            }
            memory = [pscustomobject][ordered]@{ command = 'npx'; args = @('memory-server') }
        }
    })
    $PreexistingRawBefore = [System.IO.File]::ReadAllText($PreexistingConfig, [System.Text.Encoding]::UTF8)

    & $Manager `
        -InstallRoot $InstallRoot `
        -CopilotJetBrains `
        -Strict `
        -DataRoot $DataRoot `
        -StatePath $PreexistingState `
        -LogPath (Join-Path $Root 'preexisting\log.txt') `
        -BackupRoot (Join-Path $Root 'preexisting\backups') `
        -CopilotJetBrainsConfigPath $PreexistingConfig `
        -ClaudeDesktopConfigPath $ClaudeDesktopConfig

    $PreexistingValue = Read-Json -Path $PreexistingConfig
    $PreexistingTracking = Read-Json -Path $PreexistingState
    Assert-True (@($PreexistingValue.servers.PSObject.Properties | Where-Object { $_.Name -eq 'minos' }).Count -eq 1) 'Compatible pre-existing MINOS entry was duplicated.'
    Assert-True ($PreexistingTracking.clients[0].ownership -eq 'preexisting') 'Compatible pre-existing MINOS entry ownership was not tracked safely.'
    Assert-True ([System.IO.File]::ReadAllText($PreexistingConfig, [System.Text.Encoding]::UTF8) -eq $PreexistingRawBefore) 'Compatible pre-existing MINOS configuration was unnecessarily rewritten.'

    & $Manager `
        -InstallRoot $InstallRoot `
        -Action Uninstall `
        -Strict `
        -DataRoot $DataRoot `
        -StatePath $PreexistingState `
        -LogPath (Join-Path $Root 'preexisting\log.txt') `
        -BackupRoot (Join-Path $Root 'preexisting\backups') `
        -CopilotJetBrainsConfigPath $PreexistingConfig `
        -ClaudeDesktopConfigPath $ClaudeDesktopConfig

    $PreexistingAfterUninstall = Read-Json -Path $PreexistingConfig
    Assert-True ($null -ne $PreexistingAfterUninstall.servers.minos) 'Pre-existing compatible MINOS entry was removed during uninstall.'
    Assert-True (-not (Test-Path -LiteralPath $PreexistingState)) 'Pre-existing integration tracking state remained after uninstall.'

    # Collision safety: an unmanaged existing JSON `minos` entry with a
    # different target must never be overwritten.
    $CollisionConfig = Join-Path $Root 'collision\mcp.json'
    $CollisionState = Join-Path $Root 'collision\state.json'
    Write-Utf8Json -Path $CollisionConfig -Value ([pscustomobject][ordered]@{
        servers = [pscustomobject][ordered]@{
            minos = [pscustomobject][ordered]@{ command = 'C:\Other\minos.exe'; args = @('mcp') }
        }
    })
    $CollisionRaised = $false
    try {
        & $Manager `
            -InstallRoot $InstallRoot `
            -CopilotJetBrains `
            -Strict `
            -DataRoot $DataRoot `
            -StatePath $CollisionState `
            -LogPath (Join-Path $Root 'collision\log.txt') `
            -BackupRoot (Join-Path $Root 'collision\backups') `
            -CopilotJetBrainsConfigPath $CollisionConfig `
            -ClaudeDesktopConfigPath $ClaudeDesktopConfig
    }
    catch {
        $CollisionRaised = $true
    }
    Assert-True $CollisionRaised 'Existing unmanaged MINOS MCP entry should have caused a strict-mode failure.'
    $CollisionAfter = Read-Json -Path $CollisionConfig
    Assert-True ($CollisionAfter.servers.minos.command -eq 'C:\Other\minos.exe') 'Existing unmanaged MINOS MCP entry was overwritten.'

    # Modification safety: an entry originally managed through a CLI must be
    # preserved if the user changes its command/environment before uninstall.
    $ModifiedStatePath = Join-Path $Root 'modified\state.json'
    $ModifiedLogPath = Join-Path $Root 'modified\log.txt'
    & $Manager `
        -InstallRoot $InstallRoot `
        -Codex `
        -Strict `
        -DataRoot $DataRoot `
        -StatePath $ModifiedStatePath `
        -LogPath $ModifiedLogPath `
        -BackupRoot (Join-Path $Root 'modified\backups') `
        -CopilotJetBrainsConfigPath $CopilotConfig `
        -ClaudeDesktopConfigPath $ClaudeDesktopConfig
    $CodexFakeState = Join-Path $FakeBin 'codex.state'
    Set-Content -LiteralPath $CodexFakeState -Value 'mcp add minos -- C:\Other\minos.exe mcp MINOS_HOME=C:\Other\data' -Encoding ascii

    $PreserveRaised = $false
    try {
        & $Manager `
            -InstallRoot $InstallRoot `
            -Action Uninstall `
            -Strict `
            -DataRoot $DataRoot `
            -StatePath $ModifiedStatePath `
            -LogPath $ModifiedLogPath `
            -BackupRoot (Join-Path $Root 'modified\backups') `
            -CopilotJetBrainsConfigPath $CopilotConfig `
            -ClaudeDesktopConfigPath $ClaudeDesktopConfig
    }
    catch {
        $PreserveRaised = $true
    }
    Assert-True $PreserveRaised 'Modified managed CLI entry should have been preserved with a strict-mode warning.'
    Assert-True (Test-Path -LiteralPath $CodexFakeState) 'Modified managed CLI entry was removed.'

    Write-Host ''
    Write-Host 'MINOS MCP CLIENT INTEGRATION VERIFICATION SUCCESS' -ForegroundColor Green
    Write-Host 'Clients : Copilot JetBrains, Copilot CLI, Claude Code, Claude Desktop, Codex'
    Write-Host 'JSON    : two-space indentation; idempotent install; no duplicate MINOS entries'
    Write-Host 'Safety  : existing/modified entries preserved; unchanged managed entries removed selectively'
}
finally {
    $env:Path = $OldPath
    Remove-Item -LiteralPath $Root -Recurse -Force -ErrorAction SilentlyContinue
}
