param(
    [switch]$SkipCleanVerify
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Push-Location $Root
try {
    $Head = (git rev-parse HEAD).Trim()
    if ([string]::IsNullOrWhiteSpace($Head)) { throw 'cannot resolve HEAD' }

    if (-not (git diff --quiet -- .)) {
        throw 'worktree must be clean before P0-P2 qualification'
    }
    git diff --exit-code develop...HEAD -- .github/workflows

    python scripts/remediation/check-p0-p2.py
    if ($LASTEXITCODE -ne 0) { throw 'P0-P2 structural gate failed' }

    python scripts/docs/product-facts.py --check
    if ($LASTEXITCODE -ne 0) { throw 'product facts gate failed' }

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

    if (-not (git diff --quiet -- .)) {
        throw 'qualification modified the worktree'
    }
    $Validated = (git rev-parse HEAD).Trim()
    if ($Validated -ne $Head) { throw 'HEAD changed during qualification' }

    Write-Host 'P0-P2 FINAL AUDIT REMEDIATION VALIDATION SUCCESS'
    Write-Host "Validated HEAD: $Validated"
    Write-Host 'GitHub Actions / M21-S2 were not invoked by this July-safe runner.'
} finally {
    Pop-Location
}
