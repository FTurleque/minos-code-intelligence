# Audit MINOS — 10 octobre 2026

Audit technique complet (6 axes) du dépôt `minos-code-intelligence` au HEAD `816cdd0c` de `develop` (merge de la PR #391). Il succède à l'[audit de septembre](../archive/2026-09/AUDIT-2026-09.md), à l'[audit d'octobre](../archive/2026-10-06/README.md) et à l'[audit outillé du 8 octobre](../../quality/code-audit-constats.md) : leurs constats clos ne sont pas re-signalés, et ceux qui restent ouverts au HEAD sont repris avec leur identifiant d'origine (champ **Connu**).

| Document | Contenu |
|---|---|
| [`findings.md`](findings.md) | Les 90 constats, triés par sévérité puis par axe, avec preuve, impact, action, sprint et dépendances |
| [`findings.json`](findings.json) | Les mêmes constats en JSON (source de l'artefact, point d'entrée d'un suivi automatisé) |
| [`sprints.md`](sprints.md) | **L'ordre de correction** : 11 sprints, chemin critique, critères de sortie |
| [`sprints-meta.json`](sprints-meta.json) | Les sprints en JSON |
| [`architecture.md`](architecture.md) | Le découpage réellement observé, diagramme Mermaid, écarts aux règles |
| [`plan-action.md`](plan-action.md) | Le plan par urgence (immédiat, 1 mois, fond) |

## Contexte et méthode

- **Périmètre** : 14 modules Maven + plugin IntelliJ (Gradle), 546 fichiers et 74,6 k lignes de production, ~77 k lignes de test, 59 scripts Python, 101 scripts PowerShell, 13 workflows, Docker. Le cœur lu en profondeur est la vingtaine de classes les plus modifiées depuis mars et les plus lourdes (bacs à sable de `minos-runtime-local`, orchestration de `minos-engine`, stores local et PostgreSQL, runtimes SCIP, surfaces CLI/MCP/API, plugin).
- **Méthode** : lecture directe du code, un agent par axe, puis consolidation et **relecture indépendante** (22 modifications : une sévérité relevée, deux abaissées, cinq champs « connu » corrigés, quatre titres rectifiés, dépendances retouchées). Les 18 gates du job `invariants` et leurs auto-tests ont été rejoués : tous verts. Le serveur d'indexation MINOS n'a pas été utilisé.
- **Build** : `mvnw -B -ntp verify` exécuté sur le poste Windows du mainteneur (JDK 24.0.1), aucune CI déclenchée. Première exécution : **échec** sur `minos-provider-scip`, un test dépendant d'un `JAVA_HOME` ambiant (AUD-TST-18). Seconde exécution, `JAVA_HOME` posé : **BUILD SUCCESS en 21 min 29 s, 2 317 tests, 0 échec, 56 sautés** (dont 38 dans `minos-runtime-local`). `check-jacoco.py` : **SUCCESS** (scope PostgreSQL exclu comme en CI Windows, `provider-sandbox-linux` sauté par plateforme). Couverture agrégée : **83,0 % des lignes, 66,4 % des branches**.
- **Aucun code applicatif n'a été modifié.**

## Notes par axe

| Axe | Note | Calcul | Lecture |
|---|---:|---|---|
| Sécurité | 75 | 100 − (1×10 + 1×4 + 11×1) = 75 | aucun secret en clair, SQL paramétré, confinement solide ; 11 faibles sont des résidus S10–S19 |
| Qualité | 74 | 100 − (5×4 + 6×1) = 74 | 2,7 % de duplication, aucun TODO ; dette de copies parallèles |
| Tests | 74 | 100 − (4×4 + 10×1) = 74 | 2 317 tests verts, 83 % des lignes couvertes ; le gate ne protège que 48 % des lignes |
| Architecture | 67 | 100 − (7×4 + 5×1) = 67 | aucun cycle entre modules ; surfaces → moteur en dette déclarée |
| Performance | 57 | 100 − (1×10 + 7×4 + 5×1) = 57 | note pessimiste, voir ci-dessous |
| Dépendances | 51 | 100 − (1×10 + 8×4 + 7×1) = 51 | note pessimiste, voir ci-dessous |

Barème : 100, moins 25 par Critique, 10 par Élevée, 4 par Moyenne, 1 par Faible. **Trois notes communiquent mal, à lire avec leur réserve** : la Performance (57) compte comme ignorée une dette mesurée et volontairement différée en septembre (7 constats sur 14 viennent d'ARCHI-SUIVI § A6.8) ; les Dépendances (51) sont tirées vers le bas par la chaîne de livraison, pas par le code ; l'Architecture (67) compte des dettes déclarées par l'ADR 0058 et planifiées dans le chantier SH.

## Les 5 constats majeurs

1. **AUD-SEC-01 (Élevée)** — `GitIntelligenceService` cherche le dépôt en remontant sans plafond ni contrôle de propriétaire (`GitIntelligenceService.java:186-187`), puis lance `git status` : JGit exécute le filtre `clean` déclaré par ce dépôt. Un `C:\.git` ou `/tmp/.git` posé par un autre compte fait exécuter une commande sous l'identité de l'utilisateur, hors bac à sable. Effort S.
2. **AUD-DEP-01 (Élevée)** — tous les livrables (deux images Docker, runtime jlink de l'installeur) tournent sur le JDK 24.0.2, non-LTS, sans correctif depuis juillet 2025 ; l'enforcer `[24,25)` et Dependabot empêchent d'en sortir. Préalable : un ADR qui remplace l'ADR 0005.
3. **AUD-PERF-01 (Élevée)** — une recherche hybride avec index sémantique prêt charge cinq fois le snapshot actif et l'index vectoriel entier ; sous PostgreSQL (configuration Docker « connected »), environ 300 Mo de vecteurs en texte par requête MCP, dans un conteneur dont le tas par défaut est d'environ 768 Mio.
4. **AUD-SEC-02 (Moyenne)** — l'installation des providers résout scip-java par Coursier sans empreinte hors Windows et exécute les commandes d'installation avec l'environnement complet, clés `MINOS_TEAM_KEY_*` comprises, alors que les providers eux-mêmes reçoivent un environnement en liste blanche.
5. **AUD-TST-03 (Moyenne, chemin critique)** — les tests de confinement bubblewrap, cgroup et AppContainer (dont la non-régression de S3) se sautent en silence par `assumeTrue`, sans interrupteur « requis » comme celui de PostgreSQL. Tant qu'il n'est pas corrigé, aucune modification du bac à sable ni la migration de JDK ne sont vérifiables.

## Thèmes transverses

1. **Les contrôles existent mais ne s'imposent pas.** Gates non exigés par le ruleset, auto-test JaCoCo jamais rejoué, tests de confinement qui se sautent en silence, gate de couverture qui ne regarde que 48 % des lignes, contrôle documentaire qui passe sur une page fausse. Neuf sprints de remédiation ont construit l'appareil ; rien ne garantit qu'il reste allumé. (DEP-09, TST-07, TST-03, TST-02, TST-01, TST-06, ARC-03, ARC-10, ARC-06)
2. **Des copies parallèles qui divergent.** Deux orchestrations de l'indexation, un lanceur Job Object recopié dans le plugin (70 lignes d'écart), huit caches LRU et trois sémantiques de safeAdd, deux versions de Gradle, des helpers de scripts dupliqués. Chaque correctif doit être écrit deux fois, et l'audit d'octobre en a déjà payé le prix (D06). (ARC-02, QUA-01, QUA-05, QUA-09, DEP-05, PERF-08, ARC-01)
3. **Le chemin de requête relit au lieu de retenir.** Facteur de poids 8 au lieu de 1,4 mesuré, cinq chargements du snapshot par requête hybride, relecture du fichier source par symbole, double découverte du dépôt. Les mesures existent depuis septembre ; les corrections bornées n'ont pas été faites. (PERF-01, PERF-02, PERF-03, PERF-05, PERF-07, PERF-11, PERF-13)
4. **Le bac à sable est solide, ce qui tourne à côté l'est moins.** Les providers s'exécutent confinés avec un environnement en liste blanche. Mais l'installation des providers hérite de tout l'environnement, l'analyse Git remonte vers un .git parent qui peut déclarer un filtre, le lanceur de développement cherche java dans le répertoire courant, et un test dépend du JAVA_HOME de la machine. (SEC-01, SEC-02, SEC-13, SEC-11, TST-18, SEC-12)
5. **Le socle de livraison est en retard sur le code.** JDK 24 et Node 20 hors support, release Windows non signée, publication du plugin jamais déclenchée, aucune veille sur les dépendances npm et les outils embarqués, journalisation des bibliothèques supprimée dans le livrable. (DEP-01, DEP-02, DEP-03, DEP-06, DEP-07, DEP-08)

## Sprints

| Sprint | Titre | Constats | Charge (j) | Critère de sortie |
|---|---|---:|---|---|
| S1 | Remettre les dispositifs de mesure en marche | 9 | 3 – 11 | Chaque gate du job invariants et chaque auto-test de gate sont des checks exigés par le ruleset ; un test de confinement sauté fait échouer le job de son OS ; la garde de frontières refuse un cycle de packages et une arête interdite par l'ADR 0042 ; la documentation d'architecture courante est contrôlée contre les POM. |
| S2 | Failles de sécurité et observabilité | 7 | 2,5 – 9 | Aucune commande lancée par MINOS hors bac à sable ne reçoit les variables MINOS_TEAM_KEY_* ou de jeton, aucun dépôt Git n'est ouvert hors de la racine demandée, et une erreur du SDK MCP, de JGit ou d'une tâche du plugin laisse une trace journalisée sans chemin absolu. |
| S3 | Socle d'exécution supporté | 7 | 6,75 – 20 | Le jar, les images Docker et l'installateur tournent sur un JDK LTS et un Node maintenus, Dependabot peut proposer leurs montées majeures, et l'inventaire de provenance décrit ce qui est réellement installé. |
| S4 | Chemin de requête : ne plus relire le snapshot | 9 | 3,75 – 13 | Une requête hybride lit le snapshot actif au plus une fois et ne charge aucun vecteur, un snapshot de la taille de ce dépôt tient dans le cache de vues, et la synchronisation sémantique PostgreSQL ne réécrit que les documents modifiés. |
| S5 | Chaîne de release et d'approvisionnement | 7 | 2,5 – 9 | Une release Windows publiée est signée et attestée, le plugin IntelliJ est publié par la même release, et chaque dépendance livrée (Maven, Gradle, npm, outils embarqués) est couverte par une veille de vulnérabilités ou un verrou de sommes. |
| S6 | Couverture réelle et plugin IntelliJ | 10 | 5,5 – 18 | Le gate JaCoCo porte un plancher global et couvre les classes du cœur aujourd'hui hors scope, `mvnw verify` passe sur un poste sans JAVA_HOME, et le plugin a une couverture mesurée à chaque PR. |
| S7 | Sorties publiques : messages, MCP, rendus | 7 | 1,75 – 7 | Aucune sortie CLI, MCP ou API n'émet de chemin absolu, d'e-mail d'auteur ou de HTML non échappé, et un argument MCP inconnu est refusé avec une erreur d'usage. |
| S8 | Confinement et E/S privées | 5 | 2,75 – 9 | Toute écriture sous MINOS_HOME passe par la primitive et le gate le prouve sans nouvelle exception, aucune entrée publique de runtime-local n'exécute un provider hors bac à sable, et le suivi de processus ne sonde plus à 10 ms. |
| S9 | Une seule orchestration d'indexation | 6 | 4,5 – 14 | Une seule classe orchestre l'indexation incrémentale et c'est celle qu'appelle la CLI ; aucune classe publique de src/main n'est appelée que par des tests hors liste blanche ; le port IndexStateStore a un type d'erreur unique. |
| S10 | Frontières d'architecture | 6 | 14 – 38 | Les surfaces n'importent plus minos-engine hors d'une liste à cliquet, aucun adaptateur n'importe un autre adaptateur, et l'ADR 0047 est accepté ou rejeté. |
| S11 | Hygiène de fond | 6 | 4,5 – 14 | Un seul cache LRU pondéré et une seule arithmétique bornée dans le code de production, aucun identifiant de jalon dans les noms de production, et les scripts partagent leurs fonctions communes. |

Enveloppe totale : 51,5 à 162 jours de travail, hors revue, allers-retours de CI et ADR. Détail et ordre interne dans [`sprints.md`](sprints.md).

**Chemin critique** : AUD-TST-03 (S1, S) débloque AUD-DEP-01, AUD-PERF-08, AUD-SEC-06 ; AUD-ARC-10 (S1, S) débloque AUD-ARC-01, AUD-ARC-05, AUD-ARC-08 ; AUD-ARC-02 (S9, M) débloque AUD-ARC-01, AUD-PERF-04 ; AUD-PERF-03 (S4, S) débloque AUD-ARC-07, AUD-PERF-11 ; AUD-TST-07 (S1, S) débloque AUD-TST-01, AUD-TST-02. Les deux premiers sont d'effort S et en sprint 1 : ce sont les actions les plus rentables de l'audit.

## Angles morts

- Pas de compilation dans l'environnement d'audit (Java 21 contre 24 requis) : tailles, complexité et duplication viennent de scripts textuels ; ArchUnit, SpotBugs et PIT n'ont pas été rejoués.
- Pas de graphe d'appels complet : le code atteint par réflexion, ServiceLoader ou injection est mal couvert (seuls les fichiers META-INF/services ont été relevés).
- Comportement à l'exécution non observé : aucun bubblewrap, cgroup, AppContainer, Docker ni PostgreSQL réel ; aucune mesure de performance Java (les coûts s'appuient sur ARCHI-SUIVI et l'ADR 0047).
- Maven Central inaccessible depuis l'environnement : fraîcheur des bibliothèques Maven et CVE non rejouées (ni OWASP Dependency-Check, ni OSV, ni scanner d'image).
- Ruleset et secrets GitHub invisibles : AUD-DEP-09 (gates non exigés) reste plausible.
- JGit 7.8 non téléchargé : l'exécution des filtres clean pendant status (AUD-SEC-01) est établie sur le source amont postérieur à 7.8, pas sur le jar livré ; pas de reproduction de bout en bout.
- Modules hors du cœur survolés : analyse de programme (taint, def-use), UI du plugin, la plupart des 101 scripts PowerShell, concurrence des handlers MCP, DistributedArtifactBundleStore (dormant, ADR 0041).
- Alertes SpotBugs du 7/10 non requalifiées ; historique git non scanné (Gitleaks non exécuté).
