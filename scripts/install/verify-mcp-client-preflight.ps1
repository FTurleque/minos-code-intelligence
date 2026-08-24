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

# Resolved against the real, unmodified PATH before any scenario below
# narrows $env:Path -- looking it up later would search whatever narrowed
# PATH happens to be active at that point instead of the real one.
$RealPwshCommand = Get-Command pwsh.exe -ErrorAction SilentlyContinue
if (-not $RealPwshCommand) { $RealPwshCommand = Get-Command pwsh -ErrorAction SilentlyContinue }
$RealPwshDir = if ($RealPwshCommand) { Split-Path -Parent $RealPwshCommand.Source } else { $null }

try {
    $VsCodeBin = Join-Path $Root 'Microsoft VS Code\bin'
    $CliBin = Join-Path $Root 'cli'
    New-Item -ItemType Directory -Force -Path $VsCodeBin, $CliBin | Out-Null

    @'
@echo off
rem Simulates a VS Code/editor shim that misleadingly accepts `mcp --help`.
if /I "%~1"=="mcp" if /I "%~2"=="--help" exit /b 0
exit /b 0
'@ | Set-Content -LiteralPath (Join-Path $VsCodeBin 'copilot.cmd') -Encoding ascii

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

    $Bytes = [System.IO.File]::ReadAllBytes($Output)
    $HasUtf16LeBom = $Bytes.Length -ge 2 -and $Bytes[0] -eq 0xFF -and $Bytes[1] -eq 0xFE
    Assert-True $HasUtf16LeBom 'Preflight INI must be UTF-16 LE with BOM for deterministic Windows/Inno Setup Unicode parsing.'

    # --- Absent: no copilot command resolves. Scoped to Copilot CLI only --
    # unlike Claude/Codex, it has no filesystem-marker fallback (only PATH
    # resolution), so it's the one client this can isolate reliably: Claude
    # Code's embedded-CLI and Claude/Codex Desktop's filesystem-marker checks
    # look at real locations under the current user profile ($env:APPDATA
    # etc.) that this test cannot safely fake without a seam in
    # detect-mcp-clients.ps1 to override them -- clearing PATH alone doesn't
    # isolate a dev/CI machine that happens to have any of those installed.
    $AbsentBin = Join-Path $Root 'absent-bin'
    New-Item -ItemType Directory -Force -Path $AbsentBin | Out-Null
    $env:Path = $AbsentBin
    $AbsentOutput = Join-Path $Root 'preflight-absent.ini'
    & $Detector -OutputPath $AbsentOutput -ProbeTimeoutSeconds 3
    Assert-True ((Read-IniValue $AbsentOutput 'CopilotCli' 'Available') -eq '0') 'Absent Copilot CLI was incorrectly reported as available.'

    # --- Real, non-shim Copilot CLI compatible with MCP: proves the VS Code
    # shim rejection above isn't the only path Copilot CLI can ever take ---
    $RealCliBin = Join-Path $Root 'real-cli'
    New-Item -ItemType Directory -Force -Path $RealCliBin | Out-Null
    @'
@echo off
if /I "%~1"=="mcp" if /I "%~2"=="--help" exit /b 0
exit /b 1
'@ | Set-Content -LiteralPath (Join-Path $RealCliBin 'copilot.cmd') -Encoding ascii
    $env:Path = "$RealCliBin;$AbsentBin"
    $RealCliOutput = Join-Path $Root 'preflight-real-copilot.ini'
    & $Detector -OutputPath $RealCliOutput -ProbeTimeoutSeconds 3
    Assert-True ((Read-IniValue $RealCliOutput 'CopilotCli' 'Available') -eq '1') 'A real, non-VS-Code-shim Copilot CLI compatible with MCP was not detected.'
    Assert-True ((Read-IniValue $RealCliOutput 'CopilotCli' 'Mode') -eq 'cli') 'Real Copilot CLI mode should be cli.'

    # --- Copilot CLI resolves (not a shim) but fails the mcp --help capability
    # probe -- covers both "exits with an error code" and "present without MCP
    # support", which the detector treats identically (non-zero exit = incompatible) ---
    $NoMcpBin = Join-Path $Root 'no-mcp-cli'
    New-Item -ItemType Directory -Force -Path $NoMcpBin | Out-Null
    @'
@echo off
exit /b 3
'@ | Set-Content -LiteralPath (Join-Path $NoMcpBin 'copilot.cmd') -Encoding ascii
    $env:Path = "$NoMcpBin;$AbsentBin"
    $NoMcpOutput = Join-Path $Root 'preflight-no-mcp-copilot.ini'
    & $Detector -OutputPath $NoMcpOutput -ProbeTimeoutSeconds 3
    Assert-True ((Read-IniValue $NoMcpOutput 'CopilotCli' 'Available') -eq '0') 'A Copilot CLI that fails the mcp --help probe was incorrectly reported as available.'
    $NoMcpReason = Read-IniValue $NoMcpOutput 'CopilotCli' 'Reason'
    Assert-True (-not [string]::IsNullOrWhiteSpace($NoMcpReason)) 'A Copilot CLI without MCP support must still report a diagnostic reason.'

    # --- Copilot CLI resolves but hangs: the detector's own capability-probe
    # timeout/kill path (Invoke-CapabilityProbe), independent of and distinct
    # from configure-mcp-clients.ps1's separate timeout implementation ---
    $HangingBin = Join-Path $Root 'hanging-cli'
    New-Item -ItemType Directory -Force -Path $HangingBin | Out-Null
    $HangingPidPath = Join-Path $HangingBin 'copilot.pid'
    @"
[System.IO.File]::WriteAllText('$($HangingPidPath.Replace("'", "''"))', [string]`$PID, [System.Text.Encoding]::ASCII)
Start-Sleep -Seconds 30
exit 0
"@ | Set-Content -LiteralPath (Join-Path $HangingBin 'copilot.ps1') -Encoding utf8
    # .ps1 launchers are routed through pwsh.exe (Resolve-ProbePowerShell), so
    # pwsh must actually resolve here -- without it the probe fails instantly
    # with a "pwsh required" diagnostic instead of exercising the timeout/kill
    # path this scenario exists to prove.
    Assert-True ($null -ne $RealPwshDir) 'pwsh.exe must be resolvable to exercise the hanging-.ps1-launcher timeout scenario.'
    $env:Path = "$HangingBin;$RealPwshDir;$AbsentBin"
    $HangingOutput = Join-Path $Root 'preflight-hanging-copilot.ini'
    $HangingWatch = [System.Diagnostics.Stopwatch]::StartNew()
    & $Detector -OutputPath $HangingOutput -ProbeTimeoutSeconds 2
    $HangingWatch.Stop()
    Assert-True ((Read-IniValue $HangingOutput 'CopilotCli' 'Available') -eq '0') 'A hanging Copilot CLI was incorrectly reported as available.'
    # Lower bound proves the probe actually waited out its configured timeout
    # rather than failing instantly for an unrelated reason (e.g. pwsh missing).
    Assert-True ($HangingWatch.Elapsed.TotalSeconds -ge 1.5) 'The hanging Copilot CLI preflight probe returned too fast to have actually exercised the timeout.'
    Assert-True ($HangingWatch.Elapsed.TotalSeconds -lt 15) 'The hanging Copilot CLI preflight probe was not bounded by its configured timeout.'
    if (Test-Path -LiteralPath $HangingPidPath -PathType Leaf) {
        $HangingProcessId = [int](Get-Content -Raw -LiteralPath $HangingPidPath)
        Start-Sleep -Milliseconds 200
        Assert-True ($null -eq (Get-Process -Id $HangingProcessId -ErrorAction SilentlyContinue)) 'The timed-out Copilot CLI preflight probe process was left running.'
    }

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
