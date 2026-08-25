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

function Resolve-CSharpCompiler {
    $Candidates = @(
        (Join-Path $env:SystemRoot 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'),
        (Join-Path $env:SystemRoot 'Microsoft.NET\Framework\v4.0.30319\csc.exe')
    )
    foreach ($Candidate in $Candidates) {
        if (Test-Path -LiteralPath $Candidate -PathType Leaf) { return $Candidate }
    }
    return $null
}

# A batch/PowerShell script renamed to .exe will not run -- Windows requires a
# real PE executable for a .exe file. Find-EmbeddedClaudeCli specifically
# looks for a literal claude.exe, so proving it works end to end (found AND
# capability-probed successfully) needs one. Add-Type -OutputType
# ConsoleApplication is not supported under PowerShell 7, so this compiles
# directly with the .NET Framework C# compiler that ships on every Windows
# image (including GitHub-hosted windows-2022 runners).
function New-FakeClaudeExe([string] $Path) {
    $Compiler = Resolve-CSharpCompiler
    if (-not $Compiler) { return $false }
    $SourcePath = [System.IO.Path]::ChangeExtension($Path, '.cs')
    $CSharp = @'
using System;
class Program {
    static int Main(string[] args) {
        if (args.Length >= 2 &&
            string.Equals(args[0], "mcp", StringComparison.OrdinalIgnoreCase) &&
            string.Equals(args[1], "--help", StringComparison.OrdinalIgnoreCase)) {
            return 0;
        }
        return 1;
    }
}
'@
    Set-Content -LiteralPath $SourcePath -Value $CSharp -Encoding ascii
    & $Compiler /nologo "/out:$Path" $SourcePath 2>&1 | Out-Null
    return (Test-Path -LiteralPath $Path -PathType Leaf)
}

$Root = Join-Path ([System.IO.Path]::GetTempPath()) ('minos-mcp-preflight-' + [Guid]::NewGuid())
$OldPath = $env:Path
$OldAppData = $env:APPDATA
$OldLocalAppData = $env:LOCALAPPDATA
$OldUserProfile = $env:USERPROFILE

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

    # --- Absent: nothing resolves on PATH and no filesystem marker exists for
    # any client. Isolates $env:APPDATA/LOCALAPPDATA/USERPROFILE to fresh,
    # empty directories via detect-mcp-clients.ps1's Get-UserFolderPath seam
    # -- without it, a dev/CI machine that happens to have any real client
    # installed would leak through and this assertion would be unreliable.
    $AbsentBin = Join-Path $Root 'absent-bin'
    $AbsentAppData = Join-Path $Root 'absent-appdata\Roaming'
    $AbsentLocalAppData = Join-Path $Root 'absent-appdata\Local'
    $AbsentUserProfile = Join-Path $Root 'absent-appdata\profile'
    New-Item -ItemType Directory -Force -Path $AbsentBin, $AbsentAppData, $AbsentLocalAppData, $AbsentUserProfile | Out-Null
    $env:Path = $AbsentBin
    $env:APPDATA = $AbsentAppData
    $env:LOCALAPPDATA = $AbsentLocalAppData
    $env:USERPROFILE = $AbsentUserProfile
    $AbsentOutput = Join-Path $Root 'preflight-absent.ini'
    & $Detector -OutputPath $AbsentOutput -ProbeTimeoutSeconds 3
    Assert-True ((Read-IniValue $AbsentOutput 'CopilotJetBrains' 'Available') -eq '0') 'Absent Copilot JetBrains was incorrectly reported as available.'
    Assert-True ((Read-IniValue $AbsentOutput 'CopilotCli' 'Available') -eq '0') 'Absent Copilot CLI was incorrectly reported as available.'
    Assert-True ((Read-IniValue $AbsentOutput 'ClaudeCode' 'Available') -eq '0') 'Absent Claude CLI / Code was incorrectly reported as available.'
    Assert-True ((Read-IniValue $AbsentOutput 'ClaudeDesktop' 'Available') -eq '0') 'Absent Claude Desktop was incorrectly reported as available.'
    Assert-True ((Read-IniValue $AbsentOutput 'CodexCli' 'Available') -eq '0') 'Absent Codex CLI was incorrectly reported as available.'
    Assert-True ((Read-IniValue $AbsentOutput 'CodexDesktop' 'Available') -eq '0') 'Absent Codex Desktop was incorrectly reported as available.'
    Assert-True ((Read-IniValue $AbsentOutput 'Codex' 'Available') -eq '0') 'Absent aggregate Codex was incorrectly reported as available.'

    # --- AlreadyManaged: a client the wizard can technically configure must be
    # told apart from one MINOS already confirmed wiring, so a reinstall never
    # reads as "nothing is configured yet" for an integration that already
    # works. Isolated LOCALAPPDATA/APPDATA/USERPROFILE, same as Absent above,
    # plus real state files this scenario pre-seeds itself. ---
    $ManagedBin = Join-Path $Root 'already-managed-bin'
    $ManagedAppData = Join-Path $Root 'already-managed-appdata\Roaming'
    $ManagedLocalAppData = Join-Path $Root 'already-managed-appdata\Local'
    $ManagedUserProfile = Join-Path $Root 'already-managed-appdata\profile'
    New-Item -ItemType Directory -Force -Path $ManagedBin, $ManagedAppData, $ManagedLocalAppData, $ManagedUserProfile | Out-Null
    # CopilotJetBrains available via its filesystem marker; CodexCli available via PATH.
    New-Item -ItemType Directory -Force -Path (Join-Path $ManagedLocalAppData 'github-copilot\intellij') | Out-Null
    @'
@echo off
if /I "%~1"=="mcp" if /I "%~2"=="--help" exit /b 0
exit /b 1
'@ | Set-Content -LiteralPath (Join-Path $ManagedBin 'codex.cmd') -Encoding ascii
    # ClaudeDesktop is left genuinely unavailable -- proves AlreadyManaged is
    # never surfaced for a client the preflight itself reports unavailable.
    New-Item -ItemType Directory -Force -Path (Join-Path $ManagedLocalAppData 'MINOS') | Out-Null
    [System.IO.File]::WriteAllText(
        (Join-Path $ManagedLocalAppData 'MINOS\mcp-client-integrations.json'),
        '{"clients":[{"id":"copilot-jetbrains","ownership":"managed"},{"id":"claude-desktop","ownership":"managed"}]}',
        [System.Text.UTF8Encoding]::new($false))
    [System.IO.File]::WriteAllText(
        (Join-Path $ManagedLocalAppData 'MINOS\codex-mcp-integration.json'),
        '{"mode":"cli","ownership":"managed"}',
        [System.Text.UTF8Encoding]::new($false))

    $env:Path = $ManagedBin
    $env:APPDATA = $ManagedAppData
    $env:LOCALAPPDATA = $ManagedLocalAppData
    $env:USERPROFILE = $ManagedUserProfile
    $ManagedOutput = Join-Path $Root 'preflight-already-managed.ini'
    & $Detector -OutputPath $ManagedOutput -ProbeTimeoutSeconds 3
    Assert-True ((Read-IniValue $ManagedOutput 'CopilotJetBrains' 'Available') -eq '1') 'CopilotJetBrains marker was not detected for the AlreadyManaged scenario.'
    Assert-True ((Read-IniValue $ManagedOutput 'CopilotJetBrains' 'AlreadyManaged') -eq '1') 'A CopilotJetBrains entry already tracked in mcp-client-integrations.json was not reported AlreadyManaged.'
    Assert-True ((Read-IniValue $ManagedOutput 'CodexCli' 'Available') -eq '1') 'CodexCli was not detected for the AlreadyManaged scenario.'
    Assert-True ((Read-IniValue $ManagedOutput 'CodexCli' 'AlreadyManaged') -eq '1') 'A managed codex-mcp-integration.json state was not reported AlreadyManaged on the CodexCli surface.'
    Assert-True ((Read-IniValue $ManagedOutput 'Codex' 'AlreadyManaged') -eq '1') 'A managed codex-mcp-integration.json state was not reported AlreadyManaged on the aggregate Codex surface.'
    Assert-True ((Read-IniValue $ManagedOutput 'ClaudeDesktop' 'Available') -eq '0') 'ClaudeDesktop should remain unavailable in this scenario.'
    Assert-True ((Read-IniValue $ManagedOutput 'ClaudeDesktop' 'AlreadyManaged') -eq '0') 'An unavailable client must never be reported AlreadyManaged even if a stale tracking entry exists for it.'
    Assert-True ((Read-IniValue $ManagedOutput 'CopilotCli' 'AlreadyManaged') -eq '0') 'An available-but-untracked client was incorrectly reported AlreadyManaged.'

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

    # --- Claude Desktop, classic install: isolated via the APPDATA/LOCALAPPDATA/
    # USERPROFILE env-var seam in detect-mcp-clients.ps1 (Get-UserFolderPath) --
    # not previously exercisable without depending on whatever happens to
    # actually be installed on the machine running the test ---
    $ClassicRoot = Join-Path $Root 'claude-desktop-classic'
    $ClassicAppData = Join-Path $ClassicRoot 'Roaming'
    $ClassicLocalAppData = Join-Path $ClassicRoot 'Local'
    New-Item -ItemType Directory -Force -Path (Join-Path $ClassicAppData 'Claude'), $ClassicLocalAppData | Out-Null
    $env:Path = $AbsentBin
    $env:APPDATA = $ClassicAppData
    $env:LOCALAPPDATA = $ClassicLocalAppData
    $env:USERPROFILE = $ClassicRoot
    $ClassicOutput = Join-Path $Root 'preflight-claude-desktop-classic.ini'
    & $Detector -OutputPath $ClassicOutput -ProbeTimeoutSeconds 3
    Assert-True ((Read-IniValue $ClassicOutput 'ClaudeDesktop' 'Available') -eq '1') 'Classic Claude Desktop installation (%APPDATA%\Claude marker) was not detected.'

    # --- Claude Desktop, Microsoft Store (MSIX) install ---
    $MsixRoot = Join-Path $Root 'claude-desktop-msix'
    $MsixAppData = Join-Path $MsixRoot 'Roaming'
    $MsixLocalAppData = Join-Path $MsixRoot 'Local'
    New-Item -ItemType Directory -Force -Path $MsixAppData, (Join-Path $MsixLocalAppData 'Packages\Claude_8wekyb3d8bbwe\LocalCache\Roaming\Claude') | Out-Null
    $env:Path = $AbsentBin
    $env:APPDATA = $MsixAppData
    $env:LOCALAPPDATA = $MsixLocalAppData
    $env:USERPROFILE = $MsixRoot
    $MsixOutput = Join-Path $Root 'preflight-claude-desktop-msix.ini'
    & $Detector -OutputPath $MsixOutput -ProbeTimeoutSeconds 3
    Assert-True ((Read-IniValue $MsixOutput 'ClaudeDesktop' 'Available') -eq '1') 'MSIX Claude Desktop installation (LocalCache marker) was not detected.'

    # --- Both classic and MSIX markers present: must still detect (the
    # production code checks MSIX first and short-circuits, but either
    # signal alone must be sufficient) ---
    $BothRoot = Join-Path $Root 'claude-desktop-both'
    $BothAppData = Join-Path $BothRoot 'Roaming'
    $BothLocalAppData = Join-Path $BothRoot 'Local'
    New-Item -ItemType Directory -Force -Path (Join-Path $BothAppData 'Claude'), (Join-Path $BothLocalAppData 'Packages\Claude_8wekyb3d8bbwe\LocalCache\Roaming\Claude') | Out-Null
    $env:Path = $AbsentBin
    $env:APPDATA = $BothAppData
    $env:LOCALAPPDATA = $BothLocalAppData
    $env:USERPROFILE = $BothRoot
    $BothOutput = Join-Path $Root 'preflight-claude-desktop-both.ini'
    & $Detector -OutputPath $BothOutput -ProbeTimeoutSeconds 3
    Assert-True ((Read-IniValue $BothOutput 'ClaudeDesktop' 'Available') -eq '1') 'Claude Desktop was not detected when both classic and MSIX markers are present.'

    # --- Claude Code Desktop's embedded CLI (%APPDATA%\Claude\claude-code\<version>\claude.exe),
    # used only as a fallback when nothing named "claude" resolves on PATH ---
    $EmbeddedRoot = Join-Path $Root 'claude-embedded-cli'
    $EmbeddedAppData = Join-Path $EmbeddedRoot 'Roaming'
    $EmbeddedLocalAppData = Join-Path $EmbeddedRoot 'Local'
    $EmbeddedClaudeCodeDir = Join-Path $EmbeddedAppData 'Claude\claude-code\1.2.3'
    New-Item -ItemType Directory -Force -Path $EmbeddedClaudeCodeDir, $EmbeddedLocalAppData | Out-Null
    $EmbeddedExe = Join-Path $EmbeddedClaudeCodeDir 'claude.exe'
    if (New-FakeClaudeExe -Path $EmbeddedExe) {
        $env:Path = $AbsentBin
        $env:APPDATA = $EmbeddedAppData
        $env:LOCALAPPDATA = $EmbeddedLocalAppData
        $env:USERPROFILE = $EmbeddedRoot
        $EmbeddedOutput = Join-Path $Root 'preflight-claude-embedded.ini'
        & $Detector -OutputPath $EmbeddedOutput -ProbeTimeoutSeconds 3
        Assert-True ((Read-IniValue $EmbeddedOutput 'ClaudeCode' 'Available') -eq '1') "Claude Code Desktop's embedded claude.exe (no claude on PATH) was not detected as a compatible CLI."
        Assert-True ((Read-IniValue $EmbeddedOutput 'ClaudeCode' 'Mode') -eq 'cli') 'Embedded Claude CLI mode should be cli.'
    } else {
        Write-Warning 'Skipping embedded Claude CLI scenario: no C# compiler (csc.exe) found to build a real claude.exe.'
    }

    # --- Codex Desktop only (no codex CLI on PATH) ---
    $CodexDesktopRoot = Join-Path $Root 'codex-desktop-only'
    $CodexDesktopAppData = Join-Path $CodexDesktopRoot 'Roaming'
    $CodexDesktopLocalAppData = Join-Path $CodexDesktopRoot 'Local'
    New-Item -ItemType Directory -Force -Path (Join-Path $CodexDesktopRoot '.codex'), $CodexDesktopAppData, $CodexDesktopLocalAppData | Out-Null
    'model = "gpt-5.6"' | Set-Content -LiteralPath (Join-Path $CodexDesktopRoot '.codex\config.toml') -Encoding ascii
    $env:Path = $AbsentBin
    $env:APPDATA = $CodexDesktopAppData
    $env:LOCALAPPDATA = $CodexDesktopLocalAppData
    $env:USERPROFILE = $CodexDesktopRoot
    $CodexDesktopOutput = Join-Path $Root 'preflight-codex-desktop-only.ini'
    & $Detector -OutputPath $CodexDesktopOutput -ProbeTimeoutSeconds 3
    Assert-True ((Read-IniValue $CodexDesktopOutput 'CodexCli' 'Available') -eq '0') 'Codex CLI was incorrectly reported as available with no codex command on PATH.'
    Assert-True ((Read-IniValue $CodexDesktopOutput 'CodexDesktop' 'Available') -eq '1') 'Codex Desktop (~/.codex/config.toml marker, no CLI) was not detected.'
    Assert-True ((Read-IniValue $CodexDesktopOutput 'Codex' 'Mode') -eq 'desktop') 'Aggregate Codex mode should fall back to desktop when only the Desktop marker is present.'

    # --- Codex CLI and Desktop marker both present: production code
    # deterministically prefers CLI (detect-mcp-clients.ps1 checks
    # $CodexCliAvailable before $CodexDesktopAvailable for the aggregate
    # section) -- proven here with a controlled fixture instead of
    # incidentally depending on whatever the host machine happens to have. ---
    $CodexBothRoot = Join-Path $Root 'codex-both'
    $CodexBothBin = Join-Path $CodexBothRoot 'bin'
    $CodexBothAppData = Join-Path $CodexBothRoot 'Roaming'
    $CodexBothLocalAppData = Join-Path $CodexBothRoot 'Local'
    New-Item -ItemType Directory -Force -Path $CodexBothBin, (Join-Path $CodexBothRoot '.codex'), $CodexBothAppData, $CodexBothLocalAppData | Out-Null
    'model = "gpt-5.6"' | Set-Content -LiteralPath (Join-Path $CodexBothRoot '.codex\config.toml') -Encoding ascii
    @'
@echo off
if /I "%~1"=="mcp" if /I "%~2"=="--help" exit /b 0
exit /b 1
'@ | Set-Content -LiteralPath (Join-Path $CodexBothBin 'codex.cmd') -Encoding ascii
    $env:Path = "$CodexBothBin;$AbsentBin"
    $env:APPDATA = $CodexBothAppData
    $env:LOCALAPPDATA = $CodexBothLocalAppData
    $env:USERPROFILE = $CodexBothRoot
    $CodexBothOutput = Join-Path $Root 'preflight-codex-both.ini'
    & $Detector -OutputPath $CodexBothOutput -ProbeTimeoutSeconds 3
    Assert-True ((Read-IniValue $CodexBothOutput 'CodexCli' 'Available') -eq '1') 'Codex CLI was not detected when both CLI and Desktop markers are present.'
    Assert-True ((Read-IniValue $CodexBothOutput 'CodexDesktop' 'Available') -eq '1') 'Codex Desktop marker was not detected alongside a compatible CLI.'
    Assert-True ((Read-IniValue $CodexBothOutput 'Codex' 'Mode') -eq 'cli') 'Aggregate Codex mode did not deterministically prefer CLI when both CLI and Desktop are available.'

    Write-Host 'MINOS MCP CLIENT PREFLIGHT VERIFICATION SUCCESS' -ForegroundColor Green
}
finally {
    $env:Path = $OldPath
    $env:APPDATA = $OldAppData
    $env:LOCALAPPDATA = $OldLocalAppData
    $env:USERPROFILE = $OldUserProfile
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
