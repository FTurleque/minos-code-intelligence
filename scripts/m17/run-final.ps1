[CmdletBinding()]
param(
    [switch] $ValidateDocker
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
. (Join-Path $RepoRoot 'scripts\windows\MinosWindows.ps1')
$Branch = 'm17-provider-platform'

function Invoke-GitChecked {
    param([Parameter(Mandatory = $true)][string[]] $Arguments)
    & git @Arguments
    if ($LASTEXITCODE -ne 0) { throw "git $($Arguments -join ' ') failed (exit=$LASTEXITCODE)" }
}

function Resolve-CurrentPowerShellHost {
    try {
        $path = [System.Diagnostics.Process]::GetCurrentProcess().MainModule.FileName
        if (-not [string]::IsNullOrWhiteSpace($path) -and (Test-Path -LiteralPath $path -PathType Leaf)) { return $path }
    } catch { }
    if ($env:OS -eq 'Windows_NT' -and -not [string]::IsNullOrWhiteSpace($env:SystemRoot)) {
        $fallback = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
        if (Test-Path -LiteralPath $fallback -PathType Leaf) { return $fallback }
    }
    foreach ($name in @('pwsh.exe','powershell.exe')) {
        $command = Get-Command $name -ErrorAction SilentlyContinue
        if ($command) { return $command.Source }
    }
    throw 'Unable to resolve a PowerShell host.'
}

function Restart-UpdatedRunner {
    param([Parameter(Mandatory = $true)][string] $Head)
    Write-Host "Runner changed after pull; restarting on exact HEAD $Head..." -ForegroundColor Yellow
    $args = @('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $RepoRoot 'scripts\m17\run-final.ps1'))
    if ($ValidateDocker) { $args += '-ValidateDocker' }
    & (Resolve-CurrentPowerShellHost) @args
    if ($LASTEXITCODE -ne 0) { throw "Restarted M17 runner failed (exit=$LASTEXITCODE)." }
}

function Resolve-Python {
    foreach ($name in @('python.exe','python','python3.exe','python3')) {
        $command = Get-Command $name -ErrorAction SilentlyContinue
        if ($command) { return $command.Source }
    }
    throw 'M17 requires Python 3.10+ in PATH for scip-python qualification.'
}

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
    } finally { $ErrorActionPreference = $previous }
    if ($exit -ne 0) { throw "$Failure (exit=$exit)" }
}

function Invoke-MinosJson {
    param([Parameter(Mandatory = $true)][string[]] $Arguments)
    $stderr = Join-Path $script:M17Temp ('stderr-' + [Guid]::NewGuid().ToString('N') + '.log')
    $previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $stdout = @(& $script:JavaExecutable "-Dminos.home=$script:ValidationHome" -jar $script:MinosJar @Arguments 2> $stderr |
            ForEach-Object { $_.ToString() })
        $exit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previous }
    if ($exit -ne 0) {
        if (Test-Path -LiteralPath $stderr) { Get-Content -LiteralPath $stderr | ForEach-Object { Write-Host $_ } }
        throw "MINOS command failed: $($Arguments -join ' ') (exit=$exit)"
    }
    $text = (($stdout -join "`n").Trim())
    if ([string]::IsNullOrWhiteSpace($text)) { throw "MINOS command returned empty JSON: $($Arguments -join ' ')" }
    return ($text | ConvertFrom-Json)
}

function Assert-Structure {
    $required = @(
        'docs\roadmap\M17_EXECUTION.md',
        'docs\adr\0026-discovery-provider-spi-and-explicit-capability-profiles.md',
        'minos-application\src\main\java\com\minos\discovery\spi\ProjectDetector.java',
        'minos-application\src\main\java\com\minos\discovery\spi\BuildSystemDetector.java',
        'minos-application\src\main\java\com\minos\discovery\spi\SourceRootDetector.java',
        'minos-application\src\main\java\com\minos\discovery\spi\LanguageDetector.java',
        'minos-engine\src\main\java\com\minos\orchestration\IndexerProvider.java',
        'minos-engine\src\main\java\com\minos\orchestration\ProviderCapabilityProfile.java',
        'minos-engine\src\main\java\com\minos\orchestration\ProviderConformanceKit.java',
        'minos-runtime-local\src\main\java\com\minos\runtime\CompositeProviderRuntimeManager.java',
        'minos-provider-scip\src\main\java\com\minos\adapter\scip\runtime\ManagedScipPythonRuntimeManager.java',
        'fixtures\kotlin\kotlin-maven-simple\pom.xml',
        'fixtures\python\python-simple\pyproject.toml',
        'fixtures\gradle\gradle-kotlin-multi\settings.gradle.kts',
        'fixtures\typescript\typescript-pnpm-workspace\pnpm-workspace.yaml'
    )
    foreach ($relative in $required) {
        if (-not (Test-Path -LiteralPath (Join-Path $RepoRoot $relative) -PathType Leaf)) {
            throw "Required M17 file is missing: $relative"
        }
    }
    $centralDiscovery = Get-Content -LiteralPath (Join-Path $RepoRoot 'minos-application\src\main\java\com\minos\discovery\ProjectDiscoveryService.java') -Raw
    foreach ($forbidden in @('pom.xml','package.json','build.gradle','pnpm','yarn','.java','.kt','.py')) {
        if ($centralDiscovery.Contains($forbidden)) { throw "M17 discovery orchestrator still contains ecosystem-specific token: $forbidden" }
    }
    $capabilities = Get-Content -LiteralPath (Join-Path $RepoRoot 'minos-engine\src\main\java\com\minos\orchestration\IndexerCapability.java') -Raw
    foreach ($name in @('STABLE_SYMBOL_IDENTITY','UNRESOLVED_REFERENCES','CALL_RELATIONS','POSITION_UTF16','RUNTIME_INSTALLATION')) {
        if (-not $capabilities.Contains($name)) { throw "M17 capability model is missing $name" }
    }
    $mcpTools = Get-Content -LiteralPath (Join-Path $RepoRoot 'minos-mcp\src\main\java\com\minos\mcp\MinosMcpTools.java') -Raw
    if ($mcpTools -notmatch 'TOOL_COUNT\s*=\s*16') { throw 'M17 must preserve the historical 16-tool MCP catalog.' }
    $mainSources = @(Get-ChildItem -LiteralPath $RepoRoot -Directory -Filter 'minos-*' | ForEach-Object {
        Get-ChildItem -LiteralPath (Join-Path $_.FullName 'src\main\java') -Recurse -File -Filter '*.java' -ErrorAction SilentlyContinue
    })
    $testSources = @(Get-ChildItem -LiteralPath $RepoRoot -Directory -Filter 'minos-*' | ForEach-Object {
        Get-ChildItem -LiteralPath (Join-Path $_.FullName 'src\test\java') -Recurse -File -Filter '*.java' -ErrorAction SilentlyContinue
    })
    if ($mainSources.Count -ne 226) { throw "Unexpected M17 production source count: expected=226 actual=$($mainSources.Count)" }
    if ($testSources.Count -ne 105) { throw "Unexpected M17 test source count: expected=105 actual=$($testSources.Count)" }
}

function Assert-ProviderProfile {
    param([Parameter(Mandatory = $true)] $Provider)
    if ($Provider.id -ne 'scip-python' -or $Provider.version -ne '0.6.6') { throw 'Unexpected scip-python profile identity.' }
    $propertyCount = @($Provider.capabilities.PSObject.Properties).Count
    if ($propertyCount -ne 13) { throw "Expected 13 explicit scip-python capabilities, found $propertyCount" }
    if (@($Provider.limitations).Count -eq 0) { throw 'scip-python limitations must be explicit.' }
}

function Qualify-Project {
    param(
        [Parameter(Mandatory = $true)][string] $Name,
        [Parameter(Mandatory = $true)][string] $Fixture,
        [Parameter(Mandatory = $true)][string] $Provider,
        [Parameter(Mandatory = $true)][string] $Symbol
    )
    Invoke-NativeChecked -File $script:JavaExecutable -Arguments @(
        "-Dminos.home=$script:ValidationHome", '-jar', $script:MinosJar,
        'project','add',$Fixture,'--name',$Name
    ) -Failure "$Name registration failed"

    $dry = Invoke-MinosJson @('index',$Name,'--provider',$Provider,'--dry-run','--format','json')
    if ($dry.mode -ne 'FULL' -or -not (@($dry.providers) -contains $Provider)) {
        throw "$Name expected FULL with $Provider; mode=$($dry.mode) providers=$(@($dry.providers) -join ',')"
    }
    $first = Invoke-MinosJson @('index',$Name,'--provider',$Provider,'--format','json')
    if ($first.status -ne 'SUCCEEDED' -or [string]::IsNullOrWhiteSpace([string]$first.activeSnapshotId)) {
        throw "$Name indexing failed: status=$($first.status) snapshot=$($first.activeSnapshotId)"
    }
    $symbols = Invoke-MinosJson @('find-symbol',$Name,$Symbol,'--format','json')
    if ([long]$symbols.count -lt 1) { throw "$Name did not expose symbol $Symbol" }
    $symbolId = [string]$symbols.symbols[0].id
    $usages = Invoke-MinosJson @('find-usages',$Name,$symbolId,'--format','json')
    if ([long]$usages.count -lt 1) { throw "$Name symbol $Symbol has no qualified usage" }
    $second = Invoke-MinosJson @('index',$Name,'--provider',$Provider,'--format','json')
    if ($second.status -ne 'NO_CHANGES' -or $second.plan.mode -ne 'NONE') {
        throw "$Name second run expected NO_CHANGES/NONE; status=$($second.status) mode=$($second.plan.mode)"
    }
    return [pscustomobject]@{
        project = $Name; provider = $Provider; snapshot = [string]$first.activeSnapshotId;
        symbol = $Symbol; symbolId = $symbolId; usages = [long]$usages.count
    }
}

Push-Location $RepoRoot
try {
    Write-Host '=== MINOS M17 - FINAL provider platform exact-head qualification ===' -ForegroundColor Cyan
    $dirty = @(& git status --porcelain)
    if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect worktree.' }
    if ($dirty.Count -gt 0) { throw "M17 final runner requires a clean worktree.`n$($dirty -join "`n")" }
    $initialHead = ((& git rev-parse HEAD) | Select-Object -First 1).Trim()

    Write-Host '[1/8] Fetching/finalizing M17 branch...'
    Invoke-GitChecked @('fetch','origin',$Branch)
    $current = ((& git branch --show-current) | Select-Object -First 1).Trim()
    if ($current -ne $Branch) {
        & git show-ref --verify --quiet "refs/heads/$Branch"
        if ($LASTEXITCODE -eq 0) { Invoke-GitChecked @('switch',$Branch) }
        else { Invoke-GitChecked @('switch','-c',$Branch,'--track',"origin/$Branch") }
    }
    Invoke-GitChecked @('pull','--ff-only','origin',$Branch)
    $head = ((& git rev-parse HEAD) | Select-Object -First 1).Trim()
    if ($head -ne $initialHead) { Restart-UpdatedRunner $head; return }
    Write-Host "HEAD: $head"

    Write-Host '[2/8] Checking SPI/capability/document boundaries...'
    Assert-Structure
    $python = Resolve-Python
    Invoke-NativeChecked -File $python -Arguments @('scripts/docs/product-facts.py','--check') -Failure 'Product facts consistency failed'
    $java = Resolve-MinosJava24
    $script:JavaExecutable = $java.JavaExecutable
    $env:JAVA_HOME = $java.JavaHome
    $env:Path = "$($java.JavaHome)\bin;$env:Path"

    Write-Host '[3/8] Replaying clean verify + complete M14/providers/Windows qualification...'
    $baselineParams = @{ ExpectedHead = $head }
    if ($ValidateDocker) { $baselineParams['ValidateDocker'] = $true }
    & (Join-Path $RepoRoot 'scripts\m15\capture-baseline.ps1') @baselineParams
    $baseline = Get-Content -LiteralPath (Join-Path $RepoRoot 'target\m15-baseline\baseline.json') -Raw | ConvertFrom-Json
    if ($baseline.verifyStatus -ne 'PASS' -or $baseline.m14ReplayStatus -ne 'PASS') {
        throw "M17 inherited qualification failed: verify=$($baseline.verifyStatus) m14=$($baseline.m14ReplayStatus)"
    }
    if ([long]$baseline.junit.tests -ne 277 -or [long]$baseline.junit.failures -ne 0 -or [long]$baseline.junit.errors -ne 0) {
        throw "Unexpected M17 JUnit summary: tests=$($baseline.junit.tests) failures=$($baseline.junit.failures) errors=$($baseline.junit.errors)"
    }
    if ([long]$baseline.mainSourceCount -ne 226 -or [long]$baseline.testSourceCount -ne 105 -or [long]$baseline.reactorModules -ne 13) {
        throw "Unexpected M17 source/reactor counts: main=$($baseline.mainSourceCount) test=$($baseline.testSourceCount) reactor=$($baseline.reactorModules)"
    }
    Invoke-NativeChecked -File $python -Arguments @('scripts/quality/check-jacoco.py') -Failure 'JaCoCo targeted gates failed'

    $validationRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("minos-m14-" + $head.Substring(0,12))
    $script:ValidationHome = Join-Path $validationRoot 'home'
    $script:MinosJar = Join-Path $validationRoot 'installed\lib\minos.jar'
    if (-not (Test-Path -LiteralPath $script:MinosJar -PathType Leaf)) { throw "Installed M14 JAR missing: $script:MinosJar" }
    $script:M17Temp = Join-Path ([System.IO.Path]::GetTempPath()) ("minos-m17-" + $head.Substring(0,12))
    Remove-Item -LiteralPath $script:M17Temp -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $script:M17Temp | Out-Null

    Write-Host '[4/8] Checking provider profiles and optional-runtime doctor semantics...'
    $pythonProfile = Invoke-MinosJson @('providers','scip-python','--format','json')
    Assert-ProviderProfile $pythonProfile
    $doctor = Invoke-MinosJson @('doctor','--format','json')
    if (-not [bool]$doctor.ready) { throw 'Historical doctor baseline became non-ready after adding optional providers.' }
    $pythonRuntimeBefore = @($doctor.providers | Where-Object { $_.id -eq 'scip-python' })[0]
    if ($null -eq $pythonRuntimeBefore -or [bool]$pythonRuntimeBefore.requiredByDefault) {
        throw 'scip-python must be visible and optional before explicit installation.'
    }

    Write-Host '[5/8] Installing and qualifying scip-python end-to-end...'
    $installedPython = Invoke-MinosJson @('tools','install','scip-python','--format','json')
    $pythonStatus = @($installedPython.providers | Where-Object { $_.id -eq 'scip-python' })[0]
    if ($null -eq $pythonStatus -or $pythonStatus.state -ne 'READY') { throw 'scip-python managed installation did not become READY.' }
    $pythonResult = Qualify-Project -Name 'm17-python' -Fixture (Join-Path $RepoRoot 'fixtures\python\python-simple') `
        -Provider 'scip-python' -Symbol 'GreetingService'

    Write-Host '[6/8] Qualifying Kotlin/Maven through scip-java end-to-end...'
    $kotlinResult = Qualify-Project -Name 'm17-kotlin' -Fixture (Join-Path $RepoRoot 'fixtures\kotlin\kotlin-maven-simple') `
        -Provider 'scip-java' -Symbol 'GreetingService'

    Write-Host '[7/8] Rechecking public provider surfaces and fixture cleanliness...'
    $profiles = Invoke-MinosJson @('providers','--format','json')
    if ([long]$profiles.count -ne 3) { throw "Expected 3 M17 provider profiles, found $($profiles.count)" }
    $pythonInspect = Invoke-MinosJson @('project','inspect','m17-python','--format','json')
    if (-not (@($pythonInspect.languages) -contains 'PYTHON')) { throw 'Python project inspection lost PYTHON discovery.' }
    $kotlinInspect = Invoke-MinosJson @('project','inspect','m17-kotlin','--format','json')
    if (-not (@($kotlinInspect.languages) -contains 'KOTLIN') -or -not (@($kotlinInspect.buildSystems) -contains 'MAVEN')) {
        throw 'Kotlin project inspection lost KOTLIN/MAVEN discovery.'
    }
    Invoke-GitChecked @('clean','-fd','--','fixtures/python/python-simple','fixtures/kotlin/kotlin-maven-simple')

    Write-Host '[8/8] Verifying exact HEAD/worktree did not move...'
    $finalHead = ((& git rev-parse HEAD) | Select-Object -First 1).Trim()
    if ($finalHead -ne $head) { throw "HEAD moved during M17 qualification: start=$head final=$finalHead" }
    $finalDirty = @(& git status --porcelain)
    if ($finalDirty.Count -gt 0) { throw "M17 qualification dirtied the worktree.`n$($finalDirty -join "`n")" }

    Write-Host ''
    Write-Host 'M17 FINAL PROVIDER PLATFORM VALIDATION SUCCESS' -ForegroundColor Green
    Write-Host "HEAD=$head tests=$($baseline.junit.tests) main=$($baseline.mainSourceCount) testSources=$($baseline.testSourceCount)"
    Write-Host "pythonProvider=$($pythonResult.provider) pythonSnapshot=$($pythonResult.snapshot) pythonUsages=$($pythonResult.usages)"
    Write-Host "kotlinProvider=$($kotlinResult.provider) kotlinSnapshot=$($kotlinResult.snapshot) kotlinUsages=$($kotlinResult.usages)"
    Write-Host 'providers=3 mcpTools=16 doctorBaseline=READY'
}
finally {
    Pop-Location
}
