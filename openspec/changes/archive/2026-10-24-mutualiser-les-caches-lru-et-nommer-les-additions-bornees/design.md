# Design

## Context

Sprint 11 « Hygiène de fond » de l'audit du 10 octobre 2026 : **AUD-QUA-05** (voir `proposal.md`, « Why »). Chemins complets des classes citées par leur nom de fichier :

- `minos-application/src/main/java/com/minos/program/analysis/` : `ProgramGraphService.java`, `FingerprintConstrainedJavaProgramGraphProvider.java`, `SourceStateKeyMemo.java` ;
- `minos-application/src/main/java/com/minos/application/semantic/` : `SemanticSearchService.java`, `HybridSearchService.java`, `SemanticIndexService.java` ;
- `minos-application/src/main/java/com/minos/application/dynamic/RuntimeIntelligenceService.java`, `minos-application/src/main/java/com/minos/output/json/DeterministicJson.java` ;
- `minos-storage-local/src/main/java/com/minos/storage/local/store/` : `FileSemanticVectorStore.java`, `FileSymbolSnapshotStore.java` ;
- `minos-storage-postgresql/src/main/java/com/minos/storage/postgresql/snapshot/PostgresCodeKnowledgeSnapshotStore.java` ;
- `minos-engine/src/main/java/com/minos/hosted/HostedDenialThrottle.java`, `minos-engine/src/main/java/com/minos/source/SourceBudgetPolicy.java` ;
- `minos-integration-git/src/main/java/com/minos/integration/git/` : `RemoteCloneBudget.java`, `JGitRemoteRepositoryMaterializer.java` ;
- `minos-provider-scip/src/main/java/com/minos/adapter/scip/ingestion/ScipIngestionLimits.java` ;
- `minos-runtime-local/src/main/java/com/minos/runtime/local/workspace/` : `ProviderWriteQuotaSupervisor.java`, `RunDirectoryRetention.java`.

## État vérifié au HEAD

HEAD relu : `5ce7b79b` (`develop` du 2026-10-22). La première rédaction (HEAD `949e1569`) est corrigée en place ; chaque écart est signalé « (corrigé) ».

**Non vérifié** : aucun build, test, gate ni rapport JaCoCo exécuté pendant cette relecture ; seules des recherches textuelles (`git grep`), la lecture des sources et des scripts, et la résolution des préfixes JaCoCo par le dictionnaire `SCOPES` de `check-jacoco.py`.

**Relecture au HEAD de la branche d'implémentation** (tâche 1.1, `0c470fb7`, branche `refactor/mutualiser-les-caches-lru-et-nommer-les-additions-bornees` empilée sur `perf/calculer-le-rang-de-symbole-une-fois`, 2026-10-24). Depuis `5ce7b79b`, `exposer-un-port-de-lecture-de-snapshot` met en cache la vue de lecture (`SnapshotReadView`, enveloppée dans `WeightedReadView`) dans les deux stores, et la base SpotBugs a été réalignée. Décomptes refaits : **9** cartes à ordre d'accès (aucune nouvelle ; la vue de lecture est rangée dans la carte `queryCache` déjà comptée de chaque store, pas dans une carte de plus) et **21** déclarations des cinq noms. `grep -rnE 'safeAdd|checkedAdd|safeMultiply|trimCache|trimCorpusCache|WeightedGraph' scripts/ quality/ .github/ .claude/scripts/` : `check-mne.py:86`, `check-mnd.py:102`, `:104`, `:106`, rien d'autre (la base SpotBugs ne nomme aucun de ces helpers). Les numéros de ligne qui ont bougé sont corrigés en place (« relu au HEAD d'implémentation »). Trois constats nouveaux :

- `FileSemanticVectorStore.removeCached(…, eviction)` (`:315-323`) n'est appelé qu'avec `false` (`:83`, `:98`, `:226`, `:265`) : au HEAD, **aucune** invalidation ne compte comme éviction (la première rédaction le supposait possible).
- `FileSymbolSnapshotStore.publishBuiltView` (`:254-279`) remplace une entrée de même clé (même projet, même instantané, descripteur différent) **sans** retrancher son poids : une dérive de comptage, vers le haut, est possible sur ce chemin de course. Le cache partagé retranche le poids de l'ancienne valeur à chaque remplacement : la dérive disparaît avec la migration (tâche 4.4).
- Les deux stores, sur une entrée trop lourde, laissent en place l'ancienne valeur de la même clé (`FileSymbolSnapshotStore` : jamais servie, car son descripteur diffère ; `PostgresCodeKnowledgeSnapshotStore` : même clé, donc même contenu). Le cache partagé la retire (D4) : sans effet observable.

### Cartes à ordre d'accès

Recherche `git grep -nE 'new LinkedHashMap<[^>]*>\([^)]*true\)' -- 'minos-*/src/main/**'` : **9** occurrences (corrigé : 8). Sept caches bornés en poids, deux structures sans poids.

| Classe | Ligne | Borne en entrées | Plafond de poids | Entrée plus lourde que le plafond | Verrou | Évictions comptées |
|---|---|---|---|---|---|---|
| `ProgramGraphService` | `:62` (corrigé : `:44`) | 16 (`:36`, paramétrable) | 256 Mio (`:37`, paramétrable) | non mise en cache (`:197`) ; entrées du même projet et du même budget retirées avant (`removeSupersededEntries`, `:210-223`) | `synchronized (cache)` | non |
| `FingerprintConstrainedJavaProgramGraphProvider` | `:45-46` (corrigé : `:44`) | 64 (`:39`) | 128 Mio (`:40`) | non mise en cache, ancienne valeur de la clé retirée (`:125-132`) | `synchronized (immutableFingerprintCache)` | oui (`trimCache`, `:137-147`) |
| `SemanticSearchService` | `:27` | 256 (`:20`) | 8 Mio (`:22`) | **aucun test** : insérée, puis l'éviction part de la plus ancienne (`:102-120`) ; chemin inatteignable (requête ≤ 64 Kio, `:21` ; poids d'entrée ≈ 128 o + requête + 8 o par dimension) | `synchronized (queryCache)` | non |
| `HybridSearchService` | `:42` | 64 (`:33`) | 256 Mio (`:34`) | non mise en cache, ancienne valeur retirée (`:163-170`) | `synchronized (corpusCache)` | oui (`trimCorpusCache`, `:174-184`) |
| `FileSemanticVectorStore` | `:61` | **aucune** (corrigé : la borne en entrées était supposée partout) | 256 Mio (`:56`, paramétrable) | non mise en cache, ancienne valeur retirée (`:299-313`) | `synchronized (cacheLock)` | oui ; `removeCached(…, eviction)` (`:315-323`) n'est appelé qu'avec `false` : aucune invalidation n'est comptée (relu au HEAD d'implémentation) |
| `FileSymbolSnapshotStore` | `:65-66` (corrigé : `:59`) | 32 (`:40`, paramétrable) | 512 Mio (`:41`, paramétrable) | non mise en cache ; autres entrées du projet retirées avant (`removeProjectEntries`, `publishBuiltView` `:254-279`, `:348-357` ; relu au HEAD d'implémentation) ; valeur : `WeightedReadView` (vue de lecture) | `synchronized (cacheLock)` | oui (`trimCache`, `:363-370`) |
| `PostgresCodeKnowledgeSnapshotStore` | `:72-73` (corrigé : `:59`) | 32 (`:61`) | 512 Mio (`:62`) | non mise en cache ; autres entrées du projet retirées avant (`cache`, `:214-230`) ; valeur : `WeightedReadView` | `synchronized (cacheLock)` | oui (boucle en ligne, `:222-229`) |

Sans poids : `HostedDenialThrottle.java:28` (fenêtres de refus `ArrayDeque<Instant>`) et **`SourceStateKeyMemo.java:35`** (corrigé : absent de la première rédaction ; mémo borné à `MAX_ENTRIES = 64` avec délai de réutilisation sur horloge monotone, arrivé avec `ne-plus-hacher-les-sources-avant-le-cache-du-graphe`).

Trois classes ramènent le poids total à zéro s'il devient négatif (`ProgramGraphService.java:232`, `FingerprintConstrainedJavaProgramGraphProvider.java:146`, `HybridSearchService.java:183`) : une dérive de comptage y est masquée, pas détectée. Les compteurs de succès et d'échecs sont tenus par la classe propriétaire, parfois sur un chemin de course (`FileSymbolSnapshotStore.java:255-258`).

### Helpers d'addition et de multiplication bornées

Recherche des déclarations `private static long <nom>(` : **21**, décompte confirmé ; numéros de ligne corrigés.

| Nom | Sites | Comportement au dépassement |
|---|---|---|
| `safeAdd` (8) | `HybridSearchService.java:249`, `SemanticIndexService.java:236`, `FingerprintConstrainedJavaProgramGraphProvider.java:163`, `FileSemanticVectorStore.java:474`, `FileSymbolSnapshotStore.java:393`, `PostgresCodeKnowledgeSnapshotStore.java:507` (corrigés : `:234`, `:204`, `:138`, `:471`, `:371`, `:456` ; puis `:388` → `:393` et `:503` → `:507` au HEAD d'implémentation) | sature à `Long.MAX_VALUE` (comparaison `right > Long.MAX_VALUE - left`) |
| | `DeterministicJson.java:241` (classe imbriquée) | lève `OutputBudgetExceededException` |
| | `RuntimeIntelligenceService.java:311` (corrigé : `:303` ; trois paramètres, dont `label`) | lève `IllegalStateException` sur `Math.addExact` |
| `checkedAdd` (3) | `SemanticSearchService.java:129` | sature (`Math.addExact` rattrapé) |
| | `RemoteCloneBudget.java:143` | lève `IOException` |
| | `FileSemanticVectorStore.java:444` (corrigé : `:441` ; classe imbriquée `DecodeBudget`, trois paramètres) | lève `IOException` |
| `saturatingAdd` (5) | `ProgramGraphService.java:289` (`Math.addExact` rattrapé) ; `JGitRemoteRepositoryMaterializer.java:391`, `ProviderWriteQuotaSupervisor.java:302`, `RunDirectoryRetention.java:361` (comparaison) ; `ScipIngestionLimits.java:258` (comparaison, **refuse un opérande négatif**) | sature |
| `safeMultiply` (3) | `SemanticIndexService.java:240`, `FileSymbolSnapshotStore.java:397`, `PostgresCodeKnowledgeSnapshotStore.java:511` (relus : `Math.multiplyExact` rattrapé ; `:392` → `:397` et `:507` → `:511` au HEAD d'implémentation) | sature |
| `saturatingMultiply` (2) | `SourceBudgetPolicy.java:106` (`Math.multiplyExact` rattrapé) ; `ScipIngestionLimits.java:263` (division, refuse un opérande négatif) | sature |

Deux constats nouveaux :

- **Opérandes négatifs.** La forme par comparaison suppose `left ≥ 0` : avec `left < 0`, `Long.MAX_VALUE - left` déborde et la fonction rend `Long.MAX_VALUE` à tort ; la forme `Math.addExact` rattrapée rend `Long.MAX_VALUE` même sur un débordement vers le bas. Seul `ScipIngestionLimits` refuse les négatifs. Aucun appelant relu ne passe de négatif (poids et tailles partent de constantes positives). Le renommage du lot 1 ne change pas ce comportement ; il est consigné, pas corrigé.
- **Autres helpers nommés autrement** : `ScipIngestionLimits.add(…, label)` (`:250`, lève `IOException`), `FileProjectFingerprintSnapshotStore.addSnapshotBytes` (`minos-storage-local/src/main/java/com/minos/storage/local/incremental/`, `:476`, lève `IOException`), `NexusExportService.addWeight` (`minos-nexus`, `:313`, sature). Leur nom n'est pas l'un des noms ambigus de D1 : hors périmètre, comme les 38 appels `Math.addExact` / `Math.multiplyExact` écrits en ligne.

### Gates littéraux et outillage concernés (relus)

| Gate | Ligne | Chaîne exigée | Sort |
|---|---|---|---|
| `scripts/remediation/check-mne.py` | `:85-86` | `safeMultiply` dans `FileSymbolSnapshotStore.java` (avec `QUERY_VIEW_PERSISTED_AMPLIFICATION`, `Files.size(snapshot)`) | **supprimée par le lot 1** : réécrite en `saturatingMultiply(Files.size(snapshot)` (D1) |
| `scripts/remediation/check-mnd.py` | `:103-104` | `trimCache` (`FingerprintConstrainedJavaProgramGraphProvider.java`) | **supprimée par le lot 2** |
| | `:105-106` | `trimCorpusCache` (`HybridSearchService.java`) | **supprimée par le lot 2** |
| | `:101-102` | `WeightedGraph`, `evidenceWeight`, `stringWeight`, `maximumWeight` (`ProgramGraphService.java`) | `maximumWeight` est un champ du record public `CacheStats` (`:361-362`) et reste (corrigé : la première rédaction le croyait supprimé) ; `evidenceWeight`, `stringWeight` sont des estimateurs et restent ; `WeightedGraph` (record de valeur, `:62`) disparaît si le poids passe dans le cache partagé |
| | `:99-100` | `queryCache`, `MAX_QUERY_CACHE_WEIGHT_BYTES`, `buildLocks` (store PostgreSQL) | conservées (D5) |
| `scripts/remediation/check-p0-p2.py` | `:221` | `public CacheStats cacheStats()` (`ProgramGraphService.java`) | conservée |
| | `:305-312` | `ReentrantLock[] buildLocks`, `buildLock.lock()`, `buildLock.unlock()` ; interdit `synchronized (buildLock(` (`ProgramGraphService.java`, `FileSymbolSnapshotStore.java`) | conservées : les verrous de construction restent hors du cache (D4) |
| `scripts/quality/check-semantic-retrieval-consistency.py` | `:119-130` | `MAX_QUERY_CACHE_ENTRIES = 256`, `MAX_QUERY_CACHE_WEIGHT_BYTES = 8L * 1024L * 1024L`, `QueryCacheKey` (`SemanticSearchService.java`) | conservées (corrigé : gate absent de la première rédaction) |
| `scripts/architecture/check-module-boundaries.py` | A14 `:612-636` | racines de packages par module ; `store` n'appartient qu'à `minos-engine` | un package `com.minos.store.cache` ne crée pas de racine (D2) |
| | A15 `:842-972` | tout type public de production a un appelant de production, sinon une entrée motivée (cliquet) | la classe partagée n'est pas livrée sans un premier cache migré (D5) |
| | A3, A8 | un package par module, aucun cycle | le package d'accueil est une feuille (JDK seul) |

Scopes JaCoCo (`scripts/quality/check-jacoco.py`, préfixes résolus) : `ProgramGraphService` → `program-graph-analysis` ; `FingerprintConstrainedJavaProgramGraphProvider` → `java-advanced-provider` ; `SemanticSearchService`, `HybridSearchService`, `SemanticIndexService` → `semantic-hybrid-retrieval` ; `FileSemanticVectorStore` → `semantic-vector-store` ; `FileSymbolSnapshotStore` → `persistence-cache-indexes` ; `PostgresCodeKnowledgeSnapshotStore` → `m30-postgresql-pgvector` (sauté sur Windows, `pr-ci.yml:356`) ; `RuntimeIntelligenceService` → `m26-runtime-dynamic-intelligence` (corrigé : absent) ; `RemoteCloneBudget`, `DeterministicJson`, `SourceStateKeyMemo` et tout `com/minos/store/cache/` → `global` seulement (corrigé : `RemoteCloneBudget` n'est pas dans `m25-remote-distributed-indexing` ; `advanced-impact-security` ne couvre aucune classe touchée).

Base SpotBugs : `quality/spotbugs-baseline/minos-storage-local.xml` porte une alerte `EI_EXPOSE_REP` sur `FileSymbolSnapshotStore.retentionService` (`:698-714`), sans rapport avec le cache. SpotBugs ne tourne que dans `code-audit.yml` (déclenchement manuel, profil `audit-spotbugs`) ; la comparaison par `instanceHash` se fait par `python scripts/quality/audit-report-summary.py spotbugs --baseline-dir quality/spotbugs-baseline`.

Tests existants des caches : `FileSymbolSnapshotStoreCacheTest`, `-RetryTest`, `-IntegrityTest`, `QueryViewWeightTest`, `FileSemanticVectorStoreTest`, `-ReuseTest` (`minos-storage-local`) ; `PostgresCodeKnowledgeSnapshotStoreCacheTest` (`minos-storage-postgresql`, base réelle) ; `ProgramGraphSourceStateReuseTest`, `AdvancedImpactAdjacencyReuseTest`, `HybridQueryCostTest`, `SemanticQueryPathStatesTest`, `SourceStateKeyMemoTest` (`minos-application`) ; `ProgramGraphServiceConcurrencyTest`, `HybridCorpusWeightTest` (`minos-bootstrap`). Aucun test ne nomme un helper d'addition ni n'atteint un champ de cache par réflexion.

### Écarts avec l'action proposée par l'audit

1. **« Huit caches » : sept caches pondérés**, sur neuf cartes à ordre d'accès. `HostedDenialThrottle` et `SourceStateKeyMemo` n'ont pas de poids ; les plier dans une classe générique leur ferait porter un poids factice.
2. **« Treize helpers » : vingt et un**, et `checkedAdd` a lui aussi deux comportements ; `saturatingAdd` en a deux face aux opérandes négatifs (refus dans `ScipIngestionLimits` seulement).
3. **Les sept caches ne sont pas identiques** : `FileSemanticVectorStore` n'a pas de borne en entrées ; `SemanticSearchService` ne teste pas l'entrée trop lourde ; deux caches ne comptent pas les évictions ; trois invalident les entrées du même projet avant d'insérer. Le contrat partagé (D4) doit le permettre sans changer le comportement observable.
4. **Les noms que la migration supprime sont dans deux gates** (`check-mnd.py`, `check-mne.py`) ; `check-p0-p2.py` et `check-semantic-retrieval-consistency.py` lisent les mêmes fichiers et restent verts si les noms conservés par D5 le restent.
5. **Ordre des lots.** Les cinq changements de performance qui bloquaient le lot 2 sont archivés le 2026-10-15 (`openspec/changes/archive/2026-10-15-ramener-le-poids-des-vues-a-la-mesure`, `…-lire-l-identite-du-snapshot-actif-sans-le-charger`, `…-ne-plus-hacher-les-sources-avant-le-cache-du-graphe`, `…-synchroniser-l-index-semantique-par-differentiel`, `…-lire-chaque-source-une-fois-par-fichier`). Cette contrainte est levée ; celle qui reste est l'ordre avec `exposer-un-port-de-lecture-de-snapshot` (ci-dessous). L'ordre lot 1 puis lot 2 tient toujours : le lot 1 est sans dépendance et réduit le diff du lot 2.

### Recouvrements avec les changements actifs

| Changement | Point de contact | Conduite |
|---|---|---|
| `exposer-un-port-de-lecture-de-snapshot` (1/22) | sa tâche 2.2 change la valeur mise en cache par `FileSymbolSnapshotStore` et `PostgresCodeKnowledgeSnapshotStore` ; son design (tableau des recouvrements, relu le 2026-10-22) demande que la migration de ces deux caches fusionne **avant** ; son design (liste des « Littéraux des stores, à conserver ») cite `safeMultiply` de `check-mne.py:86` parmi les littéraux « à conserver » | ordre recommandé : lot 1, migration des stores (tâche 4.4), puis sa tâche 2.2 ; le cache partagé est générique, le type de valeur change sans le toucher. Après le lot 1, cette mention est périmée (`saturatingMultiply`) : à signaler dans la PR |
| `calculer-le-rang-de-symbole-une-fois` (0/9) | sa décision D2 (précalcul dans la vue, non recommandé) toucherait l'estimation de poids des deux stores et `check-mne.py:86` | conflit textuel seulement |
| `decouper-les-classes-de-plus-de-800-lignes` (0/18) | modifie d'autres lignes de `check-mnd.py` (`:60`, `:118`, `:122`) et de `check-p0-p2.py` (`:130`, `:139`) ; crée des classes dans `com.minos.io` (`minos-engine`) | conflits triviaux dans les scripts ; aucun package commun avec `com.minos.store.cache` ; aucune classe touchée ici ne dépasse 800 lignes (la plus longue : `PostgresCodeKnowledgeSnapshotStore`, 536) |
| `decouper-les-methodes-d-analyse-et-d-ingestion` (0/10) | `ImpactAnalysisService`, `ScipIngestionAdapter`, `CodeSearchService`, `IndexingLifecycleService` | aucun fichier commun ; `ScipIngestionLimits`, voisin de `ScipIngestionAdapter`, n'est pas renommé ici |

## Goals / Non-Goals

**Goals** : un nom, un comportement ; un seul endroit où l'invariant d'un cache LRU pondéré est écrit et testé ; aucun comportement observable modifié hors du chemin inatteignable de `SemanticSearchService` (D4).

**Non-Goals** : changer une borne, un plafond ou une estimation de poids ; unifier ce qui n'a pas la même nature (fenêtres de refus, mémo à délai) ; créer un module ; corriger la sémantique des opérandes négatifs des helpers existants.

## Decisions

### D1. Noms par comportement, renommage en place (lot 1)

`saturatingAdd` / `saturatingMultiply` pour ce qui sature ; `addOrThrow` (suffixé si utile) pour ce qui lève ; `checkedAdd` n'est gardé que pour ce qui lève. Les huit `safeAdd`, le `checkedAdd` de `SemanticSearchService` et les trois `safeMultiply` sont renommés dans leur classe, sans extraction ni ligne de calcul modifiée. `check-mne.py:86` passe de `safeMultiply` à `saturatingMultiply(Files.size(snapshot)` dans la même tâche : l'ancre est plus forte (l'appel, pas seulement le nom). La première rédaction gardait `safeMultiply` dans `FileSymbolSnapshotStore` à cause de `ramener-le-poids-des-vues-a-la-mesure`, archivé depuis : cette exception n'a plus de raison.

Table de renommage (tâche 1.1, relue au HEAD d'implémentation `0c470fb7`) :

| Site | Nom actuel | Comportement au dépassement | Nom cible |
|---|---|---|---|
| `HybridSearchService.java:249`, `SemanticIndexService.java:236`, `FingerprintConstrainedJavaProgramGraphProvider.java:163`, `FileSemanticVectorStore.java:474`, `FileSymbolSnapshotStore.java:393`, `PostgresCodeKnowledgeSnapshotStore.java:507` | `safeAdd` | sature à `Long.MAX_VALUE` | `saturatingAdd` |
| `DeterministicJson.java:241` | `safeAdd` | lève `OutputBudgetExceededException` | `addOrThrow` |
| `RuntimeIntelligenceService.java:311` | `safeAdd(…, label)` | lève `IllegalStateException` | `addOrThrow` |
| `SemanticSearchService.java:129` | `checkedAdd` | sature à `Long.MAX_VALUE` | `saturatingAdd` |
| `SemanticIndexService.java:240`, `FileSymbolSnapshotStore.java:397`, `PostgresCodeKnowledgeSnapshotStore.java:511` | `safeMultiply` | sature à `Long.MAX_VALUE` | `saturatingMultiply` |
| `RemoteCloneBudget.java:143`, `FileSemanticVectorStore.java:444` | `checkedAdd` | lève `IOException` | inchangé (le nom dit l'échec) |
| `saturatingAdd` (5), `saturatingMultiply` (2) | — | sature | inchangés |

### D2. Module et package du cache partagé — **décidé par le propriétaire le 2026-10-24**

Décision : **`minos-engine`, package neuf `com.minos.store.cache`**, classe `WeightedLruCache` (la recommandation est retenue).

- Direction (ADR 0022) : les trois modules consommateurs (`minos-application`, `minos-storage-local`, `minos-storage-postgresql`) dépendent déjà de `minos-engine` (POM relus) ; aucune arête nouvelle. `minos-application` est exclu (les stores n'en dépendent pas : ce serait inverser la direction) ; `minos-storage-local` aussi (l'application n'en dépend pas, et le store PostgreSQL ne nomme que les quatre types de sa liste A13, `check-module-boundaries.py:604-609`).
- Propriété (ADR 0044, ADR 0062, A14) : la racine `store` n'appartient qu'à `minos-engine` (`:618-621`) ; le sous-package ne crée ni racine ni package partagé. Il est une feuille (JDK seul) : A8 reste à zéro cycle.
- Alternative : `minos-domain`. Plus en amont, mais le domaine porte le modèle, pas une structure de données technique, et sa liste A14 (`domain`, `program`, `semantic`) imposerait une racine nouvelle, donc une décision au sens de l'ADR 0062.

**D2b. Arithmétique bornée partagée** (action de l'audit, `BoundedArithmetic`) — **décidé par le propriétaire le 2026-10-24** : **aucune classe `BoundedArithmetic` partagée dans ce changement** (la recommandation est retenue). Le lot 1 suffit à lever l'ambiguïté ; une classe partagée toucherait sept modules (dont `minos-runtime-local`, `minos-provider-scip`, `minos-integration-git`) pour des fonctions d'une ligne et devrait trancher la sémantique des négatifs (écart 2). Si le propriétaire la veut, elle vit dans `minos-engine` (seul module en amont de tous ses appelants, hors domaine) et fait l'objet d'un changement distinct.

### D3. Structures sans poids — **décidé par le propriétaire le 2026-10-24**

Décision : **exclure `HostedDenialThrottle` et `SourceStateKeyMemo`**, inscrits dans la liste fermée du gate avec leur raison (« fenêtres de refus glissantes, sans poids » ; « mémo à délai de réutilisation, borné en nombre, sans poids »). Alternative écartée : leur donner un poids unitaire ; cela ne partage que l'éviction par nombre, au prix d'un couplage et d'une sémantique de délai que le cache n'a pas.

Les autres décisions (D1, D4, D5, D6) suivent les recommandations de ce document, sur instruction du propriétaire du 2026-10-24.

### D4. Contrat du cache partagé

- Bornes fixées à la construction : nombre d'entrées ≥ 1 (une borne « illimitée » s'exprime par `Integer.MAX_VALUE`, pour `FileSemanticVectorStore`) et plafond de poids dans `[1, Long.MAX_VALUE / 2]` ; hors de ces plages, `IllegalArgumentException`. Chaque entrée conservée étant ≤ plafond, la somme ne peut pas déborder.
- Le poids d'une entrée est fourni à l'insertion ; un poids négatif est **refusé** (`IllegalArgumentException`, cache inchangé) au lieu d'être masqué par une remise à zéro ; il est retranché à chaque retrait, remplacement, invalidation ou éviction.
- Une entrée plus lourde que le plafond n'est **pas** conservée et n'évince rien ; l'ancienne valeur de la **même clé** est retirée (comportement des caches qui testent ce cas). Pour `SemanticSearchService`, c'est un changement sur un chemin inatteignable (poids d'entrée de l'ordre de 200 Kio au plus pour un plafond de 8 Mio) : à dire dans la PR.
- Éviction par ordre d'accès, de la plus anciennement lue, tant que l'une des deux bornes est dépassée.
- Retrait par prédicat sur la clé, pour les invalidations par projet (`removeSupersededEntries`, `removeProjectEntries`).
- Statistiques du cache : entrées, poids, évictions de capacité. Succès, échecs et durées restent comptés par la classe propriétaire ; chaque record public de statistiques garde ses noms et ses valeurs (une invalidation de `FileSemanticVectorStore` comptée comme éviction le reste, par lui).
- Concurrence : toutes les opérations, lectures comprises, sont sérialisées par le moniteur du cache (une lecture en ordre d'accès modifie la carte). Les séquences composées des propriétaires (relecture après course, invalidation puis insertion) restent sous leur verrou actuel ; l'ordre des verrous est toujours propriétaire → cache, et le cache n'appelle aucun code extérieur sous son moniteur (le poids est calculé avant l'appel).
- Les verrous de construction par bande (`buildLocks`) ne font **pas** partie du cache.

### D5. Migration un cache à la fois, gate réécrit dans la même tâche

Pour chaque cache : tests existants verts avant, migration, tests verts après, puis réécriture de l'assertion devenue fausse. Les noms publics ou exigés qui gardent un sens sont **conservés** : `queryCache`, `MAX_QUERY_CACHE_WEIGHT_BYTES`, `MAX_QUERY_CACHE_ENTRIES`, `buildLocks`, `fingerprintCacheStats`, `public CacheStats cacheStats()`, `maximumWeight`, `evidenceWeight`, `stringWeight`, les constantes `DEFAULT_MAX_*`. Ceux qui désignaient le code d'éviction supprimé (`trimCache`, `trimCorpusCache`, et `WeightedGraph` s'il disparaît) sont remplacés dans `check-mnd.py` par une chaîne qui prouve l'usage du cache partagé (déclaration du champ), sans affaiblir l'invariant protégé (cache borné en poids). La classe partagée n'est livrée qu'avec un premier cache migré (A15) : pas d'entrée temporaire dans `UNREFERENCED_PRODUCTION_TYPES`.

### D6. Un gate neuf, à liste fermée

`scripts/quality/check-bounded-helpers.py` (nouveau, `--self-test` sur arbres temporaires, racine paramétrable, câblé dans le job `invariants` de `pr-ci.yml`) refuse dans `minos-*/src/main/**` : une déclaration de méthode `safeAdd` ou `safeMultiply` (toute signature) ; une déclaration `checkedAdd` dont le corps ne contient pas `throw` (lot 1) ; une construction `new LinkedHashMap<…>(…, true)` (espaces et retours à la ligne admis) hors de la classe partagée et hors de la liste fermée (lot 2). Une entrée de liste qui ne correspond plus à rien fait échouer le gate. Le gate affiche le nombre de helpers contrôlés et la liste fermée.

### Écarts constatés à la mise en œuvre (2026-10-24)

- **Code extérieur sous le verrou du cache** (D4) : le prédicat de `removeIf`, ainsi que `equals` et `hashCode` des clés, s'exécutent sous le verrou privé du cache. Ce sont des comparaisons de records sans effet de bord ; la Javadoc de `WeightedLruCache` interdit qu'ils rappellent le cache. Le poids, lui, est toujours calculé avant l'appel.
- **Borne haute du plafond** : les constructeurs de visibilité package de `ProgramGraphService`, `FileSemanticVectorStore` et `FileSymbolSnapshotStore` acceptaient un plafond jusqu'à `Long.MAX_VALUE` ; au-delà de `Long.MAX_VALUE / 2`, le cache partagé lève désormais `IllegalArgumentException`. Aucun appelant ni test ne passe une telle valeur.
- **Poids négatif au point d'appel** (tâche 4.2) et **entrée trop lourde de `SemanticSearchService`** (tâche 4.3) : inatteignables par l'API (sommes saturantes de termes positifs ; dimensions ≤ 16 384 et requête ≤ 64 Kio, soit ≤ ~200 Kio pour un plafond de 8 Mio). Le comportement est prouvé par `WeightedLruCacheTest`, pas par un test de la classe propriétaire.
- **`FileSymbolSnapshotStore`** : le remplacement d'une entrée de même clé retranche maintenant le poids de l'ancienne (dérive vers le haut corrigée) ; le record `WeightedReadView`, qui refusait un poids < 1, disparaît (le poids estimé vaut au moins 64 Kio).
- **`FileSemanticVectorStore`** : `removeCached(…, eviction)` est conservé (l'appel `removeCached(snapshot.projectId(), false)` est exigé par `check-semantic-retrieval-consistency.py`) ; une invalidation marquée éviction resterait comptée par le store, en plus des évictions du cache.
- **`SemanticSearchService`** : la première lecture du cache ne prend plus le moniteur du propriétaire (le cache sérialise lui-même) ; la séquence relecture puis insertion reste sous ce moniteur.

## Direction des dépendances

`minos-engine` reçoit la classe ; `minos-application`, `minos-storage-local` et `minos-storage-postgresql` l'importent, dans le sens de l'ADR 0022. Aucun adaptateur n'importe un autre adaptateur (A13 inchangée) ; aucune surface ne nomme la classe (A11 inchangée). Le lot 1 n'ajoute aucune dépendance.

## Qualification des capacités

Sans objet : aucune capacité produit nouvelle ni modifiée. Le cache partagé est un composant interne, qualifié par ses propres tests (bornes, poids, refus, concurrence).

## Windows et Linux

Aucune différence de comportement : code JDK pur, sans E/S. Le store PostgreSQL ne se teste qu'avec une base réelle (Docker, `"-Dminos.postgresql.tests.required=true"`) ; sur Windows, le scope `m30-postgresql-pgvector` est sauté (`pr-ci.yml:356`) et la couverture de ce store vient du job Linux. Les tests de concurrence utilisent des barrières, pas des délais, pour tenir sur les deux systèmes.

## Risks / Trade-offs

- **SonarCloud** (nouveau code par module, seuil 80 %) : la classe partagée est du code neuf de `minos-engine`, couvert par **ses propres tests dans ce module** ; les lignes renommées (lot 1) et migrées (lot 2) comptent comme nouveau code de chaque module. Plusieurs caches de `minos-application` ne sont exercés que par des tests de `minos-bootstrap` (`ProgramGraphServiceConcurrencyTest`, `HybridCorpusWeightTest`) : prévoir un test dans `minos-application` si le rapport de la PR montre ces lignes non couvertes.
- Une différence fine entre deux caches (écart 3) peut être gommée : d'où la migration un par un et le tableau ci-dessus, à relire au HEAD de la branche d'implémentation.
- Un test de concurrence instable serait pire qu'absent : barrières déterministes, aucun `sleep`.
- Conflits de fusion avec `exposer-un-port-de-lecture-de-snapshot` sur les deux stores et `check-mne.py` : ordre recommandé ci-dessus.
- Le refus d'un poids négatif est un nouvel échec possible ; aucun appelant relu n'en produit, et un test par cache migré le confirme.
