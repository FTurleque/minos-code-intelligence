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
if (-not (Test-Path -LiteralPath $Manager -PathType Leaf)) {
    throw "MINOS MCP client integration manager not found: $Manager"
}

$Parameters = @{
    InstallRoot = $InstallRoot
}
if ($CopilotJetBrains) { $Parameters['CopilotJetBrains'] = $true }
if ($CopilotCli) { $Parameters['CopilotCli'] = $true }
if ($ClaudeCode) { $Parameters['ClaudeCode'] = $true }
if ($ClaudeDesktop) { $Parameters['ClaudeDesktop'] = $true }
if ($Codex) { $Parameters['Codex'] = $true }

& $Manager @Parameters

$Selected = @()
if ($CopilotJetBrains) { $Selected += 'copilot-jetbrains' }
if ($CopilotCli) { $Selected += 'copilot-cli' }
if ($ClaudeCode) { $Selected += 'claude-code' }
if ($ClaudeDesktop) { $Selected += 'claude-desktop' }
if ($Codex) { $Selected += 'codex' }

if ($Selected.Count -eq 0) {
    return
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

$Missing = @($Selected | Where-Object { $ManagedIds -notcontains $_ })
if ($Missing.Count -gt 0) {
    throw "One or more selected MINOS MCP client integrations were not configured: $($Missing -join ', '). See %LOCALAPPDATA%\MINOS\mcp-clients.log."
}

Write-Host "MINOS setup MCP client selection SUCCESS: $($Selected -join ', ')" -ForegroundColor Green
