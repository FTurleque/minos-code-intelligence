[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $LauncherPath,

    [Parameter(Mandatory = $true)]
    [string] $CandidateHome,

    [ValidateRange(1, 120)]
    [int] $TimeoutSeconds = 20
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($env:OS -ne 'Windows_NT') {
    throw 'MINOS MCP backend handshake probe currently targets Windows hosts.'
}

$LauncherPath = [System.IO.Path]::GetFullPath($LauncherPath)
$CandidateHome = [System.IO.Path]::GetFullPath($CandidateHome)
if (-not (Test-Path -LiteralPath $LauncherPath -PathType Leaf)) {
    throw "MINOS launcher is missing: $LauncherPath"
}
New-Item -ItemType Directory -Force -Path $CandidateHome | Out-Null

function New-ProcessStartInfo([string] $Launcher, [string] $MinosHome) {
    $Info = New-Object System.Diagnostics.ProcessStartInfo
    if ([System.IO.Path]::GetExtension($Launcher) -ieq '.cmd' -or
        [System.IO.Path]::GetExtension($Launcher) -ieq '.bat') {
        $Info.FileName = $env:ComSpec
        $EscapedLauncher = $Launcher.Replace('"', '""')
        $Info.Arguments = '/d /s /c ""{0}" mcp"' -f $EscapedLauncher
    }
    else {
        $Info.FileName = $Launcher
        $Info.Arguments = 'mcp'
    }
    $Info.WorkingDirectory = Split-Path -Parent $Launcher
    $Info.UseShellExecute = $false
    $Info.CreateNoWindow = $true
    $Info.RedirectStandardInput = $true
    $Info.RedirectStandardOutput = $true
    $Info.RedirectStandardError = $true
    $Info.StandardOutputEncoding = [System.Text.Encoding]::UTF8
    $Info.StandardErrorEncoding = [System.Text.Encoding]::UTF8
    $Info.EnvironmentVariables['MINOS_HOME'] = $MinosHome
    return $Info
}

function Write-McpLine([System.IO.StreamWriter] $Writer, [string] $Json) {
    $Writer.WriteLine($Json)
    $Writer.Flush()
}

function Await-McpResponse(
    [System.IO.StreamReader] $Reader,
    [string] $Marker,
    [int] $TimeoutMilliseconds
) {
    $Deadline = [DateTime]::UtcNow.AddMilliseconds($TimeoutMilliseconds)
    while ([DateTime]::UtcNow -lt $Deadline) {
        $Remaining = [int][Math]::Max(1, ($Deadline - [DateTime]::UtcNow).TotalMilliseconds)
        $Task = $Reader.ReadLineAsync()
        if (-not $Task.Wait($Remaining)) {
            throw "Timed out waiting for MCP response $Marker"
        }
        $Line = $Task.Result
        if ($null -eq $Line) {
            throw "MCP server closed stdout before response $Marker"
        }
        if ($Line.IndexOf($Marker, [StringComparison]::Ordinal) -ge 0) {
            return $Line
        }
    }
    throw "Timed out waiting for MCP response $Marker"
}

$Process = New-Object System.Diagnostics.Process
$Process.StartInfo = New-ProcessStartInfo -Launcher $LauncherPath -MinosHome $CandidateHome
$ErrorTask = $null
$Failure = $null
$Initialize = ''
$Tools = ''
try {
    if (-not $Process.Start()) {
        throw 'MINOS MCP backend probe process did not start.'
    }
    $ErrorTask = $Process.StandardError.ReadToEndAsync()
    try {
        # MCP Java SDK 2.0.0 tracks the 2025-11-25 specification. Keep raw
        # release probes aligned with the same protocol negotiated by the SDK client.
        Write-McpLine -Writer $Process.StandardInput -Json '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"minos-release-handshake-probe","version":"1"}}}'
        $Initialize = Await-McpResponse -Reader $Process.StandardOutput -Marker '"id":1' -TimeoutMilliseconds ($TimeoutSeconds * 1000)
        if ($Initialize.IndexOf('minos-code-intelligence', [StringComparison]::OrdinalIgnoreCase) -lt 0) {
            throw "MCP initialize response does not identify MINOS: $Initialize"
        }

        Write-McpLine -Writer $Process.StandardInput -Json '{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}'
        Write-McpLine -Writer $Process.StandardInput -Json '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
        $Tools = Await-McpResponse -Reader $Process.StandardOutput -Marker '"id":2' -TimeoutMilliseconds ($TimeoutSeconds * 1000)
        foreach ($RequiredTool in @('minos_search_code', 'minos_impact')) {
            if ($Tools.IndexOf($RequiredTool, [StringComparison]::Ordinal) -lt 0) {
                throw "MCP tools/list response is missing $RequiredTool"
            }
        }
    }
    catch {
        $Failure = $_
    }
    finally {
        try { $Process.StandardInput.Close() } catch { }
        if (-not $Process.WaitForExit(3000)) {
            try { $Process.Kill() } catch { }
            $Process.WaitForExit(5000) | Out-Null
        }
    }

    $Stderr = if ($null -ne $ErrorTask -and $ErrorTask.IsCompleted) { [string]$ErrorTask.Result } else { '' }
    $BackendFile = Join-Path $CandidateHome 'runtime\backend.properties'
    $BackendEvidence = if (Test-Path -LiteralPath $BackendFile -PathType Leaf) {
        [System.IO.File]::ReadAllText($BackendFile, [System.Text.Encoding]::UTF8).Trim()
    }
    else {
        '<backend.properties not created>'
    }

    if ($null -ne $Failure) {
        $ExitEvidence = if ($Process.HasExited) { [string]$Process.ExitCode } else { '<still running>' }
        $StderrEvidence = if ([string]::IsNullOrWhiteSpace($Stderr)) { '<empty>' } else { $Stderr.Trim() }
        throw @"
MINOS MCP backend handshake failed: $($Failure.Exception.Message)
launcher: $LauncherPath
MINOS_HOME: $CandidateHome
process exit: $ExitEvidence
initialize response: $(if ([string]::IsNullOrWhiteSpace($Initialize)) { '<none>' } else { $Initialize })
tools response: $(if ([string]::IsNullOrWhiteSpace($Tools)) { '<none>' } else { $Tools })
backend.properties:
$BackendEvidence
stderr:
$StderrEvidence
"@
    }

    foreach ($Fatal in @('NoClassDefFoundError', 'Exception in thread "main"')) {
        if ($Stderr.IndexOf($Fatal, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
            throw "MCP backend probe emitted fatal stderr: $Stderr"
        }
    }
    Write-Host 'MINOS MCP BACKEND HANDSHAKE SUCCESS' -ForegroundColor Green
}
finally {
    $Process.Dispose()
}
