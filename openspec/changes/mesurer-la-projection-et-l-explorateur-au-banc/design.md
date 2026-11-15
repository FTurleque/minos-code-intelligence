# Design

Changement d'outillage de mesure (sources de test, configuration et script du banc) : les sections Contexte, État vérifié au HEAD, Écarts, Décisions, Performance et mesure, Vérification et Traçabilité sont remplies ; s'y ajoute « Ce que le sprint 4 pourra mesurer ». Les autres sections du modèle sont non applicables : aucun composant de production, contrat, format persisté ni traitement ne change.

## Contexte

Motivation : `proposal.md`. Le banc existant est un `main` des sources de test de `minos-bootstrap` (`SnapshotScalabilityBenchmark`, 1 231 lignes) lancé par `run-scalability-benchmark.ps1` avec les paramètres de `scalability.json`. Il écrit `result.tsv` (lignes `METRIC section jeu mesure valeur`), `summary.json`, `environment.txt` et `stderr.txt` dans un dossier hors du dépôt. L'explorateur est un module de surface (`minos-explorer`) qui sert sept routes sous `/api/v1/` ; la projection est préparée par `SemanticProjectionService` (module `minos-application`) à partir des vecteurs du magasin sémantique, local ou PostgreSQL.

## État vérifié au HEAD

HEAD `89be3fb4`. Aucune mesure n'a été exécutée.

| Fait | Preuve |
|---|---|
| Le banc ne mesure que `memory`, `hybrid`, `impact`, `architecture` (et `ratio`, `size`) | `benchmarks/scalability/scalability.json:28`, `benchmarks/scalability/README.md` (table des sections) |
| Aucun fichier de `benchmarks/` ne contient `projection`, `explorer` ni `semantic` | recherche insensible à la casse : 0 fichier |
| Le harnais existant vit dans `minos-bootstrap`, qui ne dépend pas de `minos-explorer` | `minos-bootstrap/pom.xml:19-70` |
| Les tests de `minos-explorer` ouvrent un vrai `MinosApplication` (dépendance `runtime` vers `minos-bootstrap`) | `minos-explorer/pom.xml:29-33`, `minos-explorer/src/test/java/com/minos/explorer/IndexedHome.java` |
| Le démarrage du serveur est à visibilité de package | `minos-explorer/src/main/java/com/minos/explorer/ExplorerServer.java:107` (`static ExplorerServer start(...)`) |
| Routes : `projects`, `documents`, `documents/{stableKey}`, `…/neighbors`, `projection`, `projection/points`, `search` | `minos-explorer/src/main/java/com/minos/explorer/ExplorerApi.java:27-33` |
| Préparation : au plus 40 itérations, 100 000 points par défaut, budget de travail 256 Mio par défaut, pages de points de 5 000 | `SemanticProjectionService.java:24-29` |
| Le rapport de préparation porte passes, durée et mémoire de travail de pointe | `SemanticProjectionService.java:85-89`, `:224-225` ; compteur de passes `ProjectionPreparation.java:149` |
| Le provider déterministe accepte 32 à 4 096 dimensions, 384 par défaut | `LocalHashEmbeddingProvider.java:16`, `:23-25` |
| Le magasin PostgreSQL des vecteurs existe | `minos-storage/src/main/java/com/minos/storage/postgresql/semantic/` (3 classes) |
| Magasin local : fichier d'index borné à 384 Mio ; décodage complet (`load`) borné à 192 Mio de vecteurs comptés à 8 octets par composante, soit 32 768 documents à 768 dimensions | `minos-storage/src/main/java/com/minos/storage/local/store/FileSemanticVectorStore.java:57-59`, `:131-132`, `:539-540`, `:691-699` |
| Liste, détail et voisins passent par ce décodage complet en stockage local (défauts du port) ; seule la page de vecteurs est lue en flux | preuve de AUD-PERF-03 ; `FileSemanticVectorStore.java:106-147` |
| Une mesure rejouable de la projection existe hors du banc : test conditionné par `-Dminos.projection.measure=true`, index synthétiques écrits par `application.semanticVectorStore().replace(...)`, 256 dimensions, stockage local, relevé `target/projection-measurements.md` (préparation, passes, mémoire de travail, tas de pointe, taille du cache, lecture de tous les points) | `minos-bootstrap/src/test/java/com/minos/bootstrap/semantic/SemanticProjectionMeasurementTest.java:36-49`, `:93` ; `docs/developer/semantic-explorer.md:174-176` |
| La synchronisation refuse un index au-delà de 192 Mio de vecteurs à 8 octets par composante et de 250 000 documents, en local comme sous PostgreSQL : 32 768 documents au plus à 768 dimensions | `minos-application/src/main/java/com/minos/application/semantic/SemanticIndexBudget.java:15-19`, `:59-75` ; `SemanticIndexService.java:137-138` |
| `minos-bootstrap` est une dépendance `runtime` de `minos-explorer` ; `PostgresTestSupport` vit dans les tests de `minos-storage`, hors du classpath de test de `minos-explorer` | `minos-explorer/pom.xml:29-33`, `:36-47` ; `minos-storage/src/test/java/com/minos/storage/postgresql/PostgresTestSupport.java:47` |
| Le README du banc demande « JDK 24 » | `benchmarks/scalability/README.md` (Prérequis) ; la baseline du dépôt est Java 21 (`AGENTS.md`, Toolchain) |
| Règle du projet : mesure par le banc, jamais une estimation | `docs/architecture/adr/DATA-0081-port-de-connaissance-et-persistance-des-snapshots-budget-memoire-des-vues.md:44` (point 10) |

## Écarts

| Écart | Source A | Source B | Suite proposée |
|---|---|---|---|
| E1 — « Étendre benchmarks/scalability » : le harnais existant est dans `minos-bootstrap`, qui ne voit pas `minos-explorer` ; y ajouter la dépendance inverserait la direction surfaces → racine de composition | action de AUD-PERF-13 | `minos-bootstrap/pom.xml`, ARCH-0068 | second harnais dans les sources de test de `minos-explorer`, même format de sortie, même dossier `benchmarks/scalability/` pour la configuration et le script (D1) |
| E2 — DATA-0081 parle du « corpus réel » ; l'audit demande des tailles exactes (25 000 et 100 000 documents, 768 dimensions) qu'aucun corpus réel du dépôt ne donne | DATA-0081, point 10 | action de AUD-PERF-13 | jeu synthétique déterministe pour l'échelle, corpus réel en contrôle facultatif (D2) |
| E3 — PostgreSQL n'est pas démarré par la CI Windows et le banc existant est propre à Windows | action (« index local et PostgreSQL ») | `docs/developer/testing.md:60-70`, README du banc | section PostgreSQL mesurée sur le poste avec le serveur de `start-test-postgres.ps1` ; absente, elle est nommée et fait échouer le code de sortie (D4) |
| E4 — « tas retenu » et coût d'un décodage complet par requête : aucun compteur de décodage n'existe en production | action et preuve de AUD-PERF-03 | `FileSemanticVectorStore` (aucun compteur public) | mesurer les octets alloués par requête et le tas retenu après stabilisation, sans ajouter de compteur au produit (D3) |
| E5 — « index local de 100 000 documents à 768 dimensions » : au HEAD, le décodage complet du magasin local refuse plus de 32 768 documents à 768 dimensions (`semantic index decoded vectors exceed heap budget`) et le fichier approche le plafond de 384 Mio (293 Mio de vecteurs, plus les textes). Liste, détail et voisins y **échouent** ; ils ne sont pas lents | action de AUD-PERF-13 | `FileSemanticVectorStore.java:57-59`, `:691-699` | dimensionner (D7) : référence locale à 25 000 et à 32 768 documents ; jeu local de 100 000 documents déclaré « refus attendu », qui constate le refus des routes et mesure ce qui reste lisible ; PostgreSQL à 25 000 et 100 000 |
| E7 — « sans référence » : une mesure de la projection seule existe déjà, hors de `benchmarks/`, en local et à 256 dimensions | titre de AUD-PERF-13 | `SemanticProjectionMeasurementTest.java:36-49` | constat **partiel** ; la garder et la citer, le banc couvre ce qu'elle ne mesure pas (D9) |
| E8 — Un index de 100 000 documents à 768 dimensions n'est pas productible par le produit : la synchronisation le refuse (budget de construction), en local comme sous PostgreSQL | action de AUD-PERF-13 | `SemanticIndexBudget.java:15-19`, `:59-75` | les jeux au-delà de 32 768 documents sont écrits directement dans le magasin par le harnais et le résultat le dit ; ils caractérisent le magasin et les lectures, pas un index que l'utilisateur peut obtenir aujourd'hui (D7) |
| E6 — Le README du banc cite JDK 24 | `benchmarks/scalability/README.md` | baseline Java 21 | corriger la mention dans la tâche de documentation |

## Décisions

### D1 — Un harnais sémantique dans les tests de `minos-explorer`
[Décision] Classe `main` dans le package de test `com.minos.explorer` (accès à `ExplorerServer.start`), nommée sans numéro de jalon et hors des motifs d'inclusion de Surefire, comme `SnapshotScalabilityBenchmark`. Elle ouvre un `MinosApplication` réel sur un `MINOS_HOME` jetable, démarre le serveur sur un port éphémère de la boucle locale et interroge les routes avec le jeton de session : la garde, l'encodage JSON et le magasin sont dans la mesure. Écarté : mesurer les services sans HTTP (ne mesurerait pas ce que l'utilisateur attend) ; déplacer le harnais existant.

### D2 — Jeu synthétique déterministe, corpus réel en contrôle
[Proposition, recommandée] Les documents et leurs vecteurs sont engendrés par une graine fixe et écrits par le port d'écriture du magasin sémantique : le coût de la projection dépend du nombre de vecteurs et de la dimension, pas du texte. Tailles et dimension viennent de `semantic.json` (25 000 et 100 000 ; 768). Un jeu facultatif indexe le corpus réel du banc existant avec le provider déterministe à 768 dimensions, pour vérifier que le synthétique ne s'écarte pas d'un index réel de taille voisine. Écarté : le seul corpus réel (taille non choisie, préparation de plus d'une heure).

### D3 — Mesures
[Décision]
- Préparation (`projection`) : durée, passes, vecteurs lus, mémoire de travail de pointe (lus dans le rapport de préparation), nombre de points, échantillonnage ; pour PostgreSQL, nombre de pages de vecteurs demandées.
- Routes (`explorer`) : après échauffement, N itérations par route ; p50, p95, maximum ; octets alloués par requête (compteur d'allocation de la JVM avant et après) ; première requête à froid séparée.
- Mémoire (`heap`) : tas retenu après stabilisation, avant le premier appel, après la liste, après les voisins, après la lecture complète des points.
- Chaque ligne porte le stockage (`local`, `postgresql`) et le jeu (`n25000-d768`, `n100000-d768`).

Routes mesurées : liste (première page, page profonde atteinte par curseur), détail, voisins (limite 20, celle de la page), état de projection, page de points (limite 5 000), recherche. La table des routes mesurées est confrontée à l'énumération des routes de `ExplorerApi` par le test de fumée.

### D4 — Une mesure impossible est une ligne, pas un silence
[Décision] Ligne `REFUSED section jeu raison` ; les autres sections continuent ; code de sortie non nul si une section demandée manque. La raison ne contient ni chemin absolu ni mot de passe (l'URL JDBC est réduite à son hôte et à son port). Les paramètres PostgreSQL sont passés au script (URL, utilisateur, secret par variable d'environnement), reportés dans la configuration du produit du `MINOS_HOME` jetable (D8) et jamais écrits dans `environment.txt`.

### D5 — La référence va dans le suivi de l'audit
[Proposition, recommandée] Les fichiers de résultat restent hors du dépôt (règle du banc). Un extrait daté (machine, JVM, commit, valeurs par jeu) est consigné dans `docs/project/audits/2026-11-15/SUIVI.md`, section du sprint 1 : c'est une preuve datée, pas une documentation durable. Écarté : un fichier de résultats versionné sous `benchmarks/` (contredit le README) ; un ADR.

### D6 — Un test de fumée garde le harnais
[Décision] Test JUnit du même package : jeu de quelques centaines de documents, dimension minimale, stockage local, une itération ; il affirme la liste exacte des mesures émises et l'égalité entre routes mesurées et routes de `ExplorerApi`. Il n'affirme aucune durée.

### D8 — PostgreSQL joint par la configuration du produit
[Décision] Le harnais ouvre son `MINOS_HOME` jetable sur le backend PostgreSQL par la configuration de stockage du produit (URL, utilisateur, secret lu dans un fichier restreint), sur une base dédiée fournie par l'opérateur (celle que démarre `scripts/dev/start-test-postgres.ps1`). Aucun test-jar de `minos-storage` n'entre dans `minos-explorer` : `PostgresTestSupport` n'est pas réutilisé et `check-module-boundaries.py` n'a aucune arête nouvelle à juger. Le harnais refuse une base qui contient déjà un projet, pour ne pas mesurer ni modifier des données existantes. Écarté : ajouter le test-jar de `minos-storage` (arête de test surface → adaptateur à faire accepter).

### D9 — La mesure existante est gardée
[Proposition, recommandée] `SemanticProjectionMeasurementTest` reste en place, inchangé : c'est une mesure rapide de la projection seule, en local. Le README du banc et `docs/developer/semantic-explorer.md` disent ce que chacun mesure. Écarté : la supprimer au profit du banc (elle sert aussi à garder des `MINOS_HOME` à servir dans un navigateur) ; la déplacer.

### D7 — Tailles des jeux selon le stockage
[Décision] `semantic.json` déclare les jeux par stockage :
- `local` : `n25000-d768` (sous le budget de décodage) et `n32768-d768` (au budget exact : dernière taille que liste, détail et voisins servent au HEAD) pour la référence de latence ; `n100000-d768` avec `expectRefusal` sur les routes qui décodent l'index entier. Pour ce dernier jeu, le banc constate le refus (ligne `REFUSED`, motif du produit), mesure la préparation de la projection et la page de points si le fichier reste sous 384 Mio (textes synthétiques courts), et rend un code **nul** tant que le refus attendu est observé ; un refus attendu qui ne se produit plus rend un code non nul, pour que la configuration suive la correction de AUD-PERF-03.
- `postgresql` : `n25000-d768` et `n100000-d768`, sans refus attendu.

Les plafonds sont lus au HEAD et non recopiés dans le harnais : la configuration ne porte que des tailles.

Provenance des jeux : la synchronisation du produit ne construit pas d'index au-delà de 32 768 documents à 768 dimensions (écart E8). Tous les jeux sont donc **synthétiques et écrits directement dans le magasin** par le port d'écriture (`semanticVectorStore().replace`, comme la mesure existante), hors synchronisation ; chaque ligne de résultat d'un jeu au-delà du budget de construction porte la mention `beyond-build-budget`. Le jeu local de 100 000 documents à refus attendu caractérise donc un fichier écrit hors synchronisation ; les jeux PostgreSQL de 100 000 documents mesurent des lectures que le sprint 4 corrige, sur un index que seule une levée du budget rendrait productible. Si le magasin PostgreSQL refuse lui-même cette écriture, le résultat le dit (ligne `REFUSED`) et la plus grande taille acceptée devient la référence.

## Performance et mesure

Aucun objectif chiffré : ce changement produit la mesure, il ne la juge pas. Stratégie : machine de référence du banc existant (décrite par `environment.txt`), JVM lancée avec les arguments de `semantic.json`, échauffement puis itérations fixes, graine fixe. [Question] nombre d'itérations et durée totale acceptable : à régler à la première exécution (tâche 2.3), en visant une exécution locale de moins d'une heure.

## Ce que le sprint 4 pourra mesurer

| Constat du sprint 4 | Mesure de référence fournie | Comparaison après correction |
|---|---|---|
| AUD-PERF-01 — comptage par page des lectures PostgreSQL | préparation sur `postgresql` / `n100000-d768` : durée, passes, pages de vecteurs lues ; latence p50 / p95 de la liste (première page et page profonde) sur PostgreSQL | mêmes lignes, même jeu, même graine ; le nombre de passes et de vecteurs lus doit rester égal, seule la durée change. Le plan d'exécution (`EXPLAIN`) reste une vérification du sprint 4, hors du banc |
| AUD-PERF-02 — jusqu'à 43 lectures complètes de l'index | **nombre réel de parcours de l'index par préparation** à 768 dimensions (que l'audit n'a pas relevé), vecteurs lus = parcours × documents, durée et mémoire de travail de pointe, en local (25 000, 32 768 et, si le fichier tient sous le plafond, 100 000) et sous PostgreSQL (25 000 et 100 000) | vecteurs lus et durée après ajustement en mémoire ; mémoire de travail de pointe confrontée au budget de 256 Mio ; la variance expliquée publiée sert de contrôle d'équivalence du résultat |
| AUD-PERF-03 — liste, détail et voisins décodent l'index entier | sur `local`, latence p50 / p95 et octets alloués par requête de la liste, du détail et des voisins à 25 000 et à 32 768 documents ; première requête à froid ; tas retenu ; et le **refus constaté** de ces trois routes à 100 000 documents. Les mêmes routes sont mesurées sous PostgreSQL à 25 000 et 100 000 | mêmes lignes ; une pagination sans chargement se lit dans les octets alloués par requête, qui cessent de croître avec la taille, et dans la disparition du refus à 100 000 documents (le jeu perd alors son `expectRefusal`) |
| AUD-PERF-04 (sprint 5) — caches hors budget | tas retenu après une page d'explorateur et après la lecture des points | même mesure, bénéficiaire sans être prérequis |

Lignes exactes de `result.tsv` à comparer (format `METRIC section jeu mesure valeur` ; un jeu au-delà du budget de construction porte le suffixe `-beyond-build-budget`, soit `local-n100000-d768-beyond-build-budget` et `postgresql-n100000-d768-beyond-build-budget` à 768 dimensions) :

| Constat | Section | Jeux | Mesures |
|---|---|---|---|
| AUD-PERF-01 | `projection` | `postgresql-n25000-d768`, `postgresql-n100000-d768-beyond-build-budget` | `durationMillis`, `passes`, `vectorPages`, `vectorsRead` |
| AUD-PERF-01 | `explorer` | mêmes jeux | `list-first.p50Micros`, `list-first.p95Micros`, `list-deep.p50Micros`, `list-deep.p95Micros` |
| AUD-PERF-02 | `projection` | `local-n25000-d768`, `local-n32768-d768`, `local-n100000-d768-beyond-build-budget`, `postgresql-n25000-d768`, `postgresql-n100000-d768-beyond-build-budget` | `passes`, `vectorsRead`, `durationMillis`, `peakWorkingBytes` |
| AUD-PERF-03 | `explorer` | `local-n25000-d768`, `local-n32768-d768`, et les deux jeux PostgreSQL | `list-first.*`, `list-deep.*`, `detail.*`, `neighbors.*` (`coldMicros`, `p50Micros`, `p95Micros`, `allocatedBytesPerRequest`) |
| AUD-PERF-03 | `heap` | mêmes jeux | `retainedAfterListBytes`, `retainedAfterNeighborsBytes` |
| AUD-PERF-03 | `REFUSED explorer` | `local-n100000-d768-beyond-build-budget` | raisons `list-first:`, `list-deep:`, `detail:`, `neighbors:` ; leur disparition fait échouer le banc tant que `expectRefusal` reste déclaré |
| AUD-PERF-04 | `heap` | tous | `retainedAfterListBytes`, `retainedAfterAllPointsBytes` |

Ce que le banc ne donne pas : le temps de rendu dans le navigateur (AUD-PERF-10, couvert par les compteurs du front), ni une mesure sur un index produit par un modèle d'embedding réel.

## Vérification

- Rouge : le test de fumée (D6) échoue tant que le harnais n'émet pas toutes les mesures ; il échoue aussi quand une route est retirée de la table mesurée.
- `./mvnw -B -ntp -pl minos-explorer -am test` (test de fumée ; la classe `main` n'est pas un test).
- `python scripts/architecture/check-module-boundaries.py`, `python scripts/quality/check-comment-language.py`, `python scripts/quality/check-milestone-identifiers.py`, `python .claude/scripts/run_gates.py`.
- Exécution réelle du banc : manuelle, sur la machine de référence, avec et sans PostgreSQL (tâche 3.1). Linux : harnais exécutable par la même commande Java, non scripté.
- Non vérifiable en CI : les valeurs mesurées.

## Traçabilité

| Exigence (capacité › titre) | Scénario | Conception ou diagramme | Tâche | Test ou preuve |
|---|---|---|---|---|
| exploration-semantique › Le banc de scalabilité produit une référence pour la projection et les routes de l'explorateur | Mesure sur le stockage local ; Deux exécutions comparables | D1, D2, D3 | 1.2, 2.1, 2.3, 3.1 | partiel : forme des mesures prouvée par `SemanticExplorerBenchmarkSmokeTest` et par l'exécution réduite de 2.2 ; **manque** la référence complète et sa seconde exécution (3.1), dont les valeurs PostgreSQL, qui demandent le serveur de test |
| idem | Sortie dans le dépôt refusée | D5 | 2.1, 2.2 | contrôle de `run-semantic-benchmark.ps1`, rejoué à la main (2.2) sous PowerShell 7 et Windows PowerShell 5.1 |
| idem | Banc absent du build | D1 | 1.2 | `SemanticExplorerBenchmark` ne correspond à aucun motif d'inclusion de Surefire (le `pom.xml` parent n'en déclare aucun) ; rapport Surefire de `minos-explorer` sans cette classe |
| exploration-semantique › Une mesure impossible est nommée dans le résultat | PostgreSQL non configuré ; Refus attendu au-delà du budget de décodage local ; Refus attendu qui ne se produit plus ; Préparation refusée par le budget | D4, D7 | 1.3, 3.1 | `SemanticExplorerBenchmarkSmokeTest` (cas PostgreSQL sans URL, budget, refus attendu observé et absent, raisons sans chemin ni secret) ; le refus attendu y est simulé par une réponse `500` de la surface ; le refus réel du magasin local à 100 000 documents reste à constater (3.1) |
| exploration-semantique › Le harnais du banc sémantique est gardé par un test du build | Toutes les mesures sont émises ; Route non mesurée | D6 | 1.1, 1.2 | `SemanticExplorerBenchmarkSmokeTest` (liste exacte des mesures, `everyRouteOfTheSurfaceHasAMeasuredScenario`) |

Écart de réalisation : le harnais compte les pages de vecteurs lues (`vectorPages`) par le rappel de progression de la préparation, appelé une fois par page, en local comme sous PostgreSQL ; aucun compteur n'est ajouté au produit.

## Questions ouvertes

- [Question, propriétaire] D2 : jeu synthétique pour l'échelle, corpus réel en contrôle facultatif (recommandé).
- [Question, propriétaire] D5 : référence consignée dans `SUIVI.md` de l'audit (recommandé).
- [Question, propriétaire] D9 : garder `SemanticProjectionMeasurementTest` tel quel à côté du banc (recommandé).
- [Question, non bloquante] Le magasin PostgreSQL accepte-t-il l'écriture directe d'un index de 100 000 documents à 768 dimensions ? Non vérifié à la lecture ; constaté à la première exécution (D7).
- [Question, non bloquante] Itérations et durée totale : réglées en tâche 2.3.
- [Question, non bloquante] Le jeu local de 100 000 documents tient-il sous le plafond de fichier de 384 Mio avec les textes synthétiques ? Constaté à la première exécution ; sinon l'écriture ou la lecture en flux est elle-même refusée et le résultat le dit.
