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

function Resolve-InstallRoot([string] $Launcher) {
    $Parent = [System.IO.Path]::GetFullPath((Split-Path -Parent $Launcher))
    if ([System.IO.Path]::GetFileName($Parent) -ieq 'app') {
        return [System.IO.Path]::GetFullPath((Split-Path -Parent $Parent))
    }
    return $Parent
}

$InstallRoot = Resolve-InstallRoot -Launcher $LauncherPath
$RuntimeJava = Join-Path $InstallRoot 'app\runtime\bin\java.exe'
$ProbeClasspath = Join-Path $InstallRoot 'lib\minos.jar'
foreach ($Required in @($RuntimeJava, $ProbeClasspath)) {
    if (-not (Test-Path -LiteralPath $Required -PathType Leaf)) {
        throw "MINOS packaged handshake dependency is missing: $Required"
    }
}

# The release/install probe deliberately delegates MCP framing, protocol
# negotiation and STDIO lifecycle to the same MCP Java SDK client used by MINOS'
# integration tests. The child process remains the real packaged launcher, so a
# broken jpackage runtime or MCP entry point still fails this gate.
$PreviousErrorActionPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = 'Continue'
    $ProbeOutput = @(
        & $RuntimeJava `
            '-cp' $ProbeClasspath `
            'com.minos.mcp.MinosMcpHandshakeProbe' `
            $LauncherPath `
            $CandidateHome `
            ([string]$TimeoutSeconds) 2>&1 |
            ForEach-Object { $_.ToString() }
    )
    $ProbeExit = $LASTEXITCODE
}
finally {
    $ErrorActionPreference = $PreviousErrorActionPreference
}

$BackendFile = Join-Path $CandidateHome 'runtime\backend.properties'
$BackendEvidence = if (Test-Path -LiteralPath $BackendFile -PathType Leaf) {
    [System.IO.File]::ReadAllText($BackendFile, [System.Text.Encoding]::UTF8).Trim()
}
else {
    '<backend.properties not created>'
}

if ($ProbeExit -ne 0) {
    $OutputEvidence = if ($ProbeOutput.Count -eq 0) { '<empty>' } else { $ProbeOutput -join [Environment]::NewLine }
    throw @"
MINOS MCP backend handshake failed (SDK probe exit=$ProbeExit)
launcher: $LauncherPath
MINOS_HOME: $CandidateHome
runtime java: $RuntimeJava
probe classpath: $ProbeClasspath
backend.properties:
$BackendEvidence
probe output:
$OutputEvidence
"@
}

if (($ProbeOutput -join "`n").IndexOf('MINOS MCP SDK HANDSHAKE SUCCESS', [StringComparison]::Ordinal) -lt 0) {
    throw "MCP SDK probe exited successfully without its success marker: $($ProbeOutput -join [Environment]::NewLine)"
}

Write-Host 'MINOS MCP BACKEND HANDSHAKE SUCCESS' -ForegroundColor Green
