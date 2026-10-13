# Proposal

## Why

Deux constats du sprint 3 de l'audit du 10 octobre 2026 portent sur le **même bloc** de `docker/Dockerfile.mcp.release` (l'inventaire de provenance, `:216-234`), le même gate (`check-tools-manifest.py`) et la même qualification Docker :

- **AUD-DEP-02** (moyenne) : l'image Docker embarque **Node.js 20.20.2** (`Dockerfile.mcp.release:13`, `embedded-tools.json:145`), hors support depuis le 30 avril 2026, pour exécuter scip-typescript et scip-python sur du code de dépôts non maîtrisés. La distribution Windows exécute le **même** scip-typescript 0.4.0 sous Node 24.20.0 (`embedded-tools.json:133`) : l'argument « ce provider ne documente que Node 18/20 » (`Dockerfile.mcp.release:89-90`, `:227`) est contredit par l'autre plateforme.
- **AUD-DEP-10** (faible) : l'inventaire de provenance écrit dans chaque image annonce `.NET SDK 10.0.302`, `Go 1.26.5` et `Rust 1.97.1` en littéraux (`:230-232`), alors que les images de base sont `dotnet/sdk:10.0.401`, `golang:1.27.0` et `rust:1.99.0` (`:2-4`). L'écart s'est **creusé** depuis l'audit (Rust 1.98.1 → 1.99.0) et se creusera à chaque montée Dependabot. Une preuve de provenance fausse est pire qu'une preuve absente.

## What Changes

- **Node maintenu dans l'image** : qualifier scip-typescript et scip-python sous Node 24 LTS sous Linux, puis aligner `NODE_VERSION`, `NODE_LINUX_SHA256` et l'entrée `linux-x64` du catalogue ; retirer la limitation « Node 18/20 only ». Si la qualification échoue, la dérogation est écrite au registre des risques avec une échéance (décision D1).
- **Inventaire relu au build** : les versions de .NET, Go, Rust et Node écrites dans `provider-inventory.json` sont lues sur les binaires installés (`dotnet --version`, `go env GOVERSION`, `rustc --version`, `node --version`), plus aucun littéral.
- **Deux contrôles** : `check-tools-manifest.py` refuse un littéral de version dans le bloc d'inventaire ; `scripts/ci/qualify-docker-release.sh` compare l'inventaire aux binaires de l'image construite.

## Capabilities

### New Capabilities

(aucune : `socle-d-execution` est créée par `migrer-le-socle-vers-java-25` ; ce changement y ajoute des exigences et peut être fusionné avant ou après)

### Modified Capabilities

- `socle-d-execution` : deux exigences ajoutées (runtime Node maintenu et identique en majeur sur les plateformes livrées ; inventaire de provenance conforme aux binaires).

## Hors périmètre

- Le JDK de l'image (AUD-DEP-01), la séparation de l'image de requête (AUD-DEP-15), les notices (AUD-DEP-16), la veille de vulnérabilités des outils et des verrous npm (AUD-DEP-03) : autres changements.
- Monter scip-typescript ou scip-python : les versions du catalogue sont inchangées.
- Embarquer scip-python dans la distribution Windows : il n'y a pas d'entrée `assembled` pour lui, et ce n'est pas demandé.
- Les versions des toolchains elles-mêmes (.NET, Go, Rust) : suivies par Dependabot ; seul leur **report** dans l'inventaire change.

## Impact

- **Modules du reactor** : `minos-provider-scip` (ressource `embedded-tools.json`, `:145-154`), copie synchronisée par `python scripts/release/sync-tools-manifest.py`. Aucun code Java.
- **Fichiers** : `docker/Dockerfile.mcp.release` (`:13-14`, `:89-90`, `:216-234`), `scripts/ci/qualify-docker-release.sh` (`:86-114`), `scripts/quality/check-tools-manifest.py` et `test_check_tools_manifest.py`.
- **Surfaces publiques** : aucune (CLI, API Java, MCP, IntelliJ, NEXUS inchangés). Le fichier `/opt/minos/provider-evidence/provider-inventory.json` garde ses champs ; seules leurs valeurs deviennent exactes et la limitation Node disparaît.
- **ADR** : amende la « Mise en œuvre » de l'ADR 0040 (qui cite Node 24.20.0 pour Windows seulement) ; aucun nouvel ADR.
- **Gates à rejouer** : `python scripts/quality/check-tools-manifest.py`, `python scripts/quality/test_check_tools_manifest.py`, `python scripts/quality/check-image-pins.py`, `M29DockerAdministrationContractTest` (`minos-app`), gate « Provider-complete image exact-head » (`docker-release-validation.yml`).
- **Plateformes** : image Docker Linux. Windows inchangé (déjà en Node 24).
