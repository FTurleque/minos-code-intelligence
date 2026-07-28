[CmdletBinding()]
param(
    [string] $InstallRoot = (Join-Path $env:LOCALAPPDATA 'MINOS\m24-toolchains')
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$GoVersion = '1.26.4'
$GoArchive = "go$GoVersion.windows-amd64.zip"
$GoUrl = "https://go.dev/dl/$GoArchive"
$GoSha256 = '3ca8fb4630b07c419cbdd51f754e31363cfcfb83b3a5354d9e895c90be2cc345'

$RustVersion = '1.97.1'
$RustHost = 'x86_64-pc-windows-gnu'
$RustToolchain = "$RustVersion-$RustHost"
$RustupUrl = "https://static.rust-lang.org/rustup/dist/$RustHost/rustup-init.exe"
$RustupShaUrl = "$RustupUrl.sha256"

$RustAnalyzerRelease = '2026-07-27'
$RustAnalyzerVersion = '0.3.2989'
$RustAnalyzerCommit = '12c3381'
$RustAnalyzerAssetName = 'rust-analyzer-x86_64-pc-windows-msvc.zip'
$RustAnalyzerApiUrl = "https://api.github.com/repos/rust-lang/rust-analyzer/releases/tags/$RustAnalyzerRelease"

if ($env:OS -ne 'Windows_NT') {
    throw 'M24 Windows toolchain bootstrap must run on Windows.'
}
if (-not [Environment]::Is64BitOperatingSystem) {
    throw 'M24 Windows toolchain bootstrap currently supports x86-64 Windows only.'
}

function Download-File {
    param(
        [Parameter(Mandatory = $true)][string] $Uri,
        [Parameter(Mandatory = $true)][string] $Destination,
        [hashtable] $Headers = @{}
    )
    Write-Host "DOWNLOAD $Uri"
    Invoke-WebRequest -UseBasicParsing -Uri $Uri -OutFile $Destination -Headers $Headers
}

function Assert-Sha256 {
    param(
        [Parameter(Mandatory = $true)][string] $Path,
        [Parameter(Mandatory = $true)][string] $Expected
    )
    $Actual = (Get-FileHash -Algorithm SHA256 -Path $Path).Hash.ToLowerInvariant()
    $NormalizedExpected = $Expected.Trim().ToLowerInvariant()
    if ($Actual -ne $NormalizedExpected) {
        throw "SHA-256 mismatch for $Path`nexpected=$NormalizedExpected`nactual=$Actual"
    }
    Write-Host "PASS SHA-256 $([System.IO.Path]::GetFileName($Path))"
}

function Invoke-NativeChecked {
    param(
        [Parameter(Mandatory = $true)][string] $Executable,
        [Parameter(Mandatory = $true)][string[]] $Arguments
    )
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

        $Parts = @()
        $Stdout = Get-Content -Raw -Path $StdoutPath -ErrorAction SilentlyContinue
        $Stderr = Get-Content -Raw -Path $StderrPath -ErrorAction SilentlyContinue
        if (-not [string]::IsNullOrWhiteSpace($Stdout)) { $Parts += $Stdout.TrimEnd() }
        if (-not [string]::IsNullOrWhiteSpace($Stderr)) { $Parts += $Stderr.TrimEnd() }
        $Output = ($Parts -join "`n").Trim()
        if ($Process.ExitCode -ne 0) {
            throw "Command failed: $Executable $($Arguments -join ' ') (exit=$($Process.ExitCode))`n$Output"
        }
        return $Output
    }
    finally {
        Remove-Item -Force -ErrorAction SilentlyContinue $StdoutPath, $StderrPath
    }
}

function Remove-DirectoryIfPresent {
    param([Parameter(Mandatory = $true)][string] $Path)
    if (Test-Path $Path) {
        Remove-Item -Recurse -Force -Path $Path
    }
}

$InstallRoot = [System.IO.Path]::GetFullPath($InstallRoot)
$DownloadRoot = Join-Path $InstallRoot 'downloads'
$GoRoot = Join-Path $InstallRoot "go\$GoVersion"
$RustRoot = Join-Path $InstallRoot 'rust'
$CargoHome = Join-Path $RustRoot 'cargo'
$RustupHome = Join-Path $RustRoot 'rustup'
$RustAnalyzerRoot = Join-Path $InstallRoot "rust-analyzer\$RustAnalyzerRelease"

New-Item -ItemType Directory -Force -Path $InstallRoot, $DownloadRoot | Out-Null

Write-Host '=== MINOS M24 - verified Windows toolchain bootstrap ===' -ForegroundColor Cyan
Write-Host "Install root: $InstallRoot"
Write-Host 'No administrator rights, WinGet, MSI installation, user PATH mutation, or repository-local toolchain files are used.'

# Go: use the official ZIP so installation remains unprivileged and isolated.
$GoExe = Join-Path $GoRoot 'bin\go.exe'
$GoReady = $false
if (Test-Path $GoExe) {
    try {
        $ExistingGo = Invoke-NativeChecked $GoExe @('version')
        $GoReady = $ExistingGo -match [regex]::Escape("go$GoVersion") -and $ExistingGo -match 'windows/amd64'
        if ($GoReady) { Write-Host "PASS existing Go: $ExistingGo" }
    }
    catch { $GoReady = $false }
}
if (-not $GoReady) {
    $GoZip = Join-Path $DownloadRoot $GoArchive
    Download-File $GoUrl $GoZip
    Assert-Sha256 $GoZip $GoSha256

    $GoStage = Join-Path $InstallRoot 'go.stage'
    Remove-DirectoryIfPresent $GoStage
    New-Item -ItemType Directory -Force -Path $GoStage | Out-Null
    Expand-Archive -Path $GoZip -DestinationPath $GoStage -Force
    $ExtractedGo = Join-Path $GoStage 'go'
    if (-not (Test-Path (Join-Path $ExtractedGo 'bin\go.exe'))) {
        throw "Go archive did not contain go\bin\go.exe"
    }
    Remove-DirectoryIfPresent $GoRoot
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $GoRoot) | Out-Null
    Move-Item -Path $ExtractedGo -Destination $GoRoot
    Remove-DirectoryIfPresent $GoStage

    $InstalledGo = Invoke-NativeChecked $GoExe @('version')
    if ($InstalledGo -notmatch [regex]::Escape("go$GoVersion") -or $InstalledGo -notmatch 'windows/amd64') {
        throw "Installed Go verification failed: $InstalledGo"
    }
    Write-Host "PASS Go installed: $InstalledGo"
}

# Rust: use the official rustup GNU host to avoid requiring Visual Studio/MSVC
# solely for this qualification fixture. rustc/cargo are pinned to Rust 1.97.1.
$env:CARGO_HOME = $CargoHome
$env:RUSTUP_HOME = $RustupHome
New-Item -ItemType Directory -Force -Path $CargoHome, $RustupHome | Out-Null
$RustupExe = Join-Path $CargoHome 'bin\rustup.exe'
$RustcExe = Join-Path $CargoHome 'bin\rustc.exe'
$CargoExe = Join-Path $CargoHome 'bin\cargo.exe'

if (-not (Test-Path $RustupExe)) {
    $RustupInit = Join-Path $DownloadRoot 'rustup-init-x86_64-pc-windows-gnu.exe'
    $RustupShaFile = Join-Path $DownloadRoot 'rustup-init-x86_64-pc-windows-gnu.exe.sha256'
    Download-File $RustupUrl $RustupInit
    Download-File $RustupShaUrl $RustupShaFile
    $RustupExpectedSha = ((Get-Content -Raw -Path $RustupShaFile).Trim() -split '\s+')[0]
    if ($RustupExpectedSha -notmatch '^[0-9a-fA-F]{64}$') {
        throw "Invalid official rustup SHA-256 sidecar: $RustupExpectedSha"
    }
    Assert-Sha256 $RustupInit $RustupExpectedSha

    $RustupInstall = Invoke-NativeChecked $RustupInit @(
        '-y',
        '--no-modify-path',
        '--profile', 'minimal',
        '--default-host', $RustHost,
        '--default-toolchain', $RustToolchain
    )
    if (-not [string]::IsNullOrWhiteSpace($RustupInstall)) { Write-Host $RustupInstall }
}

$RustupToolchain = Invoke-NativeChecked $RustupExe @('toolchain', 'install', $RustToolchain, '--profile', 'minimal')
if (-not [string]::IsNullOrWhiteSpace($RustupToolchain)) { Write-Host $RustupToolchain }
$RustupDefault = Invoke-NativeChecked $RustupExe @('default', $RustToolchain)
if (-not [string]::IsNullOrWhiteSpace($RustupDefault)) { Write-Host $RustupDefault }

$RustcVersion = Invoke-NativeChecked $RustcExe @('--version')
if ($RustcVersion -notmatch '^rustc 1\.97\.1\b') {
    throw "Rust version verification failed: $RustcVersion"
}
Write-Host "PASS rustc installed: $RustcVersion"
$CargoVersion = Invoke-NativeChecked $CargoExe @('--version')
Write-Host "PASS cargo installed: $CargoVersion"

# rust-analyzer: resolve the pinned GitHub release asset and require GitHub's
# published SHA-256 digest before extracting the executable.
$RustAnalyzerExe = Join-Path $RustAnalyzerRoot 'rust-analyzer.exe'
$RustAnalyzerReady = $false
if (Test-Path $RustAnalyzerExe) {
    try {
        $ExistingAnalyzer = Invoke-NativeChecked $RustAnalyzerExe @('--version')
        $RustAnalyzerReady = $ExistingAnalyzer -match [regex]::Escape($RustAnalyzerVersion) -and
            $ExistingAnalyzer -match [regex]::Escape($RustAnalyzerRelease) -and
            $ExistingAnalyzer -match [regex]::Escape($RustAnalyzerCommit)
        if ($RustAnalyzerReady) { Write-Host "PASS existing rust-analyzer: $ExistingAnalyzer" }
    }
    catch { $RustAnalyzerReady = $false }
}
if (-not $RustAnalyzerReady) {
    Write-Host "RESOLVE $RustAnalyzerApiUrl"
    $Release = Invoke-RestMethod -Uri $RustAnalyzerApiUrl -Headers @{ 'User-Agent' = 'MINOS-M24-bootstrap' }
    if ([string]$Release.tag_name -ne $RustAnalyzerRelease) {
        throw "Unexpected rust-analyzer release tag: $($Release.tag_name)"
    }
    $Asset = @($Release.assets | Where-Object { $_.name -eq $RustAnalyzerAssetName }) | Select-Object -First 1
    if (-not $Asset) {
        throw "rust-analyzer release $RustAnalyzerRelease does not expose $RustAnalyzerAssetName"
    }
    $Digest = [string]$Asset.digest
    if ($Digest -notmatch '^sha256:[0-9a-fA-F]{64}$') {
        throw "GitHub release asset has no usable SHA-256 digest: $Digest"
    }

    $AnalyzerZip = Join-Path $DownloadRoot $RustAnalyzerAssetName
    Download-File ([string]$Asset.browser_download_url) $AnalyzerZip @{ 'User-Agent' = 'MINOS-M24-bootstrap' }
    Assert-Sha256 $AnalyzerZip $Digest.Substring(7)

    $AnalyzerStage = Join-Path $InstallRoot 'rust-analyzer.stage'
    Remove-DirectoryIfPresent $AnalyzerStage
    New-Item -ItemType Directory -Force -Path $AnalyzerStage | Out-Null
    Expand-Archive -Path $AnalyzerZip -DestinationPath $AnalyzerStage -Force
    $ExtractedAnalyzer = Get-ChildItem -Path $AnalyzerStage -Recurse -File -Filter 'rust-analyzer.exe' | Select-Object -First 1
    if (-not $ExtractedAnalyzer) {
        throw "rust-analyzer archive did not contain rust-analyzer.exe"
    }
    Remove-DirectoryIfPresent $RustAnalyzerRoot
    New-Item -ItemType Directory -Force -Path $RustAnalyzerRoot | Out-Null
    Copy-Item -Force -Path $ExtractedAnalyzer.FullName -Destination $RustAnalyzerExe
    Remove-DirectoryIfPresent $AnalyzerStage

    $InstalledAnalyzer = Invoke-NativeChecked $RustAnalyzerExe @('--version')
    if ($InstalledAnalyzer -notmatch [regex]::Escape($RustAnalyzerVersion) -or
        $InstalledAnalyzer -notmatch [regex]::Escape($RustAnalyzerRelease) -or
        $InstalledAnalyzer -notmatch [regex]::Escape($RustAnalyzerCommit)) {
        throw "rust-analyzer pin verification failed: $InstalledAnalyzer"
    }
    Write-Host "PASS rust-analyzer installed: $InstalledAnalyzer"
}

# Activate only this PowerShell process. The user's machine/user PATH is not modified.
# rustup places a rust-analyzer proxy in CARGO_HOME\bin even when the component is
# not installed for the selected toolchain. Put the verified standalone binary
# before CARGO_HOME\bin so Get-Command resolves the pinned M24 rust-analyzer.
$env:GOROOT = $GoRoot
$RequiredPathEntries = @(
    (Join-Path $GoRoot 'bin'),
    $RustAnalyzerRoot,
    (Join-Path $CargoHome 'bin')
)
$ExistingPathEntries = @($env:PATH -split ';' | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
$env:PATH = (($RequiredPathEntries + $ExistingPathEntries) | Select-Object -Unique) -join ';'

Write-Host '=== Activated M24 toolchains in the current PowerShell process ===' -ForegroundColor Cyan
Write-Host (Invoke-NativeChecked (Join-Path $GoRoot 'bin\go.exe') @('version'))
Write-Host (Invoke-NativeChecked $CargoExe @('--version'))
Write-Host (Invoke-NativeChecked $RustcExe @('--version'))
Write-Host (Invoke-NativeChecked $RustAnalyzerExe @('--version'))
Write-Host 'M24 WINDOWS TOOLCHAIN BOOTSTRAP SUCCESS' -ForegroundColor Green
