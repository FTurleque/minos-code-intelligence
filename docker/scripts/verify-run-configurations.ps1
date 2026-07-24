$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0

$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $scriptDirectory '..\..'))
$runDirectory = Join-Path $projectRoot '.run'
$composeTemplate = Join-Path $projectRoot 'docker\compose.mcp.prod.yaml'
$dockerfile = Join-Path $projectRoot 'docker\Dockerfile.mcp'
$prodScript = Join-Path $projectRoot 'docker\scripts\prod-mcp.ps1'
$devScript = Join-Path $projectRoot 'scripts\intellij\run-minos.ps1'
$windowsFunctions = Join-Path $projectRoot 'scripts\windows\MinosWindows.ps1'
$smokeSource = Join-Path $projectRoot 'docker\scripts\MinosDockerMcpSmoke.java'

$requiredConfigurations = @(
    '[MINOS Dev] MCP',
    '[MINOS Prod] Install',
    '[MINOS Prod] Start',
    '[MINOS Prod] MCP',
    '[MINOS Prod] Status',
    '[MINOS Prod] Stop',
    '[MINOS Prod] Validate',
    '[MINOS] Verify launch configs'
)

function Assert-Condition {
    param(
        [Parameter(Mandatory = $true)][bool]$Condition,
        [Parameter(Mandatory = $true)][string]$Message
    )

    if (-not $Condition) {
        throw $Message
    }
}

function Read-Utf8Text {
    param([Parameter(Mandatory = $true)][string]$Path)

    return [System.IO.File]::ReadAllText($Path, [System.Text.Encoding]::UTF8)
}

function Assert-PowerShellSyntax {
    param([Parameter(Mandatory = $true)][string]$Path)

    try {
        [scriptblock]::Create((Read-Utf8Text -Path $Path)) | Out-Null
    } catch {
        throw "Erreur de syntaxe PowerShell dans $Path : $($_.Exception.Message)"
    }
}

$runFiles = @(Get-ChildItem -LiteralPath $runDirectory -Filter '*.run.xml' | Sort-Object Name)
Assert-Condition -Condition ($runFiles.Count -eq $requiredConfigurations.Count) `
    -Message "Configurations attendues : $($requiredConfigurations.Count), detectees : $($runFiles.Count)."

$detected = @{}
$allRunContent = ''
foreach ($runFile in $runFiles) {
    $content = Read-Utf8Text -Path $runFile.FullName
    $allRunContent += $content + "`n"
    $document = New-Object System.Xml.XmlDocument
    $document.LoadXml($content)
    $configuration = $document.SelectSingleNode('/component/configuration')
    Assert-Condition -Condition ($null -ne $configuration) -Message "Configuration XML absente : $($runFile.Name)"
    $name = [string]$configuration.GetAttribute('name')
    Assert-Condition -Condition (-not [string]::IsNullOrWhiteSpace($name)) -Message "Nom absent : $($runFile.Name)"
    Assert-Condition -Condition ($runFile.Name -eq "$name.run.xml") -Message "Nom de fichier incoherent : $($runFile.Name)"
    Assert-Condition -Condition (-not $detected.ContainsKey($name)) -Message "Configuration dupliquee : $name"
    $detected[$name] = $document

    $workingDirectory = $document.SelectSingleNode('/component/configuration/option[@name="WORKING_DIRECTORY"]')
    Assert-Condition -Condition ($null -ne $workingDirectory -and $workingDirectory.GetAttribute('value') -eq '$PROJECT_DIR$') `
        -Message "$name doit utiliser PROJECT_DIR."
    $environmentNodes = @($document.SelectNodes('/component/configuration/envs/env'))
    Assert-Condition -Condition ($environmentNodes.Count -eq 0) `
        -Message "$name ne doit injecter aucune variable transformable en commande export."
    $interpreter = $document.SelectSingleNode('/component/configuration/option[@name="INTERPRETER_PATH"]')
    Assert-Condition -Condition ($null -ne $interpreter -and $interpreter.GetAttribute('value') -eq 'powershell.exe') `
        -Message "$name doit utiliser Windows PowerShell explicitement."
}

foreach ($required in $requiredConfigurations) {
    Assert-Condition -Condition ($detected.ContainsKey($required)) -Message "Configuration manquante : $required"
}
Assert-Condition -Condition (-not $allRunContent.Contains('export ')) -Message 'Les configurations .run ne doivent contenir aucune commande export.'
Assert-Condition -Condition (-not ([regex]::IsMatch($allRunContent, '\b[A-Za-z]:[\\/]'))) `
    -Message 'Les configurations .run ne doivent contenir aucun chemin absolu specifique au poste.'

$expectedActions = @{
    '[MINOS Prod] Install' = 'Install'
    '[MINOS Prod] Start' = 'Start'
    '[MINOS Prod] MCP' = 'Attach'
    '[MINOS Prod] Status' = 'Status'
    '[MINOS Prod] Stop' = 'Stop'
    '[MINOS Prod] Validate' = 'Validate'
}
foreach ($entry in $expectedActions.GetEnumerator()) {
    $scriptText = $detected[$entry.Key].SelectSingleNode('/component/configuration/option[@name="SCRIPT_TEXT"]').GetAttribute('value')
    Assert-Condition -Condition ($scriptText.Contains('.\docker\scripts\prod-mcp.ps1')) `
        -Message "$($entry.Key) doit appeler prod-mcp.ps1."
    Assert-Condition -Condition ($scriptText.Contains("-Action $($entry.Value)")) `
        -Message "$($entry.Key) doit utiliser l'action $($entry.Value)."
}

foreach ($script in @($prodScript, $devScript, $windowsFunctions, $MyInvocation.MyCommand.Path)) {
    Assert-PowerShellSyntax -Path $script
}

$dockerfileContent = Read-Utf8Text -Path $dockerfile
$composeContent = Read-Utf8Text -Path $composeTemplate
$smokeContent = Read-Utf8Text -Path $smokeSource
Assert-Condition -Condition ($dockerfileContent.Contains('FROM eclipse-temurin:24-jre')) `
    -Message "L'image MCP doit utiliser le runtime officiel Eclipse Temurin Java 24."
Assert-Condition -Condition ($dockerfileContent.Contains('USER 10001:10001')) `
    -Message "L'image MCP doit s'executer sans privileges root."
Assert-Condition -Condition ($composeContent.Contains('network_mode: none')) `
    -Message 'Le runtime MCP doit etre isole du reseau.'
Assert-Condition -Condition ($composeContent.Contains('read_only: true')) `
    -Message 'Le systeme de fichiers du conteneur et le montage projets doivent etre en lecture seule.'
Assert-Condition -Condition ($composeContent.Contains('no-new-privileges:true')) `
    -Message 'Le runtime MCP doit activer no-new-privileges.'
Assert-Condition -Condition ($composeContent.Contains('restart: unless-stopped')) `
    -Message 'Le conteneur PROD doit redemarrer automatiquement.'
Assert-Condition -Condition ($composeContent.Contains('- infinity')) `
    -Message 'Le conteneur PROD doit rester disponible entre deux sessions STDIO.'
Assert-Condition -Condition ($smokeContent.Contains('"--entrypoint", "java"')) `
    -Message 'Le smoke test doit lancer explicitement le serveur MCP dans le conteneur.'
Assert-Condition -Condition ($smokeContent.Contains('MINOS Docker MCP smoke SUCCESS')) `
    -Message 'Le smoke test Docker MCP reel est absent.'
$prodContent = Read-Utf8Text -Path $prodScript
Assert-Condition -Condition ($prodContent.Contains('& docker exec -i $containerName java')) `
    -Message 'La configuration MCP PROD doit ouvrir une session STDIO avec docker exec -i.'
Assert-Condition -Condition (-not $prodContent.Contains('docker attach')) `
    -Message 'La configuration MCP PROD ne doit pas dependre de docker attach.'

$temporaryRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('minos-compose-verify-' + [guid]::NewGuid().ToString('N'))
try {
    [System.IO.Directory]::CreateDirectory((Join-Path $temporaryRoot 'data')) | Out-Null
    [System.IO.Directory]::CreateDirectory((Join-Path $temporaryRoot 'projects')) | Out-Null
    $environmentFile = Join-Path $temporaryRoot '.env'
    $environmentContent = @"
MINOS_COMPOSE_PROJECT=minos-mcp-verify
MINOS_CONTAINER_NAME=minos-mcp-verify
MINOS_IMAGE=minos-code-intelligence:verify
MINOS_DATA_DIR=$($temporaryRoot.Replace('\', '/'))/data
MINOS_PROJECTS_DIR=$($temporaryRoot.Replace('\', '/'))/projects
MINOS_VERSION=verify
MINOS_GIT_COMMIT=verify
"@
    [System.IO.File]::WriteAllText($environmentFile, $environmentContent, (New-Object System.Text.UTF8Encoding -ArgumentList $false))
    & docker compose --project-directory $temporaryRoot --env-file $environmentFile -f $composeTemplate config --quiet
    if ($LASTEXITCODE -ne 0) {
        throw "Validation Docker Compose echouee (code $LASTEXITCODE)."
    }
} finally {
    if (Test-Path -LiteralPath $temporaryRoot) {
        Remove-Item -LiteralPath $temporaryRoot -Recurse -Force
    }
}

Write-Host "MINOS launch configurations SUCCESS: $($requiredConfigurations.Count) configurations, PowerShell syntax OK, Docker Compose OK."
