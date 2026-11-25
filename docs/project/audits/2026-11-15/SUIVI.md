# Suivi de l'audit du 15 novembre 2026

Suivi de la correction des constats de [`findings.md`](findings.md), dans l'ordre de [`sprints.md`](sprints.md). Une ligne par constat ; la preuve d'une fermeture est une commande ou une assertion de test, pas « la CI est verte ».

## État au 2026-11-25

Les sprints 1 à 10 sont fusionnés et archivés ; le sprint 10 reprend les quatre constats de la vérification du 20/11 et les deux partiels AUD-DEP-05 et AUD-ARC-02 (PR #809 à #816, archivés le 2026-11-24), dont le groupe 4 d'`injecter-les-collaborateurs-des-cas-d-usage` (retrait de la façade, ARCH-0087 `Accepted`). La vérification du 25/11 ([`ETAT-2026-11-25.md`](ETAT-2026-11-25.md)) ouvre onze constats Faibles, rangés au sprint 11 : **spécifié** le 2026-11-25 en huit changements OpenSpec actifs, aucun implémenté. Les tâches non faites des sprints 1 à 10 sont des mesures et des preuves manuelles, nommées dans la colonne « Statut » de chaque sprint et dans « Tâches manuelles ».

## État au 2026-11-15

Les huit sprints sont **spécifiés et contre-vérifiés** : 40 changements OpenSpec actifs sous `openspec/changes/`, qui couvrent les 47 constats à traiter. Aucun n'est implémenté.

- `openspec validate --all --strict` : 62 éléments valides sur 62 (40 changements, 22 capacités).
- `python scripts/docs/check-capability-dossiers.py` : 22 dossiers pour 22 capacités. `python .claude/scripts/run_gates.py --fast` : 26 gates sur 26.
- Chaque sprint a été relu contre le code du HEAD `89be3fb4` par un vérificateur indépendant de son rédacteur, puis corrigé : une cinquantaine d'erreurs bloquantes (tâche fausse ou inapplicable telle qu'écrite) et une centaine de mineures, toutes reprises sauf une, rejetée après relecture.
- Rédaction et contre-vérification sont des **lectures du code** : aucun build, test ni `archgate check` n'a été exécuté, et aucun chiffre de performance ou de couverture n'est mesuré. Les « rouges attendus au HEAD » des tâches sont déduits, non observés. Chaque changement porte ses tâches de mesure.
- Les corrections n'ont pas été contre-vérifiées une seconde fois.

Statut des constats à la relecture : 39 confirmés (dont AUD-PERF-09, confirmé à la lecture mais non reproduit), 8 partiels (AUD-TST-09, AUD-PERF-13, AUD-DEP-01, AUD-TST-08, AUD-ARC-04, AUD-ARC-09, AUD-QUA-03, AUD-QUA-04), aucun périmé.

## Sprint 1 — Remettre la mesure à niveau sur les zones nouvelles (spécifié, fusionné et archivé le 2026-11-15)

| Constat | Changement OpenSpec | Statut | Écart constaté à l'analyse |
|---|---|---|---|
| AUD-TST-01 | `borner-la-couverture-des-zones-nouvelles` | Fait (PR #739, archivé) | `wsl-lane` omettait `com/minos/cli/WslWorkerLaunch` ; `SemanticCorpusQueryService` et `SemanticIndexBudget` ne sont dans aucun scope ; `ExplorerCommandLine` et `ExplorerLaunchRoute` restent au scope global. Un plancher de classe est un seul nombre (la plus basse des deux mesures moins environ 5 points) et sa classe figure aussi dans `prefixes`. Le changement ajoute `target/m21-quality/**` à l'artefact de `pr-ci.yml`. |
| AUD-TST-02 | `executer-le-front-de-l-explorateur-en-test` | Fait (PR #741, archivé) ; Node v22.23.4 sur le runner `ubuntu-24.04` | Analyse syntaxique des puits infaisable sans dépendance npm : preuve par exécution (`node:vm`, faux DOM). `check-ci-wiring.py` découvre seul tout script à `--self-test` : le câblage suit immédiatement la création du gate. Le rejet du presse-papiers (`app.js:412-413`) tient en deux lignes de code applicatif (une modifiée, une ajoutée) ; la traduction du badge en ajoute huit (table `STATE_BADGE` et son commentaire) et en modifie une. Le badge d'en-tête affiche l'état d'index par un libellé (table `STATE_BADGE`), sans renommer les constantes du contrat. |
| AUD-PERF-13 | `mesurer-la-projection-et-l-explorateur-au-banc` | Fait (PR #740, archivé, constat partiel) ; reste manuel : la référence complète, PostgreSQL compris, sur la machine de référence, et son extrait ici (tâches 2.3, 3.1, 3.2) | Une mesure rejouable de la projection existe hors du banc (`SemanticProjectionMeasurementTest`, local, 256 dimensions). La synchronisation ne construit pas d'index au-delà de 192 Mio de vecteurs ni de 250 000 documents (`SemanticIndexBudget.java:15-19`) : tous les jeux du banc sont synthétiques, écrits directement dans le magasin. PostgreSQL est joint par la configuration du produit, sans test-jar de `minos-storage`. |
| AUD-TST-04 | `compter-comme-sautes-les-tests-propres-a-une-plateforme` | Fait (PR #742, archivé) ; les tests Windows des trois modules sont comptés sautés par `Verify (ubuntu-24.04)` | Cinquième site : `CommandLocatorTest.java:90`. Le « gate du suivi » était un `grep` : un gate est spécifié sur le `return` sans valeur, avec liste fermée de quatre sites légitimes. |
| AUD-TST-05 | `faire-echouer-les-preuves-de-verrou-muettes` | Fait (PR #743, archivé) | Assertions plutôt que « mode requis » ; stderr du fils vidé dans un tampon borné. |
| AUD-TST-07 | idem | Fait (PR #743, archivé) | Le concurrent doit être un autre fil (`FileIndexStateStore` est ré-entrant). Le compteur de verrous retenus n'existe que sur `ProjectIndexLease`. La preuve par copie mutée est manuelle, hors scénario. |
| AUD-TST-09 | `doter-chaque-gate-d-un-auto-test` | Fait (PR #738, archivé, constat partiel) | Dix-huit gates sans auto-test, pas dix : les dix du constat ont un `--self-test` par mutations (`scripts/quality/literal_gate.py`) ; huit autres (`check-minos-01.py`, `check-mnd.py`, `check-mne.py`, `check-p0-p2.py`, `check-post-mne.py`, `check-post228-hardening.py`, `check-audit-remediation-v2.py`, `check-supply-chain.py`) restent dans la liste fermée `GATES_WITHOUT_SELF_TEST` de `check-ci-wiring.py` (suite : les doter ou les retirer). |
| AUD-DEP-02 | `epingler-les-fins-de-ligne-des-scripts-posix` | Fait (PR #737, archivé) ; reste manuel : la remise à niveau du clone principal et des worktrees existants (tâche 3.1) | `git add --renormalize .` ne change rien pour les `*.sh` (déjà LF dans l'index) : renormalisation ciblée sur `mvnw.cmd`. `minos.cmd` passera en CRLF sous Linux, sans consommateur. L'attribut ne corrige pas un clone existant. |

Fusionnés dans l'ordre `epingler-…`, `doter-…`, `borner-…`, `mesurer-…`, `executer-…`, `compter-…`, `faire-echouer-…` (PR #737 à #743), puis archivés.

**Constat de suivi, hors sprint** : la garde `if (!createSymbolicLink(…)) return;` termine en silence 34 tests dans 14 fichiers de `minos-engine` et `minos-storage` quand la création du lien échoue. Elle n'est traitée par aucun changement.

## Sprint 2 — Sécurité et chaîne d'intégration (fusionné et archivé le 2026-11-19)

| Constat | Changement OpenSpec | Statut | Écart constaté à l'analyse |
|---|---|---|---|
| AUD-SEC-01 | `couvrir-le-clone-distant-par-l-ouverture-de-confiance` | Fait (PR #764, archivé) ; option B, ADR SEC-0083 `Proposed` ; reste : rapport JaCoCo agrégé (tâche 2.4) | Un `SystemReader` dédié est un état statique du processus et casserait l'analyse locale : clone sans extraction, ouverture de confiance à deux politiques de filtres, puis extraction. Comportement lu dans les sources de JGit 7.8.1, non observé par un test. La redirection écrit aussi `useJGitBuiltin = false`. Le gate littéral sur `Git.open(` parcourt tout le code principal du module. Rouge observé : 4 tests sur 4 (filtre utilisateur et filtre intégré exécutés au clone, filtre `clean` à la revalidation, propriétaire étranger accepté). `core.autocrlf` s'applique encore à l'extraction (test normalisé). |
| AUD-SEC-05 | `refuser-un-nom-de-secret-herite-et-une-garde-illisible` | Fait (PR #763, archivé) ; reste : rapport JaCoCo agrégé Windows (tâche 2.3) | Filtre dans le code **et** test des listes. `InstallationProcessEnvironment` applique déjà le motif. Rouge observé : 2 tests sur 6 échouaient parce que les variables étaient transmises. |
| AUD-QUA-05 | idem | Fait (PR #763, archivé) ; arbitrage D2 selon le design | Site oublié : `isWindowsSystemRoot` (`WindowsAppContainerWorkerSandboxBackend.java:777-782`). Les scénarios jugent la réponse des deux prédicats, l'appelant lisant l'environnement réel. Rouge observé : 3 tests sur 8, dont l'ancien code levait une exception qui reprenait la valeur de la variable. Les quatre autres appelants de `isWindowsSystemRoot` n'accordent plus rien sur une racine système illisible. |
| AUD-DEP-05 | `separer-le-jeton-sonarcloud-de-la-compilation-de-la-pr` | Fait (PR #749, archivé) ; restes : mesure 1.2, journal du job d'analyse (1.4), type du jeton (2.1) ; repris par le sprint 10 (PR #812, job d'analyse sans code de la PR) | L'analyse est elle-même un but Maven sur l'arbre de la PR : entrée nominative du gate avec raison. L'exigence interdit aussi tout secret dans un `env:` de job ou de workflow, hors l'identifiant Docker Hub. Le comportement de `sonar:sonar` lancé seul n'est pas mesuré : mesure sur une PR d'essai avant la correction, repli `install -DskipTests` prévu. |
| AUD-DEP-03 | `borner-la-qualification-docker-aux-pr-concernees` | Fait (PR #751, archivé) ; D1 portée calculée et D6 selon le design, décision du propriétaire du 2026-11-19 ; restes : durées (1.1), images tirées (3.1), secrets Dependabot Docker Hub (3.2) | Un filtre de chemins laisse un check exigé en attente : portée calculée dans le workflow, liste d'exclusion fermée. Un job exigé à `needs:` porte `if: always()`, contrôlé par `check-ci-wiring.py`. La qualification manuelle se lance toujours sur le SHA à taguer. |
| AUD-DEP-04 | `solder-les-restes-de-java-25-et-de-develop` | Fait (PR #748, archivé) ; reste : journal de la qualification A → B sans JDK 25 (1.4) | Aucun candidat publié n'est en Java 25. La règle Archgate de GEN-0077 lit `PREVIOUS_JAVA` : elle est modifiée dans la même tâche. |
| AUD-DEP-06 | idem | Fait (PR #748, archivé) ; `verify-ruleset.py` contre GitHub rend 0 le 2026-11-19, aucune branche `develop` | Deux phrases oubliées (`STATUS.md:9`, `ROADMAP.md:9`). Deux contrôles ajoutés à `check-current-docs.py`, dans l'auto-test créé par `doter-chaque-gate-d-un-auto-test`. |
| AUD-DEP-01 | `aligner-les-compose-sur-l-absence-de-provider-en-docker` | Fait (PR #750, archivé, constat partiel) ; D2 : toolchains gardées avec leur raison, décision du propriétaire du 2026-11-19 ; reste : plan d'administration relu dans le journal de la qualification (1.3) | Commentaires contraires confirmés, plus `docker/Dockerfile.mcp.release:262-263`. « Toolchains qu'aucun service n'exécute » est contredit : le service `minos-provider-probe` les sonde sur l'image d'administration. Le test est borné au bloc `minos-admin`. |

Ordre : `solder-…`, `refuser-…`, `separer-…`, `aligner-…`, `borner-…` (après mesure et décision), `couvrir-…` (après décision et ADR). `solder-…` (groupe 2) et `couvrir-…` (groupe 3) passent après `doter-chaque-gate-d-un-auto-test`.

## Sprint 3 — Diagnostic et robustesse des surfaces (fusionné et archivé le 2026-11-19)

| Constat | Changement OpenSpec | Statut | Écart constaté à l'analyse |
|---|---|---|---|
| AUD-QUA-02 | `tracer-les-echecs-internes-des-surfaces` (MCP, CLI, API Java, worker WSL) ; `fiabiliser-la-surface-de-l-explorateur` (explorateur) | Fait (PR #757 et #762, archivés) ; ADR SEC-0084 ; reste : échec réel du worker sur une distribution WSL (5.4) | Journal privé non supporté en conteneur (données en lecture seule) ; ligne stderr enrichie partout. Le journal est un fichier privé par échec sous `logs/internal-failures/`, borné à 200 entrées, écrit par les primitives existantes. Les surfaces passent par une façade de `minos-application` (`InternalFailureReporter`) : tables A11 et pom de l'explorateur inchangés. Côté CLI, seuls le cadre commun et le lanceur sont couverts. |
| AUD-QUA-01 | `distinguer-l-erreur-d-usage-de-l-echec-interne` (MCP, API Java) ; `fiabiliser-…` (explorateur) | Fait (PR #759 et #762, archivés) | `UsageException` dans `minos-domain`, sous-classe non finale d'`IllegalArgumentException` (89 clauses `catch` existantes). `DocumentNotFoundException` en hérite : un document inconnu reste `INVALID_REQUEST`. Un genre de symbole inconnu change de réponse côté MCP. `IndexChangedException` et `RefusedException` sont exclues de la trace. |
| AUD-SEC-02 | `fiabiliser-la-surface-de-l-explorateur` | Fait (PR #762, archivé) ; reste : rotation observée sur un conteneur (4.3) | `minos-mcp`, `minos-postgres` et `minos-ollama` n'ont pas de rotation non plus ; lot limité à l'explorateur et pgAdmin. |
| AUD-SEC-03 | idem | Fait (PR #762, archivé) ; borne des lectures abandonnées portée à 8 ; lecture PostgreSQL réellement bloquée non observée | Un `statement_timeout` toucherait la fabrique de connexions partagée : libération du créneau à l'abandon et borne des lectures abandonnées. |
| AUD-SEC-04 | idem | Fait (PR #762, archivé) | Seuls `.` et `..` sont refusés ; une clé de document contient `/`. L'IAE de `URLDecoder` sur un échappement cassé est enveloppée. |

Ordre : `tracer-…`, `distinguer-…`, `fiabiliser-…` (exige les deux premiers).

Avec le journal privé actif, seule une requête en échec interne ajoute un fichier ; réglage absent, rien n'est écrit. Cette lecture de l'exigence « Données inchangées après consultation » est portée par l'ADR à écrire.

## Sprint 4 — Performance de l'explorateur et de la projection (fusionné et archivé le 2026-11-19)

Prérequis non livrés : `mesurer-la-projection-et-l-explorateur-au-banc` (AUD-PERF-13) et `executer-le-front-de-l-explorateur-en-test` (AUD-TST-02). Chaque changement commence par une mesure « avant » et n'écrit aucun gain comme acquis. Les mesures portent sur des index que la synchronisation construit (32 768 documents à 768 dimensions, 98 304 à 256) ; au-delà, les jeux sont synthétiques et dits tels. Le critère de sortie « gain mesuré sur un index réel » n'est tenable qu'à ces tailles.

| Constat | Changement OpenSpec | Statut | Écart constaté à l'analyse |
|---|---|---|---|
| AUD-PERF-01 | `retirer-le-comptage-par-page-des-lectures-postgresql` | Fusionné (PR #760) et archivé ; décision D1 retenue (2026-11-17) ; reste manuel : référence sur la machine de référence, jeu de 100 000 documents | Site oublié : `storedDocument` (`PostgresSemanticReadQueries.java:177-179`). Compte tenu à l'écriture (colonne `document_count`, migration de schéma 6) ; `PostgresSchemaUpgradeFromV4Test` encode la version 5 et est adapté. Plan vérifié par `PREPARE` puis `EXPLAIN (ANALYZE) EXECUTE`, essayé sur PostgreSQL (voir « Réalisé »). **Réalisé** : colonne `document_count` (migration 6) tenue à l'écriture, requêtes de page scindées ; `PostgresSchemaUpgradeFromV1Test` fixait aussi la liste des versions. Plan lu au HEAD : le `COUNT` est un `SubPlan` rejoué à chaque ligne rendue (`loops=201` pour une page de 200). Tests PostgreSQL exécutés contre un serveur réel (407 verts). Mesure sur ce poste (jeu synthétique `local-hash`, 25 000 documents à 768 dimensions, une exécution, bruitée) : préparation de la projection 2 098 s → 163 s pour 43 passes dans les deux cas, liste p50 133 → 7,3 ms, détail 31 → 7,4 ms. Jeu de 100 000 documents non mesuré. |
| AUD-PERF-02 | `ajuster-la-projection-sur-l-echantillon-en-memoire` | Fusionné (PR #761) et archivé ; décision D4 retenue (2026-11-17) ; reste manuel : référence sur la machine de référence et sur un index construit par la synchronisation | Tout échantillon d'un index constructible pèse au plus 96 Mio en `float` et tient dans le budget par défaut : le chemin en mémoire est le cas courant, le parcours en flux le repli sous un budget abaissé. `passes` reste le nombre de parcours de l'index ; le record public `PreparationReport` gagne deux composants. **Réalisé** : copie de l'échantillon en matrice `float` quand il tient dans le budget (2 parcours de l'index au lieu de 43), parcours en flux conservé sinon et annoncé (`strategy`, `iterations`, `limitations`). Mesure sur ce poste (jeux synthétiques `local-hash`, une exécution, bruitée) : local 25 000 documents à 768 dimensions 20,8 → 3,2 s, 98 304 à 256 dimensions 28,6 → 4,7 s, PostgreSQL 25 000 à 768 dimensions 163,5 → 10,2 s ; mémoire de travail rapportée × 2,9 à 4,5 ; en flux sous 64 Mio, 31,1 s (sans changement). Les 40 itérations sont toutes faites sur ces jeux. |
| AUD-PERF-03 | `paginer-l-index-semantique-local-sans-le-charger` | Fusionné (PR #758) et archivé ; D3 retenue, D4 reportée, index d'offsets non retenu (2026-11-17) ; reste manuel : référence sur la machine de référence | Le constat porte sur un index constructible dont le poids en cache dépasse 256 Mio et qui est relu à chaque requête ; l'existence d'un tel index est déduite du calcul du poids, non observée. Les voisins passent par `search`, non couvert par l'action de l'audit. **Réalisé** : liste, détail et voisins parcourent le fichier quand le cache ne tient pas l'index ; décodage complet sous verrou par projet. Mesure sur ce poste (jeux synthétiques `local-hash`, une exécution, bruitée) : le jeu du banc à contenu court tient dans le cache et ne montre pas le défaut ; avec un contenu de 2 000 caractères (option ajoutée au banc), liste 764 → 11 ms, détail 687 → 47 ms, voisins 1 369 → 658 ms (p50) ; sur l'index que le cache tient, le détail avant le premier chargement passe de 8-13 à 22-37 ms. Le jeu local de 100 000 documents n'est plus refusé (`expectRefusal` retiré). |
| AUD-PERF-10 | `rendre-le-survol-de-la-carte-independant-du-nombre-de-points` | Fait (PR #746 ; script de la carte séparé par `decouper-le-script-de-l-explorateur`, PR #747 ; archivés) ; D3 confirmée, décision du propriétaire du 2026-11-19 ; reste : parcours dans un navigateur (4.2) | Le clic et `drawMap` ont le même défaut : la liste filtrée est réallouée à chaque événement et à chaque image. Le cas réel le plus lourd est 250 000 points. Mesure sous Node (jeux synthétiques, canevas 800 × 600) : points examinés par recherche de 250 000 à 214 et de 1 000 000 à 826, recherche de 4,5 ms à 0,04 ms à 250 000 points ; le tracé reste linéaire et inchangé. Relevé complet dans le design du changement. |

Ordre : `paginer-…`, `retirer-…`, `ajuster-…`, `rendre-…`.

## Sprint 5 — Budget mémoire et PostgreSQL (fusionné et archivé le 2026-11-17)

AUD-PERF-05 et AUD-PERF-06 reprennent AUD-PERF-16 et AUD-PERF-17 de l'audit du 10 octobre, ouverts le 11/11 dans [`../2026-10-10/ETAT-2026-10-13.md`](../2026-10-10/ETAT-2026-10-13.md) sans décision.

| Constat | Changement OpenSpec | Statut | Écart constaté à l'analyse |
|---|---|---|---|
| AUD-PERF-05 | `compter-toute-vue-admise-et-lier-le-rapatriement-postgresql` | Fusionné (PR #769, archivé) ; mesure 2.1 non faite ; ADR DATA-0085 Proposed | Avec le filtre `(project_id, snapshot_id, sha256)`, une ligne absente relance la résolution ; `check-mne.py` exige deux chaînes de la branche restructurée. |
| AUD-PERF-06 | idem | Fusionné (PR #769, archivé) ; D1 et D3 selon la recommandation du design, en attente du propriétaire ; mesure 3.1 non faite | Le port rend une vue que l'appelant ne ferme pas : aucun signal de libération. Six tests existants encodent « rechargée à chaque lecture » et sont adaptés. Seuils publiés : la vue tient jusqu'à 131 061 relations sous le plafond du cache et 410 681 sous le budget, à zéro symbole et zéro occurrence. |
| AUD-PERF-09 | `dimensionner-la-zone-de-travail-postgresql-du-plan-de-requete` | Fusionné (PR #770, archivé) ; D1 (2 JVM, `384m`) et D2 selon la recommandation ; restes : ligne de `docker/.env.example`, mesure 3.1 (non reproduit) | « 400 Mio » ne dit pas pour combien de JVM ; contrôle de l'espace disponible plutôt que traduction d'`ENOSPC`. Les scripts de cycle de vie ne reportent pas la variable de taille dans le `.env` régénéré : motif et validation étendus. Le rendu du refus par l'explorateur est hors périmètre. |
| AUD-PERF-04 | `borner-les-caches-applicatifs-par-le-tas` | Fusionné (PR #777, archivé ; mesure 1.1 : PR #775) ; parts du tas et D3 décidées par le propriétaire le 2026-11-17 ; restes : mesure 7.2, recherche en flux d'un index trop lourd (limite déclarée) | Deux caches oubliés (empreintes du provider Java, 128 Mio ; vecteurs de requête, 8 Mio). Somme calculée sur les constantes : 1 185,6 Mio sous PostgreSQL pour 1 075,2 Mio de tas. Le plafond de 256 Mio borne le cache de l'index vectoriel, pas le poids d'un index : une exigence refuse le décodage complet d'un index plus lourd que sa part du tas (ordre de 600 à 700 Mio au budget de construction, calculé, non mesuré). Les symboles d'une page sont lus par parcours, sans index dérivé dans la vue. |
| AUD-DEP-08 | `compter-l-ancien-chemin-d-un-renommage-dans-la-portee` (créé le 2026-11-17) | Fusionné (PR #767, archivé) | Constat relevé le 17/11 après la clôture du sprint 2. La fonction de diff est partagée : la portée du plugin IntelliJ est corrigée avec celle de Docker. `--no-renames` plutôt qu'une analyse de `--name-status`. |

Ordre : `compter-…`, `dimensionner-…`, `borner-…` (sa mesure 1.1 peut tourner tôt). Après le sprint 4, qui touche les mêmes stores.

## Sprint 6 — Voie WSL2 et hygiène des tests (fusionné et archivé le 2026-11-18)

| Constat | Changement OpenSpec | Statut | Écart constaté à l'analyse |
|---|---|---|---|
| AUD-PERF-07 | `reduire-les-lectures-de-la-voie-wsl` | Fait (PR #772, archivé) | La voie utilise l'empreinte du projet enregistré, pas l'empreinte de scope : empreinte racine portée, repli d'une capture par run. |
| AUD-PERF-08 | idem | Fait (PR #772, archivé) | Six lectures côté Windows au HEAD (sept sur entrée identique), trois après : le critère est borné à « au plus deux lectures entre l'acceptation du paquet et son retour », la lecture préalable du manifeste restant hors périmètre. Aucune comparaison d'empreinte au manifeste n'est retirée ; deux relectures du disque le sont, sur décision du propriétaire. |
| AUD-PERF-11 | `normaliser-les-noms-de-tests-lies-sans-recompilation` | Fait (PR #768, archivé) | Test de caractérisation avant réécriture (ordre filtre / minuscules, U+212A). |
| AUD-TST-06 | `prouver-les-tests-temporises-par-un-signal` | Fait (PR #771, archivé) | Le test de cadence garde un fil réel et juge la borne haute sur le temps mesuré par `System.nanoTime()` ; les deux exigences en vigueur restent prouvées. L'égalité des périodes entre plugin et runtime est une limite déclarée. |
| AUD-TST-03 | `relever-les-planchers-de-la-frontiere-des-providers` | Fait (PR #773, archivé) ; D2 : option A (code de chaque plateforme séparé) | Mesurer, tester, puis relever ; un plancher de classe est un seul nombre. Le plancher de 0,50 n'est pas garanti sur les deux systèmes. |
| AUD-TST-08 | `tester-la-logique-d-interface-du-plugin` | Fait (PR #774, archivé), D1 option A | `docs/user/intellij-plugin.md:335` déclare déjà ces classes non couvertes par construction. Hors bornes, un réglage rend la valeur de repli. Seule l'analyse de la chaîne saisie reste à extraire ; quatre classes restent sans test. |
| AUD-TST-10 | `actualiser-le-document-de-mutation` | Fait (PR #776, archivé) ; restent les mesures `code-audit.yml` (manuelles) | Le relevé daté du 8 octobre n'est pas régénéré : seule la source d'état est réalignée. Aucun PIT du plugin sur le poste. |

Ordre : `normaliser-…`, `prouver-…`, `reduire-…`, `relever-…` (après `borner-la-couverture-des-zones-nouvelles` et `refuser-un-nom-de-secret-…`), `tester-…`, `actualiser-…`. Fusionnés dans cet ordre (PR #768 à #776), puis archivés.

## Sprint 7 — Application : sortir les adaptateurs et réduire la façade (fusionné et archivé le 2026-11-19)

| Constat | Changement OpenSpec | Statut | Écart constaté à l'analyse |
|---|---|---|---|
| AUD-ARC-01 | `cabler-les-adaptateurs-embarques-par-la-racine-de-composition` | Fait (PR #785, archivé) ; D1 : adaptateurs en place sous deux tables du garde, décision du propriétaire du 2026-11-19 | Déplacer les classes exigerait de descendre trois ports dans le moteur et de passer à 15 ou 16 modules. Câblage par le composeur **sans déplacement**, deux tables à cliquet ; la table compte 17 classes (`SourceStateKeyMemo` comprise). La liste de production des fournisseurs, relations comprises, est instanciée par `minos-bootstrap`. |
| AUD-ARC-02 | `injecter-les-collaborateurs-des-cas-d-usage` | Fait en partie (PR #787, archivé) ; le groupe 4 est repris et fait par le sprint 10 (PR #816) | Trois types sont d'abord déplacés dans `com.minos.application.indexing`, sans quoi la façade crée un cycle de packages. La façade est ajoutée et les fabriques `of` retirées d'un seul tenant : rupture de classes internes, antérieure à la décision sur les accesseurs publics. Accès des tests par un `test-jar` de `minos-application`. |
| AUD-ARC-03 | `porter-l-historique-d-import-derriere-un-port` | Fait (PR #782, archivé) ; D2 : l'historique reste un fichier de `MINOS_HOME`, décision du propriétaire du 2026-11-19 | L'enregistrement `IndexHistory` existe deux fois. Magasin dans `com.minos.storage.home` ; deux tests existants sont portés dans `minos-storage`. |
| AUD-ARC-05 | `centraliser-la-politique-de-lancement-de-l-explorateur` | Fait (PR #788, archivé) ; D3 : pas de renommage de `McpBackend*`, décision du propriétaire du 2026-11-19 | Renommage de `McpBackendConfiguration` écarté. Une valeur non reconnue de `MINOS_HOSTED_MODE` n'a pas le même message selon le site. `HostedMode` vit dans `com.minos.application`. |
| AUD-ARC-06 | `unifier-le-contrat-des-codes-de-sortie` | Fait (PR #779, archivé) | 28 fichiers de production, 67 occurrences, 31 fichiers de test. `minos-explorer` ne peut pas dépendre de `minos-cli` : test de parité dans `minos-app`. |
| AUD-QUA-04 | `supprimer-les-vues-miroirs-de-la-cli` | Fait (PR #780, archivé, constat partiel) | Deux couples seulement sont des copies strictes ; les cinq autres vues assainissent des diagnostics et passent sous un test de parité. |

Ordre : `unifier-…`, `supprimer-…`, `porter-…`, `cabler-…` (écrit l'ADR en `Proposed`), `injecter-…` (le fait passer `Accepted`), `centraliser-…` (après `fiabiliser-la-surface-de-l-explorateur`). `porter`, `cabler` et `injecter` touchent `MinosApplicationComposer` et son test : fusion dans cet ordre.

## Sprint 8 — Garde de frontières, contrats et documentation (fusionné et archivé le 2026-11-19)

| Constat | Changement OpenSpec | Statut | Écart constaté à l'analyse |
|---|---|---|---|
| AUD-ARC-08 | `reclasser-minos-nexus-en-module-de-contrat` | Fait (PR #783, archivé) ; D1 requalifier et D2 garder `NexusSemanticSignalService`, décisions du propriétaire du 2026-11-17 | Absorber nexus contredirait ARCH-0068 et l'API Java documentée. Quatre règles (deux du garde, deux d'ArchUnit) portent sur surfaces ∪ modules de contrat ; règle « surface ↛ surface » ajoutée. |
| AUD-ARC-04 | `donner-une-source-unique-aux-tables-du-garde-de-frontieres` | Fait (PR #791, archivé, constat partiel) ; source unique `scripts/architecture/module-boundaries.json` | A17 ne compare que `ALLOWED`. Une seule des six tables citées est recopiée ; trois copies non citées ne sont comparées par rien. Les règles Archgate d'ARCH-0058 et d'ARCH-0068 lisent ces tables : chaque lecteur bascule dans la tâche qui retire son littéral. |
| AUD-ARC-09 | `decouper-l-executeur-d-indexation-et-le-codec-de-snapshot` | Fait (PR #784 codec, PR #786 exécuteur ; archivé, constat partiel) ; reste : `SnapshotScalabilityBenchmark` non rejoué | 49 méthodes et 4 constructeurs, non 81 ; 37 et 20 commits confirmés par `git log --oneline --follow`. Codec découpé par responsabilité. Sans delta de spec (comportement constant), deux PR. |
| AUD-QUA-03 | `trancher-la-langue-du-contrat-de-l-explorateur` | Fait (PR #781, archivé, constat partiel) ; option A, ADR ARCH-0086 ; reste : notes de version de la release qui livrera l'explorateur | Les erreurs portent déjà un `code`. La CLI sérialise aussi ces valeurs, et la nature de document entre dans la table. Aucun renommage dans v1. La liste fermée des codes se relève après le sprint 3. |
| AUD-ARC-07 | `aligner-la-prose-d-architecture-sur-le-code` | Fait (PR #792, archivé) ; la route de l'explorateur de `minos-app` est corrigée en prose, non gardée | Les trois « 14 » sont vrais au HEAD ; seul le scénario « 13 modules » de `frontieres-de-modules` est faux. `minos-domain` omet la racine `diagnostics`. |

Ordre : `reclasser-…`, `decouper-…` (lot `minos-engine` après `reduire-les-lectures-de-la-voie-wsl`), `trancher-…`, `donner-…` (après le sprint 7), `aligner-…` (en dernier).

Fusionnés dans l'ordre `reclasser-…` (PR #783), `decouper-…` (PR #784, puis #786), `donner-…` (PR #791), `aligner-…` (PR #792), `trancher-…` (PR #781), puis archivés. La PR #790 relève le JDK de distribution Ubuntu épinglé par `scripts/ci/install-linux-sandbox-toolchain.sh`, remplacé par Ubuntu le 2026-11-18.

## Sprint 9 — Reprises de la vérification du 19/11 (fusionné et archivé le 2026-11-20)

Les deux constats de ce sprint ne figurent pas dans les versions suivies de `findings.md`, `findings.json` et `sprints.md` : ils viennent de la vérification du 19/11 et portent les numéros qui suivent ceux de l'audit. AUD-ARC-11 (faible) : la règle A19 ne liait pas `EMBEDDED_ADAPTER_CLASSES` à `INFRASTRUCTURE_API_USERS`. AUD-PERF-14 (faible, résidu d'AUD-PERF-04) : la `WeakHashMap` de `SemanticCorpusQueryService` retenait la table de symboles d'une vue évincée jusqu'à l'accès suivant.

| Constat | Changement OpenSpec | Statut | Écart constaté à l'analyse |
|---|---|---|---|
| AUD-ARC-11 | `proteger-les-adaptateurs-embarques-contre-le-retrait-de-leur-entree` | Fait (PR #796, archivé) ; D2 option a, décision du propriétaire du 2026-11-20 | Mutation en mémoire des 17 entrées : 4 retraits silencieux, non 1 (`OllamaEmbeddingProvider`, `JavaAstSupport`, `JavaProgramModel`, `FileProgramGraphProvider`). L'inclusion des utilisateurs d'API dans la liste des adaptateurs couvre les trois premiers ; `FileProgramGraphProvider` ne nomme aucune API du JDK et reste à la revue (D2). |
| AUD-PERF-14 | `liberer-la-table-de-symboles-avec-sa-vue` | Fait (PR #797, archivé) ; D1 option C, décision du propriétaire du 2026-11-20 ; mesure du temps d'une page non faite (fixture trop petite, tâche 1.4) | Purger la `WeakHashMap` avant le chargement ne suffit pas : la collecte déclenchée par le chargement suivant libère la vue, pas la valeur. Une table par projet laisse retenue celle du projet évincé. Le service lit les symboles par les index de la vue, sans table propre (D1) ; les deux backends construisent leurs vues par `SnapshotReadView.indexed`. |

Fusionnés dans l'ordre #796 puis #797, puis archivés.

## Sprint 10 — Reprises de la vérification du 20/11, et constats partiels (fusionné et archivé le 2026-11-24)

Les quatre constats du sprint 10 ne figurent pas dans les versions suivies de `findings.md`, `findings.json` et `sprints.md` : ils viennent de la vérification du 20/11 et portent les numéros qui suivent ceux de l'audit. Les partiels AUD-DEP-05 (sprint 2) et AUD-ARC-02 (sprint 7) sont repris ici.

| Constat | Changement OpenSpec | Statut | Écart constaté à l'analyse |
|---|---|---|---|
| AUD-SEC-11 | `refuser-la-disparition-de-la-racine-du-clone` | Fait (PR #811, archivé) ; reste : revue de sécurité manuelle (tâche 1.4) | La racine passe aussi par `postVisitDirectory`, pas seulement par `visitFileFailed` ; un test par `checkpoint()` reste vert au HEAD (contrôle d'existence avant le parcours) ; relancer l'exception d'origine écrirait un chemin absolu. Un frère supprimé pendant la marche n'échoue sur Windows et Linux que si c'est un répertoire. `RemoteCloneBudget` n'est dans aucun scope JaCoCo. |
| AUD-TST-13 | `prouver-le-cablage-de-toutes-les-regles-du-garde` | Fait (PR #814, archivé) ; auto-test du garde de 51 s à 21 s | 10 retraits d'appel silencieux sur 13 règles non espionnées, non 8 ; A2 est déjà protégée par `PomRulesOnRealPomsTest`. Une liste d'espions recopiée ou dérivée des appels de `main()` ne voit ni la règle suivante ni l'appel retiré ; un espion qui délègue ne voit pas une erreur avalée. `gate_runner.py` n'est exercé par aucun auto-test, contrairement à sa docstring. |
| AUD-ARC-12 | `decrire-le-garde-de-frontieres-decoupe` | Fait (PR #810, archivé) ; deux exigences prouvées par commande, aucun gate (D2) | 18 passages dans 6 fichiers, dont le dossier de capacité (écarts E1, E4, E9), l'en-tête `files:` d'ARCH-0058, ARCH-0087 et le skill `minos-literal-gates` ; quatre puces fausses dans ARCH-0068, non deux. |
| AUD-PERF-15 | `mesurer-la-description-des-documents-de-l-explorateur` | Fait (PR #813, archivé) ; page la plus lente 44 ms en médiane, sous le seuil de 250 ms : aucune correction ; restes : snapshot réel, Linux, PostgreSQL (manuels) | Un CHUNK porte le symbole qu'il découpe : seuls les documents FILE paient le tri. Une page ne demande chaque fichier qu'une fois : la mémoïsation par requête ne fait rien gagner. Aucun banc ne mesure ce coût ; `docs/developer/semantic-explorer.md` décrit encore la table retirée. |
| AUD-DEP-05 | `isoler-l-analyse-sonarcloud-du-code-de-la-pr` | Fait (PR #812, archivé) ; job SonarCloud sans code de la PR, 28 rapports importés dont `minos-app` ; restes : PR d'essai comparée, type du jeton, ruleset (manuels) | La PR #749 a séparé les étapes, pas les jobs : `.mvn/maven.config` de la PR est relu à chaque appel du wrapper, et le dépôt local est purgé après l'analyse. `Verify` ne publie pas les classes dont un scanner hors Maven a besoin ; un `sonar-project.properties` venu de la PR serait lu. |
| AUD-ARC-02 | `retirer-la-racine-de-composition-de-la-facade` | Fait (PR #816, archivé) ; ARCH-0087 `Accepted` | Aucun appelant de production hors du package ; 216 appels dans 54 fichiers de test de 7 modules. Rendre les accesseurs package-private ou passer par le `Builder` ne suffit pas. Le report du 2026-11-19 n'est porté par aucune décision acceptée ; `runtimeObservationStore` sert trois tests, non un. |

Fusionnés dans l'ordre #810, #814, #816, #811, #812, #813, puis archivés. La PR #815 rend la construction de l'image d'administration plus tolérante aux 429 de Maven Central (préchargement de scip-java réessayé, distribution Maven reprise sur un miroir lent) ; un 429 qui dure plus de cinq minutes la fait encore échouer.

## Sprint 11 — Reprises de la vérification du 25/11 (spécifié le 2026-11-25)

Onze constats Faibles de la vérification du 25/11, au commit `75e10c68`. Chaque preuve a été relue au HEAD et les mutations rejouées sur des copies, sans build Maven ni Docker : les rouges des tests Java et de l'IT d'empaquetage sont déduits, non observés. `openspec validate --all --strict` : 30 éléments sur 30 ; `check-capability-dossiers.py` : 22 dossiers ; `run_gates.py --fast` : 30 sur 30.

| Constat | Changement OpenSpec | Statut | Écart constaté à l'analyse |
|---|---|---|---|
| AUD-ARC-13 | `tenir-le-contrat-de-la-facade-par-le-garde` | Spécifié ; confirmé | L'option « méthode package-private relayée par l'accès de test » seule ne ferme pas le trou et casse `MinosApplicationTestAccessTest` si le relais garde le nom : relais `decoratedProjectIndexing`, puis A11 étendu aux paramètres en cliquet à zéro. `ProjectInspectionService.view` s'appelle `statusView` au HEAD. `java-api.md:358` présente la méthode comme remplaçant public. Méthode absente de v1.3.0 : aucune rupture publiée. |
| AUD-SEC-12 | idem | Spécifié ; confirmé | Élargir, compléter ou supprimer `<includes>` laisse le garde vert. Les deux contrôles sont retenus, non l'un ou l'autre : le garde lit `<includes>` et refuse une seconde exécution `test-jar` ; l'IT affirme que le jar ne contient, hors `META-INF/`, que la classe d'accès. |
| AUD-ARC-14 | `injecter-les-ports-des-operations-locales` | Spécifié ; partiel (3 classes sur 4) | `LocalProjectSymbolQuery` ne lit qu'un accesseur public. `LocalProjectOperations` lit aussi `projectInspectionService()`. Les constructeurs sont des remplaçants documentés (`java-api.md`, notes de version) : conservés, ils délèguent. Un cliquet sur le nombre d'accesseurs figerait à tort la façade ; le design propose une règle ArchUnit sur les lecteurs. 5 accesseurs sans lecteur, dont `hostedControlPlaneService()` sans aucun appelant. |
| AUD-ARC-15 | `aligner-la-prose-de-la-facade-apres-le-sprint-10` | Spécifié ; confirmé ; `SUIVI.md:7` corrigé par cette section | Deux sites de plus : `ARCH-0042:401` (« ARCH-0087 (Proposed) ») et `docs/developer/public-surfaces.md:12` (stéréotype `composition root`). La mémoire d'agent est en tâche sans exigence (fichier traité comme figé par la spécification courante). |
| AUD-QUA-08 | idem | Spécifié ; confirmé | La rétention ne suit que l'import publié (`LocalProjectOperations.java:140-142`). |
| AUD-ARC-16 | `actualiser-le-dossier-des-frontieres-de-modules` | Spécifié ; confirmé, plus large | E3 cite le mauvais fichier et la spécification exige encore « quatre » ; `quality-gates.md` n'a de puce ni pour A19 ni pour A20 et ne dit rien de `MainWiringTest` ; « A4 à A6 » oublie A1 ; `.claude/rules/java-production.md:11` s'arrête à A16. La question ouverte du dossier sur les étapes CI est périmée. Hors périmètre, pour `/minos:docs-sync` : « 154 cas » de `STATUS.md:19` et `ROADMAP.md:23`. |
| AUD-TST-14 | `deriver-les-modules-de-regles-du-garde` | Spécifié ; confirmé, plus étroit | Avec `from module_boundary_extra import check_extra_rule`, forme d'import du garde, l'auto-test rougit déjà ; le trou vaut pour un module non importé ou importé par `import`. Dérivation par glob de `module_boundary_*.py`, fail-closed sur un glob vide. |
| AUD-DEP-09 | `exiger-l-empreinte-des-distributions-des-wrappers` | Spécifié ; confirmé | `mvnw.cmd:137-150` a le même fail-open que `mvnw`. L'URL `https` et `distributionType=only-script` ne sont pas gardés non plus. La fixture `distributionSha256Sum=00` de l'auto-test cassera avec la règle. |
| AUD-DEP-10 | `verrouiller-l-empreinte-de-maven-dans-l-image` | Spécifié ; confirmé, plus large | `set -ux` et un contrôle déplacé après `unzip` passent aussi. Six `ARG` d'empreinte dans `Dockerfile.mcp.release`, dont le paquet NuGet de scip-dotnet que l'audit omet. `fetch-verified.sh` écarté (contexte de construction, reprise de la PR #815). |
| AUD-DEP-11 | `consigner-les-pieges-des-montees-de-version` | Spécifié ; confirmé | `.gitattributes:36` épingle déjà `*.cmd eol=crlf` en copie de travail ; rien ne garde l'index. Règle générale : tout fichier à `eol=` épinglé est `i/lf`. L'instantané apt reste au skill (date de l'image non lisible hors ligne). |
| AUD-DEP-12 | idem | Spécifié ; confirmé | `code-audit-couverture.md` est un relevé daté, exact à sa date : non modifié. Aucune table de faits ne lit la version du wrapper : règle de `check-toolchain-baseline.py` sur une liste fermée de documents courants. |

**Ordre d'implémentation conseillé.** Façade et garde : `aligner-la-prose-…`, `deriver-les-modules-…`, `tenir-le-contrat-…`, `injecter-les-ports-…`, puis `actualiser-le-dossier-…` (il décrit le garde final). Chaîne d'approvisionnement, indépendante de la première : `exiger-l-empreinte-…`, `verrouiller-l-empreinte-…`, puis `consigner-les-pieges-…`.

**Recouvrements.** `MinosApplication.java` et `docs/user/java-api.md` : `aligner-la-prose-…`, `tenir-le-contrat-…`, `injecter-les-ports-…`. Garde de frontières, `test_check_module_boundaries.py` et dossier `frontieres-de-modules.md` : `deriver-…`, `tenir-…`, `injecter-…`, `actualiser-…`. `check-toolchain-baseline.py`, skill `minos-dependency-update`, GEN-0077 et dossier `socle-d-execution` : `exiger-…` et `consigner-…`. Aucune exigence `MODIFIED` n'est modifiée par deux changements.

**ADR.** Aucun créé. Planifiés en tâche : compléter « Mise en œuvre » et « Compliance » d'ARCH-0087 (`tenir-…`, `injecter-…`) et de GEN-0077 (`exiger-…`, `consigner-…`) ; deux parenthèses de mise en œuvre d'ARCH-0042 (`aligner-…`).

## Recouvrements entre changements

- **Exigences `MODIFIED`** (six, chacune modifiée par un seul changement) :
  - `persistance-snapshots` › « Un snapshot de la taille de ce dépôt est admis au cache de vues » (`compter-toute-vue-admise-…`) ;
  - `frontieres-de-modules` › « Les accesseurs de MinosApplication typés moteur sont tenus par un cliquet » (`injecter-…`) ;
  - `frontieres-de-modules` › « Les surfaces n'assemblent pas le cycle d'indexation » et « Les types du moteur nommés par une surface le sont par liste fermée » (`reclasser-…`) ;
  - `frontieres-de-modules` › « Les types du moteur utilisés par le bytecode d'une surface le sont par liste fermée » (`donner-une-source-unique-…`, à archiver après `reclasser-…`) ;
  - `frontieres-de-modules` › « Les adaptateurs de stockage forment un seul module » (`aligner-la-prose-…`).
  Toutes les autres exigences sont `ADDED`, sans titre en double.
- **`app.js`** : `executer-le-front-…`, puis `rendre-le-survol-…`, puis `trancher-la-langue-…`.
- **`ExplorerSession` / `ExplorerServer`** : `fiabiliser-la-surface-…` avant `centraliser-la-politique-…`.
- **`IndexingRunExecutor.java`** : `reduire-les-lectures-de-la-voie-wsl` avant `decouper-l-executeur-…`.
- **`WslLinuxWorker`** : `tracer-les-echecs-internes-…` et `reduire-les-lectures-…` (rebase du second fusionné).
- **`check-jacoco.py`** : `borner-la-couverture-…` avant `relever-les-planchers-…`.
- **`ProviderProcessEnvironment`** : `refuser-un-nom-de-secret-…` avant `relever-les-planchers-…`.
- **`check-workflow-pins.py` et `release-promotion-gate.yml`** : `solder-les-restes-…`, `separer-le-jeton-…`, puis `borner-la-qualification-docker-…`.
- **Tables du garde de frontières** : `cabler-…`, `injecter-…`, `porter-…`, `centraliser-…` et `reclasser-…` avant `donner-une-source-unique-…`.
- **Migration PostgreSQL** : une seule, numéro 6 (`retirer-le-comptage-par-page-…`).

## ADR à écrire

Aucun numéro n'est réservé : chaque ADR prend le prochain numéro libre de `docs/architecture/adr/README.md` au moment de son écriture.

| Domaine | Sujet | Changement qui le planifie |
|---|---|---|
| SEC | Filtres Git à la matérialisation d'un dépôt distant | `couvrir-le-clone-distant-par-l-ouverture-de-confiance` |
| SEC | Journal de diagnostic privé et opt-in des échecs internes | `tracer-les-echecs-internes-des-surfaces` (écrit : SEC-0084) |
| DATA | ADR consolidé qui remplace DATA-0081 (parts du tas des caches, vue hors cache, zone de travail) | premier changement implémenté du sprint 5 |
| ARCH | Adaptateurs embarqués par l'application et façade réduite | `cabler-…` (`Proposed`), `injecter-…` (`Accepted`) |
| ARCH | Langue des constantes des contrats publics | `trancher-la-langue-du-contrat-de-l-explorateur` |

« Mise en œuvre » à compléter sans réécrire la décision : SEC-0079, GEN-0077, ARCH-0042, ARCH-0057, ARCH-0058, ARCH-0068.

## Décisions du propriétaire

Chaque design donne les options et une recommandation. Au 2026-11-24, toutes sont tranchées selon la recommandation jusqu'au sprint 9, sauf le retrait de `compositionRoot()` et des 15 accesseurs (sprint 7), repris par le sprint 10 ; celles du sprint 10 sont retenues selon la recommandation le 2026-11-24 :

| Sprint | Décision | Recommandation du design |
|---|---|---|
| 1 | Corriger le rejet du presse-papiers d'`app.js` dans un sprint annoncé sans code applicatif | Oui, dans un groupe de tâches isolé |
| 1 | Garder la mesure existante de la projection à côté du banc | Oui, telle quelle |
| 2 | Filtres Git au clone distant : pilotes de l'utilisateur appliqués, ou aucun pilote exécuté | Aucun pilote exécuté (contenu brut, pointeurs LFS) |
| 2 | Qualification Docker : portée calculée, maintien sur toute PR, ou retrait du ruleset | Portée calculée, après mesure de durée |
| 2 | Toolchains et lanceur scip-java de l'image d'administration | Écrire la raison maintenant, instruire le retrait à part |
| 3 | Une `IllegalArgumentException` interne de l'API Java passe de `INVALID_REQUEST` à `EXECUTION_FAILURE` | Oui, avec note de version |
| 3 | Suffixe de référence sur l'erreur générique du MCP | Oui |
| 4 | Colonne `document_count` et migration de schéma 6 (retour arrière non transparent) | Oui |
| 4 | Classer les voisins sans rétention quand le cache ne peut pas tenir l'index | Oui |
| 5 | Sort d'une vue admise au-dessus du plafond du cache | Retenue seule par le store et comptée |
| 5 | Parts du tas par cache, marge de décodage comprise | À fixer après la mesure |
| 5 | Refus explicite d'un index vectoriel plus lourd que sa part du tas | Oui |
| 5 | Variable de taille de la zone de travail reprise par les scripts, ou taille fixe | Reprise par les scripts |
| 6 | Plugin IntelliJ : pas de fixture de plateforme, ou `BasePlatformTestCase` | Pas de fixture |
| 6 | Voie WSL : vérifier les octets écrits plutôt que relire le disque (deux relectures) | Oui, pendant la copie |
| 6 | Classe de la frontière des providers qui n'atteint pas 0,50 sur un système | Ranger le code propre à un système dans un scope dédié |
| 7 | Emplacement des 17 classes d'adaptateur | En place, sous deux tables du garde |
| 7 | Retrait du public de `compositionRoot()` et des 15 accesseurs | Retrait en une version, documenté |
| 8 | Requalifier `minos-nexus` ou l'absorber | Requalifier |
| 8 | Langue du contrat de l'explorateur | Anglais pour toute constante nouvelle, valeurs françaises de v1 figées |
| 9 | Retrait silencieux de l'entrée de `FileProgramGraphProvider` (aucune API du JDK) | Résidu accepté, écrit dans ARCH-0087 |
| 9 | Table de symboles de l'explorateur : lectures de la vue, ou table attachée à la vue | Lectures de la vue, sans table |
| 10 | AUD-SEC-11 : racine du clone refusée dans `visitFileFailed` et `postVisitDirectory`, message sans chemin | Oui, comparaison `equals` à la racine, comme `ProviderWriteQuotaSupervisor` (décision du propriétaire du 2026-11-24) |
| 10 | AUD-TST-13 : liste des règles attendues | Toutes les `check_*` des modules du garde, cliquet des exceptions vide ; auto-test propre de `gate_runner.py` (décision du propriétaire du 2026-11-24) |
| 10 | AUD-ARC-12 : gate contre la récidive de prose | Aucun gate nouveau, commande de la spécification rejouée à la clôture (décision du propriétaire du 2026-11-24) |
| 10 | AUD-PERF-15 : mesurer d'abord ou corriger d'emblée | Mesurer d'abord, corriger au-delà de 250 ms en médiane par un index par fichier déjà trié dans la vue (décision du propriétaire du 2026-11-24) |
| 10 | AUD-DEP-05 : job d'analyse séparé, `workflow_run` ou exception écrite | Job séparé sans code de la PR, scanner autonome épinglé (décision du propriétaire du 2026-11-24) |
| 10 | AUD-ARC-02 : retrait en une version, dépréciation ou report écrit | Retrait en une version, accès de test par un `test-jar`, ARCH-0087 `Accepted` (décision du propriétaire du 2026-11-24) |
| 11 | AUD-ARC-13 : contrat sur les seuls retours, A11 étendu aux paramètres, ou méthode package-private | Méthode package-private relayée (`decoratedProjectIndexing`), puis A11 sur les paramètres en cliquet à zéro ; `Builder` et services hors règle (en attente) |
| 11 | AUD-SEC-12 : contrôle par le garde ou par l'IT | Les deux (en attente) |
| 11 | AUD-ARC-14 : injection, cliquet de nombre ou dette écrite ; lecture d'ARCH-0087 § 2.2 | `Dependencies` injectées, constructeurs publics conservés, règle ArchUnit sur les lecteurs des ports, 5 accesseurs sans lecteur retirés ; ligne de mise en œuvre dans ARCH-0087 plutôt qu'un ADR consolidé (en attente) |
| 11 | AUD-QUA-08 : documenter l'absence de remplaçant ou exposer une rétention à la demande | Documenter (en attente) |
| 11 | AUD-ARC-16 : statu quo, auto-test léger sur le dépôt réel, ou plus aucune liste | Auto-test léger dans `test_check_module_boundaries_documents.py`, sans gate ni étape CI (en attente) |
| 11 | AUD-TST-14 : dériver les modules ou contrôler une liste | Dériver par glob, fail-closed sur un glob vide (en attente) |
| 11 | AUD-DEP-09 : gate hôte ; somme du `gradle-wrapper.jar` | `check-toolchain-baseline.py`, `only-script` exigé, pas de somme pour le jar (validé par `setup-gradle`) ; ligne de mise en œuvre dans GEN-0077 (en attente) |
| 11 | AUD-DEP-10 : test Java ou gate Python ; `fetch-verified.sh` | Règle de `check-tools-manifest.py` sur les `ARG` d'empreinte du catalogue ; `fetch-verified.sh` écarté (en attente) |
| 11 | AUD-DEP-11 et AUD-DEP-12 : gate `i/lf` ; versions de Maven écrites | Règle générale `eol=` → `i/lf` dans `check-posix-script-eol.py` ; plus de numéro de correctif dans les documents, règle sur une liste fermée ; instantané apt par le skill seul (en attente) |

## Tâches manuelles

- **Mesures de référence** : banc complet deux fois sur la machine de référence avec PostgreSQL, avant la première PR du sprint 4 ; mesures avant et après des sprints 4 et 5 ; durée de la qualification Docker ; `sonar:sonar` lancé seul sur une PR d'essai.
- **CI et GitHub** : lecture des rapports JaCoCo des deux jobs `Verify` ; type du jeton SonarCloud ; `verify-ruleset.py` contre GitHub ; secrets Dependabot Docker Hub ; `code-audit.yml` pour `minos-storage`, `minos-explorer` et le plugin.
- **Poste** : remise à niveau du clone et des worktrees après l'attribut `eol=lf` ; échec réel du worker sur une distribution WSL préparée ; rotation des journaux sous Docker ; parcours de la carte dans un navigateur.

## Restes de la spécification

- `docker/.env.example` est cité par une tâche de `dimensionner-la-zone-de-travail-…` sans avoir été lu (lecture refusée sur le poste).
- La lecture du type `test-jar` par le garde de frontières est planifiée par `injecter-…` ; son état au HEAD n'a pas été tranché entre rédacteur et vérificateur.
- L'affectation des 14 méthodes sans phase d'`IndexingRunExecutor` et les noms des trois tables ajoutées au garde par le sprint 7 sont des propositions, à arrêter à l'implémentation.
