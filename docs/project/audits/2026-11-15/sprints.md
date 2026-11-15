# Ordre de correction : sprints

Le plan en trois temps ([`plan-action.md`](plan-action.md)) classe par urgence ; ce document classe par **ordre d'exécution**. Quand ils divergent, c'est cet ordre qui s'applique : une correction dont le prérequis n'est pas livré ne peut pas être vérifiée.

L'affectation a été contrôlée par script (`consolidate.py`) : chaque constat actionnable est dans exactement un sprint, aucun Info n'est affecté, et aucun prérequis n'est dans un sprint postérieur à celui qui en dépend.

## Vue d'ensemble

| Sprint | Titre | Constats | Composition | Charge (j) |
|---|---|---:|---|---|
| 1 | Remettre la mesure à niveau sur les zones nouvelles | 8 | 2 moyenne, 6 faible | 4,25 à 14 |
| 2 | Sécurité et chaîne d'intégration | 8 | 7 faible, 1 moyenne | 3,5 à 12 |
| 3 | Diagnostic et robustesse des surfaces | 5 | 2 moyenne, 3 faible | 2,75 à 9 |
| 4 | Performance de l'explorateur et de la projection | 4 | 2 moyenne, 2 faible | 2,5 à 8 |
| 5 | Budget mémoire et PostgreSQL | 4 | 1 moyenne, 3 faible | 1,75 à 6 |
| 6 | Voie WSL2 et hygiène des tests | 7 | 7 faible | 2,5 à 9 |
| 7 | Application : sortir les adaptateurs et réduire la façade | 6 | 2 moyenne, 4 faible | 7 à 20 |
| 8 | Garde de frontières, contrats et documentation | 5 | 5 faible | 4,75 à 14 |

Total : 29 à 92 jours. Charge par sprint en bornes basse et haute (S 0,25 à 1 j, M 1 à 3 j, L 3 à 8 j) : c'est une enveloppe, qui ne couvre ni la revue, ni les allers-retours de CI, ni les ADR que demandent les sprints 2, 7 et 8.

## Chemin critique

Les constats qui débloquent le plus de travail en aval :

1. AUD-PERF-13 (S1, effort M) débloque AUD-PERF-01, AUD-PERF-02, AUD-PERF-03 : Le banc benchmarks/scalability ne mesure ni la projection 2D ni les pages de l'explorateur : les constats de performance de cet audit sont des estimations sans référence
1. AUD-TST-02 (S1, effort M) débloque AUD-PERF-10, AUD-QUA-03 : Le front de l'explorateur (app.js, 784 lignes, 45 fonctions) n'est jamais exécuté par un test : seules des recherches de sous-chaînes le vérifient, sans contrôle de syntaxe ni lint en CI
1. AUD-ARC-02 (S7, effort L) débloque AUD-ARC-04, AUD-ARC-07 : MinosApplication sert de localisateur de services : 36 méthodes publiques d'instance, dont 15 rendent un type du moteur et compositionRoot() rend la racine de composition ; les cas d'usage reçoivent l'objet entier et y puisent 14 collaborateurs
1. AUD-ARC-01 (S7, effort L) débloque AUD-ARC-04, AUD-ARC-07 : minos-application embarque et câble elle-même trois adaptateurs d'infrastructure (client HTTP Ollama, analyseur Java fondé sur javac, lecteur de fichiers sidecar), hors de la racine de composition

## Sprint 1 — Remettre la mesure à niveau sur les zones nouvelles

**Pourquoi ici.** Les corrections des sprints suivants touchent l'explorateur, la projection 2D et la voie WSL2, que seul le scope JaCoCo global voit, et un front qu'aucun test n'exécute. Ce sprint donne un filet à ces zones, un banc de mesure à l'explorateur et à la projection, et ferme les tests qui passent à vide, sans toucher au code applicatif.

**Critère de sortie.** Chaque zone créée depuis le 10/10 (explorateur, projection 2D, voie WSL2, budget mémoire des vues) a un scope JaCoCo nominatif exigé ; app.js est exécuté par au moins un test et contrôlé en CI ; benchmarks/scalability produit une référence de durée et de latence pour la projection et les routes de l'explorateur ; aucun test ne passe à vide sur un OS ni ne se saute quand sa précondition échoue ; tout gate a un auto-test câblé ; un clone Windows avec core.autocrlf=true produit des *.sh en LF.

**Prérequis des sprints antérieurs.** aucun.
**Ordre interne.** libre ; la liste ci-dessous donne l’ordre recommandé.

| ID | Sévérité | Effort | Dépend de | Titre |
|---|---|---|---|---|
| AUD-TST-01 | Moyenne | S | – | Les zones nouvelles depuis le 10/10 (minos-explorer 1 291 l., voie WSL2 2 367 l., projection 2D ~1 017 l., QueryViewMemoryBudget 327 l.) ne sont dans aucun scope JaCoCo nominatif : seul le scope global les voit |
| AUD-TST-02 | Moyenne | M | – | Le front de l'explorateur (app.js, 784 lignes, 45 fonctions) n'est jamais exécuté par un test : seules des recherches de sous-chaînes le vérifient, sans contrôle de syntaxe ni lint en CI |
| AUD-PERF-13 | Faible | M | – | Le banc benchmarks/scalability ne mesure ni la projection 2D ni les pages de l'explorateur : les constats de performance de cet audit sont des estimations sans référence |
| AUD-TST-04 | Faible | S | – | Quatre tests passent encore à vide sur Linux par un retour anticipé `if (!windows) { return; }` écrit sur deux lignes, forme que le contrôle du suivi ne voit pas |
| AUD-TST-05 | Faible | S | – | Le test inter-JVM du verrou de synchronisation sémantique se saute au lieu d'échouer quand le processus détenteur du verrou ne démarre pas (stderr jeté) |
| AUD-TST-07 | Faible | S | – | Les tests « une fermeture depuis un autre fil n'empoisonne pas le bail » n'affirment pas que le bail est toujours tenu après cette fermeture ; six `assertTrue(true)` en tiennent lieu |
| AUD-TST-09 | Faible | M | – | Neuf gates de cohérence et check-current-docs.py n'ont pas d'auto-test, alors que chaque autre gate en a un rejoué en CI |
| AUD-DEP-02 | Faible | S | – | `.gitattributes` n'épingle pas `eol=lf` pour les 13 scripts `*.sh` ni pour `mvnw` : sur le poste du mainteneur (core.autocrlf=true) ils sont extraits en CRLF et échouent sous bash (`set: pipefail\r: invalid option name`) |

## Sprint 2 — Sécurité et chaîne d'intégration

**Pourquoi ici.** Défauts courts et indépendants : filtres Git hérités au clone d'un dépôt distant, invariant de secret tenu par le contenu des listes, garde Windows qui s'ouvre sur une variable illisible, jetons présents pendant le code de PR, et un pipeline à remettre d'équerre après l'abandon de develop.

**Critère de sortie.** La décision sur les filtres Git au clone distant est écrite (ADR SEC-*), validateCheckout passe par l'ouverture de confiance et un test fixe le comportement retenu ; l'exclusion des noms de secrets s'applique à toute variable transmise à un processus ; la garde Windows refuse sur une entrée illisible ; SONAR_TOKEN n'est dans l'environnement d'aucune étape qui exécute ./mvnw sur le head de la PR, ou une entrée nominative de check-workflow-pins.py l'accepte avec sa raison ; aucune PR documentaire ne déclenche la qualification Docker A→B ; aucun workflow n'installe Temurin 25 ; aucun document courant ni risque ouvert du registre ne cite develop comme branche vivante ; les commentaires compose disent la même chose que SEC-0074.

**Prérequis des sprints antérieurs.** aucun.
**Ordre interne.** libre ; la liste ci-dessous donne l’ordre recommandé.

| ID | Sévérité | Effort | Dépend de | Titre |
|---|---|---|---|---|
| AUD-SEC-01 | Faible | M | – | Le clone distant (JGit) est hors du périmètre de la décision sur les filtres Git : il applique les pilotes filter.* de la configuration de l'utilisateur au contenu d'un dépôt non fiable, et sa revalidation ouvre le dépôt sans passer par l'ouverture de confiance (règle 5) |
| AUD-SEC-05 | Faible | S | – | L'invariant « le motif de secret l'emporte sur toute liste » n'est pas appliqué aux variables héritées : il tient seulement par le contenu des listes statiques |
| AUD-QUA-05 | Faible | S | – | Garde des autorisations trop larges du bac à sable Windows : une variable d'environnement non analysable est ignorée, et le contrôle s'ouvre au lieu de refuser |
| AUD-DEP-05 | Faible | S | – | Le jeton SonarCloud est dans l'environnement du build Maven du code de la PR (`./mvnw test-compile` + plugin sonar), schéma refusé pour le jeton Docker Hub par AUD-DEP-13 |
| AUD-DEP-03 | Moyenne | S | – | Depuis l'abandon de develop, la qualification Docker A→B de 90 min est un check exigé de chaque PR vers main, sans filtre de chemins, PR documentaires et Dependabot comprises : décision écrite dans le workflow, à réévaluer |
| AUD-DEP-04 | Faible | S | – | L'exception « Java 25 pour le candidat A » est devenue sans objet : `main` est en Java 21, mais deux workflows installent encore Temurin 25 et le gate la garde ouverte |
| AUD-DEP-06 | Faible | S | – | Suppression de `develop` : aucun déclencheur, gate ni Dependabot ne la référence encore, mais la documentation courante décrit toujours le ruleset « Protect main & develop » et des contrôles « vers main et develop » |
| AUD-DEP-01 | Faible | M | – | Les commentaires des deux compose affirment que minos-admin lance les providers, contrairement à SEC-0074 ; l'image admin embarque des toolchains et un scip-java hors verrou (risque R-13 assumé) qu'aucun service n'exécute |

## Sprint 3 — Diagnostic et robustesse des surfaces

**Pourquoi ici.** L'observabilité avant les corrections de fond : les sprints 4 à 8 changent des chemins chauds et des frontières ; un échec interne doit laisser une cause exploitable et ne plus se déguiser en erreur d'usage. L'explorateur (ExplorerServer, ExplorerApi) est traité d'un bloc, avec un seul critère de journalisation pour la trace (AUD-QUA-02) et la limitation des refus (AUD-SEC-02). Ordre recommandé : AUD-QUA-02 puis AUD-QUA-01.

**Critère de sortie.** Tout échec interne d'une surface (CLI, API, MCP, explorateur, worker WSL) laisse une trace avec la cause racine dans un journal privé sans chemin absolu ni jeton ; une IllegalArgumentException non émise par la validation d'entrée est rendue comme erreur interne ; une requête de l'explorateur abandonnée libère son créneau ; les refus de l'explorateur sont journalisés avec limitation.

**Prérequis des sprints antérieurs.** aucun.
**Ordre interne.** libre ; la liste ci-dessous donne l’ordre recommandé.

| ID | Sévérité | Effort | Dépend de | Titre |
|---|---|---|---|---|
| AUD-QUA-02 | Moyenne | M | – | MCP, explorateur et worker WSL ne conservent aucune trace de pile d'un échec interne : seul le type de l'exception est écrit, et l'explorateur ne donne même pas la cause racine |
| AUD-QUA-01 | Moyenne | M | – | Les trois surfaces (explorateur, API, MCP) traduisent toute IllegalArgumentException en erreur d'usage sans rien journaliser : un bogue interne s'affiche comme « requête invalide » |
| AUD-SEC-02 | Faible | S | – | minos-explorer journalise chaque requête refusée sans limitation ; en Docker, le journal json-file du conteneur n'a pas de rotation |
| AUD-SEC-03 | Faible | S | – | Une requête API de minos-explorer abandonnée au délai garde son créneau tant que la lecture sous-jacente ne rend pas la main : quatre lectures qui ignorent l'interruption rendent l'API indisponible (503 BUSY) |
| AUD-SEC-04 | Faible | S | – | ExplorerServer.segments annonce refuser les segments « . » et « .. » mais les accepte |

## Sprint 4 — Performance de l'explorateur et de la projection

**Pourquoi ici.** Les relectures complètes d'index et le recomptage par page se corrigent une fois le banc de mesure et le test du front en place (sprint 1).

**Critère de sortie.** Mesuré sur le banc du sprint 1 : les passes d'ajustement de la projection ne relisent que l'échantillon ; aucune page de liste, détail ou voisinage ne décode l'index vectoriel entier en stockage local ; aucun COUNT(*) par page sous PostgreSQL ; le survol de la carte n'est pas linéaire en nombre de points ; chaque gain est mesuré avant/après sur un index réel.

**Prérequis des sprints antérieurs.** AUD-PERF-13 (S1), AUD-TST-02 (S1).
**Ordre interne.** libre ; la liste ci-dessous donne l’ordre recommandé.

| ID | Sévérité | Effort | Dépend de | Titre |
|---|---|---|---|---|
| AUD-PERF-01 | Moyenne | S | AUD-PERF-13 | Préparation de la projection 2D sous PostgreSQL et pages de l'explorateur : chaque page de 1 000 vecteurs recompte tous les documents du projet (COUNT(*) par page, coût quadratique par passe, calculé et non mesuré) |
| AUD-PERF-02 | Faible | M | AUD-PERF-13 | Projection PCA : jusqu'à 43 lectures complètes de l'index (borne théorique, nombre réel de passes non relevé), y compris pour les passes d'ajustement qui ne retiennent qu'un échantillon |
| AUD-PERF-03 | Moyenne | M | AUD-PERF-13 | Explorateur sur stockage local : liste, détail et voisins chargent et décodent l'index vectoriel entier à chaque requête (la page triée en entier), au-delà d'environ 256 Mio sans cache ni coalescence |
| AUD-PERF-10 | Faible | S | AUD-TST-02 | Carte 2D côté navigateur : survol en recherche linéaire sur tous les points, filtre réalloué à chaque événement, jusqu'à 1 million de points chargés en 200 requêtes |

## Sprint 5 — Budget mémoire et PostgreSQL

**Pourquoi ici.** Mêmes fichiers que le budget des vues (DATA-0081) : les deux constats laissés ouverts le 11/11 et les caches qui échappent au budget se traitent ensemble, après le sprint 4 qui touche les mêmes stores.

**Critère de sortie.** Tout cache applicatif est soit compté dans le budget de mémoire des vues, soit plafonné par une fraction du tas écrite dans l'ADR ; la charge utile PostgreSQL est relue sur (project_id, snapshot_id, sha256) ; une vue hors cache reste comptée jusqu'à sa libération ; la zone de travail PostgreSQL du plan de requête est dimensionnée pour le nombre de JVM.

**Prérequis des sprints antérieurs.** aucun.
**Ordre interne.** libre ; la liste ci-dessous donne l’ordre recommandé.

| ID | Sévérité | Effort | Dépend de | Titre |
|---|---|---|---|---|
| AUD-PERF-04 | Moyenne | M | – | Caches applicatifs à taille fixe hors du budget de mémoire des vues : corpus hybride 256 Mio, index vectoriel 256 Mio, graphe de programme 256 Mio, projection jusqu'à 512 Mio de fichier, symboles retenus par l'explorateur |
| AUD-PERF-05 | Faible | S | – | AUD-PERF-16 toujours ouvert : la charge utile PostgreSQL est relue par project_id seul, hors de la version dont la taille a été réservée |
| AUD-PERF-06 | Faible | S | – | AUD-PERF-17 toujours ouvert : une vue plus lourde que le plafond effectif du cache mais sous le budget est admise, refusée par le cache puis rendue hors compte |
| AUD-PERF-09 | Faible | S | – | Plan de requête Docker : la zone de travail PostgreSQL est un tmpfs de 256 Mio partagé par toutes les JVM du conteneur, alors que chaque JVM y copie la charge utile entière |

## Sprint 6 — Voie WSL2 et hygiène des tests

**Pourquoi ici.** Les hachages et lectures répétés de la voie WSL2 se corrigent une fois son scope nominatif en place ; les tests fragiles et les planchers bas se resserrent en dernier, quand les chemins qu'ils protègent sont stabilisés.

**Critère de sortie.** La voie WSL2 hache l'arbre source au plus une fois par indexation et lit l'artefact SCIP au plus deux fois ; aucun test n'affirme une absence après un délai fixe ; les classes de la frontière de confiance des providers ont un plancher de branches d'au moins 0,5 ; les classes UI du plugin ont un test ; le document de mutation décrit les modules réels.

**Prérequis des sprints antérieurs.** aucun.
**Ordre interne.** libre ; la liste ci-dessous donne l’ordre recommandé.

| ID | Sévérité | Effort | Dépend de | Titre |
|---|---|---|---|---|
| AUD-PERF-07 | Faible | S | – | Voie WSL2 : l'arbre source est haché une fois de plus par indexation scip-java (seul indexeur de cette voie), en plus des empreintes déjà calculées par l'orchestration |
| AUD-PERF-08 | Faible | S | – | Voie WSL2 : l'artefact SCIP (jusqu'à 512 Mio) est lu cinq à six fois entre l'acceptation du paquet et sa copie sous runs/ |
| AUD-PERF-11 | Faible | S | – | Dérivation des tests liés : une expression régulière recompilée par symbole via replaceAll |
| AUD-TST-06 | Faible | S | – | Des tests reposent encore sur un délai fixe ou une fenêtre d'horloge réelle : affirmation négative après Thread.sleep(200 ms), fenêtres de cadence de 2 s, mesure de tas après System.gc() |
| AUD-TST-03 | Faible | S | – | Planchers JaCoCo de classe très bas sur la frontière de confiance des providers : ProviderProcessEnvironment branche 0,20, ScipJavaProcessPlanFactory 0,35 / 0,15, StrongProcessOwnershipIndexerExecutor 0,35 / 0,18, ManagedPolyglotScipRuntimeManager 0,28 / 0,20 |
| AUD-TST-08 | Faible | M | – | Plugin IntelliJ : 8 classes (~1 020 lignes : panneau, actions avancées, réglages, service, contrôleur, navigation) sans aucune référence dans les tests ; plancher de couverture resté à 0,33 depuis le 15/10 |
| AUD-TST-10 | Faible | S | – | Mutation : le document de couverture PIT décrit des modules disparus (minos-storage-local, minos-storage-postgresql) et ne contient aucune mesure de minos-explorer ni du minos-storage fusionné |

## Sprint 7 — Application : sortir les adaptateurs et réduire la façade

**Pourquoi ici.** Chantier de fond, à précéder d'un ADR. Il vient après les sprints de diagnostic et de mesure, parce qu'il déplace du code sur les chemins que ces sprints instrumentent.

**Critère de sortie.** minos-application n'instancie plus d'adaptateur d'infrastructure (Ollama, analyseur javac, sidecar, historique CLI) : ils sont câblés par minos-bootstrap derrière un port ; les cas d'usage reçoivent leurs collaborateurs, pas MinosApplication ; minos-app ne porte plus de politique de lancement ; les codes de sortie viennent d'un seul contrat.

**Prérequis des sprints antérieurs.** aucun.
**Ordre interne.** libre ; la liste ci-dessous donne l’ordre recommandé.

| ID | Sévérité | Effort | Dépend de | Titre |
|---|---|---|---|---|
| AUD-ARC-02 | Moyenne | L | – | MinosApplication sert de localisateur de services : 36 méthodes publiques d'instance, dont 15 rendent un type du moteur et compositionRoot() rend la racine de composition ; les cas d'usage reçoivent l'objet entier et y puisent 14 collaborateurs |
| AUD-ARC-01 | Moyenne | L | – | minos-application embarque et câble elle-même trois adaptateurs d'infrastructure (client HTTP Ollama, analyseur Java fondé sur javac, lecteur de fichiers sidecar), hors de la racine de composition |
| AUD-ARC-03 | Faible | S | – | Un format de persistance (cli-index-history/<projet>.properties) est écrit et relu par deux classes de minos-application, sans port ni adaptateur de stockage |
| AUD-ARC-05 | Faible | S | – | minos-app continue d'accumuler du comportement : la politique de démarrage de l'explorateur (refus mode équipe, refus backend Docker) s'y ajoute, en double de celle d'ExplorerSession et appuyée sur la configuration du backend MCP |
| AUD-ARC-06 | Faible | S | – | Le contrat des codes de sortie est importé depuis une commande particulière (FindSymbolCommand) dans 28 fichiers de production, dont 3 de minos-app, alors que CliExitCodes existe ; ExplorerSession redéfinit 0/1/2 |
| AUD-QUA-04 | Faible | S | – | La CLI recopie champ à champ, dans des records miroirs identiques, des vues que minos-application expose déjà et qu'elle importe |

## Sprint 8 — Garde de frontières, contrats et documentation

**Pourquoi ici.** Le garde se simplifie après le sprint 7, qui en change les tables ; la langue du contrat de l'explorateur demande une décision (ADR ou v2 du contrat), pas un renommage dans v1 ; la documentation d'architecture se réécrit en dernier, sur le découpage final.

**Critère de sortie.** Les tables du garde de frontières ont une seule source lue par le garde et par ModuleArchitectureTest ; IndexingRunExecutor n'est plus une classe statique unique et SnapshotBinaryCodecSupport est découpé par section de format ; la langue du contrat de l'explorateur est tranchée par écrit et app.js suit ; minos-nexus est reclassé ou absorbé ; la prose d'architecture (ports du domaine, racines du moteur, explorateur) correspond au code.

**Prérequis des sprints antérieurs.** AUD-ARC-01 (S7), AUD-ARC-02 (S7), AUD-TST-02 (S1).
**Ordre interne.** AUD-ARC-08 avant AUD-ARC-07.

| ID | Sévérité | Effort | Dépend de | Titre |
|---|---|---|---|---|
| AUD-ARC-08 | Faible | S | – | minos-nexus est classé surface mais sert de bibliothèque de traduction à une autre surface : arête minos-cli → minos-nexus en portée compile, module de 4 classes (347 l.) dont une sans appelant |
| AUD-ARC-04 | Faible | M | AUD-ARC-01, AUD-ARC-02 | Les tables du garde de frontières sont recopiées dans ModuleArchitectureTest : chaque mouvement de cliquet (82 commits sur check-module-boundaries.py, 1 912 l.) doit être fait deux fois |
| AUD-ARC-09 | Faible | L | – | Deux classes de plus de 1 000 lignes sur les chemins les plus modifiés : IndexingRunExecutor (1 025 l., classe statique de 81 méthodes, 37 commits) et SnapshotBinaryCodecSupport (1 106 l., 20 commits) |
| AUD-QUA-03 | Faible | S | AUD-TST-02 | Le contrat v1 de l'explorateur mélange constantes françaises (PRETE, PERIMEE, IDENTIQUE, SOURCE_INTROUVABLE) et anglaises (READY, STALE, INDEX_NOT_READY), et ses messages mêlent les deux langues |
| AUD-ARC-07 | Faible | S | AUD-ARC-01, AUD-ARC-02, AUD-ARC-08 | La documentation d'architecture diverge du code sur des points que la règle A9 ne contrôle pas (ports du domaine, racines du moteur, route de l'explorateur, nombre de modules) |

## Constats Info, hors sprint

Ils documentent ce qui tient et ne demandent aucune action. Plusieurs expliquent pourquoi un risque est déjà borné : ne pas les « corriger » par erreur.

- **AUD-ARC-10** (Architecture) : Ce qui tient, à ne pas « corriger » : graphe de modules acyclique et conforme aux POM, garde vert avec 252 auto-tests, aucune arête d'import sans dépendance déclarée, explorateur correctement isolé
- **AUD-DEP-07** (Dépendances) : Ce qui tient (à ne pas « corriger ») : 104 usages d'actions épinglés par SHA, aucun `pull_request_target`, permissions minimales, gates de chaîne d'approvisionnement verts, verrous npm sans avis, veille des outils fail-closed
- **AUD-PERF-12** (Performance) : Ce qui tient dans l'explorateur et la projection : calcul hors requête, réponses bornées, front léger, recherche pgvector exacte décidée
- **AUD-QUA-07** (Qualité) : Hygiène de base toujours solide et mesurée : 0 TODO/FIXME, 0 printStackTrace, 41 catch vides tous commentés, 0 cast générique ni type brut, duplication Java inférieure à 1 %, 13 méthodes de plus de 80 lignes (16 au 10/10), gates qualité verts
- **AUD-SEC-06** (Sécurité) : minos-explorer (nouveau) : la frontière HTTP locale tient — liaison loopback, Host/Origin/Sec-Fetch-Site, jeton 256 bits comparé en temps constant, CSP stricte, front sans innerHTML
- **AUD-SEC-07** (Sécurité) : Mode conteneur de minos-explorer : port joignable par les autres conteneurs des réseaux minos-runtime/minos-explorer-ingress et sortie réseau ouverte — limites documentées, le jeton reste la barrière
- **AUD-SEC-08** (Sécurité) : Voie WSL2 (nouvelle) : environnement en liste d'autorisation, wsl.exe absolu, scope systemd, sortie bornée, provider sous bac à sable Linux et artefact revérifié par empreinte
- **AUD-SEC-09** (Sécurité) : SQL : aucune concaténation de valeur ; toutes les requêtes sont paramétrées et les identifiants de schéma passent par enquoteIdentifier / format('%I')
- **AUD-SEC-10** (Sécurité) : Inspection pgAdmin : rôle minos_inspect en lecture seule par privilèges (liste fermée de tables, projects sans root_value), secrets en fichiers, port publié sur la boucle locale
- **AUD-TST-11** (Tests) : La voie WSL2 n'a aucun test bout en bout en CI : tous ses tests simulent wsl.exe ; la qualification réelle est manuelle (décision documentée)
- **AUD-TST-12** (Tests) : Ce qui tient : 0 @Disabled, 0 Mockito, 4 315 méthodes de test, 36 classes PostgreSQL réelles exigées en CI Linux, confinement en mode requis sur les deux OS, explorateur couvert côté sécurité, 62/62 gates verts et tous les test_*.py câblés
