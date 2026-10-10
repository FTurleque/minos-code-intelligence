# État courant — MINOS

État produit revérifié le : **7 octobre 2026** ; mis à jour le **10 octobre 2026** (sprint 1 de l'audit du 10 octobre). Planification additionnelle : **5 octobre 2026**.

Ce fichier est la synthèse autoritative de l'état produit courant. Les réconciliations détaillées antérieures restent archivées sous [`history/reconciliations/`](history/reconciliations/). Une capacité présente sur une branche ou une PR n'est dite intégrée dans `develop` qu'après merge ; le présent document décrit néanmoins les garanties du HEAD qui le contient afin qu'il reste exact avant et après promotion.

## Planification additionnelle — 2026-10-05

Les ADR 0055–0057 sont acceptés comme direction, sans revendiquer leur implémentation. [Suivi du chantier storage/hexagonal](roadmap/storage-hexagonal-2026-10/README.md). La présence de ce plan ne signifie ni promotion de develop vers main ni clôture des audits. Les preuves d'implémentation seront ajoutées dans le suivi des tâches.

## Audit 2026-10-10 — sprint 1 (2026-10-10)

Le sprint 1 de l'audit du 10 octobre 2026 (« remettre les dispositifs de mesure en marche », 9 constats) est **corrigé, fusionné dans `develop` et archivé** (PR #392 à #394 et #396 à #399) :

- **Checks exigés par le ruleset** : `Verify (ubuntu-24.04)`, `Verify (windows-2022)`, `Dependency vulnerability gate / osv-scan`, `Static invariants (single run)`, `Gitleaks`, `IntelliJ plugin (gate)` et `SonarCloud Code Analysis` (ruleset « Protect main & develop ») ; `Docker upgrade evidence gate` pour `main` seul. La liste attendue est versionnée dans `.github/required-checks.json` ; `scripts/quality/check-ci-wiring.py` (job `invariants`) vérifie que chaque check résout un job qui peut rendre un verdict sur une PR, et `scripts/quality/verify-ruleset.py` (à la main) mesure l'écart avec le ruleset réel (code 0 constaté le 2026-10-10).
- **Auto-tests de gates** : `check-jacoco.py --self-test` et l'auto-test du garde de jalons sont rejoués dans `invariants` ; `check-ci-wiring.py` refuse tout auto-test qui n'y serait pas câblé.
- **Confinement** : `-Dminos.sandbox.tests.required=true` sur les deux jobs `verify` ; un bubblewrap, un cgroup v2 ou un AppContainer indisponible fait échouer le job de son système au lieu d'être sauté en silence. Les tests propres à une plateforme sont comptés comme sautés ailleurs.
- **Plugin IntelliJ** : workflow exécuté sur toute PR, portée calculée (`scripts/ci/plugin-gate.py`) et gate agrégatrice toujours rendue ; la portée inclut les rendus JSON de `minos-application`, le domaine et les goldens de caractérisation.
- **Architecture** : garde de frontières testée règle par règle (90 cas), groupId interne lu dans le POM racine, **aucun cycle de packages** (règle A8, table `KNOWN_PACKAGE_CYCLES` vide, plugin compris), listes de dépendances d'`arc42/05` confrontées aux POM (règle A9). Les quatre cycles existants ont été levés par le déplacement de 13 classes de production (et de leurs tests) dans leur module.

Capacités OpenSpec créées ou étendues : `controles-de-fusion`, `frontieres-de-modules`, `confinement-code-non-fiable`. Les sprints 2 à 11 de l'audit (sécurité et observabilité, socle JDK, chemin de requête, chaîne de release, couverture réelle, sorties publiques, confinement et E/S privées, orchestration d'indexation, frontières d'architecture, hygiène) restent à traiter.

## État produit

- **C0 → M30** : terminés et intégrés.
- **M29 issue #107** : **CLOSED** ; **M29 PR #108** intégrée.
- **M30 PR #110** et **M30 promotion PR #111** intégrées.
- **hardening PR #113** intégré ; **M28 Windows CI PR #117** intégré.
- **#98 sandbox OS réelle** : primitives **implémentées** sur Linux (bubblewrap/cgroup v2) et Windows (AppContainer/Job Object) ; qualification code non fiable **refusée** par décision (ADR 0041, 2026-09-26) — le quota d'écriture reste supervisé, pas appliqué par l'OS ; `remote index` fail-closed sur tous les OS, refus journalisé et exposé par `minos doctor` (audit 2026-09, constat A1).
- **PR #227** : provider egress, provenance `CommandLocator`, reparse private storage et contrat de fallback confinement **intégrés**.
- **#224–#248** : campagne de confinement provider/filesystem, provenance, egress, installateur et Windows non-admin intégrée.
- **#258/#260** : audit du 28 août 2026 — politique sécurité, Dependabot, CODEOWNERS futur, toolchain, couverture, confinement secrets/fingerprints, simplification des qualifications historiques et hardening exact-head intégrés dans la ligne `develop` auditée.
- **Audit du 10 octobre 2026, sprint 1** : 9 constats corrigés et intégrés dans `develop` (voir la section dédiée ci-dessus).
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

`.github/workflows/pr-ci.yml` est le pipeline de PR unique (constat C1 de l'audit 2026-09, voir [`docs/audit/archive/2026-09/CI-HYGIENE-SUIVI.md`](audit/archive/2026-09/CI-HYGIENE-SUIVI.md)), avec trois jobs sans dépendance entre eux, qui tournent en parallèle :

- **`vulnerability-scan`** : gate de vulnérabilités des dépendances (OSV-Scanner, workflow réutilisable épinglé par SHA) ;
- **`invariants`** : un unique **gate statique ciblé Ubuntu**, sans Maven ni Java — épinglage supply-chain des workflows, frontières de modules, cohérence documentaire courante, `product-facts`, garde-fou de non-réaccumulation d'artefacts de jalon, invariants MND/MNE/post-MNE (y compris les neuf gates de jalon actifs M21–M28), invariants post-#228, invariants d'audit-remédiation v2/P0-P2/MINOS-01, provenance Inno Setup, tests unitaires du vérificateur Docker upgrade — chaque contrôle une seule fois, quel que soit l'OS de `verify` ;
- **`verify`** (Ubuntu 24.04 et Windows Server 2022), **seul build Maven d'une PR** (les workflows M19 et M20, qui en répétaient une copie filtrée par chemins, sont retirés : constat C2, gardé par `scripts/quality/check-single-execution.py`) : `clean verify` Maven complet, PostgreSQL obligatoire sur Linux, tests sandbox/cgroup/AppContainer applicables, seuils JaCoCo ciblés Linux/Windows, invariant d'ascendance (`main` doit être ancêtre du candidat, afin d'empêcher une nouvelle divergence silencieuse `main/develop`).

**Checks exigés.** Le ruleset exige `Verify (ubuntu-24.04)`, `Verify (windows-2022)`, `Dependency vulnerability gate / osv-scan`, `Static invariants (single run)`, `Gitleaks`, `IntelliJ plugin (gate)` et `SonarCloud Code Analysis` ; la liste est déclarée dans `.github/required-checks.json` et vérifiée par `scripts/quality/check-ci-wiring.py` (jobs résolus, déclencheurs sans filtre de chemins, auto-tests de gates câblés dans `invariants`). Les deux jobs `verify` passent `-Dminos.sandbox.tests.required=true` : un confinement OS indisponible est un échec, pas un saut. L'heuristique `check-partial-result-consumers.py`, autrefois consultative, est bloquante depuis que `invariants` est exigé.

Les workflows `mnd-remediation.yml`, `mne-remediation.yml`, `post-mne-remediation.yml` et `post-228-hardening.yml`, qui dupliquaient chacun leur propre checkout et leur propre vérification d'épinglage, ont été retirés : leurs invariants vivent désormais dans le job `invariants` ci-dessus.

Les preuves historiques Post-#228 restent explicitement conservées : candidat qualifié `1a551ff72f95db4e14e8a9597d897491b9c1589a`, puis merge `a042e97ac5e3e2ab7207fa603d85563ea1f71712`. Ces SHA décrivent l'intégration historique #228 ; ils ne changent pas la répartition actuelle des responsabilités CI.

### Qualifications historiques

Les replays M15/M28 historiques sont isolés dans `.github/workflows/historical-qualification.yml` et ne font pas partie du chemin PR courant par défaut.

### IntelliJ

`.github/workflows/intellij-plugin.yml` qualifie le plugin séparément : Java 21, Gradle 9.6.1, IntelliJ Platform 2026.1, build/structure/Plugin Verifier sous Linux et tests ownership sous Windows. Le plugin reste un client externe sans dépendance d'implémentation `com.minos:*`. Le workflow s'exécute sur **toute** PR (aucun filtre de chemins au niveau du workflow) : un job `Plugin scope` calcule si le plugin est concerné (`scripts/ci/plugin-gate.py` ; la portée inclut `minos-cli`, `minos-integration-git`, les rendus JSON de `minos-application`, `minos-domain` et les goldens de caractérisation), les deux jobs du plugin ne tournent que dans ce cas, et `IntelliJ plugin (gate)`, toujours exécuté, est le seul check exigé.

### Docker

`.github/workflows/docker-release-validation.yml` qualifie à chaque candidat l'image provider-complete Linux/amd64 exacte.

La qualification réelle **Docker MCP A → B** tourne sur un runner **GitHub-hosted `ubuntu-24.04`** standard — aucun runner auto-hébergé, aucune machine personnelle, aucun repository d'infrastructure privé (le dépôt est public). `scripts/ci/qualify-docker-upgrade.ps1` (pwsh, portable) construit deux commits/JAR distincts, chacun avec son propre Dockerfile/Compose, pilote le cœur portable `docker/scripts/mcp-lifecycle.ps1` (extrait de `prod-mcp-release.ps1`, qui reste l'interface produit Windows inchangée pour les utilisateurs), les providers/Compose réels, indexe un projet fixture, exécute le handshake MCP avant/après upgrade, vérifie la persistance du `MINOS_HOME` et s'assure qu'un candidat suivant invalide ne remplace pas le candidat B qualifié.

La promotion `develop → main` déclenche cette qualification automatiquement via `.github/workflows/release-promotion-gate.yml` (job `docker-upgrade-qualification` puis `docker-upgrade-evidence` en dépendance, jamais l'inverse). `.github/workflows/docker-upgrade-qualification.yml` reste disponible en `workflow_dispatch` pour un usage manuel ponctuel, mais ne déclenche plus rien automatiquement lui-même — cela évite toute double exécution pour un même candidat. `scripts/release/check-docker-upgrade-evidence.py` télécharge et valide le manifeste `qualification.json` de la preuve (candidat exact, résultat `PASS`, et éventuellement le SHA précédent attendu), pas seulement le nom de l'artifact.

### SonarCloud

`SonarCloud Code Analysis` est un check **exigé**. Depuis le 2026-10-09 l'analyse est **pilotée par la CI** : la variable de dépôt `SONAR_CI_ANALYSIS_ENABLED` vaut `true` et l'étape « SonarCloud CI-based analysis » de `pr-ci.yml` (job `verify` Ubuntu) s'exécute avec succès. Le Quality Gate affiche donc une **couverture réelle sur le nouveau code** (seuil 80 %), et non plus le `0.0 %` de l'Automatic Analysis.

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
- cœur MINOS : Java 24 / Maven 3.9.x ;
- plugin IntelliJ : Java 21 / Gradle 9.6.1 / IntelliJ Platform 2026.1 ;
- Dependabot couvre Maven, GitHub Actions **et le build Gradle `minos-intellij`** ;
- les actions GitHub sont épinglées sur des SHA immuables ;
- `docker/Dockerfile.mcp.release` conserve ses images/toolchains/checksums de release reproductibles ;
- le Dockerfile MCP local utilise lui aussi une base Temurin 24 JRE par digest immuable et n'est plus dépendant d'un tag flottant.

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
