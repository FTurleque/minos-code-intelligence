# Design

## Context

Constat AUD-ARC-07 de l'audit du 10 octobre 2026 (`docs/project/audits/2026-10-10/findings.md:75`), tâche SH-04 (`docs/project/roadmap/storage-hexagonal-2026-10/TASKS.md:81-102`), ADR 0056. HEAD analysé : `6b60790e` (`develop`, après la PR #534) ; contre-vérifié au HEAD `5ce7b79b` (`develop` du 2026-10-22, après la PR #538), où seuls trois fichiers de `docs/` diffèrent : les sites ci-dessous sont inchangés, sauf mention contraire. Abréviations : `STORE` = `minos-engine/src/main/java/com/minos/store`, `APP` = `minos-application/src/main/java/com/minos`, `LOCAL` = `minos-storage-local/src/main/java/com/minos/storage/local/store`, `PG` = `minos-storage-postgresql/src/main/java/com/minos/storage/postgresql/snapshot`.

Documents relus en entier : ADR 0024, 0047, 0056 ; `docs/project/roadmap/storage-hexagonal-2026-10/README.md` et la section SH-04 de `TASKS.md`. ADR 0045 relu sur sa décision (constructeur unique des classes de surface).

## État vérifié au HEAD

### Contrat de la vue et du port

- `STORE/SnapshotQueryView.java:11-16` : `public record SnapshotQueryView(SnapshotDescriptor descriptor, CodeKnowledgeSnapshot snapshot, InMemoryCodeKnowledgeStore queryStore, long buildNanos)` ; `:26-28` `indexMetrics()` rend `InMemoryCodeKnowledgeStore.IndexMetrics`. Même signature dans la version publiée `v1.2.0` (alors dans `minos-storage-local`, même nom qualifié).
- `STORE/CodeKnowledgeSnapshotStore.java:29` : `loadActiveQueryView` abstraite ; `:36-38` `activeSnapshotId` en `default` (ajout de la PR #445).
- `STORE/CodeKnowledgeStore.java:21-25` : trois écritures `put*` ; `:27-47` : sept lectures (`findSymbolById`, `findSymbols` ×2 dont une `default`, `lacksModuleAttribution` `default`, `findFileSymbols`, `findUsages`, `findRelationships`).
- `STORE/InMemoryCodeKnowledgeStore.java:42` implémente `CodeKnowledgeStore` ; `:69` constructeur depuis un snapshot ; `:123` `symbolEdgeIndex(projectId)` (construit une fois, AUD-PERF-11) ; `:129` `symbolEdgeIndexBuilds()` ; `:137` statique `symbolEdgeIndexWeightBytes` ; `:288` `indexMetrics()` ; `:502` type imbriqué public `SymbolEdgeIndex`, `:524` `SymbolEdgeIndex.of(snapshot)` ; `:596` record imbriqué `IndexMetrics` (neuf compteurs propres aux index mémoire).

### Les deux sites de reconstruction (AUD-ARC-07)

- `LOCAL/FileSymbolSnapshotStore.java:294` `new InMemoryCodeKnowledgeStore(snapshot)`, `:297` `new SnapshotQueryView(...)` (l'audit citait `:279`) ; `:385` poids du cache par `InMemoryCodeKnowledgeStore.symbolEdgeIndexWeightBytes` ; `:166` `loadActiveKnowledge` dérivé de la vue ; `:170` `loadActiveQueryView`.
- `PG/PostgresCodeKnowledgeSnapshotStore.java:171` `new InMemoryCodeKnowledgeStore(snapshot)`, `:181` `new SnapshotQueryView(...)` (l'audit citait `:142`, ancien package) ; `:500` même méthode statique de poids ; `:132` et `:138` `loadActive` et `loadActiveKnowledge` dérivés de la vue ; `:142` `loadActiveQueryView`.

Ces constructions ont lieu une fois par vue, sur défaut de cache (ADR 0024) : « par snapshot lu » s'entend par vue construite, pas par requête.

### Consommateurs de production

| Site | Ce qui est lu | Mutateurs nécessaires ? |
|---|---|---|
| `APP/application/ProjectQueryService.java:88-91` (`loadQueryStore`), utilisé `:46`, `:51`, `:56`, `:61`, `:75`, `:85` | `queryStore()` passé à `SymbolQueryService`, `RelationshipQueryService`, `CodeSearchService` | Non |
| `APP/application/ProjectQueryService.java:67-69` | `snapshot()` | Non |
| `APP/impact/ImpactAnalysisService.java:11-13` (imports), `:60-67` | `queryStore()` : `symbolEdgeIndex(projectId)`, `findSymbolById` | Non |
| `APP/impact/ImpactAnalysisService.java:81` | `SymbolEdgeIndex.of(snapshot)` (chemin sans vue) | Non |
| `APP/impact/LocalProjectImpactQuery.java:43` | `loadActiveQueryView` puis `analyze(view, …)` | Non |
| `APP/application/dynamic/RuntimeIntelligenceService.java:184-186`, `:250-252` | `queryStore().findSymbolById`, `snapshot()` | Non |

Services de lecture typés par le port complet : `minos-engine/.../query/SymbolQueryService.java:18`, `RelationshipQueryService.java:21`, `ModuleFilterGuard.java:24`, `APP/context/CodeSearchService.java:38` ; aucun n'appelle `put*`. Écrivains de production : `minos-provider-scip/.../ingestion/ScipIngestionAdapter.java:97`, `:166`, `:184` (ingestion) et `minos-provider-scip/.../ingestion/ScipSymbolSnapshotImporter.java:256` (`CapturingStore implements CodeKnowledgeStore`).

`indexMetrics()` n'a **aucun consommateur de production**. Aucune surface (`minos-cli`, `minos-api`, `minos-mcp`, `minos-nexus`) ni `minos-bootstrap` / `minos-app` ne nomme `SnapshotQueryView` ou `InMemoryCodeKnowledgeStore` en production (règle A11 : ces types sont absents de `ENGINE_TYPES_NAMED_BY_SURFACE`). Les seuls appelants de production de `loadActiveQueryView` sont les quatre sites du tableau et les deux stores eux-mêmes ; `minos-intellij` ne nomme aucun de ces types (aucune dépendance `com.minos:*`).

**Usage sans nom.** `RuntimeIntelligenceService.java:186` appelle `view.queryStore().findSymbolById(...)` sans importer `InMemoryCodeKnowledgeStore` : un garde qui ne compte que les noms (import, nom qualifié) ne voit pas cet usage. C'est le fonctionnement de `types_named_by_module` (`scripts/architecture/check-module-boundaries.py:648-674`, « import ou nom qualifié »), sur lequel reposent A11 et A13 ; le changement `faire-consommer-l-indexation-aux-surfaces` relève la même limite pour A11 (son `design.md:48`). Le cliquet de ce changement refuse donc aussi les **appels** `loadActiveQueryView(` et `queryStore()` hors de sa liste fermée.

Ports d'écriture : après D4, aucune classe de production de `minos-application` ne nomme plus `CodeKnowledgeStore` (seul `CodeSearchService.java:15`, `:35`, `:38` le nomme au HEAD) ; ses seuls noms de production restants sont l'ingestion SCIP et la classe mémoire. Voir D7.

### Consommateurs de test et scripts

- `queryStore()` : `minos-application/.../impact/ImpactIndexReuseTest.java:50`, `:67`, `:108` (compteur `symbolEdgeIndexBuilds`) ; `minos-storage-local/.../QueryViewWeightTest.java:58`.
- Constructions de la vue : `ImpactIndexReuseTest.java:118`, `minos-application/.../program/analysis/ProgramGraphSourceFixture.java:245`, `minos-bootstrap/.../scalability/SnapshotScalabilityBenchmark.java:1220`, `minos-application/.../dynamic/RuntimeIntelligenceServiceTest.java:323`.
- 33 fichiers de test nomment `loadActiveQueryView` (redéfinition ou appel) ou construisent un `InMemoryCodeKnowledgeStore` (app 1, application 11, bootstrap 1, cli 1, engine 3, provider-scip 7, storage-local 7, storage-postgresql 2) ; 14 d'entre eux implémentent le port (redéfinition de `loadActiveQueryView`), 16 construisent la classe mémoire. Aucun n'est à déplacer : aucune classe ne change de module.
- `IndexMetrics` : `minos-storage-local/.../InMemoryCodeKnowledgeStoreIndexTest.java:54-111` (sur le store, pas sur la vue).
- Consommateurs Java compilés à la main : `scripts/m15/M15FinalQueryProbe.java:83`, `:85` (`indexMetrics()`), `:128`, `:155` (`buildNanos()`) ; `scripts/history/m16/M16ScaleBenchmark.java:92-95` (`queryStore().indexMetrics()`, historique). Ils servent de « consommateur compilé avant/après » exigé par l'ADR 0056.

### Gates, couverture et bases figées

- `scripts/quality/check-jacoco.py:54-59`, scope `persistence-cache-indexes` (ligne 0,50, branche 0,35) : préfixes `com/minos/store/InMemoryCodeKnowledgeStore` et `com/minos/store/SnapshotQueryView`, conservés. Autres scopes qui couvrent du code modifié : `m30-postgresql-pgvector` (`:233`, préfixe `com/minos/storage/postgresql/`, mesuré sous Linux seul), `m26-runtime-dynamic-intelligence` (`:198`, préfixe `com/minos/application/dynamic/`, donc `RuntimeIntelligenceService`) et `global` (`:19`). Un préfixe qui ne désigne aucune classe du rapport fait échouer le gate (`:594`) : une interface sans code exécutable (`IncomingRelationships`) n'y figure pas et ne doit pas être ajoutée comme préfixe.
- `scripts/ci/test_plugin_gate.py:49` cite le chemin `minos-engine/src/main/java/com/minos/store/InMemoryCodeKnowledgeStore.java` comme exemple hors du périmètre du check du plugin ; le fichier ne bouge pas, le test reste vert (à rejouer).
- Littéraux des stores, à conserver : `scripts/remediation/check-mne.py:67-71` (`MAX_ACTIVE_QUERY_RETRIES`, boucle, `Files.deleteIfExists(value.payload())` ; interdit `return loadActiveQueryView(projectId);` dans le store PostgreSQL), `:85-88` (`QUERY_VIEW_PERSISTED_AMPLIFICATION`, `Files.size(snapshot)`, `safeMultiply`, `estimateWeight(metadata, payloadBytes)` ; le lot 1 de `mutualiser-les-caches-lru-et-nommer-les-additions-bornees` remplace l'exigence `safeMultiply` par `saturatingMultiply(Files.size(snapshot)` : relire la ligne au moment de l'implémentation) ; `scripts/remediation/check-mnd.py:82-83`, `:99-100` (`SnapshotProjectLease.acquire(...)`, `activeMetadata(projectId)`, `queryCache`, `MAX_QUERY_CACHE_WEIGHT_BYTES`, `buildLocks`) ; `scripts/remediation/check-p0-p2.py:309-312` (`ReentrantLock[] buildLocks`, `buildLock.lock()`, `buildLock.unlock()`). Aucun ne cite `queryStore`, `SnapshotQueryView`, `InMemoryCodeKnowledgeStore` ni un des quatre cas d'usage. Le `forbid` de `check-mne.py:70-71` ne vise que l'ancienne méthode : une relance récursive par la nouvelle lecture (`return loadActiveReadView(projectId);`) le passerait ; la chaîne est à ajouter au même `forbid` (tâche 2.2).
- `scripts/m15/run-final.ps1:94` exige `loadActiveQueryView(project.id())` dans `ProjectQueryService`, que la migration retire. Ce script est lancé à la main (job `m15-windows`, `.github/workflows/historical-qualification.yml:120-158`, `workflow_dispatch`) et il est déjà cassé au HEAD, **avant** d'atteindre `:94` : `:57` exige 12 modules Maven (le reactor en compte 14) ; `:78-79` lève sur `minos-storage-local\src\main\java\com\minos\store\SnapshotQueryView.java`, chemin disparu depuis le passage des types de snapshot dans `minos-engine` ; `:83` et `:88` lisent des chemins également disparus. Il calcule la racine du dépôt par `Join-Path $PSScriptRoot '..\..'` (`:11`, de même que `capture-baseline.ps1:12` et `capture-final-query.ps1:10`, qui chargent ensuite `scripts\windows\MinosWindows.ps1`) et appelle ses voisins `scripts\m15\capture-baseline.ps1` (`:160`) et `capture-final-query.ps1` (`:182`), ce dernier compilant `scripts\m15\M15FinalQueryProbe.java` (`capture-final-query.ps1:40`). L'ADR 0043 range un tel outil de rejeu manuel parmi les artefacts gardés sous leur numéro de jalon (`0043-retrait-des-artefacts-de-jalon.md:29`), retire un `run-final.ps1` en même temps que le job qui l'appelle (précédent M19/M20, `:49`) et réserve `scripts/history/` à ce qui n'est **ni exécuté ni gelé** (`:22`). Décision D6.
- `scripts/architecture/check-module-boundaries.py` porte déjà des règles A15 (`:950`, tout type public de premier niveau est nommé par la production ou déclaré) et A16 (`:816`) : la nouvelle règle est **A17** (numéro libre : aucun changement actif ne réserve A17). A15 s'applique aux nouveaux types publics, mais compte comme référence toute source **du même package** (`:902-946`) : des types ajoutés à `com.minos.store` qui se nomment entre eux passent A15 sans appelant hors du package. La preuve d'un appelant de production réel est donc la migration des cas d'usage (tâche 3.2) et le décompte de la tâche 4.3, pas A15.
- `scripts/quality/check-runtime-dynamic-consistency.py:81-86` et `scripts/remediation/check-mne.py:106-107` lisent `RuntimeIntelligenceService.java` ; leurs littéraux ne touchent pas la vue.
- `scripts/architecture/check-module-boundaries.py:576` (A11, NEXUS nomme `store.CodeKnowledgeSnapshotStore`) et `:602` (A13, SCIP nomme `FileSymbolSnapshotStore`) : inchangés.
- SpotBugs : `quality/spotbugs-baseline/minos-engine.xml:862-884` (`EI_EXPOSE_REP` sur `SnapshotQueryView.queryStore()`) et `:885-910` (`EI_EXPOSE_REP2` sur son constructeur), conservés tant que l'ancien contrat l'est ; la nouvelle vue exposera aussi son lecteur : les deux mêmes motifs sont attendus.
- Couverture de `InMemoryCodeKnowledgeStore` : testée surtout depuis `minos-storage-local` (`docs/developer/quality/code-audit-constats.md:187`). `ProjectQueryService` et `LocalProjectImpactQuery` sont testés surtout depuis `minos-bootstrap` (`ProjectQueryServiceRelationshipLimitationsTest`, `LocalProjectImpactQueryTest`).

## Écarts avec l'action proposée par l'audit

1. **« Trancher 0047 avant SH-04 »** : fait (décision D1 du 2026-10-22) ; aucune mesure ne conditionne plus ce changement.
2. **« Les deux backends reconstruisent un `InMemoryCodeKnowledgeStore` complet par snapshot lu »** : après ce changement, les backends ne **nomment** plus la classe et n'exposent plus le type concret, mais un index mémoire complet est toujours **construit** une fois par vue, par la fabrique du cœur. Le supprimer demande un autre modèle de lecture (ADR 0047, options B ou C) ; l'ADR 0056 annonce la substituabilité, pas un gain de mémoire. L'impact relevé par l'audit (taille de projet bornée par le tas) **n'est pas traité** ici, et ce changement ne le présente pas comme traité.
3. **Métriques neutres** (ADR 0056, « et des métriques neutres ») : aucun consommateur de production ; voir D3.

## Goals / Non-Goals

**Goals** : les cas d'usage de lecture et les deux stores ne dépendent plus de la classe mémoire ; un double non mémoire sert les requêtes ; aucun changement silencieux du contrat public ; résultats, ordre, budgets et cache inchangés.

**Non-Goals** : réduire le tas ou le temps de construction ; retirer l'ancien contrat ; changer un format persisté, le schéma PostgreSQL ou une sortie.

## Décisions de conception

### Port de lecture

`CodeKnowledgeReader` (STORE) déclare les sept lectures de `CodeKnowledgeStore.java:27-47` avec leurs `default` actuels. `CodeKnowledgeStore extends CodeKnowledgeReader` et ne déclare plus que les trois `put*`. Remonter une méthode dans une super-interface est compatible en binaire et en source (résolution des méthodes par les super-interfaces) : un appelant compilé contre l'ancienne version et une implémentation tierce de `CodeKnowledgeStore` continuent de fonctionner.

### Vue neutre et lecture du port de snapshot

```text
SnapshotReadView  (classe finale : descriptor(), snapshot(), reader(), buildNanos())
   incomingRelationships() -> IncomingRelationships

CodeKnowledgeSnapshotStore
   loadActiveReadView(id)   default = loadActiveQueryView(id).map(SnapshotQueryView::readView)
   loadActiveQueryView(id)  @Deprecated, reste abstraite

SnapshotQueryView (@Deprecated, composants inchangés)
   readView()               vue neutre sur le même snapshot et le même index
   of(SnapshotReadView)     @Deprecated : réutilise l'index mémoire si c'est lui, sinon en construit un
```

- `SnapshotReadView` est une **classe finale**, pas un record : un record ne peut pas porter d'état au-delà de ses composants, et l'index des arêtes doit être mémorisé. Pour un lecteur mémoire, `incomingRelationships()` délègue à `InMemoryCodeKnowledgeStore.symbolEdgeIndex(projectId)`, déjà mémorisé par l'index (AUD-PERF-11) : une vue recréée par `SnapshotQueryView.readView()` ne reconstruit donc rien. Pour un autre lecteur, l'index est construit depuis le snapshot au plus une fois par instance de vue ; les stores mettent en cache l'instance elle-même.
- `loadActiveReadView` est `default` pour qu'une implémentation existante du port (14 fichiers de test, éventuels tiers) reste valide ; `loadActiveQueryView` **reste abstraite** : rendre les deux méthodes `default` l'une par l'autre créerait une récursion sans fin pour une implémentation qui n'en redéfinit aucune.
- Les deux stores redéfinissent `loadActiveReadView` (chemin servi, en cache) et implémentent `loadActiveQueryView` par `loadActiveReadView(id).map(SnapshotQueryView::of)` : l'ancien contrat réutilise l'index en cache, sans seconde construction.
- Un store non mémoire doit encore implémenter la méthode dépréciée ; `SnapshotQueryView.of` lui en donne une implémentation qui construit un index mémoire, sur ce seul chemin déprécié.
- Les stores obtiennent la vue d'une fabrique du cœur (`SnapshotReadView.indexed(descriptor, snapshot)`, qui mesure `buildNanos`) et calculent le poids de l'index dérivé par une statique neutre déléguant à `InMemoryCodeKnowledgeStore.symbolEdgeIndexWeightBytes`. Les littéraux gardés par `check-mne.py`, `check-mnd.py` et `check-p0-p2.py` restent dans les stores.
- Identité et cohérence : la vue neutre est l'objet mis en cache ; l'identité `(projectId, snapshotId)`, la comparaison du descripteur et la relecture du pointeur actif après construction (ADR 0024) sont inchangées.

### Index des arêtes entrantes

Interface neutre `IncomingRelationships` (STORE) : `incoming`, `hasUnresolved`, `leavesProjectSymbols`, `targetCount`, déjà publics sur `SymbolEdgeIndex`, qui l'implémente (ajout d'interface à une classe finale : compatible). `ImpactAnalysisService` ne nomme plus que l'interface ; son chemin sans vue utilise une fabrique neutre équivalente à `SymbolEdgeIndex.of`. Sa surcharge publique `analyze(SnapshotQueryView, …)` est conservée, dépréciée, et délègue à `analyze(SnapshotReadView, …)` : elle figure donc dans la liste fermée du cliquet A17.

### Recouvrements avec les changements actifs

| Changement actif | Fichiers communs | Ordre recommandé |
|---|---|---|
| `mutualiser-les-caches-lru-et-nommer-les-additions-bornees` (tâches 4.4, 4.5) | caches de `FileSymbolSnapshotStore` et `PostgresCodeKnowledgeSnapshotStore` (tâche 2.2 ici) | fusionner avant ce changement ; la tâche 2.2 se rebase sur ses caches |
| `decouper-les-methodes-d-analyse-et-d-ingestion` (tâche 2.2) | surcharge privée `ImpactAnalysisService.analyze` (`:84-86`) et ses aides (`:227`, `:251`), qui reçoivent un `SymbolEdgeIndex` | fusionner avant ; la tâche 3.2 change l'entrée publique et le **type** du paramètre d'index des méthodes privées (`IncomingRelationships`), pas leur corps : le second fusionné rebase ces signatures |
| `calculer-le-rang-de-symbole-une-fois` | `InMemoryCodeKnowledgeStore.findSymbols` ; scope JaCoCo `persistence-cache-indexes` (même entrée de `check-jacoco.py`) | indépendant (ce changement n'ajoute qu'une clause `implements` et deux préfixes) ; rebaser le second fusionné |
| `faire-consommer-l-indexation-aux-surfaces` | `scripts/architecture/check-module-boundaries.py` (table A11 `:509-578`, docstring `:5`, lignes de résumé `:1465-1480`) et `test_check_module_boundaries.py` | aucun recouvrement fonctionnel (son `design.md:166` : ni `com.minos.store` ni `NexusExportService`) ; conflit textuel seulement : le second fusionné rebase et rejoue les auto-tests |
| `decouper-les-classes-de-plus-de-800-lignes` | `quality/spotbugs-baseline/minos-engine.xml` (son `design.md:68`) et `scripts/quality/check-jacoco.py` (scope `confined-io-primitives`, son `proposal.md:53`) : mêmes fichiers que les tâches 5.2 et 5.3 ici, entrées distinctes | indépendant ; conflit textuel seulement. Aucun fichier Java commun (`InMemoryCodeKnowledgeStore` fait 695 lignes, sous le seuil) |
| `garder-les-fonctions-de-scripts-jumelles` | classe `scripts/m15/run-final.ps1` comme **rejeu manuel** et relève les dépendances des scripts M15 (`capture-baseline.ps1:228` appelle `scripts/m14/validate-local.ps1` ; les scripts M15 chargent `scripts/windows/MinosWindows.ps1`) (son `design.md:48`, `:58`) | cohérent avec la recommandation D6 (a) ; sous D6 (c′), ses relevés de `scripts/m15/` deviennent faux : le dire dans la PR du second fusionné |

`lire-l-identifiant-du-snapshot-a-la-reconciliation` (même port `CodeKnowledgeSnapshotStore`, mêmes deux stores, `RuntimeIntelligenceService`) est implémenté au HEAD ; il ne lui reste que sa clôture (tâche 3.2) : aucun conflit de code, c'est le précédent de migration additive suivi ici. `figer-la-dette-de-frontieres-par-des-cliquets` est archivé et ne contredit rien.

## Qualification des capacités

| Capacité | Qualification après ce changement |
|---|---|
| Lecture des requêtes par un port neutre | **Qualifiée** par les tests du double non mémoire (cœur et application) et par le cliquet de frontière |
| Ancien contrat `SnapshotQueryView` | **Qualifiée** : mêmes résultats, aucune construction d'index supplémentaire depuis un store du dépôt |
| Store PostgreSQL | **Qualifiée sous Linux** (serveur réel en CI) ; sous Windows, tests sautés sans Docker, ce qui n'est pas un succès |
| Implémentation non mémoire de production | **Non supportée** : aucune n'est livrée ; seul un double de test prouve la substituabilité |
| Réduction du tas ou de la construction d'index | **Non supportée** (hors périmètre, ADR 0047 B/C) |

## Windows et Linux

Aucun code propre à une plateforme : types Java du cœur et deux stores déjà qualifiés sur les deux systèmes. La qualification PostgreSQL reste Linux seule en CI.

## Coût chiffré

| Poste | Estimation |
|---|---|
| Code neuf `minos-engine` | 4 types (`CodeKnowledgeReader`, `IncomingRelationships`, `SnapshotReadView`, fabrique ou statiques) et ~10 lignes dans `SnapshotQueryView`, `CodeKnowledgeSnapshotStore`, `InMemoryCodeKnowledgeStore` ; environ 60 lignes exécutables. Les tests de la classe mémoire vivent dans `minos-storage-local` : **le nouveau code du cœur doit être couvert par de nouveaux tests dans `minos-engine`** pour atteindre 80 % (SonarCloud, couverture par module du nouveau code). |
| Code modifié `minos-storage-local` | ~10 lignes de `FileSymbolSnapshotStore` ; couvertes par `FileSymbolSnapshotStoreCacheTest`, `-RetryTest`, `-IntegrityTest`, `QueryViewWeightTest` (même module). |
| Code modifié `minos-storage-postgresql` | ~10 lignes ; couvertes seulement avec Docker (`PostgresCodeKnowledgeSnapshotStoreCacheTest`) : la couverture SonarCloud vient du job Linux. |
| Code modifié `minos-application` | ~15 lignes dans quatre services ; `ProjectQueryService` et `LocalProjectImpactQuery` sont testés depuis `minos-bootstrap` : **ajouter des tests dans `minos-application`** (double non mémoire) pour couvrir les lignes modifiées. |
| Tests déplacés | **Aucun** : aucune classe ne change de module. Tests migrés : 4 usages de `queryStore()` et 4 constructions de vue (les tests de l'ancien contrat restent, marqués comme tels). |
| `check-jacoco.py` | Préfixes conservés ; ajouter `com/minos/store/SnapshotReadView`, `com/minos/store/CodeKnowledgeReader` au scope `persistence-cache-indexes` (seuils inchangés), mesurés sur les deux systèmes avant d'être resserrés. Scopes à rejouer sans modification : `m30-postgresql-pgvector` (Linux), `m26-runtime-dynamic-intelligence`, `global`. |
| Gates littéraux | Aucun gate existant ne cite les noms remplacés ; `check-mne.py`, `check-mnd.py`, `check-p0-p2.py`, `check-runtime-dynamic-consistency.py` rejoués pour prouver que leurs littéraux sont intacts. Nouveau : la règle de cliquet et ses auto-tests (`test_check_module_boundaries.py`). |
| SpotBugs | `EI_EXPOSE_REP` (accesseur du lecteur) et `EI_EXPOSE_REP2` (constructeur) probables sur la nouvelle vue ; à ajouter à `quality/spotbugs-baseline/minos-engine.xml` avec justification (lecteur partagé, sans écriture vue du port). |
| Règle A15 | Les nouveaux types publics (`CodeKnowledgeReader`, `IncomingRelationships`, `SnapshotReadView`) doivent être nommés par la production. A15 le constate déjà par les références internes à `com.minos.store` ; l'appel réel hors du package vient de la migration des cas d'usage (`minos-application`), vérifiée par la tâche 4.3. |
| Compatibilité binaire | Un test de `minos-engine` résout par `MethodHandles.publicLookup()` les descripteurs publiés en 1.2.0 (`CodeKnowledgeStore.findSymbolById`, `SnapshotQueryView.queryStore()`, `indexMetrics()`, `CodeKnowledgeSnapshotStore.loadActiveQueryView`) : une méthode remontée dans la super-interface reste résolue. La compilation de `M15FinalQueryProbe.java` ne prouve que la compatibilité de source. |

## Risques

- **Récursion des `default`** : évitée par l'asymétrie décrite ; un test du cœur couvre une implémentation qui ne redéfinit que l'ancienne méthode.
- **Double construction d'index** si l'ancien contrat reconstruisait l'index : un test à compteur (`queryViewBuilds`, `symbolEdgeIndexBuilds`) prouve 1 construction pour les deux contrats.
- **Binaire des services de requête** (D4) : changer le type de paramètre change le descripteur des constructeurs ; seul un appelant précompilé hors du reactor est touché. L'entrée de `docs/user/java-api.md` § Ruptures nomme la version (1.3.0), comme l'exige l'ADR 0056 pour une rupture.
- **Contournement du cliquet par chaînage** : sans le refus des appels `loadActiveQueryView(` et `queryStore()`, une classe pourrait écrire dans l'index partagé sans nommer aucun type surveillé (cas réel au HEAD : `RuntimeIntelligenceService.java:186`).
- **Surcharges ambiguës** : `ImpactAnalysisService.analyze` aura trois surcharges à deux paramètres (`SnapshotReadView`, `SnapshotQueryView`, `CodeKnowledgeSnapshot`) ; un appel avec `null` littéral ne compile plus. Aucun test du HEAD ne le fait (recherche de `analyze(null` dans `src/test`).
- **Dépréciations** : la compilation ne traite pas les alertes comme des erreurs (aucun `-Werror` ni `failOnWarning` dans `pom.xml`) ; les 14 faux stores de test qui redéfinissent `loadActiveQueryView` compilent avec une note de dépréciation. L'effet sur la porte qualité SonarCloud (règle des usages d'API dépréciée dans les deux stores) n'est pas mesuré.

## Décisions du propriétaire (2026-10-22)

Le propriétaire a retenu les six recommandations ; la colonne « Décision » donne l'option retenue et sa raison.

| # | Question | Options | Décision |
|---|---|---|---|
| D1 | Noms des nouveaux types | `CodeKnowledgeReader`, `SnapshotReadView`, `IncomingRelationships` ; ou autres noms | **Ceux-ci** : `CodeKnowledgeReader` est le nom de travail de l'ADR 0056 ; `SnapshotReadView` dit ce que la vue devient (lecture), sans le mot « query » de l'ancien type |
| D2 | Forme de la migration du record | (a) nouvelle vue (classe finale) + `loadActiveReadView` `default`, ancien record et ancienne méthode dépréciés ; (b) accesseur neutre ajouté au seul record existant ; (c) rupture versionnée | **(a)** : (b) garde un constructeur canonique qui exige la classe mémoire, donc aucun double non mémoire ne peut produire une vue ; (c) n'est pas nécessaire |
| D3 | Métriques neutres | (a) aucune métrique dans le port, `IndexMetrics` reste sur la classe mémoire et `indexMetrics()` déprécié ; (b) record neutre (`indexReferences`, `buildNanos`) sur la vue | **(a)** : aucun consommateur de production ; l'ADR 0056 veut des types « définis par les consommateurs réels ». L'écart avec le texte de l'ADR est consigné dans sa note de mise en œuvre |
| D4 | Type des paramètres de `SymbolQueryService`, `RelationshipQueryService`, `CodeSearchService`, `ModuleFilterGuard.require` | (a) passer à `CodeKnowledgeReader` (compatible en source, rupture binaire de classes internes listée dans `java-api.md` § Ruptures) ; (b) garder `CodeKnowledgeStore` ; (c) constructeur surchargé | **(a)** : sans lui, la vue neutre doit être retransformée en port d'écriture ; (c) va contre l'esprit de l'ADR 0045 (un seul constructeur) |
| D5 | Où vit le cliquet | (a) règle A17 de `check-module-boundaries.py` avec auto-tests, comme A11 à A16 ; (b) test ArchUnit | **(a)** : même outil que les autres cliquets, exécuté par le job `invariants` |
| D6 | `scripts/m15/run-final.ps1`, déjà cassé au HEAD, exige le littéral retiré | (a) le laisser, cassé, en le signalant dans la PR ; (b) corriger ses chemins et son littéral ; (c) le déplacer sous `scripts/history/` | **(c)** retenue le 2026-10-22 ; **à reconfirmer**, voir ci-dessous |

### Décisions rouvertes ou ajoutées par la contre-vérification (en attente)

| # | Question | Options | Recommandation |
|---|---|---|---|
| D6 (à reconfirmer) | `scripts/m15/run-final.ps1` | (a) le laisser en place, inchangé, et consigner sa panne préexistante au suivi d'audit ; (c′) archiver tout le rejeu M15 : déplacer les quatre fichiers de `scripts/m15/` sous `scripts/history/m15/`, retirer le job `m15-windows` et le choix `m15` de `historical-qualification.yml`, mettre à jour `ROADMAP.md:105` et `STATUS.md:212` ; (b) le réparer | **(a)** : l'option (c) telle que consignée casse le script (racine calculée par `..\..`, voisins appelés par `scripts\m15\`) et, si le workflow l'appelle encore, contredit l'ADR 0043 (`scripts/history/` = ni exécuté ni gelé). Le script lève dès `:57` et `:78-79`, avant le littéral de `:94` : ce changement ne dégrade rien d'observable. (c′) est une décision d'hygiène des jalons, hors de SH-04 ; elle se traite à part, sur le modèle M19/M20 de l'ADR 0043 |
| D7 | Le cliquet A17 refuse-t-il aussi le port d'écriture `CodeKnowledgeStore` dans `minos-application` ? | (a) oui : après D4, aucune classe de production de `minos-application` ne le nomme ; (b) non : seule la classe mémoire est refusée | **(a)** : c'est le critère de validation de l'ADR 0056 « absence d'accès aux mutations depuis les cas d'usage de lecture », rendu vérifiable par commande ; coût nul au HEAD après D4 (`CodeSearchService` est la seule classe concernée) |
