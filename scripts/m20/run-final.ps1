[CmdletBinding()]
param(
    [string] $ExpectedHead = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
. (Join-Path $RepoRoot 'scripts\windows\MinosWindows.ps1')

function Invoke-NativeChecked {
    param(
        [Parameter(Mandatory = $true)][string] $File,
        [Parameter(Mandatory = $true)][string[]] $Arguments,
        [Parameter(Mandatory = $true)][string] $Failure
    )
    $previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $File @Arguments
        $exit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    if ($exit -ne 0) { throw "$Failure (exit=$exit)" }
}

function Resolve-Python {
    foreach ($name in @('python.exe','python','python3.exe','python3')) {
        $command = Get-Command $name -ErrorAction SilentlyContinue
        if ($command) { return $command.Source }
    }
    throw 'M20 requires Python in PATH for product-facts verification.'
}

function Require-File {
    param([Parameter(Mandatory = $true)][string] $Relative)
    $path = Join-Path $RepoRoot $Relative
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Required M20 file is missing: $Relative"
    }
    return $path
}

function Require-Pattern {
    param(
        [Parameter(Mandatory = $true)][string] $Relative,
        [Parameter(Mandatory = $true)][string] $Pattern,
        [Parameter(Mandatory = $true)][string] $Failure
    )
    $text = Get-Content -LiteralPath (Require-File -Relative $Relative) -Raw
    if ($text -notmatch $Pattern) { throw $Failure }
}

function Assert-M20Structure {
    $required = @(
        'docs\roadmap\M20_EXECUTION.md',
        'docs\adr\0029-optional-rebuildable-semantic-layer-and-hybrid-ranking.md',
        'docs\developer\semantic-hybrid-intelligence.md',
        'minos-domain\src\main\java\com\minos\semantic\SemanticDocument.java',
        'minos-domain\src\main\java\com\minos\semantic\SemanticDocumentKind.java',
        'minos-domain\src\main\java\com\minos\semantic\SemanticVector.java',
        'minos-domain\src\main\java\com\minos\semantic\SemanticVectorStore.java',
        'minos-storage-local\src\main\java\com\minos\store\FileSemanticVectorStore.java',
        'minos-application\src\main\java\com\minos\semantic\EmbeddingProvider.java',
        'minos-application\src\main\java\com\minos\semantic\LocalHashEmbeddingProvider.java',
        'minos-application\src\main\java\com\minos\semantic\SemanticDocumentFactory.java',
        'minos-application\src\main\java\com\minos\semantic\SemanticIndexService.java',
        'minos-application\src\main\java\com\minos\semantic\SemanticSearchService.java',
        'minos-application\src\main\java\com\minos\semantic\HybridSearchService.java',
        'minos-application\src\main\java\com\minos\semantic\HybridContextBuilder.java',
        'minos-application\src\main\java\com\minos\semantic\SemanticSearchEvaluator.java',
        'minos-cli\src\main\java\com\minos\cli\LocalAutonomousIndexOperations.java',
        'minos-api\src\main\java\com\minos\api\SemanticCodeIntelligenceApi.java',
        'minos-api\src\main\java\com\minos\api\LocalSemanticCodeIntelligenceApi.java',
        'minos-nexus\src\main\java\com\minos\integration\nexus\NexusSemanticSignalContract.java',
        'minos-nexus\src\main\java\com\minos\integration\nexus\NexusSemanticSignalService.java',
        'minos-storage-local\src\test\java\com\minos\store\FileSemanticVectorStoreTest.java',
        'minos-application\src\test\java\com\minos\semantic\SemanticHybridIntelligenceTest.java',
        'minos-api\src\test\java\com\minos\api\SemanticCodeIntelligenceApiContractTest.java',
        'minos-nexus\src\test\java\com\minos\integration\nexus\NexusSemanticSignalServiceTest.java',
        'minos-mcp\src\test\java\com\minos\mcp\MinosMcpToolsTest.java',
        '.github\workflows\m20-semantic-hybrid-intelligence.yml'
    )
    foreach ($relative in $required) { [void](Require-File -Relative $relative) }

    Require-Pattern 'minos-domain\pom.xml' `
        '<include>com/minos/semantic/\*\*/\*\.java</include>' 'M20 domain POM must compile com/minos/semantic/**/*.java explicitly.'
    Require-Pattern 'minos-application\pom.xml' `
        '<include>com/minos/semantic/\*\*/\*\.java</include>' 'M20 application POM must compile com/minos/semantic/**/*.java explicitly.'

    Require-Pattern 'minos-api\src\main\java\com\minos\api\MinosApi.java' `
        'CONTRACT_VERSION\s*=\s*"1"' 'M20 must not change historical MinosApi v1.'
    Require-Pattern 'minos-api\src\main\java\com\minos\api\AdvancedCodeIntelligenceApi.java' `
        'CONTRACT_VERSION\s*=\s*"1"' 'M20 must not change M19 AdvancedCodeIntelligenceApi v1.'
    Require-Pattern 'minos-api\src\main\java\com\minos\api\SemanticCodeIntelligenceApi.java' `
        'CONTRACT_VERSION\s*=\s*"1"' 'M20 semantic API must expose contract v1.'

    $mcp = Get-Content -LiteralPath (Require-File -Relative 'minos-mcp\src\main\java\com\minos\mcp\MinosMcpTools.java') -Raw
    if ($mcp -notmatch 'TOOL_COUNT\s*=\s*23') { throw 'M20 MCP catalogue must contain exactly 23 tools.' }
    foreach ($tool in @('minos_semantic_index_status','minos_semantic_search','minos_hybrid_search','minos_hybrid_context')) {
        if ($mcp -notmatch [regex]::Escape("tool(`"$tool`"")) { throw "M20 MCP tool missing: $tool" }
    }

    $application = Get-Content -LiteralPath (Require-File -Relative 'minos-application\src\main\java\com\minos\application\MinosApplication.java') -Raw
    if ($application -notmatch 'Optional\.ofNullable\(embeddingProvider\)' -or
        $application -notmatch 'embeddingProvider\(EmbeddingProvider value\)' -or
        $application -notmatch 'MINOS_SEMANTIC_PROVIDER' -or
        $application -notmatch 'new LocalHashEmbeddingProvider\(\)') {
        throw 'M20 embedding provider must remain disabled by default and explicitly activatable locally.'
    }

    $nativeIndex = Get-Content -LiteralPath (Require-File -Relative 'minos-cli\src\main\java\com\minos\cli\LocalAutonomousIndexOperations.java') -Raw
    if ($nativeIndex -notmatch 'semanticIndexService\(\)\.synchronize' -or
        $nativeIndex -notmatch 'semantic index refresh failed without invalidating structured snapshot') {
        throw 'M20 native indexing must refresh configured semantic indexes without invalidating structured success.'
    }

    $semantic = Get-Content -LiteralPath (Require-File -Relative 'minos-application\src\main\java\com\minos\semantic\SemanticSearchService.java') -Raw
    if ($semantic -notmatch 'InformationNature\.HEURISTIC' -or
        $semantic -notmatch 'VECTOR_SCORE_IS_RANKING_SIGNAL_NOT_STRUCTURAL_FACT') {
        throw 'M20 semantic results must remain HEURISTIC ranking signals, not structural facts.'
    }

    $hybrid = Get-Content -LiteralPath (Require-File -Relative 'minos-application\src\main\java\com\minos\semantic\HybridSearchService.java') -Raw
    if ($hybrid -notmatch 'SEMANTIC_SIGNAL_UNAVAILABLE_STRUCTURED_FALLBACK_USED' -or
        $hybrid -notmatch 'return clamp01\(score\)') {
        throw 'M20 hybrid search must keep a structured fallback and avoid artificial neutral semantic bonuses.'
    }

    $index = Get-Content -LiteralPath (Require-File -Relative 'minos-application\src\main\java\com\minos\semantic\SemanticIndexService.java') -Raw
    foreach ($proof in @('SEMANTIC_EMBEDDING_PROVIDER_UNAVAILABLE','SEMANTIC_INDEX_SNAPSHOT_STALE','checksum\(\)','reused\+\+','embeddedCount')) {
        if ($index -notmatch $proof) { throw "M20 semantic index invariant missing: $proof" }
    }

    $context = Get-Content -LiteralPath (Require-File -Relative 'minos-application\src\main\java\com\minos\semantic\HybridContextBuilder.java') -Raw
    if ($context -notmatch 'usedTokens > maxTokens' -or $context -notmatch 'MAX_DOCUMENTS\s*=\s*100') {
        throw 'M20 hybrid context must enforce document/token bounds.'
    }

    $evaluator = Get-Content -LiteralPath (Require-File -Relative 'minos-application\src\main\java\com\minos\semantic\SemanticSearchEvaluator.java') -Raw
    foreach ($metric in @('recallAtK','mrr','ndcgAtK','measurableGain')) {
        if ($evaluator -notmatch $metric) { throw "M20 relevance metric missing: $metric" }
    }

    $tests = Get-Content -LiteralPath (Require-File -Relative 'minos-application\src\test\java\com\minos\semantic\SemanticHybridIntelligenceTest.java') -Raw
    foreach ($proof in @('SEMANTIC_EMBEDDING_PROVIDER_UNAVAILABLE','VECTOR_SCORE_IS_RANKING_SIGNAL_NOT_STRUCTURAL_FACT','recallAtK','mrr\(\)','ndcgAtK','gain\.measurableGain\(\)','embeddedCount\(\) < incremental\.documentCount\(\)','usedTokens\(\) <= 180')) {
        if ($tests -notmatch $proof) { throw "M20 controlled qualification proof missing: $proof" }
    }

    $nexus = Get-Content -LiteralPath (Require-File -Relative 'minos-nexus\src\main\java\com\minos\integration\nexus\NexusSemanticSignalService.java') -Raw
    if ($nexus -notmatch 'NEXUS_GLOBAL_RANKING_NOT_PERFORMED_BY_MINOS' -or
        $nexus -notmatch 'NEXUS_MULTI_SOURCE_CONTEXT_BUDGET_NOT_OWNED_BY_MINOS') {
        throw 'M20 must preserve the MINOS/NEXUS responsibility boundary.'
    }
}

Push-Location $RepoRoot
try {
    Write-Host '=== MINOS M20 - FINAL Semantic & Hybrid Code Intelligence exact-head qualification ===' -ForegroundColor Cyan

    $dirty = @(& git status --porcelain)
    if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect worktree.' }
    if ($dirty.Count -gt 0) { throw "M20 final runner requires a clean worktree.`n$($dirty -join "`n")" }

    $head = ((& git rev-parse HEAD) | Select-Object -First 1).Trim()
    if ([string]::IsNullOrWhiteSpace($head)) { throw 'Unable to resolve HEAD.' }
    if (-not [string]::IsNullOrWhiteSpace($ExpectedHead) -and $head -ne $ExpectedHead) {
        throw "M20 exact-head mismatch: expected=$ExpectedHead actual=$head"
    }
    Write-Host "HEAD: $head"

    Write-Host '[1/6] Checking M20 semantic authority, bounds, incremental proofs and public surfaces...'
    Assert-M20Structure

    Write-Host '[2/6] Checking generated product facts...'
    $python = Resolve-Python
    Invoke-NativeChecked -File $python -Arguments @('scripts/docs/product-facts.py','--check') -Failure 'Product facts consistency failed'

    Write-Host '[3/6] Running complete Maven Java 24 verification...'
    $java = Resolve-MinosJava24
    $env:JAVA_HOME = $java.JavaHome
    $env:Path = "$($java.JavaHome)\bin;$env:Path"
    $maven = if ($env:OS -eq 'Windows_NT') { Join-Path $RepoRoot 'mvnw.cmd' } else { Join-Path $RepoRoot 'mvnw' }
    Invoke-NativeChecked -File $maven -Arguments @('-B','-ntp','clean','verify') -Failure 'Maven clean verify failed'

    Write-Host '[4/6] Checking JaCoCo quality gates...'
    Invoke-NativeChecked -File $python -Arguments @('scripts/quality/check-jacoco.py') -Failure 'JaCoCo gate failed'

    Write-Host '[5/6] Rechecking exact HEAD and clean tracked worktree...'
    $finalHead = ((& git rev-parse HEAD) | Select-Object -First 1).Trim()
    if ($finalHead -ne $head) { throw "HEAD changed during M20 qualification: start=$head end=$finalHead" }
    $finalDirty = @(& git status --porcelain)
    if ($LASTEXITCODE -ne 0) { throw 'Unable to re-inspect worktree.' }
    if ($finalDirty.Count -gt 0) { throw "Worktree changed during M20 qualification.`n$($finalDirty -join "`n")" }

    Write-Host '[6/6] Qualification complete.'
    Write-Host 'M20 FINAL SEMANTIC HYBRID CODE INTELLIGENCE VALIDATION SUCCESS' -ForegroundColor Green
    Write-Host "Validated HEAD: $head"
} finally {
    Pop-Location
}
