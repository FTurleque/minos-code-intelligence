# Plan d'action

Classement par **urgence**. L'ordre d'exécution, qui prime quand les deux divergent, est dans [`sprints.md`](sprints.md) : une action urgente dont le prérequis n'est pas livré ne peut pas être vérifiée.

Aucun constat n'est Critique ni Élevé : il n'y a pas d'urgence au sens du barème. « Immédiat » regroupe donc les Moyennes d'effort court et les deux constats qui conditionnent la vérification des autres.

## Immédiat

| ID | Sévérité | Effort | Sprint | Dépend de | Titre |
|---|---|---|---|---|---|
| AUD-TST-01 | Moyenne | S | S1 | – | Les zones nouvelles depuis le 10/10 (minos-explorer 1 291 l., voie WSL2 2 367 l., projection 2D ~1 017 l., QueryViewMemoryBudget 327 l.) ne sont dans aucun scope JaCoCo nominatif : seul le scope global les voit |
| AUD-PERF-13 | Faible | M | S1 | – | Le banc benchmarks/scalability ne mesure ni la projection 2D ni les pages de l'explorateur : les constats de performance de cet audit sont des estimations sans référence |
| AUD-DEP-03 | Moyenne | S | S2 | – | Depuis l'abandon de develop, la qualification Docker A→B de 90 min est un check exigé de chaque PR vers main, sans filtre de chemins, PR documentaires et Dependabot comprises : décision écrite dans le workflow, à réévaluer |
| AUD-SEC-01 | Faible | M | S2 | – | Le clone distant (JGit) est hors du périmètre de la décision sur les filtres Git : il applique les pilotes filter.* de la configuration de l'utilisateur au contenu d'un dépôt non fiable, et sa revalidation ouvre le dépôt sans passer par l'ouverture de confiance (règle 5) |
| AUD-PERF-01 | Moyenne | S | S4 | AUD-PERF-13 | Préparation de la projection 2D sous PostgreSQL et pages de l'explorateur : chaque page de 1 000 vecteurs recompte tous les documents du projet (COUNT(*) par page, coût quadratique par passe, calculé et non mesuré) |

## Court terme (1 mois)

| ID | Sévérité | Effort | Sprint | Dépend de | Titre |
|---|---|---|---|---|---|
| AUD-DEP-02 | Faible | S | S1 | – | `.gitattributes` n'épingle pas `eol=lf` pour les 13 scripts `*.sh` ni pour `mvnw` : sur le poste du mainteneur (core.autocrlf=true) ils sont extraits en CRLF et échouent sous bash (`set: pipefail\r: invalid option name`) |
| AUD-TST-02 | Moyenne | M | S1 | – | Le front de l'explorateur (app.js, 784 lignes, 45 fonctions) n'est jamais exécuté par un test : seules des recherches de sous-chaînes le vérifient, sans contrôle de syntaxe ni lint en CI |
| AUD-TST-04 | Faible | S | S1 | – | Quatre tests passent encore à vide sur Linux par un retour anticipé `if (!windows) { return; }` écrit sur deux lignes, forme que le contrôle du suivi ne voit pas |
| AUD-TST-05 | Faible | S | S1 | – | Le test inter-JVM du verrou de synchronisation sémantique se saute au lieu d'échouer quand le processus détenteur du verrou ne démarre pas (stderr jeté) |
| AUD-TST-07 | Faible | S | S1 | – | Les tests « une fermeture depuis un autre fil n'empoisonne pas le bail » n'affirment pas que le bail est toujours tenu après cette fermeture ; six `assertTrue(true)` en tiennent lieu |
| AUD-TST-09 | Faible | M | S1 | – | Neuf gates de cohérence et check-current-docs.py n'ont pas d'auto-test, alors que chaque autre gate en a un rejoué en CI |
| AUD-DEP-01 | Faible | M | S2 | – | Les commentaires des deux compose affirment que minos-admin lance les providers, contrairement à SEC-0074 ; l'image admin embarque des toolchains et un scip-java hors verrou (risque R-13 assumé) qu'aucun service n'exécute |
| AUD-DEP-04 | Faible | S | S2 | – | L'exception « Java 25 pour le candidat A » est devenue sans objet : `main` est en Java 21, mais deux workflows installent encore Temurin 25 et le gate la garde ouverte |
| AUD-DEP-05 | Faible | S | S2 | – | Le jeton SonarCloud est dans l'environnement du build Maven du code de la PR (`./mvnw test-compile` + plugin sonar), schéma refusé pour le jeton Docker Hub par AUD-DEP-13 |
| AUD-DEP-06 | Faible | S | S2 | – | Suppression de `develop` : aucun déclencheur, gate ni Dependabot ne la référence encore, mais la documentation courante décrit toujours le ruleset « Protect main & develop » et des contrôles « vers main et develop » |
| AUD-QUA-05 | Faible | S | S2 | – | Garde des autorisations trop larges du bac à sable Windows : une variable d'environnement non analysable est ignorée, et le contrôle s'ouvre au lieu de refuser |
| AUD-SEC-05 | Faible | S | S2 | – | L'invariant « le motif de secret l'emporte sur toute liste » n'est pas appliqué aux variables héritées : il tient seulement par le contenu des listes statiques |
| AUD-QUA-01 | Moyenne | M | S3 | – | Les trois surfaces (explorateur, API, MCP) traduisent toute IllegalArgumentException en erreur d'usage sans rien journaliser : un bogue interne s'affiche comme « requête invalide » |
| AUD-QUA-02 | Moyenne | M | S3 | – | MCP, explorateur et worker WSL ne conservent aucune trace de pile d'un échec interne : seul le type de l'exception est écrit, et l'explorateur ne donne même pas la cause racine |
| AUD-SEC-02 | Faible | S | S3 | – | minos-explorer journalise chaque requête refusée sans limitation ; en Docker, le journal json-file du conteneur n'a pas de rotation |
| AUD-SEC-03 | Faible | S | S3 | – | Une requête API de minos-explorer abandonnée au délai garde son créneau tant que la lecture sous-jacente ne rend pas la main : quatre lectures qui ignorent l'interruption rendent l'API indisponible (503 BUSY) |
| AUD-SEC-04 | Faible | S | S3 | – | ExplorerServer.segments annonce refuser les segments « . » et « .. » mais les accepte |
| AUD-PERF-02 | Faible | M | S4 | AUD-PERF-13 | Projection PCA : jusqu'à 43 lectures complètes de l'index (borne théorique, nombre réel de passes non relevé), y compris pour les passes d'ajustement qui ne retiennent qu'un échantillon |
| AUD-PERF-03 | Moyenne | M | S4 | AUD-PERF-13 | Explorateur sur stockage local : liste, détail et voisins chargent et décodent l'index vectoriel entier à chaque requête (la page triée en entier), au-delà d'environ 256 Mio sans cache ni coalescence |
| AUD-PERF-10 | Faible | S | S4 | AUD-TST-02 | Carte 2D côté navigateur : survol en recherche linéaire sur tous les points, filtre réalloué à chaque événement, jusqu'à 1 million de points chargés en 200 requêtes |
| AUD-PERF-05 | Faible | S | S5 | – | AUD-PERF-16 toujours ouvert : la charge utile PostgreSQL est relue par project_id seul, hors de la version dont la taille a été réservée |
| AUD-PERF-06 | Faible | S | S5 | – | AUD-PERF-17 toujours ouvert : une vue plus lourde que le plafond effectif du cache mais sous le budget est admise, refusée par le cache puis rendue hors compte |
| AUD-PERF-09 | Faible | S | S5 | – | Plan de requête Docker : la zone de travail PostgreSQL est un tmpfs de 256 Mio partagé par toutes les JVM du conteneur, alors que chaque JVM y copie la charge utile entière |
| AUD-PERF-07 | Faible | S | S6 | – | Voie WSL2 : l'arbre source est haché une fois de plus par indexation scip-java (seul indexeur de cette voie), en plus des empreintes déjà calculées par l'orchestration |
| AUD-PERF-08 | Faible | S | S6 | – | Voie WSL2 : l'artefact SCIP (jusqu'à 512 Mio) est lu cinq à six fois entre l'acceptation du paquet et sa copie sous runs/ |
| AUD-PERF-11 | Faible | S | S6 | – | Dérivation des tests liés : une expression régulière recompilée par symbole via replaceAll |
| AUD-TST-03 | Faible | S | S6 | – | Planchers JaCoCo de classe très bas sur la frontière de confiance des providers : ProviderProcessEnvironment branche 0,20, ScipJavaProcessPlanFactory 0,35 / 0,15, StrongProcessOwnershipIndexerExecutor 0,35 / 0,18, ManagedPolyglotScipRuntimeManager 0,28 / 0,20 |
| AUD-TST-06 | Faible | S | S6 | – | Des tests reposent encore sur un délai fixe ou une fenêtre d'horloge réelle : affirmation négative après Thread.sleep(200 ms), fenêtres de cadence de 2 s, mesure de tas après System.gc() |
| AUD-TST-08 | Faible | M | S6 | – | Plugin IntelliJ : 8 classes (~1 020 lignes : panneau, actions avancées, réglages, service, contrôleur, navigation) sans aucune référence dans les tests ; plancher de couverture resté à 0,33 depuis le 15/10 |
| AUD-TST-10 | Faible | S | S6 | – | Mutation : le document de couverture PIT décrit des modules disparus (minos-storage-local, minos-storage-postgresql) et ne contient aucune mesure de minos-explorer ni du minos-storage fusionné |
| AUD-ARC-03 | Faible | S | S7 | – | Un format de persistance (cli-index-history/<projet>.properties) est écrit et relu par deux classes de minos-application, sans port ni adaptateur de stockage |
| AUD-ARC-05 | Faible | S | S7 | – | minos-app continue d'accumuler du comportement : la politique de démarrage de l'explorateur (refus mode équipe, refus backend Docker) s'y ajoute, en double de celle d'ExplorerSession et appuyée sur la configuration du backend MCP |
| AUD-ARC-06 | Faible | S | S7 | – | Le contrat des codes de sortie est importé depuis une commande particulière (FindSymbolCommand) dans 28 fichiers de production, dont 3 de minos-app, alors que CliExitCodes existe ; ExplorerSession redéfinit 0/1/2 |
| AUD-QUA-04 | Faible | S | S7 | – | La CLI recopie champ à champ, dans des records miroirs identiques, des vues que minos-application expose déjà et qu'elle importe |

## Fond (chantiers structurels, avec ADR)

| ID | Sévérité | Effort | Sprint | Dépend de | Titre |
|---|---|---|---|---|---|
| AUD-PERF-04 | Moyenne | M | S5 | – | Caches applicatifs à taille fixe hors du budget de mémoire des vues : corpus hybride 256 Mio, index vectoriel 256 Mio, graphe de programme 256 Mio, projection jusqu'à 512 Mio de fichier, symboles retenus par l'explorateur |
| AUD-ARC-01 | Moyenne | L | S7 | – | minos-application embarque et câble elle-même trois adaptateurs d'infrastructure (client HTTP Ollama, analyseur Java fondé sur javac, lecteur de fichiers sidecar), hors de la racine de composition |
| AUD-ARC-02 | Moyenne | L | S7 | – | MinosApplication sert de localisateur de services : 36 méthodes publiques d'instance, dont 15 rendent un type du moteur et compositionRoot() rend la racine de composition ; les cas d'usage reçoivent l'objet entier et y puisent 14 collaborateurs |
| AUD-ARC-04 | Faible | M | S8 | AUD-ARC-01, AUD-ARC-02 | Les tables du garde de frontières sont recopiées dans ModuleArchitectureTest : chaque mouvement de cliquet (82 commits sur check-module-boundaries.py, 1 912 l.) doit être fait deux fois |
| AUD-ARC-07 | Faible | S | S8 | AUD-ARC-01, AUD-ARC-02, AUD-ARC-08 | La documentation d'architecture diverge du code sur des points que la règle A9 ne contrôle pas (ports du domaine, racines du moteur, route de l'explorateur, nombre de modules) |
| AUD-ARC-08 | Faible | S | S8 | – | minos-nexus est classé surface mais sert de bibliothèque de traduction à une autre surface : arête minos-cli → minos-nexus en portée compile, module de 4 classes (347 l.) dont une sans appelant |
| AUD-ARC-09 | Faible | L | S8 | – | Deux classes de plus de 1 000 lignes sur les chemins les plus modifiés : IndexingRunExecutor (1 025 l., classe statique de 81 méthodes, 37 commits) et SnapshotBinaryCodecSupport (1 106 l., 20 commits) |
| AUD-QUA-03 | Faible | S | S8 | AUD-TST-02 | Le contrat v1 de l'explorateur mélange constantes françaises (PRETE, PERIMEE, IDENTIQUE, SOURCE_INTROUVABLE) et anglaises (READY, STALE, INDEX_NOT_READY), et ses messages mêlent les deux langues |

## Dépendances entre tâches

- AUD-PERF-01 → AUD-PERF-13 (S1)
- AUD-PERF-02 → AUD-PERF-13 (S1)
- AUD-PERF-03 → AUD-PERF-13 (S1)
- AUD-PERF-10 → AUD-TST-02 (S1)
- AUD-ARC-04 → AUD-ARC-01 (S7), AUD-ARC-02 (S7)
- AUD-QUA-03 → AUD-TST-02 (S1)
- AUD-ARC-07 → AUD-ARC-01 (S7), AUD-ARC-02 (S7), AUD-ARC-08 (S8)
