[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$Branch = 'm15-s2-maven-multimodule'
$LegacyJavaRoot = Join-Path $RepoRoot 'src\main\java'
$LegacyResourceRoot = Join-Path $RepoRoot 'src\main\resources'

function Invoke-GitChecked {
    param([Parameter(Mandatory = $true)][string[]] $Arguments)
    & git @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "git $($Arguments -join ' ') failed (exit=$LASTEXITCODE)"
    }
}

function Resolve-SourceOwner {
    param([Parameter(Mandatory = $true)][string] $RelativePath)

    $path = $RelativePath.Replace('\', '/')
    switch -Regex ($path) {
        '^com/minos/domain/' { return 'minos-domain' }
        '^com/minos/query/' { return 'minos-engine' }
        '^com/minos/store/CodeKnowledgeStore\.java$' { return 'minos-engine' }
        '^com/minos/discovery/ProjectDiscovery\.java$' { return 'minos-engine' }
        '^com/minos/orchestration/(IndexerCapability|IndexerQualification|IndexerDescriptor|IndexingRequirements|IndexerNegotiationResult|IndexerRegistry|IndexingMode|IndexingRuntimePorts)\.java$' { return 'minos-engine' }
        '^com/minos/runtime/(CommandLocator|IndexerProcessPlan|IndexerProcessPlanFactory|ProcessIndexerExecutor|ProviderRuntimeManager|ProviderRuntimeStatus)\.java$' { return 'minos-runtime-local' }
        '^com/minos/store/' { return 'minos-storage-local' }
        '^com/minos/adapter/scip/' { return 'minos-provider-scip' }
        '^com/minos/git/' { return 'minos-integration-git' }
        '^com/minos/(architecture|context|impact|incremental|output|registry|workspace)/' { return 'minos-application' }
        '^com/minos/discovery/' { return 'minos-application' }
        '^com/minos/orchestration/' { return 'minos-application' }
        '^com/minos/runtime/MinosVersion\.java$' { return 'minos-application' }
        '^com/minos/integration/nexus/NexusExportBridgeMain\.java$' { return 'minos-app' }
        '^com/minos/integration/nexus/' { return 'minos-nexus' }
        '^com/minos/cli/MinosLauncher\.java$' { return 'minos-app' }
        '^com/minos/cli/' { return 'minos-cli' }
        '^com/minos/api/' { return 'minos-api' }
        '^com/minos/mcp/' { return 'minos-mcp' }
        default { throw "No M15-S2 module owns production source '$path'." }
    }
}

function Save-XmlUtf8NoBom {
    param(
        [Parameter(Mandatory = $true)][System.Xml.XmlDocument] $Document,
        [Parameter(Mandatory = $true)][string] $Path
    )

    $settings = [System.Xml.XmlWriterSettings]::new()
    $settings.Encoding = [System.Text.UTF8Encoding]::new($false)
    $settings.Indent = $false
    $settings.NewLineHandling = [System.Xml.NewLineHandling]::None
    $writer = [System.Xml.XmlWriter]::Create($Path, $settings)
    try {
        $Document.Save($writer)
    }
    finally {
        $writer.Dispose()
    }

    # Removing XML nodes while preserving whitespace can leave indentation-only
    # lines behind. Normalize them before git diff --check so the relocation
    # commit stays whitespace-clean on Windows PowerShell 5.1 as well.
    $content = [System.IO.File]::ReadAllText($Path)
    $normalized = [System.Text.RegularExpressions.Regex]::Replace(
        $content,
        '[ \t]+(?=\r?$)',
        '',
        [System.Text.RegularExpressions.RegexOptions]::Multiline
    )
    if ($normalized -ne $content) {
        [System.IO.File]::WriteAllText($Path, $normalized, [System.Text.UTF8Encoding]::new($false))
    }
}

function Remove-ExternalMainSourceBridge {
    param([Parameter(Mandatory = $true)][string] $Module)

    $pomPath = Join-Path $RepoRoot "$Module\pom.xml"
    $document = [System.Xml.XmlDocument]::new()
    $document.PreserveWhitespace = $true
    $document.Load($pomPath)

    $sourceNode = $document.SelectSingleNode('/*[local-name()="project"]/*[local-name()="build"]/*[local-name()="sourceDirectory"]')
    if ($null -eq $sourceNode) {
        throw "$Module has no transitional sourceDirectory to remove."
    }
    if ($sourceNode.InnerText.Trim() -ne '${maven.multiModuleProjectDirectory}/src/main/java') {
        throw "$Module has an unexpected sourceDirectory: $($sourceNode.InnerText.Trim())"
    }
    [void] $sourceNode.ParentNode.RemoveChild($sourceNode)

    $buildNode = $document.SelectSingleNode('/*[local-name()="project"]/*[local-name()="build"]')
    $comments = @($buildNode.ChildNodes | Where-Object {
        $_.NodeType -eq [System.Xml.XmlNodeType]::Comment -and
        ($_.Value -match 'Transitional S2' -or $_.Value -match 'Physical source relocation')
    })
    foreach ($comment in $comments) {
        [void] $buildNode.RemoveChild($comment)
    }

    Save-XmlUtf8NoBom -Document $document -Path $pomPath
}

function Remove-ExternalProviderResourceBridge {
    $pomPath = Join-Path $RepoRoot 'minos-provider-scip\pom.xml'
    $document = [System.Xml.XmlDocument]::new()
    $document.PreserveWhitespace = $true
    $document.Load($pomPath)

    $resourcesNode = $document.SelectSingleNode('/*[local-name()="project"]/*[local-name()="build"]/*[local-name()="resources"]')
    if ($null -eq $resourcesNode) {
        throw 'minos-provider-scip has no transitional resources bridge to remove.'
    }
    $directoryNode = $resourcesNode.SelectSingleNode('./*[local-name()="resource"]/*[local-name()="directory"]')
    if ($null -eq $directoryNode -or $directoryNode.InnerText.Trim() -ne '${maven.multiModuleProjectDirectory}/src/main/resources') {
        throw 'minos-provider-scip resources bridge has an unexpected directory.'
    }
    [void] $resourcesNode.ParentNode.RemoveChild($resourcesNode)
    Save-XmlUtf8NoBom -Document $document -Path $pomPath
}

function Update-BaselineSourceCounting {
    $path = Join-Path $RepoRoot 'scripts\m15\capture-baseline.ps1'
    $utf8 = [System.Text.UTF8Encoding]::new($false)
    $content = [System.IO.File]::ReadAllText($path, $utf8)
    $old = '$mainSourceCount = @(Get-ChildItem -LiteralPath (Join-Path $RepoRoot ''src\main\java'') -Recurse -File -Filter ''*.java'' -ErrorAction SilentlyContinue).Count'
    if (-not $content.Contains($old)) {
        if ($content -match 'Get-ChildItem -LiteralPath \$RepoRoot -Directory -Filter ''minos-\*''') {
            return
        }
        throw 'Unable to locate the historical main-source count in capture-baseline.ps1.'
    }

    $new = @'
$mainSourceCount = @(
        Get-ChildItem -LiteralPath $RepoRoot -Directory -Filter 'minos-*' |
            ForEach-Object {
                Get-ChildItem -LiteralPath (Join-Path $_.FullName 'src\main\java') -Recurse -File -Filter '*.java' -ErrorAction SilentlyContinue
            }
    ).Count
'@
    $new = $new.TrimEnd()

    $content = $content.Replace($old, $new)
    [System.IO.File]::WriteAllText($path, $content, $utf8)
}

function Assert-RelocatedLayout {
    param([Parameter(Mandatory = $true)][hashtable] $ExpectedCounts)

    $remainingSources = @(Get-ChildItem -LiteralPath $LegacyJavaRoot -Recurse -File -ErrorAction SilentlyContinue)
    if ($remainingSources.Count -ne 0) {
        throw "Historical src/main/java still contains $($remainingSources.Count) file(s)."
    }

    foreach ($module in $ExpectedCounts.Keys) {
        $moduleRoot = Join-Path $RepoRoot "$module\src\main\java"
        $count = @(Get-ChildItem -LiteralPath $moduleRoot -Recurse -File -Filter '*.java' -ErrorAction SilentlyContinue).Count
        if ($count -ne [int] $ExpectedCounts[$module]) {
            throw "$module physical source count mismatch: expected=$($ExpectedCounts[$module]) actual=$count"
        }

        [xml] $pom = Get-Content -LiteralPath (Join-Path $RepoRoot "$module\pom.xml") -Raw
        $externalSource = $pom.SelectSingleNode('/*[local-name()="project"]/*[local-name()="build"]/*[local-name()="sourceDirectory"]')
        if ($null -ne $externalSource) {
            throw "$module still declares sourceDirectory=$($externalSource.InnerText.Trim())"
        }
    }

    foreach ($resource in @(
        'minos-provider-scip\src\main\resources\com\minos\adapter\scip\runtime\scip-java-windows-runner.ps1',
        'minos-provider-scip\src\main\resources\com\minos\adapter\scip\runtime\ScipWriter.java'
    )) {
        if (-not (Test-Path -LiteralPath (Join-Path $RepoRoot $resource) -PathType Leaf)) {
            throw "Relocated SCIP runtime resource is missing: $resource"
        }
    }

    $remainingResources = @(Get-ChildItem -LiteralPath $LegacyResourceRoot -Recurse -File -ErrorAction SilentlyContinue)
    if ($remainingResources.Count -ne 0) {
        throw "Historical src/main/resources still contains $($remainingResources.Count) file(s)."
    }
}

Push-Location $RepoRoot
try {
    $branch = ((& git branch --show-current) | Select-Object -First 1).Trim()
    if ($LASTEXITCODE -ne 0 -or $branch -ne $Branch) {
        throw "Relocation must run on branch '$Branch'; current='$branch'."
    }

    $dirty = @(& git status --porcelain)
    if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect Git worktree status.' }
    if ($dirty.Count -gt 0) {
        throw "Relocation requires a clean worktree.`n$($dirty -join "`n")"
    }

    $startingHead = ((& git rev-parse HEAD) | Select-Object -First 1).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($startingHead)) {
        throw 'Unable to resolve relocation starting HEAD.'
    }

    $sources = @(Get-ChildItem -LiteralPath $LegacyJavaRoot -Recurse -File -Filter '*.java' -ErrorAction Stop)
    if ($sources.Count -eq 0) {
        Write-Host 'Production sources are already physically relocated.' -ForegroundColor Yellow
        return
    }
    if ($sources.Count -ne 183) {
        throw "Expected exactly 183 historical production sources before relocation; found $($sources.Count)."
    }

    $expectedCounts = @{
        'minos-domain' = 24
        'minos-engine' = 17
        'minos-runtime-local' = 6
        'minos-storage-local' = 4
        'minos-provider-scip' = 22
        'minos-integration-git' = 1
        'minos-application' = 75
        'minos-nexus' = 2
        'minos-cli' = 24
        'minos-api' = 4
        'minos-mcp' = 2
        'minos-app' = 2
    }
    $actualCounts = @{}
    foreach ($module in $expectedCounts.Keys) { $actualCounts[$module] = 0 }

    $mutationStarted = $false
    try {
        $mutationStarted = $true
        Write-Host 'Relocating 183 production Java sources into Maven module roots...' -ForegroundColor Cyan
        foreach ($file in $sources | Sort-Object FullName) {
            $relative = $file.FullName.Substring($LegacyJavaRoot.Length + 1).Replace('\', '/')
            $owner = Resolve-SourceOwner -RelativePath $relative
            $sourceGitPath = "src/main/java/$relative"
            $targetGitPath = "$owner/src/main/java/$relative"
            $targetDirectory = Split-Path -Parent (Join-Path $RepoRoot $targetGitPath.Replace('/', '\'))
            New-Item -ItemType Directory -Force -Path $targetDirectory | Out-Null
            Invoke-GitChecked @('mv','--',$sourceGitPath,$targetGitPath)
            $actualCounts[$owner] = [int] $actualCounts[$owner] + 1
        }

        foreach ($module in $expectedCounts.Keys) {
            if ([int] $actualCounts[$module] -ne [int] $expectedCounts[$module]) {
                throw "$module ownership mismatch during relocation: expected=$($expectedCounts[$module]) actual=$($actualCounts[$module])"
            }
            Remove-ExternalMainSourceBridge -Module $module
        }

        $resources = @(Get-ChildItem -LiteralPath $LegacyResourceRoot -Recurse -File -ErrorAction SilentlyContinue)
        if ($resources.Count -ne 2) {
            throw "Expected exactly 2 historical production resources before relocation; found $($resources.Count)."
        }
        foreach ($resource in $resources | Sort-Object FullName) {
            $relative = $resource.FullName.Substring($LegacyResourceRoot.Length + 1).Replace('\', '/')
            if ($relative -notlike 'com/minos/adapter/scip/runtime/*') {
                throw "Unexpected production resource outside SCIP ownership: $relative"
            }
            $sourceGitPath = "src/main/resources/$relative"
            $targetGitPath = "minos-provider-scip/src/main/resources/$relative"
            $targetDirectory = Split-Path -Parent (Join-Path $RepoRoot $targetGitPath.Replace('/', '\'))
            New-Item -ItemType Directory -Force -Path $targetDirectory | Out-Null
            Invoke-GitChecked @('mv','--',$sourceGitPath,$targetGitPath)
        }
        Remove-ExternalProviderResourceBridge
        Update-BaselineSourceCounting
        Assert-RelocatedLayout -ExpectedCounts $expectedCounts

        Invoke-GitChecked @('add','-A')
        & git diff --cached --check
        if ($LASTEXITCODE -ne 0) { throw 'Relocation staged diff failed git diff --check.' }

        $staged = @(& git diff --cached --name-only)
        if ($LASTEXITCODE -ne 0 -or $staged.Count -eq 0) {
            throw 'Relocation produced no staged changes.'
        }

        Invoke-GitChecked @('commit','-m','M15-S2 - relocate production sources into Maven modules')
        $head = ((& git rev-parse HEAD) | Select-Object -First 1).Trim()
        if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($head)) {
            throw 'Unable to resolve relocation commit HEAD.'
        }
        Invoke-GitChecked @('push','origin',"HEAD:$Branch")

        Write-Host ''
        Write-Host 'M15-S2 PRODUCTION SOURCE RELOCATION COMMITTED AND PUSHED' -ForegroundColor Green
        Write-Host "HEAD     : $head"
        Write-Host "Sources  : $($sources.Count)"
        Write-Host "Resources: $($resources.Count)"
    }
    catch {
        $failure = $_
        if ($mutationStarted) {
            Write-Warning "Relocation failed; restoring clean starting HEAD $startingHead before returning the error."
            & git reset --hard $startingHead
            if ($LASTEXITCODE -ne 0) {
                throw "Relocation failed: $($failure.Exception.Message). Automatic rollback to $startingHead also failed."
            }
        }
        throw $failure
    }
}
finally {
    Pop-Location
}
