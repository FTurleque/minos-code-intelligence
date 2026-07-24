[CmdletBinding()]
param(
    [string] $ExpectedHead = '',
    [string] $ReleaseVersion = '0.2.0-rc1',
    [switch] $SkipProviderReplays,
    [switch] $ValidateDocker
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
. (Join-Path $RepoRoot 'scripts\windows\MinosWindows.ps1')

function Invoke-NativeChecked {
    param(
        [Parameter(Mandatory = $true)][string] $File,
        [Parameter(Mandatory = $true)][string[]] $Arguments,
        [Parameter(Mandatory = $true)][string] $Failure
    )
    & $File @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Failure (exit=$LASTEXITCODE)"
    }
}

function Resolve-PowerShellHost {
    foreach ($name in @('powershell.exe', 'pwsh.exe')) {
        $command = Get-Command $name -ErrorAction SilentlyContinue
        if ($command) {
            return $command.Source
        }
    }
    throw 'M14 validation requires Windows PowerShell or PowerShell 7.'
}

function Write-LatestProviderDiagnostics {
    $runsRoot = Join-Path $script:ValidationHome 'runs'
    if (-not (Test-Path -LiteralPath $runsRoot -PathType Container)) {
        return
    }
    $latestProcess = Get-ChildItem -LiteralPath $runsRoot -Recurse -File -Filter 'process.txt' |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1
    if (-not $latestProcess) {
        return
    }

    Write-Host ''
    Write-Host '=== Latest provider diagnostics ===' -ForegroundColor Yellow
    Write-Host "directory: $($latestProcess.Directory.FullName)"
    foreach ($name in @('process.txt', 'provider.stdout.log', 'provider.stderr.log')) {
        $file = Join-Path $latestProcess.Directory.FullName $name
        if (Test-Path -LiteralPath $file -PathType Leaf) {
            Write-Host "--- $name ---" -ForegroundColor Yellow
            Get-Content -LiteralPath $file -Tail 160 | ForEach-Object { Write-Host $_ }
        }
    }
}

function Invoke-MinosJson {
    param([Parameter(Mandatory = $true)][string[]] $Arguments)
    $lines = & $script:JavaExecutable "-Dminos.home=$script:ValidationHome" -jar $script:MinosJar @Arguments
    if ($LASTEXITCODE -ne 0) {
        $exitCode = $LASTEXITCODE
        Write-LatestProviderDiagnostics
        throw "MINOS command failed: $($Arguments -join ' ') (exit=$exitCode)"
    }
    return (($lines | Out-String).Trim() | ConvertFrom-Json)
}

Push-Location $RepoRoot
try {
    $Head = ((& git rev-parse HEAD) | Select-Object -First 1).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($Head)) {
        throw 'Unable to resolve the current Git HEAD.'
    }
    if (-not [string]::IsNullOrWhiteSpace($ExpectedHead) -and $Head -ne $ExpectedHead) {
        throw "HEAD mismatch. Expected $ExpectedHead, found $Head"
    }
    $Dirty = @(& git status --porcelain)
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to inspect Git worktree status.'
    }
    if ($Dirty.Count -gt 0) {
        throw "M14 validation requires a clean worktree. Dirty entries:`n$($Dirty -join "`n")"
    }

    $Java = Resolve-MinosJava24
    $script:JavaExecutable = $Java.JavaExecutable
    $script:PowerShellExecutable = Resolve-PowerShellHost
    $env:JAVA_HOME = $Java.JavaHome
    $env:Path = "$($Java.JavaHome)\bin;$env:Path"

    Write-Host '=== M14 exact-head validation ===' -ForegroundColor Cyan
    Write-Host "HEAD : $Head"
    Write-Host "Java : $($Java.VersionLine)"

    Invoke-NativeChecked -File '.\mvnw.cmd' -Arguments @('clean', 'verify') `
        -Failure 'MINOS clean verify failed'

    $script:MinosJar = Get-ChildItem -LiteralPath (Join-Path $RepoRoot 'target') -File `
        -Filter 'minos-code-intelligence-*-all.jar' |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1 -ExpandProperty FullName
    if ([string]::IsNullOrWhiteSpace($script:MinosJar)) {
        throw 'Shaded MINOS JAR is missing after verify.'
    }

    # Keep validation state outside target: the release build performs another
    # `clean verify` and must not erase provider installations or the test home.
    $ValidationRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("minos-m14-" + $Head.Substring(0, 12))
    $script:ValidationHome = Join-Path $ValidationRoot 'home'
    $InstallRoot = Join-Path $ValidationRoot 'installed'
    Remove-Item -LiteralPath $ValidationRoot -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $script:ValidationHome | Out-Null
    $env:MINOS_HOME = $script:ValidationHome

    Invoke-NativeChecked -File $script:JavaExecutable `
        -Arguments @("-Dminos.home=$script:ValidationHome", '-jar', $script:MinosJar, '--version') `
        -Failure 'MINOS --version failed'

    if (-not $SkipProviderReplays) {
        Write-Host ''
        Write-Host '=== TypeScript provider replay ===' -ForegroundColor Cyan
        $TsFixture = Join-Path $RepoRoot 'fixtures\typescript\typescript-simple'
        $Npm = Get-Command npm -ErrorAction SilentlyContinue
        $Node = Get-Command node -ErrorAction SilentlyContinue
        if (-not $Npm -or -not $Node) {
            throw 'TypeScript replay requires node and npm in PATH.'
        }
        try {
            Invoke-NativeChecked -File $Npm.Source -Arguments @('ci', '--prefix', $TsFixture, '--no-audit', '--no-fund') `
                -Failure 'Fixture npm ci failed'
            Invoke-NativeChecked -File $script:JavaExecutable `
                -Arguments @("-Dminos.home=$script:ValidationHome", '-jar', $script:MinosJar,
                    'tools', 'install', 'scip-typescript') `
                -Failure 'scip-typescript managed installation failed'
            Invoke-NativeChecked -File $script:JavaExecutable `
                -Arguments @("-Dminos.home=$script:ValidationHome", '-jar', $script:MinosJar,
                    'project', 'add', $TsFixture, '--name', 'm14-typescript') `
                -Failure 'TypeScript fixture registration failed'

            $TsDryRun = Invoke-MinosJson @('index', 'm14-typescript', '--dry-run', '--format', 'json')
            if ($TsDryRun.mode -ne 'FULL') {
                throw "Expected first TypeScript plan FULL, found $($TsDryRun.mode)"
            }
            $TsFirst = Invoke-MinosJson @('index', 'm14-typescript', '--format', 'json')
            if ($TsFirst.status -ne 'SUCCEEDED') {
                throw "Expected TypeScript SUCCEEDED, found $($TsFirst.status)"
            }
            $TsSecond = Invoke-MinosJson @('index', 'm14-typescript', '--format', 'json')
            if ($TsSecond.status -ne 'NO_CHANGES' -or $TsSecond.plan.mode -ne 'NONE') {
                throw "Expected TypeScript second run NO_CHANGES/NONE, found $($TsSecond.status)/$($TsSecond.plan.mode)"
            }
        }
        finally {
            Remove-Item -LiteralPath (Join-Path $TsFixture 'node_modules') -Recurse -Force -ErrorAction SilentlyContinue
            Remove-Item -LiteralPath (Join-Path $TsFixture 'dist') -Recurse -Force -ErrorAction SilentlyContinue
            Remove-Item -LiteralPath (Join-Path $TsFixture 'index.scip') -Force -ErrorAction SilentlyContinue
        }

        Write-Host ''
        Write-Host '=== Java provider replay + STALE recovery ===' -ForegroundColor Cyan
        $JavaFixture = Join-Path $RepoRoot 'fixtures\java\java-simple'
        Invoke-NativeChecked -File $script:JavaExecutable `
            -Arguments @("-Dminos.home=$script:ValidationHome", '-jar', $script:MinosJar,
                'tools', 'install', 'scip-java') `
            -Failure 'scip-java managed installation failed'
        Invoke-NativeChecked -File $script:JavaExecutable `
            -Arguments @("-Dminos.home=$script:ValidationHome", '-jar', $script:MinosJar,
                'project', 'add', $JavaFixture, '--name', 'm14-java') `
            -Failure 'Java fixture registration failed'

        $JavaSource = Join-Path $JavaFixture 'src\main\java\com\minos\fixture\UserService.java'
        $JavaSourceBytes = [System.IO.File]::ReadAllBytes($JavaSource)
        try {
            $JavaDryRun = Invoke-MinosJson @('index', 'm14-java', '--dry-run', '--format', 'json')
            if ($JavaDryRun.mode -ne 'FULL') {
                throw "Expected first Java plan FULL, found $($JavaDryRun.mode)"
            }
            $JavaFirst = Invoke-MinosJson @('index', 'm14-java', '--format', 'json')
            if ($JavaFirst.status -ne 'SUCCEEDED') {
                throw "Expected Java SUCCEEDED, found $($JavaFirst.status)"
            }
            $JavaSecond = Invoke-MinosJson @('index', 'm14-java', '--format', 'json')
            if ($JavaSecond.status -ne 'NO_CHANGES' -or $JavaSecond.plan.mode -ne 'NONE') {
                throw "Expected Java second run NO_CHANGES/NONE, found $($JavaSecond.status)/$($JavaSecond.plan.mode)"
            }

            Add-Content -LiteralPath $JavaSource -Value "`nTHIS_IS_AN_INTENTIONAL_M14_COMPILE_FAILURE" -Encoding utf8
            $FailureOutput = & $script:JavaExecutable "-Dminos.home=$script:ValidationHome" -jar $script:MinosJar `
                index m14-java --format json 2>&1
            $FailureExit = $LASTEXITCODE
            if ($FailureExit -eq 0) {
                throw "Expected Java refresh failure, but index returned success: $($FailureOutput | Out-String)"
            }

            $StaleStatus = Invoke-MinosJson @('index-status', 'm14-java', '--format', 'json')
            if ($StaleStatus.state -ne 'STALE') {
                throw "Expected STALE after failed Java refresh, found $($StaleStatus.state)"
            }
            if ($StaleStatus.activeSnapshotId -ne $JavaFirst.activeSnapshotId) {
                throw "Failed refresh replaced the active snapshot: before=$($JavaFirst.activeSnapshotId) after=$($StaleStatus.activeSnapshotId)"
            }
        }
        finally {
            [System.IO.File]::WriteAllBytes($JavaSource, $JavaSourceBytes)
            Remove-Item -LiteralPath (Join-Path $JavaFixture 'index.scip') -Force -ErrorAction SilentlyContinue
            Remove-Item -LiteralPath (Join-Path $JavaFixture 'target') -Recurse -Force -ErrorAction SilentlyContinue
        }

        $JavaRecovery = Invoke-MinosJson @('index', 'm14-java', '--force-full', '--format', 'json')
        if ($JavaRecovery.status -ne 'SUCCEEDED') {
            throw "Expected Java recovery SUCCEEDED, found $($JavaRecovery.status)"
        }
    }

    Write-Host ''
    Write-Host '=== Windows distribution ===' -ForegroundColor Cyan
    Invoke-NativeChecked -File $script:PowerShellExecutable `
        -Arguments @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
            (Join-Path $RepoRoot 'scripts\release\build-windows-distribution.ps1'),
            '-Version', $ReleaseVersion) `
        -Failure 'Windows release packaging failed'

    $Zip = Join-Path $RepoRoot "target\dist\minos-$ReleaseVersion-windows-x64.zip"
    $Checksum = "$Zip.sha256"
    if (-not (Test-Path -LiteralPath $Zip -PathType Leaf) -or
        -not (Test-Path -LiteralPath $Checksum -PathType Leaf)) {
        throw 'Windows distribution ZIP/checksum is missing.'
    }
    $ExpectedHash = ((Get-Content -LiteralPath $Checksum | Select-Object -First 1) -split '\s+')[0]
    $ActualHash = (Get-FileHash -LiteralPath $Zip -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($ExpectedHash.ToLowerInvariant() -ne $ActualHash) {
        throw "Distribution checksum mismatch: expected=$ExpectedHash actual=$ActualHash"
    }

    Invoke-NativeChecked -File $script:PowerShellExecutable `
        -Arguments @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
            (Join-Path $RepoRoot 'scripts\install\install-windows.ps1'),
            '-Package', $Zip,
            '-InstallRoot', $InstallRoot) `
        -Failure 'Windows distribution installation failed'

    $InstalledMinos = Join-Path $InstallRoot 'minos.cmd'
    Invoke-NativeChecked -File $InstalledMinos -Arguments @('--version') `
        -Failure 'Installed MINOS --version failed'
    if (-not $SkipProviderReplays) {
        Invoke-NativeChecked -File $InstalledMinos -Arguments @('doctor') `
            -Failure 'Installed MINOS doctor failed'
    }

    Invoke-NativeChecked -File $script:JavaExecutable `
        -Arguments @((Join-Path $RepoRoot 'scripts\m14\MinosNativeMcpSmoke.java'), $InstalledMinos, $script:ValidationHome) `
        -Failure 'Installed native MCP handshake failed'

    if ($ValidateDocker) {
        Write-Host ''
        Write-Host '=== Installed distribution Docker validation ===' -ForegroundColor Cyan
        $InstalledDockerScript = Join-Path $InstallRoot 'docker\scripts\prod-mcp-release.ps1'
        $InstalledReleaseJar = Join-Path $InstallRoot 'lib\minos.jar'
        $DockerValidationRoot = Join-Path $ValidationRoot 'docker-install'
        Invoke-NativeChecked -File $script:PowerShellExecutable `
            -Arguments @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
                $InstalledDockerScript,
                '-Action', 'Install',
                '-Jar', $InstalledReleaseJar,
                '-Version', $ReleaseVersion,
                '-Commit', $Head,
                '-InstallRoot', $DockerValidationRoot,
                '-ProjectsRoot', (Split-Path -Parent $RepoRoot)) `
            -Failure 'Installed distribution Docker install failed'
        Invoke-NativeChecked -File $script:PowerShellExecutable `
            -Arguments @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
                $InstalledDockerScript,
                '-Action', 'Validate',
                '-InstallRoot', $DockerValidationRoot) `
            -Failure 'Installed distribution Docker validation failed'
    }

    Write-Host ''
    Write-Host 'M14 LOCAL VALIDATION SUCCESS' -ForegroundColor Green
    Write-Host "HEAD       : $Head"
    Write-Host "Release    : $ReleaseVersion"
    Write-Host "MINOS_HOME : $script:ValidationHome"
    Write-Host "ZIP        : $Zip"
    Write-Host "SHA-256    : $ActualHash"
}
finally {
    Pop-Location
}
