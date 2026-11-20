# Proposal

## Why

Constat **AUD-PERF-14** de l'audit `docs/project/audits/2026-11-15/` (sprint 9, sévérité faible, ouvert par la vérification du 19/11, résidu d'AUD-PERF-04) : `SemanticCorpusQueryService` garde la table de symboles de chaque vue dans une `WeakHashMap` indexée par la vue. Une `WeakHashMap` tient ses valeurs par des références fortes et ne purge l'entrée d'une clé collectée qu'au prochain appel de l'une de ses méthodes ; or le service ne touche la map qu'après avoir chargé la vue suivante. Quand le budget des vues évince une vue pour faire place à une autre, les symboles de la vue évincée restent donc retenus pendant tout le chargement et le décodage de la suivante, hors du budget, de toute part `HeapShare` et de la marge de décodage déclarée par DATA-0085. C'est le dépassement que DATA-0081 et DATA-0085 veulent rendre impossible.

## What Changes

- Le service d'exploration ne garde plus de table de symboles hors de la vue : il résout les symboles d'une page par les lectures indexées que la vue fournit déjà (`CodeKnowledgeReader.findSymbolById`, `findFileSymbols`), sans état propre (décision D1, recommandée). Le symbole qui décrit un fichier reste celui de plus petite clé de symbole : les pages rendues ne changent pas.
- La `WeakHashMap` `lookups` disparaît de `SemanticCorpusQueryService` ; la classe interne `SymbolLookup` ne tient plus que le lecteur de la vue, le temps d'une requête.
- `SemanticCorpusQueryServiceTest.anEvictedViewIsNoLongerReachableFromTheService` vérifie aussi qu'un symbole de la vue évincée est collecté sans nouvel appel au service.
- Le point 19 de DATA-0085 dit comment la table est tenue après le changement.

Aucune sortie de l'explorateur, de la CLI, de l'API Java, du MCP, du plugin IntelliJ ni de NEXUS ne change.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `exploration-semantique` : l'exigence « La table de symboles de l'explorateur ne survit pas à sa vue » interdit tout objet hors de la vue qui retienne ses symboles, et exige leur libération sans nouvelle requête.

## Hors périmètre

- Le budget des vues, `QueryViewMemoryBudget`, les parts `HeapShare` et les autres caches du service (`fingerprints`).
- Une mesure du tas retenu sur un vrai snapshot : l'ampleur reste calculée, pas mesurée (design, « Performance et mesure »).
- Les autres constats de l'audit, dont les partiels AUD-DEP-05 (sprint 2) et AUD-ARC-02 (sprint 7).

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| La table est gardée dans une `WeakHashMap` indexée par la vue | [Existant vérifié] | `minos-application/src/main/java/com/minos/application/semantic/SemanticCorpusQueryService.java:79` |
| La vue est chargée avant le premier accès à la map, seul site de `get` et de `put` | [Existant vérifié] | `SemanticCorpusQueryService.java:411`, `:416-423` |
| La table tient les objets `Symbol` du snapshot de la vue | [Existant vérifié] | `SemanticCorpusQueryService.java:727-739` |
| Le test d'éviction ne vérifie que la collecte de la vue | [Existant vérifié] | `minos-application/src/test/java/com/minos/application/semantic/SemanticCorpusQueryServiceTest.java:691-702` |
| Les deux backends construisent leurs vues par `SnapshotReadView.indexed`, donc avec un lecteur en mémoire indexé par identifiant et par fichier | [Existant vérifié] | `minos-storage/src/main/java/com/minos/storage/local/store/FileSymbolSnapshotStore.java:353`, `minos-storage/src/main/java/com/minos/storage/postgresql/snapshot/PostgresCodeKnowledgeSnapshotStore.java:232` |
| Purger la map avant le chargement ne suffit pas sous pression mémoire | [Proposition] | design, écart 1 |
| Le service lit les symboles par la vue, sans table propre | [Proposition] | design, D1 |

## Impact

- **Modules du reactor** : `minos-application` (`SemanticCorpusQueryService` et son test). `minos-engine` inchangé avec D1 ; touché seulement si le propriétaire retient l'option B de D1.
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS, explorateur) : aucun changement de sortie.
- **ADR** : DATA-0085 (`Proposed`) amendé au point 19 ; aucun ADR nouveau.
- **Gates à rejouer** : scope JaCoCo `explorer-surface` (`scripts/quality/check-jacoco.py:60-66`), `check-module-boundaries.py`, `python .claude/scripts/run_gates.py`.
- **Plateformes** : Windows et Linux — comportement de la JVM identique.
- **Documentation durable** : `docs/architecture/diagrams/capacites/exploration-semantique.md` (phrase `:260`, ligne de traçabilité `:298`), DATA-0085.

## Questions ouvertes

Aucune bloquante : D1 a une recommandation.
