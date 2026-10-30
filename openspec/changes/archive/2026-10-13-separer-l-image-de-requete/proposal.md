# Proposal

## Why

**AUD-DEP-15** (faible, sprint 3 de l'audit du 10 octobre 2026) : le conteneur de **requête** MCP (`minos-mcp`, persistant, `restart: unless-stopped`) utilise la même image que le conteneur d'**indexation** (`minos-admin`). Cette image embarque toute la chaîne de compilation : base `eclipse-temurin:…-jdk` (`docker/Dockerfile.mcp.release:6`), `python3-pip build-essential pkg-config libicu-dev` (`:61-63`), Rust, Go et le SDK .NET copiés des images de toolchain (`:66-69`).

Le plan de requête est en lecture seule (ADR 0017) et n'a besoin que d'un JRE et du jar. Sa surface, et le volume de CVE qu'un scanner d'image lui attribue, sont sans rapport avec son besoin. L'atténuation existe (`read_only`, `cap_drop: [ALL]`, `no-new-privileges`, réseau `internal`), d'où la sévérité faible.

## What Changes

- **Deux cibles** dans `Dockerfile.mcp.release` : `query` (JRE, jar, aucun compilateur ni outil de provider) et `admin` (l'image actuelle).
- **Une seconde variable d'image** dans les deux fichiers compose : les services du plan de requête pointent sur l'image de requête, ceux qui exécutent des providers sur l'image d'administration. Chaque service est classé explicitement.
- **Le cycle de vie suit** : construction locale des deux cibles (`docker/scripts/mcp-lifecycle.ps1`), mise à jour d'installation, qualification de release et qualification de mise à niveau A→B (où A n'a qu'une image).
- **Une preuve de contenu** : la qualification échoue si l'image de requête contient un compilateur, un SDK ou un outil de provider, ou si le plan de requête ne répond pas avec elle.

## Capabilities

### New Capabilities

- `distribution-docker` : aucune capacité existante ne décrit ce que contient une image livrée ni quel service utilise laquelle. Elle porte la séparation requête / administration et sa preuve.

### Modified Capabilities

(aucune)

## Hors périmètre

- Un `HEALTHCHECK` : `minos-mcp` tourne en `sleep infinity` et le serveur est lancé par `docker exec` ; un contrôle de santé du conteneur ne prouverait rien sur le serveur. Écarté, voir `design.md` (D4).
- Réduire l'image d'administration (retirer `build-essential`, passer à des images « distroless ») : autre sujet, à mesurer provider par provider.
- `docker/Dockerfile.mcp` (lanceur de développement `prod-mcp.ps1`) : déjà un JRE et un jar ; inchangé.
- Le JDK, Node et l'inventaire de provenance (AUD-DEP-01, -02, -10), les notices (AUD-DEP-16).
- Publier les images dans un registre : elles sont construites sur le poste de l'utilisateur ou en CI, comme aujourd'hui.

## Impact

- **Modules du reactor** : `minos-app` (tests de contrat `M29DockerAdministrationContractTest`, `:28,40,73,139-143`, et les autres `M29Docker*`). Aucun code de production Java.
- **Fichiers** : `docker/Dockerfile.mcp.release`, `docker/compose-mcp.connected.yaml` (7 services sur `${MINOS_IMAGE}`), `docker/compose-mcp.prod.yaml` (6), `docker/scripts/mcp-lifecycle.ps1` (`:381`, `:395`, `:452`), `docker/scripts/verify-run-configurations.ps1` (`:146`), `scripts/install/update-installation.ps1`, `scripts/ci/qualify-docker-release.sh` (`:67-77`), `scripts/ci/qualify-docker-upgrade.ps1`.
- **Surfaces publiques** : aucune sortie CLI, MCP ou API ne change. Le contrat d'**exploitation** Docker gagne une variable d'environnement de compose ; une installation existante est migrée par la mise à jour (tâche 3).
- **ADR** : amende la « Mise en œuvre » de l'ADR 0021 (Docker MCP durci) ; aucun nouvel ADR.
- **Gates à rejouer** : `python scripts/quality/check-compose-limits.py` (plafonds de chaque service), `python scripts/quality/check-image-pins.py` (une source par image), `python scripts/quality/check-tools-manifest.py`, tests `M29Docker*`, gate « Provider-complete image exact-head », qualification A→B (`docker-upgrade-qualification.yml`, et `release-promotion-gate.yml` qui l'exige sur `main`).
- **Plateformes** : images Linux ; le lanceur est un script PowerShell exécuté sous Windows et Linux (`pwsh`).
- **Effort** : l'audit l'estime à M ; le relevé des sites (construction locale, mise à niveau, deux compose, tests de contrat) le porte à **L**.
