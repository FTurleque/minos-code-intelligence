[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string] $ExpectedHead,
    [string] $Version = '0.2.0-m23'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$RequiredSemanticProvider = 'ollama'
$RequiredSemanticModel = 'embeddinggemma'
$RequiredSemanticDimensions = '768'
$RequiredSemanticEndpoint = 'http://127.0.0.1:11434/api/embed'
$SemanticEnvironmentNames = @(
    'MINOS_SEMANTIC_PROVIDER',
    'MINOS_SEMANTIC_MODEL',
    'MINOS_SEMANTIC_DIMENSIONS',
    'MINOS_SEMANTIC_ENDPOINT',
    'MINOS_SEMANTIC_TIMEOUT_SECONDS'
)

if ($env:OS -ne 'Windows_NT') {
    throw 'M23 final qualification must run on Windows because it includes the qualified Windows release gate.'
}
if ([string]::IsNullOrWhiteSpace($ExpectedHead)) {
    throw 'M23 final qualification requires an explicit -ExpectedHead SHA.'
}
if ($Version -ne '0.2.0-m23') {
    throw "M23 final qualification requires release candidate version 0.2.0-m23, got: $Version"
}
if ($env:MINOS_SEMANTIC_PROVIDER -ne $RequiredSemanticProvider) {
    throw "M23 final qualification requires MINOS_SEMANTIC_PROVIDER=$RequiredSemanticProvider."
}
if ($env:MINOS_SEMANTIC_MODEL -ne $RequiredSemanticModel) {
    throw "M23 final qualification requires canonical model $RequiredSemanticModel."
}
if ($env:MINOS_SEMANTIC_DIMENSIONS -ne $RequiredSemanticDimensions) {
    throw "M23 final qualification requires MINOS_SEMANTIC_DIMENSIONS=$RequiredSemanticDimensions."
}
if ($env:MINOS_SEMANTIC_ENDPOINT -ne $RequiredSemanticEndpoint) {
    throw "M23 final qualification requires MINOS_SEMANTIC_ENDPOINT=$RequiredSemanticEndpoint."
}

function Resolve-Python {
    foreach ($Name in @('python.exe', 'python', 'python3.exe', 'python3')) {
        $Command = Get-Command $Name -ErrorAction SilentlyContinue
        if ($Command) { return $Command.Source }
    }
    throw 'M23 final qualification requires Python in PATH.'
}

function Get-Head {
    $Value = ((& git rev-parse HEAD) | Select-Object -First 1).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($Value)) {
        throw 'Unable to resolve git HEAD.'
    }
    return $Value
}

function Assert-CleanWorktree([string] $Stage) {
    $Dirty = @(& git status --porcelain)
    if ($LASTEXITCODE -ne 0) { throw "Unable to inspect worktree during $Stage." }
    if ($Dirty.Count -gt 0) {
        throw "M23 requires a clean worktree during $Stage.`n$($Dirty -join "`n")"
    }
}

function Invoke-PythonGate {
    param(
        [Parameter(Mandatory = $true)][string] $Python,
        [Parameter(Mandatory = $true)][string] $Script,
        [Parameter(Mandatory = $true)][string] $Failure
    )
    & $Python $Script
    if ($LASTEXITCODE -ne 0) { throw "$Failure (exit=$LASTEXITCODE)" }
}

function Invoke-WithSemanticDisabled {
    param([Parameter(Mandatory = $true)][scriptblock] $Action)

    $Saved = @{}
    foreach ($Name in $SemanticEnvironmentNames) {
        $Path = "Env:$Name"
        if (Test-Path $Path) {
            $Saved[$Name] = (Get-Item -Path $Path).Value
            Remove-Item -Path $Path
        }
    }

    try {
        & $Action
    }
    finally {
        foreach ($Name in $SemanticEnvironmentNames) {
            $Path = "Env:$Name"
            Remove-Item -Path $Path -ErrorAction SilentlyContinue
            if ($Saved.ContainsKey($Name)) {
                Set-Item -Path $Path -Value $Saved[$Name]
            }
        }
    }
}

Push-Location $RepoRoot
try {
    Write-Host '=== MINOS M23 - FINAL Semantic Retrieval 2.0 exact-head qualification ===' -ForegroundColor Cyan

    Assert-CleanWorktree 'preflight'
    $Head = Get-Head
    if ($Head -ne $ExpectedHead) {
        throw "M23 exact-head mismatch: expected=$ExpectedHead actual=$Head"
    }
    Write-Host "HEAD: $Head"
    Write-Host "Release candidate version: $Version"
    Write-Host "Learned qualification profile: provider=$RequiredSemanticProvider model=$RequiredSemanticModel dimensions=$RequiredSemanticDimensions endpoint=$RequiredSemanticEndpoint"

    $Python = Resolve-Python

    Write-Host '[1/7] Checking M23 static contract and current documentation consistency...'
    Invoke-PythonGate -Python $Python -Script 'scripts\m23\check-semantic.py' `
        -Failure 'M23 semantic retrieval consistency gate failed'
    Invoke-PythonGate -Python $Python -Script 'scripts\docs\check-current-docs.py' `
        -Failure 'Current documentation consistency failed before M23 qualification'

    Write-Host '[2/7] Measuring the canonical local learned embedding model...'
    Invoke-PythonGate -Python $Python -Script 'scripts\m23\evaluate-learned-quality.py' `
        -Failure 'M23 learned semantic quality gate failed'

    Write-Host '[3/7] Replaying authoritative local core, Maven tests, module boundaries and JaCoCo with semantic opt-in isolated...'
    Invoke-WithSemanticDisabled {
        & (Join-Path $RepoRoot 'scripts\m21\run-local.ps1') -ExpectedHead $Head
        if ($LASTEXITCODE -ne 0) { throw "M21/M20 local qualification failed on the M23 head (exit=$LASTEXITCODE)" }
    }

    Write-Host '[4/7] Checking M22 provider regression and M23 contract after the core build...'
    Invoke-PythonGate -Python $Python -Script 'scripts\m22\check-provider.py' `
        -Failure 'M22 provider regression gate failed on the M23 head'
    Invoke-PythonGate -Python $Python -Script 'scripts\m23\check-semantic.py' `
        -Failure 'M23 semantic retrieval consistency changed after the core build'

    Write-Host '[5/7] Replaying supply-chain and qualified Windows release validation with semantic opt-in isolated...'
    Invoke-WithSemanticDisabled {
        & (Join-Path $RepoRoot 'scripts\m21\run-s5.ps1') -ExpectedHead $Head -Version $Version
        if ($LASTEXITCODE -ne 0) { throw "M23 Windows release qualification failed (exit=$LASTEXITCODE)" }
    }

    Write-Host '[6/7] Replaying IntelliJ external-client parity, tests, build and Plugin Verifier with semantic opt-in isolated...'
    Invoke-WithSemanticDisabled {
        & (Join-Path $RepoRoot 'scripts\m21\run-s6.ps1') -ExpectedHead $Head
        if ($LASTEXITCODE -ne 0) { throw "M23 IntelliJ parity qualification failed (exit=$LASTEXITCODE)" }
    }

    Write-Host '[7/7] Rechecking learned quality, contracts, documentation, exact HEAD and clean worktree...'
    Invoke-PythonGate -Python $Python -Script 'scripts\m23\evaluate-learned-quality.py' `
        -Failure 'M23 learned semantic quality changed during qualification'
    Invoke-PythonGate -Python $Python -Script 'scripts\m22\check-provider.py' `
        -Failure 'M22 provider regression recheck failed on the M23 head'
    Invoke-PythonGate -Python $Python -Script 'scripts\m23\check-semantic.py' `
        -Failure 'M23 semantic retrieval consistency recheck failed'
    Invoke-PythonGate -Python $Python -Script 'scripts\docs\check-current-docs.py' `
        -Failure 'Current documentation consistency changed during M23 qualification'

    $FinalHead = Get-Head
    if ($FinalHead -ne $Head) {
        throw "HEAD changed during M23 final qualification: start=$Head end=$FinalHead"
    }
    Assert-CleanWorktree 'final gate'

    Write-Host 'M23 FINAL SEMANTIC RETRIEVAL 2.0 VALIDATION SUCCESS' -ForegroundColor Green
    Write-Host "Validated HEAD: $Head"
}
finally {
    Pop-Location
}
