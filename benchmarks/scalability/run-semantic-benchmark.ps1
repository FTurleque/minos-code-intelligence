[CmdletBinding()]
param(
    # Receives the scratch MINOS homes, the raw METRIC and REFUSED lines and the summary. Outside the repository.
    [Parameter(Mandatory = $true)][string] $OutputDirectory,

    [string] $ConfigurationPath = "benchmarks\scalability\semantic.json",

    # PostgreSQL datasets: JDBC URL of a dedicated database (scripts/dev/start-test-postgres.ps1) and its user.
    # The password is read from MINOS_TEST_POSTGRES_PASSWORD, or from the restricted file given here.
    [string] $PostgresUrl,
    [string] $PostgresUser,
    [string] $PostgresPasswordFile,

    # Reduced runs: only these datasets (storage:documents, as in the configuration) and these counts.
    [string[]] $Only,
    [int] $Warmup = -1,
    [int] $Iterations = -1
)

# Runs the semantic benchmark (see benchmarks/scalability/README.md): projection preparation and routes of the
# exploration surface. The Java harness is a test-source main class of minos-explorer: it never runs in
# `mvn verify`. Compatible with Windows PowerShell 5.1 and PowerShell 7.

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$Output = [System.IO.Path]::GetFullPath($OutputDirectory)
$RepoPrefix = $RepoRoot.TrimEnd('\') + '\'
if ($Output.Equals($RepoRoot.TrimEnd('\'), [System.StringComparison]::OrdinalIgnoreCase) -or
    $Output.StartsWith($RepoPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Benchmark data must stay outside the repository: $Output"
}
if (Test-Path -LiteralPath (Join-Path $Output "work")) {
    throw "The output directory already holds a benchmark run, use a fresh one: $Output"
}

$ConfigurationFile = if ([System.IO.Path]::IsPathRooted($ConfigurationPath)) { $ConfigurationPath } else { Join-Path $RepoRoot $ConfigurationPath }
$Configuration = Get-Content -Raw -LiteralPath $ConfigurationFile | ConvertFrom-Json

$DatasetArguments = @()
$NeedsPostgres = $false
foreach ($Dataset in @($Configuration.datasets)) {
    $Name = "$($Dataset.storage):$($Dataset.documents)"
    if ($null -ne $Only -and $Only.Count -gt 0 -and -not ($Only -contains $Name)) { continue }
    $Value = $Name
    if ($Dataset.PSObject.Properties.Name -contains "expectRefusal") {
        $Value += ":expect-refusal=$(@($Dataset.expectRefusal) -join '+')"
    }
    if ($Dataset.storage -eq "postgresql") { $NeedsPostgres = $true }
    $DatasetArguments += "--dataset=$Value"
}
if ($DatasetArguments.Count -eq 0) { throw "No dataset of the configuration matches -Only." }
if ($Warmup -lt 0) { $Warmup = [int] $Configuration.warmup }
if ($Iterations -lt 0) { $Iterations = [int] $Configuration.iterations }

New-Item -ItemType Directory -Force -Path $Output | Out-Null
$Classpath = Join-Path $Output "classpath.txt"
Push-Location $RepoRoot
try {
    & (Join-Path $RepoRoot "mvnw.cmd") -q -pl minos-explorer -am test-compile dependency:build-classpath "-Dmdep.outputFile=$Classpath"
    if ($LASTEXITCODE -ne 0) { throw "Maven failed to compile the harness ($LASTEXITCODE)." }
    $Commit = (& git rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0) { throw "git rev-parse failed ($LASTEXITCODE)." }
}
finally {
    Pop-Location
}
# The PostgreSQL backend is registered by the minos-app assembly only (ADR 0068): the registration used by the
# tests of minos-bootstrap is put on the classpath, without any dependency of minos-explorer on minos-storage.
$RuntimeClasspath = @(
    (Join-Path $RepoRoot "minos-explorer\target\test-classes"),
    (Join-Path $RepoRoot "minos-explorer\target\classes"),
    (Join-Path $RepoRoot "minos-bootstrap\src\test\postgresql-backend"),
    [System.IO.File]::ReadAllText($Classpath).Trim()
) -join [System.IO.Path]::PathSeparator

$UserHome = Join-Path $Output "user-home"
New-Item -ItemType Directory -Force -Path $UserHome | Out-Null
$Arguments = @($Configuration.jvmArguments) + @(
    "-Duser.home=$UserHome",
    "-classpath", $RuntimeClasspath,
    $Configuration.mainClass,
    "--work=$(Join-Path $Output 'work')",
    "--dimensions=$($Configuration.dimensions)",
    "--seed=$($Configuration.seed)",
    "--warmup=$Warmup",
    "--iterations=$Iterations",
    "--deep-page=$($Configuration.deepPage)",
    "--max-points=$($Configuration.maxPoints)",
    "--max-memory-bytes=$([long] $Configuration.maxMemoryMegabytes * 1024 * 1024)",
    "--request-timeout-seconds=$($Configuration.requestTimeoutSeconds)",
    "--run-id=$([DateTime]::Now.ToString('yyyyMMddHHmmss', [Globalization.CultureInfo]::InvariantCulture))"
) + $DatasetArguments

$PostgresHost = "none"
if ($NeedsPostgres) {
    if ($PostgresUrl) {
        $Arguments += "--postgres-url=$PostgresUrl"
        if ($PostgresUrl -match '^jdbc:postgresql://([^/?]+)') { $PostgresHost = $Matches[1] -replace '^.*@', '' }
    }
    if ($PostgresUser) { $Arguments += "--postgres-user=$PostgresUser" }
    if ($PostgresPasswordFile) { $Arguments += "--postgres-password-file=$([System.IO.Path]::GetFullPath($PostgresPasswordFile))" }
    elseif ($env:MINOS_TEST_POSTGRES_PASSWORD) {
        # Inherited by the benchmark JVM through the environment, never written in an argument or a file.
        $env:MINOS_POSTGRES_PASSWORD = $env:MINOS_TEST_POSTGRES_PASSWORD
    }
}

# The JDK of JAVA_HOME when it is set, never a java.exe of the current directory.
$Java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\java.exe" } else { (Get-Command java -CommandType Application | Select-Object -First 1).Source }

# No secret here: the PostgreSQL URL is reduced to its host and port.
$Environment = @(
    "date=$([DateTimeOffset]::Now.ToString('o'))",
    "gitCommit=$Commit",
    "os=$([System.Environment]::OSVersion.VersionString)",
    "processor=$((Get-CimInstance Win32_Processor | Select-Object -First 1).Name.Trim())",
    "logicalProcessors=$([System.Environment]::ProcessorCount)",
    "totalPhysicalMemoryBytes=$((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory)",
    "jvmArguments=$(@($Configuration.jvmArguments) -join ' ')",
    "datasets=$(($DatasetArguments | ForEach-Object { $_.Substring(10) }) -join ' ')",
    "warmup=$Warmup",
    "iterations=$Iterations",
    "postgresHost=$PostgresHost",
    "=== java --version ===",
    ((& $Java --version 2>&1) -join [Environment]::NewLine)
)
[System.IO.File]::WriteAllText((Join-Path $Output "environment.txt"), ($Environment -join [Environment]::NewLine) + [Environment]::NewLine,
    [System.Text.UTF8Encoding]::new($false))

$Result = Join-Path $Output "result.tsv"
$Errors = Join-Path $Output "stderr.txt"
$Started = Get-Date
$Process = Start-Process -FilePath $Java -ArgumentList ($Arguments | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } }) `
    -NoNewWindow -Wait -PassThru -RedirectStandardOutput $Result -RedirectStandardError $Errors
$Elapsed = (Get-Date) - $Started

# Summary: one object per section and dataset, built from the METRIC lines; the refusals are listed apart.
$Summary = [ordered]@{}
$Refused = @()
foreach ($Line in Get-Content -LiteralPath $Result) {
    $Parts = $Line -split "`t"
    if ($Parts.Count -eq 5 -and $Parts[0] -eq "METRIC") {
        $Key = "$($Parts[1])/$($Parts[2])"
        if (-not $Summary.Contains($Key)) { $Summary[$Key] = [ordered]@{} }
        $Summary[$Key][$Parts[3]] = $Parts[4]
    }
    elseif ($Parts.Count -eq 4 -and $Parts[0] -eq "REFUSED") {
        $Refused += [ordered]@{ section = $Parts[1]; dataset = $Parts[2]; reason = $Parts[3] }
    }
}
[ordered]@{ elapsedSeconds = [int] $Elapsed.TotalSeconds; exitCode = $Process.ExitCode; metrics = $Summary; refused = $Refused } |
    ConvertTo-Json -Depth 5 | Out-File -Encoding utf8 (Join-Path $Output "summary.json")
Write-Host "Results: $Result ($([int] $Elapsed.TotalSeconds) s)"
if ($Process.ExitCode -ne 0) {
    Write-Warning "The benchmark ended with code $($Process.ExitCode): an unexpected refusal, or an expected refusal that did not happen; see $Result and $Errors"
    exit $Process.ExitCode
}
