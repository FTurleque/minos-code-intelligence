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
    throw 'M19 requires Python in PATH for product-facts verification.'
}

function Require-File {
    param([Parameter(Mandatory = $true)][string] $Relative)
    $path = Join-Path $RepoRoot $Relative
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Required M19 file is missing: $Relative"
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

function Assert-M19Structure {
    $required = @(
        'docs\roadmap\M19_EXECUTION.md',
        'docs\adr\0028-capability-honest-program-graph-and-bounded-advanced-analysis.md',
        'minos-domain\src\main\java\com\minos\program\ProgramGraph.java',
        'minos-domain\src\main\java\com\minos\program\ProgramGraphCapability.java',
        'minos-domain\src\main\java\com\minos\program\ProgramGraphNode.java',
        'minos-domain\src\main\java\com\minos\program\ProgramGraphEdge.java',
        'minos-application\src\main\java\com\minos\program\analysis\ProgramGraphProvider.java',
        'minos-application\src\main\java\com\minos\program\analysis\ProgramGraphComposer.java',
        'minos-application\src\main\java\com\minos\program\analysis\RelationshipProgramGraphProvider.java',
        'minos-application\src\main\java\com\minos\program\analysis\ProgramGraphService.java',
        'minos-application\src\main\java\com\minos\program\analysis\ProgramGraphEvaluator.java',
        'minos-application\src\main\java\com\minos\program\analysis\InterproceduralFlowService.java',
        'minos-application\src\main\java\com\minos\program\analysis\AdvancedImpactService.java',
        'minos-application\src\main\java\com\minos\program\analysis\SecurityAnalysisService.java',
        'minos-api\src\main\java\com\minos\api\AdvancedCodeIntelligenceApi.java',
        'minos-api\src\main\java\com\minos\api\LocalAdvancedCodeIntelligenceApi.java',
        'minos-application\src\test\java\com\minos\program\analysis\ProgramGraphAnalysisTest.java',
        'minos-application\src\test\java\com\minos\program\analysis\ProgramDataFlowGroundTruthTest.java',
        'minos-api\src\test\java\com\minos\api\AdvancedCodeIntelligenceApiContractTest.java',
        'minos-mcp\src\test\java\com\minos\mcp\MinosMcpToolsTest.java',
        '.github\workflows\m19-advanced-code-intelligence.yml'
    )
    foreach ($relative in $required) { [void](Require-File -Relative $relative) }

    Require-Pattern 'minos-domain\pom.xml' `
        '<include>com/minos/program/\*\*/\*\.java</include>' 'M19 domain POM must compile com/minos/program/**/*.java explicitly.'
    Require-Pattern 'minos-application\pom.xml' `
        '<include>com/minos/program/\*\*/\*\.java</include>' 'M19 application POM must compile com/minos/program/**/*.java explicitly.'

    Require-Pattern 'minos-api\src\main\java\com\minos\api\MinosApi.java' `
        'CONTRACT_VERSION\s*=\s*"1"' 'M19 must not change the historical MinosApi v1 contract version.'
    Require-Pattern 'minos-api\src\main\java\com\minos\api\AdvancedCodeIntelligenceApi.java' `
        'CONTRACT_VERSION\s*=\s*"1"' 'M19 advanced Java API must expose contract v1.'

    $mcp = Get-Content -LiteralPath (Require-File -Relative 'minos-mcp\src\main\java\com\minos\mcp\MinosMcpTools.java') -Raw
    if ($mcp -notmatch 'TOOL_COUNT\s*=\s*19') { throw 'M19 MCP catalogue must contain exactly 19 tools.' }
    foreach ($tool in @('minos_program_graph','minos_impact_v2','minos_security_paths')) {
        if ($mcp -notmatch [regex]::Escape("tool(`"$tool`"")) { throw "M19 MCP tool missing: $tool" }
    }

    $capabilities = Get-Content -LiteralPath (Require-File -Relative 'minos-domain\src\main\java\com\minos\program\ProgramGraphCapability.java') -Raw
    foreach ($capability in @('CALL_GRAPH','CONTROL_FLOW','LOCAL_DATA_FLOW','INTERPROCEDURAL_DATA_FLOW','CPG','SECURITY_TAINT')) {
        if ($capabilities -notmatch "\b$capability\b") { throw "M19 program graph capability missing: $capability" }
    }

    $relationshipProvider = Get-Content -LiteralPath (Require-File -Relative 'minos-application\src\main\java\com\minos\program\analysis\RelationshipProgramGraphProvider.java') -Raw
    if ($relationshipProvider -notmatch 'EXECUTION_ORDER_NOT_PROVEN' -or
        $relationshipProvider -notmatch 'InformationNature\.DERIVED') {
        throw 'M19 READS/WRITES projection must remain explicitly derived with execution-order limitation.'
    }

    $service = Get-Content -LiteralPath (Require-File -Relative 'minos-application\src\main\java\com\minos\program\analysis\ProgramGraphService.java') -Raw
    foreach ($limitation in @('CONTROL_FLOW_UNAVAILABLE','LOCAL_DATA_FLOW_UNAVAILABLE','SECURITY_ANNOTATIONS_UNAVAILABLE')) {
        if ($service -notmatch $limitation) { throw "M19 missing explicit capability limitation: $limitation" }
    }

    $security = Get-Content -LiteralPath (Require-File -Relative 'minos-application\src\main\java\com\minos\program\analysis\SecurityAnalysisService.java') -Raw
    if ($security -notmatch 'ABSENCE_OF_PATH_IS_NOT_PROOF_OF_SAFETY' -or
        $security -notmatch 'PATH_SEARCH_BOUNDED') {
        throw 'M19 security analysis must remain bounded and must not treat absence of a path as proof of safety.'
    }

    $tests = (Get-Content -LiteralPath (Require-File -Relative 'minos-application\src\test\java\com\minos\program\analysis\ProgramGraphAnalysisTest.java') -Raw) +
             (Get-Content -LiteralPath (Require-File -Relative 'minos-application\src\test\java\com\minos\program\analysis\ProgramDataFlowGroundTruthTest.java') -Raw)
    foreach ($proof in @('evaluation\.perfect\(\)','CYCLE_OBSERVED','advancedAddedCount','sanitizedPathObserved','ProgramEdgeKind\.DEF_USE')) {
        if ($tests -notmatch $proof) { throw "M19 controlled qualification proof missing: $proof" }
    }
}

Push-Location $RepoRoot
try {
    Write-Host '=== MINOS M19 - FINAL Advanced Code Intelligence exact-head qualification ===' -ForegroundColor Cyan

    $dirty = @(& git status --porcelain)
    if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect worktree.' }
    if ($dirty.Count -gt 0) { throw "M19 final runner requires a clean worktree.`n$($dirty -join "`n")" }

    $head = ((& git rev-parse HEAD) | Select-Object -First 1).Trim()
    if ([string]::IsNullOrWhiteSpace($head)) { throw 'Unable to resolve HEAD.' }
    if (-not [string]::IsNullOrWhiteSpace($ExpectedHead) -and $head -ne $ExpectedHead) {
        throw "M19 exact-head mismatch: expected=$ExpectedHead actual=$head"
    }
    Write-Host "HEAD: $head"

    Write-Host '[1/6] Checking M19 structure, capability honesty and controlled proofs...'
    Assert-M19Structure

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
    if ($finalHead -ne $head) { throw "HEAD changed during M19 qualification: start=$head end=$finalHead" }
    $finalDirty = @(& git status --porcelain)
    if ($LASTEXITCODE -ne 0) { throw 'Unable to re-inspect worktree.' }
    if ($finalDirty.Count -gt 0) { throw "Worktree changed during M19 qualification.`n$($finalDirty -join "`n")" }

    Write-Host '[6/6] Qualification complete.'
    Write-Host 'M19 FINAL ADVANCED CODE INTELLIGENCE VALIDATION SUCCESS' -ForegroundColor Green
    Write-Host "Validated HEAD: $head"
} finally {
    Pop-Location
}
