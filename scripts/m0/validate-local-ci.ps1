[CmdletBinding()]
param(
    [string] $OutputDirectory = ".minos-m0\validation\manual-ci",

    [switch] $AllowDirty
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$MavenWrapper = Join-Path $RepoRoot "mvnw.cmd"
$Java24FixturePom = Join-Path $RepoRoot "fixtures\java\java-24-smoke\pom.xml"

function Resolve-FromRepoRoot {
    param([Parameter(Mandatory = $true)][string] $Path)

    if ([System.IO.Path]::IsPathRooted($Path)) {
        return [System.IO.Path]::GetFullPath($Path)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $RepoRoot $Path))
}

function Resolve-OsArchitecture {
    $Architecture = [System.Environment]::GetEnvironmentVariable("PROCESSOR_ARCHITEW6432")
    if ([string]::IsNullOrWhiteSpace($Architecture)) {
        $Architecture = [System.Environment]::GetEnvironmentVariable("PROCESSOR_ARCHITECTURE")
    }
    if (-not [string]::IsNullOrWhiteSpace($Architecture)) {
        return $Architecture
    }
    if ([System.Environment]::Is64BitOperatingSystem) {
        return "64-bit"
    }
    return "32-bit"
}

function Write-TransactionalText {
    param(
        [Parameter(Mandatory = $true)][string] $Path,
        [Parameter(Mandatory = $true)][string] $Content
    )

    $PartialPath = "$Path.partial"
    [System.IO.File]::WriteAllText(
        $PartialPath,
        $Content,
        [System.Text.UTF8Encoding]::new($false)
    )
    Move-Item -LiteralPath $PartialPath -Destination $Path -Force
}

function Invoke-LoggedCommand {
    param(
        [Parameter(Mandatory = $true)][string] $Label,
        [Parameter(Mandatory = $true)][string] $LogPath,
        [Parameter(Mandatory = $true)][scriptblock] $Command
    )

    $PartialLogPath = "$LogPath.partial"
    $Stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    Write-Host "==> $Label" -ForegroundColor Cyan

    & $Command 2>&1 | Tee-Object -FilePath $PartialLogPath | Out-Host
    $ExitCode = $LASTEXITCODE
    $Stopwatch.Stop()

    Move-Item -LiteralPath $PartialLogPath -Destination $LogPath -Force
    return [pscustomobject]@{
        Label = $Label
        ExitCode = $ExitCode
        DurationMs = [Math]::Round($Stopwatch.Elapsed.TotalMilliseconds)
        LogPath = $LogPath
    }
}

if (-not (Test-Path -LiteralPath $MavenWrapper -PathType Leaf)) {
    throw "Maven Wrapper not found at $MavenWrapper."
}
if (-not (Test-Path -LiteralPath $Java24FixturePom -PathType Leaf)) {
    throw "Java 24 fixture POM not found at $Java24FixturePom."
}

Push-Location $RepoRoot
try {
    $GitCommit = (& git rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to resolve the current Git commit."
    }
    $GitBranch = (& git branch --show-current).Trim()
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to resolve the current Git branch."
    }
    $GitStatus = @(& git status --porcelain=v1 --untracked-files=all)
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to inspect the Git worktree."
    }
    $IsDirty = $GitStatus.Count -gt 0
    if ($IsDirty -and -not $AllowDirty) {
        throw "Manual CI requires a clean worktree. Commit or stash changes, or use -AllowDirty for a non-release diagnostic run."
    }

    $JavaCommand = (Get-Command java -CommandType Application -ErrorAction Stop).Source
    $JavaVersion = ((& $JavaCommand --version 2>&1) -join [Environment]::NewLine)
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to execute Java."
    }
    if ($JavaVersion -notmatch '(?im)^(?:openjdk|java)(?: version)?\s+"?24(?:\.|\s|")') {
        throw "Manual CI requires Java 24. Observed:`n$JavaVersion"
    }

    $MavenVersion = ((& $MavenWrapper --version 2>&1) -join [Environment]::NewLine)
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to execute Maven Wrapper."
    }
    if ($MavenVersion -notmatch '(?m)^Apache Maven 3\.9\.16(?:\s|$)') {
        throw "Manual CI requires Maven 3.9.16 through the Wrapper. Observed:`n$MavenVersion"
    }

    $ResolvedOutputDirectory = Resolve-FromRepoRoot $OutputDirectory
    $RunId = "$([DateTimeOffset]::Now.ToString('yyyyMMdd-HHmmss'))-$($GitCommit.Substring(0, 8))"
    $RunDirectory = Join-Path $ResolvedOutputDirectory $RunId
    New-Item -ItemType Directory -Force -Path $RunDirectory | Out-Null
    $OsArchitecture = Resolve-OsArchitecture

    $EnvironmentLines = @(
        "startedAt=$([DateTimeOffset]::Now.ToString('o'))",
        "runId=$RunId",
        "repository=$RepoRoot",
        "gitBranch=$GitBranch",
        "gitCommit=$GitCommit",
        "gitDirty=$($IsDirty.ToString().ToLowerInvariant())",
        "allowDirty=$($AllowDirty.IsPresent.ToString().ToLowerInvariant())",
        "os=$([System.Environment]::OSVersion.VersionString)",
        "architecture=$OsArchitecture",
        "javaCommand=$JavaCommand",
        "=== java --version ===",
        $JavaVersion,
        "=== mvnw.cmd --version ===",
        $MavenVersion
    )
    Write-TransactionalText `
        -Path (Join-Path $RunDirectory "environment.txt") `
        -Content (($EnvironmentLines -join [Environment]::NewLine) + [Environment]::NewLine)

    $Results = @(
        Invoke-LoggedCommand `
            -Label "MINOS clean verify" `
            -LogPath (Join-Path $RunDirectory "minos-clean-verify.txt") `
            -Command { & $MavenWrapper --batch-mode --no-transfer-progress clean verify }
        Invoke-LoggedCommand `
            -Label "java-24-smoke clean verify" `
            -LogPath (Join-Path $RunDirectory "java-24-smoke-clean-verify.txt") `
            -Command { & $MavenWrapper --batch-mode --no-transfer-progress -f $Java24FixturePom clean verify }
    )

    $Succeeded = @($Results | Where-Object ExitCode -ne 0).Count -eq 0
    $ResultLines = @(
        "completedAt=$([DateTimeOffset]::Now.ToString('o'))",
        "runId=$RunId",
        "status=$(if ($Succeeded) { 'SUCCESS' } else { 'FAILURE' })"
    )
    foreach ($Result in $Results) {
        $Key = $Result.Label.ToLowerInvariant().Replace(" ", "-")
        $ResultLines += "$Key.exitCode=$($Result.ExitCode)"
        $ResultLines += "$Key.durationMs=$($Result.DurationMs)"
        $ResultLines += "$Key.log=$($Result.LogPath)"
    }
    Write-TransactionalText `
        -Path (Join-Path $RunDirectory "result.txt") `
        -Content (($ResultLines -join [Environment]::NewLine) + [Environment]::NewLine)
    Write-TransactionalText `
        -Path (Join-Path $ResolvedOutputDirectory "latest.txt") `
        -Content ($RunDirectory + [Environment]::NewLine)

    Write-Host "Manual CI: $(if ($Succeeded) { 'SUCCESS' } else { 'FAILURE' })" `
        -ForegroundColor $(if ($Succeeded) { 'Green' } else { 'Red' })
    Write-Host "Evidence: $RunDirectory"

    if (-not $Succeeded) {
        exit 1
    }
}
finally {
    Pop-Location
}
