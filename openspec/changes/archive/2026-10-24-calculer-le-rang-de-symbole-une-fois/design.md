# Design

## Context

Sprint 11 « Hygiène de fond » de l'audit du 10 octobre 2026 : **AUD-PERF-10** (`docs/project/audits/2026-10-10/findings.md:514-520`, suivi `SUIVI.md:617`). Design écrit au HEAD `5ce7b79b` (`develop`, 2026-10-22), **relu en place le 2026-10-24** (tâche 1.1) au HEAD `1a3cc2dc` de la branche d'implémentation `perf/calculer-le-rang-de-symbole-une-fois`, empilée sur `refactor/decouper-les-methodes-d-analyse-et-d-ingestion` (PR #553), elle-même issue de `develop` `a8288b43`. Entre-temps, `exposer-un-port-de-lecture-de-snapshot` a été archivé (`openspec/changes/archive/2026-10-24-exposer-un-port-de-lecture-de-snapshot/`). `STORE` désigne `minos-engine/src/main/java/com/minos/store/InMemoryCodeKnowledgeStore.java` (699 lignes au HEAD `1a3cc2dc`).

## État vérifié au HEAD

### AUD-PERF-10 — confirmé à la lecture, puis mesuré (voir « Mesures »)

- `STORE:170-196` `findSymbols` : `:172-174` refuse un critère nul (`IllegalArgumentException`) ; `:180-184` filtres de projet, de nature, de module et de nom qualifié ; `:185` filtre `matchRank(symbol, normalizedQuery) < Integer.MAX_VALUE` ; `:186-193` tri dont la première clé (`:187`, `Comparator.comparingInt`) rappelle `matchRank` pour chacun des deux opérandes de chaque comparaison ; clés suivantes `external`, `generated`, `qualifiedName` (nuls en dernier), `name`, `signature` (nuls en dernier), `id` ; `:194` limite après le tri complet.
- `STORE:380-406` `matchRank` : `:381-383` rend 0 sans texte ; rangs 0 (nom qualifié égal), 1 (nom égal), 2 et 3 (préfixes du nom puis du nom qualifié), 4 et 5 (contient), **6** (la clé de symbole contient la requête, `:402-404`), puis `Integer.MAX_VALUE`.
- `STORE:455-465` : `equalsIgnoreCase`, `startsWithIgnoreCase` et `containsIgnoreCase` appellent chacun `toLowerCase(Locale.ROOT)` sur la valeur. Un calcul de rang qui va jusqu'au bout fait 7 conversions.
- `STORE:320-345` `symbolCandidates` : par nom qualifié exact (`:326-331`) ; correspondances exactes de nom et de nom qualifié normalisés (`:332-342`, rendues seules si elles atteignent la limite, `:340`) ; sinon **tous les symboles du projet** (`:344`).
- `STORE:613-659` : `SymbolIndexes` est un record **privé** de la classe ; `STORE:65` et `:69` : deux constructeurs publics ; la vue indexée est construite par `:69`, désormais au travers de `SnapshotReadView.indexed` (`minos-engine/.../store/SnapshotReadView.java:41-47`) et de la vue dépréciée (`SnapshotQueryView.java:43-49`).
- **Un seul site de calcul du rang** (relu le 2026-10-24) : `git grep -n "matchRank"` ne rend que `STORE` (`:185`, `:187`, `:380`). `SnapshotReadView` n'en calcule aucun : `reader()` (`SnapshotReadView.java:66-68`) rend le `CodeKnowledgeReader`, qui est `STORE` pour une vue indexée ; `CodeKnowledgeReader.findSymbols(projectId, query, limit)` (`CodeKnowledgeReader.java:31-33`) délègue à la variante par critère ; `SnapshotQueryView` délègue à `STORE`. Les autres implémentations de `findSymbols` sont des doublures de test (`SnapshotReadViewTest.java:263`, `:307` ; `ReadViewSubstitutionTest.java:148` dans `minos-application`) ou refusent l'appel (`ScipSymbolSnapshotImporter.java:273`).
- Appelants de production de `findSymbols` : `minos-engine/.../query/SymbolQueryService.java:23` et `:33` (CLI `find-symbol`, API, MCP `minos_find_symbols`) ; `minos-application/.../context/CodeSearchService.java:53` (CLI `search-code`, MCP `minos_search_code`, limite + 1). Aucune autre implémentation ne calcule ce rang (`git grep matchRank` : la seule classe ; `ScipSymbolSnapshotImporter.java:273` refuse l'appel).
- Le comparateur est **total** : sa dernière clé est l'identifiant, unique dans un projet. L'ordre ne dépend donc ni de la stabilité du tri ni de l'ordre d'itération de `symbolsByScopedId` (`ConcurrentHashMap`).

### Tests et goldens qui figent l'ordre

- `minos-storage-local/src/test/java/com/minos/storage/local/store/InMemoryCodeKnowledgeStoreIndexTest.java:31-56` : recherche par nom qualifié (index), pas l'ordre lexical. Package `com.minos.storage.local.store`, module différent de la classe.
- `minos-engine/src/test/java/com/minos/store/` existe (`SymbolEdgeIndexTest.java`, etc.) : un test de même package y a accès aux membres de visibilité package.
- Goldens `minos-app/src/test/resources/characterization/` : `mcp-all-tools.golden:14` (`minos_find_symbols`, `query=greet`, 18 symboles, rangs 1 à 3 et départage par nom qualifié), `mcp.golden:9` (`query=Greeting`, 18 symboles), `cli-json.golden:51`, `cli-text.golden:116` (`find-symbol a2-char Greeting`), `mcp-all-tools.golden:10` (`minos_search_code`), `api.golden:11`. Tous les noms qualifiés rendus contiennent le texte cherché : **aucun golden ne fige le rang 6**.
- Tests de surface qui appellent la recherche : `minos-cli/.../command/FindSymbolCommandTest.java`, `SearchCodeCommandTest.java`, `minos-mcp/.../MinosMcpToolsTest.java`, `minos-api/.../LocalMinosApiIntegrationTest.java`, `minos-app/.../characterization/A2SurfaceCharacterizationTest.java`.

À la rédaction (HEAD `5ce7b79b`), rien n'avait été mesuré ni exécuté hors `openspec validate`. Les mesures de la mise en œuvre sont dans « Mesures ».

### Écarts avec l'action proposée par l'audit

1. **« Supprimer le rang 6 » n'est pas une optimisation.** Un symbole trouvé seulement par sa clé disparaîtrait des résultats des quatre surfaces : sortie publique changée, donc décision (D3), séparée de ce changement. À noter pour D3 : les clés de symbole ont la forme `minos:structural:<empreinte>` (golden `mcp.golden:12`), si bien qu'une requête comme `minos`, `structural` ou un fragment hexadécimal rend tous les symboles du projet au rang 6.
2. **Le précalcul des minuscules a un coût mémoire mesuré par un test existant.** `minos-storage-local/.../QueryViewWeightTest.java:35` exige que le poids estimé d'une vue reste supérieur ou égal au tas qu'elle retient, et `scripts/remediation/check-mne.py:85-88` fige `QUERY_VIEW_PERSISTED_AMPLIFICATION` dans les deux stores. Retenir deux ou trois chaînes de plus par symbole déplace cette mesure. Le rang calculé une fois par candidat, lui, ne retient rien.
3. **Le décompte « 2 × n·log n » de l'audit est exact** à la lecture : `Comparator.comparingInt` extrait la clé des deux opérandes. Il s'ajoute n appels du filtre.
4. **Preuves de l'audit décalées** : il cite `:121-128`, `:322-347`, `:397-407`, `:281-286` ; au HEAD : `:185-187`, `:380-406`, `:455-465`, `:332-344`.
5. **Recouvrements non cités par l'audit** : `scripts/quality/check-jacoco.py:54-60` (scope `persistence-cache-indexes`) ; `scripts/ci/test_plugin_gate.py:49` (chemin littéral du fichier) ; `scripts/m15/run-final.ps1:88-91` exige `byNormalizedName`, `byQualifiedName`, `byFileId`, `IndexMetrics`… dans la classe, mais lit `minos-storage-local\...\com\minos\store\InMemoryCodeKnowledgeStore.java`, chemin absent (script lancé à la main par `historical-qualification.yml:147`) ; ces identifiants restent inchangés ici.

## Goals / Non-Goals

**Goals** : au plus un calcul de rang par candidat ; aucun sans texte ; aucune conversion répétée d'un même champ dans un calcul ; résultats identiques, ordre compris.

**Non-Goals** : changer le classement ; alourdir la vue sans mesure ; indexer la recherche par sous-chaîne.

## Decisions

### D1. Paire rang/symbole, calculée après les filtres bon marché

Les filtres de projet, de nature, de module et de nom qualifié (`:180-184`) restent en tête. Avec un texte, le rang est ensuite calculé une fois par candidat restant, porté dans une paire locale, filtré (`< Integer.MAX_VALUE`) puis trié par ce rang et par les clés suivantes inchangées (`:188-193`). Sans texte, aucun rang n'est calculé et le tri garde les clés suivantes seules (équivalent au rang constant 0 d'aujourd'hui). Dans le calcul, le nom et le nom qualifié sont mis en minuscules au plus une fois chacun, et la clé seulement si aucun rang plus fort n'a été trouvé. Aucun objet n'est retenu après l'appel.

Alternative écartée : mémoriser le rang dans une `Map<Symbol,Integer>` autour du comparateur (allocation par entrée et hachage des symboles, sans gain sur la paire).

### D2. Précalcul des minuscules dans la vue — **décidé le 2026-10-24 : non adopté dans ce changement**

Décision du propriétaire (2026-10-24) : pas de précalcul des minuscules dans la vue ici ; la mesure de la tâche 3.1 est faite pour la durée avant et après D1 (voir « Mesures »). Recommandation initiale, conservée pour mémoire : **ne pas l'adopter dans ce changement** ; le mesurer (tâche 3.1 : durée d'une requête courte avant D1, après D1, et avec précalcul ; tas retenu par la vue avec et sans, par la méthode de `QueryViewWeightTest`). Il ne se justifie que si D1 laisse un coût notable ; il fait alors l'objet d'un changement propre où `QueryViewWeightTest`, `QUERY_VIEW_PERSISTED_AMPLIFICATION` et `check-mne.py` sont revus ensemble.

Alternative : précalculer dès maintenant dans `SymbolIndexes` (action de l'audit). Gain supérieur par requête, tas résident plus grand, facteur d'amplification à revoir dans la même tâche.

### D3. Rang 6 (clé de symbole) — **décidé le 2026-10-24 : conservé**, rien n'est retiré

Décision du propriétaire (2026-10-24) : le rang 6 est conservé et se comporte exactement comme avant (couvert par `SymbolMatchRankTest`). Recommandation initiale, conservée pour mémoire : le garder tant qu'un relevé n'a pas montré qui s'en sert, et trancher dans un changement propre, additif ou versionné. Alternative : le retirer (MINOS-AUD-F13), avec note de version et goldens mis à jour. Ce changement garantit seulement que le rang 6 se comporte **comme avant**.

### D4. Rendre le nombre de calculs observable

`matchRank` est privé et statique : rien ne le compte. Le calcul passe par une fonction de rang (type de visibilité package dans `com.minos.store`) que la classe reçoit par un constructeur de visibilité package ; les constructeurs publics `:65` et `:69` délèguent avec la fonction de production. Le test, placé dans `minos-engine/src/test/java/com/minos/store/`, fournit une fonction qui compte et délègue. Aucun type public nouveau, aucun package nouveau.

Alternative écartée : compter par un compteur public sur la classe (surface publique sans usage de production).

## Recouvrements avec les autres changements

| Changement | Fichiers communs | Ordre |
|---|---|---|
| `exposer-un-port-de-lecture-de-snapshot` (archivé le 2026-10-24) | `STORE` : clause `implements` / extension de `CodeKnowledgeReader`, dépréciations ; règle de frontière A18 qui interdit de nommer `InMemoryCodeKnowledgeStore` hors de `com.minos.store` | déjà dans la base de la branche ; le test de D4 est dans `com.minos.store`, conforme à A18 (`check-module-boundaries.py` vert) |
| `mutualiser-les-caches-lru-et-nommer-les-additions-bornees` | caches pondérés des stores de snapshots, pas `STORE` | aucun conflit ; il concerne D2 seulement si D2 est retenue |
| `decouper-les-methodes-d-analyse-et-d-ingestion`, `decouper-les-classes-de-plus-de-800-lignes` | aucun (`STORE` fait 695 lignes) | aucun |
| `faire-consommer-l-indexation-aux-surfaces` | aucun (ne touche pas `com.minos.store`, son `design.md:166`) | aucun |
| `retirer-les-jalons-des-identifiants` | aucun nom de scope cité ici (`persistence-cache-indexes` ne porte pas de jalon) | aucun |

Capacités : `persistance-snapshots` porte les exigences voisines « Une recherche de symbole par identifiant utilise l'index de la vue » et « Le poids estimé d'une vue ne sous-évalue pas le tas qu'elle retient » (non contredites) ; `program-graph-et-architecture` fige le refus d'un filtre par module sans attribution (non touché : il précède `findSymbols`).

## Direction des dépendances

Changement interne à `minos-engine` ; aucune arête nouvelle (ADR 0022), aucun package nouveau (ADR 0044, A14), aucun type nommé par une surface (A11). Le test neuf vit dans le module de la classe.

## Qualification des capacités

- Recherche de symboles par texte : **qualifiée**, inchangée en contenu et en ordre.
- Gain de performance : **mesuré sur un jeu synthétique** (voir « Mesures ») ; non mesuré sur un index réel.
- Précalcul des minuscules (D2, non adopté) et retrait du rang 6 (D3, rang conservé) : **non supportés** ici.

## Windows et Linux

Aucune différence : `Locale.ROOT` est utilisé pour toutes les conversions (`:451-465`) et le reste ; l'ordre est total (identifiant en dernière clé). La CI exécute les tests sur les deux systèmes (`Verify (ubuntu-24.04)`, `Verify (windows-2022)`). Les seuils JaCoCo `global` diffèrent par système ; `persistence-cache-indexes` est commun (ligne 0,50, branche 0,35).

## Mise en œuvre et écarts

- **D1** : `STORE.findSymbols` filtre d'abord par projet, nature, module et nom qualifié ; sans texte, il trie par les clés secondaires seules (`SYMBOL_TIE_BREAK`) sans aucun calcul de rang ; avec texte, il porte chaque candidat dans un record privé `RankedSymbol(rank, symbol)`, filtre `rank < NO_MATCH` et trie par `RANKED_ORDER` (rang, puis `SYMBOL_TIE_BREAK`, mêmes clés et même ordre qu'avant). `matchRank` met le nom qualifié et le nom en minuscules une fois chacun, et la clé de symbole seulement si aucun rang de 0 à 5 n'a été trouvé (3 conversions au plus, contre 7) ; les helpers `equalsIgnoreCase`, `startsWithIgnoreCase` et `containsIgnoreCase` sont supprimés.
- **Nombre de calculs de rang** : avant, n appels du filtre plus deux par comparaison du tri, et un appel par opérande de comparaison même sans texte ; après, un par candidat restant après les filtres bon marché, zéro sans texte. Relevé par `SymbolMatchRankTest` au HEAD (couture seule) : 1 242 calculs pour 200 symboles (111 correspondances), 130 pour le jeu classé de 20 symboles, 116 sans texte ; après : 200, 20 et 0.
- **D4** : la fonction de rang est l'interface imbriquée de visibilité package `InMemoryCodeKnowledgeStore.SymbolMatchRank`, reçue par un constructeur de visibilité package `(CodeKnowledgeSnapshot, SymbolMatchRank)` ; le constructeur public par snapshot lui délègue avec `matchRank`, le constructeur public sans argument prend `matchRank` directement. Aucun type public nouveau, aucun package nouveau. Écart mineur : le design évoquait un type de `com.minos.store` ; c'est un type imbriqué de `STORE`, de même visibilité.
- **Test** : `minos-engine/src/test/java/com/minos/store/SymbolMatchRankTest.java` (8 tests) : caractérisation de l'ordre (rangs 0 à 6, ex æquo sur `external`, `generated`, nom qualifié nul, `signature` nulle, nom et identifiant ; symbole d'un autre projet et symbole sans correspondance absents), limite appliquée après le tri complet (dont le raccourci des correspondances exactes), recherche sans texte, comptage par fonction de rang injectée (au plus un appel par candidat, zéro sans texte, zéro et `IllegalArgumentException` sur critère nul). Listes attendues capturées au HEAD avant D1 (couture seule) : vertes avant et après.

## Mesures (tâche 3.1)

Jeu synthétique `QueryViewWeightFixtures.repository` (2 000 fichiers, 40 000 symboles, vocabulaire ASCII), une vue `InMemoryCodeKnowledgeStore` construite une fois, critère `SymbolSearchCriteria.lexical(<requête>, 20)` ; 30 appels de chauffe puis 50 appels mesurés par `System.nanoTime()` (médiane, p10, p90) et allocation moyenne par appel (`com.sun.management.ThreadMXBean.getCurrentThreadAllocatedBytes`). Test jetable, non versionné, exécuté par `./mvnw -B -ntp -pl minos-storage-local -am test` dans un JVM Surefire. Machine : AMD Ryzen 7 5700G, 48 Gio, Windows 10 Pro 10.0.19045, Temurin 25.0.4.1. Mesure « avant » prise avec la seule couture D4 en place (même algorithme qu'au HEAD, appel par interface).

| Requête | Résultats | Avant D1 : médiane (p10–p90) | Après D1 : médiane (p10–p90) | Allocation par appel avant → après |
|---|---|---|---|---|
| `re` (aucune correspondance exacte, balayage de tout le projet) | 20 | 147,0 ms (139,0–170,9) | 48,3 ms (44,5–55,6) | 113,7 Mo → 5,9 Mo |
| `zz` (aucune correspondance) | 0 | 17,1 ms (16,4–20,6) | 15,4 ms (14,9–17,6) | 12,7 Mo → 10,0 Mo |
| `snapshot1` (correspondances exactes ≥ limite, sans balayage) | 20 | 0,88 ms | 0,38 ms | 0,5 Mo → 0,1 Mo |

Lecture : le gain est porté par le tri (le rang n'est plus recalculé à chaque comparaison) ; une requête sans correspondance garde le coût d'un rang par symbole. **Non mesuré** : le précalcul des minuscules (D2, non adopté par décision) et le tas retenu par la vue avec précalcul ; `InMemoryBackendBenchmark` sur un index réel (aucun index réel productible sur le poste Windows) ; Linux. Une seule exécution par configuration, sur un poste non isolé : ordres de grandeur, pas une qualification.

## Risks / Trade-offs

- [Une clé secondaire changée ou réordonnée] → le test de caractérisation et les goldens de `minos-app` comparent élément par élément.
- [Conflit de fusion avec `exposer-un-port-de-lecture-de-snapshot`] → zones différentes de la classe ; rebaser le second fusionné.
- [Code neuf jugé par SonarCloud à 80 % dans `minos-engine`] → le test de D4 vit dans ce module (les tests existants de la classe sont dans `minos-storage-local`).
- [Alerte SpotBugs nouvelle sur le champ de la fonction de rang] → comparer à `quality/spotbugs-baseline/minos-engine.xml` (aucune entrée pour `InMemoryCodeKnowledgeStore` au HEAD) ; `code-audit.yml` n'est pas un check exigé.
- [Gain négligeable] → le dire dans le suivi : D1 reste une simplification sans coût mémoire.
