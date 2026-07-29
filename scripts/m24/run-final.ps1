[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string] $ExpectedHead,
    [string] $Version = '0.2.0-m24'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$M24Base = '8dbe34cb9e524acb62becda4faa263d74b90b9a9'
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

if ($env:OS -ne 'Windows_NT') { throw 'M24 Windows final qualification must run on Windows.' }
if ([string]::IsNullOrWhiteSpace($ExpectedHead)) { throw 'M24 final qualification requires an explicit -ExpectedHead SHA.' }
if ($Version -ne '0.2.0-m24') { throw "M24 requires release candidate version 0.2.0-m24, got: $Version" }
if ($env:MINOS_SEMANTIC_PROVIDER -ne $RequiredSemanticProvider) { throw "M24 final qualification requires MINOS_SEMANTIC_PROVIDER=$RequiredSemanticProvider." }
if ($env:MINOS_SEMANTIC_MODEL -ne $RequiredSemanticModel) { throw "M24 final qualification requires MINOS_SEMANTIC_MODEL=$RequiredSemanticModel." }
if ($env:MINOS_SEMANTIC_DIMENSIONS -ne $RequiredSemanticDimensions) { throw "M24 final qualification requires MINOS_SEMANTIC_DIMENSIONS=$RequiredSemanticDimensions." }
if ($env:MINOS_SEMANTIC_ENDPOINT -ne $RequiredSemanticEndpoint) { throw "M24 final qualification requires MINOS_SEMANTIC_ENDPOINT=$RequiredSemanticEndpoint." }

function Resolve-Python {
    foreach ($Name in @('python.exe', 'python', 'python3.exe', 'python3')) {
        $Command = Get-Command $Name -ErrorAction SilentlyContinue
        if ($Command) { return $Command.Source }
    }
    throw 'M24 final qualification requires Python in PATH.'
}

function Get-Head {
    $Value = ((& git rev-parse HEAD) | Select-Object -First 1).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($Value)) { throw 'Unable to resolve git HEAD.' }
    return $Value
}

function Assert-CleanWorktree([string] $Stage) {
    $Dirty = @(& git status --porcelain)
    if ($LASTEXITCODE -ne 0) { throw "Unable to inspect worktree during $Stage." }
    if ($Dirty.Count -gt 0) { throw "M24 requires a clean worktree during $Stage.`n$($Dirty -join "`n")" }
}

function Assert-NoWorkflowChanges {
    & git diff --quiet $M24Base HEAD -- .github/workflows
    if ($LASTEXITCODE -ne 0) { throw 'M24 forbids changes under .github/workflows.' }
}

function Invoke-PythonGate {
    param([string] $Python, [string] $Script, [string] $Failure)
    & $Python $Script
    if ($LASTEXITCODE -ne 0) { throw "$Failure (exit=$LASTEXITCODE)" }
}

function Invoke-WithSemanticDisabled {
    param([Parameter(Mandatory = $true)][scriptblock] $Action)
    $Saved = @{}
    foreach ($Name in $SemanticEnvironmentNames) {
        $Path = "Env:$Name"
        if (Test-Path $Path) { $Saved[$Name] = (Get-Item -Path $Path).Value; Remove-Item -Path $Path }
    }
    try {
        $env:MINOS_SEMANTIC_PROVIDER = 'disabled'
        & $Action
    }
    finally {
        foreach ($Name in $SemanticEnvironmentNames) {
            Remove-Item -Path "Env:$Name" -ErrorAction SilentlyContinue
            if ($Saved.ContainsKey($Name)) { Set-Item -Path "Env:$Name" -Value $Saved[$Name] }
        }
    }
}

Push-Location $RepoRoot
try {
    Write-Host '=== MINOS M24 - FINAL Polyglot Expansion Windows exact-head qualification ===' -ForegroundColor Cyan
    Assert-CleanWorktree 'preflight'
    $Head = Get-Head
    if ($Head -ne $ExpectedHead) { throw "M24 exact-head mismatch: expected=$ExpectedHead actual=$Head" }
    Assert-NoWorkflowChanges
    $Python = Resolve-Python
    Write-Host "HEAD: $Head"
    Write-Host "Release candidate version: $Version"

    Write-Host '[preflight] Windows M24 toolchains and canonical semantic profile...'
    & (Join-Path $RepoRoot 'scripts\m24\check-windows-prerequisites.ps1')
    if ($LASTEXITCODE -ne 0) { throw "M24 Windows prerequisite gate failed (exit=$LASTEXITCODE)" }

    Write-Host '[1/9] M24 static provider/discovery/documentation contract...'
    Invoke-PythonGate $Python 'scripts\m24\check-polyglot.py' 'M24 polyglot consistency gate failed'
    Invoke-PythonGate $Python 'scripts\docs\check-current-docs.py' 'Current documentation consistency failed'

    Write-Host '[2/9] Canonical M23 learned retrieval regression...'
    Invoke-PythonGate $Python 'scripts\m23\evaluate-learned-quality.py' 'M23 learned semantic quality regression failed'

    Write-Host '[3/9] Authoritative local core + Java 24 Maven verify + JaCoCo + module boundaries...'
    Invoke-WithSemanticDisabled {
        & (Join-Path $RepoRoot 'scripts\m21\run-local.ps1') -ExpectedHead $Head
        if ($LASTEXITCODE -ne 0) { throw "M21/M20 local qualification failed on M24 head (exit=$LASTEXITCODE)" }
    }

    Write-Host '[4/9] M17/M22/M23 functional/static regressions...'
    Invoke-PythonGate $Python 'scripts\m22\check-provider.py' 'M22 provider regression gate failed'
    Invoke-PythonGate $Python 'scripts\m23\check-semantic.py' 'M23 semantic contract regression failed'
    Invoke-WithSemanticDisabled {
        & .\mvnw.cmd -q -pl minos-application,minos-provider-scip,minos-app -am test `
            '-Dtest=M24PolyglotDiscoveryTest,M24PolyglotProviderTest,M24PolyglotIdentityProvenanceTest,M24PolyglotProcessPlanFactoryTest,ManagedPolyglotScipRuntimeManagerTest,M17ProviderPlatformTest' `
            '-Dsurefire.failIfNoSpecifiedTests=false'
        if ($LASTEXITCODE -ne 0) { throw "M17/M24 targeted regression tests failed (exit=$LASTEXITCODE)" }
    }

    Write-Host '[5/9] Real M24 provider readiness/install/index/snapshot/identity/provenance evaluation on Windows...'
    Invoke-WithSemanticDisabled {
        & $Python 'scripts\m24\run-provider-e2e.py' `
            --output 'target/m24/provider-evaluation-windows.json' `
            --require-e2e 'scip-go,rust-analyzer-scip'
        if ($LASTEXITCODE -ne 0) { throw "M24 Windows provider e2e evaluation failed (exit=$LASTEXITCODE)" }
    }

    Write-Host '[6/9] M21-S5 supply-chain and Windows release package gate...'
    Invoke-WithSemanticDisabled {
        & (Join-Path $RepoRoot 'scripts\m21\run-s5.ps1') -ExpectedHead $Head -Version $Version
        if ($LASTEXITCODE -ne 0) { throw "M24 Windows release qualification failed (exit=$LASTEXITCODE)" }
    }

    Write-Host '[7/9] M21-S6 IntelliJ parity + tests/build + Plugin Verifier...'
    Invoke-WithSemanticDisabled {
        & (Join-Path $RepoRoot 'scripts\m21\run-s6.ps1') -ExpectedHead $Head
        if ($LASTEXITCODE -ne 0) { throw "M24 IntelliJ qualification failed (exit=$LASTEXITCODE)" }
    }

    Write-Host '[8/9] Learned regression and M24/M22/M23/docs recheck...'
    Invoke-PythonGate $Python 'scripts\m23\evaluate-learned-quality.py' 'M23 learned semantic quality changed during M24 qualification'
    Invoke-PythonGate $Python 'scripts\m22\check-provider.py' 'M22 provider recheck failed'
    Invoke-PythonGate $Python 'scripts\m23\check-semantic.py' 'M23 semantic recheck failed'
    Invoke-PythonGate $Python 'scripts\m24\check-polyglot.py' 'M24 polyglot recheck failed'
    Invoke-PythonGate $Python 'scripts\docs\check-current-docs.py' 'Current documentation consistency changed during M24 qualification'

    Write-Host '[9/9] Exact HEAD, workflow-diff and clean-worktree final gate...'
    $FinalHead = Get-Head
    if ($FinalHead -ne $Head) { throw "HEAD changed during M24 qualification: start=$Head end=$FinalHead" }
    Assert-NoWorkflowChanges
    Assert-CleanWorktree 'final gate'

    Write-Host 'M24 FINAL POLYGLOT EXPANSION VALIDATION SUCCESS' -ForegroundColor Green
    Write-Host "Validated HEAD: $Head"
}
finally {
    Pop-Location
}
