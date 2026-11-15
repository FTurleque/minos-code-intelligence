# Banc de scalabilité des snapshots (audit A6)

Mesures du lot A6 de `docs/project/audits/archive/2026-09/AUDIT-2026-09.md` (mémoire et scalabilité) : taille et composition
des snapshots persistés, ratio SCIP → snapshot, empreinte en tas, recherche hybride, analyse d'impact
et part de la découverte dans `LocalProjectArchitectureQuery`. Les résultats et leur lecture sont
consignés dans `docs/project/audits/archive/2026-09/ARCHI-SUIVI.md`, partie « Lot 3 — A6 ».

Le banc ne tourne jamais dans `mvn verify` : la classe
`minos-bootstrap/src/test/java/com/minos/bootstrap/scalability/SnapshotScalabilityBenchmark.java` est un
`main` des sources de test dont le nom ne correspond à aucun motif d'inclusion de Surefire (même
convention que `InMemoryBackendBenchmark` et `CodeSearchBenchmark`). Aucune donnée de mesure n'est
écrite dans le dépôt : le corpus et les résultats vont dans des répertoires passés en paramètre, hors
du dépôt (les scripts le vérifient).

## Prérequis

- Windows et PowerShell 7 (`pwsh`) : les deux scripts, le runner scip-java géré et `mvnw.cmd` sont
  propres à Windows ; `run-scalability-benchmark.ps1` lit aussi le processeur et la mémoire par
  `Get-CimInstance`. Aucun rejeu Linux n'est fourni.
- JDK 21 (baseline du dépôt, `docs/developer/TOOLCHAIN_POLICY.md`) dans le `PATH`, Git, et les outils
  scip-java gérés par MINOS pour préparer le corpus.
- Mémoire : `scalability.json` lance la JVM avec `-Xmx24g`. Mesuré sur ce dépôt : le corpus entier
  retient environ 300 Mo de tas (snapshot 163 Mo, index 82 Mo, corpus hybride 55 Mo), auxquels
  s'ajoutent les vues relues des tranches et les pics d'allocation (jusqu'à 1,3 Go par recherche
  hybride sur `file-f0.50`). Prévoir 32 Go de mémoire physique, ou baisser `-Xmx` et retirer de la
  configuration les jeux les plus gros.

## 1. Corpus réel

```powershell
.\benchmarks\scalability\prepare-corpus.ps1 -CorpusDirectory <hors-dépôt>\corpus [-Revision <sha>]
```

Le script indexe **ce dépôt** avec le runtime scip-java géré par MINOS (`minos tools install
scip-java` au préalable) :

1. copie des fichiers suivis de la révision (`git archive`) ;
2. corpus Java/Maven seulement : `fixtures/` (polyglotte) et `minos-intellij/` (Gradle) retirés, ainsi
   que `mvnw`, `mvnw.cmd` et `.mvn` (scip-java préfère `./mvnw`, qui n'est pas un exécutable Win32 ;
   le Maven géré par MINOS est utilisé, comme sur le runtime Linux) ;
3. `minos-app` construit dans son propre `target/` : dans le dépôt, il construit dans le `target/`
   racine, et son `clean` efface alors `target/scip-targetroot`, où scip-java agrège les modules
   (l'index ne contiendrait que `minos-app`) ;
4. appel direct du runner Windows géré (`scip-java-windows-runner.ps1`) : `minos index` l'exécute dans
   le bac à sable AppContainer non élevé, qui refuse un PowerShell installé sous `Program Files`.

Il écrit `minos-full.scip` et `corpus.txt` (révision, version de scip-java, taille, sha256). Deux
exécutions sur la même révision donnent la même taille à un octet près.

SCIP complémentaires, facultatifs, pour le ratio SCIP → snapshot : à déposer dans le répertoire du
corpus sous les noms de `scalability.json` (`ariane-chatbot.scip`, `nexus-context-engine.scip`, et
`minos-app-module.scip`, l'index obtenu sans l'étape 3). Les fixtures TypeScript du dépôt servent
toujours.

## 2. Mesures

```powershell
.\benchmarks\scalability\run-scalability-benchmark.ps1 -CorpusDirectory <corpus> -OutputDirectory <hors-dépôt>\run
```

Le script compile le harnais (`mvnw -pl minos-bootstrap -am test-compile`), lance la JVM avec les
paramètres de `scalability.json` et produit dans `<run>` : `result.tsv` (lignes
`METRIC section jeu mesure valeur`), `summary.json`, `environment.txt` (machine, JVM, corpus),
`stderr.txt`, ainsi que le `MINOS_HOME` jetable et le `user.home` de la JVM.

| Section | Jeu | Contenu |
|---|---|---|
| `ratio` | un par SCIP | pré-analyse, décodage, import sans persistance, import produit (publié ou refusé), taille V2, part des chaînes, taille UTF-8 et avec table de chaînes calculées, tas du snapshot |
| `size` | `file-f*`, `mem-k*` | même recensement sur les tranches et les répliques |
| `memory` | `file-f*` | chargement à froid de la vue de requête, construction des index, tas (vue, snapshot seul, index), poids estimé du cache |
| `hybrid` | `file-f*`, `mem-k*` | documents du corpus, première recherche, latence et allocations par requête, part de la normalisation (échantillonnage JFR) |
| `impact` | `file-f*`, `mem-k*` | coût fixe (racine sans arête entrante), trois racines à plus forte portée, `maxResults` 200/10 000/10, répartition JFR |
| `architecture` | `mem-k1` | découverte seule, intelligence complète, répartition JFR (Java et natif) |

Jeux de données :

- `file-fX` : tranche de fichiers du corpus réel (sélection stable par hachage du `fileId`, fraction X),
  publiée dans le magasin fichier, donc mesurée par le chemin produit (vue de requête, cache) ;
  `file-f1.00` est le corpus entier ;
- `mem-kN` : corpus réel entier répliqué N fois (identifiants préfixés `r<i>~`), servi par un magasin
  en mémoire du banc, hors persistance : il mesure ce que coûteraient les requêtes si le snapshot
  pouvait être chargé.

La configuration par défaut ne mesure que `mem-k1` : à partir de `mem-k2` (161 307 documents), le
corpus hybride n'est plus mis en cache et chaque recherche le reconstruit (environ 17 minutes). Pour
l'impact et les index sur les répliques, lancer une configuration dérivée avec `"replicas": [2, 3]`,
`"sections": ["impact", "memory"]`, `"fractions": [0.0]` et `"ratioScips": []` (environ 1 minute).

Le recensement des tailles parcourt le snapshot dans l'ordre des champs du codec V2 ; la taille prédite
est comparée à l'encodeur réel (`censusMatchesEncoder`) chaque fois que le snapshot tient sous le
plafond. Durée d'une exécution complète : plus d'une heure sur la machine de référence, dominée par la
construction des corpus hybrides (6 ms par document) et par la découverte du projet (36 s par appel).

## 3. Banc sémantique (projection et explorateur)

Mesures du constat AUD-PERF-13 de l'audit du 2026-11-15 : préparation de la projection 2D et routes de
lecture de l'explorateur, en stockage local et sous PostgreSQL. Elles servent de référence aux corrections
de performance du sprint 4 de cet audit ; la référence datée est consignée dans
`docs/project/audits/2026-11-15/SUIVI.md`, jamais dans ce dossier.

Le harnais est `minos-explorer/src/test/java/com/minos/explorer/SemanticExplorerBenchmark.java`, un `main`
des sources de test de `minos-explorer` (le module de la surface, que `minos-bootstrap` ne voit pas) dont le
nom ne correspond à aucun motif d'inclusion de Surefire : il ne tourne jamais dans `mvn verify`.
`SemanticExplorerBenchmarkSmokeTest` l'exécute à chaque build sur un index local de quelques centaines de
documents : il vérifie la liste exacte des mesures émises (65 lignes `METRIC` pour un jeu local mesuré en
entier), que chaque route de `ExplorerApi` a un scénario mesuré, la forme des refus, et que deux exécutions de
même graine donnent les mêmes documents, passes et vecteurs lus. Il n'affirme aucune durée.

```powershell
.\benchmarks\scalability\run-semantic-benchmark.ps1 -OutputDirectory <hors-dépôt>\semantic-run `
    [-PostgresUrl jdbc:postgresql://127.0.0.1:55432/postgres -PostgresUser minos] `
    [-Only local:25000,postgresql:25000] [-Warmup 1] [-Iterations 3]
```

Le script compile le harnais (`mvnw -pl minos-explorer -am test-compile`), lance la JVM avec les paramètres
de `semantic.json` et produit dans le dossier de sortie `result.tsv`, `summary.json` (mesures, refus, durée
totale et code de sortie), `environment.txt` (machine, JVM, commit, jeux, hôte PostgreSQL réduit à l'hôte et
au port), `stderr.txt` et les `MINOS_HOME` jetables. Il refuse un dossier de sortie situé dans le dépôt, avant
d'écrire quoi que ce soit, et un dossier qui contient déjà une exécution. Il est compatible Windows
PowerShell 5.1 et PowerShell 7.

### Jeux

`semantic.json` déclare la dimension (768), la graine, l'échauffement, les itérations et les jeux par
stockage : `local` 25 000 et 32 768 documents, `local` 100 000 documents à refus attendu sur la liste, le
détail, les voisins et la recherche sémantique, `postgresql` 25 000 et 100 000 documents. Chaque jeu est mesuré dans un `MINOS_HOME`
neuf ; son libellé porte le stockage, la taille et la dimension (`local-n25000-d768`).

Tous les jeux sont **synthétiques** : textes tirés de la graine, vecteurs du provider déterministe
`local-hash`, écrits directement par le port d'écriture du magasin sémantique
(`semanticVectorStore().replace`), hors synchronisation. Un jeu que la synchronisation du produit refuserait
de construire (budget `SemanticIndexBudget.DEFAULT`, relu au HEAD par le harnais : au plus 32 768 documents à
768 dimensions) porte la mention `beyond-build-budget` dans son libellé : il caractérise le magasin et les
lectures, pas un index que l'utilisateur peut obtenir. Le jeu local de 100 000 documents dépasse aussi le
décodage complet du magasin local : la liste, le détail, les voisins et la recherche sémantique (classement
exact par défaut du port `SemanticVectorStore.search`, qui charge l'index entier) y sont refusés, et le banc
constate ce refus.

### Mesures

| Section | Mesures |
|---|---|
| `dataset` | `documents`, `dimensions`, `writeMillis` (écriture du jeu), `beyondBuildBudget` (0 ou 1) |
| `projection` | `documents`, `durationMillis`, `passes` (parcours de l'index), `vectorPages` (pages de vecteurs lues), `vectorsRead`, `peakWorkingBytes`, `pointCount`, `candidateCount`, `sampled` |
| `explorer` | pour `projects`, `list-first`, `list-deep` (page atteinte par curseur), `detail`, `neighbors` (limite 20), `projection-status`, `projection-points` (limite 5 000), `search` (sémantique) : `coldMicros`, `p50Micros`, `p95Micros`, `maxMicros`, `allocatedBytesPerRequest` (médiane des octets alloués par la JVM pendant la requête, client compris), `responseBytes` |
| `heap` | tas occupé après trois collectes : avant le premier appel, après la liste, après les voisins, après la lecture de tous les points |

Les routes sont servies par un vrai serveur de l'explorateur sur un port éphémère de la boucle locale et
interrogées avec le jeton de session : la garde, l'encodage JSON et le magasin sont dans la mesure.

Une mesure impossible est une ligne `REFUSED section jeu raison`, sans chemin absolu ni secret, et sans
valeur ; les autres sections continuent. Pour une route, la raison donne le statut HTTP, le code d'erreur et
le motif du produit ; la page profonde de la liste reprend la raison de la page refusée en chemin. Code de sortie : 0 si chaque refus était attendu et chaque refus attendu s'est produit ;
1 sinon (un refus attendu qui ne se produit plus demande, sur `stderr.txt`, de retirer la déclaration de
`semantic.json`) ; 2 pour une erreur d'usage.

### PostgreSQL

Démarrer le serveur de test par `scripts/dev/start-test-postgres.ps1` (voir `docs/developer/testing.md`),
puis passer `-PostgresUrl` et `-PostgresUser`. Le mot de passe est lu dans `MINOS_TEST_POSTGRES_PASSWORD` et
transmis à la JVM par son environnement, ou lu par le produit dans le fichier restreint passé par
`-PostgresPasswordFile` ; il n'est écrit ni dans un argument ni dans `environment.txt`. Le harnais configure
chaque `MINOS_HOME` jetable sur le backend PostgreSQL par la configuration du produit, dans un schéma propre
à l'exécution et au jeu (`mb_<exécution>_n<documents>_d<dimension>`), et refuse un schéma qui contient déjà un
projet. L'enregistrement du backend PostgreSQL est celui des tests de `minos-bootstrap`
(`minos-bootstrap/src/test/postgresql-backend`), ajouté au classpath par le script : `minos-explorer` ne
dépend pas de `minos-storage`. Sans URL, les jeux PostgreSQL sont refusés en nommant le paramètre manquant,
et le code de sortie est non nul. Les schémas créés restent dans la base de test ; `start-test-postgres.ps1
-Stop` supprime le conteneur.

### Mesure de la projection seule

`minos-bootstrap/src/test/java/com/minos/bootstrap/semantic/SemanticProjectionMeasurementTest.java` reste la
mesure rapide de la préparation et de la lecture d'une projection, en stockage local, à 256 dimensions
(`docs/developer/semantic-explorer.md`) : elle ne mesure ni PostgreSQL, ni les routes HTTP, ni la dimension
d'un modèle réel, et peut garder ses `MINOS_HOME` pour les servir dans un navigateur.

### Durée

Les valeurs d'échauffement et d'itérations de `semantic.json` sont provisoires : la durée d'une exécution
locale complète n'a pas encore été relevée sur la machine de référence (tâche 2.3 du changement OpenSpec
`mesurer-la-projection-et-l-explorateur-au-banc`). Mémoire : `-Xmx8g` ; un jeu de 100 000 documents à 768
dimensions tient environ 0,6 Go de vecteurs en tas pendant son écriture.
