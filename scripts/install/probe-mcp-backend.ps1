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

function Stop-ProbeProcessTree([System.Diagnostics.Process] $Process) {
    if ($null -eq $Process -or $Process.HasExited) {
        return
    }

    $PreviousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & taskkill.exe /PID $Process.Id /T /F | Out-Null
    }
    finally {
        $ErrorActionPreference = $PreviousErrorActionPreference
    }

    try {
        $Process.WaitForExit(5000) | Out-Null
    }
    catch {
        # Best effort only. The caller has already decided the probe outcome.
    }
}

function Read-ProbeEvidence([string] $Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return '<empty>'
    }
    $Text = [System.IO.File]::ReadAllText($Path, [System.Text.Encoding]::UTF8).Trim()
    if ([string]::IsNullOrWhiteSpace($Text)) {
        return '<empty>'
    }
    return $Text
}

function Test-HandshakeSuccess([string] $StdoutEvidence) {
    return $StdoutEvidence.IndexOf('MINOS MCP SDK HANDSHAKE SUCCESS', [StringComparison]::Ordinal) -ge 0
}

$InstallRoot = Resolve-InstallRoot -Launcher $LauncherPath
$RuntimeJava = Join-Path $InstallRoot 'app\runtime\bin\java.exe'
$ProbeClasspath = Join-Path $InstallRoot 'lib\minos.jar'
foreach ($Required in @($RuntimeJava, $ProbeClasspath)) {
    if (-not (Test-Path -LiteralPath $Required -PathType Leaf)) {
        throw "MINOS packaged handshake dependency is missing: $Required"
    }
}

# Delegate MCP framing, protocol negotiation and STDIO lifecycle to the same MCP
# Java SDK client used by MINOS integration tests. Do not pipe the child process
# output through PowerShell: descendants can inherit those pipe handles and keep
# the pipeline open after the probe JVM exits. Redirect to files instead and own
# an independent wall-clock deadline for the entire Java + launcher process tree.
$ProbeStdout = Join-Path $CandidateHome 'mcp-probe.stdout.log'
$ProbeStderr = Join-Path $CandidateHome 'mcp-probe.stderr.log'
Remove-Item -LiteralPath $ProbeStdout, $ProbeStderr -Force -ErrorAction SilentlyContinue

$ProbeArguments = @(
    '-cp',
    ('"' + $ProbeClasspath + '"'),
    'com.minos.mcp.MinosMcpHandshakeProbe',
    ('"' + $LauncherPath + '"'),
    ('"' + $CandidateHome + '"'),
    ([string]$TimeoutSeconds)
)
$ProbeProcess = Start-Process `
    -FilePath $RuntimeJava `
    -ArgumentList $ProbeArguments `
    -PassThru `
    -WindowStyle Hidden `
    -RedirectStandardOutput $ProbeStdout `
    -RedirectStandardError $ProbeStderr

$ProcessDeadlineSeconds = $TimeoutSeconds + 15
$Completed = $ProbeProcess.WaitForExit($ProcessDeadlineSeconds * 1000)
$StdoutEvidence = Read-ProbeEvidence -Path $ProbeStdout
$StderrEvidence = Read-ProbeEvidence -Path $ProbeStderr
$HandshakeSucceeded = Test-HandshakeSuccess -StdoutEvidence $StdoutEvidence

if (-not $Completed) {
    # A MINOS MCP server is intentionally long-lived. The SDK client can prove
    # initialize + tools/list successfully and then remain blocked while performing
    # graceful transport shutdown against the still-running packaged launcher.
    # Once the unique success marker exists, the release gate has its protocol proof;
    # terminate the short-lived smoke process tree and accept that proof. Without the
    # marker, keep the timeout fail-closed and expose the captured diagnostics.
    Stop-ProbeProcessTree -Process $ProbeProcess
    if ($HandshakeSucceeded) {
        Write-Host "MINOS MCP SDK handshake proved before ${ProcessDeadlineSeconds}s teardown; smoke process tree terminated." -ForegroundColor DarkGray
        Write-Host 'MINOS MCP BACKEND HANDSHAKE SUCCESS' -ForegroundColor Green
        return
    }

    throw @"
MINOS MCP backend handshake timed out after ${ProcessDeadlineSeconds}s
launcher: $LauncherPath
MINOS_HOME: $CandidateHome
runtime java: $RuntimeJava
probe classpath: $ProbeClasspath
stdout:
$StdoutEvidence
stderr:
$StderrEvidence
"@
}

$ProbeExit = $ProbeProcess.ExitCode
$ProbeOutput = @()
if ($StdoutEvidence -ne '<empty>') { $ProbeOutput += $StdoutEvidence }
if ($StderrEvidence -ne '<empty>') { $ProbeOutput += $StderrEvidence }

$BackendFile = Join-Path $CandidateHome 'runtime\backend.properties'
$BackendEvidence = if (Test-Path -LiteralPath $BackendFile -PathType Leaf) {
    [System.IO.File]::ReadAllText($BackendFile, [System.Text.Encoding]::UTF8).Trim()
}
else {
    '<backend.properties not created>'
}

# The handshake marker is emitted only after SDK initialize() and tools/list()
# returned the required MINOS tools. A subsequent non-zero exit can therefore only
# affect smoke teardown, not the protocol proof itself.
if ($HandshakeSucceeded) {
    Write-Host 'MINOS MCP BACKEND HANDSHAKE SUCCESS' -ForegroundColor Green
    return
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

throw "MCP SDK probe exited successfully without its success marker: $($ProbeOutput -join [Environment]::NewLine)"
