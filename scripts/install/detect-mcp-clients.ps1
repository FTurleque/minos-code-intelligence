[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $OutputPath,

    [ValidateRange(1, 30)]
    [int] $ProbeTimeoutSeconds = 5,

    # Managed-integration state written by configure-mcp-clients.ps1 /
    # configure-codex-mcp.ps1. Read-only here: this script only reports whether
    # a client is already MINOS-managed, it never writes or repairs state.
    [string] $StatePath = '',
    [string] $CodexStatePath = '',

    # When supplied, AlreadyManaged additionally requires the tracked entry's
    # command/dataRoot to match this exact install -- a client tracked under a
    # different install root or data root (moved install, changed data root)
    # is reported as needing configuration again, not as already-managed.
    # Left blank, AlreadyManaged falls back to "tracked at all", matching every
    # existing caller that predates this parameter.
    [string] $InstallRoot = '',
    [string] $DataRoot = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($env:OS -ne 'Windows_NT') {
    throw 'MINOS MCP client preflight currently targets Windows hosts.'
}

# Repository PowerShell scripts are UTF-8 without BOM. Windows PowerShell 5.1
# parses such files using the legacy ANSI code page, so keep this source file
# ASCII-only and construct the few required Unicode UI characters at runtime.
# The generated INI is UTF-16 LE with BOM, which is the native Unicode format
# consumed reliably by Windows/Inno Setup INI APIs.
function Ui([string] $Value) {
    $EAcute = [string][char]0x00E9
    $EmDash = [string][char]0x2014
    return $Value.Replace('{e}', $EAcute).Replace('{dash}', $EmDash)
}

# Reads the corresponding environment variable first, falling back to the
# WinAPI special-folder lookup only if it is unset. On any real Windows
# session these are identical (both ultimately derive from the same user
# profile), but the env var is overridable per-process, which is what lets a
# test harness isolate this script's filesystem-marker detection from
# whatever happens to actually be installed on the host running the test.
function Get-UserFolderPath([string] $EnvironmentVariableName, [string] $SpecialFolder) {
    $Value = [Environment]::GetEnvironmentVariable($EnvironmentVariableName)
    if (-not [string]::IsNullOrWhiteSpace($Value)) { return $Value }
    return [Environment]::GetFolderPath($SpecialFolder)
}

if ([string]::IsNullOrWhiteSpace($StatePath)) {
    $StatePath = Join-Path (Get-UserFolderPath 'LOCALAPPDATA' 'LocalApplicationData') 'MINOS\mcp-client-integrations.json'
}
if ([string]::IsNullOrWhiteSpace($CodexStatePath)) {
    $CodexStatePath = Join-Path (Get-UserFolderPath 'LOCALAPPDATA' 'LocalApplicationData') 'MINOS\codex-mcp-integration.json'
}

# Both blank unless the caller (the installer wizard) supplied them -- console/test callers
# that predate -InstallRoot/-DataRoot keep the pre-existing "tracked at all" behavior.
$ExpectedExe = if (-not [string]::IsNullOrWhiteSpace($InstallRoot)) {
    [System.IO.Path]::GetFullPath((Join-Path $InstallRoot 'app\minos.exe'))
} else { $null }
$ExpectedDataRoot = if (-not [string]::IsNullOrWhiteSpace($DataRoot)) {
    [System.IO.Path]::GetFullPath($DataRoot)
} else { $null }

function Test-TrackedWiringCurrent([object] $Entry) {
    if ($null -eq $ExpectedExe) { return $true }
    $Command = [string]$Entry.command
    $TrackedDataRoot = [string]$Entry.dataRoot
    if ([string]::IsNullOrWhiteSpace($Command)) { return $false }
    if (-not $Command.Equals($ExpectedExe, [StringComparison]::OrdinalIgnoreCase)) { return $false }
    if ([string]::IsNullOrWhiteSpace($TrackedDataRoot)) { return $true }
    return $TrackedDataRoot.Equals($ExpectedDataRoot, [StringComparison]::OrdinalIgnoreCase)
}

# Ids already tracked in mcp-client-integrations.json, managed or preexisting alike -- both
# mean MINOS already confirmed this client is correctly wired, which is exactly what the
# wizard needs to tell the user apart from "merely capable of being configured". A tracked
# entry whose command/dataRoot no longer matches this exact install (moved install, changed
# data root) does not count -- that client still needs (re)configuration, not a locked
# already-done checkbox.
function Get-ManagedClientIds([string] $Path) {
    $Ids = New-Object System.Collections.Generic.HashSet[string]
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return , $Ids }
    try { $Parsed = Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json }
    catch { return , $Ids }
    foreach ($Client in @($Parsed.clients)) {
        $Id = [string]$Client.id
        if (-not [string]::IsNullOrWhiteSpace($Id) -and (Test-TrackedWiringCurrent $Client)) { [void]$Ids.Add($Id) }
    }
    # Unary comma: see the note in update-installation.ps1 -- without it a
    # single-entry (or empty) HashSet is unrolled on return, and .Contains()
    # at the caller fails.
    return , $Ids
}

# codex-mcp-integration.json is a single tracked object, not a clients[] array.
function Test-CodexManaged([string] $Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $false }
    try { $Parsed = Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json }
    catch { return $false }
    if (-not (Test-TrackedWiringCurrent $Parsed)) { return $false }
    return -not [string]::IsNullOrWhiteSpace([string]$Parsed.ownership)
}

$ManagedClientIds = Get-ManagedClientIds $StatePath
$CodexManaged = Test-CodexManaged $CodexStatePath

function Resolve-CommandPath([string] $Name) {
    $Command = Get-Command $Name -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -eq $Command) { return '' }
    if (-not [string]::IsNullOrWhiteSpace([string]$Command.Source)) { return [string]$Command.Source }
    if ($Command.PSObject.Properties['Path'] -and -not [string]::IsNullOrWhiteSpace([string]$Command.Path)) {
        return [string]$Command.Path
    }
    return [string]$Command.Name
}

function Test-VsCodeCopilotShim([string] $Path) {
    if ([string]::IsNullOrWhiteSpace($Path)) { return $false }
    return $Path -match '(?i)(Microsoft VS Code|\\Code\\bin\\|\\.vscode\\|vscode)'
}

function Find-EmbeddedClaudeCli {
    # Claude Code Desktop ships its own claude.exe under
    # %APPDATA%\Claude\claude-code\<version>\claude.exe -- never on PATH.
    $ClaudeCodeDir = Join-Path (Get-UserFolderPath 'APPDATA' 'ApplicationData') 'Claude\claude-code'
    if (-not (Test-Path -LiteralPath $ClaudeCodeDir -PathType Container)) { return '' }
    foreach ($Dir in @(Get-ChildItem -LiteralPath $ClaudeCodeDir -Directory -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending)) {
        $Exe = Join-Path $Dir.FullName 'claude.exe'
        if (Test-Path -LiteralPath $Exe -PathType Leaf) { return $Exe }
    }
    return ''
}

function Resolve-ProbePowerShell([string] $ToolPath) {
    # npm/global CLIs are commonly exposed as .ps1 shims. Use PowerShell 7 for
    # those launchers, matching the actual integration manager; Windows
    # PowerShell 5.1 can otherwise reject a valid modern CLI or its syntax.
    if ([System.IO.Path]::GetExtension($ToolPath).Equals('.ps1', [StringComparison]::OrdinalIgnoreCase)) {
        $Pwsh = Get-Command pwsh.exe -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($null -eq $Pwsh) { $Pwsh = Get-Command pwsh -ErrorAction SilentlyContinue | Select-Object -First 1 }
        if ($null -eq $Pwsh) { return '' }
        return [string]$Pwsh.Source
    }

    $WindowsPowerShell = Join-Path ([Environment]::GetFolderPath('System')) 'WindowsPowerShell\v1.0\powershell.exe'
    if (Test-Path -LiteralPath $WindowsPowerShell -PathType Leaf) { return $WindowsPowerShell }
    $Fallback = Get-Command powershell.exe -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -eq $Fallback) { return '' }
    return [string]$Fallback.Source
}

function Invoke-CapabilityProbe([string] $File, [string[]] $Arguments) {
    if ([string]::IsNullOrWhiteSpace($File)) {
        return [pscustomobject]@{ ExitCode = -1; Output = '' }
    }

    $HostPath = Resolve-ProbePowerShell -ToolPath $File
    if ([string]::IsNullOrWhiteSpace($HostPath)) {
        return [pscustomobject]@{
            ExitCode = -2
            Output = if ([System.IO.Path]::GetExtension($File) -ieq '.ps1') {
                "PowerShell 7 (pwsh) is required to probe the CLI launcher '$File'."
            } else {
                'No PowerShell host is available for the MCP capability probe.'
            }
        }
    }

    $Payload = [pscustomobject][ordered]@{
        file = $File
        arguments = @($Arguments)
    } | ConvertTo-Json -Depth 4 -Compress
    $PayloadBase64 = [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($Payload))
    $ChildScript = @'
$ErrorActionPreference = 'Continue'
try {
    $request = [System.Text.Encoding]::UTF8.GetString(
        [Convert]::FromBase64String($env:MINOS_MCP_PREFLIGHT)) | ConvertFrom-Json
    $invokeArgs = @($request.arguments | ForEach-Object { [string]$_ })
    & ([string]$request.file) @invokeArgs
    $code = $LASTEXITCODE
    if ($null -eq $code) { $code = if ($?) { 0 } else { 1 } }
    exit ([int]$code)
}
catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}
'@
    $Encoded = [Convert]::ToBase64String([System.Text.Encoding]::Unicode.GetBytes($ChildScript))

    $Info = New-Object System.Diagnostics.ProcessStartInfo
    $Info.FileName = $HostPath
    $Info.Arguments = "-NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand $Encoded"
    $Info.UseShellExecute = $false
    $Info.CreateNoWindow = $true
    $Info.RedirectStandardInput = $true
    $Info.RedirectStandardOutput = $true
    $Info.RedirectStandardError = $true
    $Info.EnvironmentVariables['MINOS_MCP_PREFLIGHT'] = $PayloadBase64

    $Process = New-Object System.Diagnostics.Process
    $Process.StartInfo = $Info
    try {
        if (-not $Process.Start()) {
            return [pscustomobject]@{ ExitCode = -1; Output = 'process did not start' }
        }
        $Process.StandardInput.Close()
        $StdOut = $Process.StandardOutput.ReadToEndAsync()
        $StdErr = $Process.StandardError.ReadToEndAsync()
        if (-not $Process.WaitForExit($ProbeTimeoutSeconds * 1000)) {
            try { & (Join-Path ([Environment]::SystemDirectory) 'taskkill.exe') /PID ([string]$Process.Id) /T /F 2>&1 | Out-Null } catch { }
            try { if (-not $Process.HasExited) { $Process.Kill() } } catch { }
            return [pscustomobject]@{ ExitCode = -3; Output = "probe timed out after $ProbeTimeoutSeconds seconds" }
        }
        $Process.WaitForExit()
        $Parts = @()
        if (-not [string]::IsNullOrWhiteSpace($StdOut.Result)) { $Parts += $StdOut.Result.Trim() }
        if (-not [string]::IsNullOrWhiteSpace($StdErr.Result)) { $Parts += $StdErr.Result.Trim() }
        return [pscustomobject]@{ ExitCode = $Process.ExitCode; Output = ($Parts -join [Environment]::NewLine) }
    }
    finally {
        $Process.Dispose()
    }
}

function Test-Capability([string] $ToolPath, [string[]] $Arguments) {
    if ([string]::IsNullOrWhiteSpace($ToolPath)) { return $false }
    return (Invoke-CapabilityProbe -File $ToolPath -Arguments $Arguments).ExitCode -eq 0
}

function Test-JetBrainsCopilot {
    $LocalAppData = Get-UserFolderPath 'LOCALAPPDATA' 'LocalApplicationData'
    $Roaming = Get-UserFolderPath 'APPDATA' 'ApplicationData'
    if (Test-Path -LiteralPath (Join-Path $LocalAppData 'github-copilot\intellij')) { return $true }

    foreach ($Root in @((Join-Path $Roaming 'JetBrains'), (Join-Path $LocalAppData 'JetBrains'))) {
        if (-not (Test-Path -LiteralPath $Root -PathType Container)) { continue }
        foreach ($Product in @(Get-ChildItem -LiteralPath $Root -Directory -ErrorAction SilentlyContinue)) {
            $Plugins = Join-Path $Product.FullName 'plugins'
            if (-not (Test-Path -LiteralPath $Plugins -PathType Container)) { continue }
            if (Get-ChildItem -LiteralPath $Plugins -Directory -ErrorAction SilentlyContinue |
                    Where-Object { $_.Name -match '(?i)copilot' } |
                    Select-Object -First 1) { return $true }
        }
    }
    return $false
}

function Test-ClaudeCodeDesktop {
    # Claude Code Desktop (claude.ai/code app) creates ~/.claude/ with these marker files.
    $UserProfile = Get-UserFolderPath 'USERPROFILE' 'UserProfile'
    foreach ($Marker in @('.claude\settings.json', '.claude\.credentials.json')) {
        if (Test-Path -LiteralPath (Join-Path $UserProfile $Marker) -PathType Leaf) {
            return $true
        }
    }
    return $false
}

function Find-ClaudeDesktopMsixDir {
    # Claude Desktop installed from the Windows Store (MSIX) uses a sandboxed
    # LocalCache path instead of %APPDATA%\Claude.
    # Pattern: %LOCALAPPDATA%\Packages\Claude_<publisher>\LocalCache\Roaming\Claude
    $Local = Get-UserFolderPath 'LOCALAPPDATA' 'LocalApplicationData'
    $PackagesDir = Join-Path $Local 'Packages'
    if (-not (Test-Path -LiteralPath $PackagesDir -PathType Container)) { return '' }
    foreach ($Dir in @(Get-ChildItem -LiteralPath $PackagesDir -Directory -Filter 'Claude_*' -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending)) {
        $CandidateDir = Join-Path $Dir.FullName 'LocalCache\Roaming\Claude'
        if (Test-Path -LiteralPath $CandidateDir -PathType Container) { return $CandidateDir }
    }
    return ''
}

function Test-ClaudeDesktop {
    $Roaming = Get-UserFolderPath 'APPDATA' 'ApplicationData'
    $Local = Get-UserFolderPath 'LOCALAPPDATA' 'LocalApplicationData'
    # Windows Store (MSIX) installation — sandboxed LocalCache path.
    if (-not [string]::IsNullOrWhiteSpace((Find-ClaudeDesktopMsixDir))) {
        return $true
    }
    # Traditional Claude Desktop standalone exe path.
    if (Test-Path -LiteralPath (Join-Path $Local 'Programs\Claude\Claude.exe') -PathType Leaf) {
        return $true
    }
    # %APPDATA%\Claude exists but only counts as traditional Claude Desktop when the
    # Claude Code Desktop markers are absent — both apps write to %APPDATA%\Claude.
    if ((Test-Path -LiteralPath (Join-Path $Roaming 'Claude') -PathType Container) -and
        -not (Test-ClaudeCodeDesktop)) {
        return $true
    }
    return $false
}

function Test-CodexDesktop {
    $Local = Get-UserFolderPath 'LOCALAPPDATA' 'LocalApplicationData'
    $HomeDir = Get-UserFolderPath 'USERPROFILE' 'UserProfile'
    return (Test-Path -LiteralPath (Join-Path $HomeDir '.codex\config.toml') -PathType Leaf) -or
        (Test-Path -LiteralPath (Join-Path $Local 'Programs\Codex\Codex.exe') -PathType Leaf) -or
        (Test-Path -LiteralPath (Join-Path $Local 'Codex\Codex.exe') -PathType Leaf)
}

function New-Result([bool] $Available, [string] $Reason, [string] $Mode = '', [bool] $AlreadyManaged = $false) {
    return [pscustomobject][ordered]@{
        Available = $Available
        Reason = $Reason
        Mode = $Mode
        AlreadyManaged = $AlreadyManaged
    }
}

$Results = [ordered]@{}

if (Test-JetBrainsCopilot) {
    $Results['CopilotJetBrains'] = New-Result $true (Ui 'D{e}tect{e}') 'json' ($ManagedClientIds.Contains('copilot-jetbrains'))
} else {
    $Results['CopilotJetBrains'] = New-Result $false (Ui 'Non disponible {dash} GitHub Copilot JetBrains / IntelliJ non d{e}tect{e}')
}

$Copilot = Resolve-CommandPath 'copilot'
if ([string]::IsNullOrWhiteSpace($Copilot)) {
    $Results['CopilotCli'] = New-Result $false (Ui 'Non disponible {dash} commande introuvable')
} elseif (Test-VsCodeCopilotShim $Copilot) {
    # Reject known editor shims before any command probe. Some wrappers may
    # return success for generic help while not being the standalone Copilot CLI.
    $Results['CopilotCli'] = New-Result $false (Ui 'Non disponible {dash} launcher VS Code d{e}tect{e}, vrai CLI absent')
} elseif (Test-Capability $Copilot @('mcp', '--help')) {
    $Results['CopilotCli'] = New-Result $true (Ui 'D{e}tect{e} {dash} CLI MCP compatible') 'cli' ($ManagedClientIds.Contains('copilot-cli'))
} else {
    $Results['CopilotCli'] = New-Result $false (Ui 'Non disponible {dash} commande copilot d{e}tect{e}e mais interface MCP incompatible')
}

$Claude = Resolve-CommandPath 'claude'
if ([string]::IsNullOrWhiteSpace($Claude)) {
    $Claude = Find-EmbeddedClaudeCli
}
if (-not [string]::IsNullOrWhiteSpace($Claude) -and (Test-Capability $Claude @('mcp', '--help'))) {
    $Results['ClaudeCode'] = New-Result $true (Ui 'D{e}tect{e} {dash} Claude CLI / Code compatible MCP') 'cli' ($ManagedClientIds.Contains('claude-code'))
} elseif (-not [string]::IsNullOrWhiteSpace($Claude)) {
    $Results['ClaudeCode'] = New-Result $false (Ui 'Non disponible {dash} commande claude d{e}tect{e}e mais interface MCP incompatible')
} else {
    $Results['ClaudeCode'] = New-Result $false (Ui 'Non disponible {dash} Claude CLI / Code introuvable')
}

if (Test-ClaudeDesktop) {
    $Results['ClaudeDesktop'] = New-Result $true (Ui 'D{e}tect{e}') 'json' ($ManagedClientIds.Contains('claude-desktop'))
} else {
    $Results['ClaudeDesktop'] = New-Result $false (Ui 'Non disponible {dash} Claude Desktop non d{e}tect{e}')
}

$Codex = Resolve-CommandPath 'codex'
$CodexCliAvailable = -not [string]::IsNullOrWhiteSpace($Codex) -and (Test-Capability $Codex @('mcp', '--help'))
if ($CodexCliAvailable) {
    $Results['CodexCli'] = New-Result $true (Ui 'D{e}tect{e} {dash} Codex CLI MCP compatible') 'cli' $CodexManaged
} elseif (-not [string]::IsNullOrWhiteSpace($Codex)) {
    $Results['CodexCli'] = New-Result $false (Ui 'Non disponible {dash} commande codex d{e}tect{e}e mais interface MCP incompatible')
} else {
    $Results['CodexCli'] = New-Result $false (Ui 'Non disponible {dash} Codex CLI introuvable')
}

$CodexDesktopAvailable = Test-CodexDesktop
if ($CodexDesktopAvailable) {
    $Results['CodexDesktop'] = New-Result $true (Ui 'D{e}tect{e} {dash} configuration Codex Desktop disponible') 'desktop' $CodexManaged
} else {
    $Results['CodexDesktop'] = New-Result $false (Ui 'Non disponible {dash} Codex Desktop / config utilisateur non d{e}tect{e}')
}

# Backward-compatible aggregate section retained for existing verifier fixtures and
# non-wizard consumers. New installer UI uses CodexCli and CodexDesktop explicitly.
if ($CodexCliAvailable) {
    $Results['Codex'] = New-Result $true (Ui 'D{e}tect{e} {dash} Codex CLI') 'cli' $CodexManaged
} elseif ($CodexDesktopAvailable) {
    $Results['Codex'] = New-Result $true (Ui 'D{e}tect{e} {dash} Codex Desktop (configuration via fichier utilisateur)') 'desktop' $CodexManaged
} elseif (-not [string]::IsNullOrWhiteSpace($Codex)) {
    $Results['Codex'] = New-Result $false (Ui 'Non disponible {dash} commande codex d{e}tect{e}e mais interface MCP incompatible')
} else {
    $Results['Codex'] = New-Result $false (Ui 'Non disponible {dash} Codex CLI / Desktop non d{e}tect{e}')
}

$Parent = Split-Path -Parent ([System.IO.Path]::GetFullPath($OutputPath))
if (-not [string]::IsNullOrWhiteSpace($Parent)) {
    New-Item -ItemType Directory -Force -Path $Parent | Out-Null
}
$Lines = New-Object System.Collections.Generic.List[string]
foreach ($Name in $Results.Keys) {
    $Value = $Results[$Name]
    $Lines.Add("[$Name]")
    $Lines.Add('Available=' + $(if ($Value.Available) { '1' } else { '0' }))
    $Lines.Add('Reason=' + ([string]$Value.Reason).Replace("`r", ' ').Replace("`n", ' '))
    $Lines.Add('Mode=' + [string]$Value.Mode)
    $Lines.Add('AlreadyManaged=' + $(if ($Value.AlreadyManaged) { '1' } else { '0' }))
    $Lines.Add('')
}
[System.IO.File]::WriteAllLines(
    [System.IO.Path]::GetFullPath($OutputPath),
    $Lines,
    [System.Text.Encoding]::Unicode)

Write-Host "MINOS MCP client preflight written: $OutputPath"
foreach ($Name in $Results.Keys) {
    $Value = $Results[$Name]
    Write-Host ("{0}: available={1} mode={2} alreadyManaged={3} reason={4}" -f $Name, $Value.Available, $Value.Mode, $Value.AlreadyManaged, $Value.Reason)
}
