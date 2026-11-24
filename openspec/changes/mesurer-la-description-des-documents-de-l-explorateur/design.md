# Design

## Contexte

Motivation : voir `proposal.md` (Why). Le changement ajoute une mesure et un test de caractérisation dans `minos-application` (paquet `com.minos.application.semantic`, sources de test) ; il ne touche le code de production que si la mesure franchit le seuil de D3, et alors seulement l'index en mémoire de `minos-engine` (`com.minos.store`). Aucune dépendance nouvelle entre modules : `minos-application` lit déjà `SnapshotReadView` et `CodeKnowledgeReader` (`minos-engine`), direction ARCH-0068 respectée. Le code concerné est du Java pur, sans branche propre à Windows ou à Linux.

## État vérifié au HEAD

Relu au commit `535a50176` (PR #804), worktree `docs/specifier-le-sprint-10-de-l-audit-2026-11-15`.

| Fait | Preuve |
|---|---|
| Une page filtrée balaie au plus `MAX_SCANNED_DOCUMENTS = 2 000` documents, puis rend un curseur et `SEMANTIC_DOCUMENT_FILTER_SCAN_BOUNDED` | `minos-application/src/main/java/com/minos/application/semantic/SemanticCorpusQueryService.java:48`, `:160-167` |
| Une page non filtrée lit `request.limit()` documents (au plus 200) ; une page filtrée lit le magasin par lots de 200 | `SemanticCorpusQueryService.java:41`, `:147` |
| `DocumentWalk.select` décrit **chaque** document lu par `lookup.summarize`, avant le filtre | `SemanticCorpusQueryService.java:186-198` |
| `summarize` : pour un document non `FILE`, `findSymbolById(sourceId)` ; le symbole de fichier n'est cherché que si ce symbole manque (`Optional.or`) | `SemanticCorpusQueryService.java:722-735` |
| `fileSymbol` appelle `findFileSymbols(projectId, fileId, Integer.MAX_VALUE)` puis parcourt toute la liste pour garder la plus petite `symbolKey` ; l'ordre rendu ne lui sert pas | `SemanticCorpusQueryService.java:741-750` |
| `SymbolLookup` ne tient que le lecteur de la vue ; il est créé par requête (`lookup`, `:403-413`) et gardé par `DocumentWalk` ou une variable locale | `SemanticCorpusQueryService.java:111`, `:135`, `:202`, `:225`, `:258`, `:306`, `:699-718` |
| Quatre routes décrivent des documents : liste (au plus 2 000 balayés), détail (1), voisins (au plus 200), recherche (au plus la limite de la requête) | `SemanticCorpusQueryService.java:192`, `:228`, `:265`, `:312` |
| Un document `SYMBOL` et un document `CHUNK` portent `symbol.id()` comme `sourceId` ; un document `FILE` est créé une fois par fichier qui porte un symbole localisé, avec `sourceId = fileId` | `minos-application/src/main/java/com/minos/application/semantic/SemanticDocumentFactory.java:80-83`, `:101-107` |
| Les documents sont rangés par clé stable : `chunk:…`, puis `file:…`, puis `symbol:…` ; les documents `FILE` sont contigus | `SemanticDocumentFactory.java:101`, `:106`, `:111` |
| `findFileSymbols` lit la liste du fichier dans `symbolIndexes.byFileId`, la copie et la trie (ligne, colonne, nom qualifié, nom, id) à chaque appel | `minos-engine/src/main/java/com/minos/store/InMemoryCodeKnowledgeStore.java:224-240` |
| `byFileId` range un symbole sous `fileId()` et, s'il diffère, sous `location().fileId()` ; la liste est figée dans l'ordre d'insertion | `InMemoryCodeKnowledgeStore.java:654-686` (`:672-677`) |
| L'autre consommateur de `findFileSymbols` est `SymbolQueryService.getFileSymbols`, qui rend l'ordre trié | `minos-engine/src/main/java/com/minos/query/SymbolQueryService.java:40-42` |
| Les deux backends construisent leurs vues par `SnapshotReadView.indexed`, donc avec le même lecteur en mémoire | `minos-engine/src/main/java/com/minos/store/SnapshotReadView.java:41-47` ; `minos-storage/src/main/java/com/minos/storage/local/store/FileSymbolSnapshotStore.java:353` ; `minos-storage/src/main/java/com/minos/storage/postgresql/snapshot/PostgresCodeKnowledgeSnapshotStore.java:232` |
| `SnapshotReadView` a un constructeur public qui accepte tout `CodeKnowledgeReader` : un test peut compter les lectures par un lecteur délégant | `SnapshotReadView.java:25-38` |
| Le double de test `FixedSnapshotStore` bâtit la vue par `SnapshotReadView.indexed` et compte les demandes de vue | `minos-application/src/test/java/com/minos/application/semantic/SemanticCorpusTestDoubles.java:110-136` |
| Un test exerce déjà le plafond de 2 000 documents, sans symboles à décrire en nombre | `minos-application/src/test/java/com/minos/application/semantic/SemanticCorpusQueryServiceTest.java:318-330` |
| Le banc HTTP publie un seul symbole (`src/Benchmark.java`) ; ses documents sont des `CHUNK` de `sourceId` `source-N` sur `src/FileN.java` : `findSymbolById` échoue, `fileSymbol` est appelé pour chaque document et rend une liste vide | `minos-explorer/src/test/java/com/minos/explorer/SemanticExplorerBenchmark.java:256`, `:705-712` ; `benchmarks/scalability/semantic.json` |
| Une mesure opt-in existe déjà sur le même modèle pour la projection : `@EnabledIfSystemProperty`, relevé sous `target/` | `minos-bootstrap/src/test/java/com/minos/bootstrap/semantic/SemanticProjectionMeasurementTest.java:39-44`, `:68` |
| L'exigence « La table de symboles de l'explorateur ne survit pas à sa vue » interdit tout objet hors de la vue qui retienne ses symboles une fois la vue évincée et aucune requête en cours | `openspec/specs/exploration-semantique/spec.md:489-514` |
| Le scope JaCoCo `explorer-surface` couvre le service ; `persistence-cache-indexes` couvre l'index en mémoire | `scripts/quality/check-jacoco.py:60-73`, `:124-130` |
| La règle A18 interdit de nommer `InMemoryCodeKnowledgeStore` hors de `com.minos.store` dans le code de production | `scripts/architecture/module_boundary_ratchets.py:483-507` |
| `docs/developer/semantic-explorer.md` dit encore que module et langage sont « lus par `loadActiveKnowledge` et gardés pour le dernier couple projet / snapshot » et liste « tables de symboles gardées : 4 projets » ; le service n'a plus que `MAX_FINGERPRINTED_PROJECTS = 64` | `docs/developer/semantic-explorer.md:49-52`, `:197` ; `SemanticCorpusQueryService.java:68-72` |
| La tâche 1.4 d'AUD-PERF-14 est ouverte : la fixture du test ne porte que quelques symboles | `openspec/changes/archive/2026-11-20-liberer-la-table-de-symboles-avec-sa-vue/tasks.md:14-15` |

Non reproduit ici : la sonde javac de l'audit (1 à 3 ms par document de fichier à 500 à 5 000 symboles, `ETAT-2026-11-20.md`, section AUD-PERF-14). Aucun build ni test n'a été lancé pour cette spécification.

## Écarts

| Écart | Source A | Source B | Suite proposée |
|---|---|---|---|
| 1. L'audit dit que le coût vaut « pour les documents FILE et CHUNK » et qu'« un CHUNK n'a pas de symbole » | `findings.json` (AUD-PERF-15, impact) ; `ETAT-2026-11-20.md` (AUD-PERF-14) | un `CHUNK` porte `symbol.id()` (`SemanticDocumentFactory.java:101-104`) ; résolu par `findSymbolById`, il n'atteint pas `fileSymbol` (`SemanticCorpusQueryService.java:725`) | coût limité aux documents `FILE` et aux documents dont le symbole est introuvable ; gardé par un test de caractérisation (exigence ajoutée) |
| 2. L'action propose de mémoïser la description par `fileId` le temps d'une requête | `findings.json`, action | un document `FILE` est unique par fichier (`SemanticDocumentFactory.java:106-107`) : une page ne demande chaque fichier qu'une fois, la mémoïsation n'évite aucune lecture sur un index aligné | option écartée par défaut (D4, option A) ; gardée seulement si la mesure montre des lectures répétées |
| 3. L'audit borne une page à « 2 000 documents × 1 à 3 ms », soit de l'ordre de la seconde | `findings.json`, impact | les 2 000 documents d'une page sont des fichiers distincts : une page trie au plus, morceau par morceau, les symboles localisés du snapshot une fois (Σ n·log n ≤ N·log N) ; parcourir tout l'index le fait aussi une fois, comme l'ancienne table à la première page d'une vue ; seule la répétition d'une même page répète le coût | à mesurer (D2), l'ordre de grandeur annoncé est un majorant |
| 4. La tâche 1.4 voulait mesurer « sur la fixture du test » | `tasks.md:14` (archive) | cette fixture ne porte que quelques symboles ; le banc HTTP n'en porte qu'un (`SemanticExplorerBenchmark.java:256`) : aucun ne mesure ce coût | mesure dédiée à gros fichiers (D2) |
| 5. La page développeur décrit une table gardée qui n'existe plus | `docs/developer/semantic-explorer.md:49-52`, `:197` | `SemanticCorpusQueryService.java:68-72`, `:699-718` | corrigée par la tâche de documentation |
| 6. Sur PostgreSQL, « même ordre de grandeur attendu, non mesuré » | `findings.json`, impact | le lecteur est le même objet (`PostgresCodeKnowledgeSnapshotStore.java:232`) ; seul le chargement de la vue diffère, hors de ce coût | mesure du service suffisante ; mesure PostgreSQL de bout en bout facultative et manuelle |

## Exigences couvertes

- exploration-semantique › Un document n'est décrit par les symboles de son fichier qu'une fois par requête et sans table gardée — D4, « Vérification ».
- exploration-semantique › Le temps d'une page de documents est mesurable sur des fichiers à nombreux symboles — D2, D3.
- exploration-semantique › La table de symboles de l'explorateur ne survit pas à sa vue — inchangée ; toute correction (D4) la respecte et ses tests restent verts.

## Objectifs et non-objectifs

Objectifs : mesurer le temps d'une page filtrée et d'un parcours complet sur des fichiers à nombreux symboles ; décider sur cette mesure ; fermer la tâche 1.4 d'AUD-PERF-14. Non-objectifs : seuil de performance en CI, changement de sortie, table de symboles hors de la vue.

## Responsabilités et frontières des composants

- `SemanticCorpusQueryService.SymbolLookup` décrit les documents d'une requête par les lectures de la vue, sans état au-delà de la requête. [Existant vérifié]
- `InMemoryCodeKnowledgeStore` (dans la vue) porte seul les index de symboles d'un snapshot et l'ordre des symboles d'un fichier. [Existant vérifié]
- La mesure vit dans les sources de test de `minos-application`, à côté des doubles existants, et ne nomme pas `InMemoryCodeKnowledgeStore` (elle passe par `SnapshotReadView`). [Proposition]

## Modèle de domaine et invariants

Invariant inchangé : le symbole qui décrit un fichier est celui de plus petite `symbolKey` parmi les symboles dont l'emplacement (ou, sans emplacement, le `fileId`) nomme ce fichier.

## Traitements et interactions

Question : quels documents d'une page lisent les symboles de leur fichier, et combien de fois ?

```mermaid
sequenceDiagram
    participant W as DocumentWalk (une requête)
    participant L as SymbolLookup (une requête)
    participant R as lecteur de la vue
    loop chaque document lu (au plus 2 000 si filtré)
        W->>L: summarize(document)
        alt SYMBOL ou CHUNK
            L->>R: findSymbolById(sourceId)
            R-->>L: symbole (trouvé sur un index aligné)
        else FILE (un par fichier)
            L->>R: findFileSymbols(fileId, MAX)
            Note over R: copie + tri de tous les symboles du fichier (HEAD)
            R-->>L: liste ; plus petite symbolKey retenue
        end
    end
    Note over W,L: rien n'est gardé après la réponse
```

Réponse : seuls les documents `FILE` (et un document dont le symbole manque) lisent leur fichier, une fois chacun.

## États et transitions

Non applicable.

## Contrats d'interface

Aucune méthode publique ne change. Avec D4 = B, `findFileSymbols` garde sa signature et son ordre ; seule sa mise en œuvre en mémoire change.

## Persistance et intégrité

Non applicable : aucun format persisté touché.

## Transactions, concurrence et idempotence

La vue est partagée par les requêtes simultanées (au plus 4 appels d'API). Avec D4 = B, l'ordre est calculé à la construction de l'index, avant toute publication de la vue : aucune écriture concurrente après construction, listes immuables.

## Sécurité et isolation

Inchangé. La mesure écrit son relevé sous `target/` du module, sans chemin absolu dans le relevé.

## Erreurs et reprise

Inchangé. La mesure sautée sans sa propriété n'est pas un succès déclaré : le rapport Surefire la montre sautée.

## Observabilité

Non applicable en production. Le relevé de mesure donne, par fixture : nombre de fichiers et de symboles par fichier, temps de construction de la vue (`buildNanos`), médiane et p95 de la page filtrée la plus lente, durée du parcours complet, nombre d'appels `findFileSymbols` et de symboles rendus.

## Performance et mesure

Protocole (D2), même machine, même JDK, aucune autre charge Maven en parallèle :

1. Fixtures synthétiques construites en mémoire, documents à la forme de `SemanticDocumentFactory` (un `SYMBOL` et un `CHUNK` par symbole, un `FILE` par fichier, clés stables identiques) : `2 000 × 20`, `400 × 500`, `40 × 5 000` (fichiers × symboles par fichier), plus une fixture « réaliste » à la distribution de `morpheus-engine` (60 472 symboles sur 1 488 fichiers, `docs/developer/indexing-and-storage.md:254`). Symboles tous localisés, `symbolKey` en ordre inverse de la position pour que le tri ne soit pas trivial.
2. Requête filtrée qui ne retient rien (module absent) : chaque page balaie 2 000 documents. Mesures : la page placée sur la région `file:` (la plus coûteuse), et le parcours complet par curseur.
3. 3 itérations d'échauffement, 15 mesurées ; médiane et p95 par `System.nanoTime` ; trois exécutions de la JVM.
4. Relevé dans `minos-application/target/explorer-page-measurements.md`, recopié dans la PR et dans ce design.
5. « Après » : même protocole sur la correction si D3 la déclenche ; sinon « après = avant », consigné tel quel.

Comparaison facultative (manuelle) avec la table d'avant la PR #797 : la classe de mesure copiée dans un worktree jetable au commit `acd79cb86`, même protocole.

Non mesuré par ce protocole : le temps de bout en bout HTTP, le chargement de la vue, le backend PostgreSQL au-delà du lecteur commun, un snapshot réel (tâche manuelle).

## Compatibilité, migration et retour arrière

Aucune donnée ni sortie ne change ; retour arrière par revert.

## Décisions


Décision du propriétaire du 2026-11-24 : les recommandations de ce design sont retenues.
### D1 — Mesurer d'abord, corriger ensuite [Question, recommandation : mesurer d'abord]

Options :

- (A) **recommandée** : mesurer au HEAD, décider sur le seuil de D3 ; la correction n'est écrite que si le seuil est franchi. Raisons : l'écart 3 borne le coût par page à un tri des symboles du snapshot, du même ordre que l'ancienne table ; l'écart 2 rend inopérante la correction proposée par l'audit ; corriger sans mesure répéterait le défaut que le constat reproche.
- (B) corriger d'emblée (D4 option B) puis mesurer avant et après : coût faible, mais touche `minos-engine` et le scope `persistence-cache-indexes` sans besoin démontré.

### D2 — Instrument de mesure [Proposition, recommandation : test opt-in de `minos-application`]

Options :

- (A) **recommandée** : test JUnit `SemanticCorpusPageMeasurementTest`, `@EnabledIfSystemProperty(named = "minos.explorer.page.measure", matches = "true")`, sur le modèle de `SemanticProjectionMeasurementTest` ; lancé par `./mvnw -B -ntp -pl minos-application -am test "-Dtest=SemanticCorpusPageMeasurementTest" "-Dminos.explorer.page.measure=true" "-Dsurefire.failIfNoSpecifiedTests=false"`. Mesure le service seul, isole le coût de description.
- (B) enrichir le banc HTTP `SemanticExplorerBenchmark` de symboles réels par fichier : mesure de bout en bout, mais bruitée par HTTP et le magasin, et le harnais est gardé par un test de fumée qui compte les mesures (spec `:236-247`) ; proposé comme suite, pas fait ici.
- (C) JMH : absent du dépôt ; nouvelle dépendance à justifier (règle de sécurité 10) ; écarté.

### D3 — Seuil de décision [Question, recommandation : 250 ms]

Recommandation : corriger (D4) si la médiane de la page filtrée la plus lente dépasse **250 ms** sur l'une des fixtures, ou si les lectures `findFileSymbols` y comptent pour plus de la moitié du temps de page et que la page dépasse 100 ms. Raisons : quatre appels d'API simultanés au plus et un délai de 30 s par appel ; 250 ms garde une page filtrée dans l'ordre de grandeur des autres routes mesurées (liste 11 ms p50, détail 47 ms, écart E11 du dossier de capacité) ; au-dessous, la correction ne paie pas la modification de `minos-engine`. Le propriétaire peut fixer un autre seuil ; il est consigné dans la PR avant la mesure « après ».

### D4 — Forme de la correction si le seuil est franchi [Question, recommandation : B]

Options :

- (A) mémoïser la description par `fileId` dans `SymbolLookup` le temps d'une requête : conforme à l'exigence d'AUD-PERF-14 (objet de requête, libéré avec elle) mais sans gain sur la liste (écart 2) ; écartée sauf lectures répétées constatées.
- (B) **recommandée** : `InMemoryCodeKnowledgeStore` range chaque liste de `byFileId` dans l'ordre de `findFileSymbols` à la construction de l'index (un tri par fichier et par vue, compté dans `buildNanos`), et `findFileSymbols` rend la liste figée ou son préfixe, sans copie ni tri. Les symboles restent dans la vue : l'exigence d'AUD-PERF-14 est respectée. Bénéficie aussi à `SymbolQueryService.getFileSymbols`. Coût : construction de vue un peu plus longue, à mesurer ; aucun tas en plus.
- (C) méthode additive de `CodeKnowledgeReader` qui rend le symbole de plus petite clé d'un fichier : élargit le port public du noyau pour un seul appelant ; écartée.

### D5 — DATA-0085 [Décision de forme]

Si D4 = B, le point 19 de DATA-0085 (`Proposed`, `docs/architecture/adr/DATA-0085-port-de-connaissance-et-persistance-des-snapshots-vues-comptees.md:56`) gagne une phrase au présent : l'index en mémoire range une fois par vue les symboles de chaque fichier. Sinon DATA-0085 n'est pas touché. Aucun ADR nouveau.

## Qualification des capacités

- Règle de lecture (un fichier lu au plus une fois, rien de gardé) : qualifiée par le test de caractérisation, Windows et Linux (CI `Verify`).
- Temps d'une page sur fixtures synthétiques : qualifié par la mesure sur le poste Windows ; Linux non qualifié tant que la mesure facultative sous WSL n'est pas faite.
- Temps d'une page sur un snapshot réel et sur PostgreSQL : non qualifié tant que les tâches manuelles ne sont pas faites.

## Windows et Linux

Aucune branche propre à un système dans le code touché. Les tests de caractérisation (et ceux de D4 = B) tournent sur les deux CI `Verify`. La mesure opt-in ne tourne pas en CI ; elle est faite sur le poste Windows, et facultativement sous WSL (Linux) avec le même protocole. Un écart de plus de 2× entre les deux systèmes serait consigné comme écart, pas comme régression.

## Risques et compromis

- Mesure bruitée sur un poste partagé : trois exécutions, médiane, et aucun build Maven parallèle (les builds simultanés cassent aussi AppContainer sur le poste).
- D4 = B allonge la construction de vue : mesurée par `buildNanos` dans le relevé ; l'ordre de `findFileSymbols` est gardé par les tests existants de l'index et par un test d'égalité avec le tri d'aujourd'hui.
- La fixture synthétique peut sous-estimer un snapshot réel : la tâche manuelle sur un snapshot réel le dit.

## Vérification

- Caractérisation (verte au HEAD) : un lecteur délégant compte les appels ; une page de documents `SYMBOL`, `CHUNK` et `FILE` sur un index aligné appelle `findFileSymbols` une fois par document `FILE` et jamais pour un `SYMBOL` ou un `CHUNK` résolu ; les tests d'éviction d'AUD-PERF-14 restent verts.
- Mesure « avant » : relevé du test opt-in au HEAD.
- Si D4 = B — rouge : test de `minos-engine` qui exige que deux appels de `findFileSymbols` sur le même fichier rendent la même liste sans copie (`assertSame`) et le même ordre qu'un tri de référence ; correction ; preuve : mesure « après », scopes `persistence-cache-indexes` et `explorer-surface`.
- `./mvnw -B -ntp -pl minos-application -am test` ; `python scripts/architecture/check-module-boundaries.py` ; `python .claude/scripts/run_gates.py`.

## Documentation durable à mettre à jour

- `docs/developer/semantic-explorer.md:49-52` (résolution des symboles par la vue), `:197` (ligne « tables de symboles gardées »), `:203-206` (commande de mesure).
- `docs/architecture/diagrams/capacites/exploration-semantique.md` : lignes de traçabilité des deux exigences ajoutées (après archivage), écart nouveau si la mesure Linux, PostgreSQL ou réelle manque.
- DATA-0085 point 19 si D4 = B.

## Traçabilité

| Constat | Exigence | Scénario | Décision | Tâches | Preuve attendue |
|---|---|---|---|---|---|
| AUD-PERF-15 | exploration-semantique › Le temps d'une page de documents est mesurable sur des fichiers à nombreux symboles | Mesure demandée ; Mesure absente du build | D1, D2 | 1.1-1.3 | relevé `target/explorer-page-measurements.md` au HEAD ; rapport Surefire du build sans la propriété : test sauté |
| AUD-PERF-15 | exploration-semantique › Un document n'est décrit par les symboles de son fichier qu'une fois par requête et sans table gardée | Page mêlée de symboles, fragments et fichiers ; Vue évincée après la page | D4 | 1.4 | test de caractérisation vert au HEAD et après ; tests d'éviction existants verts |
| AUD-PERF-15 | — | — | D3, D4 | 2.1 | seuil et forme consignés par le propriétaire dans la PR |
| AUD-PERF-15 | idem caractérisation | Page mêlée… | D4 = B | 3.1-3.4 | rouge `assertSame` au HEAD ; vert après ; mesure « après » ; JaCoCo |
| AUD-PERF-14 (réserve) | — | — | D2 | 1.3, 4.2 | tâche 1.4 d'AUD-PERF-14 fermée par le relevé ; comparaison avec `acd79cb86` facultative |
| AUD-PERF-15 | — | — | — | 4.1, 4.3 | mesure sur snapshot réel, PostgreSQL et Linux : manuelles, nommées comme manquantes si non faites |

## Questions ouvertes

D1, D3 et D4 attendent le propriétaire ; chacune a une recommandation. Aucune ne bloque la mesure « avant ».
