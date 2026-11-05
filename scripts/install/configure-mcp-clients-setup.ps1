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

    [string] $DataRoot = '',

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

$Manager = Join-Path $PSScriptRoot 'configure-mcp-clients.ps1'
$CodexManager = Join-Path $PSScriptRoot 'configure-codex-mcp.ps1'
$NamedInvoker = Join-Path $PSScriptRoot 'invoke-named-mcp-script.ps1'
foreach ($Required in @($Manager, $CodexManager, $NamedInvoker)) {
    if (-not (Test-Path -LiteralPath $Required -PathType Leaf)) {
        throw "MINOS MCP client integration helper not found: $Required"
    }
}

$Parameters = @{ InstallRoot = $InstallRoot }
if (-not [string]::IsNullOrWhiteSpace($DataRoot)) { $Parameters['DataRoot'] = $DataRoot }
if ($CopilotJetBrains) { $Parameters['CopilotJetBrains'] = $true }
if ($CopilotCli) { $Parameters['CopilotCli'] = $true }
if ($ClaudeCode) { $Parameters['ClaudeCode'] = $true }
if ($ClaudeDesktop) { $Parameters['ClaudeDesktop'] = $true }

$Selected = @()
if ($CopilotJetBrains) { $Selected += 'copilot-jetbrains' }
if ($CopilotCli) { $Selected += 'copilot-cli' }
if ($ClaudeCode) { $Selected += 'claude-code' }
if ($ClaudeDesktop) { $Selected += 'claude-desktop' }

# Installer row of each managed client, keyed by the id its integration state records.
$ClientSteps = @{
    'copilot-jetbrains' = 'client-copilot-jetbrains'
    'copilot-cli' = 'client-copilot-cli'
    'claude-code' = 'client-claude-code'
    'claude-desktop' = 'client-claude-desktop'
}

$ManagerSelected = $CopilotJetBrains -or $CopilotCli -or $ClaudeCode -or $ClaudeDesktop
if ($ManagerSelected) {
    foreach ($Client in $Selected) { Write-SetupProgress -Step $ClientSteps[$Client] -State 'running' }
    & $NamedInvoker -SourcePath $Manager -McpServerName $McpServerName -Parameters $Parameters
}
if ($Codex) {
    Write-SetupProgress -Step 'client-codex' -State 'running'
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

# A client is reported done only once its integration state proves it; a missing one stays running
# and the installer shows it failed when this script exits non-zero.
foreach ($Client in @($Selected | Where-Object { $ManagedIds -contains $_ })) {
    Write-SetupProgress -Step $ClientSteps[$Client] -State 'done'
}
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
    Write-SetupProgress -Step 'client-codex' -State 'done'
}

$AllSelected = @($Selected)
if ($Codex) { $AllSelected += "codex:$CodexMode" }
if ($AllSelected.Count -gt 0) {
    Write-Host "MINOS setup MCP client selection SUCCESS ($McpServerName): $($AllSelected -join ', ')" -ForegroundColor Green
}
