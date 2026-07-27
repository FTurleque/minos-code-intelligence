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
    throw 'M18 requires Python in PATH for product-facts verification.'
}

function Resolve-Gradle {
    $command = Get-Command 'gradle' -ErrorAction SilentlyContinue
    if (-not $command) {
        throw 'M18 plugin qualification requires Gradle 9+ in PATH. CI pins Gradle 9.6.1.'
    }
    return $command.Source
}

function Assert-M18Structure {
    $required = @(
        'docs\roadmap\M18_EXECUTION.md',
        'docs\adr\0027-intellij-external-client-and-versioned-cli-protocol.md',
        'docs\user\intellij-plugin.md',
        'minos-cli\src\main\java\com\minos\cli\IdeCommand.java',
        'minos-cli\src\main\java\com\minos\cli\GitActivityCommand.java',
        'minos-intellij\build.gradle.kts',
        'minos-intellij\src\main\resources\META-INF\plugin.xml',
        'minos-intellij\src\main\java\com\minos\intellij\protocol\MinosCliClient.java',
        'minos-intellij\src\main\java\com\minos\intellij\navigation\MinosNavigation.java',
        'minos-intellij\src\main\java\com\minos\intellij\ui\MinosToolWindowFactory.java',
        '.github\workflows\intellij-plugin.yml'
    )
    foreach ($relative in $required) {
        if (-not (Test-Path -LiteralPath (Join-Path $RepoRoot $relative) -PathType Leaf)) {
            throw "Required M18 file is missing: $relative"
        }
    }

    $pluginBuild = Get-Content -LiteralPath (Join-Path $RepoRoot 'minos-intellij\build.gradle.kts') -Raw
    if ($pluginBuild -match 'implementation\("com\.minos:') {
        throw 'M18 plugin must not depend on com.minos:* Maven artifacts.'
    }
    if ($pluginBuild -notmatch 'org\.jetbrains\.intellij\.platform.*2\.18\.1') {
        throw 'M18 plugin must use the qualified IntelliJ Platform Gradle Plugin 2.18.1.'
    }
    if ($pluginBuild -notmatch 'sourceCompatibility\s*=\s*JavaVersion\.VERSION_21' -or
        $pluginBuild -notmatch 'targetCompatibility\s*=\s*JavaVersion\.VERSION_21' -or
        $pluginBuild -notmatch 'options\.release\s*=\s*21') {
        throw 'M18 plugin must target Java 21 source/target/release compatibility.'
    }

    $protocol = Get-Content -LiteralPath (Join-Path $RepoRoot 'minos-cli\src\main\java\com\minos\cli\IdeCommand.java') -Raw
    if ($protocol -notmatch 'PROTOCOL_ID\s*=\s*"minos-ide"' -or $protocol -notmatch 'PROTOCOL_VERSION\s*=\s*"1"') {
        throw 'M18 IDE protocol id/version is not the qualified v1 contract.'
    }

    $gitActivity = Get-Content -LiteralPath (Join-Path $RepoRoot 'minos-cli\src\main\java\com\minos\cli\GitActivityCommand.java') -Raw
    if ($gitActivity -notmatch 'importanceInference' -or $gitActivity -notmatch 'FACTUAL_ACTIVITY') {
        throw 'M18 Git adapter must explicitly preserve activity != importance.'
    }

    $pluginXml = Get-Content -LiteralPath (Join-Path $RepoRoot 'minos-intellij\src\main\resources\META-INF\plugin.xml') -Raw
    foreach ($action in @('OpenDefinition','FindUsages','Dependents','Implementations','RelatedTests','Impact','Architecture','CopyIdentity')) {
        if ($pluginXml -notmatch "Minos\.$action") { throw "M18 plugin action missing: $action" }
    }
}

Push-Location $RepoRoot
try {
    Write-Host '=== MINOS M18 - FINAL IntelliJ integration exact-head qualification ===' -ForegroundColor Cyan

    $dirty = @(& git status --porcelain)
    if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect worktree.' }
    if ($dirty.Count -gt 0) { throw "M18 final runner requires a clean worktree.`n$($dirty -join "`n")" }

    $head = ((& git rev-parse HEAD) | Select-Object -First 1).Trim()
    if ([string]::IsNullOrWhiteSpace($head)) { throw 'Unable to resolve HEAD.' }
    if (-not [string]::IsNullOrWhiteSpace($ExpectedHead) -and $head -ne $ExpectedHead) {
        throw "M18 exact-head mismatch: expected=$ExpectedHead actual=$head"
    }
    Write-Host "HEAD: $head"

    Write-Host '[1/6] Checking M18 structure and architecture boundaries...'
    Assert-M18Structure

    Write-Host '[2/6] Checking generated product facts...'
    $python = Resolve-Python
    Invoke-NativeChecked -File $python -Arguments @('scripts/docs/product-facts.py','--check') -Failure 'Product facts consistency failed'

    Write-Host '[3/6] Running complete Maven Java 24 verification...'
    $java = Resolve-MinosJava24
    $env:JAVA_HOME = $java.JavaHome
    $env:Path = "$($java.JavaHome)\bin;$env:Path"
    $maven = if ($env:OS -eq 'Windows_NT') { Join-Path $RepoRoot 'mvnw.cmd' } else { Join-Path $RepoRoot 'mvnw' }
    Invoke-NativeChecked -File $maven -Arguments @('-B','-ntp','clean','verify') -Failure 'Maven clean verify failed'
    Invoke-NativeChecked -File $python -Arguments @('scripts/quality/check-jacoco.py') -Failure 'JaCoCo gate failed'

    Write-Host '[4/6] Verifying IntelliJ plugin Java 21 target with Gradle 9+...'
    $gradle = Resolve-Gradle
    Invoke-NativeChecked -File $gradle -Arguments @(
        '-p','minos-intellij','--no-daemon',
        'test','buildPlugin','verifyPluginProjectConfiguration','verifyPluginStructure','verifyPlugin'
    ) -Failure 'IntelliJ plugin qualification failed'

    $distribution = @(Get-ChildItem -LiteralPath (Join-Path $RepoRoot 'minos-intellij\build\distributions') -Filter '*.zip' -File -ErrorAction SilentlyContinue)
    if ($distribution.Count -lt 1) { throw 'M18 plugin ZIP was not produced.' }
    Write-Host "Plugin ZIP: $($distribution[0].FullName)"

    Write-Host '[5/6] Rechecking exact HEAD and clean worktree...'
    $finalHead = ((& git rev-parse HEAD) | Select-Object -First 1).Trim()
    if ($finalHead -ne $head) { throw "HEAD changed during M18 qualification: start=$head end=$finalHead" }
    $finalDirty = @(& git status --porcelain)
    if ($LASTEXITCODE -ne 0) { throw 'Unable to re-inspect worktree.' }
    $trackedDirty = @($finalDirty | Where-Object { $_ -notmatch '^\?\? minos-intellij[/\\](build|\.intellijPlatform)[/\\]' })
    if ($trackedDirty.Count -gt 0) { throw "Tracked worktree changed during M18 qualification.`n$($trackedDirty -join "`n")" }

    Write-Host '[6/6] Qualification complete.'
    Write-Host 'M18 FINAL INTELLIJ INTEGRATION VALIDATION SUCCESS' -ForegroundColor Green
    Write-Host "Validated HEAD: $head"
} finally {
    Pop-Location
}
