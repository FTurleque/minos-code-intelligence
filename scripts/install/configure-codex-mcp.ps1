[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $InstallRoot,

    [ValidateSet('Install', 'Uninstall')]
    [string] $Action = 'Install',

    [switch] $Strict,

    [ValidateRange(1, 60)]
    [int] $NativeCommandTimeoutSeconds = 10,

    [string] $DataRoot = '',
    [string] $ConfigPath = '',
    [string] $StatePath = '',
    [string] $LogPath = '',
    [string] $BackupRoot = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($env:OS -ne 'Windows_NT') {
    throw 'MINOS Codex MCP integration currently targets Windows hosts.'
}

$InstallRoot = [System.IO.Path]::GetFullPath($InstallRoot)
$MinosExe = Join-Path $InstallRoot 'app\minos.exe'
if ($Action -eq 'Install' -and -not (Test-Path -LiteralPath $MinosExe -PathType Leaf)) {
    throw "MINOS native executable not found: $MinosExe"
}

$LocalAppData = [Environment]::GetFolderPath('LocalApplicationData')
$UserProfile = [Environment]::GetFolderPath('UserProfile')
if ([string]::IsNullOrWhiteSpace($DataRoot)) { $DataRoot = Join-Path $LocalAppData 'MINOS\data' }
if ([string]::IsNullOrWhiteSpace($ConfigPath)) { $ConfigPath = Join-Path $UserProfile '.codex\config.toml' }
if ([string]::IsNullOrWhiteSpace($StatePath)) { $StatePath = Join-Path $LocalAppData 'MINOS\codex-mcp-integration.json' }
if ([string]::IsNullOrWhiteSpace($LogPath)) { $LogPath = Join-Path $LocalAppData 'MINOS\mcp-clients.log' }
if ([string]::IsNullOrWhiteSpace($BackupRoot)) { $BackupRoot = Join-Path $LocalAppData 'MINOS\backups\mcp-clients' }

$DataRoot = [System.IO.Path]::GetFullPath($DataRoot)
$ConfigPath = [System.IO.Path]::GetFullPath($ConfigPath)
$StatePath = [System.IO.Path]::GetFullPath($StatePath)
$LogPath = [System.IO.Path]::GetFullPath($LogPath)
$BackupRoot = [System.IO.Path]::GetFullPath($BackupRoot)
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $LogPath) | Out-Null

function Write-Log([string] $Message) {
    Add-Content -LiteralPath $LogPath -Value ('{0} {1}' -f ([DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')), $Message) -Encoding UTF8
}

function Fail-Or-Warn([string] $Message) {
    Write-Log "WARN $Message"
    if ($Strict) { throw $Message }
    Write-Warning $Message
}

function Resolve-CommandPath([string] $Name) {
    $Command = Get-Command $Name -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -eq $Command) { return '' }
    if (-not [string]::IsNullOrWhiteSpace([string]$Command.Source)) { return [string]$Command.Source }
    if ($Command.PSObject.Properties['Path'] -and -not [string]::IsNullOrWhiteSpace([string]$Command.Path)) { return [string]$Command.Path }
    return [string]$Command.Name
}

function Invoke-Capture([string] $File, [string[]] $Arguments) {
    $Payload = [pscustomobject][ordered]@{ file = $File; arguments = @($Arguments) } | ConvertTo-Json -Depth 4 -Compress
    $Payload64 = [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($Payload))
    $Script = @'
$ErrorActionPreference='Continue'
try {
  $r=[System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($env:MINOS_CODEX_INVOCATION))|ConvertFrom-Json
  $a=@($r.arguments|ForEach-Object{[string]$_})
  & ([string]$r.file) @a
  $c=$LASTEXITCODE
  if($null -eq $c){$c=if($?){0}else{1}}
  exit([int]$c)
}catch{[Console]::Error.WriteLine($_.Exception.Message);exit 1}
'@
    $Encoded = [Convert]::ToBase64String([System.Text.Encoding]::Unicode.GetBytes($Script))
    $HostPath = Join-Path ([Environment]::GetFolderPath('System')) 'WindowsPowerShell\v1.0\powershell.exe'
    if (-not (Test-Path -LiteralPath $HostPath -PathType Leaf)) { $HostPath = (Get-Command powershell.exe -ErrorAction Stop).Source }
    $Info = New-Object System.Diagnostics.ProcessStartInfo
    $Info.FileName = $HostPath
    $Info.Arguments = "-NoLogo -NoProfile -NonInteractive -EncodedCommand $Encoded"
    $Info.UseShellExecute = $false
    $Info.CreateNoWindow = $true
    $Info.RedirectStandardInput = $true
    $Info.RedirectStandardOutput = $true
    $Info.RedirectStandardError = $true
    $Info.EnvironmentVariables['MINOS_CODEX_INVOCATION'] = $Payload64
    $Process = New-Object System.Diagnostics.Process
    $Process.StartInfo = $Info
    try {
        if (-not $Process.Start()) { return [pscustomobject]@{ ExitCode = -1; Output = 'process did not start' } }
        $Process.StandardInput.Close()
        $Out = $Process.StandardOutput.ReadToEndAsync()
        $Err = $Process.StandardError.ReadToEndAsync()
        if (-not $Process.WaitForExit($NativeCommandTimeoutSeconds * 1000)) {
            try { & (Join-Path ([Environment]::SystemDirectory) 'taskkill.exe') /PID ([string]$Process.Id) /T /F 2>&1 | Out-Null } catch { }
            try { if (-not $Process.HasExited) { $Process.Kill() } } catch { }
            return [pscustomobject]@{ ExitCode = -3; Output = "command timed out after $NativeCommandTimeoutSeconds seconds" }
        }
        $Process.WaitForExit()
        $Parts = @()
        if (-not [string]::IsNullOrWhiteSpace($Out.Result)) { $Parts += $Out.Result.Trim() }
        if (-not [string]::IsNullOrWhiteSpace($Err.Result)) { $Parts += $Err.Result.Trim() }
        return [pscustomobject]@{ ExitCode = $Process.ExitCode; Output = ($Parts -join [Environment]::NewLine) }
    }
    finally { $Process.Dispose() }
}

function Read-State {
    if (-not (Test-Path -LiteralPath $StatePath -PathType Leaf)) { return $null }
    try { return Get-Content -Raw -LiteralPath $StatePath | ConvertFrom-Json }
    catch { Fail-Or-Warn "Codex MCP state is invalid: $($_.Exception.Message)"; return $null }
}

function Write-State([object] $State) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $StatePath) | Out-Null
    [System.IO.File]::WriteAllText(
        $StatePath,
        (($State | ConvertTo-Json -Depth 8) + [Environment]::NewLine),
        [System.Text.UTF8Encoding]::new($false))
}

function Backup-File([string] $Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return '' }
    $Directory = Join-Path $BackupRoot ([DateTime]::UtcNow.ToString('yyyyMMdd-HHmmssfff'))
    New-Item -ItemType Directory -Force -Path $Directory | Out-Null
    $Target = Join-Path $Directory ('codex-' + [System.IO.Path]::GetFileName($Path))
    Copy-Item -LiteralPath $Path -Destination $Target -Force
    Write-Log "BACKUP client=codex source='$Path' target='$Target'"
    return $Target
}

function Text-Hash([string] $Text) {
    $Sha = [System.Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($Sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($Text))).Replace('-', '').ToLowerInvariant()) }
    finally { $Sha.Dispose() }
}

function Toml-String([string] $Value) {
    return '"' + $Value.Replace('\', '\\').Replace('"', '\"').Replace("`r", '\r').Replace("`n", '\n') + '"'
}

$BeginMarker = '# BEGIN MINOS MANAGED MCP SERVER'
$EndMarker = '# END MINOS MANAGED MCP SERVER'
function New-TomlBlock {
    return @(
        $BeginMarker,
        '[mcp_servers.minos]',
        'command = ' + (Toml-String $MinosExe),
        'args = ["mcp"]',
        'enabled = true',
        '',
        '[mcp_servers.minos.env]',
        'MINOS_HOME = ' + (Toml-String $DataRoot),
        $EndMarker
    ) -join [Environment]::NewLine
}

function Get-ManagedBlock([string] $Text) {
    $Pattern = '(?ms)^\s*' + [regex]::Escape($BeginMarker) + '.*?' + [regex]::Escape($EndMarker) + '\s*'
    $Match = [regex]::Match($Text, $Pattern)
    if (-not $Match.Success) { return '' }
    return $Match.Value.Trim()
}

function Install-Toml {
    $State = Read-State
    $Text = if (Test-Path -LiteralPath $ConfigPath -PathType Leaf) {
        [System.IO.File]::ReadAllText($ConfigPath, [System.Text.Encoding]::UTF8)
    } else { '' }
    $Expected = New-TomlBlock
    $CurrentManaged = Get-ManagedBlock $Text

    if (-not [string]::IsNullOrWhiteSpace($CurrentManaged)) {
        if ((Text-Hash $CurrentManaged) -eq (Text-Hash $Expected)) {
            if ($null -eq $State) {
                Write-State ([pscustomobject][ordered]@{ mode='toml'; ownership='preexisting'; configPath=$ConfigPath; blockHash=(Text-Hash $Expected); command=$MinosExe; dataRoot=$DataRoot })
            }
            Write-Log "KEEP client=codex mode=toml reason=already-compatible"
            Write-Host 'MINOS MCP already configured for Codex Desktop' -ForegroundColor Green
            return
        }
        if ($null -eq $State -or [string]$State.mode -ne 'toml' -or [string]$State.blockHash -ne (Text-Hash $CurrentManaged)) {
            Fail-Or-Warn 'Codex config contains a MINOS-managed marker block that no longer matches MINOS state; preserving it.'
            return
        }
        Backup-File $ConfigPath | Out-Null
        $Text = [regex]::Replace($Text, '(?ms)^\s*' + [regex]::Escape($BeginMarker) + '.*?' + [regex]::Escape($EndMarker) + '\s*', '')
    } elseif ($Text -match '(?m)^\s*\[mcp_servers\.minos\]\s*$') {
        Fail-Or-Warn "Codex config already contains an unmanaged [mcp_servers.minos] section; MINOS did not overwrite it."
        return
    } else {
        Backup-File $ConfigPath | Out-Null
    }

    $Parent = Split-Path -Parent $ConfigPath
    New-Item -ItemType Directory -Force -Path $Parent | Out-Null
    $Prefix = $Text.TrimEnd()
    $NewText = if ([string]::IsNullOrWhiteSpace($Prefix)) { $Expected + [Environment]::NewLine } else { $Prefix + [Environment]::NewLine + [Environment]::NewLine + $Expected + [Environment]::NewLine }
    [System.IO.File]::WriteAllText($ConfigPath, $NewText, [System.Text.UTF8Encoding]::new($false))
    Write-State ([pscustomobject][ordered]@{ mode='toml'; ownership='managed'; configPath=$ConfigPath; blockHash=(Text-Hash $Expected); command=$MinosExe; dataRoot=$DataRoot })
    Write-Log "INSTALL client=codex mode=toml path='$ConfigPath'"
    Write-Host 'MINOS MCP configured for Codex Desktop' -ForegroundColor Green
}

function Uninstall-Toml([object] $State) {
    if ([string]$State.ownership -eq 'preexisting') { Remove-Item -LiteralPath $StatePath -Force -ErrorAction SilentlyContinue; return }
    $Path = [string]$State.configPath
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { Remove-Item -LiteralPath $StatePath -Force -ErrorAction SilentlyContinue; return }
    $Text = [System.IO.File]::ReadAllText($Path, [System.Text.Encoding]::UTF8)
    $Current = Get-ManagedBlock $Text
    if ([string]::IsNullOrWhiteSpace($Current)) { Remove-Item -LiteralPath $StatePath -Force -ErrorAction SilentlyContinue; return }
    if ((Text-Hash $Current) -ne [string]$State.blockHash) {
        Fail-Or-Warn 'Codex MINOS block was modified after installation; preserving the current user configuration.'
        return
    }
    Backup-File $Path | Out-Null
    $NewText = [regex]::Replace($Text, '(?ms)^\s*' + [regex]::Escape($BeginMarker) + '.*?' + [regex]::Escape($EndMarker) + '\s*', '').TrimEnd()
    if ([string]::IsNullOrWhiteSpace($NewText)) { [System.IO.File]::WriteAllText($Path, '', [System.Text.UTF8Encoding]::new($false)) }
    else { [System.IO.File]::WriteAllText($Path, $NewText + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false)) }
    Remove-Item -LiteralPath $StatePath -Force -ErrorAction SilentlyContinue
    Write-Log "UNINSTALL client=codex mode=toml path='$Path'"
}

function Test-CliMatches([object] $Probe) {
    if ($Probe.ExitCode -ne 0) { return $false }
    $Text = ([string]$Probe.Output).Replace('\\', '\')
    return $Text.IndexOf($MinosExe, [StringComparison]::OrdinalIgnoreCase) -ge 0 -and
        $Text.IndexOf($DataRoot, [StringComparison]::OrdinalIgnoreCase) -ge 0
}

function Install-Cli([string] $CodexPath) {
    $State = Read-State
    $Probe = Invoke-Capture $CodexPath @('mcp','get','minos')
    if ($Probe.ExitCode -eq 0) {
        if (Test-CliMatches $Probe) {
            $Ownership = if ($null -eq $State) { 'preexisting' } else { [string]$State.ownership }
            Write-State ([pscustomobject][ordered]@{ mode='cli'; ownership=$Ownership; toolPath=$CodexPath; command=$MinosExe; dataRoot=$DataRoot })
            Write-Log "KEEP client=codex mode=cli reason=already-compatible tool='$CodexPath'"
            return
        }
        Fail-Or-Warn "Codex already contains a different MCP server named 'minos'; MINOS did not overwrite it."
        return
    }
    $Add = Invoke-Capture $CodexPath @('mcp','add','minos','--env',"MINOS_HOME=$DataRoot",'--',$MinosExe,'mcp')
    if ($Add.ExitCode -ne 0) { throw "Codex MCP add failed (exit=$($Add.ExitCode)): $($Add.Output)" }
    Write-State ([pscustomobject][ordered]@{ mode='cli'; ownership='managed'; toolPath=$CodexPath; command=$MinosExe; dataRoot=$DataRoot })
    Write-Log "INSTALL client=codex mode=cli tool='$CodexPath'"
}

function Uninstall-Cli([object] $State) {
    if ([string]$State.ownership -eq 'preexisting') { Remove-Item -LiteralPath $StatePath -Force -ErrorAction SilentlyContinue; return }
    $ToolPath = [string]$State.toolPath
    if ([string]::IsNullOrWhiteSpace($ToolPath) -or -not (Test-Path -LiteralPath $ToolPath -PathType Leaf)) { $ToolPath = Resolve-CommandPath 'codex' }
    if ([string]::IsNullOrWhiteSpace($ToolPath)) { Fail-Or-Warn 'Cannot remove MINOS from Codex: the saved Codex CLI and PATH command are unavailable.'; return }
    $Probe = Invoke-Capture $ToolPath @('mcp','get','minos')
    if ($Probe.ExitCode -ne 0) { Remove-Item -LiteralPath $StatePath -Force -ErrorAction SilentlyContinue; return }
    if (-not (Test-CliMatches $Probe)) { Fail-Or-Warn 'Codex MCP entry minos no longer matches the entry managed by MINOS; preserving it.'; return }
    $Remove = Invoke-Capture $ToolPath @('mcp','remove','minos')
    if ($Remove.ExitCode -ne 0) { throw "Codex MCP remove failed (exit=$($Remove.ExitCode)): $($Remove.Output)" }
    Remove-Item -LiteralPath $StatePath -Force -ErrorAction SilentlyContinue
    Write-Log "UNINSTALL client=codex mode=cli tool='$ToolPath'"
}

Write-Log "BEGIN client=codex action=$Action installRoot='$InstallRoot' dataRoot='$DataRoot'"
try {
    if ($Action -eq 'Install') {
        $CodexPath = Resolve-CommandPath 'codex'
        $Capability = if ([string]::IsNullOrWhiteSpace($CodexPath)) { $null } else { Invoke-Capture $CodexPath @('mcp','--help') }
        if ($null -ne $Capability -and $Capability.ExitCode -eq 0) { Install-Cli $CodexPath }
        else { Install-Toml }
    } else {
        $State = Read-State
        if ($null -eq $State) { return }
        if ([string]$State.mode -eq 'cli') { Uninstall-Cli $State }
        elseif ([string]$State.mode -eq 'toml') { Uninstall-Toml $State }
        else { Fail-Or-Warn "Unknown Codex MCP integration mode '$($State.mode)'." }
    }
    Write-Log "END client=codex action=$Action status=success"
}
catch {
    Write-Log "END client=codex action=$Action status=failed error='$($_.Exception.Message)'"
    if ($Strict) { throw }
    Write-Warning "MINOS Codex integration failed: $($_.Exception.Message)"
}
