[CmdletBinding()]
param(
    # Directory that receives the corpus. Keep it outside the repository: it holds a source copy,
    # Maven build outputs and SCIP artifacts of several tens of megabytes.
    [Parameter(Mandatory = $true)][string] $CorpusDirectory,

    [string] $Revision = "HEAD",

    # MINOS-managed tools (coursier, Maven, scip-java runner), as installed by `minos tools install scip-java`.
    [string] $ToolsDirectory = (Join-Path $HOME ".minos\tools")
)

# Builds the real corpus of the scalability benchmark: a SCIP index of this repository produced by the
# MINOS-managed scip-java runtime. See benchmarks/scalability/README.md for the rationale of each step.

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$Corpus = [System.IO.Path]::GetFullPath($CorpusDirectory)
if ($Corpus.StartsWith($RepoRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "The corpus directory must be outside the repository: $Corpus"
}
$Source = Join-Path $Corpus "minos-src"
if (Test-Path -LiteralPath $Source) {
    throw "The corpus source copy already exists, remove it first: $Source"
}
New-Item -ItemType Directory -Force -Path $Source | Out-Null

# 1. Source copy of the requested revision (tracked files only, no build output).
$Archive = Join-Path $Corpus "minos-src.tar"
& git -C $RepoRoot archive --format=tar -o $Archive $Revision
if ($LASTEXITCODE -ne 0) { throw "git archive failed ($LASTEXITCODE)" }
& tar -xf $Archive -C $Source
if ($LASTEXITCODE -ne 0) { throw "tar extraction failed ($LASTEXITCODE)" }
Remove-Item -LiteralPath $Archive -Force
$Commit = (& git -C $RepoRoot rev-parse $Revision).Trim()

# 2. Java/Maven corpus only: the polyglot fixtures and the Gradle IntelliJ plugin are outside the
#    Maven reactor. The root Maven wrapper is removed because scip-java prefers ./mvnw, which is not
#    a Win32 executable; the MINOS-managed Maven is used instead (as the Linux runtime does).
foreach ($Relative in @("fixtures", "minos-intellij", "mvnw", "mvnw.cmd", ".mvn")) {
    $Target = Join-Path $Source $Relative
    if (Test-Path -LiteralPath $Target) { Remove-Item -LiteralPath $Target -Recurse -Force }
}

# 3. minos-app builds into the root target/ directory. Its `clean` would then wipe the root
#    target/scip-targetroot where scip-java aggregates every module, leaving only minos-app in the
#    index. The corpus copy builds minos-app into its own module directory instead.
$AppPom = Join-Path $Source "minos-app\pom.xml"
$Pom = [System.IO.File]::ReadAllText($AppPom)
$Original = '<directory>${maven.multiModuleProjectDirectory}/target</directory>'
if (-not $Pom.Contains($Original)) { throw "minos-app/pom.xml no longer declares $Original; review step 3." }
[System.IO.File]::WriteAllText($AppPom, $Pom.Replace($Original, '<directory>${project.basedir}/target</directory>'),
    [System.Text.UTF8Encoding]::new($false))

# 4. MINOS-managed scip-java Windows runner, called directly: `minos index` runs it inside the
#    non-elevated AppContainer sandbox, which refuses a PowerShell installed under Program Files.
$Runner = Get-ChildItem -LiteralPath (Join-Path $ToolsDirectory "scip-java") -Recurse -Filter "scip-java-windows-runner.ps1" |
    Select-Object -First 1
$Maven = Get-ChildItem -LiteralPath (Join-Path $ToolsDirectory "maven") -Recurse -Filter "mvn.cmd" | Select-Object -First 1
$Coursier = Get-ChildItem -LiteralPath (Join-Path $ToolsDirectory "coursier") -Recurse -Filter "cs.exe" | Select-Object -First 1
if ($null -eq $Runner -or $null -eq $Maven -or $null -eq $Coursier) {
    throw "MINOS-managed scip-java tools are missing under $ToolsDirectory (run: minos tools install scip-java)."
}
$Version = Split-Path -Leaf (Split-Path -Parent (Split-Path -Parent $Runner.FullName))
$Output = Join-Path $Corpus "scip-out"
$Log = Join-Path $Corpus "scip-java.log"
$Stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
& pwsh -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $Runner.FullName `
    -ProjectPath $Source -CoursierCommand $Coursier.FullName -Coordinate "org.scip-code:scip-java:$Version" `
    -Language JAVA -OutputDirectory $Output -MavenCommand $Maven.FullName *>&1 | Out-File -Encoding utf8 $Log
if ($LASTEXITCODE -ne 0) { throw "scip-java failed ($LASTEXITCODE), see $Log" }
$Stopwatch.Stop()

$Scip = Join-Path $Corpus "minos-full.scip"
Copy-Item -LiteralPath (Join-Path $Output "index.scip") -Destination $Scip -Force
$Hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $Scip).Hash.ToLowerInvariant()
$Manifest = @(
    "revision=$Commit",
    "scipJavaVersion=$Version",
    "scipBytes=$((Get-Item -LiteralPath $Scip).Length)",
    "scipSha256=$Hash",
    "indexingSeconds=$($Stopwatch.Elapsed.TotalSeconds.ToString('F1', [Globalization.CultureInfo]::InvariantCulture))"
)
[System.IO.File]::WriteAllText((Join-Path $Corpus "corpus.txt"), ($Manifest -join [Environment]::NewLine) + [Environment]::NewLine,
    [System.Text.UTF8Encoding]::new($false))
$Manifest | ForEach-Object { Write-Host $_ }
