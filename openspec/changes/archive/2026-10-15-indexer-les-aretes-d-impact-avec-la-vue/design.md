# Design

## Context

Sprint 4 de l'audit du 10 octobre 2026 : **AUD-PERF-11** (après AUD-PERF-03, ordre interne du sprint) et **AUD-PERF-13**. HEAD analysé : `2d1cc214` (`develop`). `APP` = `minos-application/src/main/java/com/minos`.

## État vérifié au HEAD

### AUD-PERF-11 — confirmé

- `APP/impact/ImpactAnalysisService.java:76` : `incomingRelationships(snapshot, symbolsById)` à chaque `analyze` ; `:199-221` : tri de toutes les relations ; la table `symbolsById` est reconstruite elle aussi (vers `:62`).
- `APP/program/analysis/AdvancedImpactService.java:38`, `:46-48`, et `:95` : `graph.edges().stream().sorted(...)` sur tout le graphe à chaque appel.
- `APP/impact/LocalProjectImpactQuery.java:42` charge le snapshot par `loadActiveKnowledge`, pas la vue de requête.

### AUD-PERF-13 — confirmé

- `APP/application/dynamic/RuntimeIntelligenceService.java:183` : `snapshot.symbols().stream().filter(value -> symbolId.equals(value.id())).findFirst()` ; `:242-245` `activeSnapshot`.
- `minos-engine/src/main/java/com/minos/store/InMemoryCodeKnowledgeStore.java:97-101` : `findSymbolById`.
- C'est le seul site de ce motif dans `src/main`.

**Non mesuré pendant cette analyse** : les durées (15 à 68,5 ms) viennent de l'audit d'architecture de septembre (`docs/audit/archive/2026-09/ARCHI-SUIVI.md`, § A6.3.5).

### Écarts avec l'action proposée par l'audit

1. **« Porter l'index dans `SnapshotQueryView` » ne peut pas se faire par un nouveau composant.** L'ADR 0056 fige les composants de ce record public. L'index est exposé par un **accesseur d'`InMemoryCodeKnowledgeStore`** (que la vue contient déjà), construit paresseusement une fois puis conservé.
2. **L'action oublie le point d'entrée.** Tant que `LocalProjectImpactQuery.java:42` charge le snapshot brut, aucun index de vue n'est disponible : il passe à `loadActiveQueryView`.
3. **Le poids des caches doit suivre.** Un index d'arêtes retenu par une vue en cache est du tas non compté ; le poids de la vue et celui de l'entrée du cache de graphe l'incluent. C'est la raison de l'ordre « AUD-PERF-03 avant AUD-PERF-11 ».
4. **L'ordre des sorties est un contrat.** `tests-lies-et-impact` exige des sorties reproductibles : l'index précalculé doit rendre exactement l'ordre que rend le tri par appel.
5. **Des gates citent les appels actuels** : `check-mnd.py:101-102`, `check-mne.py:106-107`, `check-runtime-dynamic-consistency.py:81-86` ; à relire avant de renommer ou de déplacer.

### Relevé avant modification (tâche 1.1, 2026-10-14, HEAD `9e4a6e61`)

- **Littéraux relus.** `scripts/remediation/check-mnd.py:101-102` exige dans `ProgramGraphService.java` `WeightedGraph`, `evidenceWeight`, `stringWeight`, `maximumWeight` ; `scripts/remediation/check-mne.py:106-107` exige dans `RuntimeIntelligenceService.java` `store.list(project.id(), null, limit)` et `store.list(projectId, snapshotId, MAX_SESSION_RESULTS)` ; le troisième gate est `scripts/quality/check-runtime-dynamic-consistency.py` (pas `remediation/`) : `:81-86` exige des messages et limitations du service et interdit `.publish(`, `ProviderCapability`, `ProjectDiscoveryService`. Aussi relus : `check-p0-p2.py:219-221,305-308` (`ReentrantLock[] buildLocks`, `buildLock.lock()`/`unlock()`, `public CacheStats cacheStats()`) et `check-advanced-provider-consistency.py:97-99`. Aucun de ces littéraux n'est touché par le changement ; les six gates passent avant et après.
- **Référence figée.** Avant toute modification du code de production, deux tests ont écrit la référence au HEAD : `ImpactReferenceReportsTest` (snapshot pseudo-aléatoire à graine fixe de 48 symboles et 360 relations, `ImpactReferenceFixture` ; 40 racines × 3 bornes = 120 rapports) et `AdvancedImpactReferenceReportsTest` (graphe synthétique de 78 nœuds et 260 arêtes sur ces symboles ; 40 racines × 2 bornes = 80 rapports). La référence garde une empreinte SHA-256 du rendu canonique de chaque rapport (ordre compris) : `minos-application/src/test/resources/impact/reference-reports.txt` (SHA-256 du fichier `88535e2e…`) et `advanced-reference-reports.txt` (`07c8bd80…`). Le rendu complet au HEAD (2 946 lignes : 1 826 impacts, 880 tests) a été relu pour vérifier la variété des cas (limitations, départages, profondeur et nombre de résultats atteints).
- **Écart avec D1 sur l'interface du store.** L'index est exposé par `InMemoryCodeKnowledgeStore.symbolEdgeIndex(projectId)` (type imbriqué `SymbolEdgeIndex`), construit au premier appel par `ConcurrentHashMap.computeIfAbsent` (au plus une construction par projet, lecteurs simultanés en attente) et abandonné par toute écriture de symboles ou de relations. Il porte aussi les deux faits des limitations de base (sortes ayant une relation non résolue, sortes ayant une relation qui sort des symboles du projet) : sans eux, `baselineLimitations` restait un double parcours linéaire des relations à chaque appel. Le parcours des occurrences (`SnapshotCoverageLimitations.hasResolvedReferenceOccurrences`) reste par appel : il s'arrête à la première référence résolue et sa règle a une source unique.

## Goals / Non-Goals

**Goals** : la préparation d'une analyse d'impact n'est plus linéaire en taille de snapshot quand la vue est en cache ; le rapport runtime trouve un symbole en temps constant ; rapports identiques.

**Non-Goals** : changer l'algorithme de traversée ; ajouter un cache d'analyses ; modifier le record de vue.

## Decisions

### D1. Index dérivés portés par le store en mémoire de la vue

`InMemoryCodeKnowledgeStore` expose les arêtes entrantes triées par symbole cible, construites à la première demande et publiées de façon sûre entre threads (le serveur MCP sert des requêtes concurrentes). `ImpactAnalysisService` reçoit la vue et utilise cet accesseur et `findSymbolById` au lieu de reconstruire ses tables.

Alternative écartée : un cache dans `ImpactAnalysisService` indexé par identifiant de snapshot. Il doublerait la durée de vie et le plafond du cache de vues.

### D2. Adjacence portée par l'entrée de cache du graphe

L'entrée de cache de `ProgramGraphService` contient, avec le graphe, l'adjacence triée dont `AdvancedImpactService` a besoin ; son poids l'inclut.

### D3. Le rapport runtime passe par la vue

`RuntimeIntelligenceService` charge la vue de requête active et appelle `findSymbolById`. Symbole inconnu : même réponse qu'aujourd'hui.

### D4. Preuves

- Compteur de constructions d'index : deux analyses sur la même vue → 1.
- Rapports d'impact et d'impact avancé identiques aux goldens et à une comparaison avant/après sur fixtures (ordre compris).
- Rapport runtime : un faux store dont `loadActiveKnowledge` lève prouve que seul le chemin par la vue est emprunté.
- Poids : le test de poids d'AUD-PERF-03 est étendu à une vue dont l'index dérivé est construit.

## Direction des dépendances

`minos-application` → `minos-engine` (déjà existant). Aucune arête nouvelle, aucun cycle de packages (règle A8).

## Qualification des capacités

Aucune capacité nouvelle. Le comportement observable est inchangé ; seule la préparation est mutualisée.

## Windows et Linux

Aucune différence.

## Risks / Trade-offs

- Tas : l'index dérivé vit autant que la vue ; il est compté (écart 3).
- Concurrence à la première construction : publication sûre exigée, testée par des appels simultanés.
- Ordre des sorties : le test de comparaison avant/après est la condition de fusion.
