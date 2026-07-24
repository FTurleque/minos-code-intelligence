[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string] $ProjectPath,
    [Parameter(Mandatory = $true)][string] $CoursierCommand,
    [Parameter(Mandatory = $true)][string] $Coordinate,
    [Parameter(Mandatory = $true)][string] $OutputDirectory
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Resolve-JdkTool {
    param([Parameter(Mandatory = $true)][string] $Name)
    if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        throw 'scip-java requires JAVA_HOME to point to the project JDK.'
    }
    $candidate = Join-Path $env:JAVA_HOME "bin\$Name.exe"
    if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
        throw "JAVA_HOME does not expose $Name.exe: $env:JAVA_HOME"
    }
    return $candidate
}

function Resolve-MavenCommand {
    param([Parameter(Mandatory = $true)][string] $Root)

    $current = [System.IO.DirectoryInfo]::new($Root)
    while ($null -ne $current) {
        foreach ($name in @('mvnw.cmd', 'mvnw.bat')) {
            $candidate = Join-Path $current.FullName $name
            if (Test-Path -LiteralPath $candidate -PathType Leaf) {
                return (Resolve-Path -LiteralPath $candidate).Path
            }
        }
        $current = $current.Parent
    }

    foreach ($name in @('mvn.cmd', 'mvn.bat', 'mvn.exe', 'mvn')) {
        $command = Get-Command $name -ErrorAction SilentlyContinue
        if ($command) {
            return $command.Source
        }
    }
    throw 'scip-java requires Maven. No mvnw.cmd was found in the project/ancestors and Maven is not available in PATH.'
}

function Resolve-GitBash {
    $bash = Get-Command bash.exe -ErrorAction SilentlyContinue
    if ($bash) {
        return $bash.Source
    }
    $git = Get-Command git.exe -ErrorAction SilentlyContinue
    if ($git) {
        $gitRoot = Split-Path -Parent (Split-Path -Parent $git.Source)
        $candidate = Join-Path $gitRoot 'bin\bash.exe'
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return $candidate
        }
    }
    throw 'scip-java on Windows requires Git Bash (bash.exe).'
}

function Resolve-CSharpCompiler {
    $candidates = @(
        (Join-Path $env:SystemRoot 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'),
        (Join-Path $env:SystemRoot 'Microsoft.NET\Framework\v4.0.30319\csc.exe')
    )
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return $candidate
        }
    }
    $command = Get-Command csc.exe -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }
    throw 'scip-java on Windows requires csc.exe to build the local Maven/javac compatibility shims.'
}

function Install-CommandShims {
    param(
        [Parameter(Mandatory = $true)][string] $DestinationDirectory,
        [Parameter(Mandatory = $true)][string] $Compiler
    )

    New-Item -ItemType Directory -Force -Path $DestinationDirectory | Out-Null
    $javacDirectory = Join-Path $DestinationDirectory 'scip-javac'
    New-Item -ItemType Directory -Force -Path $javacDirectory | Out-Null

    $mavenSource = Join-Path $DestinationDirectory 'MavenCommandShim.cs'
    $mavenExe = Join-Path $DestinationDirectory 'mvn.exe'
    $mavenPartial = Join-Path $DestinationDirectory 'mvn.partial.exe'
    $javacSource = Join-Path $javacDirectory 'ScipJavacCommandShim.cs'
    $javacExe = Join-Path $javacDirectory 'javac.exe'
    $javacPartial = Join-Path $javacDirectory 'javac.partial.exe'

    @'
using System;
using System.Diagnostics;
using System.IO;
using System.Text;

internal static class MavenCommandShim
{
    private static string Quote(string value) { return "\"" + value.Replace("\"", "\"\"") + "\""; }

    private static int Main(string[] args)
    {
        string mavenCommand = Environment.GetEnvironmentVariable("MINOS_M14_MAVEN_CMD");
        if (String.IsNullOrEmpty(mavenCommand) || !File.Exists(mavenCommand))
        {
            Console.Error.WriteLine("MINOS Maven command not found: " + mavenCommand);
            return 2;
        }

        foreach (string name in new[] {
            "SCIP_ERRORPATH", "SCIP_JAVAC_OPTIONS_PREFIX", "SCIP_OLD_JAVAC_OPTS",
            "SCIP_PLUGINPATH", "SCIP_SOURCEROOT", "SCIP_TARGETROOT"
        })
        {
            string value = Environment.GetEnvironmentVariable(name);
            if (!String.IsNullOrEmpty(value)) Environment.SetEnvironmentVariable(name, value.Replace('\\', '/'));
        }

        const string compilerPrefix = "-Dmaven.compiler.executable=";
        for (int i = 0; i < args.Length; i++)
        {
            if (args[i].StartsWith(compilerPrefix, StringComparison.Ordinal))
            {
                string script = args[i].Substring(compilerPrefix.Length);
                string shim = Environment.GetEnvironmentVariable("MINOS_M14_SCIP_JAVAC_EXE");
                if (String.IsNullOrEmpty(shim) || !File.Exists(shim))
                {
                    Console.Error.WriteLine("MINOS scip-java javac shim not found: " + shim);
                    return 2;
                }
                Environment.SetEnvironmentVariable("MINOS_M14_SCIP_JAVAC_SCRIPT", script);
                args[i] = compilerPrefix + shim;
            }
        }

        string commandProcessor = Environment.GetEnvironmentVariable("ComSpec") ?? "cmd.exe";
        var commandLine = new StringBuilder("/d /s /c \"\"");
        commandLine.Append(mavenCommand).Append("\"");
        foreach (string arg in args) commandLine.Append(' ').Append(Quote(arg));
        commandLine.Append("\"");

        var startInfo = new ProcessStartInfo(commandProcessor, commandLine.ToString())
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true
        };
        using (var process = Process.Start(startInfo))
        {
            process.OutputDataReceived += delegate(object sender, DataReceivedEventArgs e) { if (e.Data != null) Console.Out.WriteLine(e.Data); };
            process.ErrorDataReceived += delegate(object sender, DataReceivedEventArgs e) { if (e.Data != null) Console.Error.WriteLine(e.Data); };
            process.BeginOutputReadLine();
            process.BeginErrorReadLine();
            process.WaitForExit();
            return process.ExitCode;
        }
    }
}
'@ | Set-Content -LiteralPath $mavenSource -Encoding UTF8

    @'
using System;
using System.Diagnostics;
using System.IO;
using System.Text;

internal static class ScipJavacCommandShim
{
    private static string Quote(string value) { return "\"" + value.Replace("\"", "\\\"") + "\""; }

    private static int Main(string[] args)
    {
        string bash = Environment.GetEnvironmentVariable("MINOS_M14_BASH_EXE");
        string script = Environment.GetEnvironmentVariable("MINOS_M14_SCIP_JAVAC_SCRIPT");
        if (String.IsNullOrEmpty(bash) || !File.Exists(bash))
        {
            Console.Error.WriteLine("MINOS Git Bash executable not found: " + bash);
            return 2;
        }
        if (String.IsNullOrEmpty(script) || !File.Exists(script))
        {
            Console.Error.WriteLine("MINOS scip-java javac script not found: " + script);
            return 2;
        }

        var commandLine = new StringBuilder(Quote(script.Replace('\\', '/')));
        foreach (string arg in args) commandLine.Append(' ').Append(Quote(arg));
        var startInfo = new ProcessStartInfo(bash, commandLine.ToString())
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true
        };
        using (var process = Process.Start(startInfo))
        {
            process.OutputDataReceived += delegate(object sender, DataReceivedEventArgs e) { if (e.Data != null) Console.Out.WriteLine(e.Data); };
            process.ErrorDataReceived += delegate(object sender, DataReceivedEventArgs e) { if (e.Data != null) Console.Error.WriteLine(e.Data); };
            process.BeginOutputReadLine();
            process.BeginErrorReadLine();
            process.WaitForExit();
            return process.ExitCode;
        }
    }
}
'@ | Set-Content -LiteralPath $javacSource -Encoding UTF8

    Remove-Item -LiteralPath $mavenPartial, $javacPartial -Force -ErrorAction SilentlyContinue
    & $Compiler /nologo /target:exe "/out:$mavenPartial" $mavenSource
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $mavenPartial -PathType Leaf)) {
        throw "Compilation of Maven shim failed with exit code $LASTEXITCODE"
    }
    Move-Item -LiteralPath $mavenPartial -Destination $mavenExe -Force

    & $Compiler /nologo /target:exe "/out:$javacPartial" $javacSource
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $javacPartial -PathType Leaf)) {
        throw "Compilation of javac shim failed with exit code $LASTEXITCODE"
    }
    Move-Item -LiteralPath $javacPartial -Destination $javacExe -Force

    return [pscustomobject]@{ Maven = $mavenExe; Javac = $javacExe }
}

function Resolve-ScipJavaClasspath {
    param([string] $Coursier, [string] $ScipCoordinate)
    $lines = & $Coursier fetch --classpath $ScipCoordinate 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Coursier classpath resolution failed ($LASTEXITCODE): $($lines | Out-String)"
    }
    $classpath = $lines | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Last 1
    if ([string]::IsNullOrWhiteSpace($classpath)) {
        throw "Coursier returned no classpath for $ScipCoordinate"
    }
    return $classpath.Trim()
}

function Build-WindowsPatch {
    param(
        [string] $Classpath,
        [string] $WorkRoot,
        [string] $Javac,
        [string] $Jar
    )
    $source = Join-Path $PSScriptRoot 'ScipWriter.java'
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
        throw "Embedded ScipWriter Windows patch is missing: $source"
    }
    $classes = Join-Path $WorkRoot 'patch-classes'
    $patch = Join-Path $WorkRoot 'scip-java-windows-patch.jar'
    $partial = Join-Path $WorkRoot 'scip-java-windows-patch.partial.jar'
    Remove-Item -LiteralPath $classes -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $partial -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $classes | Out-Null

    & $Javac -classpath $Classpath -d $classes $source
    if ($LASTEXITCODE -ne 0) { throw "Compilation of scip-java Windows patch failed with exit code $LASTEXITCODE" }
    & $Jar --create --file $partial -C $classes .
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $partial -PathType Leaf)) {
        throw "Packaging of scip-java Windows patch failed with exit code $LASTEXITCODE"
    }
    Move-Item -LiteralPath $partial -Destination $patch -Force
    return $patch
}

$project = (Resolve-Path -LiteralPath $ProjectPath).Path
$coursier = (Resolve-Path -LiteralPath $CoursierCommand).Path
$output = [System.IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $output | Out-Null
$workRoot = Join-Path $PSScriptRoot 'windows-work'
New-Item -ItemType Directory -Force -Path $workRoot | Out-Null

$java = Resolve-JdkTool -Name 'java'
$javac = Resolve-JdkTool -Name 'javac'
$jar = Resolve-JdkTool -Name 'jar'
$maven = Resolve-MavenCommand -Root $project
$bash = Resolve-GitBash
$csc = Resolve-CSharpCompiler
$shims = Install-CommandShims -DestinationDirectory (Join-Path $workRoot 'command-shims') -Compiler $csc
$classpath = Resolve-ScipJavaClasspath -Coursier $coursier -ScipCoordinate $Coordinate
$patch = Build-WindowsPatch -Classpath $classpath -WorkRoot $workRoot -Javac $javac -Jar $jar

$generated = Join-Path $project 'index.scip'
$destination = Join-Path $output 'index.scip'
$backup = Join-Path $output 'preexisting-project-index.scip'
$hadExisting = Test-Path -LiteralPath $generated -PathType Leaf
Remove-Item -LiteralPath $destination, $backup -Force -ErrorAction SilentlyContinue
if ($hadExisting) { Move-Item -LiteralPath $generated -Destination $backup -Force }

$oldPath = $env:PATH
$oldMaven = [Environment]::GetEnvironmentVariable('MINOS_M14_MAVEN_CMD', 'Process')
$oldJavac = [Environment]::GetEnvironmentVariable('MINOS_M14_SCIP_JAVAC_EXE', 'Process')
$oldBash = [Environment]::GetEnvironmentVariable('MINOS_M14_BASH_EXE', 'Process')
$env:MINOS_M14_MAVEN_CMD = $maven
$env:MINOS_M14_SCIP_JAVAC_EXE = $shims.Javac
$env:MINOS_M14_BASH_EXE = $bash
$env:PATH = "$(Split-Path -Parent $shims.Maven);$oldPath"

try {
    Write-Output "MINOS scip-java Windows runtime"
    Write-Output "project=$project"
    Write-Output "coordinate=$Coordinate"
    Write-Output "maven=$maven"
    Write-Output "bash=$bash"

    Push-Location $project
    try {
        & $java -classpath "$patch;$classpath" org.scip_code.scip_java.ScipJava index
        $exit = $LASTEXITCODE
    }
    finally {
        Pop-Location
    }
    if ($exit -ne 0) { throw "scip-java index failed with exit code $exit" }
    if (-not (Test-Path -LiteralPath $generated -PathType Leaf) -or (Get-Item -LiteralPath $generated).Length -eq 0) {
        throw "scip-java did not produce a non-empty index.scip in $project"
    }
    Copy-Item -LiteralPath $generated -Destination $destination -Force
}
finally {
    Remove-Item -LiteralPath $generated -Force -ErrorAction SilentlyContinue
    if ($hadExisting -and (Test-Path -LiteralPath $backup -PathType Leaf)) {
        Move-Item -LiteralPath $backup -Destination $generated -Force
    }
    $env:PATH = $oldPath
    [Environment]::SetEnvironmentVariable('MINOS_M14_MAVEN_CMD', $oldMaven, 'Process')
    [Environment]::SetEnvironmentVariable('MINOS_M14_SCIP_JAVAC_EXE', $oldJavac, 'Process')
    [Environment]::SetEnvironmentVariable('MINOS_M14_BASH_EXE', $oldBash, 'Process')
}

if (-not (Test-Path -LiteralPath $destination -PathType Leaf) -or (Get-Item -LiteralPath $destination).Length -eq 0) {
    throw "MINOS scip-java runner did not publish $destination"
}
