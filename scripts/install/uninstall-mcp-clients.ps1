[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $InstallRoot,

    [switch] $Strict
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$Manager = Join-Path $PSScriptRoot 'configure-mcp-clients.ps1'
$CodexManager = Join-Path $PSScriptRoot 'configure-codex-mcp.ps1'
$NamedInvoker = Join-Path $PSScriptRoot 'invoke-named-mcp-script.ps1'
foreach ($Required in @($Manager, $CodexManager, $NamedInvoker)) {
    if (-not (Test-Path -LiteralPath $Required -PathType Leaf)) {
        if ($Strict) { throw "MINOS MCP uninstall helper not found: $Required" }
        Write-Warning "MINOS MCP uninstall helper not found: $Required"
        return
    }
}

$LocalAppData = [Environment]::GetFolderPath('LocalApplicationData')
$StatePath = Join-Path $LocalAppData 'MINOS\mcp-client-integrations.json'
$InstallerStatePath = Join-Path $LocalAppData 'MINOS\installer-state.json'
$McpServerName = 'minos'
$DataRoot = ''

if (Test-Path -LiteralPath $InstallerStatePath -PathType Leaf) {
    try {
        $InstallerState = Get-Content -Raw -LiteralPath $InstallerStatePath | ConvertFrom-Json
        $CandidateName = [string]$InstallerState.mcpServerName
        if (-not [string]::IsNullOrWhiteSpace($CandidateName)) {
            if ($CandidateName -notmatch '^[A-Za-z][A-Za-z0-9_-]{0,63}$') {
                throw "Invalid MCP server name in installer state: $CandidateName"
            }
            $McpServerName = $CandidateName
        }
        if (-not [string]::IsNullOrWhiteSpace([string]$InstallerState.dataRoot)) {
            $DataRoot = [System.IO.Path]::GetFullPath([string]$InstallerState.dataRoot)
        }
    }
    catch {
        if ($Strict) { throw "Unable to read MINOS installer state before MCP uninstall: $($_.Exception.Message)" }
        Write-Warning "Unable to read MINOS installer state before MCP uninstall: $($_.Exception.Message)"
    }
}

# Rehydrate PATH from exact CLI tool paths captured at install time. This keeps
# uninstall ownership-safe after IDE/CLI upgrades or user PATH changes.
$SavedDirectories = @()
if (Test-Path -LiteralPath $StatePath -PathType Leaf) {
    try {
        $State = Get-Content -Raw -LiteralPath $StatePath | ConvertFrom-Json
        foreach ($Client in @($State.clients)) {
            if ([string]$Client.kind -ne 'cli' -or -not $Client.PSObject.Properties['toolPath']) { continue }
            $ToolPath = [string]$Client.toolPath
            if ([string]::IsNullOrWhiteSpace($ToolPath) -or -not (Test-Path -LiteralPath $ToolPath -PathType Leaf)) { continue }
            $SavedDirectories += (Split-Path -Parent $ToolPath)
        }
    }
    catch {
        if ($Strict) { throw "Unable to read MINOS MCP client state before uninstall: $($_.Exception.Message)" }
        Write-Warning "Unable to read MINOS MCP client state before uninstall: $($_.Exception.Message)"
    }
}

$OldPath = $env:Path
try {
    foreach ($Directory in @($SavedDirectories | Sort-Object -Unique)) {
        if ($env:Path -notlike "$Directory;*") {
            $env:Path = "$Directory;$env:Path"
        }
    }

    $ManagerParameters = @{ InstallRoot = $InstallRoot; Action = 'Uninstall' }
    if ($Strict) { $ManagerParameters['Strict'] = $true }
    if (-not [string]::IsNullOrWhiteSpace($DataRoot)) { $ManagerParameters['DataRoot'] = $DataRoot }
    & $NamedInvoker -SourcePath $Manager -McpServerName $McpServerName -Parameters $ManagerParameters

    $CodexStatePath = Join-Path $LocalAppData 'MINOS\codex-mcp-integration.json'
    if (Test-Path -LiteralPath $CodexStatePath -PathType Leaf) {
        $CodexParameters = @{ InstallRoot = $InstallRoot; Action = 'Uninstall'; Mode = 'auto' }
        if ($Strict) { $CodexParameters['Strict'] = $true }
        if (-not [string]::IsNullOrWhiteSpace($DataRoot)) { $CodexParameters['DataRoot'] = $DataRoot }
        & $NamedInvoker -SourcePath $CodexManager -McpServerName $McpServerName `
            -Parameters $CodexParameters -CodexScript
    }
}
catch {
    if ($Strict) { throw }
    Write-Warning "MINOS MCP client cleanup completed with warnings: $($_.Exception.Message)"
}
finally {
    $env:Path = $OldPath
}
