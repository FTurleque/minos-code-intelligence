[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RequiredSemanticProvider = 'ollama'
$RequiredSemanticModel = 'embeddinggemma'
$RequiredSemanticDimensions = '768'
$RequiredSemanticEndpoint = 'http://127.0.0.1:11434/api/embed'
$RequiredRustAnalyzerRelease = '2026-07-27'
$RequiredRustAnalyzerVersion = '0.3.2989'
$RequiredRustAnalyzerCommit = '12c3381'

if ($env:OS -ne 'Windows_NT') {
    throw 'M24 Windows prerequisite gate must run on Windows.'
}

function Find-Command {
    param([Parameter(Mandatory = $true)][string] $Name)
    $Command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($Command) { return $Command.Source }
    return $null
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
            $Stdout = Get-Content -Raw -Path $StdoutPath -ErrorAction SilentlyContinue
            if (-not [string]::IsNullOrWhiteSpace($Stdout)) { $OutputParts += $Stdout.TrimEnd() }
        }
        if (Test-Path $StderrPath) {
            $Stderr = Get-Content -Raw -Path $StderrPath -ErrorAction SilentlyContinue
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

function Try-VersionProbe {
    param(
        [Parameter(Mandatory = $true)][string] $Label,
        [Parameter(Mandatory = $true)][string] $Executable,
        [Parameter(Mandatory = $true)][string[]] $Arguments,
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][System.Collections.Generic.List[string]] $Problems
    )
    try {
        return Invoke-Captured $Executable $Arguments
    }
    catch {
        $Problems.Add("$Label probe failed: $($_.Exception.Message)")
        return $null
    }
}

Write-Host '=== MINOS M24 - Windows polyglot prerequisite gate ===' -ForegroundColor Cyan

Require-EnvironmentValue 'MINOS_SEMANTIC_PROVIDER' $RequiredSemanticProvider
Require-EnvironmentValue 'MINOS_SEMANTIC_MODEL' $RequiredSemanticModel
Require-EnvironmentValue 'MINOS_SEMANTIC_DIMENSIONS' $RequiredSemanticDimensions
Require-EnvironmentValue 'MINOS_SEMANTIC_ENDPOINT' $RequiredSemanticEndpoint

$Problems = [System.Collections.Generic.List[string]]::new()

$Java = Find-Command 'java.exe'
if (-not $Java) {
    $Problems.Add('Java 24: java.exe is missing from PATH')
}
else {
    $JavaVersion = Try-VersionProbe 'Java' $Java @('-version') $Problems
    if ($JavaVersion) {
        if ($JavaVersion -notmatch 'version\s+"24(?:\.|"|\s)') {
            $Problems.Add("Java 24 required; java -version returned: $($JavaVersion -replace "`r?`n", ' | ')")
        }
        else {
            Write-Host "PASS Java 24: $($JavaVersion.Split("`n")[0])"
        }
    }
}

$Python = $null
foreach ($Candidate in @('python.exe', 'python', 'python3.exe', 'python3')) {
    $Python = Find-Command $Candidate
    if ($Python) { break }
}
if (-not $Python) {
    $Problems.Add('Python: no python/python3 command found in PATH')
}
else {
    $PythonVersion = Try-VersionProbe 'Python' $Python @('--version') $Problems
    if ($PythonVersion) { Write-Host "PASS Python: $PythonVersion" }
}

$WindowsSupport = Test-Dotnet10SupportedWindowsHost
Write-Host "INFO Windows host: $($WindowsSupport.ProductName) / $($WindowsSupport.Edition) / $($WindowsSupport.DisplayVersion) / build $($WindowsSupport.Build)"
if ($WindowsSupport.Supported) {
    $Dotnet = Find-Command 'dotnet.exe'
    if (-not $Dotnet) {
        $Problems.Add('.NET SDK 10+: dotnet.exe is missing from PATH on a Windows host supported by .NET 10')
    }
    else {
        $DotnetVersion = Try-VersionProbe '.NET SDK' $Dotnet @('--version') $Problems
        if ($DotnetVersion) {
            $DotnetMajorText = ($DotnetVersion -split '\.')[0]
            $DotnetMajor = 0
            if (-not [int]::TryParse($DotnetMajorText, [ref]$DotnetMajor) -or $DotnetMajor -lt 10) {
                $Problems.Add(".NET SDK 10+ required for scip-dotnet 0.2.14; dotnet --version=$DotnetVersion")
            }
            else {
                Write-Host "PASS .NET SDK: $DotnetVersion"
            }
        }
    }
}
else {
    Write-Host 'INFO scip-dotnet: .NET 10 is not supported on this Windows host; Windows C# e2e is intentionally BLOCKED/NOT_RUN and no unsupported SDK installation is required.'
}

$Go = Find-Command 'go.exe'
if (-not $Go) {
    $Problems.Add('Go: go.exe is missing from PATH')
}
else {
    $GoVersion = Try-VersionProbe 'Go' $Go @('version') $Problems
    if ($GoVersion) { Write-Host "PASS Go: $GoVersion" }
}

$Cargo = Find-Command 'cargo.exe'
if (-not $Cargo) {
    $Problems.Add('Rust: cargo.exe is missing from PATH')
}
else {
    $CargoVersion = Try-VersionProbe 'cargo' $Cargo @('--version') $Problems
    if ($CargoVersion) { Write-Host "PASS cargo: $CargoVersion" }
}

$Rustc = Find-Command 'rustc.exe'
if (-not $Rustc) {
    $Problems.Add('Rust: rustc.exe is missing from PATH')
}
else {
    $RustcVersion = Try-VersionProbe 'rustc' $Rustc @('--version') $Problems
    if ($RustcVersion) { Write-Host "PASS rustc: $RustcVersion" }
}

$RustAnalyzer = Find-Command 'rust-analyzer.exe'
if (-not $RustAnalyzer) {
    $Problems.Add('Rust: rust-analyzer.exe is missing from PATH')
}
else {
    $RustAnalyzerVersion = Try-VersionProbe 'rust-analyzer' $RustAnalyzer @('--version') $Problems
    if ($RustAnalyzerVersion) {
        if ($RustAnalyzerVersion -notmatch [regex]::Escape($RequiredRustAnalyzerVersion) -or
            $RustAnalyzerVersion -notmatch [regex]::Escape($RequiredRustAnalyzerRelease) -or
            $RustAnalyzerVersion -notmatch [regex]::Escape($RequiredRustAnalyzerCommit)) {
            $Problems.Add("rust-analyzer must match v$RequiredRustAnalyzerVersion / release $RequiredRustAnalyzerRelease / commit $RequiredRustAnalyzerCommit; got $RustAnalyzerVersion")
        }
        else {
            Write-Host "PASS rust-analyzer: $RustAnalyzerVersion"
        }
    }
}

Write-Host 'INFO scip-clang: Windows runtime intentionally not required; upstream 0.4.0 has no qualified Windows binary path in M24.'
Write-Host 'INFO scip-go executable is managed under MINOS_HOME/tools by the M24 provider runtime during e2e evaluation.'

if ($Problems.Count -gt 0) {
    Write-Host 'M24 WINDOWS PREREQUISITES FAILED' -ForegroundColor Red
    foreach ($Problem in $Problems) {
        Write-Host " - $Problem" -ForegroundColor Red
    }
    throw "M24 Windows prerequisite gate found $($Problems.Count) problem(s)."
}

Write-Host 'M24 WINDOWS PREREQUISITES SUCCESS' -ForegroundColor Green
