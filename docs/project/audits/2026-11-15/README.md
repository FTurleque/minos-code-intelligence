# Audit technique du 15 novembre 2026

> **État au 27/11/2026** (`4f4cac81`) : les sprints 1 à 11 sont exécutés et vérifiés ; 65 constats fermés sur 73, **8 restent à traiter** : huit défauts Faible nouveaux, rangés au sprint 12. Notes sur les constats non fermés : Sécurité 100, Qualité 98, Tests 99, Architecture 98, Performance 100, Dépendances 97. Détail : [`ETAT-2026-11-27.md`](ETAT-2026-11-27.md) (vérifications précédentes : [`ETAT-2026-11-25.md`](ETAT-2026-11-25.md), [`ETAT-2026-11-20.md`](ETAT-2026-11-20.md), [`ETAT-2026-11-19.md`](ETAT-2026-11-19.md), [`ETAT-2026-11-17.md`](ETAT-2026-11-17.md)).

**Cible** : `origin/main` au commit `89be3fb4` (PR #735). **Profil** : complet, six axes. **Résultat** : 58 constats, dont 47 à traiter et 11 Info ; aucun Critique, aucun Élevé.

## Contexte

L'audit du 10 octobre (93 constats, [`../2026-10-10/`](../2026-10-10/README.md)) est presque entièrement fermé : au 11/11, seuls AUD-PERF-16 et AUD-PERF-17 restaient ouverts. Cet audit repart de zéro sur `main`, devenue seule branche d'intégration, et regarde en priorité ce qui est arrivé depuis : l'explorateur web et sa projection 2D, la voie d'indexation WSL2, le budget mémoire des vues (DATA-0081), la fusion des stockages, la suppression de Testcontainers.

Méthode : lecture directe du code par six auditeurs d'axe en parallèle, gates et auto-tests du dépôt rejoués (62 sur 62 verts dans l'environnement d'audit), et build complet `mvnw -B -ntp -fae verify` sur le poste du mainteneur (Windows, JDK 21.0.13, copie extraite de 89be3fb4) : BUILD SUCCESS en 33 min 36, 4 576 tests exécutés, 0 échec, 0 erreur, 357 sautés (tests PostgreSQL sans serveur, tests Linux sous Windows) ; gate JaCoCo vert (global 0,857 en lignes / 0,736 en branches, 31 scopes nominatifs, scopes Linux et PostgreSQL non applicables), puis relecture par un agent qui n'a produit aucun constat : 9 constats requalifiés (5 sévérités baissées), 9 titres corrigés, 3 dépendances retirées et 6 ajoutées, 1 constat ajouté (AUD-PERF-13). Aucun serveur d'indexation de code n'a été utilisé.

## Notes par axe

Avant de lire les notes : aucune n'est une mesure. Performance (79) compte des ordres de grandeur calculés, pas des durées observées : le dépôt n'a pas de banc pour l'explorateur ni la projection (AUD-PERF-13). Le barème additif pénalise aussi le volume de constats Faibles : Tests perd 8 points pour 8 constats d'hygiène. Un constat à plusieurs axes ne pèse que sur son axe principal. Aucun axe n'a de constat Critique ou Élevé.

| Axe | Note | Moyenne | Faible | Calcul |
|---|---:|---:|---:|---|
| Sécurité | 95 | 0 | 5 | 100 − 4×0 − 1×5 = 95 |
| Qualité | 89 | 2 | 3 | 100 − 4×2 − 1×3 = 89 |
| Tests | 84 | 2 | 8 | 100 − 4×2 − 1×8 = 84 |
| Architecture | 85 | 2 | 7 | 100 − 4×2 − 1×7 = 85 |
| Performance | 79 | 3 | 9 | 100 − 4×3 − 1×9 = 79 |
| Dépendances | 91 | 1 | 5 | 100 − 4×1 − 1×5 = 91 |

Barème : 100 − 25 par Critique − 10 par Élevée − 4 par Moyenne − 1 par Faible, sur les constats non Info, plancher 0.

## Cinq constats majeurs

1. **AUD-PERF-04** (Moyenne, S5) — Caches applicatifs à taille fixe hors du budget de mémoire des vues : corpus hybride 256 Mio, index vectoriel 256 Mio, graphe de programme 256 Mio, projection jusqu'à 512 Mio de fichier, symboles retenus par l'explorateur
1. **AUD-TST-01** (Moyenne, S1) — Les zones nouvelles depuis le 10/10 (minos-explorer 1 291 l., voie WSL2 2 367 l., projection 2D ~1 017 l., QueryViewMemoryBudget 327 l.) ne sont dans aucun scope JaCoCo nominatif : seul le scope global les voit
1. **AUD-QUA-01** (Moyenne, S3) — Les trois surfaces (explorateur, API, MCP) traduisent toute IllegalArgumentException en erreur d'usage sans rien journaliser : un bogue interne s'affiche comme « requête invalide »
1. **AUD-PERF-03** (Moyenne, S4) — Explorateur sur stockage local : liste, détail et voisins chargent et décodent l'index vectoriel entier à chaque requête (la page triée en entier), au-delà d'environ 256 Mio sans cache ni coalescence
1. **AUD-DEP-03** (Moyenne, S2) — Depuis l'abandon de develop, la qualification Docker A→B de 90 min est un check exigé de chaque PR vers main, sans filtre de chemins, PR documentaires et Dependabot comprises : décision écrite dans le workflow, à réévaluer

## Thèmes transverses

- **Les zones nouvelles n'ont pas encore le dispositif du reste du dépôt.** L'explorateur, la projection 2D, la voie WSL2 et le budget mémoire des vues ont été livrés depuis le 10/10 avec leurs tests, mais sans scope JaCoCo nominatif, sans exécution du front, sans banc de mesure et sans test de bout en bout : seul le scope global les voit. C'est pourquoi le sprint 1 précède tout le reste. (AUD-TST-01, AUD-TST-02, AUD-PERF-13, AUD-TST-03, AUD-TST-11)
- **Le budget mémoire couvre les vues, pas ce qui les entoure.** DATA-0081 a borné les vues de requête ; les caches applicatifs voisins (corpus hybride, index vectoriel, graphe de programme) gardent des tailles fixes hors budget, et les deux constats laissés ouverts le 11/11 sont toujours là. Dans la JVM de requête Docker, budget et caches font environ 1 049 Mio pour 1 075 Mio de tas (calcul sur les constantes du code, non mesuré). (AUD-PERF-04, AUD-PERF-05, AUD-PERF-06, AUD-PERF-09, AUD-PERF-03)
- **Aux frontières des surfaces, un échec interne perd sa cause.** Explorateur, API et MCP traduisent toute IllegalArgumentException en erreur d'usage, et aucune de ces surfaces (ni le worker WSL) ne garde de trace de pile : un bogue se présente comme une faute de l'utilisateur, sans rien pour le diagnostiquer. (AUD-QUA-01, AUD-QUA-02, AUD-SEC-02, AUD-SEC-03)
- **Des décisions n'ont pas suivi un changement de contexte.** L'abandon de develop, le passage de main en Java 21 et l'arrivée du clone distant ont laissé derrière eux une qualification Docker exigée sur chaque PR, une exception Java 25 sans objet, un risque R-25 encore ouvert, des commentaires compose contraires à SEC-0074 et un clone hors du périmètre de la décision sur les filtres Git. (AUD-DEP-03, AUD-DEP-04, AUD-DEP-06, AUD-DEP-01, AUD-SEC-01, AUD-ARC-07)
- **L'application reste le lieu où tout s'assemble.** minos-application loge des adaptateurs d'infrastructure qu'elle câble elle-même, sa façade sert de localisateur de services, et minos-app accumule de la politique. Le graphe de modules est propre et gardé ; c'est le contenu des couches qui dérive, ce qu'aucun graphe ne montre. (AUD-ARC-01, AUD-ARC-02, AUD-ARC-03, AUD-ARC-05, AUD-ARC-06)

## Sprints

| Sprint | Titre | Constats | Charge (j) | Critère de sortie |
|---|---|---:|---|---|
| 1 | Remettre la mesure à niveau sur les zones nouvelles | 8 | 4,25 à 14 | Chaque zone créée depuis le 10/10 (explorateur, projection 2D, voie WSL2, budget mémoire des vues) a un scope JaCoCo nominatif exigé ; app.js est exécuté par au moins un test et contrôlé en CI ; benchmarks/scalability produit une référence de durée et de latence pour la projection et les routes de l'explorateur ; aucun test ne passe à vide sur un OS ni ne se saute quand sa précondition échoue ; tout gate a un auto-test câblé ; un clone Windows avec core.autocrlf=true produit des *.sh en LF. |
| 2 | Sécurité et chaîne d'intégration | 8 | 3,5 à 12 | La décision sur les filtres Git au clone distant est écrite (ADR SEC-*), validateCheckout passe par l'ouverture de confiance et un test fixe le comportement retenu ; l'exclusion des noms de secrets s'applique à toute variable transmise à un processus ; la garde Windows refuse sur une entrée illisible ; SONAR_TOKEN n'est dans l'environnement d'aucune étape qui exécute ./mvnw sur le head de la PR, ou une entrée nominative de check-workflow-pins.py l'accepte avec sa raison ; aucune PR documentaire ne déclenche la qualification Docker A→B ; aucun workflow n'installe Temurin 25 ; aucun document courant ni risque ouvert du registre ne cite develop comme branche vivante ; les commentaires compose disent la même chose que SEC-0074. |
| 3 | Diagnostic et robustesse des surfaces | 5 | 2,75 à 9 | Tout échec interne d'une surface (CLI, API, MCP, explorateur, worker WSL) laisse une trace avec la cause racine dans un journal privé sans chemin absolu ni jeton ; une IllegalArgumentException non émise par la validation d'entrée est rendue comme erreur interne ; une requête de l'explorateur abandonnée libère son créneau ; les refus de l'explorateur sont journalisés avec limitation. |
| 4 | Performance de l'explorateur et de la projection | 4 | 2,5 à 8 | Mesuré sur le banc du sprint 1 : les passes d'ajustement de la projection ne relisent que l'échantillon ; aucune page de liste, détail ou voisinage ne décode l'index vectoriel entier en stockage local ; aucun COUNT(*) par page sous PostgreSQL ; le survol de la carte n'est pas linéaire en nombre de points ; chaque gain est mesuré avant/après sur un index réel. |
| 5 | Budget mémoire et PostgreSQL | 4 | 1,75 à 6 | Tout cache applicatif est soit compté dans le budget de mémoire des vues, soit plafonné par une fraction du tas écrite dans l'ADR ; la charge utile PostgreSQL est relue sur (project_id, snapshot_id, sha256) ; une vue hors cache reste comptée jusqu'à sa libération ; la zone de travail PostgreSQL du plan de requête est dimensionnée pour le nombre de JVM. |
| 6 | Voie WSL2 et hygiène des tests | 7 | 2,5 à 9 | La voie WSL2 hache l'arbre source au plus une fois par indexation et lit l'artefact SCIP au plus deux fois ; aucun test n'affirme une absence après un délai fixe ; les classes de la frontière de confiance des providers ont un plancher de branches d'au moins 0,5 ; les classes UI du plugin ont un test ; le document de mutation décrit les modules réels. |
| 7 | Application : sortir les adaptateurs et réduire la façade | 6 | 7 à 20 | minos-application n'instancie plus d'adaptateur d'infrastructure (Ollama, analyseur javac, sidecar, historique CLI) : ils sont câblés par minos-bootstrap derrière un port ; les cas d'usage reçoivent leurs collaborateurs, pas MinosApplication ; minos-app ne porte plus de politique de lancement ; les codes de sortie viennent d'un seul contrat. |
| 8 | Garde de frontières, contrats et documentation | 5 | 4,75 à 14 | Les tables du garde de frontières ont une seule source lue par le garde et par ModuleArchitectureTest ; IndexingRunExecutor n'est plus une classe statique unique et SnapshotBinaryCodecSupport est découpé par section de format ; la langue du contrat de l'explorateur est tranchée par écrit et app.js suit ; minos-nexus est reclassé ou absorbé ; la prose d'architecture (ports du domaine, racines du moteur, explorateur) correspond au code. |

Charge par sprint en bornes basse et haute (S 0,25 à 1 j, M 1 à 3 j, L 3 à 8 j) : c'est une enveloppe, qui ne couvre ni la revue, ni les allers-retours de CI, ni les ADR que demandent les sprints 2, 7 et 8.

## Chemin critique

1. AUD-PERF-13 (S1, effort M) débloque AUD-PERF-01, AUD-PERF-02, AUD-PERF-03 : Le banc benchmarks/scalability ne mesure ni la projection 2D ni les pages de l'explorateur : les constats de performance de cet audit sont des estimations sans référence
1. AUD-TST-02 (S1, effort M) débloque AUD-PERF-10, AUD-QUA-03 : Le front de l'explorateur (app.js, 784 lignes, 45 fonctions) n'est jamais exécuté par un test : seules des recherches de sous-chaînes le vérifient, sans contrôle de syntaxe ni lint en CI
1. AUD-ARC-02 (S7, effort L) débloque AUD-ARC-04, AUD-ARC-07 : MinosApplication sert de localisateur de services : 36 méthodes publiques d'instance, dont 15 rendent un type du moteur et compositionRoot() rend la racine de composition ; les cas d'usage reçoivent l'objet entier et y puisent 14 collaborateurs
1. AUD-ARC-01 (S7, effort L) débloque AUD-ARC-04, AUD-ARC-07 : minos-application embarque et câble elle-même trois adaptateurs d'infrastructure (client HTTP Ollama, analyseur Java fondé sur javac, lecteur de fichiers sidecar), hors de la racine de composition

## Angles morts

- Tests Java exécutés une fois, sous Windows seulement : les 357 tests sautés (PostgreSQL sans serveur, bac à sable Linux, bubblewrap et cgroup) n'ont pas tourné, et leur scope JaCoCo n'est pas mesuré ici ; la CI Linux reste la seule preuve pour eux.
- Pas de graphe d'appels complet (aucun outil d'indexation utilisé, par règle de l'audit) : le code atteint par ServiceLoader ou réflexion est couvert par lecture, pas par traçage.
- Aucune CVE vérifiée pour les dépendances Maven ni pour les images : OSV et l'API GitHub sont refusés par le proxy de l'environnement d'audit ; npm audit est propre sur les deux verrous.
- Comportement à l'exécution non observé : aucune mesure de durée ni de mémoire ; les constats de performance sont calculés. JGit n'a pas été exécuté pour AUD-SEC-01 (le mécanisme est prouvé par un test du dépôt).
- Non exécutés : ArchUnit et règles Archgate, PIT, build Gradle et tests du plugin IntelliJ, scripts PowerShell (pas de pwsh), images Docker, workflows GitHub, état réel des rulesets et des secrets.
- Survolés plutôt qu'audités en profondeur : plugin IntelliJ, installeur Windows et scripts PowerShell, tests adversariaux du bac à sable, montage de la racine réelle pour les providers qui exécutent du code du dépôt (rust-analyzer, scip-dotnet).

## Fichiers

- [`findings.md`](findings.md) et [`findings.json`](findings.json) : tous les constats (sprint, dépendances dans les deux sens).
- [`sprints.md`](sprints.md) et [`sprints-meta.json`](sprints-meta.json) : ordre de correction.
- [`architecture.md`](architecture.md) : découpage observé et graphe.
- [`plan-action.md`](plan-action.md) : classement par urgence.
- [`ETAT-2026-11-27.md`](ETAT-2026-11-27.md) : vérification du 27/11 (statuts, désaccords avec le suivi, notes recalculées).
- [`ETAT-2026-11-25.md`](ETAT-2026-11-25.md) : vérification du 25/11.
- [`ETAT-2026-11-20.md`](ETAT-2026-11-20.md) : vérification du 20/11.
- [`ETAT-2026-11-19.md`](ETAT-2026-11-19.md) : vérification du 19/11.
- [`ETAT-2026-11-17.md`](ETAT-2026-11-17.md) : vérification du 17/11.
