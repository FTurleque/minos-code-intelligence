# Audit outillé de MINOS — couverture, exécutions et reprise

Audit du **8 octobre 2026** avec les cinq outils SpotBugs, PIT, ArchUnit, OWASP Dependency-Check et Gitleaks, sur tout le périmètre applicable du dépôt. Ce document contient l'état de référence, l'inventaire, la matrice de couverture, le registre des exécutions et le point de reprise. Les constats qualifiés et le plan de correction sont dans [code-audit-constats.md](code-audit-constats.md) (§ 5 à § 8), la méthode et les commandes dans [code-audit.md](code-audit.md). Ce document complète l'[audit d'octobre](../audit/archive/2026-10-06/README.md) sans le remplacer.

> **Lecture.** Pour chaque outil, ce document sépare quatre choses : l'outil a été **exécuté** ; le **périmètre réellement analysé** (compté, pas supposé) ; les **résultats** obtenus ; les **conclusions démontrées**. Un build vert n'est jamais pris pour une couverture. « Couverture intégrale » n'est employé nulle part : chaque outil a son périmètre et ses limites, écrits ci-dessous.

## 1. État de référence

| Élément | Valeur |
|---|---|
| Commit audité | `902e2bfa66b8f7a9d3694edad9b032b0d8c7f9c3` (`develop`, 2026-10-08 00:21 +02:00) |
| Modifications locales pendant l'audit | configuration d'audit uniquement : `pom.xml`, `minos-app/pom.xml`, `.github/workflows/code-audit.yml`, `scripts/quality/audit-report-summary.py`, `minos-app/src/test/java/com/minos/app/architecture/ModuleArchitectureTest.java`, documents de `docs/quality/` et `docs/audit/archive/2026-10-06/README.md`, changements OpenSpec. **Aucun code de production modifié.** `Claude outputs/` (non suivi, à l'utilisateur) laissé intact |
| Poste | Windows 10 Pro 10.0.19045, AMD Ryzen 7 5700G (8 cœurs / 16 fils), 48 Go |
| JDK | OpenJDK 24.0.1 |
| Maven | wrapper 3.10.0 |
| Python | 3.13.7 (PyYAML 6.0.2) |
| PowerShell | 7.6.6 |
| Git | 2.51.0.windows.2 |
| Docker | Docker Desktop, moteur 29.7.2 (Testcontainers PostgreSQL/pgvector réellement démarré) |
| OpenSpec | 1.14.1 |
| IntelliJ IDEA | `IU-261.22158.277` installé localement (plateforme cible du plugin) ; Gradle **absent** |
| Base NVD (Dependency-Check) | `NVD API Last Modified` 2026-10-08 07:16:33 UTC, vérifiée à 09:18 +02:00 ; mise à jour **anonyme** (12.2.2), la clé `NVD_API_KEY` de l'environnement étant refusée par le NVD |
| Outils | SpotBugs 4.10.4 (plugin Maven 4.10.4.1), PIT 1.30.0 + `pitest-junit5-plugin` 1.2.3, ArchUnit 1.5.1, Dependency-Check 13.0.0 (analyse) et 12.2.2 (mise à jour), Gitleaks 8.30.1 |

## 2. Inventaire

### 2.1 Modules et composants

| Composant | Build | Sources prod (`.java`) | Classes compilées | Fichiers de test | Tests exécutés (sautés) |
|---|---|---|---|---|---|
| `minos-domain` | Maven | 37 | 43 | 6 | 22 (0) |
| `minos-engine` | Maven | 169 | 309 | 110 | 656 (10) |
| `minos-runtime-local` | Maven | 37 + 17 scripts PowerShell embarqués | 96 | 60 | 338 (38) |
| `minos-storage-local` | Maven | 31 | 64 | 64 | 221 (4) |
| `minos-provider-scip` | Maven | 39 + 1 script PowerShell embarqué | 66 | 39 | 123 (0) |
| `minos-integration-git` | Maven | 6 | 23 | 7 | 33 (1) |
| `minos-application` | Maven | 108 | 229 | 40 | 137 (0) |
| `minos-storage-postgresql` | Maven | 18 (schéma SQL dans `PostgresSchemaMigrator`) | 38 | 19 | 78 (0), PostgreSQL réel via Docker |
| `minos-bootstrap` | Maven | 6 | 8 | 45 | 117 (3) |
| `minos-nexus` | Maven | 4 | 15 | 3 | 10 (0) |
| `minos-cli` | Maven | 38 | 95 | 73 | 287 (0) |
| `minos-api` | Maven | 13 | 95 | 19 | 84 (0) |
| `minos-mcp` | Maven | 9 | 29 | 20 | 56 (0) |
| `minos-app` (assemblage, jar ombré) | Maven | 7 | 16 | 39 (+1 ArchUnit) | 102 (0) + 7 Failsafe ; + 7 ArchUnit |
| **Réacteur** | | **522** | **1 126** | | **2 271 (56)** au `clean verify` de référence |
| `minos-intellij` (plugin IntelliJ) | **Gradle**, hors réacteur, Java 21 | 24 + 1 script PowerShell embarqué | 68 (recompilées) | 15 | 71 (8, réservés Unix) par le harnais |
| Agrégateur `minos-parent` | Maven `pom` | 0 | 0 | 0 | — |

Aucun module n'est activé par profil : les deux profils de module (`windows-docker-desktop-testcontainers` dans `minos-bootstrap` et `minos-storage-postgresql`, `jacoco-aggregate-report` dans `minos-app`) changent la configuration des tests ou des rapports, pas la liste des modules. Aucun code généré (`target/generated-sources` vide), aucune source tierce copiée dans les modules.

Le projet ouvert dans IntelliJ (MCP JetBrains) déclare 15 modules dont un doublon `minos-code-intelligence (1)` ; **`minos-app` et `minos-intellij` n'y figurent pas** : l'IDE ne peut pas servir de référence d'inventaire (configuration locale, `.idea/` ignoré par Git).

### 2.2 Hors modules

| Catégorie | Contenu | Remarque |
|---|---|---|
| Scripts PowerShell | 100 `.ps1` suivis (installation, release, qualification, lanceurs), 14 fragments `.ps1frag` et 2 gabarits embarqués dans `minos-runtime-local` | 101 analysables seuls ; les fragments et gabarits ne s'analysent qu'une fois assemblés par le code Java |
| Scripts shell | 11 `.sh` | tous avec `set -e` ; quatre ont le mode Git 100644 mais sont toujours appelés par `bash …` |
| Lanceurs | `minos.cmd` (racine), `minos.cmd` et `minos-mcp.cmd` générés par `build-windows-distribution.ps1`, `scripts/intellij/run-minos.ps1`, `docker/scripts/*` | |
| Python | 58 scripts (gates de qualité, release, remédiation) et leurs auto-tests | |
| Workflows | 12 workflows GitHub Actions + `dependabot.yml` | |
| Docker | `docker/Dockerfile.mcp`, `docker/Dockerfile.mcp.release`, `docker/compose-mcp.prod.yaml`, `docker/compose-mcp.connected.yaml` | |
| Installateur | `packaging/windows/minos-installer.iss.template` (Inno Setup) | |
| Persistance | schéma PostgreSQL versionné à la main (`schema_version`, `CURRENT_VERSION = 4`) dans `PostgresSchemaMigrator.java` ; snapshots locaux par `DurableAtomicFile` | aucune migration Flyway/Liquibase, aucun fichier `.sql` |
| Code embarqué ou tiers | outils SCIP téléchargés à la release (`embedded-tools.json`, `tools-manifest`), jar ombré de `minos-app` | |
| Projets d'exemple | `fixtures/` (Java, TypeScript, Python, Gradle, Kotlin, polyglottes) | données de test indexées, pas du code de MINOS |
| Java hors modules | sources de scripts historiques (`scripts/history`, `scripts/m14`, `m15`, `m26`, `m27`) et une classe `docker/scripts` | non compilées par le réacteur |

### 2.3 Dépendances

| Vue | Nombre | Source |
|---|---|---|
| Livrées, agrégées sur le réacteur | 40 (13 modules MINOS + 27 artefacts tiers) | Dependency-Check, profil `audit-dependency-check` |
| Avec la portée test | 68 (dont 28 propres aux tests) | profil `audit-dependency-check-tests` |
| Plugin IntelliJ (`build.gradle.kts`) | 10 (gson 2.14.0 livré ; JUnit 6.1.3 et sa plateforme en test) | POM de reconstitution hors dépôt |
| Non couvertes par ces vues | plugins Maven et leurs dépendances, plugin Gradle `org.jetbrains.intellij.platform` 2.19.0, plateforme IntelliJ (fournie par l'IDE), images Docker, outils SCIP embarqués, dépendances Python (aucun fichier `requirements`) | |

Le jar ombré embarque `slf4j-api` 2.0.20 ; les arbres des modules intermédiaires résolvent 2.0.16 (`minos-mcp`) et 2.0.18 (`minos-integration-git`, `minos-cli`, `minos-api`), et l'agrégat Dependency-Check voit aussi 1.7.36 (constat H05).

### 2.4 Historique Git

| Référence | Disponible | Commits |
|---|---|---|
| Clone de travail | **superficiel** (`.git/shallow`, commit le plus ancien du 2026-10-02) : `develop`, `main`, 3 références distantes, 8 tags, `refs/stash` ; un worktree détaché `minos-wt/verif-cli` | 3 460 accessibles |
| Clone miroir hors dépôt (`git clone --mirror`) | 2 branches, **293 `refs/pull/*`**, 8 tags ; non superficiel | 4 064 |
| Indisponible | branches supprimées sans référence restante, forks, journaux et artefacts GitHub Actions, commentaires de PR et d'issues, pièces jointes de release | — |

## 3. Matrice de couverture

Statuts : **exécuté** (périmètre compté), **partiel**, **bloqué**, **non applicable** (N/A), **non exécuté**.

### 3.1 Vue d'ensemble

| Composant | SpotBugs | PIT | ArchUnit | Dependency-Check | Gitleaks |
|---|---|---|---|---|---|
| 13 modules de code du réacteur | exécuté | 6 exécutés, 1 partiel, 1 interrompu, 5 non exécutés (§ 5.2) | exécuté | exécuté | exécuté |
| `minos-app` | exécuté | non exécuté (§ 5.2) | exécuté (importé, porte les tests) | exécuté (agrégat) | exécuté |
| Agrégateur `minos-parent` | N/A (aucun bytecode) | N/A | N/A | exécuté (racine de l'agrégat) | exécuté |
| `minos-intellij` | exécuté (classes recompilées) | bloqué (§ 5.2) | **non exécuté** : hors réacteur, aucune règle d'architecture décidée pour le plugin | exécuté (dépendances reconstituées) | exécuté |
| Scripts PowerShell, shell, Python, lanceurs | N/A (pas de bytecode) | N/A | N/A | N/A (aucun manifeste de dépendances) | exécuté |
| Workflows, Compose, Dockerfiles, installateur | N/A | N/A | N/A | **non couvert** (images Docker et actions hors du champ de l'outil) | exécuté |
| `fixtures/`, `benchmarks/` | N/A (données de test) | N/A | N/A | N/A | exécuté |
| Historique Git | N/A | N/A | N/A | N/A | exécuté (miroir complet + local) |

### 3.2 SpotBugs

| Élément | Valeur |
|---|---|
| Configuration | effort `Max`, seuil `Medium`, filtre `quality/spotbugs-exclude.xml` (vide), tests exclus |
| Exécuté | oui, deux fois : plugin Maven (`clean verify -Paudit-spotbugs`) et ligne de commande (SpotBugs 4.10.4, mêmes réglages) |
| Périmètre analysé | **1 126 classes sur 1 126 compilées** dans les 14 modules (comptées par `ClassStats` de la ligne de commande) ; **68 sur 68** pour le plugin IntelliJ ; 0 classe manquante, 0 erreur d'analyse |
| Exclusions | aucune |
| Résultats | réacteur : **197 alertes `Medium`, 0 `High`** ; plugin : **11 `Medium`, 0 `High`** |
| Conclusions démontrées | toutes les classes de production compilées ont été analysées ; les alertes de la ligne de commande et du plugin Maven sont identiques (`instanceHash`) module par module ; les rapports du plugin Maven, eux, ne prouvent pas le périmètre (constat H01) |
| Limites | sans greffon de sécurité (`find-sec-bugs`) ; les fragments PowerShell embarqués ne sont pas du bytecode |
| Rapports | `<module>/target/spotbugsXml.xml` ; relecture : `…\minos-audit\reports\2026-10-08\spotbugs-cli\<module>.xml` (§ 4) |

### 3.3 PIT

| Élément | Valeur |
|---|---|
| Configuration | profil `audit-mutation`, `targetClasses`/`targetTests` = `com.minos.*`, mutateurs `DEFAULTS`, 10 fils, `timeoutConstant` 8 000 ms, `timeoutFactor` 1,5, tas 1 024 Mo par minion, `-Duser.dir` à la racine, `crossModule=false` |
| Exécuté | oui, mais **incomplet** : 6 modules complets, `minos-runtime-local` partiel, `minos-storage-postgresql` interrompu, 6 modules non exécutés ; plugin IntelliJ bloqué |
| Périmètre analysé | 537 classes mutées sur 830 compilées dans les 7 modules traités ; 12 384 mutants (dont 560 non analysés) |
| Exclusions | aucune |
| Résultats | voir § 5.2 |
| Conclusions démontrées | 60 % des mutants couverts tués par une assertion sur les 7 modules ; survivants confirmés sur l'autorisation, l'intégrité de la chaîne d'audit et le nonce du stockage chiffré des tenants (H07). Rien n'est démontré pour les 7 modules non mutés |
| Limites | tests d'autres modules non comptés (`crossModule=false`) ; branches Linux non couvertes sous Windows ; timeouts et erreurs non comptés comme tués |

### 3.4 ArchUnit

| Élément | Valeur |
|---|---|
| Configuration | `ModuleArchitectureTest` dans `minos-app`, ArchUnit 1.5.1, import des 14 `target/classes` |
| Exécuté | oui : `-pl minos-app -am test -Dtest=ModuleArchitectureTest` (mode par défaut et mode strict) |
| Périmètre analysé | **14 modules, 1 126 classes**, chaque `.class` du disque importée (test `everyModuleIsImportedCompletely`) |
| Exclusions | le plugin IntelliJ (aucune règle d'architecture décidée pour lui) ; les tests ne sont pas importés |
| Résultats | mode par défaut : **6 règles établies vertes**, 1 mesure sautée ; mode strict : **rouge**, 4 modules utilisent un module qu'ils ne déclarent pas (`minos-storage-local→minos-domain` 259 dépendances, `minos-nexus→minos-engine` 21, `minos-mcp→minos-domain` 14 et `→minos-engine` 13, `minos-runtime-local→minos-domain` 5) |
| Conclusions démontrées | les frontières A2 (ADR 0042), l'unicité package/module (ADR 0044) et la position du domaine et du moteur (ADR 0022) sont respectées **au niveau des classes compilées**, au-delà de ce que vérifient les POM ; l'usage transitif de modules non déclarés existe et n'est tranché par aucun ADR (constat H08) |
| Limites | usages dynamiques (`ServiceLoader`, réflexion) invisibles ; aucun contrôle de cycles de packages internes à un module (non décidé) |

### 3.5 OWASP Dependency-Check

| Élément | Valeur |
|---|---|
| Configuration | profils `audit-dependency-check` (livré) et `audit-dependency-check-tests` ; `failOnError=true`, `failBuildOnCVSS=11`, OSS Index désactivé (identifiants requis) |
| Exécuté | oui : `aggregate` livré, `aggregate` tests, `check` sur les dépendances du plugin |
| Fraîcheur | base mise à jour le 8 octobre (260 enregistrements depuis la veille au soir, 26 906 depuis le 4 septembre) ; mise à jour avec clé **en échec** (`Invalid API Key`, build rouge, distingué d'un résultat) |
| Périmètre analysé | livré : 40 dépendances ; tests : 68 ; plugin : 10 ; 0 exception d'analyse dans les trois rapports |
| Résultats | livré : **43 alertes, toutes sur le module MINOS `minos-storage-postgresql`** rapproché du CPE du serveur PostgreSQL (faux positif, constat H02) ; **0 alerte sur les 27 bibliothèques tierces livrées** (pilote `postgresql` 42.7.13 compris) ; tests : **4 CVE** sur `httpclient5` 5.5.1 et `httpcore5` 5.3.6 embarqués par `docker-java-transport-zerodep` 3.7.1 (Testcontainers, constat H03) ; plugin : 0 |
| Conclusions démontrées | aucune vulnérabilité publiée connue de la base au 8 octobre ne touche une bibliothèque tierce livrée par le réacteur Maven ; les vulnérabilités trouvées ne concernent que l'outillage de test |
| Limites | ne couvre ni les plugins Maven, ni le plugin Gradle, ni les images Docker (`pr-ci.yml` lance par ailleurs OSV-Scanner sur le dépôt), ni les outils SCIP embarqués ; correspondance par CPE sujette aux faux positifs et faux négatifs |

### 3.6 Gitleaks

| Élément | Valeur |
|---|---|
| Configuration | règles par défaut de Gitleaks 8.30.1, aucun `.gitleaks.toml` ni `.gitleaksignore` dans le dépôt ; `--redact=100` ; rapports hors du dépôt |
| Exécuté | oui, trois périmètres |
| Périmètre analysé | historique distant complet (miroir, toutes références : 4 064 commits, ≈ 23 Mo de patchs) ; historique local (`--all`, `refs/stash` comprise, ≈ 27 Mo) ; arbre de travail avec fichiers non suivis et ignorés (≈ 147 Mo, `target/` et `.intellijPlatform/` compris) |
| Résultats | 17 alertes (miroir), 22 (local), 27 (arbre) ; **13 causes distinctes, toutes des faux positifs** : SHA hexadécimaux de 40 caractères (épinglages d'actions, versions d'outils dans `embedded-tools.json`, HEAD cités dans la documentation et les rapports du superviseur), empreintes de 64 caractères des benchmarks, clé HMAC de test, faux jetons des tests `PublicErrorMessagesTest`, identifiants de plugins de la sandbox IntelliJ |
| Conclusions démontrées | **aucun secret** détecté par les règles par défaut dans les références accessibles et l'arbre de travail |
| Limites | règles par défaut seulement ; références indisponibles listées au § 2.4 ; un secret au format inconnu ou faiblement entropique n'est pas détecté ; le clone de travail superficiel produit des alertes attribuées au commit de frontière (attribution corrigée par le miroir) |

## 4. Registre des exécutions

Les journaux et rapports volumineux ne sont **pas** versionnés. Ils ont été conservés hors du dépôt dans `%USERPROFILE%\.cache\minos-audit\reports\2026-10-08\` (copie des journaux de session, rapports SpotBugs, PIT, Dependency-Check, ArchUnit ; rapports Gitleaks rédigés). Chaque ligne se rejoue avec la commande citée au commit de référence.

| # | Exécution | Commande (résumé) | Durée | Résultat |
|---|---|---|---|---|
| E01 | Gitleaks miroir | `git clone --mirror` puis `gitleaks git mirror.git --log-opts=--all --redact=100` | 1,7 s | 17 alertes, 0 secret |
| E02 | Gitleaks historique local | `gitleaks git . --log-opts=--all --redact=100` | 3,6 s | 22 alertes, 0 secret |
| E03 | Gitleaks arbre de travail | `gitleaks dir . --redact=100` | 3,9 s | 27 alertes, 0 secret |
| E04 | Mise à jour NVD avec clé | `dependency-check-maven:13.0.0:update-only -DnvdApiKeyEnvironmentVariable=NVD_API_KEY` | 10 s | **échec** : `Invalid API Key` |
| E05 | Mise à jour NVD anonyme | `dependency-check-maven:12.2.2:update-only` (base copiée de la base 11.0 du 4 septembre) | 1 min 37 s | 26 906 enregistrements, succès |
| E06 | Plugin IntelliJ : compilation et tests | harnais `javac --release 21` + JUnit Platform Launcher 6.1.3 | 1 min 05 s | 68 classes ; 71 tests, 63 réussis, 0 échec, 8 sautés |
| E07 | Plugin IntelliJ : SpotBugs | `FindBugs2 -effort:max -medium`, IDE en classpath auxiliaire | 20 s | 68/68 classes, 11 alertes |
| E08 | Plugin IntelliJ : Dependency-Check | `dependency-check-maven:13.0.0:check` sur POM de reconstitution | 8 s | 10 dépendances, 0 vulnérabilité |
| E09 | Ligne de base interrompue | `clean verify -Paudit-spotbugs` (session coupée au module 12/15) | — | 11 modules verts ; remplacée par E10 |
| E10 | **Ligne de base** | `./mvnw -B -ntp -fae -Paudit-spotbugs clean verify` | 23 min 19 s | BUILD SUCCESS ; 2 271 tests, 0 échec, 56 sautés ; 197 alertes SpotBugs |
| E11 | JaCoCo | `check-jacoco.py` avec et sans `--skip-scope m30-postgresql-pgvector` | < 5 s | SUCCESS (tous les scopes) |
| E12 | 28 gates statiques de `pr-ci.yml` | `check-*.py` et auto-tests | < 2 min | 28 / 28 rc = 0, avec les modifications de configuration |
| E13 | SpotBugs ligne de commande | `dependency:build-classpath` puis `FindBugs2` par module | 1 min 57 s | 1 126/1 126 classes, 197 alertes identiques |
| E14 | ArchUnit | `-pl minos-app -am test -Dtest=ModuleArchitectureTest` | ≈ 40 s | 6 règles vertes, 1 sautée |
| E15 | ArchUnit strict | idem avec `-Dminos.audit.archunit.strict=true` | ≈ 40 s | rouge : 4 modules, usages non déclarés |
| E16 | Mise à jour NVD anonyme | `12.2.2:update-only` | 17 s | 260 enregistrements |
| E17 | Dependency-Check livré | `-Paudit-dependency-check -DautoUpdate=false aggregate` | 12 s | 40 dépendances, 43 alertes (faux positif) |
| E18 | Dependency-Check tests | `-Paudit-dependency-check-tests -DautoUpdate=false aggregate` | 14 s | 68 dépendances, 4 CVE (tests) |
| E19 | PIT (échec de configuration) | `… mutationCoverage -DwithHistory` | < 10 s | échec immédiat sur 14 modules : historique réservé à un greffon commercial |
| E20 | **PIT réacteur** | `./mvnw -B -ntp -fn -Paudit-mutation -DfailWhenNoMutations=false -Dpit.threads=10 -Djacoco.skip=true test-compile org.pitest:pitest-maven:mutationCoverage` | 2 h 19 | 3 modules mutés ; 11 en échec de compilation des tests (jar verrouillé dans `~/.m2`) |
| E21 | PIT plugin IntelliJ (ligne de commande 1.30.0, 2 fils) | `MutationCoverageReport --classPathFile … --mutableCodePaths …` | ≈ 10 min, arrêté | couverture calculée ; 42 minions morts : résultats écartés |
| E22, E23 | Reprise PIT `-rf :minos-storage-local` | deux tentatives | 3 s chacune | **échec** : `Error loading java.security file` (JDK) |
| E25 | Reprise PIT `-rf :minos-storage-local` (après réparation du poste) | idem E22 | 3 s | **échec** : les modules amont sortis du réacteur ne sont pas résolubles (`${revision}`, rien d'installé) |
| E26 | Chien de garde du JDK | `jdk-watchdog.ps1` : relevé chaque minute des entrées `S-1-15-2-…` explicites sur la racine du JDK, arrêt de Maven si une entrée persiste ≥ 3 min | pendant E27 | aucune entrée vue (`present=0` à chaque relevé) |
| E27 | **PIT modules restants** | réacteur complet, `-DtargetClasses=<paquets des 11 modules restants>` (§ 7.3) | ≈ 2 h, arrêté | 4 modules mutés ; `minos-storage-postgresql` arrêté (conteneurs orphelins, Docker saturé) |
| E28 | PIT `minos-nexus`, `minos-cli`, `minos-api`, `minos-mcp`, `minos-app` | `-DtargetClasses=<paquets des 5 modules>` | ≈ 10 min, arrêté | `minos-nexus` muté ; arrêt par le chien de garde pendant `minos-cli` : 3 entrées `S-1-15-2-…` restées sur le JDK après l'arrêt des lanceurs |
| E29 | PIT `minos-api`, `minos-mcp` | `-DtargetClasses` et `-DtargetTests=com.minos.api.*,com.minos.mcp.*` | ≈ 9 min | mutés (une première tentative avait échoué : `ProjectInspectionUnreadableDirectoryTest`, du jar de tests de `minos-bootstrap`, ne passe pas sous PIT) |
| E30 | PIT `minos-storage-postgresql` | 2 fils, tests du module seulement | 1 h 23 | muté ; 18 timeouts, 18 conteneurs orphelins supprimés ensuite |
| E31 | Nettoyage Docker | suppression des conteneurs `org.testcontainers=true` / `pgvector:0.8.7-pg17` créés par E27 (74) et E30 (18) | — | 0 conteneur Testcontainers restant ; aucun autre conteneur touché |
| E24 | Contrôles complémentaires | analyse syntaxique PowerShell (116 fichiers) et `bash -n` (11) ; YAML (26) ; `docker compose config` (2) | < 1 min | voir § 6 |

## 5. Résultats détaillés

### 5.1 SpotBugs par module

| Module | Classes compilées | Classes analysées | Alertes `Medium` |
|---|---|---|---|
| `minos-api` | 95 | 95 | 57 |
| `minos-storage-local` | 64 | 64 | 42 |
| `minos-engine` | 309 | 309 | 39 |
| `minos-runtime-local` | 96 | 96 | 21 |
| `minos-provider-scip` | 66 | 66 | 14 |
| `minos-intellij` | 68 | 68 | 11 |
| `minos-application` | 229 | 229 | 5 |
| `minos-cli` | 95 | 95 | 5 |
| `minos-integration-git` | 23 | 23 | 4 |
| `minos-nexus` | 15 | 15 | 4 |
| `minos-storage-postgresql` | 38 | 38 | 4 |
| `minos-domain` | 43 | 43 | 1 |
| `minos-mcp` | 29 | 29 | 1 |
| `minos-bootstrap` | 8 | 8 | 0 |
| `minos-app` | 16 | 16 | 0 |
| **Total** | **1 194** | **1 194** | **208** |

Motifs du réacteur : `EI_EXPOSE_REP` 104, `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` 70, `DMI_HARDCODED_ABSOLUTE_FILENAME` 6, `EI_EXPOSE_REP2` 5, `USO_UNSAFE_METHOD_SYNCHRONIZATION` 2, `OS_OPEN_STREAM` 2, `CT_CONSTRUCTOR_THROW` 2, `ENV_USE_PROPERTY_INSTEAD_OF_ENV` 2, `REC_CATCH_EXCEPTION` 2, `IS2_INCONSISTENT_SYNC` 1, `VA_FORMAT_STRING_USES_NEWLINE` 1 : mêmes nombres par module et par motif que le 7 octobre ([code-audit-constats.md § 1](code-audit-constats.md#1-alertes-spotbugs)). Plugin : `DMI_HARDCODED_ABSOLUTE_FILENAME` 4 (`MinosStrongProcessLauncher:47`), `EI_EXPOSE_REP` 3, `EI_EXPOSE_REP2` 2, `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` 1 (`MinosProjectService:121`), `USO_UNSAFE_METHOD_SYNCHRONIZATION` 1 (`MinosUiController:27`).

### 5.2 PIT par module

**PIT est incomplet : 11 modules sur 14 analysés** (`minos-runtime-local` partiellement) ; `minos-cli`, `minos-bootstrap`, `minos-app` et le plugin IntelliJ ne sont pas mutés. Exécutions E20 à E31 du § 4.

| Module | Classes compilées | Classes mutées | Mutants | Tués (assertion) | Survivants | Sans couverture | Timeouts / erreurs | Non viables | Tués / mutants | Tués / couverts |
|---|---|---|---|---|---|---|---|---|---|---|
| `minos-domain` | 43 | 24 | 211 | 75 | 22 | 114 | 0 | 0 | 36 % | 77 % |
| `minos-engine` | 309 | 186 | 3402 | 1832 | 940 | 621 | 9 | 0 | 54 % | 66 % |
| `minos-runtime-local` | 96 | 72 | 2013 | 812 | 160 | 385 | 656 | 0 | 40 % | 50 % |
| `minos-storage-local` | 64 | 45 | 1725 | 1062 | 518 | 127 | 18 | 0 | 62 % | 66 % |
| `minos-provider-scip` | 66 | 50 | 1459 | 620 | 417 | 421 | 1 | 0 | 42 % | 60 % |
| `minos-integration-git` | 23 | 15 | 426 | 187 | 141 | 94 | 4 | 0 | 44 % | 56 % |
| `minos-application` | 229 | 145 | 3148 | 876 | 681 | 1585 | 6 | 0 | 28 % | 56 % |
| `minos-storage-postgresql` | 38 | 25 | 774 | 367 | 215 | 174 | 18 | 0 | 47 % | 61 % |
| `minos-bootstrap` | 8 | — | **pas de rapport** | | | | | | | |
| `minos-nexus` | 15 | 8 | 165 | 23 | 21 | 121 | 0 | 0 | 14 % | 52 % |
| `minos-cli` | 95 | — | **rapport vide (exécution interrompue)** | | | | | | | |
| `minos-api` | 95 | 36 | 344 | 208 | 78 | 58 | 0 | 0 | 60 % | 73 % |
| `minos-mcp` | 29 | 10 | 337 | 195 | 78 | 59 | 5 | 0 | 58 % | 70 % |
| `minos-app` | 16 | — | **pas de rapport** | | | | | | | |
| **Total** | **1007** | **616** | **14004** | **6257** | **3271** | **3759** | **717** | **0** | **45 %** | **61 %** |

Lecture du tableau :

- « Tués (assertion) » ne compte que `KILLED`. PIT, lui, compte aussi les timeouts et erreurs : il affiche 54 % pour `minos-engine` et **73 %** pour `minos-runtime-local`, contre 54 % et **40 %** ici.
- **`minos-runtime-local` : les 560 `RUN_ERROR` ne sont pas des résultats.** Tous sont survenus entre 11 h 25 et 11 h 40, à raison d'environ 40 par minute, y compris sur des records sans logique : des minions n'ont pas pu démarrer (incident H23). Ces 560 mutants sont **non analysés** et doivent être rejoués ; les 96 timeouts viennent des tests de confinement de processus (≈ 1 par minute sur deux heures).
- « Classes compilées » moins « classes mutées » = classes sans mutation générée (interfaces, records sans logique, classes internes triviales) : aucune n'est exclue par configuration.
- **Tests d'autres modules** : `minos-domain` (114 mutants sans couverture) et `minos-engine` (621) sont en grande partie testés par des modules aval (`InMemoryCodeKnowledgeStore` par `minos-provider-scip` et `minos-storage-local`, `ProjectPathMapping` par `minos-storage-local` et `minos-cli`, les types du domaine par presque tous les modules). La passe `crossModule` qui le mesurerait n'a pas été exécutée.
- Coût mesuré (10 fils) : `minos-domain` 9 s, `minos-engine` 18 min 20 s (31 619 exécutions de tests), `minos-runtime-local` 2 h 00, `minos-storage-local` 32 min, `minos-provider-scip`, `minos-integration-git` et `minos-application` ≈ 45 min à eux trois.
- `minos-application` (1 585 mutants sans couverture) est surtout testé depuis `minos-cli`, `minos-api` et `minos-app` : même limite inter-modules.
- Survivants qualifiés : [code-audit-constats.md](code-audit-constats.md), fiche H07.

| Module | Statut PIT | Raison |
|---|---|---|
| `minos-domain`, `minos-engine`, `minos-storage-local`, `minos-provider-scip`, `minos-integration-git`, `minos-application`, `minos-storage-postgresql`, `minos-nexus`, `minos-api`, `minos-mcp` | **exécuté** | `minos-storage-postgresql` avec 2 fils (E30) ; `minos-api` et `minos-mcp` avec leurs seuls tests (E29) |
| `minos-runtime-local` | **partiel** | 560 mutants non analysés (minions non démarrés pendant l'incident H23) ; à rejouer sur une machine jetable |
| `minos-cli` | **interrompu, à faire sur machine jetable** | E28 : arrêté par le chien de garde, 2 autorisations AppContainer restées plus de 3 min sur le JDK (H22 reproduit) |
| `minos-bootstrap`, `minos-app` | **non exécuté, à faire sur machine jetable** | leurs tests démarrent le même bac à sable Windows (et PostgreSQL pour `minos-bootstrap`) |
| `minos-intellij` | **bloqué** | E21 : couverture calculée, puis 42 minions morts pendant l'incident H23 ; résultats écartés |


## 6. Angles morts : contrôles complémentaires

| Zone | Contrôle | Exécuté ? | Résultat |
|---|---|---|---|
| Scripts PowerShell | parseur PowerShell 7.6 (`Parser.ParseFile`) sur 116 fichiers | oui | 101 scripts autonomes sans erreur ; 15 fragments et gabarits embarqués non analysables seuls (placeholders), couverts par les tests Java qui les assemblent |
| Scripts shell | `bash -n`, `set -e`, mode Git | oui | 11/11 valides, tous avec `set -e` ; 4 en mode 100644, toujours appelés par `bash` |
| Analyse statique de scripts | PSScriptAnalyzer, shellcheck, hadolint, actionlint | **bloqué** | aucun outil installé ; non téléchargé sans accord |
| YAML (workflows, Compose, OpenSpec) | `yaml.safe_load_all` | oui | 26/26 valides |
| Compose | `docker compose config -q` | oui | valides avec les variables fournies par les scripts ; sans elles, échec sur `container_name` vide (variables obligatoires sans garde `${VAR:?}`) |
| Workflows | `check-workflow-pins.py`, `check-single-execution.py` | oui | verts ; `code-audit.yml` non exécuté sur GitHub |
| PostgreSQL et migrations | 78 tests de `minos-storage-postgresql` contre un PostgreSQL/pgvector réel (Testcontainers) | oui (Windows + Docker Desktop) | verts ; aucun test ne migre une base déjà peuplée de v1 vers v4 (lecture) |
| Reprise après interruption | tests existants (`ResumeAfterHardKillIntegrationTest`, `InterruptionDuringIndexingTest`, `DurableAtomicFileTest`…) dans la ligne de base | oui | verts ; aucun test ne tue un processus entre l'écriture temporaire et le renommage d'un snapshot local (lecture) |
| Échanges CLI/MCP | tests de `minos-cli`, `minos-mcp`, `minos-app` (dont `ShadedJarMcpEntryPointIT`) | oui | verts |
| Plugin IntelliJ dans une IDE réelle, Gradle, vérificateur de plugin | — | **non exécuté** | Gradle absent ; le job `intellij-plugin.yml` n'a pas été observé |
| Linux (bubblewrap, cgroup v2), WSL, Docker de release | — | **non exécuté** | poste Windows seul |

La lecture de code (revérification des anciens constats, § 6 de [code-audit-constats.md](code-audit-constats.md)) n'est jamais présentée comme une exécution.

## 7. Blocages, limites et point de reprise

> **L'audit est incomplet.** PIT n'a analysé que 3 modules sur 14 et le plugin IntelliJ n'a pas été muté. Les quatre autres outils ont couvert leur périmètre applicable (§ 3).

### 7.1 Incident d'environnement (8 octobre, entre 10 h 12 et 11 h 55)

Pendant le PIT de `minos-runtime-local`, le poste a changé d'état ; c'est le constat **MINOS-AUD-H23** :

- La racine du JDK `C:\Users\fturl\.jdks\openjdk-24.0.1` porte **460 entrées explicites `(OI)(CI)RX` pour des SID d'AppContainer** (`S-1-15-2-…`), la forme exacte que pose `Grant-AppContainerDirectory` du script de bac à sable Windows de MINOS et que `Remove-AppContainerPath` doit retirer en fin d'exécution (constat **H22**). Leur date d'apparition n'est pas connue : elles ont pu s'accumuler sur des semaines de tests comme pendant ce PIT, dont 96 timeouts ont tué des lanceurs avant leur nettoyage.
- Depuis, **aucune JVM ne peut charger la configuration de sécurité du JDK** : `java.lang.InternalError: Error loading java.security file`, cause `AccessDeniedException` sur `conf\security\java.security`, alors que .NET ouvre le fichier sans erreur. Maven ne démarre plus. Le lien de cause à effet avec les entrées AppContainer n'est pas démontré.
- **Le DACL du profil `C:\Users\fturl` n'est plus lisible** (`icacls C:\Users\fturl` : « Accès refusé ») et MSYS ne peut plus le traverser, alors qu'un `mkdir -p` sous ce profil réussissait à 10 h 12. La cause n'est pas établie : aucun code de MINOS lu pendant l'audit ne réécrit le DACL d'un ancêtre de son répertoire (le contrôle du parent du lanceur se contente de le **lire** par `icacls /save`), et un autre outil modifie aussi des ACL sur ce poste (entrée `CodexSandboxUsers` sur `C:\Users\fturl\.jdks`).
- Aucun processus issu des exécutions PIT ne restait actif à 12 h. Les processus `java` démarrés vers 1 h du matin appartiennent à MORPHEUS et n'ont pas été touchés.

**Aucune réparation n'a été tentée par l'audit** : modifier les droits du profil ou du JDK est une décision de l'utilisateur, à prendre en administrateur.

**Réparation par l'utilisateur (8 octobre, vers 13 h, en administrateur)** : DACL sauvegardés dans `C:\acl-backup\` ; la racine du profil ne portait plus qu'une entrée `BUILTIN\Administrateurs:(F)` non héritable, avec Administrateurs pour propriétaire ; elle a été rétablie à SYSTEM, Administrateurs et l'utilisateur en `(OI)(CI)(F)`, sans héritage, propriétaire SYSTEM. Les 460 entrées `S-1-15-2-…` ont été retirées de la racine du JDK (0 restante). Vérifié ensuite : la JVM charge `java.security`, `./mvnw -v` répond, le profil est traversable. Les deux réparations ayant été faites ensemble, laquelle a débloqué la JVM n'est pas établi. La reprise de PIT tourne sous un chien de garde qui arrête Maven si une entrée AppContainer reste plus de trois minutes sur la racine du JDK.

### 7.2 Blocages

| Blocage | Effet | Levée |
|---|---|---|
| Incident H23 (JDK et profil) | plus aucun build Maven ni JVM avec configuration de sécurité ; PIT arrêté | l'utilisateur examine puis rétablit, en administrateur, le DACL de `C:\Users\fturl` (contrôle total de l'utilisateur, de SYSTEM et des Administrateurs, sans entrée étrangère) ; il retire les entrées `S-1-15-2-…` explicites de la racine du JDK (ou réinstalle ce JDK) ; puis vérifie `java -XshowSettings:security -version` et `./mvnw -v` |
| Clé `NVD_API_KEY` refusée par le NVD | Dependency-Check 13.0.0 ne rafraîchit pas sa base ; repli anonyme par 12.2.2 | l'utilisateur vérifie ou régénère la clé et la déclare comme secret du dépôt pour `code-audit.yml` |
| Gradle absent | plugin IntelliJ analysé par harnais, pas par son build | installer Gradle ou ajouter un wrapper au plugin |
| Poste Windows seul | aucun contrôle Linux, WSL ni Docker de release exécuté | CI Ubuntu (`pr-ci.yml`) et `code-audit.yml` sur `ubuntu-24.04` |
| Analyseurs de scripts absents | analyse syntaxique seulement | autoriser l'installation de PSScriptAnalyzer, shellcheck, hadolint, actionlint |
| PIT de code qui modifie l'hôte | le PIT de `minos-runtime-local` exerce sous Windows des lanceurs qui accordent des droits sur des fichiers réels ; un mutant ou un timeout peut laisser ces droits en place | rejouer ce module dans une machine jetable (VM, runner éphémère), jamais sur un poste de travail |

### 7.3 Point de reprise

État au 8 octobre 2026, 19 h 30 : inventaire, Gitleaks, SpotBugs (réacteur et plugin), ArchUnit, Dependency-Check (réacteur et plugin), contrôles complémentaires et revérification des constats **terminés** ; PIT **terminé** pour 10 modules, **partiel** pour `minos-runtime-local` (560 mutants à rejouer), **à faire sur une machine jetable** pour `minos-cli`, `minos-bootstrap`, `minos-app` (leurs tests laissent des autorisations AppContainer sur l'hôte, H22) et `minos-runtime-local` ; plugin IntelliJ **non muté** ; passe `crossModule` **non exécutée**. Trois entrées `S-1-15-2-…` laissées sur le JDK par E28 sont à retirer par l'utilisateur.

Après levée de l'incident H23, depuis la racine du dépôt :

```bash
./mvnw -v                                   # doit afficher Maven 3.10.0 et Java 24.0.1
./mvnw -B -ntp -DskipTests test-compile     # si les target/ ont été nettoyés
# 1. Modules non mutés :
# (pas de -rf : les modules amont sortiraient du réacteur et leurs artefacts ne sont pas installés)
./mvnw -B -ntp -fn -Paudit-mutation -DfailWhenNoMutations=false -Dpit.threads=10 -Djacoco.skip=true \
  '-DtargetClasses=com.minos.cli.*,com.minos.bootstrap.*,com.minos.app.*,com.minos.integration.nexus.*' \n  '-DtargetTests=com.minos.cli.*,com.minos.bootstrap.*,com.minos.app.*,com.minos.integration.nexus.*' \
  test-compile org.pitest:pitest-maven:mutationCoverage
# (paquets des 7 modules restants au 8 octobre 15 h 30 ; minos-storage-postgresql et minos-bootstrap démarrent
#  PostgreSQL par Testcontainers : sous Windows, Ryuk est désactivé par le profil windows-docker-desktop-testcontainers
#  et chaque minion tué laisse un conteneur (H24). Les muter sur Linux (profil inactif, Ryuk actif) ou avec
#  -Dpit.threads=2 en surveillant `docker ps --filter label=org.testcontainers=true`.)
# 2. minos-runtime-local, à rejouer en entier, dans une machine jetable uniquement :
./mvnw -B -ntp -Paudit-mutation -pl minos-runtime-local -am -DfailWhenNoMutations=false -Dpit.threads=10 \
  -Djacoco.skip=true -DtargetClasses='com.minos.runtime.local.*' test-compile org.pitest:pitest-maven:mutationCoverage
# 3. Passe inter-modules sur le domaine (tests de minos-engine et minos-storage-local) :
./mvnw -B -ntp -Paudit-mutation -pl minos-storage-local -am -DfailWhenNoMutations=false -Dpit.crossModule=true \
  -Djacoco.skip=true '-DtargetClasses=com.minos.domain.*,com.minos.semantic.*,com.minos.program.*' \
  test-compile org.pitest:pitest-maven:mutationCoverage
python scripts/quality/audit-report-summary.py pit --all
```

Le point 1 relance aussi, en aval, des tests de `minos-bootstrap`, `minos-cli` et `minos-app` qui démarrent le bac à sable : sur un poste de travail, le faire seulement après avoir corrigé H22 ou dans une machine jetable.

Plugin IntelliJ : rejouer le harnais ([code-audit.md](code-audit.md), « Plugin IntelliJ ») puis PIT en ligne de commande 1.30.0 avec `--classPathFile` (classes recompilées, `<IDE>/lib/*`, gson, JUnit 6.1.3), `--mutableCodePaths` sur les classes du plugin et `--jvmArgs -Xmx512m`. Les rapports PIT déjà produits sont archivés hors dépôt (§ 4).
