# Design

## Context

Ce changement est la phase 1 de la résorption de la dette d'architecture figée de l'audit du 10 octobre 2026 (AUD-ARC-01, -05, -08). Les phases 2 à 4 (SH-08, lots 2 à 6 d'AUD-ARC-01, SH-02, SH-03, SH-06) retirent des entrées des tables A11, A12 et A13. Ce changement fait en sorte qu'une entrée ne puisse disparaître que par une correction : jamais par un `var`, un chaînage d'accesseur ou un déplacement de l'usage dans un autre fichier.

ADR applicables : 0068 (modules, dépendances et propriété des packages ; § 5 et « Compliance and Enforcement »), 0058 § 3 (les usages du moteur par les surfaces sont une dette à résorber) et 0057 § 7 (ArchUnit est une façon acceptée de compléter les gardes).

## État vérifié au HEAD

HEAD analysé : `develop` `dedb219e`. Relevés faits par un script qui importe le garde (`types_named_by_module`, `production_types`) et par un import ArchUnit 1.5.1 des classes compilées (`mvnw -pl minos-cli,minos-api,minos-mcp,minos-nexus -am compile` sur le HEAD).

### A11 : ce que voit le garde Python

`ENGINE_TYPES_NAMED_BY_SURFACE` (`scripts/architecture/check-module-boundaries.py:509-557`) compte **39 types** : CLI 15, API 14, MCP 4, NEXUS 6. Ces types sont nommés par **46 couples (fichier, type)**. Un type est nommé par plusieurs fichiers dans 4 cas : `PublicErrorMessages` (CLI 3, MCP 2), `GitIntelligence` (CLI 3), `HostedControlPlaneService` (CLI 2) et `UnreadableRegistryException` (CLI 2). La règle (`named_type_ratchet_problems`, `:656-672`) ne compare que des ensembles de types : un troisième fichier de la CLI qui nomme `HostedControlPlaneService` passe.

### A11 : usages sans nom, accesseurs de `MinosApplication`

`MinosApplication` (`minos-application/src/main/java/com/minos/application/MinosApplication.java:247-313`) déclare **19 accesseurs publics d'instance** dont le type de retour nomme un type de `minos-engine`. Parmi eux, `hostedControlPlaneService()` rend `Optional<HostedControlPlaneService>` et `indexerDescriptors()` rend `List<IndexerDescriptor>`. `snapshotStager()` et `snapshotPromoter()` rendent des types imbriqués de `IndexingRuntimePorts`. Les surfaces en appellent quatre :

| Fichier | Appel | Type rendu | Déjà nommé par le fichier ? |
|---|---|---|---|
| `minos-api/.../LocalMinosApi.java:96` | `app.projectRegistry()`, `app.snapshotStore()` | `registry.ProjectRegistry`, `store.CodeKnowledgeSnapshotStore` | non : **types absents de la liste de l'API** |
| `minos-api/.../LocalMinosMultiRepositoryApi.java:36` | `app.gitIntelligence()` | `git.GitIntelligence` | oui |
| `minos-api/.../LocalMinosTeamApi.java:27` | `hostedControlPlaneService()` | `hosted.HostedControlPlaneService` | oui |
| `minos-cli/.../MinosCliRunner.java:117` | `opened.projectRegistry()`, `opened.snapshotStore()` | idem | non : **types absents de la liste de la CLI** |
| `minos-cli/.../MinosCliRunner.java:123` | `MinosApplication::gitIntelligence` | `git.GitIntelligence` | oui |
| `minos-cli/.../MinosCliRunner.java:127` | `opened.hostedControlPlaneService()` | `hosted.HostedControlPlaneService` | non : **un fichier de plus** |
| `minos-mcp/.../MinosApplicationMcpBackend.java:315` | `application.hostedControlPlaneService()` | `hosted.HostedControlPlaneService` | oui |

Il y a un homonyme : `MinosCliRunner.java:122` appelle `.gitIntelligence(LazyApplication.lazy(…))`, une méthode de `MinosCli.Builder` à un argument. L'accesseur n'a aucun paramètre ; comparer l'arité suffit à l'écarter.

### A11 : ce que voit le bytecode

ArchUnit (`JavaClass.getDirectDependenciesFromSelf`, type imbriqué ramené à son type de premier niveau, classe ramenée à sa classe de premier niveau) donne aussi **39 types**, mais pas les mêmes :

| Vu par le bytecode seulement (usages sans nom, par appel chaîné) | Vu par les sources seulement |
|---|---|
| CLI : `hosted.HostedProjectBinding` et `hosted.HostedTenantState` (`TeamCommand` : `service.bindProject(…).projectId()`, `service.tenant(…).retentionPolicy()`), `runtime.HostCommandLocator` (`DoctorCommand` : `compositionRoot().hostCommandLocator().find(…)`) ; `registry.DegradedEntry` dans `ProjectCommand` (paramètre de lambda inféré) | CLI : `query.SymbolResult`, `query.UsageResult`, `query.RelationshipResult` (types utilisés seulement comme argument générique, effacés du bytecode) ; accesseurs dont le résultat est passé sans appel (`MinosCliRunner`, `LocalMinosApi`) |

Aucune des deux vues ne contient l'autre. Le relevé complet par surface est dans la table `ENGINE_TYPES_USED_BY_SURFACE_BYTECODE` du test (tâche 3).

### A12

`PROCESS_LAUNCHERS` (`:561-564`) = `{minos-engine/io.PrivateLocalStorage}`. `PrivateLocalStorage.java` mentionne `ProcessBuilder` **6 fois**, dont 4 fois `ProcessBuilder.Redirect.DISCARD`. Il a **2 sites de lancement** :

- `:828`, `icacls /inheritance:d` ;
- `:866`, le `Launcher` de `requireNoConditionalDeny`, qui sert à `icacls /save`.

Le suivi (« 1 classe, 6 `ProcessBuilder` ») comptait les mentions, pas les sites.

### A13

`ADAPTER_TYPES_NAMED_BY_ADAPTER` (`:568-589`) compte **14 types**, nommés par **35 couples (fichier, type)**. `runtime.local.process.CommandLocator` à lui seul est nommé par 10 fichiers du provider SCIP.

| Couple | Types | Fichiers |
|---|---|---|
| `minos-provider-scip` → `minos-runtime-local` | 9 | 28 |
| `minos-provider-scip` → `minos-storage-local` | 1 | 1 |
| `minos-storage-postgresql` → `minos-storage-local` | 4 | 6 |

Le compte de 28 rejoint le « 28 imports SCIP → runtime-local » de la revérification du 24 octobre.

## Goals / Non-Goals

**Goals** : faire échouer le garde sur un nouvel usage d'un type déjà listé, sur un nouveau site de lancement et sur un usage sans nom par accesseur de `MinosApplication` ; donner à A11 une vue bytecode ; écrire les compteurs de départ des phases 2 à 4.

**Non-Goals** : retirer une entrée ; modifier un fichier de `src/main` ; changer A14 ; suivre les accesseurs d'autres types que `MinosApplication` dans le garde Python.

## Decisions

### D1 — A11 et A13 : un compteur de fichiers par type

Chaque entrée devient `type → nombre de fichiers de production qui l'utilisent`. La forme est `dict[str, dict[str, int]]` pour A11 (surface → type → fichiers) et `dict[tuple[str, str], dict[str, int]]` pour A13. Le garde exige l'**égalité** :

- un fichier de plus échoue en listant tous les fichiers concernés, pour que la relecture voie le nouveau ;
- un fichier de moins échoue en donnant le compteur à écrire.

Le cliquet ne fait donc que se resserrer, et chaque baisse est une modification visible de la table.

Alternative écartée : une table `fichier → types`. Elle nommerait exactement le fichier fautif, mais elle compterait 46 + 35 lignes et devrait changer à chaque renommage de fichier sans que la dette bouge. Le compteur suffit à refuser une croissance ; déplacer un usage d'un fichier à un autre ne fait pas croître la dette.

### D2 — A11 : un appel d'accesseur de `MinosApplication` compte comme un usage

Le garde lit la source de `MinosApplication` : ses méthodes publiques d'instance et leur type de retour. Un mot de ce type qui se résout, par les imports du fichier, en un type de `minos-engine` donne l'accesseur ; un type imbriqué est ramené à son type de premier niveau. Rien n'est recopié dans le garde : un nouvel accesseur est couvert sans modification de la règle.

Dans une surface, deux formes comptent comme un usage du type rendu, dans le même compteur que les noms :

- l'appel `.nom(` dont le nombre d'arguments égale le nombre de paramètres de l'accesseur ;
- la référence `::nom`.

Un `var` ou un chaînage ne retire donc pas un fichier du compteur. Le fichier qui écrit `var g = app.gitIntelligence()` utilise toujours `git.GitIntelligence`. C'est l'exigence déjà posée par le lot 1 : « remplacer un type nommé par `var` ou par un chaînage n'est pas un retrait ».

Conséquence sur les compteurs : A11 passe de **39 à 43 types** (API et CLI : `registry.ProjectRegistry` et `store.CodeKnowledgeSnapshotStore`) et de 46 à **51 couples (fichier, type)**. Les 4 nouvelles entrées sont de la dette existante qui n'était pas comptée, pas une dette ajoutée. Les critères de sortie des phases suivantes s'expriment désormais sur 43 (phase 2 : 41 au plus, voir « Compteurs de départ »).

Limites écrites :

- Un accesseur d'un autre type (`MinosApplicationComposer`, service applicatif) n'est pas suivi ; la vue bytecode (D4) voit ceux dont la surface appelle une méthode.
- Une méthode de la surface sans argument et homonyme d'un accesseur serait comptée à tort. Le garde échoue alors, et l'erreur reste visible à la relecture.

### D3 — A12 : un compteur de sites de lancement par classe

`PROCESS_LAUNCHERS` devient `dict[str, int]` (classe → sites). Une classe reste un lanceur dès qu'elle mentionne `ProcessBuilder` ou `Runtime.getRuntime().exec` (règle inchangée, la plus large). Ses sites sont comptés sur `new ProcessBuilder`, `ProcessBuilder::new`, `ProcessBuilder.startPipeline` et `Runtime.getRuntime().exec`. Le garde exige l'égalité. La valeur de départ est `{"minos-engine/io.PrivateLocalStorage": 2}`.

Limite écrite : réutiliser une même instance de `ProcessBuilder` pour un second `start()` ne crée pas de site. Le code actuel n'en a aucune, et la relecture de sécurité de tout lancement (`.claude/rules/code-review.md`) reste le contrôle de ce cas.

### D4 — A11 existe aussi en ArchUnit, A13 non

**Décision : ajouter A11 à `ModuleArchitectureTest`, en complément du garde Python, et ne pas le faire pour A13.**

Raisons :

1. **Les deux vues sont disjointes au HEAD** (« État vérifié »). Le bytecode voit 3 types de la CLI et 1 fichier que le garde Python ne peut pas voir sans inférence de types : les appels chaînés sur un résultat du moteur. Les sources voient 3 types utilisés seulement en argument générique, et les accesseurs passés sans appel. Le seul garde Python laisserait la dette baisser par un chaînage, ce que l'exigence du lot 1 interdit.
2. L'objection du 2026-10-22 (archive `figer-la-dette-de-frontieres-par-des-cliquets`, design D1) visait une règle ArchUnit **au grain du module**, qui laisserait passer ce que l'on veut refuser. La règle ajoutée ici est au grain du type et compte les classes ; elle n'a pas ce défaut.
3. Le test tourne dans `Verify (ubuntu-24.04)` et `Verify (windows-2022)`, deux checks exigés, après la compilation complète qu'ils font déjà. Il n'ajoute aucun build.

Forme retenue :

- Le test tient sa propre table `ENGINE_TYPES_USED_BY_SURFACE_BYTECODE` (surface → type → classes de premier niveau). Il exige l'égalité, de façon symétrique au garde Python.
- Le garde Python reste la référence des **noms**, le test celle des **dépendances compilées**. Un lot qui retire un usage met à jour les deux tables dans le même commit.
- La table du test n'est pas confrontée par le garde Python à la sienne, comme l'est `ALLOWED` (`check_architecture_test_policy`) : leurs contenus diffèrent par construction, et chacune est déjà un cliquet exact contre ce qu'elle observe.

Pour A13, une vue bytecode est écartée : tous les couplages relevés sont des noms (imports, nom qualifié) que le garde Python voit, et la phase 4 les retire par déplacement de code (SH-02, SH-03, SH-06).

### D5 — `call_argument_count` sert aussi à A11

L'aide existante (`:774-791`, règle A16) compte les arguments d'un appel. Elle prend en paramètre le libellé de son erreur d'appel mal formé, pour qu'A11 l'emploie sans message trompeur. Le comportement d'A16 ne change pas.

## Compteurs de départ des phases suivantes

| Cliquet | Avant (HEAD `dedb219e`) | Après ce changement | Cible des phases suivantes |
|---|---|---|---|
| A11, garde Python | 39 types (CLI 15, API 14, MCP 4, NEXUS 6) ; 46 couples non comptés | **43 types** (CLI 17, API 16, MCP 4, NEXUS 6), **51 couples** (fichier, type) | phase 2 (lot 2) : 41 types au plus ; phase 3 : seulement des entrées justifiées |
| A11, bytecode | non mesuré | **39 types** (CLI 15, API 14, MCP 4, NEXUS 6), **47 couples** (classe, type) | phase 2 : sans `PrivateLocalStorage`, `WorkerSandboxProbe`, `HostCommandLocator` dans la CLI (36 au plus) |
| A12 | 1 classe, sites non comptés | **1 classe, 2 sites** | phase 2 : vide, ou sans `PrivateLocalStorage` |
| A13 | 14 types ; couples non comptés | **14 types, 35 couples** (SCIP → runtime local 9/28, SCIP → stockage local 1/1, PostgreSQL → stockage local 4/6) | phase 4 : vide, ou seulement des entrées justifiées |

## Direction des dépendances (ADR 0068)

Inchangée : aucun POM, aucune source de production. Le test ArchUnit vit déjà dans `minos-app`, qui importe les classes compilées de tous les modules en portée de test.

## Capacités

Aucune capacité produit n'est ajoutée, modifiée ni requalifiée.

## Windows et Linux

Le garde Python tourne dans le job `invariants` (Ubuntu) et sur le poste Windows ; ses chemins sont normalisés en `/`. Le test ArchUnit tourne dans les deux jobs `Verify`. Le bytecode compilé est le même sur les deux systèmes : aucune dépendance ne dépend de la plateforme.

## Coût

| Poste | Estimation |
|---|---|
| Couverture du nouveau code (SonarCloud, 80 % par module) | sans objet : aucun code de production. Le test ArchUnit est du code de test |
| Tests déplacés avec leur classe | aucun déplacement |
| Portées de `scripts/quality/check-jacoco.py` | inchangées |
| Gates littéraux | aucun gate ne lit les trois tables (recherche de `ENGINE_TYPES_NAMED_BY_SURFACE`, `PROCESS_LAUNCHERS`, `ADAPTER_TYPES_NAMED_BY_ADAPTER` dans `scripts/` et `.archgate/`, `docs/architecture/adr/*.rules.ts`). `ARCH-0068-….rules.ts` lit `MODULES` et `PACKAGE_ROOTS_BY_MODULE`, que ce changement ne touche pas. `check-ci-wiring.py` et `check-single-execution.py` : l'auto-test existant est déjà câblé |
| Bases SpotBugs | inchangées : aucune classe de production |
| Documentation | `docs/developer/quality-gates.md` (§ A11 à A14), ADR 0068 « Compliance and Enforcement », `SUIVI.md`, plan du chantier |

## Risks / Trade-offs

- **Deux tables à tenir pour A11.** Un lot qui oublie la table du test échoue en CI (`Verify`). Le message du test nomme la table et le compteur attendu ; la tâche de clôture de chaque lot cite les deux.
- **Faux positif d'un homonyme d'accesseur.** Il est écarté par l'arité. Un reste éventuel fait échouer le garde : c'est un refus, pas une fuite.
- **Coût de relecture d'un compteur.** Le message liste les fichiers qui utilisent le type, et la relecture voit lequel est nouveau.
- **Conflit avec `decouper-les-classes-de-plus-de-800-lignes`.** Ce changement remplace l'entrée A12. Celui qui fusionne en second rebase et écrit `{"minos-engine/io.WindowsDacl": 2}`.

## Recouvrements avec les changements actifs

| Changement | Fichiers communs | Effet |
|---|---|---|
| `decouper-les-classes-de-plus-de-800-lignes` (0/19) | `check-module-boundaries.py` (entrée A12) | son entrée A12 prend la forme de D3 ; aucune autre table touchée |
| `lire-le-registre-sans-ecrire`, `retirer-les-jalons-des-identifiants` et les autres changements actifs | aucun fichier de ce changement | sans effet |

## Migration Plan

Une PR vers `develop`, branche `ci/rendre-les-cliquets-d-architecture-etanches`. Retour arrière : revert de la PR (aucune donnée, aucune sortie).

## Open Questions

Aucune.
