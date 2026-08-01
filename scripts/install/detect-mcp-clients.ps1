[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $OutputPath,

    [ValidateRange(1, 30)]
    [int] $ProbeTimeoutSeconds = 5
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($env:OS -ne 'Windows_NT') {
    throw 'MINOS MCP client preflight currently targets Windows hosts.'
}

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

function Invoke-CapabilityProbe([string] $File, [string[]] $Arguments) {
    if ([string]::IsNullOrWhiteSpace($File)) {
        return [pscustomobject]@{ ExitCode = -1; Output = '' }
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
    $HostPath = Join-Path ([Environment]::GetFolderPath('System')) 'WindowsPowerShell\v1.0\powershell.exe'
    if (-not (Test-Path -LiteralPath $HostPath -PathType Leaf)) {
        $HostPath = (Get-Command powershell.exe -ErrorAction Stop).Source
    }

    $Info = New-Object System.Diagnostics.ProcessStartInfo
    $Info.FileName = $HostPath
    $Info.Arguments = "-NoLogo -NoProfile -NonInteractive -EncodedCommand $Encoded"
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
    $LocalAppData = [Environment]::GetFolderPath('LocalApplicationData')
    $Roaming = [Environment]::GetFolderPath('ApplicationData')
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

function Test-ClaudeDesktop {
    $Roaming = [Environment]::GetFolderPath('ApplicationData')
    $Local = [Environment]::GetFolderPath('LocalApplicationData')
    return (Test-Path -LiteralPath (Join-Path $Roaming 'Claude')) -or
        (Test-Path -LiteralPath (Join-Path $Local 'Programs\Claude\Claude.exe') -PathType Leaf)
}

function Test-CodexDesktop {
    $Local = [Environment]::GetFolderPath('LocalApplicationData')
    $HomeDir = [Environment]::GetFolderPath('UserProfile')
    return (Test-Path -LiteralPath (Join-Path $HomeDir '.codex')) -or
        (Test-Path -LiteralPath (Join-Path $Local 'Programs\Codex\Codex.exe') -PathType Leaf) -or
        (Test-Path -LiteralPath (Join-Path $Local 'Codex\Codex.exe') -PathType Leaf)
}

function New-Result([bool] $Available, [string] $Reason, [string] $Mode = '') {
    return [pscustomobject][ordered]@{
        Available = $Available
        Reason = $Reason
        Mode = $Mode
    }
}

$Results = [ordered]@{}

if (Test-JetBrainsCopilot) {
    $Results['CopilotJetBrains'] = New-Result $true 'Détecté' 'json'
} else {
    $Results['CopilotJetBrains'] = New-Result $false 'Non disponible — GitHub Copilot JetBrains / IntelliJ non détecté'
}

$Copilot = Resolve-CommandPath 'copilot'
if ([string]::IsNullOrWhiteSpace($Copilot)) {
    $Results['CopilotCli'] = New-Result $false 'Non disponible — commande introuvable'
} elseif (Test-VsCodeCopilotShim $Copilot) {
    # Reject known editor shims before any command probe. Some wrappers may
    # return success for generic help while not being the standalone Copilot CLI.
    $Results['CopilotCli'] = New-Result $false 'Non disponible — launcher VS Code détecté, vrai CLI absent'
} elseif (Test-Capability $Copilot @('mcp', '--help')) {
    $Results['CopilotCli'] = New-Result $true 'Détecté — CLI MCP compatible' 'cli'
} else {
    $Results['CopilotCli'] = New-Result $false 'Non disponible — commande copilot détectée mais interface MCP incompatible'
}

$Claude = Resolve-CommandPath 'claude'
if ([string]::IsNullOrWhiteSpace($Claude)) {
    $Results['ClaudeCode'] = New-Result $false 'Non disponible — commande introuvable'
} elseif (Test-Capability $Claude @('mcp', '--help')) {
    $Results['ClaudeCode'] = New-Result $true 'Détecté — CLI MCP compatible' 'cli'
} else {
    $Results['ClaudeCode'] = New-Result $false 'Non disponible — commande claude détectée mais interface MCP incompatible'
}

if (Test-ClaudeDesktop) {
    $Results['ClaudeDesktop'] = New-Result $true 'Détecté' 'json'
} else {
    $Results['ClaudeDesktop'] = New-Result $false 'Non disponible — Claude Desktop non détecté'
}

$Codex = Resolve-CommandPath 'codex'
if (-not [string]::IsNullOrWhiteSpace($Codex) -and (Test-Capability $Codex @('mcp', '--help'))) {
    $Results['Codex'] = New-Result $true 'Détecté — Codex CLI' 'cli'
} elseif (Test-CodexDesktop) {
    $Results['Codex'] = New-Result $true 'Détecté — Codex Desktop (configuration via fichier utilisateur)' 'desktop'
} elseif (-not [string]::IsNullOrWhiteSpace($Codex)) {
    $Results['Codex'] = New-Result $false 'Non disponible — commande codex détectée mais interface MCP incompatible'
} else {
    $Results['Codex'] = New-Result $false 'Non disponible — Codex CLI / Desktop non détecté'
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
    $Lines.Add('')
}
[System.IO.File]::WriteAllLines(
    [System.IO.Path]::GetFullPath($OutputPath),
    $Lines,
    [System.Text.UTF8Encoding]::new($false))

Write-Host "MINOS MCP client preflight written: $OutputPath"
foreach ($Name in $Results.Keys) {
    $Value = $Results[$Name]
    Write-Host ("{0}: available={1} mode={2} reason={3}" -f $Name, $Value.Available, $Value.Mode, $Value.Reason)
}
