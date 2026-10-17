# Plan d'action — par urgence

Ce plan classe les constats par **urgence**. L'**ordre d'exécution** est dans [`sprints.md`](sprints.md) et prévaut quand les deux divergent : une action urgente dont le prérequis n'est pas livré ne peut pas être vérifiée. Exemple : AUD-DEP-01 (JDK hors support) est urgent mais attend AUD-TST-03, faute de quoi la migration serait qualifiée sur des tests de confinement qui se sautent.

Effort : S < 1 j, M 1 à 3 j, L > 3 j.

## Immédiat (cette semaine)

Élevées rapides, défauts de sécurité exploitables et leviers d'effort S qui débloquent la suite.

| ID | Sévérité | Effort | Sprint | Prérequis | Constat |
|---|---|---|---|---|---|
| AUD-SEC-01 | Élevée | S | S2 | — | Analyse Git : le dépôt est cherché en remontant sans plafond ni contrôle de propriétaire, et `git status` (JGit) peut exécuter un filtre `clean` déclaré par ce dépôt |
| AUD-SEC-02 | Moyenne | M | S2 | — | Installation des providers : scip-java est résolu par Coursier sans empreinte épinglée hors Windows et exécuté hors bac à sable, et les commandes d'installation reçoivent l'environnement complet |
| AUD-DEP-09 | Moyenne | S | S1 | — | Les gates statiques, OSV, Gitleaks et le plugin IntelliJ ne sont toujours pas imposés par le ruleset |
| AUD-TST-03 | Moyenne | S | S1 | — | Les tests de confinement Linux (bubblewrap, cgroup v2) et Windows (AppContainer) se sautent en silence : aucun interrupteur « requis » comme pour PostgreSQL |
| AUD-TST-07 | Faible | S | S1 | — | L'auto-test de check-jacoco.py n'est exécuté par aucun workflow, alors que celui de chaque autre gate l'est |
| AUD-PERF-03 | Moyenne | S | S4 | — | Cache de vues de requête : poids estimé à 8 fois la taille persistée (1,4 mesuré), donc tout snapshot de plus de 64 Mio n'est jamais mis en cache et chaque requête MCP le relit et le décode |
| AUD-PERF-02 | Moyenne | S | S4 | — | Construction des documents sémantiques : le fichier source est relu et décodé en entier pour chaque symbole, à chaque `minos index` avec embeddings et à la première requête hybride de chaque snapshot |
| AUD-DEP-07 | Moyenne | S | S5 | — | Le workflow de publication du plugin IntelliJ ne se déclenche pas sur une release créée par le workflow Windows (événement émis par GITHUB_TOKEN) |
| AUD-DEP-08 | Moyenne | S | S2 | — | Journalisation des bibliothèques supprimée dans le livrable : slf4j-nop efface les erreurs du SDK MCP et de JGit |
| AUD-TST-18 | Faible | S | S6 | — | Un test de minos-provider-scip dépend d'une variable JAVA_HOME ambiante sous Windows : `mvnw verify` échoue sans elle |

AUD-DEP-01 (Élevée, L) n'est pas dans cette liste parce qu'il n'est pas rapide ; son préalable immédiat est d'écrire l'ADR qui remplace l'ADR 0005 et de l'inscrire au registre des risques avec une échéance.

## Court terme (1 mois)

Le reste des sévérités Élevée et Moyenne qui ne demandent pas de refonte.

| ID | Sévérité | Effort | Sprint | Prérequis | Constat |
|---|---|---|---|---|---|
| AUD-ARC-03 | Moyenne | S | S1 | — | Correction incomplète de G16/G17 : arc42/05 donne des dépendances fausses pour 7 modules sur 14, dont 4 arêtes surface/adaptateur interdites par ADR 0042, et SYNTHESE.md maintient PostgreSQL → minos-application |
| AUD-QUA-04 | Moyenne | S | S2 | — | Plugin IntelliJ : trois tâches d'arrière-plan copiées attrapent Throwable et n'écrivent aucun journal ; un échec sans message s'affiche « Unknown failure » sans trace nulle part |
| AUD-DEP-02 | Moyenne | M | S3 | — | L'image Docker embarque Node.js 20.20.2, hors support depuis le 30/04/2026, alors que la distribution Windows exécute le même scip-typescript 0.4.0 sous Node 24 |
| AUD-DEP-03 | Moyenne | M | S3 | — | Les dépendances npm livrées (2 lockfiles, 66 paquets) et les 11 artefacts de embedded-tools.json n'ont aucune veille de vulnérabilité automatisée |
| AUD-PERF-01 | Élevée | M | S4 | — | Recherche hybride avec index sémantique READY : 5 chargements du snapshot actif, 4 lectures de métadonnées et de taille, et un chargement complet de l'index vectoriel par requête (sans cache sous PostgreSQL) |
| AUD-PERF-05 | Moyenne | S | S4 | — | `minos_architecture_graph` en Mermaid ou DOT avec un module : deux découvertes complètes du dépôt et deux chargements du snapshot pour une seule requête |
| AUD-PERF-06 | Moyenne | M | S4 | AUD-PERF-01 | Synchronisation sémantique PostgreSQL : l'index entier est supprimé puis réinséré (vecteurs sérialisés en texte) à chaque `minos index`, même quand presque tous les vecteurs sont réutilisés |
| AUD-PERF-07 | Moyenne | S | S4 | — | Graphe de programme : la clé de cache est calculée avant la recherche en cache et, sans empreinte exacte du snapshot, hache toutes les sources Java à chaque appel, même en cas de succès du cache |
| AUD-DEP-04 | Moyenne | S | S5 | — | Le plugin IntelliJ résout ses dépendances Gradle sans verrou ni vérification de somme : aucun scanner ne les analyse |
| AUD-DEP-05 | Moyenne | S | S5 | — | Les workflows de release construisent le plugin avec Gradle 9.6.1 alors que le wrapper qualifié par la CI est en 9.8.0 |
| AUD-QUA-01 | Moyenne | M | S6 | AUD-TST-13 | Le lanceur Job Object C# du plugin IntelliJ (350 lignes) est une copie manuelle, divergente de 70 lignes, du script assemblé par fragments dans minos-runtime-local, sans contrôle de parité |
| AUD-TST-02 | Moyenne | M | S6 | AUD-TST-07, AUD-TST-08 | Le gate JaCoCo ne regarde que 48 % des lignes de production : 17 classes du cœur hors de tout scope, en plus des 7 déjà citées par T2 |
| AUD-TST-04 | Moyenne | S | S6 | — | Le dépôt PostgreSQL des sessions runtime n'est exécuté par aucun test et se cache derrière le scope de paquet à 60 % |
| AUD-TST-05 | Moyenne | M | S6 | — | Plugin IntelliJ : aucune couverture mesurée en CI de PR (seul le PIT manuel de code-audit.yml en donne une), 10 classes sur 24 (39 % des lignes) sans aucun test, dont toute l'UI, les actions et `MinosProjectService` |
| AUD-PERF-08 | Moyenne | S | S8 | AUD-TST-03 | Suivi de propriété des processus : `ProcessHandle.descendants()` interrogé toutes les 10 ms pendant toute l'exécution, toujours au HEAD et dupliqué dans le plugin IntelliJ |
| AUD-ARC-04 | Moyenne | S | S9 | — | A8 toujours ouvert et élargi : deux découvertes ServiceLoader sans chargeur explicite ni refus des doublons, dont une par laquelle le moteur atteint un adaptateur hors de la racine de composition |
| AUD-PERF-04 | Moyenne | M | S9 | AUD-ARC-02 | Une indexation hache tout l'arbre source au moins trois fois (empreinte avant, empreinte du scope racine, empreinte après), sans réutilisation ni raccourci taille/date |
| AUD-QUA-03 | Moyenne | M | S9 | — | Le port IndexStateStore n'a pas de contrat d'erreur : UncheckedIOException côté fichiers, IllegalStateException côté PostgreSQL, d'où des catch (RuntimeException) qui requalifient aussi les bogues en erreurs d'E/S |
| AUD-QUA-05 | Moyenne | M | S11 | — | Huit caches LRU pondérés et treize helpers d'addition bornée réécrits à la main ; le même nom safeAdd a trois sémantiques différentes |

## Fond (chantiers structurels)

Effort L ou décision d'architecture préalable (ADR 0047, 0055, 0057, 0058, remplacement de 0005).

| ID | Sévérité | Effort | Sprint | Prérequis | Constat |
|---|---|---|---|---|---|
| AUD-DEP-01 | Élevée | L | S3 | AUD-TST-03 | Tous les livrables tournent sur le JDK 24.0.2, version non-LTS sans correctif de sécurité depuis juillet 2025, et l'outillage interdit d'en sortir |
| AUD-ARC-01 | Moyenne | L | S10 | AUD-ARC-02, AUD-ARC-10 | Les surfaces pilotent directement le moteur : 103 références à minos-engine dans 30 fichiers, et le workflow d'indexation vit dans la CLI, sans aucun cliquet contre une nouvelle dépendance |
| AUD-ARC-05 | Moyenne | L | S10 | AUD-ARC-10, AUD-SEC-06 | minos-engine, annoncé « ports », est à 72 % du code concret : 12 136 lignes de classes contre 1 042 d'interfaces, et il lance lui-même icacls.exe, rôle que l'ADR 0022 réserve au runtime local |
| AUD-ARC-07 | Moyenne | L | S10 | AUD-PERF-03 | A9 toujours ouvert : les deux backends reconstruisent un InMemoryCodeKnowledgeStore complet par snapshot lu, ADR 0047 toujours « Proposed » et ADR 0056 non commencé |
| AUD-ARC-08 | Moyenne | L | S10 | AUD-ARC-10 | Couplages adaptateur → adaptateur inchangés : le provider SCIP instancie quatre FileSymbolSnapshotStore et PostgreSQL réutilise codecs et registre de chemins du backend local |
| AUD-ARC-02 | Moyenne | M | S9 | — | Deux orchestrations de l'indexation incrémentale coexistent : IncrementalIndexingCoordinator (moteur, public, 4 classes de test) n'a aucun appelant en production, la CLI réimplémente le flux |
| AUD-QUA-02 | Moyenne | M | S9 | — | Environ 380 lignes de services de production ne sont appelées que par des tests (hors IncrementalIndexingCoordinator, traité par AUD-ARC-02), et deux gates en imposent la couverture |
| AUD-DEP-06 | Moyenne | M | S5 | — | La release Windows est publiée sans signature Authenticode ni attestation de provenance : le script de signature n'est appelé par aucun workflow |
| AUD-SEC-03 | Faible | M | S8 | — | S17 tient encore : le gate d'E/S privées ignore `newOutputStream` (23 appels), `createTempFile` (8), `createDirectory` (5), `copy` (4), `newBufferedWriter` (3) et `move` (14) |
| AUD-SEC-06 | Faible | M | S8 | AUD-TST-03 | S16 tient encore : la réécriture de DACL efface les ACE conditionnelles invisibles à Java, et le backend AppContainer écrit ses ACE hors de `PrivateLocalStorage` |

## Faibles

Les 44 constats Faible sont rangés dans les sprints au fil des fichiers qu'ils touchent ; ils n'ont pas d'échéance propre. Voir [`sprints.md`](sprints.md).
