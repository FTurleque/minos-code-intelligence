[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$Branch = 'm15-s2-maven-multimodule'
$LegacyTestRoot = Join-Path $RepoRoot 'src\test\java'
$Utf8 = [System.Text.UTF8Encoding]::new($false)
$Modules = @(
    'minos-domain','minos-engine','minos-runtime-local','minos-storage-local',
    'minos-provider-scip','minos-integration-git','minos-application','minos-nexus',
    'minos-cli','minos-api','minos-mcp','minos-app'
)

function Invoke-GitChecked {
    param([Parameter(Mandatory = $true)][string[]] $Arguments)
    & git @Arguments
    if ($LASTEXITCODE -ne 0) { throw "git $($Arguments -join ' ') failed (exit=$LASTEXITCODE)" }
}

function Invoke-NativeChecked {
    param(
        [Parameter(Mandatory = $true)][string] $File,
        [Parameter(Mandatory = $true)][string[]] $Arguments,
        [Parameter(Mandatory = $true)][string] $Failure
    )
    & $File @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Failure (exit=$LASTEXITCODE)" }
}

function Save-XmlUtf8NoBom {
    param(
        [Parameter(Mandatory = $true)][System.Xml.XmlDocument] $Document,
        [Parameter(Mandatory = $true)][string] $Path
    )

    $settings = [System.Xml.XmlWriterSettings]::new()
    $settings.Encoding = $Utf8
    $settings.Indent = $false
    $settings.NewLineHandling = [System.Xml.NewLineHandling]::None
    $writer = [System.Xml.XmlWriter]::Create($Path, $settings)
    try { $Document.Save($writer) } finally { $writer.Dispose() }

    $content = [System.IO.File]::ReadAllText($Path, $Utf8)
    $normalized = [System.Text.RegularExpressions.Regex]::Replace(
        $content,
        '[ \t]+(?=\r?$)',
        '',
        [System.Text.RegularExpressions.RegexOptions]::Multiline
    )
    if ($normalized -ne $content) {
        [System.IO.File]::WriteAllText($Path, $normalized, $Utf8)
    }
}

function Ensure-ParentTestSupport {
    $path = Join-Path $RepoRoot 'pom.xml'
    $content = [System.IO.File]::ReadAllText($path, $Utf8)

    if ($content -notmatch '<artifactId>junit-jupiter</artifactId>') {
        $anchor = "    </dependencyManagement>`r`n`r`n    <build>"
        if (-not $content.Contains($anchor)) {
            $anchor = "    </dependencyManagement>`n`n    <build>"
        }
        if (-not $content.Contains($anchor)) {
            throw 'Unable to locate parent dependencyManagement/build boundary.'
        }
        $newline = if ($anchor.Contains("`r`n")) { "`r`n" } else { "`n" }
        $replacement = @(
            '    </dependencyManagement>',
            '',
            '    <!-- Shared test contract for module-local S2 tests. -->',
            '    <dependencies>',
            '        <dependency>',
            '            <groupId>org.junit.jupiter</groupId>',
            '            <artifactId>junit-jupiter</artifactId>',
            '            <version>${junit.version}</version>',
            '            <scope>test</scope>',
            '        </dependency>',
            '    </dependencies>',
            '',
            '    <build>'
        ) -join $newline
        $content = $content.Replace($anchor, $replacement)
    }

    if ($content -notmatch '<workingDirectory>\$\{maven\.multiModuleProjectDirectory\}</workingDirectory>') {
        $old = '                    <useModulePath>false</useModulePath>'
        $new = "                    <useModulePath>false</useModulePath>`r`n                    <workingDirectory>`${maven.multiModuleProjectDirectory}</workingDirectory>"
        if ($content -notmatch "`r`n") {
            $new = "                    <useModulePath>false</useModulePath>`n                    <workingDirectory>`${maven.multiModuleProjectDirectory}</workingDirectory>"
        }
        if (-not $content.Contains($old)) { throw 'Unable to locate parent Surefire useModulePath configuration.' }
        $content = $content.Replace($old, $new)
    }

    [System.IO.File]::WriteAllText($path, $content, $Utf8)
}

function Remove-AppExternalTestBridge {
    $path = Join-Path $RepoRoot 'minos-app\pom.xml'
    $document = [System.Xml.XmlDocument]::new()
    $document.PreserveWhitespace = $true
    $document.Load($path)

    $testSource = $document.SelectSingleNode('/*[local-name()="project"]/*[local-name()="build"]/*[local-name()="testSourceDirectory"]')
    if ($null -ne $testSource) {
        if ($testSource.InnerText.Trim() -ne '${maven.multiModuleProjectDirectory}/src/test/java') {
            throw "minos-app has unexpected testSourceDirectory=$($testSource.InnerText.Trim())"
        }
        [void] $testSource.ParentNode.RemoveChild($testSource)
    }

    $testResources = $document.SelectSingleNode('/*[local-name()="project"]/*[local-name()="build"]/*[local-name()="testResources"]')
    if ($null -ne $testResources) {
        $directories = @($testResources.SelectNodes('./*[local-name()="testResource"]/*[local-name()="directory"]') |
            ForEach-Object { $_.InnerText.Trim() })
        if ($directories.Count -ne 1 -or $directories[0] -ne '${maven.multiModuleProjectDirectory}/src/test/resources') {
            throw "minos-app has unexpected testResources=[$($directories -join ', ')]"
        }
        [void] $testResources.ParentNode.RemoveChild($testResources)
    }

    Save-XmlUtf8NoBom -Document $document -Path $path
}

function Update-BaselineTestCounting {
    $path = Join-Path $RepoRoot 'scripts\m15\capture-baseline.ps1'
    $content = [System.IO.File]::ReadAllText($path, $Utf8)
    $old = '$testSourceCount = @(Get-ChildItem -LiteralPath (Join-Path $RepoRoot ''src\test\java'') -Recurse -File -Filter ''*.java'' -ErrorAction SilentlyContinue).Count'
    if (-not $content.Contains($old)) {
        if ($content -match 'testSourceCount = @\([\s\S]*?src\\test\\java') { return }
        throw 'Unable to locate historical test-source count in capture-baseline.ps1.'
    }

    $new = @'
$testSourceCount = @(
        Get-ChildItem -LiteralPath $RepoRoot -Directory -Filter 'minos-*' |
            ForEach-Object {
                Get-ChildItem -LiteralPath (Join-Path $_.FullName 'src\test\java') -Recurse -File -Filter '*.java' -ErrorAction SilentlyContinue
            }
    ).Count
'@
    $content = $content.Replace($old, $new.TrimEnd())
    [System.IO.File]::WriteAllText($path, $content, $Utf8)
}

function Resolve-TestOwner {
    param(
        [Parameter(Mandatory = $true)][string] $RelativePath,
        [Parameter(Mandatory = $true)][string] $Content
    )

    $path = $RelativePath.Replace('\', '/')

    # Composition-root and cross-boundary tests deliberately stay in minos-app.
    if ($Content -match '\bMinosLauncher\b' -or $Content -match '\bNexusExportBridgeMain\b') { return 'minos-app' }
    if ($path -match '^com/minos/packaging/' -or $path -match '^com/minos/query/') { return 'minos-app' }

    switch -Regex ($path) {
        '^com/minos/domain/' { return 'minos-domain' }
        '^com/minos/orchestration/IndexerRegistryTest\.java$' { return 'minos-engine' }
        '^com/minos/runtime/MinosVersion' { return 'minos-application' }
        '^com/minos/runtime/' {
            if ($Content -match 'import com\.minos\.(adapter\.scip|architecture|cli|api|mcp|integration\.nexus|git)\.') { return 'minos-app' }
            return 'minos-runtime-local'
        }
        '^com/minos/store/' {
            if ($Content -match 'import com\.minos\.(adapter\.scip|architecture|cli|api|mcp|integration\.nexus|git)\.') { return 'minos-app' }
            return 'minos-storage-local'
        }
        '^com/minos/adapter/scip/' {
            if ($Content -match 'import com\.minos\.(architecture|cli|api|mcp|integration\.nexus|git)\.') { return 'minos-app' }
            return 'minos-provider-scip'
        }
        '^com/minos/git/' {
            if ($Content -match 'import com\.minos\.(adapter\.scip|architecture|cli|api|mcp|integration\.nexus|store|runtime)\.') { return 'minos-app' }
            return 'minos-integration-git'
        }
        '^com/minos/(architecture|context|impact|incremental|output|registry|workspace|discovery)/' {
            if ($Content -match 'import com\.minos\.(adapter\.scip|cli|api|mcp|integration\.nexus|git|runtime)\.') { return 'minos-app' }
            return 'minos-application'
        }
        '^com/minos/orchestration/' {
            if ($Content -match 'import com\.minos\.(adapter\.scip|cli|api|mcp|integration\.nexus|git|runtime)\.') { return 'minos-app' }
            return 'minos-application'
        }
        '^com/minos/integration/nexus/' {
            if ($Content -match 'import com\.minos\.(api|cli|mcp|adapter\.scip|git)\.') { return 'minos-app' }
            return 'minos-nexus'
        }
        '^com/minos/cli/' {
            if ($Content -match 'import com\.minos\.(api|mcp)\.') { return 'minos-app' }
            return 'minos-cli'
        }
        '^com/minos/api/' {
            if ($Content -match 'import com\.minos\.mcp\.') { return 'minos-app' }
            return 'minos-api'
        }
        '^com/minos/mcp/' { return 'minos-mcp' }
        default { return 'minos-app' }
    }
}

function Get-JUnitSummary {
    $reports = @(Get-ChildItem -LiteralPath $RepoRoot -Recurse -File -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match '[\\/]target[\\/]surefire-reports[\\/]TEST-.*\.xml$' })
    $tests = 0L; $failures = 0L; $errors = 0L; $skipped = 0L
    foreach ($report in $reports) {
        [xml] $xml = Get-Content -LiteralPath $report.FullName -Raw
        if ($null -eq $xml.testsuite) { continue }
        $tests += [long] $xml.testsuite.tests
        $failures += [long] $xml.testsuite.failures
        $errors += [long] $xml.testsuite.errors
        $skipped += [long] $xml.testsuite.skipped
    }
    [pscustomobject]@{ tests=$tests; failures=$failures; errors=$errors; skipped=$skipped; reports=$reports.Count }
}

function Assert-FinalTestLayout {
    $legacy = @(Get-ChildItem -LiteralPath $LegacyTestRoot -Recurse -File -Filter '*.java' -ErrorAction SilentlyContinue)
    if ($legacy.Count -ne 0) { throw "Historical src/test/java still contains $($legacy.Count) Java source(s)." }

    $all = @()
    foreach ($module in $Modules) {
        $all += @(Get-ChildItem -LiteralPath (Join-Path $RepoRoot "$module\src\test\java") -Recurse -File -Filter '*.java' -ErrorAction SilentlyContinue)
        [xml] $pom = Get-Content -LiteralPath (Join-Path $RepoRoot "$module\pom.xml") -Raw
        $external = $pom.SelectSingleNode('/*[local-name()="project"]/*[local-name()="build"]/*[local-name()="testSourceDirectory"]')
        if ($null -ne $external -and $external.InnerText -match 'maven.multiModuleProjectDirectory') {
            throw "$module still declares external testSourceDirectory=$($external.InnerText.Trim())"
        }
    }
    if ($all.Count -ne 92) { throw "Expected 92 physically relocated test sources; found $($all.Count)." }

    foreach ($required in @(
        'minos-app\src\test\java\com\minos\cli\MinosLauncherTest.java',
        'minos-app\src\test\java\com\minos\packaging\ShadedJarSmokeIT.java',
        'minos-app\src\test\java\com\minos\mcp\MinosMcpServerIntegrationTest.java',
        'minos-app\src\test\java\com\minos\integration\nexus\NexusExportIntegrationTest.java',
        'minos-app\src\test\java\com\minos\query\SymbolQueryServiceTest.java',
        'minos-provider-scip\src\test\java\com\minos\adapter\scip\ScipIndexReaderTest.java',
        'minos-api\src\test\java\com\minos\api\MinosApiContractTest.java'
    )) {
        if (-not (Test-Path -LiteralPath (Join-Path $RepoRoot $required) -PathType Leaf)) {
            throw "Expected relocated test is missing: $required"
        }
    }
}

Push-Location $RepoRoot
try {
    $branch = ((& git branch --show-current) | Select-Object -First 1).Trim()
    if ($LASTEXITCODE -ne 0 -or $branch -ne $Branch) { throw "Test relocation must run on branch '$Branch'; current='$branch'." }

    $dirty = @(& git status --porcelain)
    if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect Git worktree status.' }
    if ($dirty.Count -gt 0) { throw "Test relocation requires a clean worktree.`n$($dirty -join "`n")" }

    $startingHead = ((& git rev-parse HEAD) | Select-Object -First 1).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($startingHead)) { throw 'Unable to resolve test relocation starting HEAD.' }

    $tests = @(Get-ChildItem -LiteralPath $LegacyTestRoot -Recurse -File -Filter '*.java' -ErrorAction SilentlyContinue)
    if ($tests.Count -eq 0) {
        Assert-FinalTestLayout
        Write-Host 'Tests are already physically relocated and the final test layout is valid.' -ForegroundColor Green
        return
    }
    if ($tests.Count -ne 92) { throw "Expected exactly 92 historical test sources; found $($tests.Count)." }

    foreach ($module in $Modules) {
        $existing = @(Get-ChildItem -LiteralPath (Join-Path $RepoRoot "$module\src\test\java") -Recurse -File -Filter '*.java' -ErrorAction SilentlyContinue)
        if ($existing.Count -gt 0) { throw "$module already contains $($existing.Count) test source(s) before one-time relocation." }
    }

    $owners = @{}
    foreach ($module in $Modules) { $owners[$module] = 0 }
    $mutationStarted = $false
    try {
        $mutationStarted = $true
        Write-Host 'Relocating 92 test sources into Maven module roots...' -ForegroundColor Cyan
        foreach ($file in $tests | Sort-Object FullName) {
            $relative = $file.FullName.Substring($LegacyTestRoot.Length + 1).Replace('\', '/')
            $content = [System.IO.File]::ReadAllText($file.FullName, $Utf8)
            $owner = Resolve-TestOwner -RelativePath $relative -Content $content
            $sourceGitPath = "src/test/java/$relative"
            $targetGitPath = "$owner/src/test/java/$relative"
            $targetDirectory = Split-Path -Parent (Join-Path $RepoRoot $targetGitPath.Replace('/', '\'))
            New-Item -ItemType Directory -Force -Path $targetDirectory | Out-Null
            Invoke-GitChecked @('mv','--',$sourceGitPath,$targetGitPath)
            $owners[$owner] = [int] $owners[$owner] + 1
        }

        Ensure-ParentTestSupport
        Remove-AppExternalTestBridge
        Update-BaselineTestCounting
        Assert-FinalTestLayout

        Invoke-GitChecked @('add','-A')
        & git diff --cached --check
        if ($LASTEXITCODE -ne 0) { throw 'Test relocation staged diff failed git diff --check.' }

        . (Join-Path $RepoRoot 'scripts\windows\MinosWindows.ps1')
        $java = Resolve-MinosJava24
        $env:JAVA_HOME = $java.JavaHome
        $env:Path = "$($java.JavaHome)\bin;$env:Path"

        Write-Host 'Validating relocated tests before commit...' -ForegroundColor Cyan
        Invoke-NativeChecked '.\mvnw.cmd' @('clean','test') 'Relocated module tests failed Maven clean test'
        $summary = Get-JUnitSummary
        if ($summary.tests -ne 237 -or $summary.failures -ne 0 -or $summary.errors -ne 0 -or $summary.skipped -ne 0) {
            throw "Relocated Surefire summary mismatch: tests=$($summary.tests) failures=$($summary.failures) errors=$($summary.errors) skipped=$($summary.skipped) reports=$($summary.reports)"
        }

        $staged = @(& git diff --cached --name-only)
        if ($LASTEXITCODE -ne 0 -or $staged.Count -eq 0) { throw 'Test relocation produced no staged changes.' }

        Invoke-GitChecked @('commit','-m','M15-S2 - relocate tests into Maven modules')
        $head = ((& git rev-parse HEAD) | Select-Object -First 1).Trim()
        if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($head)) { throw 'Unable to resolve test relocation commit HEAD.' }
        Invoke-GitChecked @('push','origin',"HEAD:$Branch")

        Write-Host ''
        Write-Host 'M15-S2 TEST SOURCE RELOCATION COMMITTED AND PUSHED' -ForegroundColor Green
        Write-Host "HEAD  : $head"
        Write-Host "Tests : $($summary.tests) PASS"
        foreach ($module in $Modules) {
            if ([int] $owners[$module] -gt 0) { Write-Host ("{0,-24}: {1}" -f $module, $owners[$module]) }
        }
    }
    catch {
        $failure = $_
        if ($mutationStarted) {
            Write-Warning "Test relocation failed; restoring clean starting HEAD $startingHead."
            & git reset --hard $startingHead
            if ($LASTEXITCODE -ne 0) {
                throw "Test relocation failed: $($failure.Exception.Message). Automatic rollback to $startingHead also failed."
            }
        }
        throw $failure
    }
}
finally {
    Pop-Location
}
