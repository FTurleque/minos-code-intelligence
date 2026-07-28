[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $Artifact,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9A-Fa-f]{40}$')]
    [string] $CertificateThumbprint,

    [string] $TimestampUrl = '',
    [string] $SignToolPath = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$Artifact = [System.IO.Path]::GetFullPath($Artifact)
if (-not (Test-Path -LiteralPath $Artifact -PathType Leaf)) {
    throw "Artifact not found: $Artifact"
}

function Resolve-SignTool([string] $Explicit) {
    if (-not [string]::IsNullOrWhiteSpace($Explicit)) {
        $Candidate = [System.IO.Path]::GetFullPath($Explicit)
        if (-not (Test-Path -LiteralPath $Candidate -PathType Leaf)) {
            throw "signtool.exe not found: $Candidate"
        }
        return $Candidate
    }

    $Command = Get-Command signtool.exe -ErrorAction SilentlyContinue
    if ($Command) { return $Command.Source }

    $Roots = @()
    if (-not [string]::IsNullOrWhiteSpace(${env:ProgramFiles(x86)})) {
        $Roots += (Join-Path ${env:ProgramFiles(x86)} 'Windows Kits\10\bin')
    }
    if (-not [string]::IsNullOrWhiteSpace($env:ProgramFiles)) {
        $Roots += (Join-Path $env:ProgramFiles 'Windows Kits\10\bin')
    }

    foreach ($Root in $Roots) {
        if (-not (Test-Path -LiteralPath $Root -PathType Container)) { continue }
        $Candidate = Get-ChildItem -LiteralPath $Root -Directory -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending |
            ForEach-Object { Join-Path $_.FullName 'x64\signtool.exe' } |
            Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
            Select-Object -First 1
        if ($Candidate) { return $Candidate }
    }

    throw 'signtool.exe was not found. Install the Windows SDK or provide -SignToolPath.'
}

$SignTool = Resolve-SignTool -Explicit $SignToolPath
$Arguments = @(
    'sign',
    '/sha1', $CertificateThumbprint,
    '/fd', 'SHA256'
)
if (-not [string]::IsNullOrWhiteSpace($TimestampUrl)) {
    $Arguments += @('/tr', $TimestampUrl, '/td', 'SHA256')
}
$Arguments += @('/v', $Artifact)

& $SignTool @Arguments
if ($LASTEXITCODE -ne 0) {
    throw "Authenticode signing failed for $Artifact (exit=$LASTEXITCODE)"
}

$Signature = Get-AuthenticodeSignature -LiteralPath $Artifact
if ($Signature.Status -ne [System.Management.Automation.SignatureStatus]::Valid) {
    throw "Authenticode verification failed after signing: status=$($Signature.Status), message=$($Signature.StatusMessage)"
}

Write-Host 'MINOS AUTHENTICODE SIGNING SUCCESS' -ForegroundColor Green
Write-Host "Artifact      : $Artifact"
Write-Host "Signer        : $($Signature.SignerCertificate.Subject)"
Write-Host "Thumbprint    : $($Signature.SignerCertificate.Thumbprint)"
Write-Host "Timestamped   : $([bool]$Signature.TimeStamperCertificate)"
