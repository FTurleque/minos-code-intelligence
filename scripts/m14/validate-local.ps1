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

function Invoke-MinosJson {
    param([Parameter(Mandatory = $true)][string[]] $Arguments)
    $lines = & $script:JavaExecutable "-Dminos.home=$script:ValidationHome" -jar $script:MinosJar @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "MINOS command failed: $($Arguments -join ' ') (exit=$LASTEXITCODE)"
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

    $ValidationRoot = Join-Path $RepoRoot 'target\m14-validation'
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
        Write-Host '=== Java provider replay ===' -ForegroundColor Cyan
        $JavaFixture = Join-Path $RepoRoot 'fixtures\java\java-simple'
        Invoke-NativeChecked -File $script:JavaExecutable `
            -Arguments @("-Dminos.home=$script:ValidationHome", '-jar', $script:MinosJar,
                'tools', 'install', 'scip-java') `
            -Failure 'scip-java managed installation failed'
        Invoke-NativeChecked -File $script:JavaExecutable `
            -Arguments @("-Dminos.home=$script:ValidationHome", '-jar', $script:MinosJar,
                'project', 'add', $JavaFixture, '--name', 'm14-java') `
            -Failure 'Java fixture registration failed'

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
        }
        finally {
            Remove-Item -LiteralPath (Join-Path $JavaFixture 'index.scip') -Force -ErrorAction SilentlyContinue
            Remove-Item -LiteralPath (Join-Path $JavaFixture 'target') -Recurse -Force -ErrorAction SilentlyContinue
        }
    }

    Write-Host ''
    Write-Host '=== Windows distribution ===' -ForegroundColor Cyan
    Invoke-NativeChecked -File 'powershell.exe' `
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

    Invoke-NativeChecked -File 'powershell.exe' `
        -Arguments @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
            (Join-Path $RepoRoot 'scripts\install\install-windows.ps1'),
            '-Package', $Zip,
            '-InstallRoot', $InstallRoot) `
        -Failure 'Windows distribution installation failed'

    $InstalledMinos = Join-Path $InstallRoot 'minos.cmd'
    Invoke-NativeChecked -File $InstalledMinos -Arguments @('--version') `
        -Failure 'Installed MINOS --version failed'
    Invoke-NativeChecked -File $InstalledMinos -Arguments @('doctor') `
        -Failure 'Installed MINOS doctor failed'

    if ($ValidateDocker) {
        Write-Host ''
        Write-Host '=== Packaged Docker validation ===' -ForegroundColor Cyan
        $ReleaseJar = Join-Path $RepoRoot "target\minos-code-intelligence-$ReleaseVersion-all.jar"
        Invoke-NativeChecked -File 'powershell.exe' `
            -Arguments @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
                (Join-Path $RepoRoot 'docker\scripts\prod-mcp-release.ps1'),
                '-Action', 'Install', '-Jar', $ReleaseJar, '-Version', $ReleaseVersion,
                '-Commit', $Head, '-ProjectsRoot', (Split-Path -Parent $RepoRoot)) `
            -Failure 'Packaged Docker install failed'
        Invoke-NativeChecked -File 'powershell.exe' `
            -Arguments @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
                (Join-Path $RepoRoot 'docker\scripts\prod-mcp-release.ps1'), '-Action', 'Validate') `
            -Failure 'Packaged Docker validation failed'
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
