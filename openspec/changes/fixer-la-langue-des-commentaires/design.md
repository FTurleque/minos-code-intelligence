# Design

## Context

Sprint 11 « Hygiène de fond » de l'audit du 10 octobre 2026 : **AUD-QUA-11** (Q18 de l'audit de septembre, toujours ouvert). HEAD analysé : `5ce7b79b` (`develop` du 2026-10-22). Motivation : voir `proposal.md` (Why).

## État vérifié au HEAD

### Méthode de mesure (reproductible)

Toutes les mesures portent sur les fichiers suivis par git : `git ls-files 'minos-*/src/main/**/*.java'`, lus en **UTF-8 explicite**. Deux critères sont appliqués au même ensemble :

1. **Commande de l'audit** : `grep -lE '^\s*(\*|//).*[éèàùêç]'` sur ces fichiers, lancée avec `LC_ALL=C.UTF-8`. Sous `LC_ALL=C`, la classe de caractères est lue octet par octet et la même commande compte **176** fichiers au lieu de 168 : par exemple `MinosApplicationMcpBackend.java:120` (« ADR 0039 §6 ») est retenu à cause d'un octet de `§`. Le décompte de l'audit dépend donc de la locale. « Mixte » selon l'audit : un de ces fichiers qui a aussi une ligne de commentaire contenant `the`, `is`, `never` ou `when` (mot entier).
2. **Critère indicatif par commentaire** (script Python de lecture seule, hors dépôt) : extraction des commentaires `//`, `/* */` et Javadoc après saut des littéraux de chaîne, de caractère et des blocs de texte ; retrait des balises `{@…}`, des balises HTML et des noms de `@param`/`@throws` ; un commentaire est français s'il contient une lettre accentuée ou au moins deux mots d'une courte liste française (`le`, `la`, `les`, `des`, `est`, `pour`, `dans`…), anglais s'il contient au moins deux mots d'une courte liste anglaise (`the`, `is`, `when`, `returns`, `with`…), la langue la plus représentée tranchant quand il a les deux. Un fichier est **mixte** s'il a au moins un commentaire de chaque langue. Ce n'est pas le critère du gate (D2), fixé et testé en tâche 2.1 : il sert à borner l'écart entre heuristiques.

### Décomptes au HEAD

| Module | Fichiers `src/main` | Français (audit) | Français (indicatif) | Mixtes (audit) | Mixtes (indicatif) |
|---|---|---|---|---|---|
| `minos-api` | 13 | 0 | 0 | 0 | 0 |
| `minos-app` | 7 | 1 | 1 | 0 | 0 |
| `minos-application` | 109 | 53 | 54 | 5 | 7 |
| `minos-bootstrap` | 6 | 5 | 6 | 0 | 0 |
| `minos-cli` | 39 | 11 | 16 | 2 | 3 |
| `minos-domain` | 37 | 19 | 20 | 0 | 0 |
| `minos-engine` | 168 | 56 | 59 | 6 | 8 |
| `minos-integration-git` | 7 | 0 | 0 | 0 | 0 |
| `minos-intellij` (plugin, Gradle) | 29 | 1 | 1 | 0 | 0 |
| `minos-mcp` | 9 | 1 | 0 | 1 | 0 |
| `minos-nexus` | 4 | 0 | 0 | 0 | 0 |
| `minos-provider-scip` | 42 | 14 | 16 | 4 | 6 |
| `minos-runtime-local` | 40 | 4 | 5 | 1 | 2 |
| `minos-storage-local` | 31 | 2 | 2 | 2 | 2 |
| `minos-storage-postgresql` | 19 | 1 | 1 | 1 | 1 |
| **Total** | **560** (531 hors plugin) | **168** (167 hors plugin) | **181** | **22** | **29** |

Au critère indicatif, 268 fichiers n'ont que des commentaires anglais et 111 n'ont aucun commentaire classable. L'audit annonçait 170 fichiers sur 547 et 27 mixtes. Les deux heuristiques ne s'accordent que sur 19 fichiers mixtes : l'audit retient en plus `McpArgumentBounds.java` (lettre `é` citée dans un `{@code "é"}`, ligne 53 : faux positif), `StorageBackends.java` et `IndexStateStore.java` ; le critère indicatif retient en plus dix fichiers dont l'en-tête Javadoc est français et le reste anglais (par exemple `ArchitectureDependencyGraph.java:12`, `CommandLocator.java:20`, `ScipSymbolSnapshotReport.java:5`, `UnreadableRegistryException.java:9`).

Fichiers mixtes selon le critère indicatif (liste de départ de la tâche 1.1, à confirmer par le critère du gate) :

- `minos-application` : `application/MinosApplication.java`, `application/MinosApplicationAssembler.java`, `application/dynamic/RuntimeIntelligenceService.java`, `architecture/ArchitectureDependencyGraph.java`, `context/LocalSourceReader.java`, `output/CodeIntelligenceResultRenderer.java`, `program/analysis/AdvancedImpactService.java`.
- `minos-cli` : `cli/DockerRuntimeBootstrap.java`, `cli/MinosLauncher.java`, `cli/command/ProjectCommand.java`.
- `minos-engine` : `incremental/ProjectFingerprintService.java`, `io/StaleScratchReclamation.java`, `orchestration/IndexingLifecycleService.java`, `orchestration/IndexingResumePlanner.java`, `orchestration/IndexingRunExecutor.java`, `orchestration/IndexingRuntimePorts.java`, `orchestration/ScipSymbolSnapshotReport.java`, `registry/UnreadableRegistryException.java`.
- `minos-provider-scip` : `ingestion/ScipIndexReader.java`, `ingestion/ScipSymbolCatalog.java`, `ingestion/ScipSymbolNormalizer.java`, `runtime/ScipProjectSnapshotLifecycle.java`, `runtime/plan/ScipJavaProcessPlanFactory.java`, `runtime/plan/ScipTypeScriptProcessPlanFactory.java`.
- `minos-runtime-local` : `process/CommandLocator.java`, `process/IndexerProcessPlanFactory.java`.
- `minos-storage-local` : `registry/LocalProjectRegistry.java`, `store/FileSymbolSnapshotStore.java`.
- `minos-storage-postgresql` : `snapshot/PostgresCodeKnowledgeSnapshotStore.java`.

Aucun nom de fichier n'est en double parmi les 560 sources de production (`git ls-files 'minos-*/src/main/**/*.java' | xargs -n1 basename | sort | uniq -d` ne sort rien).

### Exemples de l'audit relus

- `minos-mcp/src/main/java/com/minos/mcp/MinosMcpServer.java` : aucune lettre accentuée ; l'exemple « Serveur MCP local » n'existe pas au HEAD.
- `minos-engine/src/main/java/com/minos/orchestration/IndexingRunExecutor.java:123` : « Rouvre le run interrompu … » au milieu de Javadoc anglaise (4 commentaires français, 42 anglais au critère indicatif).
- `FileResumableRunMarkers.java` n'existe pas au HEAD ; la classe correspondante est `minos-bootstrap/src/main/java/com/minos/bootstrap/RunDirectoryResumableRunMarkers.java`, qu'aucun des deux critères ne tient pour mixte.

### Textes et gates existants

- `CONTRIBUTING.md` : aucune occurrence de « langue », « français », « anglais », « language », « English » (`grep -niE`). La seule règle écrite est `AGENTS.md:9` : identifiants et messages de code suivent la convention du module touché, Javadoc sans mélange de langues dans un même fichier. `.claude/rules/java-production.md` et `.claude/rules/plugin-intellij.md` ne disent rien de la langue des commentaires.
- **Aucun gate ne contrôle la langue des commentaires** : `grep -rlniE "comment.?language|langue des commentaires|accent" scripts/ --include=*.py` ne trouve rien. `scripts/architecture/check-private-io.py:149` (`decode_unicode_escapes`) et `:154` (`strip_comments_and_literals`) font l'inverse du besoin : ils effacent commentaires et littéraux pour ne garder que le code.
- Squelette commun : `scripts/quality/gate_cli.py` (`repository_files`, `run`, `report_failures`). `scripts/quality/check-ci-wiring.py` (règle 2, docstring lignes 11-12) exige que chaque `scripts/**/test_*.py` et chaque `--self-test` soit exécuté par le job `invariants` de `.github/workflows/pr-ci.yml`, que `.claude/scripts/run_gates.py:31` (`invariants_commands`) rejoue en local.
- **Baseline SpotBugs insensible aux lignes** (relu, non exécuté) : `scripts/quality/audit-report-summary.py:121-131` identifie une alerte par son `instanceHash`, « que SpotBugs conserve quand les lignes bougent » selon sa docstring ; la comparaison ne tourne que dans `.github/workflows/code-audit.yml:87` (déclenchement manuel, hors checks exigés). Les `SourceLine start/end` de `quality/spotbugs-baseline/*.xml` ne servent qu'à l'affichage.
- `scripts/architecture/private-io-allowlist.json` : entrées par fichier, méthode et plafond, sans numéro de ligne ; le gate ignore les commentaires.
- **Gates qui citent un fichier mixte** (`grep -rlw <NomDeClasse> scripts --include=*.py`, hors `history/`) : 18 des 29 fichiers, dont `CommandLocator`, `FileSymbolSnapshotStore` et `MinosApplication` (5 scripts chacun), `ScipJavaProcessPlanFactory` (3). Scripts concernés : `architecture/check-module-boundaries.py`, `quality/check-jacoco.py`, `quality/check-partial-result-consumers.py`, `quality/check-hosted-control-plane-consistency.py`, `quality/check-runtime-dynamic-consistency.py`, `quality/check-semantic-retrieval-consistency.py`, `quality/check-polyglot-provider-consistency.py`, `remediation/check-p0-p2.py`, `remediation/check-mnd.py`, `remediation/check-mne.py`, `remediation/check-post-mne.py`. **Non vérifié** : si l'une des chaînes exigées se trouve dans un commentaire (tâche 1.2).

### Écarts avec l'action proposée par l'audit

1. **Le décompte de l'audit n'est pas reproductible** : il dépend de la locale (168 ou 176 fichiers) et son heuristique de mots diverge sur 13 des 32 fichiers retenus par l'un ou l'autre critère. Le gate a besoin d'un critère écrit et testé (D2), et sa liste initiale est son propre relevé, pas les 27 de l'audit.
2. **« Fixer la langue par module » suppose un choix que les chiffres ne font pas** : `minos-bootstrap` (5 ou 6 fichiers sur 6) et `minos-domain` (19 ou 20 sur 37) ont une majorité de fichiers avec du français, `minos-application` est à moitié (53 ou 54 sur 109), `minos-api` n'en a aucun. C'est la décision D1.
3. **Une langue déclarée qu'aucun contrôle ne lit n'est pas une règle** : la déclaration reçoit un effet vérifiable (D3, compteur par module).
4. **`CONTRIBUTING.md` n'est pas le seul texte à tenir** : `AGENTS.md:9` énonce déjà une règle ; les deux doivent dire la même chose (D5).
5. **Retoucher un commentaire est sans effet sur les baselines** : ni la baseline SpotBugs (`instanceHash`) ni la liste des E/S privées ne dépendent des lignes. Le risque restant est un gate littéral qui exigerait une chaîne écrite dans un commentaire (tâche 1.2).
6. **Coût SonarCloud nul** : des commentaires ne sont pas du code à couvrir.

## Goals / Non-Goals

**Goals** : une règle écrite, lisible par un contributeur et par un gate ; plus aucun nouveau fichier mixte ; un nombre de fichiers hors de la langue déclarée qui ne peut que baisser ; les fichiers mixtes existants résorbés sans campagne.

**Non-Goals** : traduire le code ; juger la qualité des commentaires ; détecter la langue par un modèle ou une dépendance (bibliothèque standard Python seule).

## Decisions

### D1. Langue de référence — **décision en attente du propriétaire**

**Option A (recommandée) : anglais pour les commentaires et la Javadoc de tous les modules de code**, plugin compris. Une seule règle, sans table à retenir ; déjà vraie pour `minos-api` (Javadoc publique) et pour 379 des 560 fichiers (aucun commentaire français au critère indicatif) ; les artefacts du dépôt (docs, ADR, OpenSpec, commits, PR) restent en français (`AGENTS.md:9`). Conséquence sur les cliquets : environ 181 fichiers comptent « hors langue » au départ ; ils restent tolérés par le compteur de leur module (D3) et basculent quand on les retouche ; seuls les mixtes (une trentaine) sont nommés.

**Option B : langue par module** (français pour `minos-bootstrap` et `minos-domain`, anglais ailleurs, ou choix du propriétaire). Le compteur hors langue part plus bas dans ces deux modules ; en contrepartie, une classe déplacée d'un module à l'autre change de langue cible, et la règle ne s'énonce plus en une phrase.

**Option C : français partout sauf `minos-api`.** Aligné sur la langue des artefacts ; le compteur hors langue part d'environ 300 fichiers (268 entièrement anglais plus les mixtes), le plus gros volume à reprendre.

Dans tous les cas, la langue de chaque module est déclarée dans **un seul fichier** lu par le gate ; sous A, tous les modules y portent la même valeur. **Sous-décision** : inclure `minos-intellij` (1 fichier avec du français, `task/MinosBackgroundTask.java`, aucun mixte) ; recommandé oui, puisque le gate ne lit que des sources et ne dépend pas de Gradle.

### D2. Critère « mixte » du gate

Le gate ne lit que les commentaires (`//`, blocs, Javadoc), après décodage des échappements `\uXXXX` et saut des littéraux de chaîne et de caractère et des blocs de texte : mêmes règles lexicales que `check-private-io.py:149-154`, mais en gardant les commentaires au lieu du code. Les balises `{@code …}`, `{@link …}`, les balises HTML et les noms de paramètres sont retirés avant classement (cas `McpArgumentBounds.java:53`). Un commentaire est français s'il contient une lettre accentuée ou des mots d'une liste fermée française, anglais s'il contient des mots d'une liste fermée anglaise ; seuils et listes sont écrits dans le gate et couverts par son auto-test (un cas par mot ambigu relevé en tâche 1.1 : noms propres, termes techniques). Un fichier est **mixte** s'il a au moins un commentaire de chaque langue ; il est **hors langue** s'il a au moins un commentaire dans la langue que son module ne déclare pas.

Le critère est volontairement grossier : il décide du mélange et compte des fichiers, sans prétendre décider de la langue d'une phrase.

### D3. Deux cliquets, fail-closed, qui tolèrent les déplacements

`scripts/quality/check-comment-language.py` (nouveau, construit sur `gate_cli.py`, option `--root`, `--self-test` sur arbres temporaires) lit `scripts/quality/comment-language.json` (nouveau) :

- `languages` : langue de chaque module du reactor (`minos-*` ayant un `src/main/java`, plugin selon D1).
- `outOfLanguageMaximum` : par module, le nombre maximal de fichiers hors langue. Le gate échoue si le décompte dépasse le maximum (fichier hors langue ajouté) **et** s'il lui est inférieur (maximum à abaisser dans la même PR, valeur donnée par le message) : le compteur ne fait que baisser. Il compte des fichiers, pas des chemins : un déplacement dans le module ne le change pas ; un déplacement entre modules baisse l'un et monte l'autre, et la PR qui déplace ajuste les deux (somme constante).
- `mixed` : les fichiers mixtes tolérés, **nommés par leur nom de fichier** (`IndexingRunExecutor.java`) avec une justification, et non par leur chemin : un déplacement de package ou de module ne touche pas la liste.

Le gate échoue si : un fichier mixte n'est pas listé ; une entrée ne correspond à aucun fichier de production (entrée morte) ; une entrée correspond à plusieurs fichiers (nom ambigu) ; un fichier listé n'est plus mixte ; un module n'a pas de langue déclarée ; une langue n'est ni `fr` ni `en` ; un compteur est dépassé ou trop haut. Le parcours passe par `gate_cli.repository_files` (jamais `rglob` depuis la racine) ; les chemins sont affichés avec `/` ; chaque échec de mélange nomme le fichier et une ligne de chaque langue. Les valeurs initiales sont le relevé du gate lui-même au HEAD de la tâche 2.2, pas les chiffres de l'audit ni ceux de ce design.

### D4. Reprise à la main, par lots relisibles

Les fichiers mixtes sont repris par lots de quelques fichiers par PR, commentaires seulement, un module à la fois. Aucun outil de traduction n'est appliqué en masse. Un commentaire dont le sens est incertain est gardé et signalé plutôt que réécrit. Un fichier mixte repris dans la langue déclarée sort aussi du compteur hors langue : le maximum de son module baisse dans la même PR.

### D5. Les deux textes disent la même chose

`CONTRIBUTING.md` reçoit la règle ; la phrase de `AGENTS.md:9` sur la Javadoc est alignée dans la même tâche ; `.claude/rules/java-production.md`, qui ne dit rien de la langue, reçoit un renvoi d'une ligne.

## Recouvrements avec les changements actifs

Relevé par `grep -rw <NomDeClasse> openspec/changes/<changement>` pour chacun des 29 fichiers mixtes :

| Changement | Fichiers mixtes cités | Effet sur les cliquets |
|---|---|---|
| `decouper-les-methodes-d-analyse-et-d-ingestion` | `MinosApplicationAssembler`, `IndexingLifecycleService`, `IndexingRunExecutor` | une classe extraite qui emporte un commentaire français à côté d'anglais est un fichier mixte non listé : le gate le nomme ; la PR d'extraction écrit les commentaires déplacés dans la langue déclarée |
| `decouper-les-classes-de-plus-de-800-lignes` | aucun (`PrivateLocalStorage.java` et `ManagedScipProviderRuntimeManager.java` n'ont aucune ligne de commentaire accentuée) | aucun, tant que les classes extraites gardent des commentaires anglais |
| `retirer-les-jalons-des-identifiants` | `ProjectFingerprintService`, `ScipProjectSnapshotLifecycle` | un renommage de fichier renomme l'entrée `mixed` dans la même PR. Ce changement renvoie ses 74 lignes de commentaires citant un jalon vers celui-ci (`retirer-les-jalons-des-identifiants/proposal.md:39`), qui les déclare hors périmètre |
| `mutualiser-les-caches-lru-et-nommer-les-additions-bornees` | `RuntimeIntelligenceService`, `FileSymbolSnapshotStore`, `PostgresCodeKnowledgeSnapshotStore` | modification en place : noms inchangés ; un commentaire nouveau suit la langue déclarée |
| `faire-consommer-l-indexation-aux-surfaces` | `MinosApplication`, `DockerRuntimeBootstrap`, `MinosLauncher`, `ProjectCommand`, `IndexingRuntimePorts`, `UnreadableRegistryException` | un déplacement entre modules ajuste deux maximums `outOfLanguageMaximum` ; la liste `mixed` n'est pas touchée |
| `exposer-un-port-de-lecture-de-snapshot`, `lire-le-registre-sans-ecrire` | `RuntimeIntelligenceService`, `FileSymbolSnapshotStore`, `PostgresCodeKnowledgeSnapshotStore`, `ScipProjectSnapshotLifecycle`, `LocalProjectRegistry` | modification en place ou déplacement : mêmes règles |

Ordre : indépendant. Si ce changement fusionne avant les autres, leurs PR rencontrent le gate par `run_gates.py` (qui lit le job `invariants`) et le message d'échec dit quoi ajuster ; s'il fusionne après, le relevé de la tâche 2.2 part de leur état. Les lots de reprise (section 3) évitent un fichier qu'une PR ouverte d'un autre changement modifie.

## Direction des dépendances

Sans objet : aucun import, aucun module (ADR 0022 non concerné). Le gate est un script Python de `scripts/quality`, bibliothèque standard seule.

## Qualification des capacités

Aucune capacité produit. Le gate est **qualifié** pour détecter le mélange et compter les fichiers hors langue selon le critère D2, **non supporté** pour décider de la langue d'une phrase sans accent ni mot des listes.

## Windows et Linux

Le gate ouvre les sources en UTF-8 explicite : la page de code par défaut d'un poste Windows et la locale d'un shell (`LC_ALL=C` contre `C.UTF-8`, voir la méthode) ne changent pas le décompte, car il compare des caractères décodés et jamais des octets. Ses sorties normalisent les chemins en `/` et restent en ASCII ou sous `PYTHONUTF8=1`. L'auto-test contient un cas à lettre accentuée et un cas à `§` pour le prouver sur les deux systèmes ; le job `invariants` tourne sur Ubuntu, la preuve Windows est le rejeu local de la tâche 2.2.

## Risks / Trade-offs

- **Faux positifs et faux négatifs du critère** → un commentaire français sans accent ni mot de la liste passe ; un nom propre accentué dans un commentaire anglais déclenche. Les listes tolérées absorbent les cas existants, l'auto-test fige les cas connus.
- **Gates littéraux** → 18 fichiers mixtes sont cités par un script : la tâche 1.2 mesure si une chaîne exigée est dans un commentaire ; chaque lot rejoue `run_gates.py`.
- **Friction du compteur** → abaisser un maximum dans chaque PR qui retire du français est voulu (même règle que les autres cliquets : une entrée obsolète échoue) ; le message d'échec donne la nouvelle valeur.
- **Commentaires citant un jalon** → déclarés hors périmètre ici et renvoyés ici par `retirer-les-jalons-des-identifiants` : personne ne les prend en charge tant que le propriétaire ne tranche pas.
- **Sous l'option A**, la règle écrite et l'état du dépôt divergent tant que les fichiers français ne sont pas repris : `CONTRIBUTING.md` le dit (règle pour tout commentaire nouveau ou modifié ; le compteur par module mesure l'écart).

## Non vérifié

Aucun build, test ni gate exécuté. Non mesurés : la présence d'une chaîne exigée par un gate littéral dans un commentaire d'un fichier mixte ; la stabilité réelle de `instanceHash` après modification de commentaires (seule la docstring de `audit-report-summary.py:124` est relue) ; le décompte sous Linux (mesures faites sous Windows, Git Bash et Python).
