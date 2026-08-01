[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $InstallRoot,

    [switch] $CopilotJetBrains,
    [switch] $CopilotCli,
    [switch] $ClaudeCode,
    [switch] $ClaudeDesktop,
    [switch] $Codex
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$Manager = Join-Path $PSScriptRoot 'configure-mcp-clients.ps1'
$CodexManager = Join-Path $PSScriptRoot 'configure-codex-mcp.ps1'
foreach ($Required in @($Manager, $CodexManager)) {
    if (-not (Test-Path -LiteralPath $Required -PathType Leaf)) {
        throw "MINOS MCP client integration helper not found: $Required"
    }
}

$Parameters = @{ InstallRoot = $InstallRoot }
if ($CopilotJetBrains) { $Parameters['CopilotJetBrains'] = $true }
if ($CopilotCli) { $Parameters['CopilotCli'] = $true }
if ($ClaudeCode) { $Parameters['ClaudeCode'] = $true }
if ($ClaudeDesktop) { $Parameters['ClaudeDesktop'] = $true }

$ManagerSelected = $CopilotJetBrains -or $CopilotCli -or $ClaudeCode -or $ClaudeDesktop
if ($ManagerSelected) {
    & $Manager @Parameters
}
if ($Codex) {
    & $CodexManager -InstallRoot $InstallRoot -Action Install -Strict
}

$LocalAppData = [Environment]::GetFolderPath('LocalApplicationData')
$StatePath = Join-Path $LocalAppData 'MINOS\mcp-client-integrations.json'
$ManagedIds = @()
if (Test-Path -LiteralPath $StatePath -PathType Leaf) {
    try {
        $State = Get-Content -Raw -LiteralPath $StatePath | ConvertFrom-Json
        $ManagedIds = @($State.clients | ForEach-Object { [string]$_.id })
    }
    catch {
        throw "MINOS MCP integration state could not be verified: $($_.Exception.Message)"
    }
}

$Selected = @()
if ($CopilotJetBrains) { $Selected += 'copilot-jetbrains' }
if ($CopilotCli) { $Selected += 'copilot-cli' }
if ($ClaudeCode) { $Selected += 'claude-code' }
if ($ClaudeDesktop) { $Selected += 'claude-desktop' }
$Missing = @($Selected | Where-Object { $ManagedIds -notcontains $_ })
if ($Missing.Count -gt 0) {
    throw "One or more selected MINOS MCP client integrations were not configured: $($Missing -join ', '). See %LOCALAPPDATA%\MINOS\mcp-clients.log."
}

if ($Codex) {
    $CodexState = Join-Path $LocalAppData 'MINOS\codex-mcp-integration.json'
    if (-not (Test-Path -LiteralPath $CodexState -PathType Leaf)) {
        throw 'OpenAI Codex was selected but MINOS could not verify its managed integration state. See %LOCALAPPDATA%\MINOS\mcp-clients.log.'
    }
}

$AllSelected = @($Selected)
if ($Codex) { $AllSelected += 'codex' }
if ($AllSelected.Count -gt 0) {
    Write-Host "MINOS setup MCP client selection SUCCESS: $($AllSelected -join ', ')" -ForegroundColor Green
}
