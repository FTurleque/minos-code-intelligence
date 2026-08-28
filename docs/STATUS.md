# État courant — MINOS

Dernière mise à jour : **28 août 2026**.

Ce fichier est la synthèse autoritative de l'état produit courant. Les réconciliations détaillées antérieures restent archivées sous [`history/reconciliations/`](history/reconciliations/). Une capacité présente sur une branche ou une PR n'est dite intégrée dans `develop` qu'après merge ; le présent document décrit néanmoins les garanties du HEAD qui le contient afin qu'il reste exact avant et après promotion.

## État produit

- **C0 → M30** : terminés et intégrés.
- **M29 issue #107** : **CLOSED** ; **M29 PR #108** intégrée.
- **M30 PR #110** et **M30 promotion PR #111** intégrées.
- **hardening PR #113** intégré ; **M28 Windows CI PR #117** intégré.
- **#98 sandbox OS réelle** : implémentée et qualifiée sur Linux et Windows.
- **#224–#248** : campagne de confinement provider/filesystem, provenance, egress, installateur et Windows non-admin intégrée.
- **#258/#260** : audit 28 août — politique sécurité, Dependabot, CODEOWNERS futur, toolchain, couverture, confinement secrets/fingerprints, simplification des qualifications historiques et hardening exact-head intégrés dans la ligne `develop` auditée.
- Ligne de développement : **1.1.0-SNAPSHOT**.

## Release 1.0.1

La release **MINOS v1.0.1 est PUBLIÉE et immuable**.

```text
v1.0.1 → f762025d66e33c40324c811079f1527d122f90f9
```

- URL : https://github.com/FTurleque/minos-code-intelligence/releases/tag/v1.0.1
- publication : **10 assets**, soit **5 paires** artefact/checksum ;
- workflow de publication : `31288322126` ;
- setup Windows, distribution et plugin IntelliJ restent soumis aux gates OSV, provenance et **Plugin Verifier** applicables.

Aucune release 1.1.0 n'est publiée à ce jour.

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

### Qualifications historiques

Les replays M15/M28 historiques sont isolés dans `.github/workflows/historical-qualification.yml` et ne font pas partie du chemin PR courant par défaut.

### IntelliJ

`.github/workflows/intellij-plugin.yml` qualifie le plugin séparément : Java 21, Gradle 9.6.1, IntelliJ Platform 2026.1, build/structure/Plugin Verifier sous Linux et tests ownership sous Windows. Le plugin reste un client externe sans dépendance d'implémentation `com.minos:*`.

### Docker

`.github/workflows/docker-release-validation.yml` qualifie à chaque candidat l'image provider-complete Linux/amd64 exacte.

`.github/workflows/docker-upgrade-qualification.yml` fournit en plus la qualification réelle **Docker MCP A → B** sur un runner Windows x64 auto-hébergé portant le label `minos-docker` et Docker Desktop Linux containers. `scripts/ci/qualify-docker-upgrade.ps1` construit deux commits/JAR distincts, utilise le vrai workflow `prod-mcp-release.ps1`, les providers/Compose réels, indexe un projet fixture, exécute le handshake MCP avant/après upgrade, vérifie la persistance du `MINOS_HOME` et s'assure qu'un candidat suivant invalide ne remplace pas le candidat B qualifié.

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
