[CmdletBinding()]
param([Parameter(Mandatory = $true)][string] $ExpectedHead)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$M27Base = '5db06f2a778b60b318ae6d83ad76928c24672810'

if ($env:OS -ne 'Windows_NT') { throw 'M27 Windows final qualification must run on Windows.' }
if ($ExpectedHead -notmatch '^[0-9a-f]{40}$') { throw 'M27 requires an explicit lowercase 40-character -ExpectedHead SHA.' }

function Resolve-Python {
    foreach ($Name in @('python.exe', 'python', 'python3.exe', 'python3')) {
        $Command = Get-Command $Name -ErrorAction SilentlyContinue
        if ($Command) { return $Command.Source }
    }
    throw 'M27 final qualification requires Python in PATH.'
}
function Get-Head {
    $Value = ((& git rev-parse HEAD) | Select-Object -First 1).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($Value)) { throw 'Unable to resolve git HEAD.' }
    return $Value
}
function Assert-Clean([string] $Stage) {
    $Dirty = @(& git status --porcelain)
    if ($LASTEXITCODE -ne 0) { throw "Unable to inspect worktree during $Stage." }
    if ($Dirty.Count -gt 0) { throw "M27 requires a clean worktree during $Stage.`n$($Dirty -join "`n")" }
}
function Assert-NoWorkflowChanges {
    & git diff --quiet $M27Base HEAD -- .github/workflows
    if ($LASTEXITCODE -ne 0) { throw 'M27 forbids changes under .github/workflows.' }
}
function Invoke-PythonGate([string] $Python, [string] $Script, [string] $Failure) {
    & $Python $Script
    if ($LASTEXITCODE -ne 0) { throw "$Failure (exit=$LASTEXITCODE)" }
}
function Invoke-SemanticDisabled([Parameter(Mandatory = $true)][scriptblock] $Action) {
    $Names = @('MINOS_SEMANTIC_PROVIDER', 'MINOS_SEMANTIC_MODEL', 'MINOS_SEMANTIC_DIMENSIONS',
               'MINOS_SEMANTIC_ENDPOINT', 'MINOS_SEMANTIC_TIMEOUT_SECONDS', 'MINOS_HOSTED_MODE',
               'MINOS_TEAM_TOKEN')
    $Saved = @{}
    foreach ($Name in $Names) {
        $Path = "Env:$Name"
        if (Test-Path $Path) { $Saved[$Name] = (Get-Item $Path).Value }
        Remove-Item $Path -ErrorAction SilentlyContinue
    }
    try { $env:MINOS_SEMANTIC_PROVIDER = 'disabled'; & $Action }
    finally {
        foreach ($Name in $Names) {
            Remove-Item "Env:$Name" -ErrorAction SilentlyContinue
            if ($Saved.ContainsKey($Name)) { Set-Item "Env:$Name" $Saved[$Name] }
        }
    }
}

Push-Location $RepoRoot
try {
    Write-Host '=== MINOS M27 - FINAL Team / Hosted Mode Windows exact-head qualification ===' -ForegroundColor Cyan
    Assert-Clean 'preflight'
    $Head = Get-Head
    if ($Head -ne $ExpectedHead) { throw "M27 exact-head mismatch: expected=$ExpectedHead actual=$Head" }
    Assert-NoWorkflowChanges
    $Python = Resolve-Python
    foreach ($CommandName in @('java', 'javac')) {
        if (-not (Get-Command $CommandName -ErrorAction SilentlyContinue)) { throw "M27 requires $CommandName in PATH." }
    }
    $JavaVersion = (& java -version 2>&1 | Select-Object -First 1 | Out-String).Trim()
    if ($JavaVersion -notmatch '"24(?:\.|"|$)') { throw "M27 requires Java 24; got: $JavaVersion" }
    Write-Host "HEAD: $Head"
    Write-Host "Java: $JavaVersion"

    Write-Host '[1/7] M27 static, documentation and previous milestone contracts...'
    Invoke-PythonGate $Python 'scripts\m27\check-hosted.py' 'M27 consistency gate failed'
    Invoke-PythonGate $Python 'scripts\docs\check-current-docs.py' 'Current documentation consistency failed'
    Invoke-PythonGate $Python 'scripts\m26\check-runtime-dynamic.py' 'M26 regression gate failed'

    Write-Host '[2/7] Full Java 24 Maven reactor...'
    Invoke-SemanticDisabled {
        & .\mvnw.cmd clean verify
        if ($LASTEXITCODE -ne 0) { throw "M27 Maven reactor failed (exit=$LASTEXITCODE)" }
    }

    Write-Host '[3/7] JaCoCo including M27 scope...'
    Invoke-PythonGate $Python 'scripts\quality\check-jacoco.py' 'M27 JaCoCo gate failed'

    Write-Host '[4/7] Historical polyglot, remote and runtime regression contracts...'
    Invoke-PythonGate $Python 'scripts\m24\check-polyglot.py' 'M24 regression gate failed'
    Invoke-PythonGate $Python 'scripts\m25\check-remote-distributed.py' 'M25 regression gate failed'
    Invoke-PythonGate $Python 'scripts\m26\check-runtime-dynamic.py' 'M26 regression recheck failed'

    Write-Host '[5/7] Shaded CLI tenant/auth/RBAC/encryption/audit/retention e2e...'
    Invoke-SemanticDisabled {
        & $Python 'scripts\m27\run-hosted-e2e.py' --expected-head $Head --output 'target/m27/hosted-e2e-windows.json'
        if ($LASTEXITCODE -ne 0) { throw "M27 Windows hosted e2e failed (exit=$LASTEXITCODE)" }
    }

    Write-Host '[6/7] Detailed evidence recheck...'
    Invoke-PythonGate $Python 'scripts\m27\check-hosted.py' 'M27 consistency recheck failed'
    Invoke-PythonGate $Python 'scripts\docs\check-current-docs.py' 'Documentation recheck failed'
    $Evidence = Get-Content 'target\m27\hosted-e2e-windows.json' -Raw | ConvertFrom-Json
    if ($Evidence.status -ne 'PASS' -or $Evidence.commit -ne $Head `
            -or $Evidence.mode -ne 'OPT_IN_LOCAL_CONTROL_PLANE' `
            -or $Evidence.isolation.crossTenantLeak -ne $false `
            -or $Evidence.authentication.tokenInArguments -ne $false `
            -or $Evidence.authentication.oldKeyRejected -ne $true `
            -or $Evidence.authorization.viewerMutationDenied -ne $true `
            -or $Evidence.authorization.denialAudited -ne $true `
            -or $Evidence.binding.staleRejected -ne $true `
            -or $Evidence.storage.algorithm -ne 'AES-256-GCM' `
            -or $Evidence.storage.plaintextAbsent -ne $true `
            -or $Evidence.storage.tamperRejected -ne $true `
            -or $Evidence.retention.implicitDeletion -ne $false `
            -or $Evidence.mcp.tools -ne 31 -or $Evidence.mcp.readOnlyTeamTools -ne 5 `
            -or $Evidence.mcp.tokenArguments -ne $false) {
        throw 'M27 Windows detailed e2e evidence is not an exact-head PASS.'
    }

    Write-Host '[7/7] Exact HEAD, workflow diff and clean-worktree final gate...'
    $FinalHead = Get-Head
    if ($FinalHead -ne $Head) { throw "HEAD changed during M27 qualification: start=$Head end=$FinalHead" }
    Assert-NoWorkflowChanges
    Assert-Clean 'final gate'
    Write-Host 'M27 FINAL TEAM HOSTED MODE VALIDATION SUCCESS' -ForegroundColor Green
    Write-Host "Validated HEAD: $Head"
}
finally { Pop-Location }
