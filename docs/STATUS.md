# État courant — MINOS

Dernière mise à jour : **30 août 2026**.

Ce fichier est la synthèse autoritative de l'état produit courant. Les réconciliations détaillées antérieures restent archivées sous [`history/reconciliations/`](history/reconciliations/). Une capacité présente sur une branche ou une PR n'est dite intégrée dans `develop` qu'après merge ; le présent document décrit néanmoins les garanties du HEAD qui le contient afin qu'il reste exact avant et après promotion.

## État produit

- **C0 → M30** : terminés et intégrés.
- **M29 issue #107** : **CLOSED** ; **M29 PR #108** intégrée.
- **M30 PR #110** et **M30 promotion PR #111** intégrées.
- **hardening PR #113** intégré ; **M28 Windows CI PR #117** intégré.
- **#98 sandbox OS réelle** : **IMPLÉMENTÉE + QUALIFIÉE** sur Linux et Windows dans la campagne de convergence.
- **PR #227** : provider egress, provenance `CommandLocator`, reparse private storage et contrat de fallback confinement **intégrés**.
- **#224–#248** : campagne de confinement provider/filesystem, provenance, egress, installateur et Windows non-admin intégrée.
- **#258/#260** : audit 28 août — politique sécurité, Dependabot, CODEOWNERS futur, toolchain, couverture, confinement secrets/fingerprints, simplification des qualifications historiques et hardening exact-head intégrés dans la ligne `develop` auditée.
- Ligne de développement : **1.2.0-SNAPSHOT**.

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

Aucune release 1.2.0 n'est publiée à ce jour ; la ligne `1.2.0-SNAPSHOT` couvre notamment la remédiation d'audit `develop` (fuite de `RemoteMaterialization` sur échec d'acquisition du lease de réindexation distante, résolution PATH POSIX acceptant un fichier non exécutable, gates JaCoCo d'orchestration critique) promue vers `main` par la PR #259.

## Répartition autoritative des gates CI

La qualification courante est volontairement séparée entre gates produit actuels et replays historiques.

### PR Validation

`.github/workflows/pr-ci.yml` porte les contrôles produit exact-head actuels :

- scan de vulnérabilités OSV ;
- Maven `clean verify` sous Ubuntu 24.04 et Windows Server 2022 ;
- PostgreSQL obligatoire sur Linux ;
- tests sandbox/cgroup/AppContainer applicables ;
- seuils JaCoCo ciblés Linux/Windows ;
- invariants architecture, supply-chain et documentation ;
- invariant d'ascendance : `main` doit être ancêtre du candidat afin d'empêcher une nouvelle divergence silencieuse `main/develop`.

### Post-228 Hardening Invariants

`.github/workflows/post-228-hardening.yml` est désormais un **gate statique ciblé Ubuntu**. Il vérifie les invariants post-#228 sans dupliquer Maven, Windows ou JaCoCo, qui restent sous l'autorité de **PR Validation**.

Les preuves historiques Post-#228 restent explicitement conservées : candidat qualifié `1a551ff72f95db4e14e8a9597d897491b9c1589a`, puis merge `a042e97ac5e3e2ab7207fa603d85563ea1f71712`. Ces SHA décrivent l'intégration historique #228 ; ils ne changent pas la répartition actuelle des responsabilités CI.

### Qualifications historiques

Les replays M15/M28 historiques sont isolés dans `.github/workflows/historical-qualification.yml` et ne font pas partie du chemin PR courant par défaut.

### IntelliJ

`.github/workflows/intellij-plugin.yml` qualifie le plugin séparément : Java 21, Gradle 9.6.1, IntelliJ Platform 2026.1, build/structure/Plugin Verifier sous Linux et tests ownership sous Windows. Le plugin reste un client externe sans dépendance d'implémentation `com.minos:*`.

### Docker

`.github/workflows/docker-release-validation.yml` qualifie à chaque candidat l'image provider-complete Linux/amd64 exacte.

La qualification réelle **Docker MCP A → B** tourne sur un runner **GitHub-hosted `ubuntu-24.04`** standard — aucun runner auto-hébergé, aucune machine personnelle, aucun repository d'infrastructure privé (le dépôt est public). `scripts/ci/qualify-docker-upgrade.ps1` (pwsh, portable) construit deux commits/JAR distincts, chacun avec son propre Dockerfile/Compose, pilote le cœur portable `docker/scripts/mcp-lifecycle.ps1` (extrait de `prod-mcp-release.ps1`, qui reste l'interface produit Windows inchangée pour les utilisateurs), les providers/Compose réels, indexe un projet fixture, exécute le handshake MCP avant/après upgrade, vérifie la persistance du `MINOS_HOME` et s'assure qu'un candidat suivant invalide ne remplace pas le candidat B qualifié.

La promotion `develop → main` déclenche cette qualification automatiquement via `.github/workflows/release-promotion-gate.yml` (job `docker-upgrade-qualification` puis `docker-upgrade-evidence` en dépendance, jamais l'inverse). `.github/workflows/docker-upgrade-qualification.yml` reste disponible en `workflow_dispatch` pour un usage manuel ponctuel, mais ne déclenche plus rien automatiquement lui-même — cela évite toute double exécution pour un même candidat. `scripts/release/check-docker-upgrade-evidence.py` télécharge et valide le manifeste `qualification.json` de la preuve (candidat exact, résultat `PASS`, et éventuellement le SHA précédent attendu), pas seulement le nom de l'artifact.

### SonarCloud

`SonarCloud Code Analysis` s'exécute aujourd'hui en **Automatic Analysis** (application GitHub installée sur le dépôt), sans étape `sonar-scanner`/`mvn sonar:sonar` dans `pr-ci.yml` et sans secret `SONAR_TOKEN` configuré côté dépôt. Ce mode ne clone et n'analyse le code que statiquement : il ne peut **structurellement pas** importer de rapport de couverture, quelle que soit la qualité de la configuration JaCoCo. C'est la cause racine du `0.0% Coverage on New Code` affiché par le Quality Gate — ce n'est pas un défaut d'intégration JaCoCo côté build (JaCoCo XML est bien généré et vérifié, voir `scripts/quality/check-jacoco.py` et la gate décrite plus haut).

L'autorité réelle de couverture sur le nouveau code reste donc la **gate JaCoCo ciblée** de `PR Validation` (seuils par composant, voir `scripts/quality/check-jacoco.py`), pas le pourcentage SonarCloud affiché sur la PR. Le Quality Gate SonarCloud reste utile pour les bugs/vulnérabilités/hotspots (aujourd'hui à 0) et pour les code smells de maintenabilité, qu'il faut corriger au fil de l'eau.

Un scaffold d'analyse SonarCloud pilotée par CI (`mvn sonar:sonar` avec `sonar.coverage.jacoco.xmlReportPaths` pointant vers l'agrégat JaCoCo) existe, gardé derrière la variable de dépôt `SONAR_CI_ANALYSIS_ENABLED` (désactivée par défaut, donc sans effet tant qu'elle n'est pas positionnée). Pour l'activer et obtenir une couverture SonarCloud représentative : provisionner un `SONAR_TOKEN` (secret du dépôt), désactiver l'Automatic Analysis dans les paramètres du projet SonarCloud (les deux modes sont mutuellement exclusifs), puis positionner `SONAR_CI_ANALYSIS_ENABLED=true`. Cette étape nécessite un accès SonarCloud que l'automatisation de ce dépôt n'a pas et reste une action externe pour l'opérateur humain.

## Garanties de stockage et secrets

- les chemins de secrets relatifs sont confinés physiquement à `MINOS_HOME` ; les chemins absolus restent une option opérateur explicite pour les secret stores montés ;
- les fichiers de secret sont lus avec un plafond d'octets et un décodeur UTF-8 strict : les séquences mal formées sont refusées, jamais remplacées silencieusement ;
- les snapshots structurés v1/v2 conservent leur plafond persistant de **256 MiB**, désormais imposé pendant l'I/O par flux bornés en plus des contrôles de taille ;
- les tailles/cardinalités/chaînes des formats persistés restent bornées et les données PostgreSQL utilisent un scratch privé ;
- les clés hosted dérivées utilisent HMAC-SHA-256 ; les buffers temporaires maître et dérivé sont nettoyés après construction de la clé finale ;
- hosted control plane : AES-256-GCM avec AAD, limites de taille et écriture atomique durable.

## Supply-chain et toolchains

- Maven Wrapper : Maven 3.9.16 avec checksum SHA-256 ;
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
- branches de promotion : `develop` doit contenir l'ascendance de `main` avant tout candidat, vérifié par CI.

## Qualification d'une nouvelle remédiation

Une correction n'est déclarée intégrée qu'après succès exact-head des workflows applicables sur son HEAD final. Aucun document ni workflow n'autorise un merge automatique vers `main` : la promotion reste une décision explicite.
