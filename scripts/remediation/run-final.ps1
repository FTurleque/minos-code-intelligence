param(
    [switch]$SkipCleanVerify,
    [ValidateRange(4, 2000)][int]$ProgramGraphFiles = 1000
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Push-Location $Root
try {
    $Head = (git rev-parse HEAD).Trim()
    if ([string]::IsNullOrWhiteSpace($Head)) { throw 'cannot resolve HEAD' }

    $StatusBefore = @(git status --porcelain=v1 --untracked-files=all)
    if ($StatusBefore.Count -ne 0) {
        throw "worktree must be clean before P0-P2 qualification: $($StatusBefore -join '; ')"
    }
    git diff --exit-code develop...HEAD -- .github/workflows
    if ($LASTEXITCODE -ne 0) { throw 'GitHub workflow changes are forbidden in this July-safe lot' }

    python scripts/remediation/check-p0-p2.py
    if ($LASTEXITCODE -ne 0) { throw 'P0-P2 structural gate failed' }

    python scripts/m28/check-m28.py
    if ($LASTEXITCODE -ne 0) { throw 'M28 convergence/decomposition gate failed' }

    python scripts/docs/product-facts.py --check
    if ($LASTEXITCODE -ne 0) { throw 'product facts gate failed' }

    python scripts/m28/check-current-docs.py
    if ($LASTEXITCODE -ne 0) { throw 'M28 current documentation gate failed' }

    python scripts/architecture/check-module-boundaries.py
    if ($LASTEXITCODE -ne 0) { throw 'architecture dependency gate failed' }

    if (-not $SkipCleanVerify) {
        .\mvnw.cmd clean verify
        if ($LASTEXITCODE -ne 0) { throw 'Maven clean verify failed' }
    } else {
        .\mvnw.cmd -pl minos-application,minos-runtime-local -am test
        if ($LASTEXITCODE -ne 0) { throw 'targeted Maven tests failed' }
    }

    python scripts/quality/check-jacoco.py
    if ($LASTEXITCODE -ne 0) { throw 'JaCoCo gate failed' }

    $ProgramGraphRunner = Join-Path $Root 'scripts\m28\run-program-graph-performance.ps1'
    & $ProgramGraphRunner -FileCount $ProgramGraphFiles
    if ($LASTEXITCODE -ne 0) { throw 'M28 ProgramGraph performance qualification failed' }

    $StatusAfter = @(git status --porcelain=v1 --untracked-files=all)
    if ($StatusAfter.Count -ne 0) {
        throw "qualification modified the worktree: $($StatusAfter -join '; ')"
    }
    $Validated = (git rev-parse HEAD).Trim()
    if ($Validated -ne $Head) { throw 'HEAD changed during qualification' }

    Write-Host 'M28 JULY-SAFE CROSS-CUTTING VALIDATION SUCCESS'
    Write-Host "Validated HEAD: $Validated"
    Write-Host 'GitHub Actions / M21-S2 were not invoked by this July-safe runner.'
} finally {
    Pop-Location
}
