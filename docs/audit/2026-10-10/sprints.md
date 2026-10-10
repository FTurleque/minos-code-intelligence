# Ordre de correction — sprints

Le [plan d'action](plan-action.md) classe par urgence ; ce document classe par **ordre d'exécution**. Quand les deux divergent, l'ordre des sprints s'applique : une correction dont le prérequis n'est pas livré ne peut pas être vérifiée.

L'ordre a été vérifié par script (`check_sprints.py`, rejoué à chaque modification) : chaque constat actionnable est dans exactement un sprint, aucun constat Info n'est affecté, et aucun prérequis n'est dans un sprint postérieur à celui qui en dépend.

## Vue d'ensemble

| Sprint | Titre | Constats | Composition | Charge (j) |
|---|---|---|---|---|
| S1 | Remettre les dispositifs de mesure en marche | 9 | 3 Moyenne, 6 Faible | 3 – 11 |
| S2 | Failles de sécurité et observabilité | 7 | 1 Élevée, 3 Moyenne, 3 Faible | 2,5 – 9 |
| S3 | Socle d'exécution supporté | 7 | 1 Élevée, 2 Moyenne, 4 Faible | 6,75 – 20 |
| S4 | Chemin de requête : ne plus relire le snapshot | 9 | 1 Élevée, 5 Moyenne, 3 Faible | 3,75 – 13 |
| S5 | Chaîne de release et d'approvisionnement | 7 | 4 Moyenne, 3 Faible | 2,5 – 9 |
| S6 | Couverture réelle et plugin IntelliJ | 10 | 4 Moyenne, 6 Faible | 5,5 – 18 |
| S7 | Sorties publiques : messages, MCP, rendus | 7 | 7 Faible | 1,75 – 7 |
| S8 | Confinement et E/S privées | 5 | 1 Moyenne, 4 Faible | 2,75 – 9 |
| S9 | Une seule orchestration d'indexation | 6 | 5 Moyenne, 1 Faible | 4,5 – 14 |
| S10 | Frontières d'architecture | 6 | 4 Moyenne, 2 Faible | 14 – 38 |
| S11 | Hygiène de fond | 6 | 1 Moyenne, 5 Faible | 4,5 – 14 |
| | **Total** | **79** | | **51,5 – 162** |

La charge est une **enveloppe**, pas une estimation : somme des efforts (S = 0,25 à 1 j, M = 1 à 3 j, L = 3 à 8 j). Elle ne couvre ni la revue, ni les allers-retours de CI, ni les ADR et arbitrages écrits que demandent les chantiers de fond (sprints 3, 9 et 10).

## Chemin critique

- **AUD-TST-03** (S1, effort S) — Les tests de confinement Linux (bubblewrap, cgroup v2) et Windows (AppContainer) se sautent en silence : aucun interrupteur « requis » comme pour PostgreSQL. Débloque : AUD-DEP-01, AUD-PERF-08, AUD-SEC-06.
- **AUD-ARC-10** (S1, effort S) — E02 toujours ouvert : les 13 auto-tests du garde de frontières ne couvrent toujours aucune règle A2 et la reconnaissance des dépendances internes repose sur le littéral « com.minos ». Débloque : AUD-ARC-01, AUD-ARC-05, AUD-ARC-08.
- **AUD-ARC-02** (S9, effort M) — Deux orchestrations de l'indexation incrémentale coexistent : IncrementalIndexingCoordinator (moteur, public, 4 classes de test) n'a aucun appelant en production, la CLI réimplémente le flux. Débloque : AUD-ARC-01, AUD-PERF-04.
- **AUD-PERF-03** (S4, effort S) — Cache de vues de requête : poids estimé à 8 fois la taille persistée (1,4 mesuré), donc tout snapshot de plus de 64 Mio n'est jamais mis en cache et chaque requête MCP le relit et le décode. Débloque : AUD-ARC-07, AUD-PERF-11.
- **AUD-TST-07** (S1, effort S) — L'auto-test de check-jacoco.py n'est exécuté par aucun workflow, alors que celui de chaque autre gate l'est. Débloque : AUD-TST-01, AUD-TST-02.
- **AUD-PERF-01** (S4, effort M) — Recherche hybride avec index sémantique READY : 5 chargements du snapshot actif, 4 lectures de métadonnées et de taille, et un chargement complet de l'index vectoriel par requête (sans cache sous PostgreSQL). Débloque : AUD-PERF-06.

## Détail par sprint


### Sprint 1 — Remettre les dispositifs de mesure en marche

**Pourquoi ici.** Aucune correction ultérieure n'est vérifiable tant qu'un gate peut être rouge, un auto-test absent ou un test de confinement sauté sans rien bloquer. Ce sprint ne touche presque pas au code applicatif.

**Critère de sortie.** Chaque gate du job invariants et chaque auto-test de gate sont des checks exigés par le ruleset ; un test de confinement sauté fait échouer le job de son OS ; la garde de frontières refuse un cycle de packages et une arête interdite par l'ADR 0042 ; la documentation d'architecture courante est contrôlée contre les POM.

**Charge** : 3 à 11 jours.

| ID | Sévérité | Effort | Titre |
|---|---|---|---|
| AUD-ARC-03 | Moyenne | S | Correction incomplète de G16/G17 : arc42/05 donne des dépendances fausses pour 7 modules sur 14, dont 4 arêtes surface/adaptateur interdites par ADR 0042, et SYNTHESE.md maintient PostgreSQL → minos-application |
| AUD-DEP-09 | Moyenne | S | Les gates statiques, OSV, Gitleaks et le plugin IntelliJ ne sont toujours pas imposés par le ruleset |
| AUD-TST-03 | Moyenne | S | Les tests de confinement Linux (bubblewrap, cgroup v2) et Windows (AppContainer) se sautent en silence : aucun interrupteur « requis » comme pour PostgreSQL |
| AUD-ARC-06 | Faible | M | Les quatre cycles de packages relevés le 6/10 sont toujours là (15 packages) et aucune des deux gardes ne contrôle les cycles |
| AUD-ARC-10 | Faible | S | E02 toujours ouvert : les 13 auto-tests du garde de frontières ne couvrent toujours aucune règle A2 et la reconnaissance des dépendances internes repose sur le littéral « com.minos » |
| AUD-TST-06 | Faible | S | 21 tests de minos-runtime-local passent à vide sur l'autre OS (`if (plateforme) return;`) au lieu d'être comptés comme sautés |
| AUD-TST-07 | Faible | S | L'auto-test de check-jacoco.py n'est exécuté par aucun workflow, alors que celui de chaque autre gate l'est |
| AUD-TST-08 | Faible | S | Le scope `m29-backend-routing` lit toujours un rapport JaCoCo obtenu par redirection du `<directory>` de minos-app |
| AUD-TST-13 | Faible | S | La CI du plugin IntelliJ ne se déclenche pas quand change le code qui produit le JSON qu'il consomme |

### Sprint 2 — Failles de sécurité et observabilité

**Pourquoi ici.** Les deux défauts de sécurité exploitables (dépôt Git parent piégé, installation des providers avec l'environnement complet) sont locaux et d'effort court. L'observabilité vient ensuite, avant les refontes qui pourraient régresser sans laisser de trace.

**Critère de sortie.** Aucune commande lancée par MINOS hors bac à sable ne reçoit les variables MINOS_TEAM_KEY_* ou de jeton, aucun dépôt Git n'est ouvert hors de la racine demandée, et une erreur du SDK MCP, de JGit ou d'une tâche du plugin laisse une trace journalisée sans chemin absolu.

**Charge** : 2,5 à 9 jours.

| ID | Sévérité | Effort | Titre |
|---|---|---|---|
| AUD-SEC-01 | Élevée | S | Analyse Git : le dépôt est cherché en remontant sans plafond ni contrôle de propriétaire, et `git status` (JGit) peut exécuter un filtre `clean` déclaré par ce dépôt |
| AUD-DEP-08 | Moyenne | S | Journalisation des bibliothèques supprimée dans le livrable : slf4j-nop efface les erreurs du SDK MCP et de JGit |
| AUD-QUA-04 | Moyenne | S | Plugin IntelliJ : trois tâches d'arrière-plan copiées attrapent Throwable et n'écrivent aucun journal ; un échec sans message s'affiche « Unknown failure » sans trace nulle part |
| AUD-SEC-02 | Moyenne | M | Installation des providers : scip-java est résolu par Coursier sans empreinte épinglée hors Windows et exécuté hors bac à sable, et les commandes d'installation reçoivent l'environnement complet |
| AUD-QUA-06 | Faible | S | Journalisation : deux régimes coexistent, certains WARNING écrivent chemins absolus et exception complète alors que la règle du dépôt est de n'en écrire aucun |
| AUD-SEC-12 | Faible | S | Les scripts d'installation écrivent le mot de passe PostgreSQL avec l'ACL héritée, puis la restreignent, et ne revérifient pas un fichier existant |
| AUD-SEC-13 | Faible | S | Le lanceur de développement `minos.cmd` appelle `java` sans chemin : cmd.exe le cherche d'abord dans le répertoire courant, qui est le projet quand le plugin le lance |

### Sprint 3 — Socle d'exécution supporté

**Pourquoi ici.** Le JDK 24 et Node 20 ne reçoivent plus de correctifs. La migration exige que les tests de confinement s'exécutent réellement (sprint 1) pour qualifier le nouveau JDK sous Linux et Windows.

**Critère de sortie.** Le jar, les images Docker et l'installateur tournent sur un JDK LTS et un Node maintenus, Dependabot peut proposer leurs montées majeures, et l'inventaire de provenance décrit ce qui est réellement installé.

**Charge** : 6,75 à 20 jours.

**Prérequis des sprints antérieurs** : AUD-DEP-01 attend AUD-TST-03 (S1).

| ID | Sévérité | Effort | Titre |
|---|---|---|---|
| AUD-DEP-01 | Élevée | L | Tous les livrables tournent sur le JDK 24.0.2, version non-LTS sans correctif de sécurité depuis juillet 2025, et l'outillage interdit d'en sortir |
| AUD-DEP-02 | Moyenne | M | L'image Docker embarque Node.js 20.20.2, hors support depuis le 30/04/2026, alors que la distribution Windows exécute le même scip-typescript 0.4.0 sous Node 24 |
| AUD-DEP-03 | Moyenne | M | Les dépendances npm livrées (2 lockfiles, 66 paquets) et les 11 artefacts de embedded-tools.json n'ont aucune veille de vulnérabilité automatisée |
| AUD-DEP-10 | Faible | S | L'inventaire de provenance de l'image Docker annonce des toolchains périmées (.NET 10.0.302, Go 1.26.5, Rust 1.97.1) au lieu de celles réellement installées |
| AUD-DEP-14 | Faible | S | CVE du client HTTP de Testcontainers toujours présentes en portée test (Testcontainers 2.0.5 inchangé) |
| AUD-DEP-15 | Faible | M | L'image du plan de requête MCP embarque toute la chaîne de compilation (JDK, gcc, Go, Rust, .NET SDK, pip) |
| AUD-DEP-16 | Faible | S | Le jar ombré et l'image Docker ne conservent pas les NOTICE des dépendances Apache-2.0 |

### Sprint 4 — Chemin de requête : ne plus relire le snapshot

**Pourquoi ici.** Le chemin de requête MCP relit et redécode le snapshot plusieurs fois par appel. Les corrections sont locales aux stores et aux services sémantiques, et leurs effets se mesurent avec les compteurs déjà utilisés par ARCHI-SUIVI.

**Critère de sortie.** Une requête hybride lit le snapshot actif au plus une fois et ne charge aucun vecteur, un snapshot de la taille de ce dépôt tient dans le cache de vues, et la synchronisation sémantique PostgreSQL ne réécrit que les documents modifiés.

**Charge** : 3,75 à 13 jours.

**Ordre interne** : AUD-PERF-03 avant AUD-PERF-11 ; AUD-PERF-01 avant AUD-PERF-06.

| ID | Sévérité | Effort | Titre |
|---|---|---|---|
| AUD-PERF-01 | Élevée | M | Recherche hybride avec index sémantique READY : 5 chargements du snapshot actif, 4 lectures de métadonnées et de taille, et un chargement complet de l'index vectoriel par requête (sans cache sous PostgreSQL) |
| AUD-PERF-02 | Moyenne | S | Construction des documents sémantiques : le fichier source est relu et décodé en entier pour chaque symbole, à chaque `minos index` avec embeddings et à la première requête hybride de chaque snapshot |
| AUD-PERF-03 | Moyenne | S | Cache de vues de requête : poids estimé à 8 fois la taille persistée (1,4 mesuré), donc tout snapshot de plus de 64 Mio n'est jamais mis en cache et chaque requête MCP le relit et le décode |
| AUD-PERF-05 | Moyenne | S | `minos_architecture_graph` en Mermaid ou DOT avec un module : deux découvertes complètes du dépôt et deux chargements du snapshot pour une seule requête |
| AUD-PERF-06 | Moyenne | M | Synchronisation sémantique PostgreSQL : l'index entier est supprimé puis réinséré (vecteurs sérialisés en texte) à chaque `minos index`, même quand presque tous les vecteurs sont réutilisés |
| AUD-PERF-07 | Moyenne | S | Graphe de programme : la clé de cache est calculée avant la recherche en cache et, sans empreinte exacte du snapshot, hache toutes les sources Java à chaque appel, même en cas de succès du cache |
| AUD-PERF-11 | Faible | S | Analyse d'impact et impact avancé reconstruisent et retrient leurs index d'arêtes à chaque appel |
| AUD-PERF-12 | Faible | S | Pool JDBC : `isValid()` (un aller-retour réseau) à l'emprunt ET à la restitution de chaque connexion |
| AUD-PERF-13 | Faible | S | Rapport runtime d'un symbole : recherche linéaire dans tous les symboles du snapshot au lieu de l'index de la vue |

### Sprint 5 — Chaîne de release et d'approvisionnement

**Pourquoi ici.** La chaîne de livraison a des trous indépendants du code : pas de signature, un déclenchement de release du plugin qui ne part jamais, des dépendances livrées sans veille. Regroupés parce qu'ils touchent les mêmes workflows.

**Critère de sortie.** Une release Windows publiée est signée et attestée, le plugin IntelliJ est publié par la même release, et chaque dépendance livrée (Maven, Gradle, npm, outils embarqués) est couverte par une veille de vulnérabilités ou un verrou de sommes.

**Charge** : 2,5 à 9 jours.

| ID | Sévérité | Effort | Titre |
|---|---|---|---|
| AUD-DEP-04 | Moyenne | S | Le plugin IntelliJ résout ses dépendances Gradle sans verrou ni vérification de somme : aucun scanner ne les analyse |
| AUD-DEP-05 | Moyenne | S | Les workflows de release construisent le plugin avec Gradle 9.6.1 alors que le wrapper qualifié par la CI est en 9.8.0 |
| AUD-DEP-06 | Moyenne | M | La release Windows est publiée sans signature Authenticode ni attestation de provenance : le script de signature n'est appelé par aucun workflow |
| AUD-DEP-07 | Moyenne | S | Le workflow de publication du plugin IntelliJ ne se déclenche pas sur une release créée par le workflow Windows (événement émis par GITHUB_TOKEN) |
| AUD-DEP-11 | Faible | S | Build Maven non reproductible : ni `project.build.outputTimestamp` ni `requirePluginVersions` |
| AUD-DEP-12 | Faible | S | Les 22 `actions/checkout` conservent le jeton GITHUB_TOKEN et le job de PR `verify` n'a pas de délai maximal |
| AUD-DEP-13 | Faible | S | Le jeton Docker Hub est exposé au code de la PR pendant `mvnw clean verify` |

### Sprint 6 — Couverture réelle et plugin IntelliJ

**Pourquoi ici.** Une fois les gates exigés et l'auto-test JaCoCo branché (sprint 1), on peut étendre le gate de couverture sans risquer de le rendre faux en silence.

**Critère de sortie.** Le gate JaCoCo porte un plancher global et couvre les classes du cœur aujourd'hui hors scope, `mvnw verify` passe sur un poste sans JAVA_HOME, et le plugin a une couverture mesurée à chaque PR.

**Charge** : 5,5 à 18 jours.

**Prérequis des sprints antérieurs** : AUD-TST-01 attend AUD-TST-07 (S1) ; AUD-TST-02 attend AUD-TST-07 (S1) ; AUD-TST-02 attend AUD-TST-08 (S1) ; AUD-QUA-01 attend AUD-TST-13 (S1).

| ID | Sévérité | Effort | Titre |
|---|---|---|---|
| AUD-QUA-01 | Moyenne | M | Le lanceur Job Object C# du plugin IntelliJ (350 lignes) est une copie manuelle, divergente de 70 lignes, du script assemblé par fragments dans minos-runtime-local, sans contrôle de parité |
| AUD-TST-02 | Moyenne | M | Le gate JaCoCo ne regarde que 48 % des lignes de production : 17 classes du cœur hors de tout scope, en plus des 7 déjà citées par T2 |
| AUD-TST-04 | Moyenne | S | Le dépôt PostgreSQL des sessions runtime n'est exécuté par aucun test et se cache derrière le scope de paquet à 60 % |
| AUD-TST-05 | Moyenne | M | Plugin IntelliJ : aucune couverture mesurée en CI de PR (seul le PIT manuel de code-audit.yml en donne une), 10 classes sur 24 (39 % des lignes) sans aucun test, dont toute l'UI, les actions et `MinosProjectService` |
| AUD-TST-01 | Faible | S | Toujours aucun seuil de couverture global au HEAD : 28 scopes ciblés, aucun but `jacoco:check`, planchers de 12 % à 80 % |
| AUD-TST-09 | Faible | S | Trois tests d'idempotence PostgreSQL n'ont aucune assertion : ils ne prouvent que l'absence d'exception |
| AUD-TST-10 | Faible | S | Onze tests de verrou prouvent l'attente par un `Thread.sleep(100–300 ms)` suivi de `assertFalse(isDone())` |
| AUD-TST-11 | Faible | M | Mutation : 10 modules complets et 1 partiel sur 14, cli/bootstrap/app jamais mutés, et le document de couverture se contredit (« 3 modules sur 14 ») et ignore le PIT du plugin désormais exécuté |
| AUD-TST-12 | Faible | S | Les refus de `WindowsJobObjectProcessOwnership` (NUL dans le plan, exécutable absent ou introuvable) n'ont aucun test |
| AUD-TST-18 | Faible | S | Un test de minos-provider-scip dépend d'une variable JAVA_HOME ambiante sous Windows : `mvnw verify` échoue sans elle |

### Sprint 7 — Sorties publiques : messages, MCP, rendus

**Pourquoi ici.** Résidus connus des audits de septembre et d'octobre sur ce que MINOS écrit vers l'extérieur. Lot homogène : messages d'erreur, sorties MCP, rendus Mermaid.

**Critère de sortie.** Aucune sortie CLI, MCP ou API n'émet de chemin absolu, d'e-mail d'auteur ou de HTML non échappé, et un argument MCP inconnu est refusé avec une erreur d'usage.

**Charge** : 1,75 à 7 jours.

| ID | Sévérité | Effort | Titre |
|---|---|---|---|
| AUD-SEC-04 | Faible | S | S18 tient encore : `MinosRuntimeSettings.load` suit un lien avant la primitive, qui le refuse avec un chemin absolu dans le message |
| AUD-SEC-05 | Faible | S | S19 tient encore : un chemin absolu collé à un caractère d'identifiant (ex. `\u001b[31m/home/…`) échappe à `PublicErrorMessages` |
| AUD-SEC-07 | Faible | S | S10 (Ollama) tient encore : `minos-ollama` est accepté hors Docker, en HTTP clair, et un corps d'erreur de 16 Mio est recopié dans l'exception |
| AUD-SEC-08 | Faible | S | S10 (Mermaid) tient encore : `<` et `>` ne sont pas échappés dans les libellés, qui contiennent volontairement du HTML (`<br/>`) |
| AUD-SEC-09 | Faible | S | S10/C13 tiennent encore : le serveur MCP ignore les arguments inconnus alors que le schéma publie `additionalProperties:false` |
| AUD-SEC-10 | Faible | S | S10 tient encore : le MCP expose `rootPath` en absolu et `git-activity`/l'API exposent les e-mails des auteurs |
| AUD-SEC-11 | Faible | S | Les règles source/puits/assainisseur de `minos_security_paths` viennent exclusivement du dépôt analysé (`.minos/java-advanced-provider.properties`), sans le dire |

### Sprint 8 — Confinement et E/S privées

**Pourquoi ici.** Touche le bac à sable et les primitives d'E/S. Placé après le sprint 1 parce que ces changements ne sont vérifiables que si les tests de confinement s'exécutent réellement sur les deux OS.

**Critère de sortie.** Toute écriture sous MINOS_HOME passe par la primitive et le gate le prouve sans nouvelle exception, aucune entrée publique de runtime-local n'exécute un provider hors bac à sable, et le suivi de processus ne sonde plus à 10 ms.

**Charge** : 2,75 à 9 jours.

**Prérequis des sprints antérieurs** : AUD-SEC-06 attend AUD-TST-03 (S1) ; AUD-PERF-08 attend AUD-TST-03 (S1).

| ID | Sévérité | Effort | Titre |
|---|---|---|---|
| AUD-PERF-08 | Moyenne | S | Suivi de propriété des processus : `ProcessHandle.descendants()` interrogé toutes les 10 ms pendant toute l'exécution, toujours au HEAD et dupliqué dans le plugin IntelliJ |
| AUD-ARC-09 | Faible | S | A11 toujours présent : trois entrées publiques de minos-runtime-local exécutent un provider sans bac à sable |
| AUD-QUA-10 | Faible | S | Point d'injection de test statique et mutable toujours présent en production dans ConfinedFileOpener (résidu ouvert de Q15) |
| AUD-SEC-03 | Faible | M | S17 tient encore : le gate d'E/S privées ignore `newOutputStream` (23 appels), `createTempFile` (8), `createDirectory` (5), `copy` (4), `newBufferedWriter` (3) et `move` (14) |
| AUD-SEC-06 | Faible | M | S16 tient encore : la réécriture de DACL efface les ACE conditionnelles invisibles à Java, et le backend AppContainer écrit ses ACE hors de `PrivateLocalStorage` |

### Sprint 9 — Une seule orchestration d'indexation

**Pourquoi ici.** Deux orchestrations de l'indexation coexistent ; il faut en garder une avant de toucher au hachage de l'arbre (PERF-04) ou de déplacer le workflow hors de la CLI (sprint 10), sinon chaque correctif s'écrit deux fois.

**Critère de sortie.** Une seule classe orchestre l'indexation incrémentale et c'est celle qu'appelle la CLI ; aucune classe publique de src/main n'est appelée que par des tests hors liste blanche ; le port IndexStateStore a un type d'erreur unique.

**Charge** : 4,5 à 14 jours.

**Ordre interne** : AUD-ARC-02 avant AUD-PERF-04.

| ID | Sévérité | Effort | Titre |
|---|---|---|---|
| AUD-ARC-02 | Moyenne | M | Deux orchestrations de l'indexation incrémentale coexistent : IncrementalIndexingCoordinator (moteur, public, 4 classes de test) n'a aucun appelant en production, la CLI réimplémente le flux |
| AUD-ARC-04 | Moyenne | S | A8 toujours ouvert et élargi : deux découvertes ServiceLoader sans chargeur explicite ni refus des doublons, dont une par laquelle le moteur atteint un adaptateur hors de la racine de composition |
| AUD-PERF-04 | Moyenne | M | Une indexation hache tout l'arbre source au moins trois fois (empreinte avant, empreinte du scope racine, empreinte après), sans réutilisation ni raccourci taille/date |
| AUD-QUA-02 | Moyenne | M | Environ 380 lignes de services de production ne sont appelées que par des tests (hors IncrementalIndexingCoordinator, traité par AUD-ARC-02), et deux gates en imposent la couverture |
| AUD-QUA-03 | Moyenne | M | Le port IndexStateStore n'a pas de contrat d'erreur : UncheckedIOException côté fichiers, IllegalStateException côté PostgreSQL, d'où des catch (RuntimeException) qui requalifient aussi les bogues en erreurs d'E/S |
| AUD-PERF-09 | Faible | S | Tests liés : une preuve par emplacement de référence, sans plafond, est persistée dans chaque relation RELATED_TEST |

### Sprint 10 — Frontières d'architecture

**Pourquoi ici.** Chantiers structurels déjà décidés en ADR (0055, 0057, 0058) ou à trancher (0047). Ils supposent la garde de frontières durcie (sprint 1), l'orchestration unique (sprint 9) et la correction bornée du cache (sprint 4).

**Critère de sortie.** Les surfaces n'importent plus minos-engine hors d'une liste à cliquet, aucun adaptateur n'importe un autre adaptateur, et l'ADR 0047 est accepté ou rejeté.

**Charge** : 14 à 38 jours.

**Prérequis des sprints antérieurs** : AUD-ARC-01 attend AUD-ARC-02 (S9) ; AUD-ARC-01 attend AUD-ARC-10 (S1) ; AUD-ARC-08 attend AUD-ARC-10 (S1) ; AUD-ARC-05 attend AUD-ARC-10 (S1) ; AUD-ARC-05 attend AUD-SEC-06 (S8) ; AUD-ARC-07 attend AUD-PERF-03 (S4).

| ID | Sévérité | Effort | Titre |
|---|---|---|---|
| AUD-ARC-01 | Moyenne | L | Les surfaces pilotent directement le moteur : 103 références à minos-engine dans 30 fichiers, et le workflow d'indexation vit dans la CLI, sans aucun cliquet contre une nouvelle dépendance |
| AUD-ARC-05 | Moyenne | L | minos-engine, annoncé « ports », est à 72 % du code concret : 12 136 lignes de classes contre 1 042 d'interfaces, et il lance lui-même icacls.exe, rôle que l'ADR 0022 réserve au runtime local |
| AUD-ARC-07 | Moyenne | L | A9 toujours ouvert : les deux backends reconstruisent un InMemoryCodeKnowledgeStore complet par snapshot lu, ADR 0047 toujours « Proposed » et ADR 0056 non commencé |
| AUD-ARC-08 | Moyenne | L | Couplages adaptateur → adaptateur inchangés : le provider SCIP instancie quatre FileSymbolSnapshotStore et PostgreSQL réutilise codecs et registre de chemins du backend local |
| AUD-ARC-11 | Faible | M | minos-app n'est pas qu'un assemblage : il porte un transport MCP Docker et un pont NEXUS, que la CLI atteint par une dépendance d'exécution non déclarée |
| AUD-ARC-12 | Faible | M | Le nom d'un package ne dit pas son module : 24 packages com.minos.* sans préfixe de module dans engine, application et domain, et des hiérarchies partagées entre modules |

### Sprint 11 — Hygiène de fond

**Pourquoi ici.** Hygiène sans urgence, à faire quand les fichiers concernés ont fini de bouger.

**Critère de sortie.** Un seul cache LRU pondéré et une seule arithmétique bornée dans le code de production, aucun identifiant de jalon dans les noms de production, et les scripts partagent leurs fonctions communes.

**Charge** : 4,5 à 14 jours.

| ID | Sévérité | Effort | Titre |
|---|---|---|---|
| AUD-QUA-05 | Moyenne | M | Huit caches LRU pondérés et treize helpers d'addition bornée réécrits à la main ; le même nom safeAdd a trois sémantiques différentes |
| AUD-PERF-10 | Faible | S | Recherche de symboles : le rang de correspondance (jusqu'à 7 `toLowerCase`) est recalculé dans le filtre puis à chaque comparaison du tri |
| AUD-QUA-07 | Faible | M | Méthodes longues concentrées dans quelques services : 40 méthodes de plus de 80 lignes, dont analyze (131 l., 22 décisions) et ingest (154 l.) |
| AUD-QUA-08 | Faible | S | Des numéros de jalon subsistent dans des identifiants de production (MinosM21Actions, qualifiedM24Providers, M17_DEFAULT_FILE_NAMES…) contrairement à la convention de l'ADR 0043 |
| AUD-QUA-09 | Faible | M | Scripts sans bibliothèque partagée : fonctions PowerShell et Python recopiées entre scripts vivants, avec variantes divergentes |
| AUD-QUA-11 | Faible | M | Commentaires et Javadoc : 170 fichiers de production contiennent du français, dont 27 mélangent français et anglais dans le même fichier (Q18 toujours ouvert) |

## Constats Info, hors sprint

Ils documentent ce qui tient et ne demandent aucune action. Ceux marqués ⚑ expliquent pourquoi un risque est déjà borné : ne pas les « corriger ».

- **AUD-ARC-13** — A10 toujours plausible : le chemin de source le plus long fait 125 caractères relatifs, sans garde de longueur dans le dépôt
- ⚑ **AUD-ARC-14** — Mécanismes solides à ne pas « simplifier » : gate de frontières vert, test ArchUnit à import prouvé complet, découverte SPI en échec rapide, API publique sans type interne
- ⚑ **AUD-QUA-12** — Hygiène de base mesurée solide : 2,7 % de duplication Java, aucun TODO/FIXME, aucune Locale ni Charset implicite, 38 catch vides tous justifiés
- ⚑ **AUD-SEC-14** — S10 (« séparateur `\0` sans préfixe de longueur ») n'est plus exploitable : tous les champs chaînés refusent `\0`
- ⚑ **AUD-SEC-15** — Mécanismes solides vérifiés au HEAD (à ne pas « corriger ») : aucun secret en clair, SQL entièrement paramétré, confinement des providers et des téléchargements
- ⚑ **AUD-TST-14** — Aucun test désactivé : 0 `@Disabled`, les tests conditionnels sont tous liés à l'OS et les deux OS exécutent le `verify` complet en CI
- ⚑ **AUD-TST-15** — Les 12 goldens de caractérisation sont comparés octet pour octet, une référence absente échoue et la régénération n'est possible que sur demande explicite
- **AUD-TST-16** — Pyramide : 2 304 tests, 80 % unitaires (dont 48 % sur système de fichiers réel), 15 % d'intégration processus/OS, 2 % PostgreSQL réel, 2 % goldens, 7 e2e sur le jar ombré
- **AUD-TST-17** — T3 est levé au HEAD pour S1, S2 et S3 mais reste listé sans statut dans l'audit de septembre
- ⚑ **AUD-PERF-14** — Info — mécanismes à ne pas « corriger » sans nouvelle mesure : recherche pgvector exacte, traversée d'impact sans arrêt anticipé, pas de cache d'architecture
- ⚑ **AUD-DEP-17** — Points solides à ne pas « corriger » : épinglage par SHA des actions, digests d'images, sommes SHA-256 des outils, compose durci
