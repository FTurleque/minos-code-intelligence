# État courant — MINOS

État produit revérifié le : **7 octobre 2026** ; mis à jour le **13 octobre 2026** (sprints 1 et 2 de l'audit du 10 octobre, sprint 3 en partie). Planification additionnelle : **5 octobre 2026**.

Ce fichier est la synthèse autoritative de l'état produit courant. Les réconciliations détaillées antérieures restent archivées sous [`history/reconciliations/`](../history/reconciliations/). Une capacité présente sur une branche ou une PR n'est dite intégrée dans `develop` qu'après merge ; le présent document décrit néanmoins les garanties du HEAD qui le contient afin qu'il reste exact avant et après promotion.

## Planification additionnelle — 2026-10-05

Les ADR 0055–0057 sont acceptés comme direction, sans revendiquer leur implémentation. [Suivi du chantier storage/hexagonal](../roadmap/storage-hexagonal-2026-10/README.md). La présence de ce plan ne signifie ni promotion de develop vers main ni clôture des audits. Les preuves d'implémentation seront ajoutées dans le suivi des tâches.

## Audit 2026-10-10 — sprint 1 (2026-10-10)

Le sprint 1 de l'audit du 10 octobre 2026 (« remettre les dispositifs de mesure en marche », 9 constats) est **corrigé, fusionné dans `develop` et archivé** (PR #392 à #394 et #396 à #399) :

- **Checks exigés par le ruleset** : `Verify (ubuntu-24.04)`, `Verify (windows-2022)`, `Dependency vulnerability gate / osv-scan`, `Static invariants (single run)`, `Gitleaks`, `IntelliJ plugin (gate)` et `SonarCloud Code Analysis` (ruleset « Protect main & develop ») ; `Docker upgrade evidence gate` pour `main` seul. La liste attendue est versionnée dans `.github/required-checks.json` ; `scripts/quality/check-ci-wiring.py` (job `invariants`) vérifie que chaque check résout un job qui peut rendre un verdict sur une PR, et `scripts/quality/verify-ruleset.py` (à la main) mesure l'écart avec le ruleset réel (code 0 constaté le 2026-10-10).
- **Auto-tests de gates** : `check-jacoco.py --self-test` et l'auto-test du garde de jalons sont rejoués dans `invariants` ; `check-ci-wiring.py` refuse tout auto-test qui n'y serait pas câblé.
- **Confinement** : `-Dminos.sandbox.tests.required=true` sur les deux jobs `verify` ; un bubblewrap, un cgroup v2 ou un AppContainer indisponible fait échouer le job de son système au lieu d'être sauté en silence. Les tests propres à une plateforme sont comptés comme sautés ailleurs.
- **Plugin IntelliJ** : workflow exécuté sur toute PR, portée calculée (`scripts/ci/plugin-gate.py`) et gate agrégatrice toujours rendue ; la portée inclut les rendus JSON de `minos-application`, le domaine et les goldens de caractérisation.
- **Architecture** : garde de frontières testée règle par règle (154 cas), groupId interne lu dans le POM racine, **aucun cycle de packages** (règle A8, table `KNOWN_PACKAGE_CYCLES` vide, plugin compris), listes de dépendances d'`arc42/05` confrontées aux POM (règle A9), liste fermée des types de `minos-runtime-local` nommables par les autres modules (règle A10), dette d'architecture figée par quatre listes fermées en cliquet (règles A11 à A14 : types du moteur par surface, lanceurs de processus, types entre adaptateurs, racines de packages) et arêtes d'exécution par service écrites dans le document de dépendances généré. Les quatre cycles existants ont été levés par le déplacement de 13 classes de production (et de leurs tests) dans leur module.

Capacités OpenSpec créées ou étendues : `controles-de-fusion`, `frontieres-de-modules`, `confinement-code-non-fiable`.

## Audit 2026-10-10 — sprint 2 (2026-10-13)

Le sprint 2 (« failles de sécurité et observabilité », 7 constats) est **corrigé, fusionné dans `develop` et archivé** (PR #406 à #410, #420 et #429) :

- **Dépôt Git analysé** : propriétaire contrôlé, aucun filtre `clean`/`smudge` déclaré par le dépôt n'est exécuté ; un dépôt trouvé au-dessus de la racine du projet reste accepté et signalé par la limitation `REPOSITORY_ABOVE_PROJECT_ROOT`.
- **Installation des providers** : les commandes d'installation et les sondes reçoivent un environnement en liste d'autorisation, sans `MINOS_*` ni jeton ; hors paquet Windows, le classpath de scip-java est résolu par `coursier fetch` puis vérifié jar par jar contre un verrou SHA-256 avant d'être publié. À l'indexation, la copie du classpath rangée dans `MINOS_HOME/tools` est revérifiée avant chaque exécution, sous Windows comme sous Linux ; `coursier launch` n'est plus utilisé ; un lanceur autonome du `PATH` (image Docker) ne sert qu'en l'absence de classpath installé.
- **Journalisation** : le jar ombré lie SLF4J à `java.util.logging` (stderr ; stdout reste réservé au protocole MCP) ; les échecs des tâches d'arrière-plan du plugin sont journalisés dans `idea.log` ; les journaux de récupération de MINOS ne portent ni chemin absolu ni cause, sous garde (`check-private-io.py`).
- **Scripts Windows** : le mot de passe PostgreSQL est écrit dans un fichier déjà restreint et vérifié ; le lanceur de développement `minos.cmd` ne cherche plus `java` dans le répertoire courant.

Preuves, écarts avec le critère de sortie et suite à ouvrir : [`docs/project/audits/2026-10-10/SUIVI.md`](../audits/2026-10-10/SUIVI.md). Capacités OpenSpec créées ou étendues par l'archivage : voir `openspec/specs/`. Les sprints 3 et 4 sont traités (sections suivantes) ; les sprints 5 à 11 (chaîne de release, couverture réelle, sorties publiques, confinement et E/S privées, orchestration d'indexation, frontières d'architecture, hygiène) restent à traiter.

## Audit 2026-10-10 — sprint 3 (2026-10-13, restes mis à jour le 2026-10-16)

Les cinq changements du sprint 3 (« socle d'exécution supporté ») sont **fusionnés dans `develop`** (PR #434 à #437 et #441) :

- **Java 25 LTS** : le noyau, la CLI, le MCP, le stockage et les providers sont compilés pour Java 25 (enforcer `[25,26)`), les deux images partent d'`eclipse-temurin:25` sur Ubuntu 24.04, le runtime de l'installateur Windows est produit par le JDK 25 ; le plugin IntelliJ reste en Java 21. La version est énoncée une fois (POM racine) et gardée par `check-toolchain-baseline.py` ; la politique de toolchain date la fin de support (ADR 0059). Une installation par jar demande un JDK 25.
- **Node.js 24 LTS dans l'image Docker**, la même ligne que la distribution Windows ; l'inventaire de provenance lit les versions de .NET, Go, Rust et Node sur les binaires installés, et la qualification d'image les compare.
- **Veille des dépendances livrées** : les deux verrous npm embarqués sont analysés par le gate OSV de chaque PR ; une veille hebdomadaire, non exigée, interroge une base d'avis pour le catalogue d'outils et le classpath de scip-java. Elle ne tournera qu'après la promotion vers `main`, et signale un avis sur le classpath de scip-java 0.13.2 (guava 33.6.0-jre, sans version corrigée dans une release de scip-java), sous dérogation datée jusqu'au 2027-01-28 (risque R-29).
- **Notices et licences tierces** : le jar ombré et les images conservent les notices (agrégées) et un fichier de licence par dépendance ; `META-INF/LICENSE` est la licence de MINOS. Douze dépendances ne livrent aucun fichier de licence dans leur jar et ne sont couvertes que par le SBOM ; les outils non Maven de l'image sont hors périmètre.

- **Image de requête séparée** : le plan de requête MCP et les amorçages tournent sur une image de 355 Mo (JRE et jar, aucun outil de compilation) ; l'indexation et les outils de provider gardent l'image complète. Une installation à une image reçoit la seconde à l'`Install` suivant.

Le défaut mis au jour par ce sprint — les outils MCP échouaient avec la racine de données en lecture seule, comme la montent les compose pour le plan de requête — est corrigé (changement `lire-le-registre-sans-ecrire`) : une lecture du registre ou des observations runtime prend un verrou partagé sans rien écrire, et la qualification de l'image interroge le serveur sur un volume en lecture seule. Conditions et limites : [`docs/user/docker-runtime.md`](../../user/docker-runtime.md).

Limites : aucun contrôle de CI n'exécute un provider géré ; les providers ont été qualifiés sur le nouveau socle par exécution directe sous Linux, pas sous Windows. La distribution Windows 1.3.0 indexe par son runtime Java 25 embarqué (preuve du 2026-10-16 sur un projet TypeScript ; `migrer-le-socle-vers-java-25` est archivé). Sous Windows, la chaîne scip-java ne s'exécute pas sous le bac à sable AppContainer (diagnostic du 2026-10-17 : le conteneur ne peut pas lister la racine du volume) : scip-java y est `BLOCKED` avec un diagnostic qui nomme le runtime Docker, et la ligne d'erreur d'une indexation en échec nomme le run, sa phase et l'emplacement de la cause ([ADR 0060](../../architecture/adr/SEC-0060-scip-java-windows-natif-bloque-indexation-java-par-docker.md), accepté : l'indexation Java d'un poste Windows passe par le runtime Docker). Les quatre CVE du client HTTP embarqué par Testcontainers (portée test seulement, AUD-DEP-14) sont un risque accepté jusqu'au 2027-01-15 (registre R-27) : aucune version corrective de docker-java n'existe, et elles restent visibles dans le rapport de Dependency-Check des tests. La veille planifiée du catalogue d'outils tourne depuis `main` (premier passage le 2026-10-31, run 41210062079 : aucun composant touché ni hors support, une dérogation datée sur le classpath de scip-java 0.13.2, neuf composants non couverts). Reste à faire : le retrait de l'installation de Java 24 dans les deux workflows de qualification A → B. Détail et preuves : [`docs/project/audits/2026-10-10/SUIVI.md`](../audits/2026-10-10/SUIVI.md).

## Audit 2026-10-10 — sprint 4 (2026-10-15)

Les huit changements du sprint 4 (« chemin de requête : ne plus relire le snapshot », neuf constats) sont **fusionnés dans `develop`** (PR #442, #444, #445, #447, #448, #456, #457, #458) et **archivés** (les deux derniers le 2026-10-15, après leur mesure sur un index réel) :

- **Cache des vues de requête** : les chaînes sont dédoublonnées au décodage, ce qui divise par deux le tas retenu par une vue ; le poids estimé d'une vue passe à deux fois sa taille persistée, de sorte qu'un gros projet tient dans le cache au lieu d'être relu à chaque requête. Un snapshot est décodé et authentifié en un seul parcours ; rien de décodé n'est publié si l'empreinte diffère.
- **Requête hybride** : une requête prête lit l'identité du snapshot actif et les métadonnées de l'index sans charger les vecteurs ni calculer la taille de l'index ; la garde `SEMANTIC_INDEX_CHANGED_DURING_QUERY` est conservée.
- **Statut et réconciliation** (`lire-l-identifiant-du-snapshot-a-la-reconciliation`, PR #460) : une lecture de statut (`minos_index_status`, `project inspect`) et une réparation de l'état comparent l'identifiant du snapshot actif par `activeSnapshotId`, sans charger le snapshot (0 chargement complet au lieu de 2, et de 5 pour une réparation) ; il en va de même pour la liaison hébergée et la reprise d'un run promu. Le record `Reconciliation` et les méthodes `reconcile`, `observe`, `observeStatus` sont dépréciés et conservés.
- **Index sémantique** : une indexation sans changement n'écrit rien ; sinon, les vecteurs des documents inchangés sont réutilisés et seuls les documents modifiés sont recalculés et envoyés. La construction des documents lit chaque fichier source une fois au lieu d'une fois par symbole.
- **Impact et graphe de programme** : l'index des arêtes entrantes et l'adjacence du graphe sont construits une fois par vue ou par graphe en cache, avec des rapports identiques ; une requête d'architecture avec module ne découvre le projet qu'une fois (MCP et CLI).
- **Graphe de programme Java** : sans empreinte exacte des sources, la clé de cache est hachée une fois puis réutilisée cinq secondes ; la limitation `PROGRAM_GRAPH_REFLECTS_DISK_AS_OF_AT_MOST_5_SECONDS_AGO_NOT_INDEXED_STATE` le déclare.
- **PostgreSQL** : une connexion du pool n'est validée qu'à l'emprunt après 30 s de repos ; une coupure sur la première instruction est rejouée une fois sur une connexion neuve, jamais après une écriture.

Mesures sur un index réel de ce dépôt (2026-10-15, produit sous Linux) : sur le code de production du reactor (snapshot de 114 Mo), une seconde lecture de la même vue passe de 2 chargements complets et environ 0,9 s à 1 chargement et environ 0,5 ms ; sur un index sémantique réel de 25 210 documents (`embeddinggemma`, PostgreSQL), synchroniser après une ligne modifiée passe de 10,4–10,9 s à 3,2–4,2 s et de 117–218 Mo à 33–55 Mo de journal, et une synchronisation sans changement de ≈ 11 s à ≈ 50 ms. Limites relevées par ces mesures : le reactor complet, tests compris, n'est pas indexable (snapshot de 300 Mo au-delà de la limite de 256 Mio) ; l'indexation de ce dépôt ne retenait que `minos-app` (build de `minos-app` dans le `target/` racine, qui effaçait les fichiers intermédiaires de scip-java) : scip-java écrit désormais ces fichiers dans le répertoire du run et les 14 modules sont indexés ; le code de production complet dépasse le budget de vecteurs de la synchronisation sémantique. Détail et preuves : [`docs/project/audits/2026-10-10/SUIVI.md`](../audits/2026-10-10/SUIVI.md).

## Audit 2026-10-10 — sprint 5 (2026-10-16, preuves de release en attente)

Les cinq changements du sprint 5 (« chaîne de release et d'approvisionnement ») sont **fusionnés dans `develop`** (PR #467, #468, #469, #470, #472) :

- **Workflows GitHub** : aucun checkout ne garde le jeton Git (`persist-credentials: false`), chaque job a un délai ; le code exécuté connecté à Docker Hub est limité à une liste fermée de deux jobs de montée de version ; les autres, jobs de test compris, tirent leurs images épinglées puis se déconnectent, et les tests refusent toute image hors de la liste. Le jeton Docker Hub est en lecture seule sur les dépôts publics (vérifié le 2026-10-28). `check-workflow-pins.py` le garde, avec son auto-test.
- **Plugin IntelliJ** : ses dépendances sont verrouillées (verrous Gradle stricts, sommes SHA-256 vérifiées) et il est construit par son wrapper Gradle, y compris en release. Le workflow de release attache le zip du plugin à la release ; v1.1.0 et v1.2.0 n'en ont pas et ne seront pas rattrapées.
- **Release Windows** : les artefacts reçoivent une attestation de provenance GitHub. Les exécutables **ne sont pas signés** (risque accepté R-28 : aucun service de signature accessible au mainteneur ; révision au plus tard le 2027-10-24) ; le manifeste et le texte de la release le disent, avec la commande `gh attestation verify` ; quand la signature est exigée (variable `MINOS_REQUIRE_SIGNED_RELEASE`), le workflow échoue sur tout exécutable publié sans signature Authenticode valide et horodatée.
- **Build Maven reproductible** : deux constructions du même commit donnent le même jar ombré, sous Linux d'une machine à l'autre ; toutes les versions de plugins sont épinglées. Un jar construit sous Windows diffère d'un jar Linux par les fins de ligne de 20 fichiers texte (aucune classe) : la reproductibilité vaut par plateforme.

Reste à faire : relever, au prochain candidat de release, la construction du plugin par le wrapper, le zip du plugin attaché et l'attestation. Détail et preuves : [`docs/project/audits/2026-10-10/SUIVI.md`](../audits/2026-10-10/SUIVI.md).

## Audit 2026-10-10 — sprint 6 (2026-10-16)

Les six changements du sprint 6 (« couverture réelle et plugin IntelliJ ») sont **fusionnés dans `develop`** (PR #471, #473, #474, #475, #476, #477) :

- **Couverture bornée** : le gate JaCoCo juge le scope `global` avec un plancher par système (dernière mesure en CI : lignes 0,866 sous Linux, 0,834 sous Windows) et trois scopes du cœur de plus (codec et état du stockage, primitives d'E/S confinées, rétention des runs) ; les classes de propriété de processus ont leur plancher de classe sur leur système.
- **Tests de concurrence** : les 14 tests qui prouvaient une attente par un `sleep` observent désormais le concurrent en attente (verrou consultatif PostgreSQL vu dans `pg_locks`, ou fil parqué dans la classe qui porte le verrou) ; tous rejoués 50 fois sous Windows, les cinq tests de verrou de fichier aussi sous Linux. Les tests d'idempotence relisent l'état après le second appel.
- **Test scip-java hermétique** : aucun test de `minos-provider-scip` ne lit plus le `JAVA_HOME` de l'hôte ; surefire le retire.
- **Plugin IntelliJ** : couverture mesurée par JaCoCo et bornée dans son build (0,33 en lignes et en branches ; mesure CI Linux 0,357 / 0,359). SonarCloud n'analyse pas le plugin. Le lanceur Job Object du plugin est gardé en parité avec celui du moteur par un gate, trois différences étant assumées et listées.
- **Mutation** : le document d'audit porte un seul état PIT par module, et chaque module ainsi que le plugin a désormais un rapport PIT produit par `code-audit.yml` sous Ubuntu (16 octobre) ; les classes propres à Windows de `minos-runtime-local` restent non analysées. Aucun plancher de mutation n'est fixé.

Reste hors de ce sprint : aligner la construction de ligne de commande du moteur sur le mode strict du plugin (`raw-cmd-mode`). Détail et preuves : [`docs/project/audits/2026-10-10/SUIVI.md`](../audits/2026-10-10/SUIVI.md).

## Audit 2026-10-10 — sprint 7 (2026-10-17)

Cinq des six changements du sprint 7 (« sorties publiques : messages, MCP, rendus ») sont **fusionnés dans `develop` et archivés** (PR #485, #487, #488) ; le sixième, `reserver-minos-ollama-au-runtime-docker` (PR #486), est fusionné et reste actif jusqu'à sa preuve manuelle :

- **Chemins absolus dans les erreurs** : `PublicErrorMessages` retire les séquences ANSI complètes avant d'analyser, de sorte qu'un chemin absolu masqué par une séquence est détecté ; les refus de cible des primitives d'E/S privées nomment l'entrée, jamais le chemin. Limite : un chemin POSIX accolé à un identifiant, sans séquence de contrôle, reste non détecté. Une vingtaine de messages de même famille restent à nommer (liste dans le suivi).
- **MCP** : les arguments hors schéma sont refusés par `MinosMcpTools`, nom borné, jamais la valeur ; `minos_project_structure` ne rend plus que le nom du répertoire racine dans `rootPath` (la CLI lue par le plugin garde le chemin absolu) ; `authorEmail` vaut `[masked]` par défaut, l'adresse réelle n'étant rendue que sur `--include-author-emails` (CLI `git-activity`) ou par le composant `includeAuthorEmails` de la requête Java — **changement de la sortie par défaut** de ces deux surfaces.
- **Graphe de programme** : les règles de sécurité Java viennent du dépôt analysé, qui n'est pas de confiance ; la réponse le dit par la limitation `SECURITY_RULES_SUPPLIED_BY_ANALYZED_REPOSITORY`. Les règles d'opérateur hors du dépôt ne sont pas faites.
- **Rendu Mermaid** : les chevrons des libellés sont échappés (`&lt;`, `&gt;`) et la séquence `%%{` est neutralisée dans les lignes de commentaire ; forme vérifiée sur Mermaid 11.4.1.
- **`minos-ollama`** : le nom réservé n'est joignable que dans le runtime Docker ; hors Docker, chaque `embed` échoue avant toute résolution de nom (limite `OLLAMA_MANAGED_SERVICE_UNREACHABLE_OUTSIDE_DOCKER`), et l'ouverture réussit. Le corps d'erreur HTTP est borné.

Reste : la preuve sur une installation Docker réelle du refus et de l'absence de requête DNS depuis un poste natif (tâche manuelle 4.2). Les tests Gradle du plugin n'ont pas été rejoués sur le poste, seulement en CI. Détail et preuves : [`docs/project/audits/2026-10-10/SUIVI.md`](../audits/2026-10-10/SUIVI.md).

## Audit 2026-10-10 — sprint 8 (2026-10-18)

Les cinq changements du sprint 8 (« confinement et E/S privées ») sont **fusionnés dans `develop` et archivés** (PR #490 à #494) :

- **Garde d'E/S privées** : neuf API de `Files` (`newOutputStream`, `newBufferedWriter`, `newByteChannel`, `createFile`, `createDirectory`, `createTempFile`, `createTempDirectory`, `copy`, `move`) et la redirection de la sortie d'un processus vers un fichier rejoignent les règles de `check-private-io.py` (20 règles). Les 44 occurrences existantes sont inscrites dans la liste de tolérance, chacune avec son lot de migration ; **la migration elle-même n'est pas faite**. `PrivateLocalStorage` porte ses appels par entrées de liste.
- **Windows, refus conditionnels** : avant de réécrire une DACL, `PrivateLocalStorage` la lit (`icacls /save`) et **refuse**, sans rien modifier, si elle porte un refus conditionnel (`XD`) ou si elle est illisible ; une autorisation conditionnelle héritée reste retirée. Les octrois Java du bac à sable AppContainer passent par une primitive unique, additive et bornée.
- **Exécution de provider** : `ProcessIndexerExecutor.execute` refuse de lancer un provider sans frontière de propriété ni bac à sable ; le constructeur « propriété seule » de `StrongProcessOwnershipIndexerExecutor` n'est plus public (ADR 0038, « Mise en œuvre »).
- **Ouverture confinée** : `ConfinedFileOpener` ne garde plus de crochet de test statique ; l'étape est un paramètre de surcharges de paquet.
- **Suivi des descendants** : sondage toutes les 10 ms pendant la première seconde, puis toutes les 100 ms, dans le runtime local et le plugin IntelliJ. Gain mesuré : 4,6 fois moins d'énumérations sous Linux (conteneur), 2,9 fois sous Windows, l'énumération coûtant 14 à 34 ms.

Reste : deux tâches manuelles de la migration des écritures brutes (indexation réelle sous AppContainer et lecture de l'ACL des artefacts) et le nombre d'objets durcis pendant une indexation réelle. Détail et preuves : [`docs/project/audits/2026-10-10/SUIVI.md`](../audits/2026-10-10/SUIVI.md).

## Audit 2026-10-10 — sprint 9 (2026-10-22)

Les six changements du sprint 9 (« une seule orchestration d'indexation ») sont **fusionnés dans `develop` et archivés** (PR #526 à #531, puis #532 et la PR de finalisation) :

- **Orchestration unique** : `ProjectIndexingUseCase` (`minos-application`, `com.minos.application.indexing`) porte la préparation, l'exécution sous bail, la reprise d'un run promu, l'empreinte, la synchronisation sémantique et la rétention ; la CLI ne fait que projeter son résultat. `IncrementalIndexingCoordinator` et `IncrementalIndexingResult` sont supprimés, leurs tests reportés sur le cas d'usage (ADR 0057, « Mise en œuvre »). Une empreinte de référence illisible donne le diagnostic `active fingerprint baseline unreadable; a full indexing was required` ; l'échec de publication de l'empreinte reste une erreur de `minos index`. Le diagnostic d'échec du rafraîchissement sémantique ne recopie pas un message qui peut nommer un chemin.
- **Hachage de l'arbre source** : le scope racine réutilise l'empreinte de référence (2 hachages complets au lieu de 3). L'orchestration transmet son `ProjectFingerprintService` à l'exécuteur de run et à `IndexingResumePlanner` : le point de contrôle et la vérification de reprise utilisent le même service, et l'empreinte n'est réutilisée que si elle a été capturée par cette même instance. Pas de raccourci par taille et date. Le plugin IntelliJ compte une preuve sans emplacement comme les autres (`ArchitectureGraphPanelTest`).
- **Port d'état d'index** : `IndexStateStoreException` (non vérifiée) est le seul type d'échec des stores fichier et PostgreSQL, bail compris ; les appelants n'attrapent que lui ; les messages du store fichier ne contiennent pas de chemin absolu.
- **Découverte des fournisseurs** : backends de stockage et fournisseur d'identité de chemin chargés par le chargeur du port, doublons refusés (ADR 0042 § 9) ; règle **A16** du garde de frontières (tout `ServiceLoader.load` de production a deux arguments).
- **Types sans appelant** : `ProjectFingerprintSnapshotAlignmentService`, `IndexerProviderRegistry` et `InterproceduralFlowService` supprimés, `SemanticSearchEvaluator` déplacé dans les tests de `minos-bootstrap` ; règle **A15** (liste à cliquet `UNREFERENCED_PRODUCTION_TYPES`, 11 entrées avec leur raison).
- **Preuves des tests liés** : au plus 5 emplacements par type de preuve et par relation, puis une preuve de synthèse qui compte les emplacements omis ; confiances, ordre et ensemble des tests liés inchangés. Mesuré sur ce dépôt : 42 054 → 37 394 preuves, maximum par relation 80 → 9.

Reste : l'injection du fournisseur d'identité de chemin depuis la racine de composition (reportée par décision). Constat hors sprint : le snapshot de ce dépôt (306,7 Mo) dépasse la limite de persistance de 256 Mio (AUD-PERF-03). Détail et preuves : [`docs/project/audits/2026-10-10/SUIVI.md`](../audits/2026-10-10/SUIVI.md).

## Audit 2026-10-10 — sprint 10 (2026-10-22, clos le 2026-10-24)

Les deux changements du sprint 10 (« frontières d'architecture ») sont **fusionnés dans `develop` et archivés** ([PR #534](https://github.com/FTurleque/minos-code-intelligence/pull/534)). Ils **figent la dette sans déplacer de classe** et consignent les décisions du propriétaire :

- **Cliquets** (`figer-la-dette-de-frontieres-par-des-cliquets`) : règles **A11** (types de `minos-engine` nommés par chaque surface : 39 entrées, dont 15 pour la CLI ; aucune surface ne nomme plus `com.minos.orchestration` ni `com.minos.remote`, l'indexation distante étant le cas d'usage `RemoteIndexingUseCase` de `minos-application`), **A12** (aucun processus lancé dans le domaine, le moteur et l'application ; table vide, le mécanisme d'ACL Windows passant par le port `PlatformAccessControl` de `minos-runtime-local`), **A13** (14 types d'un adaptateur nommés par un autre) et **A14** (37 racines de packages, aucune nouvelle) de `check-module-boundaries.py` ; chaque table refuse un nom hors liste et une entrée devenue inutile. Le document généré `docs/architecture/diagrams/module-dependencies.md` montre les quatre arêtes d'exécution par service, dont `minos-cli` → `minos-app`.
- **Décisions** (`trancher-les-decisions-d-architecture-du-sprint-10`) : [ADR 0062](../../architecture/adr/archive/0062-confirmer-l-adr-0044-et-geler-les-racines-de-packages.md) (port d'ACL de plateforme conçu avec AUD-SEC-06, `hosted` et `minos-app` inchangés, aucun nouveau package hors des racines de son module) ; ordre de l'ADR 0047 confirmé (option A déjà livrée).

- **Port de lecture des snapshots** (`exposer-un-port-de-lecture-de-snapshot`, SH-04, AUD-ARC-07, 2026-10-23) : les cas d'usage de lecture lisent le snapshot actif par `SnapshotReadView` et le port `CodeKnowledgeReader`, sans accès à l'index mémoire ni à ses écritures (PR #547) ; `SnapshotQueryView` et `loadActiveQueryView` sont dépréciés et conservés ; la règle **A18** de `check-module-boundaries.py` le garde. Aucune capacité nouvelle, aucune sortie CLI, MCP ou API publique ne change ; seule rupture : le type de paramètre de quatre classes internes (`docs/user/java-api.md` § Ruptures).
- **Surfaces et cas d'usage d'indexation** (`faire-consommer-l-indexation-aux-surfaces`, lot 1 d'AUD-ARC-01, PR #548, 2026-10-24) : la CLI, l'API Java et le MCP passent par `ProjectIndexingUseCase`, `RemoteIndexingUseCase`, `ProjectInspectionService` et `ProviderPlatformService` de `minos-application` pour l'indexation locale et distante, la reprise et les runtimes de providers ; 21 entrées retirées de la règle A11. Aucune sortie CLI, MCP ou API publique ne change.

Le workflow `code-audit` en mode SpotBugs bloquant (2026-10-24) est vert sur la base figée : sur les 22 alertes antérieures au sprint (20 dues aux nouveaux détecteurs de SpotBugs 4.10.5, 2 au sprint 9), 15 sont corrigées et 7 figées avec une raison écrite dans `docs/developer/quality/code-audit.md`.

Les chantiers de fond sont livrés par la résorption de la dette figée (section suivante). Statuts au 2026-10-28 : AUD-ARC-01, -07, -08 et -11 fermés avec réserve, AUD-ARC-12 clos par décision documentée, AUD-ARC-05 partiel (la part de code concret du moteur n'a ni cliquet ni cible). Le coût de l'index mémoire par vue d'AUD-ARC-07 reste hors périmètre (ADR 0047, options B et C). Détail et preuves : [`docs/project/audits/2026-10-10/SUIVI.md`](../audits/2026-10-10/SUIVI.md).

## Audit 2026-10-10 — sprint 11 (2026-10-24)

Les six changements du sprint 11 (« hygiène de fond ») sont **fusionnés dans `develop`** (PR #552 à #558) ; cinq sont archivés. Aucune sortie CLI, API Java, MCP ni du plugin ne change.

- **Méthodes découpées** : `ImpactAnalysisService.analyze` et `ScipIngestionAdapter.ingest` en étapes nommées ; `IndexingRunExecutor.execute` prend deux records (`RunRequest`, `Collaborators`) au lieu de 16 paramètres.
- **Recherche de symboles** : le rang de correspondance est calculé une fois par candidat, aucun sans texte ; ordre des résultats inchangé.
- **Caches** : un cache partagé `WeightedLruCache` (`com.minos.store.cache`) porte l'éviction de sept caches ; les helpers d'addition et de multiplication bornées sont nommés d'après leur comportement (`saturatingAdd`, `addOrThrow`, `saturatingMultiply`) et gardés par `check-bounded-helpers.py`.
- **Jalons** : aucun numéro de jalon dans un nom de production (gate `check-milestone-identifiers.py`), plugin compris ; les variables du runner scip-java Windows s'appellent `MINOS_SCIP_JAVA_*`.
- **Scripts jumeaux** : les fonctions PowerShell recopiées entre scripts livrés restent identiques (gate `check-twin-script-functions.py`).
- **Langue des commentaires** : anglais pour tout le code, plugin compris ; aucun fichier ne mêle deux langues, et les fichiers hors langue et ceux qui citent un jalon sont tolérés par des cliquets décroissants (gate `check-comment-language.py`).

Reste : la preuve Windows réelle du runner scip-java renommé (`retirer-les-jalons-des-identifiants`, tâche 4.2), sans laquelle ce changement n'est pas archivé. Détail et preuves : [`docs/project/audits/2026-10-10/SUIVI.md`](../audits/2026-10-10/SUIVI.md).

## Dette d'architecture figée (2026-10-25, close le 2026-10-28)

Les quatre phases du chantier (AUD-ARC-01, -05, -08, -12) sont **fusionnées dans `develop` et archivées**, sans changement de sortie CLI, API Java, MCP ni du plugin. Compteurs de `check-module-boundaries.py` à la clôture :

- **A11** : 9 types de `minos-engine` nommés par les surfaces (CLI 5, API 3, MCP 1, NEXUS 0), chacun depuis un nombre de fichiers listé, aussi vérifié sur le bytecode.
- **A12** : vide ; `minos-domain`, `minos-engine` et `minos-application` ne lancent aucun processus, l'ACL Windows passe par le port `PlatformAccessControl` de `minos-runtime-local`.
- **A13** : un seul couple, `minos-provider-scip` → `minos-runtime-local` (9 types, 28 fichiers), justifié par écrit ; le provider SCIP ne nomme plus le stockage.
- **Stockage** : un seul module `minos-storage` (reactor à 13 modules), codec commun dans `com.minos.storage.codec`, backends isolés par des règles de packages.

Reste au plan du chantier hexagonal : SH-12. Détail et preuves : [`docs/project/audits/2026-10-10/SUIVI.md`](../audits/2026-10-10/SUIVI.md).

## État produit

- **C0 → M30** : terminés et intégrés.
- **M29 issue #107** : **CLOSED** ; **M29 PR #108** intégrée.
- **M30 PR #110** et **M30 promotion PR #111** intégrées.
- **hardening PR #113** intégré ; **M28 Windows CI PR #117** intégré.
- **#98 sandbox OS réelle** : primitives **implémentées** sur Linux (bubblewrap/cgroup v2) et Windows (AppContainer/Job Object) ; qualification code non fiable **refusée** par décision (ADR 0041, 2026-09-26) — le quota d'écriture reste supervisé, pas appliqué par l'OS ; `remote index` fail-closed sur tous les OS, refus journalisé et exposé par `minos doctor` (audit 2026-09, constat A1).
- **PR #227** : provider egress, provenance `CommandLocator`, reparse private storage et contrat de fallback confinement **intégrés**.
- **#224–#248** : campagne de confinement provider/filesystem, provenance, egress, installateur et Windows non-admin intégrée.
- **#258/#260** : audit du 28 août 2026 — politique sécurité, Dependabot, CODEOWNERS futur, toolchain, couverture, confinement secrets/fingerprints, simplification des qualifications historiques et hardening exact-head intégrés dans la ligne `develop` auditée.
- **Audit du 10 octobre 2026, sprints 1 à 9** : constats corrigés et intégrés dans `develop` (voir les sections dédiées ci-dessus).
- Ligne de développement : **1.3.0-SNAPSHOT**.

## Release 1.0.1

La release **MINOS v1.0.1** a été publiée le **9 août 2026** après validation utilisateur réelle du setup Windows.

```text
v1.0.1 → f762025d66e33c40324c811079f1527d122f90f9
```

La release **v1.0.1 est PUBLIÉE et immuable**.

- URL : https://github.com/FTurleque/minos-code-intelligence/releases/tag/v1.0.1
- publication : **10 assets**, soit **5 paires** artefact/checksum ;
- workflow de publication : `31288322126` ;
- setup Windows, distribution et plugin IntelliJ restent soumis aux gates OSV, provenance et **Plugin Verifier** applicables.

## Release 1.1.0

La release **MINOS v1.1.0** a été publiée le **27 août 2026**.

```text
v1.1.0 → b2ba3ac9b9dbb852dab712ee33bc05e41e03e879
```

La release **v1.1.0 est PUBLIÉE et immuable**.

- URL : https://github.com/FTurleque/minos-code-intelligence/releases/tag/v1.1.0
- publication : **8 assets**, soit **4 paires** artefact/checksum ;
- workflow de publication : `33116192634` ;
- setup Windows, distribution et plugin IntelliJ restent soumis aux gates OSV, provenance et **Plugin Verifier** applicables.

## Release 1.2.0

La release **MINOS v1.2.0** a été publiée le **31 août 2026**.

```text
v1.2.0 → 730b760020b8ed9d69666c32a35fcff05bf21bdb
```

La release **v1.2.0 est PUBLIÉE et immuable**.

- URL : https://github.com/FTurleque/minos-code-intelligence/releases/tag/v1.2.0
- publication : **8 assets**, soit **4 paires** artefact/checksum ;
- workflow de publication : `33339951562` ;
- couvre la remédiation d'audit `develop` (fuite de `RemoteMaterialization` sur échec d'acquisition du lease de réindexation distante, résolution PATH POSIX acceptant un fichier non exécutable, gates JaCoCo d'orchestration critique) et le rafraîchissement Dependabot (jackson, mcp-sdk, testcontainers, jgit, junit-jupiter, actions GitHub) promus vers `main` par les PR #259, #284 ;
- setup Windows, distribution et plugin IntelliJ restent soumis aux gates OSV, provenance et **Plugin Verifier** applicables.

Aucune release 1.3.0 n'est publiée à ce jour.

## Répartition autoritative des gates CI

La qualification courante est volontairement séparée entre gates produit actuels et replays historiques.

### PR Validation

`.github/workflows/pr-ci.yml` est le pipeline de PR unique (constat C1 de l'audit 2026-09, voir [`docs/project/audits/archive/2026-09/CI-HYGIENE-SUIVI.md`](../audits/archive/2026-09/CI-HYGIENE-SUIVI.md)), avec trois jobs sans dépendance entre eux, qui tournent en parallèle :

- **`vulnerability-scan`** : gate de vulnérabilités des dépendances (OSV-Scanner, workflow réutilisable épinglé par SHA) ;
- **`invariants`** : un unique **gate statique ciblé Ubuntu**, sans Maven ni Java — épinglage supply-chain des workflows, frontières de modules, cohérence documentaire courante, `product-facts`, garde-fou de non-réaccumulation d'artefacts de jalon, invariants MND/MNE/post-MNE (y compris les neuf gates de jalon actifs M21–M28), invariants post-#228, invariants d'audit-remédiation v2/P0-P2/MINOS-01, provenance Inno Setup, tests unitaires du vérificateur Docker upgrade — chaque contrôle une seule fois, quel que soit l'OS de `verify` ;
- **`verify`** (Ubuntu 24.04 et Windows Server 2022), **seul build Maven d'une PR** (les workflows M19 et M20, qui en répétaient une copie filtrée par chemins, sont retirés : constat C2, gardé par `scripts/quality/check-single-execution.py`) : `clean verify` Maven complet, PostgreSQL obligatoire sur Linux, tests sandbox/cgroup/AppContainer applicables, seuils JaCoCo ciblés Linux/Windows, invariant d'ascendance (`main` doit être ancêtre du candidat, afin d'empêcher une nouvelle divergence silencieuse `main/develop`).

**Checks exigés.** Le ruleset exige `Verify (ubuntu-24.04)`, `Verify (windows-2022)`, `Dependency vulnerability gate / osv-scan`, `Static invariants (single run)`, `Gitleaks`, `IntelliJ plugin (gate)` et `SonarCloud Code Analysis` ; la liste est déclarée dans `.github/required-checks.json` et vérifiée par `scripts/quality/check-ci-wiring.py` (jobs résolus, déclencheurs sans filtre de chemins, auto-tests de gates câblés dans `invariants`). Les deux jobs `verify` passent `-Dminos.sandbox.tests.required=true` : un confinement OS indisponible est un échec, pas un saut. L'heuristique `check-partial-result-consumers.py`, autrefois consultative, est bloquante depuis que `invariants` est exigé.

Les workflows `mnd-remediation.yml`, `mne-remediation.yml`, `post-mne-remediation.yml` et `post-228-hardening.yml`, qui dupliquaient chacun leur propre checkout et leur propre vérification d'épinglage, ont été retirés : leurs invariants vivent désormais dans le job `invariants` ci-dessus.

Les preuves historiques Post-#228 restent explicitement conservées : candidat qualifié `1a551ff72f95db4e14e8a9597d897491b9c1589a`, puis merge `a042e97ac5e3e2ab7207fa603d85563ea1f71712`. Ces SHA décrivent l'intégration historique #228 ; ils ne changent pas la répartition actuelle des responsabilités CI.

### Qualifications historiques

Les replays M15/M28 historiques sont isolés dans `.github/workflows/historical-qualification.yml` et ne font pas partie du chemin PR courant par défaut.

### IntelliJ

`.github/workflows/intellij-plugin.yml` qualifie le plugin séparément : Java 21, Gradle 9.8.2, IntelliJ Platform 2026.1, build/structure/Plugin Verifier sous Linux et tests ownership sous Windows. Le plugin reste un client externe sans dépendance d'implémentation `com.minos:*`. Le workflow s'exécute sur **toute** PR (aucun filtre de chemins au niveau du workflow) : un job `Plugin scope` calcule si le plugin est concerné (`scripts/ci/plugin-gate.py` ; la portée inclut `minos-cli`, `minos-integration-git`, les rendus JSON de `minos-application`, `minos-domain` et les goldens de caractérisation), les deux jobs du plugin ne tournent que dans ce cas, et `IntelliJ plugin (gate)`, toujours exécuté, est le seul check exigé.

### Docker

`.github/workflows/docker-release-validation.yml` qualifie à chaque candidat l'image provider-complete Linux/amd64 exacte.

La qualification réelle **Docker MCP A → B** tourne sur un runner **GitHub-hosted `ubuntu-24.04`** standard — aucun runner auto-hébergé, aucune machine personnelle, aucun repository d'infrastructure privé (le dépôt est public). `scripts/ci/qualify-docker-upgrade.ps1` (pwsh, portable) construit deux commits/JAR distincts, chacun avec son propre Dockerfile/Compose, pilote le cœur portable `docker/scripts/mcp-lifecycle.ps1` (extrait de `prod-mcp-release.ps1`, qui reste l'interface produit Windows inchangée pour les utilisateurs), les providers/Compose réels, indexe un projet fixture, exécute le handshake MCP avant/après upgrade, vérifie la persistance du `MINOS_HOME` et s'assure qu'un candidat suivant invalide ne remplace pas le candidat B qualifié.

La promotion `develop → main` déclenche cette qualification automatiquement via `.github/workflows/release-promotion-gate.yml` (job `docker-upgrade-qualification` puis `docker-upgrade-evidence` en dépendance, jamais l'inverse). `.github/workflows/docker-upgrade-qualification.yml` reste disponible en `workflow_dispatch` pour un usage manuel ponctuel, mais ne déclenche plus rien automatiquement lui-même — cela évite toute double exécution pour un même candidat. `scripts/release/check-docker-upgrade-evidence.py` télécharge et valide le manifeste `qualification.json` de la preuve (candidat exact, résultat `PASS`, et éventuellement le SHA précédent attendu), pas seulement le nom de l'artifact.

### SonarCloud

`SonarCloud Code Analysis` est un check **exigé**. Depuis le 2026-10-09 l'analyse est **pilotée par la CI** : la variable de dépôt `SONAR_CI_ANALYSIS_ENABLED` vaut `true` et l'étape « SonarCloud CI-based analysis » de `pr-ci.yml` s'exécute avec succès. Depuis le 2026-10-12 cette étape est un job à part, `sonar`, qui attend les deux jobs `Verify` et importe **les rapports JaCoCo de chaque module, Linux et Windows** : auparavant elle tournait dans le job `Verify` Ubuntu avec les seuls rapports Linux, et le code que seul Windows exécute (AppContainer, Job Object) comptait comme non couvert. Le scanner lit la couverture module par module, depuis un chemin relatif au module (`target/site/jacoco/jacoco.xml` pour Linux, `target/site/jacoco-windows/jacoco.xml` pour Windows) : le rapport agrégé de la racine n'est rattaché à aucun fichier source. Une première version du job (PR #421) ne publiait que cet agrégat et toute ligne neuve comptait comme non couverte ; le job échoue maintenant si le scanner importe moins de rapports qu'il n'y a de modules qui en ont un. Le job recompile le candidat sans rejouer de test. Si un `Verify` échoue sans laisser de rapport, l'analyse tourne avec celui qui existe ; le rapport Linux est exigé. GitHub ne donne pas les secrets du dépôt aux PR Dependabot : `SONAR_TOKEN` doit aussi figurer parmi les **secrets Dependabot**, faute de quoi le job `sonar` échoue (message explicite) et le check exigé bloque ces PR. Le Quality Gate affiche donc une **couverture réelle sur le nouveau code** (seuil 80 %), et non plus le `0.0 %` de l'Automatic Analysis.

Observation du 2026-10-10 (PR #398) : la couverture est mesurée **module par module**. Une classe déplacée est comptée comme du code neuf, et une classe testée depuis un autre module (`ProjectResolver`, testée depuis `minos-bootstrap`) apparaît non couverte dans le sien : le gate a échoué à 69,1 % jusqu'à ce que des tests unitaires soient ajoutés dans `minos-application` et `minos-engine` (80,2 %). Pour toute PR qui déplace des classes, prévoir des tests dans le module qui les porte.

La **gate JaCoCo ciblée** de `PR Validation` (`scripts/quality/check-jacoco.py`, seuils par composant) reste l'autorité de couverture par composant ; le Quality Gate SonarCloud porte la couverture du nouveau code, les bugs, vulnérabilités, hotspots et code smells de maintenabilité.

## Garanties de stockage et secrets

- les chemins de secrets relatifs sont confinés physiquement à `MINOS_HOME` ; les chemins absolus restent une option opérateur explicite pour les secret stores montés ;
- les fichiers de secret sont lus avec un plafond d'octets et un décodeur UTF-8 strict : les séquences mal formées sont refusées, jamais remplacées silencieusement ;
- les snapshots structurés v1/v2/v3 conservent leur plafond persistant de **256 MiB**, désormais imposé pendant l'I/O par flux bornés en plus des contrôles de taille ;
- les tailles/cardinalités/chaînes des formats persistés restent bornées et les données PostgreSQL utilisent un scratch privé ;
- les clés hosted dérivées utilisent HMAC-SHA-256 ; les buffers temporaires maître et dérivé sont nettoyés après construction de la clé finale ;
- hosted control plane : AES-256-GCM avec AAD, limites de taille et écriture atomique durable.

## Supply-chain et toolchains

- Maven Wrapper : Maven 3.10.0 avec checksum SHA-256 (`.mvn/wrapper/maven-wrapper.properties`), accepté par la plage `[3.9,4.0)` de l'enforcer ; l'image Docker et les outils embarqués (`embedded-tools.json`) restent en Maven 3.9.16 ;
- cœur MINOS : Java 25 / Maven 3.9.x ;
- plugin IntelliJ : Java 21 / Gradle 9.8.2 / IntelliJ Platform 2026.1 ;
- Dependabot couvre Maven, GitHub Actions **et le build Gradle `minos-intellij`** ;
- les actions GitHub sont épinglées sur des SHA immuables ;
- `docker/Dockerfile.mcp.release` conserve ses images/toolchains/checksums de release reproductibles ;
- le Dockerfile MCP local utilise lui aussi une base Temurin 25 JRE par digest immuable et n'est plus dépendant d'un tag flottant.

## Garanties structurantes

- snapshots structurés autoritatifs et promotions fail-closed ;
- API/CLI/MCP/NEXUS/IntelliJ au-dessus du métier sans autorité concurrente ;
- Git distant : HTTPS, host/ref/SHA/path validés et frontières de confiance explicites ;
- provider egress `DENY` par défaut ;
- providers locaux exécutés dans une copie éphémère bornée avec containment OS et job boundary agrégé ;
- worker hostile maintenu fail-closed lorsqu'un hard filesystem quota OS n'est pas disponible ;
- environnement provider allowlisté ;
- stockage local privé, symlink/junction/reparse refusés aux frontières sensibles ;
- PostgreSQL distant exige la politique TLS qualifiée ;
- indexation Windows non-admin et mutations ACL additives/ciblées ;
- mise à jour Docker MCP non interactive et fail-fast ;
- branches de promotion : `develop` doit contenir l'ascendance de `main` avant tout candidat, vérifié par CI ;
- aucun cycle de packages dans le code de production ni dans le plugin (règle A8 de `check-module-boundaries.py`, table `KNOWN_PACKAGE_CYCLES` vide) ;
- une qualification de confinement OS indisponible échoue dans la CI au lieu d'être sautée (`minos.sandbox.tests.required`).

## Qualification d'une nouvelle remédiation

Une correction n'est déclarée intégrée qu'après succès exact-head des workflows applicables sur son HEAD final. Aucun document ni workflow n'autorise un merge automatique vers `main` : la promotion reste une décision explicite.
