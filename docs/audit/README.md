# Audit MINOS — octobre 2026

Audit de code et de spécification du dépôt `minos-code-intelligence`, réalisé le **6 octobre 2026** sur le HEAD `bc1d3421` de `develop`. Il succède à l'[audit de septembre](AUDIT-2026-09.md) (dont il revérifie l'état) et prépare les changements [OpenSpec](../../openspec/changes/) qui précèdent toute correction de code de production.

> **Cet audit n'est pas exhaustif.** Il couvre en profondeur les capacités priorité 1 de [`CAPACITES.md`](CAPACITES.md) et partiellement les autres ; la matrice de couverture et les zones non examinées sont en § 4 et § 5.

## 1. Documents

| Document | Contenu |
|---|---|
| [`CAPACITES.md`](CAPACITES.md) | Carte des 18 capacités auditables (phase 0, antérieure à cet audit ; sa limite « non vérifiée contre le code » est levée par `architecture-actuelle.md`) |
| [`architecture-actuelle.md`](architecture-actuelle.md) | Architecture réelle : contexte et conteneurs C4, dépendances POM exactes, carte des modules, parcours d'indexation, d'interrogation MCP et du plan de contrôle hébergé (diagrammes Mermaid) |
| [`constats.md`](constats.md) | Lecture des qualifications, **fiches détaillées des 7 constats P1**, registre des 101 constats, ajustements et errata |
| [`annexes/`](annexes/) | Rapports d'analyse par capacité (A à G), avec les fiches complètes de **tous** les constats : preuves `chemin:ligne`, comportement attendu et source, cause, correction, test de validation |
| [`plan-de-remediation.md`](plan-de-remediation.md) | Changements préparés, ordre, dépendances, décisions à clarifier, critères de clôture |
| [`openspec/changes/`](../../openspec/changes/) | 8 changements OpenSpec prêts à implémenter (validés en mode strict) |

## 2. Synthèse

**État réel.** MINOS est un moteur de code intelligence local-first mûr et défensif : build vert, ~2 000 tests, gates de frontières et de supply-chain verts, 16 constats clos de l'audit précédent revérifiés sans contradiction. Les défauts trouvés ne remettent pas en cause l'architecture ; ils se concentrent sur **des voies d'échec** (refus, erreurs, récupération après arrêt brutal) et sur **l'honnêteté de ce que les sorties affirment**.

**Constats : 101** — **7 P1, 39 P2, 55 P3, aucun P0.** Répartition des qualifications dans le registre : défauts confirmés, risques, améliorations, décisions à clarifier et dérives de documentation, avec un niveau de vérification par ligne (E exécuté par l'auteur, L relu, R reproduit hors dépôt par l'analyse, A lecture de code).

**Défauts confirmés les plus importants**

1. **MINOS-AUD-B01 (P1, exécuté)** — un refus RBAC dont l'identifiant de ressource n'est pas canonique (espace ou tabulation) corrompt la chaîne d'audit HMAC : tout le tenant devient inutilisable, lectures comprises, après un seul appel d'un membre de rang minimal. Trois tests de reproduction rouges sont dans le dépôt.
2. **MINOS-AUD-B02 (P1, mesuré)** — les refus chaînés sont bornés en nombre mais pas en octets : ≈ 7 906 refus de 4 000 caractères saturent la limite de 32 MiB, avant la réserve de 9 000, et bloquent les écritures autorisées.
3. **MINOS-AUD-C01 (P1, exécuté)** — les erreurs de statut sont opaques : `index-status 'N:\…'` répond `ResolutionException` (CLI) et « MINOS tool execution failed » (MCP). Le symptôme a été observé pendant cet audit sur `minos_index_status`.
4. **MINOS-AUD-C02 (P1, chemin relu)** — l'outil MCP de statut, déclaré lecture seule (ADR 0017), inspecte les runtimes providers à chaque appel (écritures, hachage d'arbres, sonde AppContainer sous Windows).
5. **MINOS-AUD-F01 (P1, relu)** — l'analyse d'impact, les appelants et l'architecture sont aveugles aux occurrences SCIP, et la sortie ne le dit pas (la limite n'est déclarée qu'au niveau du fournisseur).
6. **MINOS-AUD-A01 (P1, relu)** — Windows : le lanceur AppContainer détruit les ACL et le profil d'un autre sandbox encore vivant (équivalent Windows de S3).
7. **MINOS-AUD-C04 (P1 à confirmer)** — plugin IntelliJ sous Windows : le lanceur `minos.cmd` par défaut ne démarre pas (reproduit par reconstitution, non exécuté dans l'IDE).

Autres défauts P2 confirmés et vérifiés par l'auteur : **B07** (`SSLMODE=verify-full` accepté par la politique d'URL PostgreSQL mais ignoré par le pilote : TLS sans vérification de certificat ; test rouge + vérification directe de `Driver.parseURL`), **B03** (refus sans trace) et **G11** (le wrapper Maven est en 3.10.0 alors que la documentation annonce 3.9.x).

**Risques et décisions encore à clarifier** : 12 décisions sont listées dans [`plan-de-remediation.md`](plan-de-remediation.md#6-décisions-à-clarifier) (dérivation des relations d'appel, tolérance aux répertoires illisibles, rejeu du fichier chiffré, droits de l'ADMIN sur l'audit, statuts d'ADR…). Les principaux risques non mesurés : consommation de tas au décodage d'un snapshot (E03), limites `prlimit`/`pids` du sandbox Linux (A03), fraîcheur source/snapshot (F06).

## 3. Méthode

1. **Inspection de l'environnement** : IDE (MCP JetBrains : modules du projet, racines VCS), instructions du dépôt (`openspec/config.yaml` fait office de contrat ; aucun `AGENTS.md` ni `CLAUDE.md` dans le dépôt), état Git, ADR, backlog, audit précédent. OpenSpec 1.14.1 est installé, initialisé, schéma `spec-driven`, `openspec/specs/` vide, aucun changement actif au départ.
2. **Analyse par capacité** : sept analyses en lecture seule (A à G), une par groupe de capacités, chacune produisant des fiches au format imposé et listant ce qu'elle n'a pas examiné. Les analyses ne devaient pas re-signaler les constats clos et devaient distinguer défaut confirmé, risque, amélioration et décision à clarifier.
3. **Vérification indépendante** des constats les plus lourds : tests de reproduction dans le dépôt (B01, B03, B07), commande sur le jar construit au HEAD (C01), lecture des chemins de code clés (A01, C02, F01, G11, C08) et mesure directe du pilote PostgreSQL (B07). Les écarts relevés entre les analyses et le code sont dans l'[errata](constats.md#4-ajustements-de-priorité-et-errata-par-rapport-aux-annexes) : par exemple C08, dont la preuve centrale est fausse (le plan Windows est supprimé avant le lancement de la CLI), a été rétrogradé.
4. **Préparation OpenSpec** : un changement par ensemble cohérent de constats, rédigé selon `openspec instructions` et validé par `openspec validate --all --strict`. Les rédacteurs devaient relire les preuves clés dans le code avant d'écrire, ce qui a corrigé plusieurs fiches (errata).

Le serveur MCP `minos` (index du projet par lui-même) n'a pas pu servir : `minos_index_status` a échoué avec « MINOS tool execution failed » (c'est le constat C01). Les analyses reposent sur la lecture directe du code et des POM, pas sur l'index MINOS.

## 4. Vérifications exécutées

Poste : Windows 10 Pro (10.0.19045), JDK 24.0.1, Maven via le wrapper (`.mvn/wrapper/maven-wrapper.properties` : Maven **3.10.0**), Python 3.13, OpenSpec 1.14.1. Les journaux détaillés étaient dans le scratchpad de session (non versionné).

| Vérification | Commande / configuration | Résultat |
|---|---|---|
| Build et tests complets | `./mvnw -B -ntp clean verify` (journal hors du `target/` racine) | **BUILD SUCCESS**, 17 min 33 s, 15 modules, ≈ 2 032 tests exécutés (somme des lignes de synthèse des modules), 0 échec, 0 erreur, 54 sautés (motifs non analysés) |
| Gate de couverture JaCoCo | `python scripts/quality/check-jacoco.py --skip-scope m30-postgresql-pgvector` (comme le job Windows de `pr-ci.yml`) | **SUCCESS**, tous les scopes PASS, `m24-polyglot-provider-platform` compris (la mémoire du projet le donnait rouge sous Windows : **non observé ici**) |
| 19 gates statiques du job `invariants` | `check-workflow-pins`, `check-single-execution`, `check-image-pins`, `check-compose-limits`, `check-tools-manifest`, `check-module-boundaries` (+ auto-test), `check-private-io`, `check-current-docs`, `product-facts --check`, `check-milestone-artifact-references`, `check-partial-result-consumers`, `check-mnd`, `check-mne`, `check-post-mne`, `check-post228-hardening`, `check-audit-remediation-v2`, `check-p0-p2`, `check-minos-01` | **19 / 19 rc = 0** |
| Reproduction B01 + B03 | `./mvnw -B -ntp -pl minos-engine -am test -Dtest=AuditReproHostedDenialChainTest -Dminos.audit.repro=true -Dsurefire.failIfNoSpecifiedTests=false` | **3 échecs sur 3**, comme attendu (`hosted audit event authentication failed` ; `IllegalArgumentException` pour l'identifiant de 5 000 caractères) |
| Reproduction B07 | idem avec `-pl minos-storage-postgresql` et `AuditReproPostgresJdbcUrlPolicyTest` | **2 échecs sur 2**, comme attendu |
| Tests de reproduction sans la propriété | idem sans `-Dminos.audit.repro=true` | 3 sautés, build vert |
| Pilote PostgreSQL | `org.postgresql.Driver.parseURL` (pgjdbc 42.7.13) sur `sslmode`, `SSLMODE` et `ssl%6Dode` | seule la clé minuscule est reconnue ; les deux autres donnent `SSLMODE=null` |
| Reproduction C01 | `java -jar target/minos-code-intelligence-1.3.0-SNAPSHOT-all.jar index-status <réf>` avec `MINOS_HOME` isolé | nom inconnu → `unknown project: …` ; chemin → `ResolutionException` (code 1) |
| OpenSpec | `openspec validate --all --strict` | **8 / 8 valides** |

**Tests volontairement rouges au moment de l'audit** (sautés par défaut, rouges avec `-Dminos.audit.repro=true`) :
`minos-engine/src/test/java/com/minos/hosted/AuditReproHostedDenialChainTest.java` (3 tests, **depuis convertis en tests de régression et supprimés**, voir § 9) et `minos-storage-postgresql/src/test/java/com/minos/storage/postgresql/AuditReproPostgresJdbcUrlPolicyTest.java` (2 tests, **depuis renommé `PostgresJdbcUrlPolicyTest` et rendu actif par défaut**, voir § 9). Ils n'utilisent pas `@Disabled` (`check-minos-01.py` l'interdit sur ses fichiers) mais `@EnabledIfSystemProperty(named = "minos.audit.repro", matches = "true")`. Chaque changement OpenSpec concerné a une tâche qui les fait passer puis retire la condition.

**Limites de l'environnement, à ne pas confondre avec des défauts** : poste Windows seul (aucun test Linux/WSL, bubblewrap, cgroup v2 ni Docker n'a été exécuté dans ce travail) ; pas de base PostgreSQL réelle ; pas de Gradle (le plugin IntelliJ n'a pas été construit ni testé) ; aucune CI déclenchée ; aucun diagnostic d'inspection IDE (`get_file_problems`) n'a été exploité.

## 5. Couverture

| # | Capacité (`CAPACITES.md`) | Niveau | Analyse |
|---|---|---|---|
| 1 | Confinement du code non fiable | Approfondi (Linux et Windows par lecture ; non exécuté) | A |
| 2 | Plan de contrôle tenant hébergé | Approfondi, reproduit | B |
| 3 | Stockage privé et secrets | Approfondi pour les primitives de snapshot et de secrets ; partiel pour le reste des E/S | B, E |
| 4 | Indexation distante | Approfondi (code dormant, ADR 0041) ; sources JGit non lues | A |
| 5 | Surfaces publiques versionnées | Approfondi pour MCP/API/plugin ; partiel pour la CLI (la plupart des commandes non lues en détail) | C |
| 6 | Cycle de vie de l'indexation | Approfondi | D |
| 7 | Persistance des snapshots | Approfondi (local) ; PostgreSQL partiel | E |
| 8 | Racine de composition et frontières | Approfondi | E |
| 9 | Runtime et distribution | Partiel (packaging, Docker, scripts d'installation et workflows autres que `pr-ci.yml` non examinés) | A, G |
| 10 | Program graph et architecture | Partiel (analyses de programme, taint, CFG non lues) | F |
| 11 | Providers SCIP | Approfondi pour l'ingestion ; runtimes gérés partiels | F |
| 12 | Client IntelliJ | Partiel (lanceur, protocole ; UI non examinée) | C |
| 13 | Modèle de connaissance | Partiel | F |
| 14 | Découverte et négociation | Approfondi | D |
| 15 | Requêtes bornées | Approfondi | F |
| 16 | Couche sémantique | Partiel | F |
| 17 | Tests liés et impact | Approfondi | F |
| 18 | Observations runtime | Peu examiné (une seule mention, F03) | F |

## 6. Zones non examinées ou insuffisamment vérifiées

- **Exécution** : aucun test Linux, WSL, Docker ni PostgreSQL réel ; aucune mesure de performance ni de tas (E03, D02, D03 reposent sur le code et les mesures de l'ADR 0047) ; plugin IntelliJ non construit ; CI non déclenchée.
- **Reproductions manquantes** : 85 des 101 constats ne sont établis que par lecture de code (niveau « A » du registre) ; les 8 reproduites hors dépôt (« R ») et les 4 exécutées (« E ») sont repérées dans [`constats.md`](constats.md#3-registre-complet).
- **Code non lu** : analyse de programme (taint, CFG, def-use), codec TSV et stores du runtime dynamique, provider AST Java, backends PostgreSQL/pgvector hors sécurité et persistance d'état, `PostgresSemantic*`, scripts PowerShell de lancement, gestionnaires d'installation de providers, JGit (limites de pack, redirections), `NexusSemanticSignalService`, la majorité de `TeamCommand`/`IndexCommand`/`RemoteIndexCommand`, les autres façades API, l'UI du plugin, la concurrence des handlers MCP, `RunDirectoryRetention` et `WindowsJobObjectProcessOwnership` en détail.
- **Documentation** : les ADR ont été confrontés au code sur leur section Décision (57 lus) ; Conséquences, Alternatives et Validation ne l'ont été que pour 0036, 0037, 0039, 0040 et 0041. Les PR et issues GitHub, les mesures et SHA cités n'ont pas été vérifiés.
- **Fiabilité des analyses** : les fiches des annexes ont été relues par échantillonnage, pas ligne à ligne ; plusieurs erreurs ont été trouvées par les rédacteurs OpenSpec en relisant les preuves (voir l'errata). Des erreurs résiduelles sont possibles dans les constats de niveau « A ».

## 7. Premier changement recommandé

[`fiabiliser-chaine-audit-tenant`](../../openspec/changes/archive/2026-10-07-fiabiliser-chaine-audit-tenant/proposal.md) : seul défaut P1 déjà démontré par des tests rouges dans le dépôt, correctif local sans ADR préalable, et il referme une promesse centrale (refus audités et bornés) de l'ADR 0035. Justification complète et alternative (placer `diagnostiquer-statut-mcp-et-erreurs` en premier) dans [`plan-de-remediation.md`](plan-de-remediation.md#3-premier-changement-recommandé--fiabiliser-chaine-audit-tenant).

## 8. Ce que cet audit ne change pas

L'audit lui-même ne modifie aucun code de production : seules deux classes de reproduction ont été ajoutées. Aucun ADR n'est créé ni modifié. Rien n'est déployé ; les corrections de la section 9 sont proposées par PR, non fusionnées.

## 9. Suivi des corrections

- **2026-10-06 — `fiabiliser-chaine-audit-tenant`** : MINOS-AUD-B01, B02, B03 et B04 corrigés **en local** (code de production et tests modifiés, PR ouverte, CI de la PR à observer). `clean verify` vert, 20 gates et gate JaCoCo verts. Preuves : [`constats.md`, § 6](constats.md#6-suivi-des-corrections) et section « Évidence d'implémentation » des [tâches](../../openspec/changes/archive/2026-10-07-fiabiliser-chaine-audit-tenant/tasks.md). Reste ouvert : CI Ubuntu/Windows, quatre décisions de l'utilisateur.
- **2026-10-07 — `diagnostiquer-statut-mcp-et-erreurs`** : MINOS-AUD-C01, C02, C03 (avec D02), C05, C06 et C16 corrigés **en local** (PR ouverte, CI de la PR à observer). `clean verify` vert (2 119 tests), 21 gates et gate JaCoCo verts ; `mcp-all-tools.golden` régénéré (5 lignes, voulues). Preuves : [`constats.md`, § 6](constats.md#6-suivi-des-corrections) et « Évidence d'implémentation » des [tâches](../../openspec/changes/archive/2026-10-07-diagnostiquer-statut-mcp-et-erreurs/tasks.md). Reste ouvert : CI Ubuntu/Windows, quatre décisions de l'utilisateur dont la valeur de `runtimeState`.
- **2026-10-07 — `declarer-limites-impact-scip`** : MINOS-AUD-F01 (lot 1 : limites déclarées), F02 (classification polyglotte des tests) et F04 (filtre par module refusé explicitement, puis modules attribués à l'indexation autonome) corrigés **en local, non commités**. `verify` vert (2 182 tests), 27 gates et gate JaCoCo verts ; cinq goldens régénérés de façon additive. La dérivation occurrence→relation (fond de F01) et F03 restent des décisions ouvertes. Preuves : [`constats.md`, § 6](constats.md#6-suivi-des-corrections) et « Évidence d'implémentation » des [tâches](../../openspec/changes/archive/2026-10-07-declarer-limites-impact-scip/tasks.md). Reste ouvert : réindexation réelle, CI Ubuntu/Windows, PR.
- **2026-10-07 — `isoler-recuperation-appcontainer-par-proprietaire`** : MINOS-AUD-A01 (le lanceur AppContainer ne détruit plus le sandbox d'un autre processus vivant : la propriété est un verrou exclusif du système d'exploitation) et A02 (borne de vie de 24 h sur les résidus de run mort) corrigés **en local**. Preuve de A01 sur le vrai lanceur Windows (`WindowsAppContainerRecoveryOwnershipTest`) ; `verify` vert sous Windows (2 211 tests), 27 gates et gate JaCoCo verts. A07 reste une décision ouverte. Preuves : [`constats.md`, § 6](constats.md#6-suivi-des-corrections) et « Évidence d'implémentation » des [tâches](../../openspec/changes/archive/2026-10-07-isoler-recuperation-appcontainer-par-proprietaire/tasks.md). Reste ouvert : Linux, job `windows-2022`, PR.
- **2026-10-07 — `corriger-lancement-plugin-intellij-windows`** : MINOS-AUD-C04 corrigé **en local** : le lanceur `minos.cmd` par défaut du plugin IntelliJ démarre sous Windows (chaîne de commande transmise brute à `cmd.exe`, dans le même Job Object), et les arguments qu'un `.cmd` ne peut pas recevoir (`"`, `%`) sont refusés avant tout démarrage. Preuve par un vrai `.cmd` (rouge au HEAD avec le message de la fiche) via un harnais local `javac` + JUnit, **sans Gradle ni CI** ; 26 gates verts. C07, C08, C09 restent des décisions ouvertes. Preuves : [`constats.md`, § 6](constats.md#6-suivi-des-corrections) et « Évidence d'implémentation » des [tâches](../../openspec/changes/archive/2026-10-07-corriger-lancement-plugin-intellij-windows/tasks.md). Reste ouvert : build Gradle et CI du plugin, exécution dans une IDE réelle.
- **2026-10-07 — `durcir-configuration-postgresql-et-secrets`** : MINOS-AUD-B07 (la politique d'URL PostgreSQL comparait la clé `sslmode` décodée et en minuscules alors que le pilote l'ignore, d'où un TLS sans vérification ; **l'installateur Windows avait un défaut de plus**, trouvé à l'exécution), B08 (BOM UTF-8), B13 (bouclage IPv6) et B09 (publication qui écrasait) corrigés **en local**. Test de parité contre le vrai pilote ; `verify` vert sous Windows (2 242 tests), 26 gates et JaCoCo verts, sans ignorer `m30-postgresql-pgvector`. Preuves : [`constats.md`, § 6](constats.md#6-suivi-des-corrections) et « Évidence d'implémentation » des [tâches](../../openspec/changes/archive/2026-10-07-durcir-configuration-postgresql-et-secrets/tasks.md). Reste ouvert : CI Ubuntu et Windows, BOM du secret source de l'installateur.
- **2026-10-07 — `tolerer-repertoires-illisibles-a-la-decouverte`** : MINOS-AUD-D01 (un répertoire illisible, même ignoré ou durci, faisait échouer la découverte, l'empreinte **et la copie de travail du provider**, ce que la fiche ne mentionnait pas), D10 (BOM d'un fichier d'ignore) et D06 (l'empreinte précédait la découverte) corrigés **en local** ; un répertoire non ignoré reste fail-closed avec un message actionnable. `verify` vert sous Windows (2 271 tests), 26 gates et JaCoCo verts ; tests d'illisibilité réellement exécutés (compte non élevé). D12 et la casse restent des décisions ouvertes. Preuves : [`constats.md`, § 6](constats.md#6-suivi-des-corrections) et « Évidence d'implémentation » des [tâches](../../openspec/changes/archive/2026-10-07-tolerer-repertoires-illisibles-a-la-decouverte/tasks.md). Reste ouvert : Linux, CI.
- **2026-10-07 — `reconcilier-documentation-courante`** : MINOS-AUD-G01, G03 (citation), G09, G10, G11 et G12 à G19 corrigés **en local**, documentation seule : wrapper Maven 3.10.0 (et `docker-runtime.md`, que la fiche ne mentionnait pas, ne dit plus l'image identique au wrapper), ligne de développement 1.3.0-SNAPSHOT, ADR 0039 hors des travaux en conception, workflow Post-228 retiré, trois jobs de `pr-ci.yml`, README aligné sur l'ADR-0042 et les releases 1.1.0 et 1.2.0, 14 modules dans arc42, index arc42/09 complété (57 ADR), dix liens morts réparés et sept diagrammes manquants créés (relus contre le code). Aucun fichier ADR modifié ; le statut de l'ADR-0036 (G02) est aligné sur son fichier sur décision de l'utilisateur ; les bandeaux et statuts d'ADR (G04 à G08, G20) restent des décisions ouvertes. Les trois gates documentaires et 18 scripts de contrôle sont verts. Preuves : [`constats.md`, § 6](constats.md#6-suivi-des-corrections) et « Évidence d'implémentation » des [tâches](../../openspec/changes/archive/2026-10-07-reconcilier-documentation-courante/tasks.md). Reste ouvert : CI de la PR.
- Les deux tests de reproduction ont été convertis en tests de régression exécutés par défaut : `AuditReproHostedDenialChainTest` (supprimé) et `AuditReproPostgresJdbcUrlPolicyTest` (B07, renommé `PostgresJdbcUrlPolicyTest`, sans condition `minos.audit.repro`). Aucun test de reproduction de l'audit ne reste.

## 10. Audit outillé du 8 octobre 2026

Les cinq outils SpotBugs, PIT, ArchUnit, OWASP Dependency-Check et Gitleaks ont été exécutés au HEAD `902e2bfa` ; **l'audit est incomplet** (PIT : 3 modules sur 14, interrompu par un incident d'environnement, constat H23). Couverture par module et par outil, registre des exécutions, blocages et point de reprise : [`docs/quality/code-audit-couverture.md`](../quality/code-audit-couverture.md). Constats `MINOS-AUD-H01` à `H23`, revérification des familles A à G au HEAD, plan de correction et changements OpenSpec : [`docs/quality/code-audit-constats.md`](../quality/code-audit-constats.md) § 5 à § 8.

**Mise à jour du suivi (§ 9)** : les correctifs décrits ci-dessus comme « en local » ou « PR ouverte » sont fusionnés dans `develop` (commits `82dc354b`, `f87f2250`, `9bc1129d`, `9f3e2911`, `14596db2`, `93e10c3a`) ; le § 6 de [`constats.md`](constats.md) sera mis à jour par le changement `corriger-documentation-plugin-et-suivi-audit` (constat H11).
