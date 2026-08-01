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
foreach ($Required in @($Manager, $CodexManager)) {
    if (-not (Test-Path -LiteralPath $Required -PathType Leaf)) {
        if ($Strict) { throw "MINOS MCP uninstall helper not found: $Required" }
        Write-Warning "MINOS MCP uninstall helper not found: $Required"
        return
    }
}

# The historical manager resolved CLI tools again through PATH during uninstall.
# Rehydrate PATH from the exact toolPath values captured at installation time so
# an IDE/CLI update cannot strand a MINOS-managed MCP entry merely because the
# user's PATH changed between installation and removal.
$LocalAppData = [Environment]::GetFolderPath('LocalApplicationData')
$StatePath = Join-Path $LocalAppData 'MINOS\mcp-client-integrations.json'
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

    & $CodexManager -InstallRoot $InstallRoot -Action Uninstall -Strict:$Strict
    & $Manager -InstallRoot $InstallRoot -Action Uninstall -Strict:$Strict
}
finally {
    $env:Path = $OldPath
}
