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

    # A complete block explicitly bounded by MINOS markers may survive an older
    # installation after its sidecar state was removed or changed. The installer
    # must back up the file and reclaim only that bounded block instead of failing.
    $OldExe = Join-Path $Root 'old MINOS\app\minos.exe'
    $OldData = Join-Path $Root 'old-data'
    $OldExeToml = $OldExe.Replace('\', '\\')
    $OldDataToml = $OldData.Replace('\', '\\')
    $OrphanedManaged = @(
        'model = "gpt-5.6"',
        '',
        '# BEGIN MINOS MANAGED MCP SERVER',
        '[mcp_servers.minos]',
        ('command = "' + $OldExeToml + '"'),
        'args = ["mcp"]',
        'enabled = true',
        '',
        '[mcp_servers.minos.env]',
        ('MINOS_HOME = "' + $OldDataToml + '"'),
        '# END MINOS MANAGED MCP SERVER',
        ''
    ) -join [Environment]::NewLine
    [System.IO.File]::WriteAllText($Config, $OrphanedManaged, [System.Text.UTF8Encoding]::new($false))
    Remove-Item -LiteralPath $State -Force -ErrorAction SilentlyContinue

    & $Manager -InstallRoot $InstallRoot -Action Install -Mode desktop -Strict `
        -DataRoot $DataRoot -ConfigPath $Config -StatePath $State -LogPath $Log -BackupRoot $Backups

    $Recovered = [System.IO.File]::ReadAllText($Config, [System.Text.Encoding]::UTF8)
    Assert-True ($Recovered.Contains('model = "gpt-5.6"')) 'Codex recovery changed unrelated TOML content.'
    Assert-True (-not $Recovered.Contains($OldExeToml)) 'Codex recovery left the stale MINOS executable in the managed block.'
    Assert-True ($Recovered.Contains($InstallRoot.Replace('\', '\\'))) 'Codex recovery did not install the current MINOS executable.'
    $RecoveredState = Get-Content -Raw -LiteralPath $State | ConvertFrom-Json
    Assert-True ([string]$RecoveredState.mode -eq 'toml') 'Codex recovered state mode should be toml.'
    Assert-True ([string]$RecoveredState.ownership -eq 'managed') 'Codex recovered block should be MINOS-managed.'
    Assert-True ((Get-Content -Raw -LiteralPath $Log).Contains('RECOVER client=codex mode=toml reason=orphaned-minos-managed-block')) 'Codex recovery was not recorded in the integration log.'

    & $Manager -InstallRoot $InstallRoot -Action Uninstall -Strict `
        -DataRoot $DataRoot -ConfigPath $Config -StatePath $State -LogPath $Log -BackupRoot $Backups

    # An unmanaged MINOS section must never be overwritten.
    [System.IO.File]::WriteAllText($Config, "[mcp_servers.minos]`r`ncommand = `"other.exe`"`r`n", [System.Text.UTF8Encoding]::new($false))
    $FailedAsExpected = $false
    try {
        & $Manager -InstallRoot $InstallRoot -Action Install -Mode desktop -Strict `
            -DataRoot $DataRoot -ConfigPath $Config -StatePath $State -LogPath $Log -BackupRoot $Backups
    } catch { $FailedAsExpected = $true }
    Assert-True $FailedAsExpected 'Codex unmanaged MINOS section should block installation in strict mode.'
    Assert-True (([System.IO.File]::ReadAllText($Config, [System.Text.Encoding]::UTF8)).Contains('command = "other.exe"')) 'Codex unmanaged section was modified.'

    Write-Host 'MINOS CODEX MCP INTEGRATION VERIFICATION SUCCESS' -ForegroundColor Green
}
finally {
    Remove-Item -LiteralPath $Root -Recurse -Force -ErrorAction SilentlyContinue
}
