# Design

## Context

Sprint 10 de l'audit du 10 octobre 2026 : **AUD-ARC-01, -05, -08, -11, -12** (AUD-ARC-07 est traité par le changement de décisions). HEAD analysé : `949e1569` (`develop`). `check-module-boundaries.py` est vert au HEAD : 14 modules, 527 sources, 60 packages, 0 cycle, 12 types en liste A10.

## État vérifié au HEAD

### AUD-ARC-01 — confirmé

| Surface | Imports du moteur | Fichiers | Types distincts |
|---|---|---|---|
| `minos-cli` | 76 | 21 | 52 |
| `minos-api` | 16 | 5 | 16 |
| `minos-nexus` | 6 | 1 | 6 |
| `minos-mcp` | 4 (l'audit : 5) | 3 | 4 |

Total 102 imports dans 30 fichiers (l'audit : 103). `new IndexingLifecycleService` reste unique en production : `minos-cli/src/main/java/com/minos/cli/operations/LocalAutonomousIndexOperations.java:166` (fichier déplacé par le rangement de la CLI). Le prérequis AUD-ARC-10 est fermé ; AUD-ARC-02 (sprint 9) n'est pas implémenté.

### AUD-ARC-05 — confirmé

`minos-engine` : 169 fichiers, 16 928 lignes ; 70 classes (12 135 lignes), 33 interfaces (1 039). `orchestration` a grossi depuis l'audit (46 fichiers / 5 213 lignes contre 40 / 4 463, effet de `casser-les-cycles-de-packages`). `minos-engine/src/main/java/com/minos/io/PrivateLocalStorage.java:634-635` : `new ProcessBuilder(… "icacls.exe" …)`, **seul** `ProcessBuilder` du moteur ; aucun dans `minos-application` ni `minos-domain`. Aucun garde ne cite `ProcessBuilder`.

### AUD-ARC-08 — confirmé, un couplage de plus que l'audit

- `minos-provider-scip` → `minos-storage-local` : 1 import, 4 instanciations (`ScipProjectSnapshotLifecycle.java:21,41,82,112,131`).
- `minos-storage-postgresql` → `minos-storage-local` : 5 imports dans 3 fichiers (l'audit : 6), déplacés en sous-packages : `project/PostgresProjectRegistry.java:7`, `snapshot/PostgresCodeKnowledgeSnapshotStore.java:11-12`, `support/PostgresSnapshotPayloadCodec.java:4-5`. **Non compté par l'audit** : deux usages en nom qualifié, sans import, d'un type d'intégrité des snapshots (`PostgresCodeKnowledgeSnapshotStore.java:287,414`).
- `minos-provider-scip` → `minos-runtime-local` : 28 imports dans 12 fichiers, déjà tenus par la liste A10.

### AUD-ARC-11 — confirmé

`minos-app` : 7 fichiers Java, 538 lignes, un fichier de service. `DockerMcpTransport.java:114,190` : exact. `McpLaunchRouteProvider` implémente `com.minos.cli.McpLaunchRoute` : l'arête d'exécution `minos-cli` → `minos-app` passe par un service et n'apparaît ni dans un POM ni dans `docs/architecture/diagrams/module-dependencies.md` (document **généré** par `--write-doc`).

### AUD-ARC-12 — confirmé, décomptes corrigés

23 racines sans préfixe de module (l'audit : 24) : moteur 15, application 6 sur 7, domaine 2 sur 3. **Aucun package n'est partagé entre deux modules** (règle A3 verte) : les « hiérarchies partagées » de l'audit sont des parents lexicaux (`com.minos.program` dans le domaine, `com.minos.program.analysis` dans l'application), que l'ADR 0055 autorise.

**Non vérifié** : aucun build ni ArchUnit exécuté ; `test_check_module_boundaries.py` non rejoué. Les décomptes portent sur les imports et sur les noms qualifiés repérés par recherche de texte ; `architecture-graphe.json`, cité par l'audit, n'a pas été retrouvé dans le dépôt. Le risque de faux positifs d'une reconnaissance au grain du mot sur des noms de types courts n'a pas été simulé (tâche 1.1).

### Écarts avec l'action proposée par l'audit

1. **AUD-ARC-01 — le cliquet va dans le garde Python, pas dans `ModuleArchitectureTest`.** Les précédents (A8, A10) sont des règles Python : elles tournent en secondes dans `invariants`, couvrent le plugin hors reactor et ont un auto-test par règle. L'ADR 0057 § 7 présente ArchUnit comme une option.
2. **AUD-ARC-05 — « `minos-engine` n'appelle ni `ProcessBuilder` ni `Runtime.exec` » est faux aujourd'hui.** La règle ne peut entrer que comme liste fermée contenant `PrivateLocalStorage`. Extraire le mécanisme d'ACL est une conception (la classe est nommée par 9 modules et la primitive est statique), et AUD-SEC-06 réécrit ce même code : hors de ce changement.
3. **AUD-ARC-05 — déplacer `hosted` vers `minos-application` contredit l'ADR 0044 § 2**, qui l'a placé dans le moteur à dessein ; `minos-storage-local` l'importe. Renvoyé au changement de décisions.
4. **AUD-ARC-11 — les deux actions principales sont infaisables telles quelles.** Déplacer le transport dans `minos-mcp` créerait `minos-mcp → minos-cli`, arête interdite et supprimée en M15-S4 (ADR 0022) ; renommer `com.minos.integration.nexus` casse un point d'entrée documenté (`docs/user/java-api.md:324`) et contredit l'ADR 0044. Reste faisable sans décision : **documenter** l'arête d'exécution.
5. **AUD-ARC-12 — une convention appliquée par renommage contredit l'ADR 0044** (déplacement à nom qualifié constant) et casserait des gates littéraux. Faisable sans renommer : interdire toute **nouvelle** racine.

## Goals / Non-Goals

**Goals** : la dette mesurée ne peut plus croître sans une modification visible d'une liste ; chaque liste ne peut que rétrécir ; l'arête d'exécution cachée est écrite et contrôlée.

**Non-Goals** : réduire la dette ; déplacer ou renommer ; trancher les ADR.

## Decisions

### D1. Quatre règles Python sur le modèle de A10

Chaque règle porte une table dans `check-module-boundaries.py`, relue à chaque exécution contre les sources :

- **A11** `ENGINE_TYPES_NAMED_BY_SURFACE` : par surface (`minos-cli`, `minos-api`, `minos-mcp`, `minos-nexus`), l'ensemble des types de `minos-engine` qu'elle nomme (import ou nom qualifié).
- **A12** `PROCESS_LAUNCHERS` : classes de `minos-engine`, `minos-application` et `minos-domain` qui contiennent `ProcessBuilder` ou `Runtime.getRuntime().exec` : `{PrivateLocalStorage}`.
- **A13** `ADAPTER_TYPES_NAMED_BY_ADAPTER` : par couple d'adaptateurs, les types nommés (noms relus au HEAD avant d'être écrits, y compris l'usage en nom qualifié).
- **A14** `PACKAGE_ROOTS_BY_MODULE` : racines `com.minos.<x>` autorisées par module, calculées une fois sur l'existant.

Comportement commun (celui de A10) : un nom hors liste échoue ; une entrée que plus personne n'utilise échoue aussi (le cliquet ne se desserre pas et ne garde pas d'entrée morte) ; une entrée qui nomme un type inexistant échoue. Les tables initiales sont **générées** par une exécution du garde sur le HEAD, puis relues à la main.

Alternative écartée : ArchUnit dans `minos-app`. Plus lent, ne voit pas le plugin, et sa règle au grain module laisserait passer ce que l'on veut refuser.

### D2. Reconnaissance des usages

Imports **et** noms qualifiés complets (le couplage non importé d'AUD-ARC-08 doit être vu). Les commentaires et les chaînes sont ignorés comme pour les règles existantes. Les usages à l'intérieur d'un même package ne sont pas concernés (ce n'est pas une frontière).

### D3. Arêtes d'exécution par service

`render_dependency_document` lit les fichiers `META-INF/services/*` de chaque module : un service dont l'interface appartient au module X et l'implémentation au module Y produit une arête d'exécution « X charge Y ». Elles sont rendues dans une section distincte du document généré (elles ne sont pas des dépendances de compilation et ne changent pas A2). `--check` échoue si le document versionné ne les contient pas.

### D4. Lots

Quatre règles et un rendu dans un même fichier : cinq lots de tâches indépendants, fusionnables ensemble ou en série. Le changement n'est pas découpé en cinq parce que tous modifient les mêmes deux fichiers et que chacun, seul, prend quelques dizaines de lignes.

## Direction des dépendances

Aucune arête créée ni supprimée. Les règles rendent explicite ce que l'ADR 0058 autorise et ce que les ADR 0022 et 0055 veulent voir disparaître.

## Qualification des capacités

Sans objet (aucune capacité produit). Les règles sont qualifiées par leur auto-test (un cas refusé, un cas accepté, un cas « entrée morte »).

## Windows et Linux

Garde Python pur ; chemins construits par `pathlib` ; fichiers lus en UTF-8. Exécuté dans `invariants` (Ubuntu) et sur le poste Windows.

## Risks / Trade-offs

- **Friction** : tout nouvel usage légitime d'un port du moteur par une surface demande d'éditer une liste. C'est l'effet demandé par l'ADR 0058 § 3 (« justification ») ; le message d'erreur dit quoi faire.
- **Listes longues** (52 types pour la CLI) : générées, triées, une par ligne ; leur diff rend la dette lisible.
- **Faux positifs** au grain du mot : mesurés en tâche 1.1 avant de figer la méthode de reconnaissance.
- Les chantiers à venir (SH-05, SH-08, SH-03/06) **réduiront** ces listes : chaque réduction est une ligne supprimée, pas une règle à réécrire.
