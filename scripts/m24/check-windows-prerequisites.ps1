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

    # Windows PowerShell 5.1 can turn a native program's normal stderr output
    # into NativeCommandError when $ErrorActionPreference='Stop'. java -version
    # writes its version to stderr, so capture native streams outside the
    # PowerShell error pipeline and evaluate only the native exit code.
    $StdoutPath = [System.IO.Path]::GetTempFileName()
    $StderrPath = [System.IO.Path]::GetTempFileName()
    try {
        $Process = Start-Process `
            -FilePath $Executable `
            -ArgumentList $Arguments `
            -NoNewWindow `
            -Wait `
            -PassThru `
            -RedirectStandardOutput $StdoutPath `
            -RedirectStandardError $StderrPath

        $OutputParts = @()
        if (Test-Path $StdoutPath) {
            $Stdout = (Get-Content -Raw -Path $StdoutPath -ErrorAction SilentlyContinue)
            if (-not [string]::IsNullOrWhiteSpace($Stdout)) { $OutputParts += $Stdout.TrimEnd() }
        }
        if (Test-Path $StderrPath) {
            $Stderr = (Get-Content -Raw -Path $StderrPath -ErrorAction SilentlyContinue)
            if (-not [string]::IsNullOrWhiteSpace($Stderr)) { $OutputParts += $Stderr.TrimEnd() }
        }
        $Output = ($OutputParts -join "`n").Trim()

        if ($Process.ExitCode -ne 0) {
            throw "Command failed: $Executable $($Arguments -join ' ') (exit=$($Process.ExitCode))`n$Output"
        }
        return $Output
    }
    finally {
        Remove-Item -Force -ErrorAction SilentlyContinue $StdoutPath, $StderrPath
    }
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

function Test-Dotnet10SupportedWindowsHost {
    $CurrentVersion = Get-ItemProperty 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion'
    $Build = 0
    [void][int]::TryParse([string]$CurrentVersion.CurrentBuild, [ref]$Build)
    $Edition = [string]$CurrentVersion.EditionID
    $DisplayVersion = [string]$CurrentVersion.DisplayVersion
    $ProductName = [string]$CurrentVersion.ProductName

    # .NET 10 support in 2026 excludes Windows 10 Pro 22H2. Supported Windows 10
    # clients are the still-supported Enterprise/IoT/LTSC lines (21H2/1809/1607).
    if ($Build -ge 22000) {
        return [pscustomobject]@{ Supported = $true; ProductName = $ProductName; Edition = $Edition; DisplayVersion = $DisplayVersion; Build = $Build }
    }

    $EnterpriseLike = $Edition -match 'Enterprise|IoT|EnterpriseS'
    $SupportedWindows10Build = $Build -in @(19044, 17763, 14393)
    $Supported = $EnterpriseLike -and $SupportedWindows10Build
    return [pscustomobject]@{ Supported = $Supported; ProductName = $ProductName; Edition = $Edition; DisplayVersion = $DisplayVersion; Build = $Build }
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

$WindowsSupport = Test-Dotnet10SupportedWindowsHost
Write-Host "INFO Windows host: $($WindowsSupport.ProductName) / $($WindowsSupport.Edition) / $($WindowsSupport.DisplayVersion) / build $($WindowsSupport.Build)"
if ($WindowsSupport.Supported) {
    $Dotnet = Resolve-Command 'dotnet.exe'
    $DotnetVersion = Invoke-Captured $Dotnet @('--version')
    $DotnetMajorText = ($DotnetVersion -split '\.')[0]
    $DotnetMajor = 0
    if (-not [int]::TryParse($DotnetMajorText, [ref] $DotnetMajor) -or $DotnetMajor -lt 10) {
        throw "M24 scip-dotnet 0.2.14 requires .NET SDK 10+ on this supported Windows host; dotnet --version=$DotnetVersion"
    }
    Write-Host "PASS .NET SDK: $DotnetVersion"
}
else {
    Write-Host 'INFO scip-dotnet: .NET 10 is not supported on this Windows host; Windows C# e2e is intentionally BLOCKED/NOT_RUN and no unsupported SDK installation is required.'
}

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
Write-Host 'INFO scip-go executable is managed under MINOS_HOME/tools by the M24 provider runtime during e2e evaluation.'
Write-Host 'M24 WINDOWS PREREQUISITES SUCCESS' -ForegroundColor Green
