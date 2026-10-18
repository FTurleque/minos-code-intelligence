# Design

## Context

Défaut relevé le 2026-10-16 pendant la clôture des sprints 3, 5 et 6 de l'audit du 10 octobre 2026 (« à instruire à part »). Diagnostic fait le 2026-10-17 sur le poste Windows 10 (19045) de l'auteur, compte standard non élevé. HEAD analysé : `6dd24bd0` (`develop`). Prémisses du lot 2 revalidées au HEAD `4922f4ac` le 2026-10-18 : `ManagedScipProviderRuntimeManager.java:256` déclare toujours `READY` sans exécution qualifiée, le runner garde `Install-CommandShims` (`:72`, appelé en `:378`) et `Push-Location` (`:257`, `:412`), `WindowsNonElevatedIndexingTest` n'écrit toujours que par `WriteAllText` (`:92`), `STATUS.md:47` porte toujours la limite. Les numéros de ligne cités plus bas datent de `6dd24bd0` ; depuis, `develop` a déplacé du code de `WindowsAppContainerWorkerSandboxBackend` vers `WindowsAclGrants` et durci `PublicErrorMessages` (séquences ANSI) sans changer ces conclusions.

## Reproduction

- Projet d'essai : `pom.xml` sans dépendance, `maven.compiler.release` 21, une classe `demo.Greeter`.
- MINOS 1.2.0 installé (`%LOCALAPPDATA%\Programs\MINOS`, non modifié), `MINOS_HOME` jetable, `tools install scip-java` → `scip-java 0.13.1 READY`, `JAVA_HOME` = Temurin 25.0.4.1 sous `C:\Users\<compte>\.jdks`.
- `minos project add … ; minos index java-mini` → `error: index failed: IllegalStateException`, code 1. Même sortie qu'avec la distribution 1.3.0 de la CI (relevé du 2026-10-16).

Le message réel est dans l'enregistrement du run, `MINOS_HOME/index-state/runs/<projet>/<run>.properties`, clé `message`. Les couches successives, chacune levée pour atteindre la suivante :

| # | Condition de l'essai | Cause enregistrée |
|---|---|---|
| 1 | `PATH` du poste (sans `System32\WindowsPowerShell\v1.0`) | `Provider runtime is not compatible with the non-elevated Windows sandbox: cannot grant AppContainer read access to C:\Program Files\PowerShell\7\pwsh.exe …` |
| 2 | PowerShell 5.1 mis en tête du `PATH` | `provider exited with code 1; see <chemin absolu>\provider.stderr.log` ; journal : `Install-CommandShims : Lecteur introuvable. Il n'existe aucun lecteur nommé « Microsoft.PowerShell.Core\FileSystem »` |
| 3 | copie jetable du runner : emplacement courant posé par un `PSDrive` | `csc.exe` : `error CS1619: Impossible de créer le fichier temporaire ''` (avec ou sans `TEMP` redirigé) |
| 4 | shims précompilés hors bac à sable, classpath local (comme en 1.3.0) | `jar` : `AccessDeniedException: <JAVA_HOME>\conf\security\java.security` depuis `WindowsPath.toRealPath` (JDK 25) |
| 5 | idem avec `JAVA_HOME` = JDK 21 | `jar` : `WinNTFileSystem.getNameMax0` puis `NullPointerException` dans `File.createTempFile` |

Sondes de la couche 2, faites dans le vrai conteneur par une copie instrumentée du runner : `Get-Location` rend `C:\` alors que le répertoire courant du processus est l'espace de travail ; tout appel natif (`cmd.exe`, `whoami.exe`, `Start-Process`) échoue en `DriveNotFoundException` ; `Set-Location` vers une racine accordée échoue en `UnauthorizedAccessException` ; un `PSDrive` enraciné sur la racine accordée, ou `Set-Location $env:SystemRoot`, rétablit les appels natifs ; `System.Diagnostics.Process` fonctionne. Le `PSModulePath` hérité de PowerShell 7 n'y est pour rien (même résultat avec celui de Windows PowerShell). Hors conteneur, le même runner passe `Install-CommandShims`.

ACL relues par `icacls` : `C:\` n'accorde rien à `TOUS LES PACKAGES D'APPLICATION` (seule une capacité `S-1-15-3-65536-…` y a `(S,RD,X,RA)`) ; `C:\Users\<compte>` et `AppData\Local\Temp` non plus ; `C:\Program Files` leur accorde `(RX)`.

## État vérifié au HEAD

### Erreur publique opaque — confirmé

- `minos-cli/src/main/java/com/minos/cli/operations/LocalAutonomousIndexOperations.java:189-191` : `new IllegalStateException("indexing run " + run.id() + outcome + run.message().orElse(…))` — le message du run est collé tel quel.
- Ce message vient de `minos-engine/src/main/java/com/minos/orchestration/IndexingRunExecutor.java:837` (`<classe>: <message>` de l'exception du provider).
- `minos-cli/src/main/java/com/minos/cli/support/CliCommandSupport.java:167-173` : `failureMessage` passe toute la ligne à `PublicErrorMessages.sanitize(message, getSimpleName())`.
- `minos-engine/src/main/java/com/minos/diagnostics/PublicErrorMessages.java:45-49` : une ligne qui « a l'air sensible » est remplacée en bloc par le repli ; `:64` un chemin absolu suffit.
- Les deux messages amont citent un chemin absolu : `minos-runtime-local/src/main/java/com/minos/runtime/local/process/ProcessIndexerExecutor.java:158` (`"; see " + stderr`) et `minos-runtime-local/src/main/java/com/minos/runtime/local/sandbox/WindowsAppContainerWorkerSandboxBackend.java:662-670` (`requireAclGrantable`, `+ path +`).
- Le code de sortie est déjà exploitable : 1 (`CliExitCodes.EXECUTION_ERROR`, `CliCommandSupport.java:105-106`). Ce qui manque est le texte.

### PowerShell pris dans le `PATH` — confirmé

- `minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/plan/ScipJavaProcessPlanFactory.java:108-110` et `:351-353` : `CommandLocator.find("powershell").or(() -> CommandLocator.find("pwsh"))`.
- Même règle pour la disponibilité : `minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/install/ManagedScipProviderRuntimeManager.java:242` et `:870`.
- Le bac à sable, lui, ne fait confiance qu'au PowerShell du répertoire système : `minos-runtime-local/src/main/java/com/minos/runtime/local/process/CommandLocator.java:127-146` (`windowsPowerShell`, « PATH is intentionally not a trust source »), utilisé par `WindowsAppContainerWorkerSandboxBackend.java:107`.
- Un exécutable sous le répertoire système n'a besoin d'aucun octroi (`WindowsAppContainerWorkerSandboxBackend.java:507-514`, `isWindowsSystemRoot` `:802`) ; tout autre fichier doit être accordable par le compte courant (`:662`), ce qu'un fichier de `Program Files` n'est pas.
- Le runner est écrit pour Windows PowerShell 5.1 (`csc.exe` du .NET Framework 4, `scip-java-windows-runner.ps1:52-67`).

### La chaîne scip-java Windows n'est pas exécutable sous AppContainer — confirmé, jamais qualifiée

- Runner : `minos-provider-scip/src/main/resources/com/minos/adapter/scip/runtime/scip-java-windows-runner.ps1` — compilation des shims à l'exécution (`:208`, `:214`, appelée en `:378`), `Push-Location $Project` (`:257`, `:412`), patch Windows compilé et empaqueté par la JVM du projet (`:355`, `:357`, appelé en `:385`), scip-java lancé par `& $java` (`:419`, `:426`).
- Le seul test bout en bout du conteneur sous Windows, `minos-runtime-local/src/test/java/com/minos/runtime/local/WindowsNonElevatedIndexingTest.java`, lance un script PowerShell qui écrit par `[System.IO.File]::WriteAllText` (`:94-97`) : aucun appel natif, aucune JVM.
- `docs/project/governance/STATUS.md:47` : « aucun contrôle de CI n'exécute un provider géré ; les providers ont été qualifiés … sous Linux, pas sous Windows ».
- `ManagedScipProviderRuntimeManager.java:215`, `:256` : l'état `READY` ne dépend que de la présence des fichiers et prérequis, pas d'une exécution qualifiée sous le bac à sable.
- Déjà constaté en septembre : `docs/project/audits/archive/2026-09/S15-REGRESSION-RUNTIME-GRANT.md:83`, `:88-91` (points 1 et 2).

### Propre au poste ou défaut produit

- **Propre au poste** : l'absence de `C:\Windows\System32\WindowsPowerShell\v1.0` dans le `PATH` machine (relu : seul `C:\Program Files\PowerShell\7\` y figure) déclenche la couche 1 ; le JDK du projet sous le profil déclenche les couches 4 et 5. Git Bash et le JDK 24 du `PATH` ne jouent aucun rôle observé (le runner prend `JAVA_HOME`).
- **Défaut produit** : la couche 1 tient au choix par le `PATH` ; les couches 2 à 5 tiennent à l'absence de droit de liste du conteneur sur la racine du volume et les ancêtres, vraie sur toute installation standard (aucune ACL par défaut ne l'accorde aux packages d'application) ; l'opacité de l'erreur est indépendante du poste.

**Non mesuré** : le comportement sur un runner Windows Server 2022 de la CI (élevé, autre profil) ; la couche qui suivrait la 5 ; le coût d'une compilation des shims à l'installation. **Non vérifié** : l'exécution de scip-java elle-même sous le conteneur (jamais atteinte).

## Goals / Non-Goals

**Goals** : une erreur d'indexation dit quel run, quelle phase et où lire la cause ; scip-java Windows ne prend que le PowerShell de confiance ; une capacité non qualifiée n'est plus annoncée prête.

**Non-Goals** : rendre scip-java exécutable sous AppContainer dans ce changement ; affaiblir le confinement ; modifier `PublicErrorMessages`.

## Decisions

### D1. La ligne publique est composée de parties sûres, la cause n'est jointe que si elle est sûre

`LocalAutonomousIndexOperations` construit la ligne à partir de l'identifiant du run, de sa phase (`IndexingRun.phase()`), puis de la cause **seulement** si `PublicErrorMessages.looksSensitive` la juge sûre. Sinon, la cause est remplacée par un renvoi fixe à l'enregistrement du run et au répertoire de journaux, exprimés relativement à `MINOS_HOME` (`MINOS_HOME/index-state/runs`, `MINOS_HOME/runs/<run>`). La ligne entière passe ensuite la règle commune sans être remplacée. Alternative écartée : masquer le chemin dans la cause (fragments d'un chemin à espaces, voir le hors périmètre).

### D2. Les messages amont nomment un emplacement relatif

`ProcessIndexerExecutor` nomme le journal d'erreur relativement à `MINOS_HOME` (`runs/<run>/<provider>/provider.stderr.log`), séparateur `/` ; `requireAclGrantable` nomme le seul nom de fichier. Ces causes, devenues sûres, sont alors jointes par D1. Le littéral `without administrator privileges` (`WindowsSandboxAclAndProbeRefusalTest.java:64`) est conservé.

### D3. PowerShell canonique pour scip-java

Le plan et la disponibilité de scip-java sous Windows utilisent `CommandLocator.windowsPowerShell()`. Plus de repli `pwsh` : PowerShell 7 n'est pas une cible du runner et un `pwsh.exe` hors du répertoire système est refusé par le bac à sable. Le diagnostic de prérequis nomme Windows PowerShell 5.1.

### D4. Lot 2 : honnêteté d'abord, qualification par ADR

Sans décision d'architecture, la seule mesure conforme est de ne plus déclarer prêt ce qui n'est pas qualifié : sous Windows, l'état de scip-java devient bloqué avec un prérequis qui dit que l'exécution sous le bac à sable n'est pas qualifiée et nomme le runtime Docker. Les options de qualification, à trancher par l'ADR du lot 2 (prochain numéro libre de l'index) : (a) compiler shims et patch à l'installation, hors conteneur, sous empreinte ; emplacement par `PSDrive` ; (b) la JVM du projet ne s'initialise pas sans lister la racine du volume : capacité AppContainer dédiée, JDK géré par MINOS hors profil, ou exécution du provider Java dans le runtime Linux. Aucune n'est retenue ici.

## Risques

- Un script ou un client qui analyse le texte `index failed: IllegalStateException` : le texte change, le code de sortie non. Aucun usage dans le plugin IntelliJ (il lit la sortie JSON des commandes de lecture).
- Lot 2 : un utilisateur Windows pour qui scip-java fonctionnerait (non observé) le verrait bloqué ; réversible par l'ADR.
