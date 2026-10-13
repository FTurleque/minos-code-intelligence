# Design

## Context

Sprint 11 « Hygiène de fond » de l'audit du 10 octobre 2026 : **AUD-QUA-05**. HEAD analysé : `949e1569` (`develop`). Chemins complets des classes citées par leur nom de fichier :

- `minos-application/src/main/java/com/minos/program/analysis/ProgramGraphService.java` et `FingerprintConstrainedJavaProgramGraphProvider.java` (même dossier) ;
- `minos-application/src/main/java/com/minos/application/semantic/SemanticSearchService.java`, `HybridSearchService.java`, `SemanticIndexService.java` ;
- `minos-storage-local/src/main/java/com/minos/storage/local/store/FileSemanticVectorStore.java`, `FileSymbolSnapshotStore.java` ;
- `minos-storage-postgresql/src/main/java/com/minos/storage/postgresql/snapshot/PostgresCodeKnowledgeSnapshotStore.java` ;
- `minos-engine/src/main/java/com/minos/hosted/HostedDenialThrottle.java` ;
- `minos-integration-git/src/main/java/com/minos/integration/git/RemoteCloneBudget.java`.

## État vérifié au HEAD

### AUD-QUA-05 — confirmé

- **Cartes à ordre d'accès** (recherche de `new LinkedHashMap<>(…, true)` dans `minos-*/src/main`) : 8 occurrences. Sept caches pondérés : `ProgramGraphService.java:44`, `FingerprintConstrainedJavaProgramGraphProvider.java:44`, `SemanticSearchService.java:27`, `HybridSearchService.java:42`, `FileSemanticVectorStore.java:61`, `FileSymbolSnapshotStore.java:59`, `PostgresCodeKnowledgeSnapshotStore.java:59`. `HostedDenialThrottle.java:28` porte des `ArrayDeque<Instant>` : fenêtres de refus, sans poids.
- **Helpers** (déclarations `private static long …`) : `safeAdd` × 8 (`RuntimeIntelligenceService.java:303`, `HybridSearchService.java:234`, `SemanticIndexService.java:204`, `DeterministicJson.java:241`, `FingerprintConstrainedJavaProgramGraphProvider.java:138`, `FileSemanticVectorStore.java:471`, `FileSymbolSnapshotStore.java:371`, `PostgresCodeKnowledgeSnapshotStore.java:456`) ; `saturatingAdd` × 5 ; `checkedAdd` × 3 (`SemanticSearchService.java:129`, `RemoteCloneBudget.java:143`, `FileSemanticVectorStore.java:441`) ; `safeMultiply` × 3 (`SemanticIndexService.java:208`, `FileSymbolSnapshotStore.java:375`, `PostgresCodeKnowledgeSnapshotStore.java:460`) ; `saturatingMultiply` × 2. Total 21.
- **Comportements relus** : `minos-application/src/main/java/com/minos/output/json/DeterministicJson.java:241-242` lève `OutputBudgetExceededException` ; `minos-application/src/main/java/com/minos/application/dynamic/RuntimeIntelligenceService.java:303-307` enveloppe `Math.addExact` dans une `IllegalStateException` ; les deux `checkedAdd` de `RemoteCloneBudget.java:143` et `FileSemanticVectorStore.java:441` déclarent `throws IOException`. La saturation des six autres `safeAdd` et de `SemanticSearchService.java:129` est reprise de la contre-vérification ; les corps des trois `safeMultiply` et des deux `saturatingMultiply` sont **non relus** (tâche 1.1).

**Non vérifié** : aucun build, test ni gate exécuté pendant cette analyse.

### Gates littéraux concernés (relus)

- `scripts/remediation/check-mne.py:86` exige `safeMultiply` dans `FileSymbolSnapshotStore.java`.
- `scripts/remediation/check-mnd.py:99-106` exige `queryCache`, `MAX_QUERY_CACHE_WEIGHT_BYTES` et `buildLocks` (store PostgreSQL), `WeightedGraph`, `evidenceWeight`, `stringWeight`, `maximumWeight` (`ProgramGraphService.java`), `trimCache`, `fingerprintCacheStats` (`FingerprintConstrainedJavaProgramGraphProvider.java`), `trimCorpusCache` (`HybridSearchService.java`).
- `scripts/remediation/check-p0-p2.py:221` exige `public CacheStats cacheStats()` ; `:305-312` exigent `ReentrantLock[] buildLocks`, `buildLock.lock()` et `buildLock.unlock()` dans `ProgramGraphService.java` et `FileSymbolSnapshotStore.java`.

### Écarts avec l'action proposée par l'audit

1. **« Huit caches » : sept.** `HostedDenialThrottle` n'est pas un cache pondéré ; le plier dans une classe générique lui ferait porter un poids factice.
2. **« Treize helpers » : vingt et un**, et `checkedAdd` a lui aussi deux comportements.
3. **L'extraction du cache supprime des chaînes que trois gates exigent.** Chaque migration réécrit l'assertion correspondante dans la même tâche, sinon le job `invariants` casse.
4. **`safeMultiply` de `FileSymbolSnapshotStore` ne se renomme pas sans toucher `check-mne.py:86`**, et le changement actif `ramener-le-poids-des-vues-a-la-mesure` modifie la même ligne : il reste hors du lot 1.
5. **Conflits de fusion certains** avec les changements actifs `ramener-le-poids-des-vues-a-la-mesure`, `lire-l-identite-du-snapshot-actif-sans-le-charger`, `ne-plus-hacher-les-sources-avant-le-cache-du-graphe`, `synchroniser-l-index-semantique-par-differentiel` et `lire-chaque-source-une-fois-par-fichier`. Le lot 2 passe après eux ; le lot 1 (renommage local) peut passer avant, au prix de conflits triviaux.

## Goals / Non-Goals

**Goals** : un nom, un comportement ; un seul endroit où l'invariant d'un cache LRU pondéré est écrit et testé ; aucun comportement observable modifié.

**Non-Goals** : changer une borne ; unifier ce qui n'a pas la même nature (fenêtres de refus) ; créer un module.

## Decisions

### D1. Noms par comportement, renommage en place (lot 1)

`saturatingAdd` pour ce qui sature ; un nom qui dit l'échec pour ce qui lève (`addOrThrow`, suffixé par ce qui est borné si utile). Les `safeAdd` et `checkedAdd` privés sont renommés dans leur classe, sans extraction : aucune classe neuve, aucun appelant externe. `safeMultiply` : renommé de même là où aucun gate ne l'exige ; conservé dans `FileSymbolSnapshotStore` (écart 4). Aucune ligne de calcul n'est modifiée.

### D2. Module et package de la classe partagée — **décision en attente du propriétaire**

Recommandation : `minos-engine`, dans un **nouveau package à propriétaire unique** (ADR 0044). Les trois modules consommateurs (`minos-storage-local`, `minos-storage-postgresql`, `minos-application`) sont en aval de `minos-engine` (ADR 0022) : aucune arête nouvelle. La classe ne dépend que du JDK.

Alternative : `minos-domain`. Plus en amont, mais le domaine n'a pas vocation à porter une structure de données technique.

Une classe d'arithmétique bornée partagée (action de l'audit, `BoundedArithmetic`) suit la même décision : elle n'est créée que si D2 est tranchée, dans le même package, et elle remplace alors les helpers renommés au lot 1.

### D3. `HostedDenialThrottle` — **décision en attente du propriétaire**

Recommandation : **exclu**. Il figure dans la liste fermée du gate avec sa raison (« fenêtres glissantes, non pondéré »). Alternative : l'inclure en donnant un poids unitaire à chaque entrée ; cela ne partage que l'éviction par nombre d'entrées, pour un couplage de plus.

### D4. Contrat de la classe partagée

Bornée en nombre d'entrées et en poids total ; le poids d'une entrée est fourni à l'insertion ; il est retranché à chaque retrait, remplacement ou éviction ; une entrée plus lourde que le plafond n'est **pas** mise en cache (comportement des caches actuels, à confirmer cache par cache en tâche 3.1) ; tous les accès, lectures comprises, sont sérialisés (une lecture en ordre d'accès modifie la carte) ; statistiques lisibles. Les verrous de construction par bande (`buildLocks`) ne font **pas** partie du cache : ils restent dans leurs classes, avec leurs chaînes exigées par `check-p0-p2.py`.

### D5. Migration un cache à la fois, gate réécrit dans la même tâche

Pour chaque cache : tests existants verts avant, migration, tests verts après, puis réécriture de l'assertion littérale devenue fausse. Les noms publics ou exigés qui gardent un sens sont **conservés** (`queryCache`, `MAX_QUERY_CACHE_WEIGHT_BYTES`, `fingerprintCacheStats`, `public CacheStats cacheStats()`) ; ceux qui désignaient le code d'éviction supprimé (`trimCache`, `trimCorpusCache`, `maximumWeight`) sont remplacés dans le gate par l'usage de la classe partagée.

### D6. Un gate neuf, à liste fermée

`scripts/quality/check-bounded-helpers.py` (nouveau, avec `--self-test`, câblé dans `invariants`) refuse dans `*/src/main/**` : une déclaration `long safeAdd(` ou une déclaration `checkedAdd` qui ne lève pas (lot 1) ; un `new LinkedHashMap<>(…, true)` hors de la classe partagée et hors de la liste fermée (lot 2). Une entrée de liste qui ne correspond plus à rien fait échouer le gate.

## Direction des dépendances

`minos-engine` reçoit la classe ; `storage` et `application` l'importent, dans le sens de l'ADR 0022. Aucun adaptateur n'importe un autre adaptateur. La règle A8 (`check-module-boundaries.py`) doit rester à 0 cycle avec le nouveau package.

## Qualification des capacités

Sans objet : aucune capacité produit nouvelle. Le cache partagé est un composant interne, qualifié par ses propres tests (bornes, poids, concurrence).

## Windows et Linux

Aucune différence de comportement. Le store PostgreSQL ne se teste qu'avec une base réelle (CI Linux) : dire dans la PR si ses tests ont tourné localement.

## Risks / Trade-offs

- **SonarCloud** : la classe partagée est du code neuf dans son module et doit atteindre 80 % **par ses propres tests dans ce module** ; les lignes renommées au lot 1 comptent aussi comme nouveau code et doivent être couvertes par les tests existants de chaque module.
- Une différence fine entre deux caches (ordre d'éviction, traitement de l'entrée trop lourde, moment où les statistiques sont comptées) peut être gommée par la mutualisation : d'où la migration un par un et le relevé 3.1.
- Un test de concurrence instable serait pire qu'absent : il utilise des barrières déterministes, pas des délais.
- Le lot 2 attend plusieurs changements : il peut rester ouvert longtemps. Le lot 1 se clôt seul.
