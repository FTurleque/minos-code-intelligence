# Design

## Context

Sprint 11 « Hygiène de fond » de l'audit du 10 octobre 2026 : **AUD-QUA-11** (Q18 de l'audit de septembre, toujours ouvert). HEAD analysé : `5ce7b79b` (`develop` du 2026-10-22) à la spécification, `ebee335a` à la mise en œuvre (2026-10-24). Motivation : voir `proposal.md` (Why).

## État vérifié au HEAD

Mesure rejouée le 2026-10-24 au HEAD `ebee335a` de la branche `refactor/fixer-la-langue-des-commentaires` (empilée sur les PR #553, #555, #556 et #557, non fusionnées), tâche 1.1. Elle remplace celle du HEAD `5ce7b79b`, dont les écarts sont expliqués plus bas.

### Méthode de mesure (reproductible)

Toutes les mesures portent sur les fichiers suivis par git : `git ls-files 'minos-*/src/main/**/*.java'` (**576** fichiers au HEAD, 560 au HEAD `5ce7b79b` : les sprints 10 et 11 ont extrait des classes), lus en **UTF-8 explicite**. Deux critères sont appliqués au même ensemble :

1. **Commande de l'audit** : `grep -lE '^\s*(\*|//).*[éèàùêç]'` sur ces fichiers, lancée avec `LC_ALL=C.UTF-8` : **170** fichiers. Sous `LC_ALL=C`, la classe de caractères est lue octet par octet et la même commande en compte **178** : par exemple `MinosApplicationMcpBackend.java:120` (« ADR 0039 §6 ») est retenu à cause d'un octet de `§`. Le décompte de l'audit dépend donc de la locale. « Mixte » selon l'audit : un de ces fichiers qui a aussi une ligne de commentaire contenant `the`, `is`, `never` ou `when` (mot entier) : **22** fichiers.
2. **Critère du gate** (D2, `scripts/quality/check-comment-language.py --inventory`) : il remplace le script indicatif hors dépôt de la première mesure. Commentaires extraits après décodage des `\uXXXX` et saut des littéraux ; balises en ligne, `<code>`, `<pre>`, balises HTML, segments entre guillemets ou accents graves, noms de `@param`/`@throws` et lignes `@see`/`@author`/`@since` retirés ; chaque commentaire découpé en paragraphes ; un paragraphe est français si son score français (mots de la liste française plus mots accentués) l'emporte et vaut au moins 2, ou au moins 1 avec un mot accentué ; anglais si son score anglais l'emporte et vaut au moins 2. Un fichier est **mixte** s'il a un paragraphe de chaque langue, **hors langue** (sous D1 = A) s'il a un paragraphe français, **à jalon** si un commentaire cite `M` suivi de chiffres en mot entier (même motif que `git grep -nE '^\s*(//|\*|/\*).*\bM[0-9]+\b'`, qui compte **111 lignes** dans les mêmes **98** fichiers).

### Décomptes au HEAD `ebee335a`

| Module | Fichiers `src/main` | Français (audit) | Français (gate) | Anglais seul (gate) | Mixtes (audit) | Mixtes (gate) | À jalon |
|---|---|---|---|---|---|---|---|
| `minos-api` | 13 | 0 | 0 | 11 | 0 | 0 | 11 |
| `minos-app` | 7 | 1 | 1 | 6 | 0 | 0 | 2 |
| `minos-application` | 122 | 53 | 58 | 51 | 5 | 10 | 33 |
| `minos-bootstrap` | 6 | 5 | 6 | 0 | 0 | 0 | 1 |
| `minos-cli` | 38 | 11 | 16 | 19 | 2 | 3 | 9 |
| `minos-domain` | 37 | 19 | 21 | 6 | 0 | 0 | 3 |
| `minos-engine` | 172 | 58 | 64 | 85 | 6 | 9 | 21 |
| `minos-integration-git` | 7 | 0 | 0 | 7 | 0 | 0 | 1 |
| `minos-intellij` (plugin, Gradle) | 29 | 1 | 1 | 18 | 0 | 0 | 2 |
| `minos-mcp` | 9 | 1 | 0 | 9 | 1 | 0 | 0 |
| `minos-nexus` | 4 | 0 | 0 | 3 | 0 | 0 | 3 |
| `minos-provider-scip` | 42 | 14 | 17 | 20 | 4 | 7 | 5 |
| `minos-runtime-local` | 40 | 4 | 5 | 35 | 1 | 2 | 1 |
| `minos-storage-local` | 31 | 2 | 2 | 24 | 2 | 2 | 6 |
| `minos-storage-postgresql` | 19 | 1 | 1 | 11 | 1 | 1 | 0 |
| **Total** | **576** | **170** | **192** | **305** | **22** | **34** | **98** |

79 fichiers n'ont aucun paragraphe classable. Les deux critères s'accordent sur **21** fichiers mixtes : l'audit retient en plus `McpArgumentBounds.java` (lettre `é` citée dans un `{@code "é"}`, ligne 53 : faux positif) ; le gate retient en plus treize fichiers dont un seul paragraphe (souvent l'en-tête de classe) est dans l'autre langue, par exemple `CommandLocator.java:20`, `ScipSymbolSnapshotReport.java:5`, `UnreadableRegistryException.java:18`, `RuntimeIntelligenceService.java:183`.

**Écarts avec la mesure du HEAD `5ce7b79b`** (168 fichiers à l'audit, 22 ou 29 mixtes) : les 16 fichiers nouveaux des sprints 10 et 11 sont anglais ; deux fichiers de plus portent une ligne accentuée à la commande de l'audit (170). Le gate retient 34 mixtes et non 29 : les 29 du critère indicatif y sont tous, plus cinq fichiers que le découpage en paragraphes et le poids d'un mot accentué font apparaître (`StorageBackends.java`, `EmbeddingProvider.java`, `ImpactLimitation.java`, `IndexStateStore.java`, `ScipSymbolSnapshotImporter.java`) ; aucun de ces cinq fichiers n'a changé depuis `5ce7b79b` (`git diff --stat 5ce7b79b..HEAD` vide), l'écart tient au critère.

Fichiers mixtes selon le gate (liste `mixed` initiale de `scripts/quality/comment-language.json`), ceux qui citent aussi un jalon marqués « jalon » :

- `minos-application` (10) : `application/MinosApplication.java`, `application/MinosApplicationAssembler.java`, `application/StorageBackends.java`, `application/dynamic/RuntimeIntelligenceService.java`, `application/semantic/EmbeddingProvider.java` (jalon), `architecture/ArchitectureDependencyGraph.java`, `context/LocalSourceReader.java`, `impact/ImpactLimitation.java`, `output/CodeIntelligenceResultRenderer.java` (jalon), `program/analysis/AdvancedImpactService.java` (jalon).
- `minos-cli` (3) : `cli/DockerRuntimeBootstrap.java`, `cli/MinosLauncher.java`, `cli/command/ProjectCommand.java`.
- `minos-engine` (9) : `incremental/ProjectFingerprintService.java`, `io/StaleScratchReclamation.java`, `orchestration/IndexStateStore.java`, `orchestration/IndexingLifecycleService.java`, `orchestration/IndexingResumePlanner.java`, `orchestration/IndexingRunExecutor.java`, `orchestration/IndexingRuntimePorts.java` (jalon), `orchestration/ScipSymbolSnapshotReport.java`, `registry/UnreadableRegistryException.java`.
- `minos-provider-scip` (7) : `ingestion/ScipIndexReader.java`, `ingestion/ScipSymbolCatalog.java`, `ingestion/ScipSymbolNormalizer.java` (jalon), `ingestion/ScipSymbolSnapshotImporter.java`, `runtime/ScipProjectSnapshotLifecycle.java` (jalon), `runtime/plan/ScipJavaProcessPlanFactory.java`, `runtime/plan/ScipTypeScriptProcessPlanFactory.java`.
- `minos-runtime-local` (2) : `process/CommandLocator.java`, `process/IndexerProcessPlanFactory.java`.
- `minos-storage-local` (2) : `registry/LocalProjectRegistry.java` (jalon), `store/FileSymbolSnapshotStore.java`.
- `minos-storage-postgresql` (1) : `snapshot/PostgresCodeKnowledgeSnapshotStore.java`.

Aucun nom de fichier n'est en double parmi les 576 sources de production (`git ls-files 'minos-*/src/main/**/*.java' | xargs -n1 basename | sort | uniq -d` ne sort rien).

**Mots ambigus relevés** (cas de l'auto-test) : `a`, `an` (« un an »), `on` (« on peut »), `plus`, `via`, `type`, `plan`, `index` n'appartiennent à aucune liste ; `car` est retiré de la liste française ; les élisions (`n'est`, `l'index`) sont coupées à l'apostrophe ; `§`, une lettre accentuée dans un littéral, un bloc de texte, un `{@code}`, un `<pre>`, un segment entre guillemets, un nom de `@param` ou un `@author` ne comptent pas ; `arm64`, `System32` et `qualifiedM24Providers` ne sont pas des jalons.

### Exemples de l'audit relus

- `minos-mcp/src/main/java/com/minos/mcp/MinosMcpServer.java` : aucune lettre accentuée ; l'exemple « Serveur MCP local » n'existe pas au HEAD.
- `minos-engine/src/main/java/com/minos/orchestration/IndexingRunExecutor.java:129` (ligne 123 au HEAD `5ce7b79b`) : « Rouvre le run interrompu … » au milieu de Javadoc anglaise (6 paragraphes français, 29 anglais au critère du gate).
- `FileResumableRunMarkers.java` n'existe pas au HEAD ; la classe correspondante est `minos-bootstrap/src/main/java/com/minos/bootstrap/RunDirectoryResumableRunMarkers.java`, qu'aucun des deux critères ne tient pour mixte.

### Textes et gates existants

- `CONTRIBUTING.md` : aucune occurrence de « langue », « français », « anglais », « language », « English » (`grep -niE`). La seule règle écrite est `AGENTS.md:9` : identifiants et messages de code suivent la convention du module touché, Javadoc sans mélange de langues dans un même fichier. `.claude/rules/java-production.md` et `.claude/rules/plugin-intellij.md` ne disent rien de la langue des commentaires.
- **Aucun gate ne contrôle la langue des commentaires** : `grep -rlniE "comment.?language|langue des commentaires|accent" scripts/ --include=*.py` ne trouve rien. `scripts/architecture/check-private-io.py:149` (`decode_unicode_escapes`) et `:154` (`strip_comments_and_literals`) font l'inverse du besoin : ils effacent commentaires et littéraux pour ne garder que le code.
- Squelette commun : `scripts/quality/gate_cli.py` (`repository_files`, `run`, `report_failures`). `scripts/quality/check-ci-wiring.py` (règle 2, docstring lignes 11-12) exige que chaque `scripts/**/test_*.py` et chaque `--self-test` soit exécuté par le job `invariants` de `.github/workflows/pr-ci.yml`, que `.claude/scripts/run_gates.py:31` (`invariants_commands`) rejoue en local.
- **Baseline SpotBugs insensible aux lignes** (relu, non exécuté) : `scripts/quality/audit-report-summary.py:121-131` identifie une alerte par son `instanceHash`, « que SpotBugs conserve quand les lignes bougent » selon sa docstring ; la comparaison ne tourne que dans `.github/workflows/code-audit.yml:87` (déclenchement manuel, hors checks exigés). Les `SourceLine start/end` de `quality/spotbugs-baseline/*.xml` ne servent qu'à l'affichage.
- `scripts/architecture/private-io-allowlist.json` : entrées par fichier, méthode et plafond, sans numéro de ligne ; le gate ignore les commentaires.
- **Gates qui citent un fichier mixte** (`grep -rlw <NomDeClasse> scripts --include=*.py`, hors `history/`, au HEAD `ebee335a`) : 21 des 34 fichiers, dont `FileSymbolSnapshotStore` (6 scripts), `CommandLocator` et `MinosApplication` (5 chacun), `PostgresCodeKnowledgeSnapshotStore` (4), `ScipJavaProcessPlanFactory` (3). Scripts concernés : `architecture/check-module-boundaries.py`, `architecture/test_check_module_boundaries.py`, `quality/check-jacoco.py`, `quality/check-partial-result-consumers.py`, `quality/check-hosted-control-plane-consistency.py`, `quality/check-runtime-dynamic-consistency.py`, `quality/check-semantic-retrieval-consistency.py`, `quality/check-polyglot-provider-consistency.py`, `quality/check-remote-distributed-consistency.py`, `remediation/check-p0-p2.py`, `remediation/check-mnd.py`, `remediation/check-mne.py`, `remediation/check-post-mne.py`.
- **Chaînes exigées dans un commentaire (tâche 1.2, 2026-10-24)** : **aucune**. Méthode : pour chaque fichier mixte et chaque script qui le cite, toutes les constantes de chaîne du script (analyse `ast`, 6 caractères au moins) qui n'apparaissent dans le fichier Java **qu'à l'intérieur d'un commentaire**. Les seules rencontres sont des mots génériques que le script affirme ailleurs (`services`, `sandbox`, `report`, `process`, `opt-in`, `fail-closed`, `minos-storage-local`…) : `check-hosted-control-plane-consistency.py` cherche `opt-in` et `check-remote-distributed-consistency.py` `fail-closed` dans des documents (`require_facts`), pas dans ces sources ; les autres sont des noms de modules, de scopes JaCoCo ou de règles de `check-module-boundaries.py`. Consigne pour les lots : aucune phrase de commentaire à garder ; chaque lot rejoue `python .claude/scripts/run_gates.py`, qui reste la preuve.

### Écarts avec l'action proposée par l'audit

1. **Le décompte de l'audit n'est pas reproductible** : il dépend de la locale (170 ou 178 fichiers au HEAD `ebee335a`) et son heuristique de mots diverge sur 14 des 35 fichiers retenus par l'un ou l'autre critère. Le gate a besoin d'un critère écrit et testé (D2), et sa liste initiale est son propre relevé (34 fichiers), pas les 27 de l'audit.
2. **« Fixer la langue par module » suppose un choix que les chiffres ne font pas** : `minos-bootstrap` (5 ou 6 fichiers sur 6) et `minos-domain` (19 ou 21 sur 37) ont une majorité de fichiers avec du français, `minos-application` est à moitié (53 ou 58 sur 122), `minos-api` n'en a aucun. C'est la décision D1.
3. **Une langue déclarée qu'aucun contrôle ne lit n'est pas une règle** : la déclaration reçoit un effet vérifiable (D3, compteur par module).
4. **`CONTRIBUTING.md` n'est pas le seul texte à tenir** : `AGENTS.md:9` énonce déjà une règle ; les deux doivent dire la même chose (D5).
5. **Retoucher un commentaire est sans effet sur les baselines** : ni la baseline SpotBugs (`instanceHash`) ni la liste des E/S privées ne dépendent des lignes. Le risque restant est un gate littéral qui exigerait une chaîne écrite dans un commentaire (tâche 1.2).
6. **Coût SonarCloud nul** : des commentaires ne sont pas du code à couvrir.

## Goals / Non-Goals

**Goals** : une règle écrite, lisible par un contributeur et par un gate ; plus aucun nouveau fichier mixte ; un nombre de fichiers hors de la langue déclarée qui ne peut que baisser ; les fichiers mixtes existants résorbés sans campagne.

**Non-Goals** : traduire le code ; juger la qualité des commentaires ; détecter la langue par un modèle ou une dépendance (bibliothèque standard Python seule).

## Decisions

### D1. Langue de référence — **option A, décision du propriétaire du 2026-10-24**

**Décision (tâche 1.3, 2026-10-24)** : **option A**, l'anglais est la langue de référence des commentaires et de la Javadoc de tous les modules de code ; `minos-intellij` est **inclus**. Les commentaires qui citent un jalon sont **rattachés à ce changement** (D6). D2 à D4 suivent les recommandations ci-dessous. Au HEAD `ebee335a`, 192 fichiers comptent « hors langue » au départ et 384 n'ont aucun paragraphe français.

**Option A (recommandée) : anglais pour les commentaires et la Javadoc de tous les modules de code**, plugin compris. Une seule règle, sans table à retenir ; déjà vraie pour `minos-api` (Javadoc publique) et pour 379 des 560 fichiers au HEAD `5ce7b79b` (aucun commentaire français au critère indicatif) ; les artefacts du dépôt (docs, ADR, OpenSpec, commits, PR) restent en français (`AGENTS.md:9`). Conséquence sur les cliquets : environ 181 fichiers comptent « hors langue » au départ ; ils restent tolérés par le compteur de leur module (D3) et basculent quand on les retouche ; seuls les mixtes (une trentaine) sont nommés.

**Option B : langue par module** (français pour `minos-bootstrap` et `minos-domain`, anglais ailleurs, ou choix du propriétaire). Le compteur hors langue part plus bas dans ces deux modules ; en contrepartie, une classe déplacée d'un module à l'autre change de langue cible, et la règle ne s'énonce plus en une phrase.

**Option C : français partout sauf `minos-api`.** Aligné sur la langue des artefacts ; le compteur hors langue part d'environ 300 fichiers (268 entièrement anglais plus les mixtes), le plus gros volume à reprendre.

Dans tous les cas, la langue de chaque module est déclarée dans **un seul fichier** lu par le gate ; sous A, tous les modules y portent la même valeur. **Sous-décision** : inclure `minos-intellij` (1 fichier avec du français, `task/MinosBackgroundTask.java`, aucun mixte) ; recommandé oui, puisque le gate ne lit que des sources et ne dépend pas de Gradle.

### D2. Critère « mixte » du gate

Le gate ne lit que les commentaires (`//`, blocs, Javadoc), après décodage des échappements `\uXXXX` et saut des littéraux de chaîne et de caractère et des blocs de texte : mêmes règles lexicales que `check-private-io.py:149-154`, mais en gardant les commentaires au lieu du code. Les balises `{@code …}`, `{@link …}`, le contenu de `<code>` et `<pre>`, les balises HTML, les segments entre guillemets ou accents graves, les noms de paramètres et les lignes `@see`, `@author`, `@since` sont retirés avant classement (cas `McpArgumentBounds.java:53`). Chaque commentaire est découpé en paragraphes (ligne vide, `<p>`, balise de bloc ; lignes `//` consécutives réunies). Un paragraphe est français si son score français (mots d'une liste fermée française plus mots portant une lettre accentuée française) l'emporte et vaut au moins 2, ou au moins 1 avec un mot accentué ; anglais si son score anglais (mots d'une liste fermée anglaise) l'emporte et vaut au moins 2 ; sinon il n'est pas classé. Seuils et listes sont écrits dans le gate et couverts par son auto-test (mots ambigus de la tâche 1.1). Un fichier est **mixte** s'il a au moins un paragraphe de chaque langue ; il est **hors langue** s'il a au moins un paragraphe dans la langue que son module ne déclare pas.

Le critère est volontairement grossier : il décide du mélange et compte des fichiers, sans prétendre décider de la langue d'une phrase.

### D3. Deux cliquets, fail-closed, qui tolèrent les déplacements

`scripts/quality/check-comment-language.py` (nouveau, construit sur `gate_cli.py`, option `--root`, `--self-test` sur arbres temporaires) lit `scripts/quality/comment-language.json` (nouveau) :

- `languages` : langue de chaque module du reactor (`minos-*` ayant un `src/main/java`, plugin selon D1).
- `outOfLanguageMaximum` : par module, le nombre maximal de fichiers hors langue. Le gate échoue si le décompte dépasse le maximum (fichier hors langue ajouté) **et** s'il lui est inférieur (maximum à abaisser dans la même PR, valeur donnée par le message) : le compteur ne fait que baisser. Il compte des fichiers, pas des chemins : un déplacement dans le module ne le change pas ; un déplacement entre modules baisse l'un et monte l'autre, et la PR qui déplace ajuste les deux (somme constante).
- `milestoneCommentMaximum` : par module, le nombre maximal de fichiers dont un commentaire cite un jalon (D6), même cliquet que `outOfLanguageMaximum`.
- `mixed` : les fichiers mixtes tolérés, **nommés par leur nom de fichier** (`IndexingRunExecutor.java`) avec une justification, et non par leur chemin : un déplacement de package ou de module ne touche pas la liste.

Un module dont un maximum vaut 0 n'a pas d'entrée pour ce maximum ; une entrée qui nomme un module absent échoue.

Le gate échoue si : un fichier mixte n'est pas listé ; une entrée ne correspond à aucun fichier de production (entrée morte) ; une entrée correspond à plusieurs fichiers (nom ambigu) ; un fichier listé n'est plus mixte ; une entrée n'a pas de justification ; un module n'a pas de langue déclarée, ou un module déclaré n'existe pas ; une langue n'est ni `fr` ni `en` ; un compteur est dépassé ou trop haut ; une source ou la déclaration ne se lit pas (fail-closed). Le parcours passe par `gate_cli.repository_files` (jamais `rglob` depuis la racine) ; les chemins sont affichés avec `/` ; chaque échec de mélange nomme le fichier et une ligne de chaque langue. Les valeurs initiales sont le relevé du gate lui-même au HEAD de la tâche 2.2, pas les chiffres de l'audit ni ceux de ce design.

### D4. Reprise à la main, par lots relisibles

Les fichiers mixtes sont repris par lots de quelques fichiers par PR, commentaires seulement, un module à la fois. Aucun outil de traduction n'est appliqué en masse. Un commentaire dont le sens est incertain est gardé et signalé plutôt que réécrit. Un fichier mixte repris dans la langue déclarée sort aussi du compteur hors langue : le maximum de son module baisse dans la même PR.

### D5. Les deux textes disent la même chose

`CONTRIBUTING.md` reçoit la règle ; la phrase de `AGENTS.md:9` sur la Javadoc est alignée dans la même tâche ; `.claude/rules/java-production.md`, qui ne dit rien de la langue, reçoit un renvoi d'une ligne, et `.claude/rules/plugin-intellij.md` aussi puisque le plugin est inclus. `CONTRIBUTING.md` est entièrement rédigé en anglais (fichier public) : la section « Comment language » y est écrite en anglais pour ne pas mêler deux langues dans ce fichier ; les artefacts du dépôt restent en français (`AGENTS.md`).

### D6. Commentaires citant un jalon — **rattachés à ce changement (décision du 2026-10-24)**

`retirer-les-jalons-des-identifiants` ne réécrit aucun commentaire et renvoie ici les commentaires Java qui citent un jalon (111 lignes dans 98 fichiers au HEAD `ebee335a`, motif `\bM[0-9]+\b`). Même mécanisme de cliquet, sans campagne : le gate compte par module les fichiers dont un commentaire cite un jalon (`milestoneCommentMaximum`), compteur toléré qui ne peut que baisser ; un fichier repris (lot « mixtes » ou toute autre retouche de ses commentaires) perd aussi ses mentions de jalon : la phrase dit ce que fait le code, sans le jalon, et le maximum de son module baisse dans le même commit. Le gate ne sait pas qu'un fichier a été repris : cette consigne est procédurale (`CONTRIBUTING.md`), le compteur en mesure l'effet. Les chaînes publiques qui citent un jalon (littéraux) restent hors périmètre (D3 de `retirer-les-jalons-des-identifiants`).

## Recouvrements avec les changements actifs

Relevé par `grep -rw <NomDeClasse> openspec/changes/<changement>` pour chacun des 29 fichiers mixtes du HEAD `5ce7b79b` (non rejoué pour les cinq fichiers ajoutés au HEAD `ebee335a`) :

| Changement | Fichiers mixtes cités | Effet sur les cliquets |
|---|---|---|
| `decouper-les-methodes-d-analyse-et-d-ingestion` | `MinosApplicationAssembler`, `IndexingLifecycleService`, `IndexingRunExecutor` | une classe extraite qui emporte un commentaire français à côté d'anglais est un fichier mixte non listé : le gate le nomme ; la PR d'extraction écrit les commentaires déplacés dans la langue déclarée |
| `decouper-les-classes-de-plus-de-800-lignes` | aucun (`PrivateLocalStorage.java` et `ManagedScipProviderRuntimeManager.java` n'ont aucune ligne de commentaire accentuée) | aucun, tant que les classes extraites gardent des commentaires anglais |
| `retirer-les-jalons-des-identifiants` | `ProjectFingerprintService`, `ScipProjectSnapshotLifecycle` | un renommage de fichier renomme l'entrée `mixed` dans la même PR. Ce changement renvoie ses 111 lignes de commentaires citant un jalon vers celui-ci (`retirer-les-jalons-des-identifiants/proposal.md:40`), qui les prend en charge par le cliquet `milestoneCommentMaximum` (D6) |
| `mutualiser-les-caches-lru-et-nommer-les-additions-bornees` | `RuntimeIntelligenceService`, `FileSymbolSnapshotStore`, `PostgresCodeKnowledgeSnapshotStore` | modification en place : noms inchangés ; un commentaire nouveau suit la langue déclarée |
| `faire-consommer-l-indexation-aux-surfaces` | `MinosApplication`, `DockerRuntimeBootstrap`, `MinosLauncher`, `ProjectCommand`, `IndexingRuntimePorts`, `UnreadableRegistryException` | un déplacement entre modules ajuste deux maximums `outOfLanguageMaximum` ; la liste `mixed` n'est pas touchée |
| `exposer-un-port-de-lecture-de-snapshot`, `lire-le-registre-sans-ecrire` | `RuntimeIntelligenceService`, `FileSymbolSnapshotStore`, `PostgresCodeKnowledgeSnapshotStore`, `ScipProjectSnapshotLifecycle`, `LocalProjectRegistry` | modification en place ou déplacement : mêmes règles |

Ordre : indépendant. Si ce changement fusionne avant les autres, leurs PR rencontrent le gate par `run_gates.py` (qui lit le job `invariants`) et le message d'échec dit quoi ajuster ; s'il fusionne après, le relevé de la tâche 2.2 part de leur état. Les lots de reprise (section 3) évitent un fichier qu'une PR ouverte d'un autre changement modifie. Au 2026-10-24, la branche de ce changement est empilée sur #553, #555, #556 et #557 (`decouper-les-methodes-d-analyse-et-d-ingestion`, `calculer-le-rang-de-symbole-une-fois`, `mutualiser-les-caches-lru-et-nommer-les-additions-bornees`, `retirer-les-jalons-des-identifiants`) : leurs modifications de `IndexingRunExecutor`, `RuntimeIntelligenceService`, `FileSymbolSnapshotStore` et `PostgresCodeKnowledgeSnapshotStore` sont déjà dans la base des lots, qui ne créent donc pas de conflit avec elles.

## Direction des dépendances

Sans objet : aucun import, aucun module (ADR 0022 non concerné). Le gate est un script Python de `scripts/quality`, bibliothèque standard seule.

## Qualification des capacités

Aucune capacité produit. Le gate est **qualifié** pour détecter le mélange et compter les fichiers hors langue selon le critère D2, **non supporté** pour décider de la langue d'une phrase sans accent ni mot des listes.

## Windows et Linux

Le gate ouvre les sources en UTF-8 explicite : la page de code par défaut d'un poste Windows et la locale d'un shell (`LC_ALL=C` contre `C.UTF-8`, voir la méthode) ne changent pas le décompte, car il compare des caractères décodés et jamais des octets. Ses sorties normalisent les chemins en `/` et restent en ASCII ou sous `PYTHONUTF8=1`. L'auto-test contient un cas à lettre accentuée et un cas à `§` pour le prouver sur les deux systèmes ; le job `invariants` tourne sur Ubuntu, la preuve Windows est le rejeu local de la tâche 2.2.

## Risks / Trade-offs

- **Faux positifs et faux négatifs du critère** → un commentaire français sans accent ni mot de la liste passe ; un nom propre accentué dans un commentaire anglais déclenche. Les listes tolérées absorbent les cas existants, l'auto-test fige les cas connus.
- **Gates littéraux** → 21 fichiers mixtes sont cités par un script ; la tâche 1.2 n'a trouvé aucune chaîne exigée dans un commentaire ; chaque lot rejoue `run_gates.py`.
- **Friction du compteur** → abaisser un maximum dans chaque PR qui retire du français est voulu (même règle que les autres cliquets : une entrée obsolète échoue) ; le message d'échec donne la nouvelle valeur.
- **Commentaires citant un jalon** → pris en charge ici (D6) par un compteur décroissant ; ils disparaissent au fil des reprises, pas par campagne : la Javadoc publique de `minos-api` (11 fichiers) en garde tant qu'elle n'est pas retouchée.
- **Sous l'option A**, la règle écrite et l'état du dépôt divergent tant que les fichiers français ne sont pas repris : `CONTRIBUTING.md` le dit (règle pour tout commentaire nouveau ou modifié ; le compteur par module mesure l'écart).

## Non vérifié

À la spécification, aucun build, test ni gate exécuté. À la mise en œuvre (2026-10-24), sous Windows seulement : gate, auto-test, `run_gates.py`, `openspec validate`, build Maven (tâches). Non mesurés : la stabilité réelle de `instanceHash` après modification de commentaires (seule la docstring de `audit-report-summary.py:124` est relue ; `code-audit.yml` n'est pas lancé) ; le décompte sous Linux (job `invariants` de la PR) ; la validité HTML de la Javadoc reprise par l'outil `javadoc` (aucun module repris ne génère sa Javadoc au build ; `minos-api` n'est pas touché) ; la qualité des traductions au-delà de la relecture, faite fichier par fichier sans outil de traduction.
