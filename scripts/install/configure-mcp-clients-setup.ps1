[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $InstallRoot,

    [switch] $CopilotJetBrains,
    [switch] $CopilotCli,
    [switch] $ClaudeCode,
    [switch] $ClaudeDesktop,
    [switch] $Codex,

    [ValidateSet('auto', 'cli', 'desktop')]
    [string] $CodexMode = 'auto',

    [ValidatePattern('^[A-Za-z][A-Za-z0-9_-]{0,63}$')]
    [string] $McpServerName = 'minos',

    [string] $DataRoot = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$Manager = Join-Path $PSScriptRoot 'configure-mcp-clients.ps1'
$CodexManager = Join-Path $PSScriptRoot 'configure-codex-mcp.ps1'
$NamedInvoker = Join-Path $PSScriptRoot 'invoke-named-mcp-script.ps1'
foreach ($Required in @($Manager, $CodexManager, $NamedInvoker)) {
    if (-not (Test-Path -LiteralPath $Required -PathType Leaf)) {
        throw "MINOS MCP client integration helper not found: $Required"
    }
}

function Resolve-CommandPath([string] $Name) {
    $Command = Get-Command $Name -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -eq $Command) { return '' }
    if (-not [string]::IsNullOrWhiteSpace([string]$Command.Source)) { return [string]$Command.Source }
    if ($Command.PSObject.Properties['Path'] -and -not [string]::IsNullOrWhiteSpace([string]$Command.Path)) { return [string]$Command.Path }
    return [string]$Command.Name
}

function Test-VsCodeCopilotShim([string] $Path) {
    if ([string]::IsNullOrWhiteSpace($Path)) { return $false }
    return $Path -match '(?i)(Microsoft VS Code|\\Code\\bin\\|\\Code\\User\\globalStorage\\github\.copilot-chat\\|\\.vscode\\|vscode)'
}

if ($CopilotCli) {
    $ResolvedCopilot = Resolve-CommandPath 'copilot'
    if (Test-VsCodeCopilotShim $ResolvedCopilot) {
        throw "GitHub Copilot CLI was selected, but the resolved launcher belongs to VS Code/Copilot Chat rather than a standalone CLI: $ResolvedCopilot"
    }
}

$Parameters = @{ InstallRoot = $InstallRoot }
if (-not [string]::IsNullOrWhiteSpace($DataRoot)) { $Parameters['DataRoot'] = $DataRoot }
if ($CopilotJetBrains) { $Parameters['CopilotJetBrains'] = $true }
if ($CopilotCli) { $Parameters['CopilotCli'] = $true }
if ($ClaudeCode) { $Parameters['ClaudeCode'] = $true }
if ($ClaudeDesktop) { $Parameters['ClaudeDesktop'] = $true }

$ManagerSelected = $CopilotJetBrains -or $CopilotCli -or $ClaudeCode -or $ClaudeDesktop
if ($ManagerSelected) {
    & $NamedInvoker -SourcePath $Manager -McpServerName $McpServerName -Parameters $Parameters
}
if ($Codex) {
    $CodexParameters = @{
        InstallRoot = $InstallRoot
        Action = 'Install'
        Mode = $CodexMode
        Strict = $true
    }
    if (-not [string]::IsNullOrWhiteSpace($DataRoot)) { $CodexParameters['DataRoot'] = $DataRoot }
    & $NamedInvoker -SourcePath $CodexManager -McpServerName $McpServerName -Parameters $CodexParameters -CodexScript
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
    $CodexStateValue = Get-Content -Raw -LiteralPath $CodexState | ConvertFrom-Json
    if ($CodexMode -eq 'cli' -and [string]$CodexStateValue.mode -ne 'cli') {
        throw "Codex preflight selected CLI mode but configured mode is '$($CodexStateValue.mode)'."
    }
    if ($CodexMode -eq 'desktop' -and [string]$CodexStateValue.mode -ne 'toml') {
        throw "Codex preflight selected Desktop mode but configured mode is '$($CodexStateValue.mode)'."
    }
}

$AllSelected = @($Selected)
if ($Codex) { $AllSelected += "codex:$CodexMode" }
if ($AllSelected.Count -gt 0) {
    Write-Host "MINOS setup MCP client selection SUCCESS ($McpServerName): $($AllSelected -join ', ')" -ForegroundColor Green
}
