[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($env:OS -ne 'Windows_NT') {
    throw 'MINOS MCP client preflight verification must run on Windows.'
}

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$Detector = Join-Path $RepoRoot 'scripts\install\detect-mcp-clients.ps1'
if (-not (Test-Path -LiteralPath $Detector -PathType Leaf)) {
    throw "MCP client detector not found: $Detector"
}

function Assert-True([bool] $Condition, [string] $Message) {
    if (-not $Condition) { throw $Message }
}

function Read-IniValue([string] $Path, [string] $Section, [string] $Key) {
    $Current = ''
    foreach ($Line in [System.IO.File]::ReadAllLines($Path, [System.Text.Encoding]::Unicode)) {
        if ($Line -match '^\[([^\]]+)\]$') {
            $Current = $Matches[1]
            continue
        }
        if ($Current -eq $Section -and $Line -match '^([^=]+)=(.*)$' -and $Matches[1] -eq $Key) {
            return $Matches[2]
        }
    }
    return ''
}

$Root = Join-Path ([System.IO.Path]::GetTempPath()) ('minos-mcp-preflight-' + [Guid]::NewGuid())
$OldPath = $env:Path
try {
    $VsCodeBin = Join-Path $Root 'Microsoft VS Code\bin'
    $EmbeddedCopilotBin = Join-Path $Root 'AppData\Roaming\Code\User\globalStorage\github.copilot-chat\copilotCli'
    $CliBin = Join-Path $Root 'cli'
    New-Item -ItemType Directory -Force -Path $VsCodeBin, $EmbeddedCopilotBin, $CliBin | Out-Null

    @'
@echo off
rem Simulates a VS Code/editor shim that misleadingly accepts `mcp --help`.
if /I "%~1"=="mcp" if /I "%~2"=="--help" exit /b 0
exit /b 0
'@ | Set-Content -LiteralPath (Join-Path $VsCodeBin 'copilot.cmd') -Encoding ascii

    @'
@echo off
rem Simulates Copilot Chat's private embedded CLI under Code\User\globalStorage.
if /I "%~1"=="mcp" if /I "%~2"=="--help" exit /b 0
exit /b 0
'@ | Set-Content -LiteralPath (Join-Path $EmbeddedCopilotBin 'copilot.cmd') -Encoding ascii

    foreach ($Name in @('claude', 'codex')) {
        @"
@echo off
if /I "%~1"=="mcp" if /I "%~2"=="--help" exit /b 0
exit /b 1
"@ | Set-Content -LiteralPath (Join-Path $CliBin "$Name.cmd") -Encoding ascii
    }

    $env:Path = "$VsCodeBin;$CliBin;$OldPath"
    $Output = Join-Path $Root 'preflight.ini'
    & $Detector -OutputPath $Output -ProbeTimeoutSeconds 3

    Assert-True (Test-Path -LiteralPath $Output -PathType Leaf) 'Preflight did not create its INI contract.'
    Assert-True ((Read-IniValue $Output 'CopilotCli' 'Available') -eq '0') 'VS Code copilot shim was incorrectly accepted as Copilot CLI even though its help probe returned success.'
    $CopilotReason = Read-IniValue $Output 'CopilotCli' 'Reason'
    Assert-True ($CopilotReason -match 'launcher VS Code') 'Copilot shim diagnostic is not explicit.'
    Assert-True ($CopilotReason.IndexOf([char]0x00E9) -ge 0) 'Copilot shim diagnostic lost its Unicode accented character.'
    Assert-True ($CopilotReason.IndexOf([char]0x2014) -ge 0) 'Copilot shim diagnostic lost its Unicode em dash.'
    Assert-True ((Read-IniValue $Output 'ClaudeCode' 'Available') -eq '1') 'Compatible Claude CLI / Code was not detected.'
    Assert-True ((Read-IniValue $Output 'ClaudeCode' 'Mode') -eq 'cli') 'Claude CLI / Code mode should be cli.'
    Assert-True ((Read-IniValue $Output 'CodexCli' 'Available') -eq '1') 'Compatible Codex CLI was not exposed on the explicit CodexCli surface.'
    Assert-True ((Read-IniValue $Output 'CodexCli' 'Mode') -eq 'cli') 'Explicit CodexCli mode should be cli.'
    Assert-True ((Read-IniValue $Output 'CodexDesktop' 'Available') -in @('0', '1')) 'CodexDesktop section is missing from the preflight contract.'
    Assert-True ((Read-IniValue $Output 'Codex' 'Available') -eq '1') 'Backward-compatible aggregate Codex surface was not detected.'
    Assert-True ((Read-IniValue $Output 'Codex' 'Mode') -eq 'cli') 'Aggregate Codex mode should prefer cli when a compatible CLI is present.'

    # Re-run with the exact path shape reported by the real installer log:
    # %APPDATA%\Code\User\globalStorage\github.copilot-chat\copilotCli\copilot.*
    $env:Path = "$EmbeddedCopilotBin;$CliBin;$OldPath"
    $EmbeddedOutput = Join-Path $Root 'preflight-embedded.ini'
    & $Detector -OutputPath $EmbeddedOutput -ProbeTimeoutSeconds 3
    Assert-True ((Read-IniValue $EmbeddedOutput 'CopilotCli' 'Available') -eq '0') 'VS Code Copilot Chat embedded CLI was incorrectly accepted as standalone Copilot CLI.'
    Assert-True ((Read-IniValue $EmbeddedOutput 'CopilotCli' 'Reason') -match 'launcher VS Code') 'Embedded Copilot CLI diagnostic is not explicit.'

    $Bytes = [System.IO.File]::ReadAllBytes($Output)
    $HasUtf16LeBom = $Bytes.Length -ge 2 -and $Bytes[0] -eq 0xFF -and $Bytes[1] -eq 0xFE
    Assert-True $HasUtf16LeBom 'Preflight INI must be UTF-16 LE with BOM for deterministic Windows/Inno Setup Unicode parsing.'

    Write-Host 'MINOS MCP CLIENT PREFLIGHT VERIFICATION SUCCESS' -ForegroundColor Green
}
finally {
    $env:Path = $OldPath
    Remove-Item -LiteralPath $Root -Recurse -Force -ErrorAction SilentlyContinue
}

# Keep the installer-facing verification chain in one entry point so every
# Windows distribution build checks detection, Codex Desktop lifecycle, backend-agnostic
# client routing and the Inno contract without depending on GitHub Actions.
foreach ($FollowUp in @(
    'scripts\install\verify-codex-mcp-integration.ps1',
    'scripts\install\verify-mcp-client-backend-routing.ps1',
    'scripts\install\verify-installer-template.ps1'
)) {
    $FollowUpPath = Join-Path $RepoRoot $FollowUp
    if (-not (Test-Path -LiteralPath $FollowUpPath -PathType Leaf)) {
        throw "Installer verification helper not found: $FollowUpPath"
    }
    & $FollowUpPath
}
