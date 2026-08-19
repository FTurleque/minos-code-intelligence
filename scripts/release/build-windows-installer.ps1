[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$')]
    [string] $Version,

    [string] $DistributionRoot = '',
    [string] $OutputRoot = '',

    # Build a non-shippable setup with a distinct AppId and cleanup hooks disabled.
    # This is the only setup variant release smoke tests are allowed to install.
    [switch] $Smoke,

    # RELEASE/CI path: the caller (the workflow, right after installing a pinned Inno Setup
    # version) resolves ISCC.exe itself and passes its exact path here. When set, this script MUST
    # use exactly that binary -- it never falls back to searching PATH or other Inno Setup install
    # locations, because that search is exactly how a stray Inno Setup 7 (or any other ISCC.exe)
    # could silently take over compilation of the release setup.exe.
    [string] $IsccPath = '',

    # The exact engine version the compiler must report while compiling. Supplied together with
    # -IsccPath on the qualified release/CI path; the build is discarded if the compiler that
    # actually ran reports anything else.
    [string] $RequiredIsccVersion = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# Both or neither: a caller that pins the binary must also pin the version it has to report, and a
# caller that demands a version must say which binary it trusts. Accepting one alone would leave a
# half-qualified path where either the compiler or its version goes unproven.
if ([string]::IsNullOrWhiteSpace($IsccPath) -ne [string]::IsNullOrWhiteSpace($RequiredIsccVersion)) {
    throw 'Provide -IsccPath and -RequiredIsccVersion together, or neither: a qualified release build pins both the compiler binary and the engine version it must report.'
}

. (Join-Path $PSScriptRoot 'iscc-provenance.ps1')

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Join-Path $RepoRoot 'target\dist'
}
$OutputRoot = [System.IO.Path]::GetFullPath($OutputRoot)

$DistributionName = "minos-$Version-windows-x64"
if ([string]::IsNullOrWhiteSpace($DistributionRoot)) {
    $DistributionRoot = Join-Path $OutputRoot $DistributionName
}
$DistributionRoot = [System.IO.Path]::GetFullPath($DistributionRoot)

if ($env:OS -ne 'Windows_NT') {
    throw 'The MINOS Windows setup must be built on Windows.'
}
if (-not (Test-Path -LiteralPath $DistributionRoot -PathType Container)) {
    throw "MINOS distribution directory not found: $DistributionRoot"
}

foreach ($Required in @(
    'minos.cmd',
    'minos-mcp.cmd',
    'VERSION',
    'RUNTIME-MODULES.txt',
    'RELEASE-MANIFEST.json',
    'app\minos.exe',
    'app\runtime\bin\java.exe',
    'lib\minos.jar',
    'supply-chain\minos.cdx.json',
    'supply-chain\THIRD-PARTY-NOTICES.txt',
    'integration\configure-mcp-clients.ps1',
    'integration\configure-mcp-clients-setup.ps1',
    'integration\configure-codex-mcp.ps1',
    'integration\detect-mcp-clients.ps1',
    'integration\uninstall-mcp-clients.ps1',
    'integration\probe-mcp-backend.ps1',
    'integration\switch-mcp-backend.ps1',
    'docker\Dockerfile.mcp.release',
    'docker\compose.mcp.prod.yaml',
    'docker\scripts\prod-mcp-release.ps1',
    'docker\scripts\configure-docker-mcp.ps1'
)) {
    if (-not (Test-Path -LiteralPath (Join-Path $DistributionRoot $Required))) {
        throw "Invalid MINOS distribution for setup: missing $Required"
    }
}

if (-not [string]::IsNullOrWhiteSpace($IsccPath)) {
    # Release/CI path: the compiler identity was already resolved and pinned by the caller. Do not
    # second-guess it by searching anywhere else.
    if (-not (Test-Path -LiteralPath $IsccPath -PathType Leaf)) {
        throw "Qualified Inno Setup compiler not found at -IsccPath: $IsccPath"
    }
    if (-not [string]::IsNullOrWhiteSpace($RequiredIsccVersion)) {
        # First provenance layer: what Chocolatey recorded as installed. This proves the provisioned
        # package but NOT which binary will execute, so it is a cross-check, not the assertion --
        # the authoritative check is the engine version the compiler itself reports below. Verified
        # here independently of any check the caller already did, so this script never trusts an
        # -IsccPath it cannot itself corroborate. A missing nuspec fails closed rather than
        # silently skipping the check.
        $NuspecPath = 'C:\ProgramData\chocolatey\lib\innosetup\innosetup.nuspec'
        if (-not (Test-Path -LiteralPath $NuspecPath -PathType Leaf)) {
            throw "Chocolatey package metadata not found at $NuspecPath; cannot verify the Inno Setup compiler at -IsccPath against -RequiredIsccVersion $RequiredIsccVersion."
        }
        [xml]$Nuspec = Get-Content -LiteralPath $NuspecPath -Raw
        $InstalledIsccVersion = $Nuspec.package.metadata.version
        if ($InstalledIsccVersion -ne $RequiredIsccVersion) {
            throw "Chocolatey innosetup package metadata at $NuspecPath reports version '$InstalledIsccVersion', required $RequiredIsccVersion. Refusing to build with an unverified Inno Setup compiler."
        }
    }
    $Iscc = $IsccPath
} else {
    # Developer/local fallback only: resolve automatically so `pwsh build-windows-installer.ps1`
    # keeps working on a workstation without a pre-resolved path. Never used on the release/CI path
    # (the workflow always passes -IsccPath), so this ambiguity cannot silently affect a release.
    $IsccCandidates = @()
    $IsccCommand = Get-Command ISCC.exe -ErrorAction SilentlyContinue
    if ($IsccCommand) { $IsccCandidates += $IsccCommand.Source }
    if (-not [string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
        $IsccCandidates += (Join-Path $env:LOCALAPPDATA 'Programs\Inno Setup 7\ISCC.exe')
        $IsccCandidates += (Join-Path $env:LOCALAPPDATA 'Programs\Inno Setup 6\ISCC.exe')
    }
    if (-not [string]::IsNullOrWhiteSpace(${env:ProgramFiles(x86)})) {
        $IsccCandidates += (Join-Path ${env:ProgramFiles(x86)} 'Inno Setup 7\ISCC.exe')
        $IsccCandidates += (Join-Path ${env:ProgramFiles(x86)} 'Inno Setup 6\ISCC.exe')
    }
    if (-not [string]::IsNullOrWhiteSpace($env:ProgramFiles)) {
        $IsccCandidates += (Join-Path $env:ProgramFiles 'Inno Setup 7\ISCC.exe')
        $IsccCandidates += (Join-Path $env:ProgramFiles 'Inno Setup 6\ISCC.exe')
    }
    $IsccCandidates += 'C:\ProgramData\chocolatey\bin\ISCC.exe'

    $Iscc = $IsccCandidates |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) -and (Test-Path -LiteralPath $_ -PathType Leaf) } |
        Select-Object -First 1
    if ([string]::IsNullOrWhiteSpace($Iscc)) {
        throw 'Inno Setup is required to build MINOS setup.exe. Install Inno Setup 6/7 or expose ISCC.exe in PATH.'
    }
}

$Template = Join-Path $RepoRoot 'packaging\windows\minos-installer.iss.template'
if (-not (Test-Path -LiteralPath $Template -PathType Leaf)) {
    throw "Inno Setup template not found: $Template"
}

$InstallerWork = Join-Path $OutputRoot '.installer'
$InstallerOutput = if ($Smoke) { Join-Path $OutputRoot '.smoke' } else { $OutputRoot }
$GeneratedIssName = if ($Smoke) { "$DistributionName-smoke.iss" } else { "$DistributionName.iss" }
$OutputBaseFilename = if ($Smoke) { "MINOS-$Version-windows-x64-smoke-setup" } else { "MINOS-$Version-windows-x64-setup" }
New-Item -ItemType Directory -Force -Path $InstallerWork, $InstallerOutput | Out-Null
$GeneratedIss = Join-Path $InstallerWork $GeneratedIssName
$Setup = Join-Path $InstallerOutput "$OutputBaseFilename.exe"
$Checksum = "$Setup.sha256"

Remove-Item -LiteralPath $Setup -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $Checksum -Force -ErrorAction SilentlyContinue

function Escape-InnoString([string] $Value) {
    return $Value.Replace('"', '""')
}

function Assert-InnoTaskNames([string] $ScriptContent) {
    $InTasksSection = $false
    foreach ($Line in ($ScriptContent -split "`r?`n")) {
        $Trimmed = $Line.Trim()
        if ($Trimmed -match '^\[([^\]]+)\]$') {
            $InTasksSection = $Matches[1] -eq 'Tasks'
            continue
        }
        if (-not $InTasksSection -or [string]::IsNullOrWhiteSpace($Trimmed) -or $Trimmed.StartsWith(';')) { continue }
        if ($Trimmed -notmatch '^Name:\s*"([^"]+)"') { continue }
        $TaskName = $Matches[1]
        $ValidCharacters = $TaskName -match '^[A-Za-z_][A-Za-z0-9_/\\]*$'
        $Reserved = $TaskName -match '^(?i:not|and|or)$'
        $EndsWithSeparator = $TaskName.EndsWith('/') -or $TaskName.EndsWith('\')
        if (-not $ValidCharacters -or $Reserved -or $EndsWithSeparator) {
            throw "Invalid Inno Setup task Name '$TaskName'. Use letters, digits, underscores, '/' or '\'; do not start with a digit/separator, end with a separator, or use reserved names not/and/or."
        }
    }
}

$BaseVersion = ($Version -split '[-+]')[0]
$NumericVersion = "$BaseVersion.0"
$AppId = if ($Smoke) { "MINOS-Release-Smoke-$Version" } else { '{{7B91F355-0B8A-4D28-A6C6-5CE4B1C5F62B}' }
$SmokeMode = if ($Smoke) { '1' } else { '0' }
$Utf8 = New-Object System.Text.UTF8Encoding($false)
$Iss = [System.IO.File]::ReadAllText($Template, $Utf8)
$Iss = $Iss.Replace('@@VERSION@@', (Escape-InnoString $Version))
$Iss = $Iss.Replace('@@APP_VERSION@@', (Escape-InnoString $NumericVersion))
$Iss = $Iss.Replace('@@APP_ID@@', (Escape-InnoString $AppId))
$Iss = $Iss.Replace('@@SMOKE_MODE@@', $SmokeMode)
$Iss = $Iss.Replace('@@SOURCE_DIR@@', (Escape-InnoString $DistributionRoot))
$Iss = $Iss.Replace('@@OUTPUT_DIR@@', (Escape-InnoString $InstallerOutput))
$Iss = $Iss.Replace('@@OUTPUT_BASENAME@@', (Escape-InnoString $OutputBaseFilename))
if ($Iss -match '@@[A-Z0-9_]+@@') {
    throw "Unresolved Inno Setup template token: $($Matches[0])"
}
Assert-InnoTaskNames -ScriptContent $Iss
[System.IO.File]::WriteAllText($GeneratedIss, $Iss, $Utf8)

try {
    # Stream the compiler's output to the log AND capture it: the "Compiler engine version:" line it
    # prints is the only evidence of which binary actually produced this setup.exe. ErrorActionPreference
    # is relaxed only around the native call so that 2>&1 stderr lines stay diagnostics instead of
    # terminating the pipeline before the exit code can be inspected.
    $Previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $Iscc $GeneratedIss 2>&1 | Tee-Object -Variable CompilerOutput
        $CompilerExitCode = $LASTEXITCODE
    }
    finally { $ErrorActionPreference = $Previous }
    if ($CompilerExitCode -ne 0) { throw "Inno Setup compilation failed with exit code $CompilerExitCode" }

    $EngineVersion = '(not asserted: no -RequiredIsccVersion supplied)'
    if (-not [string]::IsNullOrWhiteSpace($RequiredIsccVersion)) {
        # Authoritative provenance check on the qualified release/CI path. A setup produced by an
        # unqualified compiler is deleted rather than left on disk where a later step could pick it up.
        try {
            $EngineVersion = Assert-IsccEngineVersion `
                -CompilerOutput @($CompilerOutput | ForEach-Object { [string] $_ }) `
                -RequiredVersion $RequiredIsccVersion `
                -IsccPath $Iscc
        }
        catch {
            Remove-Item -LiteralPath $Setup -Force -ErrorAction SilentlyContinue
            throw
        }
    }
    if (-not (Test-Path -LiteralPath $Setup -PathType Leaf)) { throw "MINOS setup executable was not produced: $Setup" }

    $Hash = (Get-FileHash -LiteralPath $Setup -Algorithm SHA256).Hash.ToLowerInvariant()
    "$Hash  $([System.IO.Path]::GetFileName($Setup))" | Set-Content -LiteralPath $Checksum -Encoding ascii

    $SuccessMessage = if ($Smoke) { 'MINOS Windows smoke setup SUCCESS' } else { 'MINOS Windows setup SUCCESS' }
    $ModeLabel = if ($Smoke) { 'isolated smoke' } else { 'production' }
    Write-Host ''
    Write-Host $SuccessMessage -ForegroundColor Green
    Write-Host "Setup        : $Setup"
    Write-Host "SHA-256      : $Hash"
    Write-Host "Distribution : $DistributionRoot"
    Write-Host "AppId mode   : $ModeLabel"
    Write-Host "Inno Setup   : $Iscc"
    Write-Host "Engine ver.  : $EngineVersion"
}
finally {
    Remove-Item -LiteralPath $GeneratedIss -Force -ErrorAction SilentlyContinue
}
