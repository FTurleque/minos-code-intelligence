[CmdletBinding()]
param(
    [string] $ExpectedHead = '',
    [string] $Version = '0.2.0-m21s5'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))

if ($env:OS -ne 'Windows_NT') {
    throw 'M21-S5 release qualification must run on Windows.'
}

function Resolve-Python {
    foreach ($Name in @('python.exe', 'python', 'python3.exe', 'python3')) {
        $Command = Get-Command $Name -ErrorAction SilentlyContinue
        if ($Command) { return $Command.Source }
    }
    throw 'M21-S5 requires Python in PATH.'
}

function Verify-Sha256Sidecar([string] $Artifact, [string] $Sidecar) {
    if (-not (Test-Path -LiteralPath $Artifact -PathType Leaf)) { throw "Missing artifact: $Artifact" }
    if (-not (Test-Path -LiteralPath $Sidecar -PathType Leaf)) { throw "Missing checksum: $Sidecar" }
    $Expected = ((Get-Content -LiteralPath $Sidecar | Select-Object -First 1) -split '\s+')[0].ToLowerInvariant()
    $Actual = (Get-FileHash -LiteralPath $Artifact -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($Expected -ne $Actual) { throw "SHA-256 mismatch for $Artifact`: expected=$Expected actual=$Actual" }
}

Push-Location $RepoRoot
try {
    Write-Host '=== MINOS M21-S5 - Supply-chain & Release Hardening qualification ===' -ForegroundColor Cyan

    $Dirty = @(& git status --porcelain)
    if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect worktree.' }
    if ($Dirty.Count -gt 0) { throw "M21-S5 requires a clean worktree.`n$($Dirty -join "`n")" }

    $Head = ((& git rev-parse HEAD) | Select-Object -First 1).Trim()
    if ([string]::IsNullOrWhiteSpace($Head)) { throw 'Unable to resolve HEAD.' }
    if (-not [string]::IsNullOrWhiteSpace($ExpectedHead) -and $Head -ne $ExpectedHead) {
        throw "M21-S5 exact-head mismatch: expected=$ExpectedHead actual=$Head"
    }
    Write-Host "HEAD: $Head"
    Write-Host "Release candidate version: $Version"

    Write-Host '[1/6] Replaying M21 local/core qualification...'
    & (Join-Path $RepoRoot 'scripts\m21\run-local.ps1') -ExpectedHead $Head
    if ($LASTEXITCODE -ne 0) { throw "M21 local qualification failed (exit=$LASTEXITCODE)" }

    Write-Host '[2/6] Building supply-chain-qualified Windows distribution without repeating tests...'
    & (Join-Path $RepoRoot 'scripts\release\build-windows-distribution.ps1') -Version $Version -SkipVerify
    if ($LASTEXITCODE -ne 0) { throw "Windows distribution build failed (exit=$LASTEXITCODE)" }

    Write-Host '[3/6] Building Windows setup from the qualified distribution...'
    & (Join-Path $RepoRoot 'scripts\release\build-windows-installer.ps1') -Version $Version
    if ($LASTEXITCODE -ne 0) { throw "Windows setup build failed (exit=$LASTEXITCODE)" }

    $DistributionName = "minos-$Version-windows-x64"
    $Distribution = Join-Path $RepoRoot "target\dist\$DistributionName"
    $Zip = Join-Path $RepoRoot "target\dist\$DistributionName.zip"
    $Setup = Join-Path $RepoRoot "target\dist\MINOS-$Version-windows-x64-setup.exe"
    $SbomSidecar = Join-Path $RepoRoot "target\dist\minos-$Version.cdx.json"
    $NoticesSidecar = Join-Path $RepoRoot "target\dist\MINOS-$Version-THIRD-PARTY-NOTICES.txt"

    Write-Host '[4/6] Verifying embedded/sidecar supply-chain evidence and release checksums...'
    $Python = Resolve-Python
    & $Python 'scripts/release/check-supply-chain.py' `
        '--distribution' $Distribution `
        '--version' $Version `
        '--commit' $Head `
        '--strict-licenses'
    if ($LASTEXITCODE -ne 0) { throw "Supply-chain evidence gate failed (exit=$LASTEXITCODE)" }

    Verify-Sha256Sidecar -Artifact $Zip -Sidecar "$Zip.sha256"
    Verify-Sha256Sidecar -Artifact $Setup -Sidecar "$Setup.sha256"
    Verify-Sha256Sidecar -Artifact $SbomSidecar -Sidecar "$SbomSidecar.sha256"
    Verify-Sha256Sidecar -Artifact $NoticesSidecar -Sidecar "$NoticesSidecar.sha256"

    Write-Host '[5/6] Replaying install/uninstall release validation from existing artifacts...'
    & (Join-Path $RepoRoot 'scripts\release\publish-windows-release.ps1') `
        -Version $Version `
        -TargetCommit $Head `
        -SkipBuild `
        -ValidateOnly
    if ($LASTEXITCODE -ne 0) { throw "Windows release smoke validation failed (exit=$LASTEXITCODE)" }

    $Signature = Get-AuthenticodeSignature -LiteralPath $Setup
    $RequireSigned = $env:MINOS_REQUIRE_SIGNED_RELEASE -eq '1'
    if ($RequireSigned -and $Signature.Status -ne [System.Management.Automation.SignatureStatus]::Valid) {
        throw "MINOS_REQUIRE_SIGNED_RELEASE=1 but setup Authenticode status is $($Signature.Status)."
    }
    Write-Host "Authenticode setup status: $($Signature.Status) (required=$RequireSigned)"

    Write-Host '[6/6] Rechecking exact HEAD and clean worktree...'
    $FinalHead = ((& git rev-parse HEAD) | Select-Object -First 1).Trim()
    if ($FinalHead -ne $Head) { throw "HEAD changed during M21-S5 qualification: start=$Head end=$FinalHead" }
    $FinalDirty = @(& git status --porcelain)
    if ($LASTEXITCODE -ne 0) { throw 'Unable to re-inspect worktree.' }
    if ($FinalDirty.Count -gt 0) { throw "Worktree changed during M21-S5 qualification.`n$($FinalDirty -join "`n")" }

    Write-Host 'M21-S5 SUPPLY-CHAIN RELEASE VALIDATION SUCCESS' -ForegroundColor Green
    Write-Host "Validated HEAD: $Head"
    Write-Host "ZIP          : $Zip"
    Write-Host "Setup        : $Setup"
    Write-Host "SBOM         : $SbomSidecar"
    Write-Host "Notices      : $NoticesSidecar"
}
finally {
    Pop-Location
}
