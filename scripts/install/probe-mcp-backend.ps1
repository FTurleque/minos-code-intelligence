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
try {
    if (-not $Process.Start()) {
        throw 'MINOS MCP backend probe process did not start.'
    }
    $ErrorTask = $Process.StandardError.ReadToEndAsync()
    try {
        Write-McpLine -Writer $Process.StandardInput -Json '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"minos-m29-s7-switch-probe","version":"1"}}}'
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
    finally {
        try { $Process.StandardInput.Close() } catch { }
        if (-not $Process.WaitForExit(3000)) {
            try { $Process.Kill() } catch { }
            $Process.WaitForExit(5000) | Out-Null
        }
    }

    $Stderr = if ($ErrorTask.IsCompleted) { [string]$ErrorTask.Result } else { '' }
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
