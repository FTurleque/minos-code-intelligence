#!/usr/bin/env pwsh
# Behavioural tests for the release signature guard and the check of published checksums (AUD-DEP-06).
#
# They dot-source the real functions of release-signature.ps1. Signatures are described by a -Reader that stands in
# for Get-AuthenticodeSignature, so every refusal is proven on any platform; under Windows one more case reads the real
# signature of an unsigned file. The checksum cases run the real Verify-Sha256 of publish-windows-release.ps1, which
# cannot be dot-sourced (it builds a release at load time): its definition is extracted with the PowerShell parser.
# Runs under PowerShell 7 (invariants job, Linux) and Windows PowerShell 5.1 (Verify (windows-2022)).

[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

. (Join-Path $PSScriptRoot 'release-signature.ps1')

$PublishScript = Join-Path $PSScriptRoot 'publish-windows-release.ps1'
$ParseErrors = $null
$PublishAst = [System.Management.Automation.Language.Parser]::ParseFile($PublishScript, [ref]$null, [ref]$ParseErrors)
if ($ParseErrors.Count -gt 0) { throw "publish-windows-release.ps1 does not parse: $($ParseErrors[0].Message)" }
$VerifySha256 = @($PublishAst.FindAll({ param($Node)
    $Node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $Node.Name -eq 'Verify-Sha256' }, $true))
if ($VerifySha256.Count -ne 1) { throw "publish-windows-release.ps1 must define Verify-Sha256 exactly once (found $($VerifySha256.Count))." }
. ([scriptblock]::Create($VerifySha256[0].Extent.Text))

$script:Failures = @()

function Test-Case([string] $Name, [scriptblock] $Body) {
    try {
        & $Body
        Write-Host "  PASS  $Name"
    }
    catch {
        $script:Failures += "$Name -- $($_.Exception.Message)"
        Write-Host "  FAIL  $Name -- $($_.Exception.Message)"
    }
}

function Assert-Equal($Expected, $Actual, [string] $Because) {
    if ($Expected -ne $Actual) { throw "expected '$Expected' but got '$Actual' ($Because)" }
}

function Assert-Throws([scriptblock] $Body, [string] $ExpectedSubstring) {
    try { & $Body }
    catch {
        if ($_.Exception.Message -notlike "*$ExpectedSubstring*") {
            throw "threw, but message did not contain '$ExpectedSubstring': $($_.Exception.Message)"
        }
        return
    }
    throw "expected a failure containing '$ExpectedSubstring', got none"
}

function New-Signature([string] $Status, [bool] $Timestamped) {
    [pscustomobject]@{ Status = $Status; TimeStamperCertificate = $(if ($Timestamped) { 'timestamp authority' } else { $null }) }
}

$Work = Join-Path ([System.IO.Path]::GetTempPath()) ("minos-release-signature-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $Work | Out-Null
try {
    $Setup = Join-Path $Work 'MINOS-9.9.9-windows-x64-setup.exe'
    Set-Content -LiteralPath $Setup -Value 'not a real executable' -Encoding ascii

    Write-Host 'Signature requirement'
    Test-Case 'unset, empty or 0 does not require a signature' {
        Assert-Equal $false (Get-ReleaseSignatureRequirement $null) 'unset'
        Assert-Equal $false (Get-ReleaseSignatureRequirement '') 'empty'
        Assert-Equal $false (Get-ReleaseSignatureRequirement '0') 'zero'
    }
    Test-Case '1 requires a signature' { Assert-Equal $true (Get-ReleaseSignatureRequirement '1') 'one' }
    Test-Case 'any other value fails instead of being read as no' {
        Assert-Throws { Get-ReleaseSignatureRequirement 'true' } "must be '1'"
    }

    Write-Host 'Signing mode'
    Test-Case 'required without a mode fails before anything is built' {
        Assert-Throws { Assert-ReleaseSigningConfigured -Required $true -Mode '' } 'no signing mode is configured'
    }
    Test-Case 'required with a mode this repository does not perform fails' {
        Assert-Throws { Assert-ReleaseSigningConfigured -Required $true -Mode 'trusted-signing' } "'trusted-signing' is not one this repository performs"
    }
    Test-Case 'not required needs no mode' { Assert-ReleaseSigningConfigured -Required $false -Mode '' }

    Write-Host 'Authenticode check'
    Test-Case 'an unsigned file is refused and named' {
        Assert-Throws { Assert-AuthenticodeSigned -Path @($Setup) -Reader { param($File) New-Signature 'NotSigned' $false } } 'setup.exe is not validly Authenticode-signed (status: NotSigned)'
    }
    Test-Case 'a valid signature without a timestamp is refused' {
        Assert-Throws { Assert-AuthenticodeSigned -Path @($Setup) -Reader { param($File) New-Signature 'Valid' $false } } 'signed without a timestamp'
    }
    Test-Case 'a signature that cannot be read is refused' {
        Assert-Throws { Assert-AuthenticodeSigned -Path @($Setup) -Reader { param($File) $null } } 'could not be read'
    }
    Test-Case 'a missing file is refused, never skipped' {
        Assert-Throws { Assert-AuthenticodeSigned -Path @((Join-Path $Work 'absent.exe')) -Reader { param($File) New-Signature 'Valid' $true } } 'missing file'
    }
    Test-Case 'an empty list is refused' {
        Assert-Throws { Assert-AuthenticodeSigned -Path @() -Reader { param($File) New-Signature 'Valid' $true } } 'proves nothing'
    }
    Test-Case 'a valid timestamped signature is accepted' {
        Assert-AuthenticodeSigned -Path @($Setup) -Reader { param($File) New-Signature 'Valid' $true }
    }
    if ($PSVersionTable.PSVersion.Major -lt 6 -or $IsWindows) {
        Test-Case 'the real Get-AuthenticodeSignature refuses an unsigned file (Windows)' {
            Assert-Throws { Assert-AuthenticodeSigned -Path @($Setup) } 'is not validly Authenticode-signed'
        }
    }
    else {
        Write-Host '  SKIP  the real Get-AuthenticodeSignature refuses an unsigned file (Windows only: runs on Verify (windows-2022))'
    }

    Write-Host 'Release signature state'
    Test-Case 'not required: unsigned, and nothing is checked' {
        Assert-Equal 'unsigned' (Get-ReleaseSignatureState -Required $false -Mode '' -Executable @() -Reader { throw 'must not be read' }) 'state'
    }
    Test-Case 'required without a mode: fails before reading any signature' {
        Assert-Throws { Get-ReleaseSignatureState -Required $true -Mode '' -Executable @($Setup) -Reader { throw 'must not be read' } } 'no signing mode is configured'
    }

    Write-Host 'Published checksums (Verify-Sha256 of publish-windows-release.ps1, run in -ValidateOnly and -PublishOnly)'
    $Hash = (Get-FileHash -LiteralPath $Setup -Algorithm SHA256).Hash.ToLowerInvariant()
    Set-Content -LiteralPath "$Setup.sha256" -Value "$Hash  $([System.IO.Path]::GetFileName($Setup))" -Encoding ascii
    Test-Case 'a final file that matches its checksum is accepted' { Assert-Equal $Hash (Verify-Sha256 $Setup "$Setup.sha256") 'hash' }
    Test-Case 'a file changed after its checksum was written (a late signature) is refused and named' {
        Add-Content -LiteralPath $Setup -Value 'signature appended after the sum' -Encoding ascii
        Assert-Throws { Verify-Sha256 $Setup "$Setup.sha256" } 'Release checksum mismatch for'
        Assert-Throws { Verify-Sha256 $Setup "$Setup.sha256" } 'MINOS-9.9.9-windows-x64-setup.exe'
    }
    Test-Case 'a file without its checksum file is refused' {
        Remove-Item -LiteralPath "$Setup.sha256"
        Assert-Throws { Verify-Sha256 $Setup "$Setup.sha256" } 'Release checksum not found'
    }
    Test-Case 'a checksum file that holds no SHA-256 is refused' {
        Set-Content -LiteralPath "$Setup.sha256" -Value 'pending' -Encoding ascii
        Assert-Throws { Verify-Sha256 $Setup "$Setup.sha256" } 'expected=pending'
    }
}
finally {
    Remove-Item -LiteralPath $Work -Recurse -Force -ErrorAction SilentlyContinue
}

if ($script:Failures.Count -gt 0) {
    Write-Host ''
    Write-Host "RELEASE SIGNATURE TESTS FAILED ($($script:Failures.Count))" -ForegroundColor Red
    $script:Failures | ForEach-Object { Write-Host " - $_" }
    exit 1
}
Write-Host ''
Write-Host 'RELEASE SIGNATURE TESTS SUCCESS' -ForegroundColor Green
