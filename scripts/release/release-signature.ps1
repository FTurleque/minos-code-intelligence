# Release signature guard (AUD-DEP-06).
#
# Dot-source this file. It defines functions only and performs no side effects, so the fail-closed rules below are
# exercised directly by scripts/release/test-release-signature.ps1 without a certificate or a built distribution.
#
# MINOS_REQUIRE_SIGNED_RELEASE says whether the release must be Authenticode-signed ('1') or not ('0' or unset).
# MINOS_SIGNING_MODE names how it is signed. No mode is implemented yet (decision D2 of
# attester-et-signer-la-release-windows): requiring a signature therefore fails before anything is built, instead of
# publishing an unsigned release that would claim otherwise. Without the requirement nothing is signed half-way and
# the release manifest records "unsigned".

Set-StrictMode -Version Latest

# The signing modes this repository can actually perform. Empty until D2 is decided and wired (with its ADR).
$script:MinosSigningModes = @()

function Get-ReleaseSignatureRequirement {
    <#
    .SYNOPSIS
        Reads MINOS_REQUIRE_SIGNED_RELEASE: '1' requires a signature, '0' or nothing does not, anything else fails.
    #>
    [OutputType([bool])]
    param([AllowNull()][AllowEmptyString()][string] $Value)

    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Trim() -eq '0') { return $false }
    if ($Value.Trim() -eq '1') { return $true }
    throw "MINOS_REQUIRE_SIGNED_RELEASE must be '1' (signature required) or '0' (not required), not '$Value'."
}

function Assert-ReleaseSigningConfigured {
    <#
    .SYNOPSIS
        Fails when a signature is required and no signing mode this repository implements is configured.
    #>
    param(
        [Parameter(Mandatory = $true)][bool] $Required,
        [AllowNull()][AllowEmptyString()][string] $Mode
    )

    if (-not $Required) { return }
    if ([string]::IsNullOrWhiteSpace($Mode)) {
        throw 'A signed release is required (MINOS_REQUIRE_SIGNED_RELEASE=1) but no signing mode is configured (MINOS_SIGNING_MODE).'
    }
    if ($script:MinosSigningModes -notcontains $Mode) {
        $known = if ($script:MinosSigningModes.Count -eq 0) { 'none is implemented yet' } else { $script:MinosSigningModes -join ', ' }
        throw "A signed release is required but the signing mode '$Mode' is not one this repository performs ($known)."
    }
}

function Assert-AuthenticodeSigned {
    <#
    .SYNOPSIS
        Every file has a valid Authenticode signature that carries a timestamp; the first one that does not is named.
    .DESCRIPTION
        A signature without a timestamp expires with its certificate, so it is refused like a missing one. A file that
        cannot be read, or a check that cannot be made, fails too. -Reader replaces Get-AuthenticodeSignature in tests.
    #>
    param(
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][string[]] $Path,
        [scriptblock] $Reader = { param($File) Get-AuthenticodeSignature -LiteralPath $File }
    )

    if ($Path.Count -eq 0) { throw 'No executable to check: a signature check over nothing proves nothing.' }
    foreach ($File in $Path) {
        if (-not (Test-Path -LiteralPath $File -PathType Leaf)) { throw "Cannot check the signature of a missing file: $File" }
        $Signature = & $Reader $File
        if ($null -eq $Signature) { throw "The signature of $File could not be read." }
        if ([string]$Signature.Status -ne 'Valid') {
            throw "$File is not validly Authenticode-signed (status: $($Signature.Status))."
        }
        if ($null -eq $Signature.TimeStamperCertificate) {
            throw "$File is signed without a timestamp: the signature would expire with its certificate."
        }
    }
}

function Get-ReleaseSignatureState {
    <#
    .SYNOPSIS
        'signed' after every executable has been proven signed when the signature is required, 'unsigned' otherwise.
    #>
    [OutputType([string])]
    param(
        [Parameter(Mandatory = $true)][bool] $Required,
        [AllowNull()][AllowEmptyString()][string] $Mode,
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][string[]] $Executable,
        [scriptblock] $Reader = { param($File) Get-AuthenticodeSignature -LiteralPath $File }
    )

    if (-not $Required) { return 'unsigned' }
    Assert-ReleaseSigningConfigured -Required $true -Mode $Mode
    Assert-AuthenticodeSigned -Path $Executable -Reader $Reader
    return 'signed'
}
