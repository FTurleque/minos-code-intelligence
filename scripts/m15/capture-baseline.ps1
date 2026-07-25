[CmdletBinding()]
param(
    [string] $ExpectedHead = '',
    [switch] $SkipM14Replay,
    [switch] $SkipProviderReplays,
    [switch] $ValidateDocker
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
. (Join-Path $RepoRoot 'scripts\windows\MinosWindows.ps1')

function Invoke-NativeCaptured {
    param(
        [Parameter(Mandatory = $true)][string] $File,
        [Parameter(Mandatory = $true)][string[]] $Arguments,
        [Parameter(Mandatory = $true)][string] $LogPath,
        [Parameter(Mandatory = $true)][string] $Failure
    )

    # Windows PowerShell 5.1 materializes native stderr as ErrorRecord objects
    # when stderr is merged into the success stream. With the script-wide
    # ErrorActionPreference=Stop, harmless JVM warnings would therefore abort
    # an otherwise successful Maven run. Native process success is determined
    # exclusively from its exit code here; stderr is still preserved in logs.
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $File @Arguments 2>&1 |
            ForEach-Object { $_.ToString() } |
            Tee-Object -FilePath $LogPath
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    if ($exitCode -ne 0) {
        throw "$Failure (exit=$exitCode)"
    }
}

function Get-JUnitSummary {
    param([Parameter(Mandatory = $true)][string] $Root)

    $reportFiles = Get-ChildItem -LiteralPath $Root -Recurse -File -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue |
        Where-Object {
            $_.FullName -match '[\\/]target[\\/](surefire-reports|failsafe-reports)[\\/]TEST-.*\.xml$'
        }

    $tests = 0L
    $failures = 0L
    $errors = 0L
    $skipped = 0L

    foreach ($file in $reportFiles) {
        [xml] $document = Get-Content -LiteralPath $file.FullName -Raw
        $suite = $document.testsuite
        if ($null -eq $suite) {
            continue
        }
        $tests += [long] $suite.tests
        $failures += [long] $suite.failures
        $errors += [long] $suite.errors
        $skipped += [long] $suite.skipped
    }

    return [pscustomobject]@{
        reportFiles = @($reportFiles).Count
        tests       = $tests
        failures    = $failures
        errors      = $errors
        skipped     = $skipped
    }
}

function Get-ReactorModuleCount {
    param([Parameter(Mandatory = $true)][string] $PomPath)

    [xml] $pom = Get-Content -LiteralPath $PomPath -Raw

    # The M14 baseline is intentionally a single-module Maven project and
    # therefore has no <modules> element. XPath keeps this StrictMode-safe
    # while remaining compatible with the multi-module POM introduced by M15-S2.
    $modules = @($pom.SelectNodes('/*[local-name()="project"]/*[local-name()="modules"]/*[local-name()="module"]'))
    return 1 + $modules.Count
}

function Write-MarkdownReport {
    param(
        [Parameter(Mandatory = $true)][pscustomobject] $Report,
        [Parameter(Mandatory = $true)][string] $Path
    )

    $lines = @(
        '# M15-S1 — Baseline capture',
        '',
        "- UTC: ``$($Report.timestampUtc)``",
        "- HEAD: ``$($Report.head)``",
        "- clean worktree: ``$($Report.cleanWorktree)``",
        "- Java: ``$($Report.javaVersion)``",
        "- Maven: ``$($Report.mavenVersionLine)``",
        "- reactor modules: ``$($Report.reactorModules)``",
        "- main Java sources: ``$($Report.mainSourceCount)``",
        "- test Java sources: ``$($Report.testSourceCount)``",
        '',
        '## Maven verify',
        '',
        "- status: **$($Report.verifyStatus)**",
        "- tests: ``$($Report.junit.tests)``",
        "- failures: ``$($Report.junit.failures)``",
        "- errors: ``$($Report.junit.errors)``",
        "- skipped: ``$($Report.junit.skipped)``",
        "- XML reports: ``$($Report.junit.reportFiles)``",
        '',
        '## M14 replay',
        '',
        "- status: **$($Report.m14ReplayStatus)**",
        "- provider replays skipped: ``$($Report.providerReplaysSkipped)``",
        "- Docker validation requested: ``$($Report.dockerValidationRequested)``"
    )

    if (-not [string]::IsNullOrWhiteSpace($Report.failure)) {
        $lines += @('', '## Failure', '', '```text', $Report.failure, '```')
    }

    Set-Content -LiteralPath $Path -Value $lines -Encoding utf8
}

Push-Location $RepoRoot
try {
    $head = ((& git rev-parse HEAD) | Select-Object -First 1).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($head)) {
        throw 'Unable to resolve current Git HEAD.'
    }
    if (-not [string]::IsNullOrWhiteSpace($ExpectedHead) -and $head -ne $ExpectedHead) {
        throw "HEAD mismatch. Expected $ExpectedHead, found $head"
    }

    $dirty = @(& git status --porcelain)
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to inspect Git worktree status.'
    }
    if ($dirty.Count -gt 0) {
        throw "M15-S1 baseline requires a clean worktree. Dirty entries:`n$($dirty -join "`n")"
    }

    $java = Resolve-MinosJava24
    $env:JAVA_HOME = $java.JavaHome
    $env:Path = "$($java.JavaHome)\bin;$env:Path"

    $mavenVersionOutput = (& .\mvnw.cmd -version 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to resolve Maven Wrapper version.'
    }
    $mavenVersionLine = ($mavenVersionOutput -split "`r?`n" | Select-Object -First 1).Trim()

    $mainSourceCount = @(Get-ChildItem -LiteralPath (Join-Path $RepoRoot 'src\main\java') -Recurse -File -Filter '*.java' -ErrorAction SilentlyContinue).Count
    $testSourceCount = @(Get-ChildItem -LiteralPath (Join-Path $RepoRoot 'src\test\java') -Recurse -File -Filter '*.java' -ErrorAction SilentlyContinue).Count
    $reactorModules = Get-ReactorModuleCount -PomPath (Join-Path $RepoRoot 'pom.xml')

    $tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("minos-m15-baseline-" + $head.Substring(0, 12))
    Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null

    $verifyLog = Join-Path $tempRoot 'maven-verify.log'
    $m14Log = Join-Path $tempRoot 'm14-replay.log'
    $verifyStatus = 'NOT_RUN'
    $m14ReplayStatus = if ($SkipM14Replay) { 'SKIPPED' } else { 'NOT_RUN' }
    $failure = ''
    $started = [DateTimeOffset]::UtcNow

    Write-Host '=== M15-S1 exact-head baseline ===' -ForegroundColor Cyan
    Write-Host "HEAD : $head"
    Write-Host "Java : $($java.VersionLine)"
    Write-Host "Maven: $mavenVersionLine"

    try {
        Invoke-NativeCaptured -File '.\mvnw.cmd' -Arguments @('clean', 'verify') -LogPath $verifyLog `
            -Failure 'MINOS clean verify failed'
        $verifyStatus = 'PASS'

        if (-not $SkipM14Replay) {
            # Run M14 validation in a separate Windows PowerShell process so its
            # native stderr cannot be reinterpreted as a terminating ErrorRecord
            # by this parent PowerShell 5.1 session.
            $powerShell = (Get-Command 'powershell.exe' -ErrorAction Stop).Source
            $m14Script = Join-Path $RepoRoot 'scripts\m14\validate-local.ps1'
            $m14Arguments = @(
                '-NoProfile',
                '-ExecutionPolicy', 'Bypass',
                '-File', $m14Script,
                '-ExpectedHead', $head
            )
            if ($SkipProviderReplays) {
                $m14Arguments += '-SkipProviderReplays'
            }
            if ($ValidateDocker) {
                $m14Arguments += '-ValidateDocker'
            }

            Invoke-NativeCaptured -File $powerShell -Arguments $m14Arguments -LogPath $m14Log `
                -Failure 'M14 replay failed'
            $m14ReplayStatus = 'PASS'
        }
    }
    catch {
        if ($verifyStatus -ne 'PASS') {
            $verifyStatus = 'FAIL'
        }
        elseif (-not $SkipM14Replay -and $m14ReplayStatus -ne 'PASS') {
            $m14ReplayStatus = 'FAIL'
        }
        $failure = $_.Exception.Message
    }

    $junit = Get-JUnitSummary -Root $RepoRoot
    $finished = [DateTimeOffset]::UtcNow

    $report = [pscustomobject]@{
        schemaVersion             = 1
        milestone                 = 'M15-S1'
        timestampUtc              = $finished.ToString('O')
        durationSeconds           = [Math]::Round(($finished - $started).TotalSeconds, 3)
        head                      = $head
        cleanWorktree             = $true
        javaVersion               = $java.VersionLine
        javaHome                  = $java.JavaHome
        mavenVersionLine          = $mavenVersionLine
        reactorModules            = $reactorModules
        mainSourceCount           = $mainSourceCount
        testSourceCount           = $testSourceCount
        verifyStatus              = $verifyStatus
        junit                     = $junit
        m14ReplayStatus           = $m14ReplayStatus
        providerReplaysSkipped    = [bool] $SkipProviderReplays
        dockerValidationRequested = [bool] $ValidateDocker
        failure                   = $failure
    }

    $finalRoot = Join-Path $RepoRoot 'target\m15-baseline'
    Remove-Item -LiteralPath $finalRoot -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $finalRoot | Out-Null

    Copy-Item -LiteralPath $verifyLog -Destination (Join-Path $finalRoot 'maven-verify.log') -Force -ErrorAction SilentlyContinue
    if (Test-Path -LiteralPath $m14Log -PathType Leaf) {
        Copy-Item -LiteralPath $m14Log -Destination (Join-Path $finalRoot 'm14-replay.log') -Force
    }

    $report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $finalRoot 'baseline.json') -Encoding utf8
    Write-MarkdownReport -Report $report -Path (Join-Path $finalRoot 'baseline.md')

    Write-Host ''
    Write-Host "Baseline artifacts: $finalRoot" -ForegroundColor Green
    Write-Host "verify=$verifyStatus m14Replay=$m14ReplayStatus tests=$($junit.tests) failures=$($junit.failures) errors=$($junit.errors)"

    if (-not [string]::IsNullOrWhiteSpace($failure)) {
        throw $failure
    }
}
finally {
    Pop-Location
}
