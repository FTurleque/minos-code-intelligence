[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($env:OS -ne 'Windows_NT') {
    throw 'MINOS Codex MCP integration verification must run on Windows.'
}

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$Manager = Join-Path $RepoRoot 'scripts\install\configure-codex-mcp.ps1'
if (-not (Test-Path -LiteralPath $Manager -PathType Leaf)) { throw "Codex MCP manager not found: $Manager" }

function Assert-True([bool] $Condition, [string] $Message) { if (-not $Condition) { throw $Message } }

$Root = Join-Path ([System.IO.Path]::GetTempPath()) ('minos-codex-mcp-' + [Guid]::NewGuid())
try {
    $InstallRoot = Join-Path $Root 'MINOS install with spaces'
    $App = Join-Path $InstallRoot 'app'
    $DataRoot = Join-Path $Root 'data'
    $Config = Join-Path $Root '.codex\config.toml'
    $State = Join-Path $Root 'state\codex.json'
    $Log = Join-Path $Root 'logs\mcp.log'
    $Backups = Join-Path $Root 'backups'
    New-Item -ItemType Directory -Force -Path $App, $DataRoot, (Split-Path -Parent $Config) | Out-Null
    New-Item -ItemType File -Force -Path (Join-Path $App 'minos.exe') | Out-Null
    [System.IO.File]::WriteAllText($Config, "model = `"gpt-5.6`"`r`n", [System.Text.UTF8Encoding]::new($false))

    & $Manager -InstallRoot $InstallRoot -Action Install -Mode desktop -Strict `
        -DataRoot $DataRoot -ConfigPath $Config -StatePath $State -LogPath $Log -BackupRoot $Backups

    $Text = [System.IO.File]::ReadAllText($Config, [System.Text.Encoding]::UTF8)
    Assert-True ($Text.Contains('model = "gpt-5.6"')) 'Codex unrelated TOML content was not preserved.'
    Assert-True ($Text.Contains('# BEGIN MINOS MANAGED MCP SERVER')) 'Codex managed marker is missing.'
    Assert-True ($Text.Contains('[mcp_servers.minos]')) 'Codex MINOS MCP section is missing.'
    Assert-True ($Text.Contains('[mcp_servers.minos.env]')) 'Codex MINOS environment section is missing.'
    Assert-True ($Text.Contains('MINOS_HOME')) 'Codex MINOS_HOME is missing.'
    $StateValue = Get-Content -Raw -LiteralPath $State | ConvertFrom-Json
    Assert-True ([string]$StateValue.mode -eq 'toml') 'Codex Desktop state mode should be toml.'
    Assert-True ([string]$StateValue.ownership -eq 'managed') 'Codex Desktop state should be managed.'

    $BeforeSecond = [System.IO.File]::ReadAllText($Config, [System.Text.Encoding]::UTF8)
    & $Manager -InstallRoot $InstallRoot -Action Install -Mode desktop -Strict `
        -DataRoot $DataRoot -ConfigPath $Config -StatePath $State -LogPath $Log -BackupRoot $Backups
    $AfterSecond = [System.IO.File]::ReadAllText($Config, [System.Text.Encoding]::UTF8)
    Assert-True ($BeforeSecond -eq $AfterSecond) 'Codex Desktop reinstall is not idempotent.'

    & $Manager -InstallRoot $InstallRoot -Action Uninstall -Strict `
        -DataRoot $DataRoot -ConfigPath $Config -StatePath $State -LogPath $Log -BackupRoot $Backups
    $After = [System.IO.File]::ReadAllText($Config, [System.Text.Encoding]::UTF8)
    Assert-True ($After.Contains('model = "gpt-5.6"')) 'Codex unrelated TOML content changed on uninstall.'
    Assert-True (-not $After.Contains('[mcp_servers.minos]')) 'Codex MINOS MCP section remained after uninstall.'
    Assert-True (-not (Test-Path -LiteralPath $State -PathType Leaf)) 'Codex managed state remained after uninstall.'
    Assert-True ((Get-ChildItem -LiteralPath $Backups -File -Recurse -ErrorAction SilentlyContinue).Count -ge 2) 'Codex install/uninstall did not create configuration backups.'

    # An unmanaged MINOS section must never be overwritten.
    [System.IO.File]::WriteAllText($Config, "[mcp_servers.minos]`r`ncommand = `"other.exe`"`r`n", [System.Text.UTF8Encoding]::new($false))
    $FailedAsExpected = $false
    try {
        & $Manager -InstallRoot $InstallRoot -Action Install -Mode desktop -Strict `
            -DataRoot $DataRoot -ConfigPath $Config -StatePath $State -LogPath $Log -BackupRoot $Backups
    } catch { $FailedAsExpected = $true }
    Assert-True $FailedAsExpected 'Codex unmanaged MINOS section should block installation in strict mode.'
    Assert-True (([System.IO.File]::ReadAllText($Config, [System.Text.Encoding]::UTF8)).Contains('command = "other.exe"')) 'Codex unmanaged section was modified.'

    # Modification safety: a block MINOS itself created and manages must be
    # preserved -- not silently removed -- if the user edits it before
    # uninstall (blockHash mismatch path). Distinct from the unmanaged-section
    # case above, where MINOS never owned the section in the first place.
    $ModifiedConfig = Join-Path $Root 'modified\.codex\config.toml'
    $ModifiedState = Join-Path $Root 'modified\state\codex.json'
    $ModifiedLog = Join-Path $Root 'modified\logs\mcp.log'
    $ModifiedBackups = Join-Path $Root 'modified\backups'
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ModifiedConfig) | Out-Null
    [System.IO.File]::WriteAllText($ModifiedConfig, "model = `"gpt-5.6`"`r`n", [System.Text.UTF8Encoding]::new($false))
    & $Manager -InstallRoot $InstallRoot -Action Install -Mode desktop -Strict `
        -DataRoot $DataRoot -ConfigPath $ModifiedConfig -StatePath $ModifiedState -LogPath $ModifiedLog -BackupRoot $ModifiedBackups

    $ManagedText = [System.IO.File]::ReadAllText($ModifiedConfig, [System.Text.Encoding]::UTF8)
    $MutatedText = $ManagedText.Replace('MINOS_HOME', 'MINOS_HOME_EDITED_BY_USER')
    Assert-True ($MutatedText -ne $ManagedText) 'Failed to mutate the managed Codex TOML block for the modification-safety test.'
    [System.IO.File]::WriteAllText($ModifiedConfig, $MutatedText, [System.Text.UTF8Encoding]::new($false))

    $ModifiedPreserveRaised = $false
    try {
        & $Manager -InstallRoot $InstallRoot -Action Uninstall -Strict `
            -DataRoot $DataRoot -ConfigPath $ModifiedConfig -StatePath $ModifiedState -LogPath $ModifiedLog -BackupRoot $ModifiedBackups
    } catch { $ModifiedPreserveRaised = $true }
    Assert-True $ModifiedPreserveRaised 'A user-modified managed Codex TOML block should have caused a strict-mode failure on uninstall.'
    $ModifiedAfter = [System.IO.File]::ReadAllText($ModifiedConfig, [System.Text.Encoding]::UTF8)
    Assert-True ($ModifiedAfter.Contains('MINOS_HOME_EDITED_BY_USER')) 'User-modified managed Codex TOML block was overwritten/removed instead of being preserved.'

    Write-Host 'MINOS CODEX MCP INTEGRATION VERIFICATION SUCCESS' -ForegroundColor Green
}
finally {
    Remove-Item -LiteralPath $Root -Recurse -Force -ErrorAction SilentlyContinue
}
