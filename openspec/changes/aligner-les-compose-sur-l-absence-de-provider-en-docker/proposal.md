# Proposal

## Why

Constat AUD-DEP-01 de l'audit du 2026-11-15 (`docs/project/audits/2026-11-15/findings.json`, Faible, confiance « probable »). Les deux fichiers compose décrivent le service `minos-admin` par le commentaire « indexing and tools list/verify launch the providers and their toolchains ». C'est l'inverse de SEC-0074 (« Son plan d'administration (`minos-admin`) ne lance aucun provider ») et de SEC-0080 décision 7 (« image qui n'exécute aucun provider »), et de ce que la qualification Docker elle-même prouve (`index` y est refusé, scip-java y est `UNSUPPORTED_BY_BACKEND`). Un lecteur qui suit le commentaire peut croire qu'un provider indexe dans cette image, alors que le classpath de son lanceur scip-java n'est pas verrouillé (risque R-13, assumé parce qu'aucun provider n'y indexe ; le lanceur n'y est lancé que par la sonde `minos-provider-probe`, pour sa version).

Le principe capability-honest s'applique aux fichiers livrés : un commentaire d'un fichier de distribution ne présente pas une capacité que le runtime refuse.

## What Changes

- Les commentaires de `minos-admin` dans `docker/compose-mcp.connected.yaml` et `docker/compose-mcp.prod.yaml`, et le commentaire du Dockerfile qui attribue la résolution des dépendances Maven au « plan d'administration éphémère », disent ce que fait réellement ce plan : administration, inventaire et vérification des outils, indexation refusée faute de bac à sable OS.
- Le test de contrat Docker de `minos-app` refuse que le bloc du service `minos-admin` le présente comme lançant des providers ; le service de sonde `minos-provider-probe`, qui exécute réellement les outils pour les sonder, garde son commentaire.
- La présence des toolchains et du lanceur scip-java dans l'image d'administration reçoit une raison écrite (ils sont sondés, jamais utilisés pour indexer), ou leur retrait est décidé avec celui de la sonde (D2 du design, en attente du propriétaire).

Aucune rupture : aucun service, aucune image, aucune option ne change dans ce lot.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `distribution-docker` : une exigence ajoutée (les fichiers de distribution Docker ne présentent pas le plan d'administration comme lançant des providers). Aucune exigence existante modifiée ; la tension avec « L'image du plan de requête ne contient ni compilateur ni outil de provider » (phrase « L'image d'administration SHALL conserver ce qu'exige l'indexation ») est un écart nommé dans le design.

## Hors périmètre

- Le retrait effectif des toolchains et du lanceur scip-java de l'image d'administration : il dépend de la décision D2 et d'une mesure ; s'il est retenu, il fait l'objet d'un changement distinct (qualification Docker, inventaire de l'image, tests de contrat).
- Le verrouillage du classpath scip-java de l'image (R-13, SEC-0080 décision 7) : risque assumé, inchangé.
- L'indexation dans un conteneur (SEC-0074 l'exclut ; la voie WSL2 est SEC-0079).
- AUD-DEP-03 à 06 : autres changements du sprint.

## Découpage

Un constat, la distribution Docker seule (`docker/`, un test de contrat de `minos-app`, `docs/user/docker-runtime.md`). Il n'est pas regroupé avec AUD-DEP-06 (documents de gouvernance, autre capacité) : toute modification de `docker/` déclenche la qualification Docker A → B, alors que les corrections documentaires d'AUD-DEP-06 n'en ont pas besoin.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| Les deux compose affirment que `minos-admin` lance les providers | [Existant vérifié] | `docker/compose-mcp.connected.yaml:168` ; `docker/compose-mcp.prod.yaml:71` |
| SEC-0074 et SEC-0080 disent l'inverse | [Existant vérifié] | `docs/architecture/adr/SEC-0074-java-non-indexable-depuis-windows-voie-wsl2.md` (§ Context, 2ᵉ paragraphe) ; `docs/architecture/adr/SEC-0080-forcage-nominatif-d-une-dependance-du-verrou-de-scip-java.md` (décision 7) |
| La qualification exige le refus de `index` et l'état `UNSUPPORTED_BY_BACKEND` de scip-java dans ce plan | [Existant vérifié] | `scripts/ci/qualify-docker-upgrade.ps1:386-413` |
| L'image d'administration embarque un lanceur scip-java autonome hors verrou | [Existant vérifié] | `docker/Dockerfile.mcp.release:257-276` |
| Un service de l'image d'administration exécute les outils pour les sonder (`javac`, `mvn`, `scip-java --version`, indexeurs, toolchains) | [Existant vérifié] | service `minos-provider-probe` : `docker/compose-mcp.connected.yaml:298-312`, `docker/compose-mcp.prod.yaml:223-230` |
| Ce que `tools list`, `tools verify` et `doctor` exécutent dans `minos-admin` | [Question] | à lire (tâche 2.1) |

## Impact

- **Modules du reactor** : `minos-app` (test de contrat Docker, aucun code principal).
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune.
- **ADR** : aucun créé ni amendé dans ce lot ; SEC-0074 et SEC-0080 sont cités. Si D2 retient le retrait des toolchains, la décision 7 de SEC-0080 et le risque R-13 sont à reprendre dans le changement de retrait.
- **Gates à rejouer** : tests de contrat `M29DockerAdministrationContractTest`, `M29DockerQueryImageContractTest`, `M29DockerReleaseCiGateContractTest` (`./mvnw -B -ntp -pl minos-app -am test`), `scripts/quality/check-compose-limits.py`, `scripts/quality/check-image-pins.py`, `scripts/quality/check-toolchain-baseline.py` (lit les `FROM` du Dockerfile), `scripts/quality/check-comment-language.py` si les fichiers `docker/` y sont déclarés, `python .claude/scripts/run_gates.py --fast`.
- **Plateformes** : image Linux ; le test de contrat s'exécute sur Windows et Linux.
- **Documentation durable** : `docs/user/docker-runtime.md`, `docs/developer/remote-worker-sandbox-disposition.md`, `docs/architecture/diagrams/capacites/distribution-docker.md`, `docs/architecture/risks/register.md` (R-13, si la raison écrite y renvoie).
