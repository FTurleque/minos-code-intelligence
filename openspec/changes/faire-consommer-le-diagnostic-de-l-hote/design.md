# Design

## Context

Constat **AUD-ARC-01**, lot 2 sur 6 de l'inventaire du design archivé `openspec/changes/archive/2026-10-24-faire-consommer-l-indexation-aux-surfaces/design.md` (« Lots suivants »). Prérequis SH-08 fusionné (PR #564, `47c02b89`). ADR applicables : 0058 § 3 (un usage du moteur par une surface est une dette à résorber), 0057 § 6 (les surfaces consomment les cas d'usage) et 0042 (racine de composition).

## État vérifié au HEAD

HEAD : `develop` `47c02b89`.

### `DoctorCommand`

Fichier : `minos-cli/src/main/java/com/minos/cli/command/DoctorCommand.java`, 261 lignes.

- **Sonde des bacs à sable** : `probeWorkerSandbox(Path)` (`:100-113`) appelle `MinosApplicationComposers.resolve().workerSandboxProbe()`. Il demande `managedLocalProvider(home)` et `untrustedCode(home)`, puis projette le résultat dans la vue CLI `WorkerSandboxReport` (`:75-97`, 8 composants). `closedByDecision()` compare `cause` à `WorkerSandboxProbe.CAUSE_REJECTED_BY_DECISION` (`:95`).
- **Localisation des commandes** : `locateCommand(String)` (`:115-117`) appelle `MinosApplicationComposers.resolve().hostCommandLocator().find(command)`. `HostCommandLocator` n'est pas nommé ; il est chaîné.
- **Confidentialité** : `privateStorageDiagnostics()` (`:241-256`) appelle `PrivateLocalStorage.privacyOf` sur dix emplacements de `MINOS_HOME`, et rend `UNKNOWN` sur une `IOException`.
- **Point d'injection** : un constructeur privé au package (`:55`) prend un `Function<Path, WorkerSandboxReport>`. Il est utilisé par `DoctorCommandTest` (4 constructions de `WorkerSandboxReport`) et `ProviderVerdictConsistencyTest` (`:45-46`). `MinosCli` (`:149`) construit le constructeur public.

### Cliquets A11

- **Garde Python** (`ENGINE_TYPES_NAMED_BY_SURFACE`, CLI) : `io.PrivateLocalStorage: 1` et `runtime.WorkerSandboxProbe: 1`, tous deux nommés par `DoctorCommand` seul (relevé du garde).
- **Bytecode** (`ENGINE_TYPES_USED_BY_SURFACE_BYTECODE`, CLI) : `io.PrivateLocalStorage: 1`, `runtime.WorkerSandboxProbe: 1` et `runtime.HostCommandLocator: 1`, les trois dans `DoctorCommand` (`ModuleArchitectureTest`).
- **Auto-test** : `test_check_module_boundaries.py`, `test_a_new_cli_file_importing_the_listed_private_storage_is_refused_against_the_real_cli_list`, affirme que `io.PrivateLocalStorage` est listé pour la CLI. Il doit prendre un autre type de la liste réelle.

### Côté application

- `MinosApplicationComposer` (`minos-application`) expose `workerSandboxProbe()` (`:77`) et `hostCommandLocator()` (`:80`) ; `MinosApplicationComposers.resolve()` le trouve par `ServiceLoader`.
- Dans les tests de `minos-application`, `resolve()` échoue faute de racine de composition (`MinosApplicationComposersTest`). Un service qui n'aurait que `defaults()` ne serait donc pas testable dans son module.
- `ProviderPlatformService` est le précédent d'un service applicatif construit par la CLI à partir de types du moteur (`:27`, `:33`).

### Sortie

`minos-app/src/test/resources/characterization/cli-doctor.golden` fige la sortie texte, normalisée pour l'hôte. `A2SurfaceCharacterizationTest.java:96` la compare. Les clés JSON (`privateStoragePermissions`, `workerSandbox` et ses champs, `ready`) sont affirmées par `DoctorCommandTest`.

### SH-08 et `privacyOf`

Le design du lot 1 prévoyait que « `privacyOf` change de forme avec SH-08 ». Ce n'est pas le cas : `privacyOf` (`PrivateLocalStorage.java`) lit l'ACL par `AclFileAttributeView` et ne consulte pas le port. Il est inchangé, et le service l'appelle tel quel.

## Goals / Non-Goals

**Goals** : que la CLI ne nomme ni n'utilise plus `PrivateLocalStorage`, `WorkerSandboxProbe` ni `HostCommandLocator` ; que le diagnostic de l'hôte soit un service de `minos-application` testé dans son module ; que la sortie reste identique à l'octet.

**Non-Goals** : changer la sortie de `doctor` ; ajouter un diagnostic ; toucher le moteur ou la racine de composition.

## Decisions

### D1 — Un service applicatif, à deux constructions

`HostDiagnosticsService` (`com.minos.application`, à côté de `ProviderPlatformService`, ce qui évite un cycle de packages : A8) se construit de deux façons :

- `defaults()`, à partir de `MinosApplicationComposers.resolve()`, pour la production ;
- un constructeur public, à partir d'un `WorkerSandboxProbe` et d'un `HostCommandLocator`, pour les tests de `minos-application`.

Les types du moteur restent dans la signature du constructeur, mais seul le code de l'application les nomme. La CLI n'appelle que `defaults()`.

Alternative écartée : ajouter le diagnostic comme accesseur de `MinosApplication`. Il faudrait ouvrir l'application, donc toucher `MINOS_HOME`, pour un simple diagnostic. Aujourd'hui `doctor` ne l'ouvre pas : il passe seulement par la racine de composition.

### D2 — Un résumé applicatif, la vue CLI conservée

`WorkerSandboxDiagnosis` (record, `com.minos.application`) reprend les champs de la sonde en types JDK, avec `closedByDecision()`. `DoctorCommand.WorkerSandboxReport` reste la vue de présentation et le point d'injection des tests de commande (D1 du lot 1). Elle est projetée depuis le résumé, et `closedByDecision()` se lit sur la constante du résumé. `DoctorCommandTest` et `ProviderVerdictConsistencyTest` ne changent pas : ils construisent la vue CLI, pas le résumé.

### D3 — La liste des emplacements contrôlés passe dans le service

Les dix emplacements de `MINOS_HOME` que `doctor` contrôle relèvent de la connaissance du stockage, pas de la présentation. `HostDiagnosticsService.privateStorage(Path home)` rend une table ordonnée libellé → niveau (`ENFORCED`, `EXPOSED`, `ABSENT`, `UNSUPPORTED`, `UNKNOWN`) avec les mêmes libellés, dans le même ordre. Le texte et le JSON sont donc inchangés.

## Direction des dépendances (ADR 0068)

`minos-cli` → `minos-application` → `minos-engine` : aucune arête ajoutée. `HostDiagnosticsService` vit dans `com.minos.application`, racine existante (A14), et ne nomme aucun adaptateur (A2). `MinosApplicationComposers` est déjà dans ce package. Aucun cycle de packages (A8) : le service ne dépend que du moteur, de `MinosApplicationComposers` et du JDK.

## Capacités

Aucune capacité n'est ajoutée ni requalifiée. Le diagnostic décrit les mêmes faits, avec les mêmes niveaux et les mêmes causes.

## Windows et Linux

La sonde réelle diffère selon le système (AppContainer et Job Object sous Windows, bubblewrap et cgroup sous Linux), et le service la relaie sans la changer. La confidentialité s'évalue par ACL sous Windows et par permissions POSIX sous Linux, toujours par `privacyOf`. Les tests du service utilisent une sonde et un localisateur factices, et de vrais dossiers temporaires : ils tournent à l'identique sur les deux systèmes. `cli-doctor.golden` est normalisé pour l'hôte.

## Coût

| Poste | Estimation au HEAD |
|---|---|
| Couverture du nouveau code (SonarCloud, 80 % par module) | `minos-application` : environ 90 lignes (service, résumé), couvertes par `HostDiagnosticsServiceTest`, qui couvre la projection de la sonde, la décision, la commande trouvée ou absente, chaque niveau de confidentialité et `UNKNOWN` sur une erreur. `minos-cli` : lignes modifiées de `DoctorCommand`, couvertes par les tests de commande existants |
| Tests déplacés | aucun : la logique de confidentialité était testée par `DoctorCommandTest` à travers la sortie, et le reste |
| Portées de `scripts/quality/check-jacoco.py` | `global` seulement ; aucune portée ne cible `DoctorCommand` |
| Gates littéraux | `check-module-boundaries.py` (A11, CLI : deux lignes retirées) ; `test_check_module_boundaries.py` (le cas qui lit `io.PrivateLocalStorage` dans la liste réelle prend un autre type listé) ; `ModuleArchitectureTest` (table bytecode, CLI : trois lignes retirées). Recherche faite : aucun `scripts/**/check-*.py` ne cite `DoctorCommand`, `probeWorkerSandbox` ni `privateStorageDiagnostics` |
| Bases SpotBugs | `minos-cli.xml` et `minos-application.xml` à rejouer par le profil `audit-spotbugs` ; une alerte nouvelle sur le résumé (record à liste) se corrige dans le code |
| Documentation | ADR 0058 (mise en œuvre, lot 2) ; `SUIVI.md` ; plan du chantier ; aucune page utilisateur, la sortie ne change pas |

## Risks / Trade-offs

- **Sortie modifiée par inadvertance** : le golden `cli-doctor.golden` (texte) et `DoctorCommandTest` (JSON) le refusent. La preuve exécute `A2SurfaceCharacterizationTest` dans `minos-app`.
- **Retrait par chaînage** : un `var` ou un chaînage ne retire rien. La table bytecode voit `HostCommandLocator`, que le garde Python ne voit pas : la tâche de clôture vérifie les deux tables.
- **Résolution de la racine de composition au mauvais moment** : `defaults()` résout la racine à l'appel, comme le code actuel, jamais au chargement de la classe.

## Recouvrements avec les changements actifs

| Changement | Fichiers communs | Effet |
|---|---|---|
| `decouper-les-classes-de-plus-de-800-lignes` | aucun fichier de ce lot (il touche `PrivateLocalStorage` et le manager SCIP) | sans effet ; son extraction de `WindowsDacl`, caduque depuis SH-08, est un sujet séparé |
| `lire-le-registre-sans-ecrire` | aucun | sans effet |

## Migration Plan

Une PR vers `develop`, branche `refactor/faire-consommer-le-diagnostic-de-l-hote`. Le premier commit archive SH-08 et met à jour le suivi. Retour arrière : revert de la PR, sans effet sur les données.

## Open Questions

Aucune.
