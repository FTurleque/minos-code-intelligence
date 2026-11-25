# Proposal

## Why

Constat **AUD-DEP-10** de l'audit `docs/project/audits/2026-11-15/` (sprint 11, sévérité faible, ouvert le 2026-11-25 au commit `75e10c68`). L'image d'administration (`docker/Dockerfile.mcp.release`) télécharge l'archive Maven, la vérifie par `sha256sum -c -` contre `MAVEN_ZIP_SHA256`, puis la décompresse. Aujourd'hui la vérification est fail-closed. Mais rien ne protège cet ordre contre une régression : `M29DockerAdministrationContractTest` n'affirme que la présence de l'`ARG`, de `${MAVEN_ZIP_SHA256}` et de la chaîne `sha256sum -c -`, que cinq autres lignes satisfont aussi. `check-tools-manifest.py` ne lit que les `ARG`. Revérifié le 2026-11-25 sur des copies : ajouter `|| true` après le contrôle, retirer `-e` de `set -eux`, ou déplacer le contrôle après `unzip` laisse tous les gates verts, et l'archive altérée est décompressée. Le même trou vaut pour les cinq autres archives vérifiées de l'image (Node, Coursier, scip-clang, rust-analyzer, paquet NuGet de scip-dotnet). Un réglage futur « pour tolérer un miroir lent », comme celui de la PR #815, est le scénario probable.

## What Changes

- `scripts/quality/check-tools-manifest.py` gagne une règle. Pour chaque `ARG` d'empreinte que le catalogue `embedded-tools.json` attribue à l'image (clé `sha256` de `dockerArgs`, six à ce jour), le Dockerfile SHALL contenir une seule instruction `RUN` qui consomme cet `ARG`. Elle commence par `set -e…`, contient une seule fois la ligne `printf '%s  %s\n' "${ARG}" <fichier> | sha256sum -c -; \`, sans `||`, sans `set +e`, sans `trap` ni `|| :` dans l'instruction. Aucune ligne qui précède le contrôle n'y utilise le fichier, hormis son téléchargement (`curl`) et son effacement (`rm -f`). La liste des `ARG` vient du catalogue, pas d'une copie : un outil ajouté au catalogue entre d'office dans la règle.
- L'auto-test `scripts/quality/test_check_tools_manifest.py` reçoit les cas rouges (`|| true`, `set -ux`, contrôle après `unzip`, contrôle absent, contrôle doublé, `ARG` consommé par deux `RUN`) et un cas sur le Dockerfile réel (`ShippedTreeTest`).
- La docstring du gate, `docs/developer/quality-gates.md` (§ « Outils livrés : une seule description ») et la mise en œuvre de l'ADR BE-0065 nomment la règle.

Le Dockerfile ne change pas : les six contrôles respectent déjà la règle. Aucun code Java de production, aucun workflow, aucune sortie de la CLI, de l'API Java, du MCP, du plugin IntelliJ ni de NEXUS ne change. Aucune étape CI n'est ajoutée : le gate et son auto-test sont déjà lancés par le job `invariants` (`.github/workflows/pr-ci.yml:136`, `:140`).

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `socle-d-execution` : une exigence ajoutée (chaque archive épinglée de l'image est vérifiée avant usage, et un contrôle neutralisé fait échouer le gate).

## Hors périmètre

- Les téléchargements de l'image qui ne sont pas vérifiés par une empreinte du catalogue. `npm ci` lit les verrous `scip-*-package-lock.json` (intégrité `sha512` par paquet, `--ignore-scripts`). `go install` passe par `GOSUMDB=sum.golang.org`. `cs fetch` de scip-java n'a aucun verrou du dépôt (R-13, écrit dans le Dockerfile). Les paquets apt viennent d'un instantané daté, signé par Ubuntu. Le design les inventorie ; aucun n'est visé par le constat.
- `docker/Dockerfile.mcp` : il ne télécharge rien.
- Remplacer les blocs par `scripts/ci/fetch-verified.sh` : option écartée (design, D2).
- `M29DockerAdministrationContractTest` : ses assertions ne sont ni retirées ni durcies (design, D1). Aucun `mvnw` n'est lancé pendant la spécification ; la tâche 2.3 vérifie en CI que le test reste vert.
- Les wrappers Maven et Gradle du dépôt : AUD-DEP-09, changement `exiger-l-empreinte-des-distributions-des-wrappers`.
- L'instantané apt `UBUNTU_APT_SNAPSHOT` et les pièges des PR Dependabot : AUD-DEP-11, changement `consigner-les-pieges-des-montees-de-version`.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| Contrôle Maven `sha256sum -c -` puis `unzip` dans le même `RUN`, ouvert par `set -eux` | [Existant vérifié] | `docker/Dockerfile.mcp.release:163`, `:174-175` |
| Six `ARG` d'empreinte, six contrôles de même forme, chacun dans un `RUN` ouvert par `set -eux` | [Existant vérifié] | `docker/Dockerfile.mcp.release:81`, `:83`, `:85-87`, `:94` ; `:174`, `:186`, `:194`, `:202`, `:211`, `:247` |
| Le catalogue attribue ces six `ARG` (clé `sha256` de `dockerArgs`) | [Existant vérifié] | `embedded-tools.json`, lu par script le 2026-11-25 : coursier, maven, nodejs, scip-clang, rust-analyzer, scip-dotnet-nupkg |
| Le test Java n'affirme que des présences | [Existant vérifié] | `minos-app/src/test/java/com/minos/packaging/M29DockerAdministrationContractTest.java:157-170`, `:178-179` |
| `check-tools-manifest.py` compare les `ARG` et refuse une empreinte écrite ailleurs, sans lire les `RUN` qui les consomment | [Existant vérifié] | `scripts/quality/check-tools-manifest.py:295-327` |
| Copies avec `|| true`, `set -ux` ou contrôle après `unzip` : gates verts, archive altérée décompressée | [Existant vérifié] | mutations et harnais rejoués le 2026-11-25 (design, « Mutations rejouées ») |
| `fetch-verified.sh` existe, est testé et câblé, mais il ne reprend pas un fichier partiel et plafonne chaque essai à 120 s | [Existant vérifié] | `scripts/ci/fetch-verified.sh:28-36` ; `scripts/ci/test_fetch_verified.py` ; `.github/workflows/pr-ci.yml:227` |
| Règle dans `check-tools-manifest.py`, liste dérivée du catalogue | [Proposition] | design, D1 |

## Impact

- **Modules du reactor** : aucun code de production. `minos-app` porte le test Java non modifié.
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune.
- **ADR** : aucun créé. BE-0065 (providers SCIP livrés dans le paquet) gagne une précision dans « Compliance and Enforcement », à la ligne de `check-tools-manifest.py`, sans changer la décision : tâche 2.2, après la fusion.
- **Workflow** : aucun changement.
- **Gates à rejouer** : `scripts/quality/check-tools-manifest.py`, `scripts/quality/test_check_tools_manifest.py`, `python scripts/release/sync-tools-manifest.py` sans `--write` (il compare puis réécrit les seuls `ARG`), `scripts/quality/check-image-pins.py`, `scripts/quality/check-toolchain-baseline.py` (lit les `FROM` du Dockerfile), `scripts/ci/test_docker_gate.py`, `scripts/quality/check-ci-wiring.py`, `scripts/quality/check-comment-language.py`, `python .claude/scripts/run_gates.py`. Aucun scope JaCoCo.
- **Plateformes** : le gate tourne sous Ubuntu (job `invariants`) et sur le poste Windows (bibliothèque standard). L'image n'est construite que pour `linux/amd64` (`docker/Dockerfile.mcp.release:115`). La règle lit le texte du Dockerfile, que ses fins de ligne soient CRLF ou LF.
- **Documentation durable** : `docs/developer/quality-gates.md`, ADR BE-0065, `docs/architecture/diagrams/capacites/socle-d-execution.md` (vue du gate et traçabilité, à l'archivage).

## Questions ouvertes

Aucune bloquante : D1 et D2 ont une recommandation.
