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

function Read-TextLines([string] $Path) {
    return [System.IO.File]::ReadAllLines($Path, [System.Text.Encoding]::UTF8)
}

function Assert-Utf8WithoutBom([string] $Path, [string] $DisplayName) {
    $Bytes = [System.IO.File]::ReadAllBytes($Path)
    $HasUtf8Bom = $Bytes.Length -ge 3 -and
        $Bytes[0] -eq 0xEF -and
        $Bytes[1] -eq 0xBB -and
        $Bytes[2] -eq 0xBF
    Assert-True (-not $HasUtf8Bom) "$DisplayName JSON contains a UTF-8 BOM."
}

function Write-Utf8Json([string] $Path, [object] $Value) {
    $Parent = Split-Path -Parent $Path
    New-Item -ItemType Directory -Force -Path $Parent | Out-Null
    $Json = $Value | ConvertTo-Json -Depth 32
    [System.IO.File]::WriteAllText($Path, $Json + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))
}

function New-FakeMcpCli(
    [string] $Directory,
    [string] $Name,
    [ValidateSet('cmd', 'ps1')]
    [string] $Extension = 'cmd'
) {
    $Path = Join-Path $Directory "$Name.$Extension"
    if ($Extension -eq 'ps1') {
        $Script = @'
$State = Join-Path $PSScriptRoot '__NAME__.state'
if ($PSVersionTable.PSVersion.Major -lt 6) {
    exit 97
}
$NonInteractive = [Environment]::GetCommandLineArgs() -contains '-NonInteractive'
if (-not $NonInteractive) {
    exit 96
}
if ($args.Count -ge 2 -and $args[0] -ieq 'mcp' -and $args[1] -ieq 'get') {
    if (Test-Path -LiteralPath $State -PathType Leaf) {
        [System.IO.File]::ReadAllText($State, [System.Text.Encoding]::ASCII)
        exit 0
    }
    exit 1
}
if ($args.Count -ge 2 -and $args[0] -ieq 'mcp' -and $args[1] -ieq 'add') {
    $Content = 'runner=' + $PSVersionTable.PSVersion.Major +
        ' nonInteractive=' + $NonInteractive + ' ' + ($args -join ' ')
    [System.IO.File]::WriteAllText($State, $Content, [System.Text.Encoding]::ASCII)
    exit 0
}
if ($args.Count -ge 2 -and $args[0] -ieq 'mcp' -and $args[1] -ieq 'remove') {
    Remove-Item -LiteralPath $State -Force -ErrorAction SilentlyContinue
    exit 0
}
exit 2
'@
        $Script = $Script.Replace('__NAME__', $Name)
        [System.IO.File]::WriteAllText($Path, $Script + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))
        return $Path
    }

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

function New-HangingMcpCli([string] $Directory, [string] $Name) {
    $Path = Join-Path $Directory "$Name.ps1"
    $Script = @'
$PidPath = Join-Path $PSScriptRoot '__NAME__.pid'
[System.IO.File]::WriteAllText($PidPath, [string]$PID, [System.Text.Encoding]::ASCII)
Start-Sleep -Seconds 30
exit 0
'@
    $Script = $Script.Replace('__NAME__', $Name)
    [System.IO.File]::WriteAllText($Path, $Script + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))
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
    # The installed Copilot CLI is commonly exposed as copilot.ps1. Running the
    # fake only under PowerShell 6+ proves that the Windows PowerShell 5.1
    # manager routes a .ps1 launcher through pwsh.exe.
    New-FakeMcpCli -Directory $FakeBin -Name 'copilot' -Extension 'ps1' | Out-Null
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
    # ReadAllLines removes CRLF/LF terminators before comparison. Exact line
    # equality therefore enforces the required spaces without making the test
    # depend on the host line-ending convention.
    $CopilotLines = Read-TextLines -Path $CopilotConfig
    $ClaudeDesktopLines = Read-TextLines -Path $ClaudeDesktopConfig
    $StateLines = Read-TextLines -Path $StatePath
    Assert-True ($CopilotLines -ccontains '  "servers": {') 'Copilot JSON is not formatted with two-space indentation.'
    Assert-True ($CopilotLines -ccontains '    "minos": {') 'Copilot MINOS JSON entry indentation is incorrect.'
    Assert-True ($ClaudeDesktopLines -ccontains '  "mcpServers": {') 'Claude Desktop JSON is not formatted with two-space indentation.'
    Assert-True ($ClaudeDesktopLines -ccontains '    "minos": {') 'Claude Desktop MINOS JSON entry indentation is incorrect.'
    Assert-True ($StateLines -ccontains '  "formatVersion": 1,') 'Managed integration state JSON is not formatted with two-space indentation.'
    Assert-True ($StateLines -ccontains '  "clients": [') 'Managed integration state clients indentation is incorrect.'
    Assert-True (-not ($CopilotLines -match '^ {20,}\S')) 'Copilot JSON contains excessive indentation.'
    Assert-True (-not ($ClaudeDesktopLines -match '^ {20,}\S')) 'Claude Desktop JSON contains excessive indentation.'
    Assert-True (-not ($StateLines -match '^ {20,}\S')) 'Managed integration state JSON contains excessive indentation.'
    Assert-Utf8WithoutBom -Path $CopilotConfig -DisplayName 'Copilot'
    Assert-Utf8WithoutBom -Path $ClaudeDesktopConfig -DisplayName 'Claude Desktop'
    Assert-Utf8WithoutBom -Path $StatePath -DisplayName 'Managed integration state'

    $CopilotState = Get-Content -Raw -LiteralPath (Join-Path $FakeBin 'copilot.state')
    $ClaudeState = Get-Content -Raw -LiteralPath (Join-Path $FakeBin 'claude.state')
    $CodexState = Get-Content -Raw -LiteralPath (Join-Path $FakeBin 'codex.state')
    Assert-True ($CopilotState.Contains($ExpectedExe) -and $CopilotState.Contains($DataRoot)) 'Copilot CLI did not receive the MINOS command/environment.'
    Assert-True ($CopilotState -match '^runner=([6-9]|\d{2,}) ') 'Copilot CLI PowerShell launcher was not invoked through pwsh.exe.'
    Assert-True ($CopilotState.Contains('nonInteractive=True')) 'Copilot CLI PowerShell launcher was not invoked non-interactively.'
    Assert-True ($ClaudeState.Contains($ExpectedExe) -and $ClaudeState.Contains($DataRoot)) 'Claude Code did not receive the MINOS command/environment.'
    Assert-True ($ClaudeState -match 'mcp add minos .*--scope user') 'Claude Code server name must precede scope and env options.'
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

    # Preexisting (CLI kind): a compatible entry is already connected through a
    # CLI's own `mcp get` output, with no prior MINOS tracking state at all
    # (the JSON-kind equivalent above proves ownership tracking; this proves
    # the same fail-safe adoption for the probe-based CLI path). It must be
    # adopted without ever invoking `mcp add`/`mcp remove`, and must remain
    # untouched during MINOS uninstall because MINOS did not create it.
    $CliPreexistingState = Join-Path $Root 'cli-preexisting\state.json'
    $CliPreexistingLog = Join-Path $Root 'cli-preexisting\log.txt'
    $CliPreexistingBackups = Join-Path $Root 'cli-preexisting\backups'
    $ClaudeFakeState = Join-Path $FakeBin 'claude.state'
    Assert-True (-not (Test-Path -LiteralPath $ClaudeFakeState)) 'Claude Code CLI fake state leaked from an earlier scenario.'
    "mcp add minos -- $ExpectedExe mcp --scope user -- env MINOS_HOME=$DataRoot" |
        Set-Content -LiteralPath $ClaudeFakeState -Encoding ascii

    & $Manager `
        -InstallRoot $InstallRoot `
        -ClaudeCode `
        -Strict `
        -DataRoot $DataRoot `
        -StatePath $CliPreexistingState `
        -LogPath $CliPreexistingLog `
        -BackupRoot $CliPreexistingBackups `
        -CopilotJetBrainsConfigPath $CopilotConfig `
        -ClaudeDesktopConfigPath $ClaudeDesktopConfig

    $CliPreexistingTracking = Read-Json -Path $CliPreexistingState
    Assert-True (@($CliPreexistingTracking.clients).Count -eq 1) 'Expected exactly one managed entry for the CLI-preexisting scenario.'
    Assert-True ($CliPreexistingTracking.clients[0].ownership -eq 'preexisting') 'Compatible pre-existing Claude Code CLI entry ownership was not tracked safely.'

    & $Manager `
        -InstallRoot $InstallRoot `
        -Action Uninstall `
        -Strict `
        -DataRoot $DataRoot `
        -StatePath $CliPreexistingState `
        -LogPath $CliPreexistingLog `
        -BackupRoot $CliPreexistingBackups `
        -CopilotJetBrainsConfigPath $CopilotConfig `
        -ClaudeDesktopConfigPath $ClaudeDesktopConfig

    Assert-True (Test-Path -LiteralPath $ClaudeFakeState -PathType Leaf) 'Pre-existing compatible Claude Code CLI entry was removed during uninstall.'
    Assert-True (-not (Test-Path -LiteralPath $CliPreexistingState)) 'Pre-existing CLI integration tracking state remained after uninstall.'
    Remove-Item -LiteralPath $ClaudeFakeState -Force

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

    # A hidden setup must not wait forever on an interactive or stalled client.
    # Strict failure after the first client also proves that ownership is saved
    # incrementally instead of being lost before the final aggregate save.
    $TimeoutBin = Join-Path $Root 'timeout\fake-bin'
    $TimeoutConfig = Join-Path $Root 'timeout\copilot\mcp.json'
    $TimeoutStatePath = Join-Path $Root 'timeout\state.json'
    $TimeoutLogPath = Join-Path $Root 'timeout\mcp-clients.log'
    New-Item -ItemType Directory -Force -Path $TimeoutBin | Out-Null
    New-HangingMcpCli -Directory $TimeoutBin -Name 'copilot' | Out-Null
    Write-Utf8Json -Path $TimeoutConfig -Value ([pscustomobject][ordered]@{
        servers = [pscustomobject][ordered]@{
            memory = [pscustomobject][ordered]@{ command = 'npx'; args = @('memory-server') }
        }
        keepMe = 'timeout-value'
    })
    $env:Path = "$TimeoutBin;$OldPath"
    $TimeoutRaised = $false
    $TimeoutMessage = ''
    $TimeoutWatch = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        & $Manager `
            -InstallRoot $InstallRoot `
            -CopilotJetBrains `
            -CopilotCli `
            -Strict `
            -NativeCommandTimeoutSeconds 1 `
            -DataRoot $DataRoot `
            -StatePath $TimeoutStatePath `
            -LogPath $TimeoutLogPath `
            -BackupRoot (Join-Path $Root 'timeout\backups') `
            -CopilotJetBrainsConfigPath $TimeoutConfig `
            -ClaudeDesktopConfigPath $ClaudeDesktopConfig
    }
    catch {
        $TimeoutRaised = $true
        $TimeoutMessage = $_.Exception.Message
    }
    finally {
        $TimeoutWatch.Stop()
    }
    Assert-True $TimeoutRaised 'A stalled Copilot CLI should have caused a strict-mode timeout failure.'
    Assert-True ($TimeoutMessage -match 'timed out after 1 seconds') 'The stalled Copilot CLI failure did not report its timeout.'
    Assert-True ($TimeoutWatch.Elapsed.TotalSeconds -lt 10) 'The stalled Copilot CLI was not bounded by the configured timeout.'

    $TimeoutPidPath = Join-Path $TimeoutBin 'copilot.pid'
    Assert-True (Test-Path -LiteralPath $TimeoutPidPath -PathType Leaf) 'The stalled Copilot CLI test did not start.'
    $TimeoutProcessId = [int](Get-Content -Raw -LiteralPath $TimeoutPidPath)
    Start-Sleep -Milliseconds 100
    Assert-True ($null -eq (Get-Process -Id $TimeoutProcessId -ErrorAction SilentlyContinue)) 'The timed-out Copilot CLI process was left running.'

    $TimeoutState = Read-Json -Path $TimeoutStatePath
    $TimeoutValue = Read-Json -Path $TimeoutConfig
    Assert-True (@($TimeoutState.clients).Count -eq 1) 'Completed MCP client ownership was lost after a later strict failure.'
    Assert-True ($TimeoutState.clients[0].id -eq 'copilot-jetbrains') 'The wrong partial MCP client state was persisted.'
    Assert-True ($TimeoutState.clients[0].ownership -eq 'managed') 'Partial MCP client ownership was not persisted as managed.'
    Assert-True ($null -ne $TimeoutValue.servers.memory) 'Timeout handling removed a pre-existing MCP server.'
    Assert-True ($TimeoutValue.keepMe -eq 'timeout-value') 'Timeout handling changed an unrelated JSON property.'

    # Fail-closed on invalid JSON: a malformed config for one client must
    # never be silently treated as empty, must never be overwritten, and --
    # in non-strict mode -- must not block configuring a sibling client whose
    # own config is valid.
    $InvalidJsonConfig = Join-Path $Root 'invalid-json\copilot\mcp.json'
    $InvalidJsonSiblingConfig = Join-Path $Root 'invalid-json\claude-desktop\claude_desktop_config.json'
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $InvalidJsonConfig) | Out-Null
    '{not valid json,,,' | Set-Content -LiteralPath $InvalidJsonConfig -Encoding utf8 -NoNewline
    $InvalidJsonRawBefore = [System.IO.File]::ReadAllText($InvalidJsonConfig, [System.Text.Encoding]::UTF8)
    Write-Utf8Json -Path $InvalidJsonSiblingConfig -Value ([pscustomobject][ordered]@{ mcpServers = [pscustomobject][ordered]@{} })

    & $Manager `
        -InstallRoot $InstallRoot `
        -CopilotJetBrains `
        -ClaudeDesktop `
        -DataRoot $DataRoot `
        -StatePath (Join-Path $Root 'invalid-json\state.json') `
        -LogPath (Join-Path $Root 'invalid-json\log.txt') `
        -BackupRoot (Join-Path $Root 'invalid-json\backups') `
        -CopilotJetBrainsConfigPath $InvalidJsonConfig `
        -ClaudeDesktopConfigPath $InvalidJsonSiblingConfig

    Assert-True ([System.IO.File]::ReadAllText($InvalidJsonConfig, [System.Text.Encoding]::UTF8) -eq $InvalidJsonRawBefore) 'Invalid JSON configuration was overwritten instead of being left untouched.'
    $InvalidJsonSiblingAfter = Read-Json -Path $InvalidJsonSiblingConfig
    Assert-True ($null -ne $InvalidJsonSiblingAfter.mcpServers.minos) 'A sibling client with valid JSON was not configured after another selected client had invalid JSON (non-strict mode must not abort the whole run).'

    $InvalidJsonRaised = $false
    try {
        & $Manager `
            -InstallRoot $InstallRoot `
            -CopilotJetBrains `
            -Strict `
            -DataRoot $DataRoot `
            -StatePath (Join-Path $Root 'invalid-json\strict-state.json') `
            -LogPath (Join-Path $Root 'invalid-json\strict-log.txt') `
            -BackupRoot (Join-Path $Root 'invalid-json\strict-backups') `
            -CopilotJetBrainsConfigPath $InvalidJsonConfig `
            -ClaudeDesktopConfigPath $ClaudeDesktopConfig
    }
    catch {
        $InvalidJsonRaised = $true
        Assert-True ($_.Exception.Message -match 'Invalid JSON configuration') 'Strict-mode invalid JSON failure did not report the expected diagnostic.'
    }
    Assert-True $InvalidJsonRaised 'Invalid JSON configuration should have caused a strict-mode failure.'
    Assert-True ([System.IO.File]::ReadAllText($InvalidJsonConfig, [System.Text.Encoding]::UTF8) -eq $InvalidJsonRawBefore) 'Invalid JSON configuration was overwritten by the strict-mode run.'

    # Modification safety (JSON kind): an entry MINOS created and manages must
    # be preserved -- not silently removed -- if the user edits it before
    # uninstall. Already proven for the CLI kind above; this is the JSON-kind
    # equivalent (Test-ManagedJsonEntryMatches mismatch handling).
    $ModifiedJsonConfig = Join-Path $Root 'modified-json\copilot\mcp.json'
    $ModifiedJsonState = Join-Path $Root 'modified-json\state.json'
    $ModifiedJsonLog = Join-Path $Root 'modified-json\log.txt'
    & $Manager `
        -InstallRoot $InstallRoot `
        -CopilotJetBrains `
        -Strict `
        -DataRoot $DataRoot `
        -StatePath $ModifiedJsonState `
        -LogPath $ModifiedJsonLog `
        -BackupRoot (Join-Path $Root 'modified-json\backups') `
        -CopilotJetBrainsConfigPath $ModifiedJsonConfig `
        -ClaudeDesktopConfigPath $ClaudeDesktopConfig

    $ModifiedJsonValue = Read-Json -Path $ModifiedJsonConfig
    $ModifiedJsonValue.servers.minos.command = 'C:\Other\minos.exe'
    Write-Utf8Json -Path $ModifiedJsonConfig -Value $ModifiedJsonValue

    $ModifiedJsonPreserveRaised = $false
    try {
        & $Manager `
            -InstallRoot $InstallRoot `
            -Action Uninstall `
            -Strict `
            -DataRoot $DataRoot `
            -StatePath $ModifiedJsonState `
            -LogPath $ModifiedJsonLog `
            -BackupRoot (Join-Path $Root 'modified-json\backups') `
            -CopilotJetBrainsConfigPath $ModifiedJsonConfig `
            -ClaudeDesktopConfigPath $ClaudeDesktopConfig
    }
    catch {
        $ModifiedJsonPreserveRaised = $true
    }
    Assert-True $ModifiedJsonPreserveRaised 'Modified managed JSON entry should have been preserved with a strict-mode warning.'
    $ModifiedJsonAfter = Read-Json -Path $ModifiedJsonConfig
    Assert-True ($ModifiedJsonAfter.servers.minos.command -eq 'C:\Other\minos.exe') 'Modified managed JSON entry was overwritten/removed instead of being preserved.'

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
