[CmdletBinding()]
param(
    # Directory produced by prepare-corpus.ps1 (minos-src/, minos-full.scip and optional extra SCIPs).
    [Parameter(Mandatory = $true)][string] $CorpusDirectory,

    # Receives the scratch MINOS_HOME, the raw METRIC lines and the summary. Outside the repository.
    [Parameter(Mandatory = $true)][string] $OutputDirectory,

    [string] $ConfigurationPath = "benchmarks\scalability\scalability.json"
)

# Replays the A6 scalability measurements (see benchmarks/scalability/README.md). The Java harness is a
# test-source main class: it never runs in `mvn verify`.

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$Corpus = [System.IO.Path]::GetFullPath($CorpusDirectory)
$Output = [System.IO.Path]::GetFullPath($OutputDirectory)
foreach ($Directory in @($Corpus, $Output)) {
    if ($Directory.StartsWith($RepoRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Benchmark data must stay outside the repository: $Directory"
    }
}
if (Test-Path -LiteralPath (Join-Path $Output "home")) {
    throw "The output directory already holds a MINOS home, use a fresh one: $Output"
}
New-Item -ItemType Directory -Force -Path $Output | Out-Null

$ConfigurationFile = if ([System.IO.Path]::IsPathRooted($ConfigurationPath)) { $ConfigurationPath } else { Join-Path $RepoRoot $ConfigurationPath }
$Configuration = Get-Content -Raw -LiteralPath $ConfigurationFile | ConvertFrom-Json

function Resolve-DatasetPath {
    param([Parameter(Mandatory = $true)][string] $Value)
    if ($Value.StartsWith("corpus:")) { return [System.IO.Path]::GetFullPath((Join-Path $Corpus $Value.Substring(7))) }
    if ($Value.StartsWith("repo:")) { return [System.IO.Path]::GetFullPath((Join-Path $RepoRoot $Value.Substring(5))) }
    return [System.IO.Path]::GetFullPath($Value)
}

function Format-ScipArgument {
    param([Parameter(Mandatory = $true)] $Entry)
    $Path = Resolve-DatasetPath ([string] $Entry.path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $null }
    return "$($Entry.label)|$($Entry.provider)|$Path"
}

$Classpath = Join-Path $Output "classpath.txt"
Push-Location $RepoRoot
try {
    & (Join-Path $RepoRoot "mvnw.cmd") -q -pl minos-bootstrap -am test-compile dependency:build-classpath "-Dmdep.outputFile=$Classpath"
    if ($LASTEXITCODE -ne 0) { throw "Maven failed to compile the harness ($LASTEXITCODE)." }
    $Commit = (& git rev-parse HEAD).Trim()
}
finally {
    Pop-Location
}
$RuntimeClasspath = @(
    (Join-Path $RepoRoot "minos-bootstrap\target\test-classes"),
    (Join-Path $RepoRoot "minos-bootstrap\target\classes"),
    [System.IO.File]::ReadAllText($Classpath).Trim()
) -join [System.IO.Path]::PathSeparator

$Main = Format-ScipArgument $Configuration.mainScip
if ($null -eq $Main) { throw "Main SCIP not found: $($Configuration.mainScip.path) (run prepare-corpus.ps1)" }
$UserHome = Join-Path $Output "user-home"
New-Item -ItemType Directory -Force -Path $UserHome | Out-Null
$Arguments = @($Configuration.jvmArguments) + @(
    "-Duser.home=$UserHome",
    "-classpath", $RuntimeClasspath,
    $Configuration.mainClass,
    "--home=$(Join-Path $Output 'home')",
    "--source-root=$(Resolve-DatasetPath $Configuration.sourceRoot)",
    "--out=$(Join-Path $Output 'work')",
    "--main-scip=$Main",
    "--fractions=$((@($Configuration.fractions) | ForEach-Object { ([double] $_).ToString([Globalization.CultureInfo]::InvariantCulture) }) -join ',')",
    "--replicas=$(@($Configuration.replicas) -join ',')",
    "--queries=$(@($Configuration.queries) -join ';')",
    "--architecture-datasets=$(@($Configuration.architectureDatasets) -join ',')",
    "--warmup=$($Configuration.warmup)",
    "--iterations=$($Configuration.iterations)",
    "--ratio-iterations=$($Configuration.ratioIterations)",
    "--load-iterations=$($Configuration.loadIterations)",
    "--architecture-warmup=$($Configuration.architectureWarmup)",
    "--architecture-iterations=$($Configuration.architectureIterations)",
    "--profile-millis=$($Configuration.profileMillis)",
    "--sections=$(@($Configuration.sections) -join ',')"
)
foreach ($Entry in @($Configuration.ratioScips)) {
    $Value = Format-ScipArgument $Entry
    if ($null -ne $Value) { $Arguments += "--ratio-scip=$Value" }
    elseif (-not ($Entry.PSObject.Properties.Name -contains "optional" -and $Entry.optional)) {
        throw "Required ratio SCIP not found: $($Entry.path)"
    }
    else { Write-Warning "Optional ratio SCIP skipped: $($Entry.label)" }
}
foreach ($Root in @($Configuration.extraDiscoveryRoots)) { $Arguments += "--extra-discovery-root=$(Resolve-DatasetPath $Root)" }

$Environment = @(
    "date=$([DateTimeOffset]::Now.ToString('o'))",
    "gitCommit=$Commit",
    "os=$([System.Environment]::OSVersion.VersionString)",
    "processor=$((Get-CimInstance Win32_Processor | Select-Object -First 1).Name.Trim())",
    "logicalProcessors=$([System.Environment]::ProcessorCount)",
    "totalPhysicalMemoryBytes=$((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory)",
    "=== java --version ===",
    ((& java --version 2>&1) -join [Environment]::NewLine)
)
$CorpusManifest = Join-Path $Corpus "corpus.txt"
if (Test-Path -LiteralPath $CorpusManifest) { $Environment += "=== corpus.txt ==="; $Environment += Get-Content -LiteralPath $CorpusManifest }
[System.IO.File]::WriteAllText((Join-Path $Output "environment.txt"), ($Environment -join [Environment]::NewLine) + [Environment]::NewLine,
    [System.Text.UTF8Encoding]::new($false))

$Result = Join-Path $Output "result.tsv"
$Errors = Join-Path $Output "stderr.txt"
$Process = Start-Process -FilePath "java" -ArgumentList ($Arguments | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } }) `
    -NoNewWindow -Wait -PassThru -RedirectStandardOutput $Result -RedirectStandardError $Errors
if ($Process.ExitCode -ne 0) { throw "Benchmark failed ($($Process.ExitCode)), see $Errors" }

# Summary: one object per section and dataset, built from the raw METRIC lines.
$Summary = [ordered]@{}
foreach ($Line in Get-Content -LiteralPath $Result) {
    $Parts = $Line -split "`t", 5
    if ($Parts.Count -ne 5 -or $Parts[0] -ne "METRIC") { continue }
    $Key = "$($Parts[1])/$($Parts[2])"
    if (-not $Summary.Contains($Key)) { $Summary[$Key] = [ordered]@{} }
    $Summary[$Key][$Parts[3]] = $Parts[4]
}
$Summary | ConvertTo-Json -Depth 4 | Out-File -Encoding utf8 (Join-Path $Output "summary.json")
Write-Host "Results: $Result"
