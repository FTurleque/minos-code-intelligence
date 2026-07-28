[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RequiredSemanticProvider = 'ollama'
$RequiredSemanticModel = 'embeddinggemma'
$RequiredSemanticDimensions = '768'
$RequiredSemanticEndpoint = 'http://127.0.0.1:11434/api/embed'
$RequiredRustAnalyzerRelease = '2026-07-27'
$RequiredRustAnalyzerCommit = '12c3381'

if ($env:OS -ne 'Windows_NT') {
    throw 'M24 Windows prerequisite gate must run on Windows.'
}

function Resolve-Command {
    param([Parameter(Mandatory = $true)][string] $Name)
    $Command = Get-Command $Name -ErrorAction SilentlyContinue
    if (-not $Command) {
        throw "Missing required command in PATH: $Name"
    }
    return $Command.Source
}

function Invoke-Captured {
    param(
        [Parameter(Mandatory = $true)][string] $Executable,
        [Parameter(Mandatory = $true)][string[]] $Arguments
    )
    $Output = @(& $Executable @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed: $Executable $($Arguments -join ' ')`n$($Output -join "`n")"
    }
    return ($Output -join "`n").Trim()
}

function Require-EnvironmentValue {
    param(
        [Parameter(Mandatory = $true)][string] $Name,
        [Parameter(Mandatory = $true)][string] $Expected
    )
    $Actual = [Environment]::GetEnvironmentVariable($Name, 'Process')
    if ($Actual -ne $Expected) {
        throw "M24 prerequisite mismatch: $Name expected='$Expected' actual='$Actual'"
    }
    Write-Host "PASS env $Name=$Expected"
}

Write-Host '=== MINOS M24 - Windows polyglot prerequisite gate ===' -ForegroundColor Cyan

Require-EnvironmentValue 'MINOS_SEMANTIC_PROVIDER' $RequiredSemanticProvider
Require-EnvironmentValue 'MINOS_SEMANTIC_MODEL' $RequiredSemanticModel
Require-EnvironmentValue 'MINOS_SEMANTIC_DIMENSIONS' $RequiredSemanticDimensions
Require-EnvironmentValue 'MINOS_SEMANTIC_ENDPOINT' $RequiredSemanticEndpoint

$Java = Resolve-Command 'java.exe'
$JavaVersion = Invoke-Captured $Java @('-version')
if ($JavaVersion -notmatch 'version\s+"24(?:\.|"|\s)') {
    throw "M24 requires Java 24; java -version returned:`n$JavaVersion"
}
Write-Host "PASS Java 24: $($JavaVersion.Split("`n")[0])"

$Python = $null
foreach ($Candidate in @('python.exe', 'python', 'python3.exe', 'python3')) {
    $Resolved = Get-Command $Candidate -ErrorAction SilentlyContinue
    if ($Resolved) {
        $Python = $Resolved.Source
        break
    }
}
if (-not $Python) {
    throw 'M24 requires Python in PATH.'
}
$PythonVersion = Invoke-Captured $Python @('--version')
Write-Host "PASS Python: $PythonVersion"

$Dotnet = Resolve-Command 'dotnet.exe'
$DotnetVersion = Invoke-Captured $Dotnet @('--version')
$DotnetMajorText = ($DotnetVersion -split '\.')[0]
$DotnetMajor = 0
if (-not [int]::TryParse($DotnetMajorText, [ref] $DotnetMajor) -or $DotnetMajor -lt 10) {
    throw "M24 scip-dotnet 0.2.14 requires .NET SDK 10+; dotnet --version=$DotnetVersion"
}
Write-Host "PASS .NET SDK: $DotnetVersion"

$Go = Resolve-Command 'go.exe'
$GoVersion = Invoke-Captured $Go @('version')
Write-Host "PASS Go: $GoVersion"

$Cargo = Resolve-Command 'cargo.exe'
$CargoVersion = Invoke-Captured $Cargo @('--version')
Write-Host "PASS cargo: $CargoVersion"

$Rustc = Resolve-Command 'rustc.exe'
$RustcVersion = Invoke-Captured $Rustc @('--version')
Write-Host "PASS rustc: $RustcVersion"

$RustAnalyzer = Resolve-Command 'rust-analyzer.exe'
$RustAnalyzerVersion = Invoke-Captured $RustAnalyzer @('--version')
if ($RustAnalyzerVersion -notmatch [regex]::Escape($RequiredRustAnalyzerRelease) -and
    $RustAnalyzerVersion -notmatch [regex]::Escape($RequiredRustAnalyzerCommit)) {
    throw "M24 requires rust-analyzer release $RequiredRustAnalyzerRelease / commit $RequiredRustAnalyzerCommit; rust-analyzer --version=$RustAnalyzerVersion"
}
Write-Host "PASS rust-analyzer: $RustAnalyzerVersion"

Write-Host 'INFO scip-clang: Windows runtime intentionally not required; upstream 0.4.0 has no qualified Windows binary path in M24.'
Write-Host 'INFO scip-dotnet and scip-go executables are managed under MINOS_HOME/tools by the M24 provider runtime during e2e evaluation.'
Write-Host 'M24 WINDOWS PREREQUISITES SUCCESS' -ForegroundColor Green
