[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $ProjectPath,

    [string] $OutputDirectory,

    [string] $ScipJavaVersion = "0.13.1",

    [string] $CoursierCommand,

    [string] $ScipCommand
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$LocalToolsBin = Join-Path $RepoRoot ".minos-m0\tools\bin"
$LocalCoursier = Join-Path $LocalToolsBin "cs.exe"
$LocalScip = Join-Path $LocalToolsBin "scip.exe"

function Resolve-ToolCommand {
    param(
        [string] $ExplicitCommand,
        [Parameter(Mandatory = $true)][string] $LocalPath,
        [Parameter(Mandatory = $true)][string] $FallbackName,
        [Parameter(Mandatory = $true)][string] $DisplayName
    )

    if (-not [string]::IsNullOrWhiteSpace($ExplicitCommand)) {
        if (Test-Path -LiteralPath $ExplicitCommand -PathType Leaf) {
            return (Resolve-Path -LiteralPath $ExplicitCommand).Path
        }
        $ResolvedExplicit = Get-Command $ExplicitCommand -ErrorAction SilentlyContinue
        if ($ResolvedExplicit) {
            return $ResolvedExplicit.Source
        }
        throw "$DisplayName not found: $ExplicitCommand"
    }

    if (Test-Path -LiteralPath $LocalPath -PathType Leaf) {
        return $LocalPath
    }

    $GlobalCommand = Get-Command $FallbackName -ErrorAction SilentlyContinue
    if ($GlobalCommand) {
        return $GlobalCommand.Source
    }

    throw "$DisplayName not found. Run .\scripts\m0\install-scip-tools.ps1 first."
}

function Invoke-Checked {
    param(
        [Parameter(Mandatory = $true)][string] $Command,
        [Parameter(Mandatory = $true)][string[]] $Arguments,
        [Parameter(Mandatory = $true)][string] $Description
    )

    Write-Host "==> $Description"
    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Description failed with exit code $LASTEXITCODE"
    }
}

function Resolve-MavenBinForScipJava {
    $WrapperPropertiesPath = Join-Path $RepoRoot ".mvn\wrapper\maven-wrapper.properties"
    if (-not (Test-Path -LiteralPath $WrapperPropertiesPath -PathType Leaf)) {
        throw "Maven is not in PATH and Maven Wrapper properties were not found."
    }

    $WrapperProperties = Get-Content -Raw -LiteralPath $WrapperPropertiesPath | ConvertFrom-StringData
    $DistributionUrl = $WrapperProperties.distributionUrl
    $DistributionName = $DistributionUrl -replace '^.*/', ''
    $DistributionDirectory = $DistributionName -replace '\.[^.]*$', '' -replace '-bin$', ''
    $DistributionHash = (
        [System.Security.Cryptography.SHA256]::Create().ComputeHash([byte[]][char[]]$DistributionUrl) |
            ForEach-Object { $_.ToString("x2") }
    ) -join ''

    $MavenUserHome = if ([string]::IsNullOrWhiteSpace($env:MAVEN_USER_HOME)) {
        Join-Path ([Environment]::GetFolderPath("UserProfile")) ".m2"
    }
    else {
        $env:MAVEN_USER_HOME
    }

    $WrapperMavenBin = Join-Path $MavenUserHome "wrapper\dists\$DistributionDirectory\$DistributionHash\bin"
    $WrapperMavenCommand = Join-Path $WrapperMavenBin "mvn.cmd"
    if (-not (Test-Path -LiteralPath $WrapperMavenCommand -PathType Leaf)) {
        throw "Maven is not in PATH and the Maven Wrapper distribution is not installed: $WrapperMavenCommand"
    }

    return $WrapperMavenBin
}

function Install-MavenExeShim {
    param(
        [Parameter(Mandatory = $true)][string] $DestinationDirectory
    )

    $Compiler = Join-Path $env:SystemRoot "Microsoft.NET\Framework64\v4.0.30319\csc.exe"
    if (-not (Test-Path -LiteralPath $Compiler -PathType Leaf)) {
        throw "The Windows C# compiler required for the local Maven executable shim was not found: $Compiler"
    }

    New-Item -ItemType Directory -Force -Path $DestinationDirectory | Out-Null
    $Source = Join-Path $DestinationDirectory "MavenCommandShim.cs"
    $Executable = Join-Path $DestinationDirectory "mvn.exe"
    $PartialExecutable = Join-Path $DestinationDirectory "mvn.partial.exe"
    $JavacDirectory = Join-Path $DestinationDirectory "scip-javac"
    New-Item -ItemType Directory -Force -Path $JavacDirectory | Out-Null
    $JavacSource = Join-Path $JavacDirectory "ScipJavacCommandShim.cs"
    $JavacExecutable = Join-Path $JavacDirectory "javac.exe"
    $PartialJavacExecutable = Join-Path $JavacDirectory "javac.partial.exe"

    # Remove the obsolete co-located shim from earlier M0 runs: leaving a
    # javac.exe beside mvn.exe would shadow the JDK compiler in Git Bash.
    Remove-Item -LiteralPath (Join-Path $DestinationDirectory "javac.exe") -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath (Join-Path $DestinationDirectory "ScipJavacCommandShim.cs") -Force -ErrorAction SilentlyContinue

    @'
using System;
using System.Diagnostics;
using System.IO;
using System.Text;

internal static class MavenCommandShim
{
    private static string Quote(string value)
    {
        return "\"" + value.Replace("\"", "\"\"") + "\"";
    }

    private static int Main(string[] args)
    {
        string mavenCommand = Environment.GetEnvironmentVariable("MINOS_M0_MAVEN_CMD");
        if (String.IsNullOrEmpty(mavenCommand) || !File.Exists(mavenCommand))
        {
            Console.Error.WriteLine("MINOS Maven Wrapper command not found: " + mavenCommand);
            return 2;
        }

        string commandProcessor = Environment.GetEnvironmentVariable("ComSpec") ?? "cmd.exe";
        foreach (string pathVariable in new[] {
            "SCIP_ERRORPATH", "SCIP_JAVAC_OPTIONS_PREFIX", "SCIP_OLD_JAVAC_OPTS",
            "SCIP_PLUGINPATH", "SCIP_SOURCEROOT", "SCIP_TARGETROOT"
        })
        {
            string pathValue = Environment.GetEnvironmentVariable(pathVariable);
            if (!String.IsNullOrEmpty(pathValue))
            {
                Environment.SetEnvironmentVariable(pathVariable, pathValue.Replace('\\', '/'));
            }
        }
        const string compilerExecutablePrefix = "-Dmaven.compiler.executable=";
        for (int index = 0; index < args.Length; index++)
        {
            if (args[index].StartsWith(compilerExecutablePrefix, StringComparison.Ordinal))
            {
                string scipJavacScript = args[index].Substring(compilerExecutablePrefix.Length);
                string javacShim = Environment.GetEnvironmentVariable("MINOS_M0_SCIP_JAVAC_EXE");
                if (String.IsNullOrEmpty(javacShim) || !File.Exists(javacShim))
                {
                    Console.Error.WriteLine("MINOS scip-java javac shim not found: " + javacShim);
                    return 2;
                }
                Environment.SetEnvironmentVariable("MINOS_M0_SCIP_JAVAC_SCRIPT", scipJavacScript);
                args[index] = compilerExecutablePrefix + javacShim;
            }
        }

        var commandLine = new StringBuilder("/d /s /c \"\"");
        commandLine.Append(mavenCommand);
        commandLine.Append("\"");
        foreach (string argument in args)
        {
            commandLine.Append(' ');
            commandLine.Append(Quote(argument));
        }
        commandLine.Append("\"");

        var startInfo = new ProcessStartInfo(commandProcessor, commandLine.ToString())
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true
        };
        using (var process = new Process())
        {
            process.StartInfo = startInfo;
            process.OutputDataReceived += delegate(object sender, DataReceivedEventArgs eventArgs)
            {
                if (eventArgs.Data != null) Console.Out.WriteLine(eventArgs.Data);
            };
            process.ErrorDataReceived += delegate(object sender, DataReceivedEventArgs eventArgs)
            {
                if (eventArgs.Data != null) Console.Error.WriteLine(eventArgs.Data);
            };
            process.Start();
            process.BeginOutputReadLine();
            process.BeginErrorReadLine();
            process.WaitForExit();
            return process.ExitCode;
        }
    }
}
'@ | Set-Content -LiteralPath $Source -Encoding UTF8

    @'
using System;
using System.Diagnostics;
using System.IO;
using System.Text;

internal static class ScipJavacCommandShim
{
    private static string Quote(string value)
    {
        return "\"" + value.Replace("\"", "\\\"") + "\"";
    }

    private static int Main(string[] args)
    {
        string bashExecutable = Environment.GetEnvironmentVariable("MINOS_M0_BASH_EXE");
        string javacScript = Environment.GetEnvironmentVariable("MINOS_M0_SCIP_JAVAC_SCRIPT");
        if (String.IsNullOrEmpty(bashExecutable) || !File.Exists(bashExecutable))
        {
            Console.Error.WriteLine("MINOS Git Bash executable not found: " + bashExecutable);
            return 2;
        }
        if (String.IsNullOrEmpty(javacScript) || !File.Exists(javacScript))
        {
            Console.Error.WriteLine("MINOS scip-java javac script not found: " + javacScript);
            return 2;
        }

        var commandLine = new StringBuilder(Quote(javacScript.Replace('\\', '/')));
        foreach (string argument in args)
        {
            commandLine.Append(' ');
            commandLine.Append(Quote(argument));
        }

        var startInfo = new ProcessStartInfo(bashExecutable, commandLine.ToString())
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true
        };
        using (var process = new Process())
        {
            process.StartInfo = startInfo;
            process.OutputDataReceived += delegate(object sender, DataReceivedEventArgs eventArgs)
            {
                if (eventArgs.Data != null) Console.Out.WriteLine(eventArgs.Data);
            };
            process.ErrorDataReceived += delegate(object sender, DataReceivedEventArgs eventArgs)
            {
                if (eventArgs.Data != null) Console.Error.WriteLine(eventArgs.Data);
            };
            process.Start();
            process.BeginOutputReadLine();
            process.BeginErrorReadLine();
            process.WaitForExit();
            return process.ExitCode;
        }
    }
}
'@ | Set-Content -LiteralPath $JavacSource -Encoding UTF8

    Remove-Item -LiteralPath $PartialExecutable -Force -ErrorAction SilentlyContinue
    & $Compiler /nologo /target:exe "/out:$PartialExecutable" $Source
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $PartialExecutable -PathType Leaf)) {
        throw "Compilation of the local Maven executable shim failed with exit code $LASTEXITCODE"
    }

    Move-Item -LiteralPath $PartialExecutable -Destination $Executable -Force

    Remove-Item -LiteralPath $PartialJavacExecutable -Force -ErrorAction SilentlyContinue
    & $Compiler /nologo /target:exe "/out:$PartialJavacExecutable" $JavacSource
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $PartialJavacExecutable -PathType Leaf)) {
        throw "Compilation of the local scip-java javac executable shim failed with exit code $LASTEXITCODE"
    }

    Move-Item -LiteralPath $PartialJavacExecutable -Destination $JavacExecutable -Force
    return [pscustomobject]@{
        Maven = $Executable
        Javac = $JavacExecutable
    }
}

function Resolve-ScipJavaClasspath {
    param(
        [Parameter(Mandatory = $true)][string] $Coursier,
        [Parameter(Mandatory = $true)][string] $Coordinate
    )

    Write-Host "==> Resolve scip-java classpath with Coursier"
    $ClasspathOutput = & $Coursier fetch --classpath $Coordinate
    if ($LASTEXITCODE -ne 0) {
        throw "Coursier classpath resolution failed with exit code $LASTEXITCODE"
    }

    $Classpath = $ClasspathOutput |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
        Select-Object -Last 1
    if ([string]::IsNullOrWhiteSpace($Classpath)) {
        throw "Coursier did not return a classpath for $Coordinate"
    }

    return $Classpath.Trim()
}

function Build-ScipJavaWindowsPatch {
    param(
        [Parameter(Mandatory = $true)][string] $Classpath
    )

    $PatchSource = Join-Path $PSScriptRoot "scip-java-windows-patch\org\scip_code\scip_java\aggregator\ScipWriter.java"
    $PatchRoot = Join-Path $RepoRoot ".minos-m0\tools\scip-java-windows-patch"
    $PatchClasses = Join-Path $PatchRoot "classes"
    $PatchJar = Join-Path $PatchRoot "scip-java-windows-patch.jar"
    $PatchJarPartial = Join-Path $PatchRoot "scip-java-windows-patch.partial.jar"

    $Javac = Get-Command javac -ErrorAction SilentlyContinue
    $Jar = Get-Command jar -ErrorAction SilentlyContinue
    if (-not $Javac -or -not $Jar) {
        throw "javac and jar from the system JDK are required to build the scip-java Windows patch."
    }

    Remove-Item -LiteralPath $PatchClasses -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $PatchJarPartial -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $PatchClasses | Out-Null

    Write-Host "==> Build minimal scip-java 0.13.1 Windows aggregation patch"
    & $Javac.Source -classpath $Classpath -d $PatchClasses $PatchSource
    if ($LASTEXITCODE -ne 0) {
        throw "Compilation of the scip-java Windows patch failed with exit code $LASTEXITCODE"
    }

    & $Jar.Source --create --file $PatchJarPartial -C $PatchClasses .
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $PatchJarPartial -PathType Leaf)) {
        throw "Packaging of the scip-java Windows patch failed with exit code $LASTEXITCODE"
    }

    Move-Item -LiteralPath $PatchJarPartial -Destination $PatchJar -Force
    return $PatchJar
}

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw "Java command not found. Check the workstation Java configuration."
}

$ResolvedCoursierCommand = Resolve-ToolCommand `
    -ExplicitCommand $CoursierCommand `
    -LocalPath $LocalCoursier `
    -FallbackName "cs" `
    -DisplayName "Coursier"

$ResolvedScipCommand = Resolve-ToolCommand `
    -ExplicitCommand $ScipCommand `
    -LocalPath $LocalScip `
    -FallbackName "scip" `
    -DisplayName "SCIP CLI"

$ResolvedProjectPath = (Resolve-Path -LiteralPath $ProjectPath).Path
$ScipJavaMainClass = "org.scip_code.scip_java.ScipJava"
$Coordinate = "org.scip-code:scip-java:$ScipJavaVersion"
$ScipJavaClasspath = Resolve-ScipJavaClasspath -Coursier $ResolvedCoursierCommand -Coordinate $Coordinate
$ScipJavaWindowsPatch = Build-ScipJavaWindowsPatch -Classpath $ScipJavaClasspath
$ResolvedMavenBin = Resolve-MavenBinForScipJava
$ResolvedMavenCommand = Join-Path $ResolvedMavenBin "mvn.cmd"
$MavenShimBin = Join-Path $RepoRoot ".minos-m0\tools\maven-shim"
$CommandShims = Install-MavenExeShim -DestinationDirectory $MavenShimBin
$MavenShimCommand = $CommandShims.Maven
$ScipJavacShimCommand = $CommandShims.Javac
$GitBashCommand = Join-Path (Split-Path -Parent (Split-Path -Parent (Get-Command git).Source)) "bin\bash.exe"
if (-not (Test-Path -LiteralPath $GitBashCommand -PathType Leaf)) {
    throw "Git Bash is required for the scip-java 0.13.1 Unix javac launcher: $GitBashCommand"
}

if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $ResolvedProjectPath ".minos-m0\scip-java"
}

$ResolvedOutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $ResolvedOutputDirectory | Out-Null

$MetadataFile = Join-Path $ResolvedOutputDirectory "environment.txt"
$IndexDestination = Join-Path $ResolvedOutputDirectory "index.scip"
$LintFile = Join-Path $ResolvedOutputDirectory "lint.txt"
$StatsFile = Join-Path $ResolvedOutputDirectory "stats.txt"
$SnapshotDirectory = Join-Path $ResolvedOutputDirectory "snapshot"
$SnapshotLogFile = Join-Path $ResolvedOutputDirectory "snapshot.txt"

Write-Host "Project      : $ResolvedProjectPath"
Write-Host "Output       : $ResolvedOutputDirectory"
Write-Host "scip-java    : $ScipJavaVersion"
Write-Host "Coursier     : $ResolvedCoursierCommand"
Write-Host "SCIP CLI     : $ResolvedScipCommand"
Write-Host "Maven        : $ResolvedMavenCommand"
Write-Host "Maven shim   : $MavenShimCommand"
Write-Host "Javac shim   : $ScipJavacShimCommand"
Write-Host "Git Bash     : $GitBashCommand"
Write-Host "Windows patch: $ScipJavaWindowsPatch"
Write-Host

$PreviousPath = $env:PATH
$PreviousMavenCommand = [Environment]::GetEnvironmentVariable("MINOS_M0_MAVEN_CMD", "Process")
$PreviousScipJavacExecutable = [Environment]::GetEnvironmentVariable("MINOS_M0_SCIP_JAVAC_EXE", "Process")
$PreviousBashExecutable = [Environment]::GetEnvironmentVariable("MINOS_M0_BASH_EXE", "Process")
$env:MINOS_M0_MAVEN_CMD = $ResolvedMavenCommand
$env:MINOS_M0_SCIP_JAVAC_EXE = $ScipJavacShimCommand
$env:MINOS_M0_BASH_EXE = $GitBashCommand
$env:PATH = "$MavenShimBin;$PreviousPath"

Push-Location $ResolvedProjectPath
try {
    @(
        "date=$(Get-Date -Format o)",
        "project=$ResolvedProjectPath",
        "scipJavaVersion=$ScipJavaVersion",
        "coursierCommand=$ResolvedCoursierCommand",
        "coursierJvm=system",
        "scipCommand=$ResolvedScipCommand",
        "mavenCommand=$ResolvedMavenCommand",
        "mavenShim=$MavenShimCommand",
        "scipJavacShim=$ScipJavacShimCommand",
        "gitBashCommand=$GitBashCommand",
        "scipJavaWindowsPatch=$ScipJavaWindowsPatch"
    ) | Set-Content -Encoding UTF8 $MetadataFile

    "=== java -version ===" | Add-Content -Encoding UTF8 $MetadataFile
    (& java -version 2>&1 | Out-String) | Add-Content -Encoding UTF8 $MetadataFile

    "=== coursier --help ===" | Add-Content -Encoding UTF8 $MetadataFile
    (& $ResolvedCoursierCommand --help 2>&1 | Select-Object -First 20 | Out-String) | Add-Content -Encoding UTF8 $MetadataFile

    "=== scip --version ===" | Add-Content -Encoding UTF8 $MetadataFile
    (& $ResolvedScipCommand --version 2>&1 | Out-String) | Add-Content -Encoding UTF8 $MetadataFile

    Invoke-Checked -Command "java" -Arguments @(
        "-classpath",
        "$ScipJavaWindowsPatch;$ScipJavaClasspath",
        $ScipJavaMainClass,
        "index"
    ) -Description "Generate index.scip with scip-java"

    $GeneratedIndex = Join-Path $ResolvedProjectPath "index.scip"
    if (-not (Test-Path -LiteralPath $GeneratedIndex -PathType Leaf)) {
        throw "scip-java did not produce index.scip in $ResolvedProjectPath"
    }

    Copy-Item -LiteralPath $GeneratedIndex -Destination $IndexDestination -Force

    Write-Host "==> Run scip lint"
    & $ResolvedScipCommand lint $IndexDestination 2>&1 | Tee-Object -FilePath $LintFile
    $LintExitCode = $LASTEXITCODE

    Write-Host "==> Run scip stats"
    & $ResolvedScipCommand stats --from $IndexDestination --project-root $ResolvedProjectPath 2>&1 |
        Tee-Object -FilePath $StatsFile
    $StatsExitCode = $LASTEXITCODE

    if (Test-Path -LiteralPath $SnapshotDirectory) {
        Remove-Item -LiteralPath $SnapshotDirectory -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $SnapshotDirectory | Out-Null

    Write-Host "==> Generate SCIP snapshot"
    $SnapshotArguments = @(
        "snapshot",
        "--from",
        $IndexDestination,
        "--to",
        $SnapshotDirectory,
        "--project-root",
        $ResolvedProjectPath
    )
    & $ResolvedScipCommand @SnapshotArguments 2>&1 | Tee-Object -FilePath $SnapshotLogFile
    $SnapshotExitCode = $LASTEXITCODE
    if (-not (Test-Path -LiteralPath $SnapshotDirectory -PathType Container)) {
        New-Item -ItemType Directory -Force -Path $SnapshotDirectory | Out-Null
    }

    @(
        "lintExitCode=$LintExitCode",
        "statsExitCode=$StatsExitCode",
        "snapshotExitCode=$SnapshotExitCode"
    ) | Add-Content -Encoding UTF8 $MetadataFile

    Write-Host
    Write-Host "scip-java experiment artifacts preserved." -ForegroundColor Green
    Write-Host "Index     : $IndexDestination"
    Write-Host "Lint      : $LintFile"
    Write-Host "Stats     : $StatsFile"
    Write-Host "Snapshot  : $SnapshotDirectory"
    Write-Host "Snapshot log: $SnapshotLogFile"
    Write-Host "Context   : $MetadataFile"

    if ($LintExitCode -ne 0 -or $StatsExitCode -ne 0 -or $SnapshotExitCode -ne 0) {
        throw "SCIP post-processing completed with failures: lint=$LintExitCode, stats=$StatsExitCode, snapshot=$SnapshotExitCode"
    }
}
finally {
    Pop-Location
    $env:PATH = $PreviousPath
    if ($null -eq $PreviousMavenCommand) {
        Remove-Item -LiteralPath "Env:\MINOS_M0_MAVEN_CMD" -ErrorAction SilentlyContinue
    }
    else {
        $env:MINOS_M0_MAVEN_CMD = $PreviousMavenCommand
    }
    if ($null -eq $PreviousScipJavacExecutable) {
        Remove-Item -LiteralPath "Env:\MINOS_M0_SCIP_JAVAC_EXE" -ErrorAction SilentlyContinue
    }
    else {
        $env:MINOS_M0_SCIP_JAVAC_EXE = $PreviousScipJavacExecutable
    }
    if ($null -eq $PreviousBashExecutable) {
        Remove-Item -LiteralPath "Env:\MINOS_M0_BASH_EXE" -ErrorAction SilentlyContinue
    }
    else {
        $env:MINOS_M0_BASH_EXE = $PreviousBashExecutable
    }
}
