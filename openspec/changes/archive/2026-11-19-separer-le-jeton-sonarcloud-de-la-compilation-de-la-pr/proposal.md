# Proposal

## Why

Constat AUD-DEP-05 de l'audit du 2026-11-15 (`docs/project/audits/2026-11-15/findings.json`, Faible, confirmé au HEAD `89be3fb4`). Dans le job `sonar` de `.github/workflows/pr-ci.yml`, une seule étape porte `SONAR_TOKEN` dans son environnement et exécute à la fois la compilation du code de la pull request (`./mvnw … test-compile`) et l'analyse SonarCloud. Tout plugin Maven ou processeur d'annotations que résout la compilation de la PR s'exécute donc avec le jeton. C'est le schéma que le dépôt a refusé pour le jeton Docker Hub (AUD-DEP-13 du 2026-10-10, exigence `controles-de-fusion › Les identifiants de registre ne restent pas exposés au code d'une pull request sans nécessité`).

## What Changes

- Le job `sonar` construit le candidat dans une étape **sans secret**, puis lance l'analyse dans une étape distincte qui porte seule `SONAR_TOKEN` et n'exécute aucune phase du cycle de vie Maven.
- `scripts/quality/check-workflow-pins.py` refuse, dans un workflow déclenché par `pull_request`, une étape `run:` dont l'`env:` d'étape référence un secret et qui exécute du code du dépôt (`mvnw`, `scripts/`), sauf entrée nominative d'une liste fermée avec sa raison ; une entrée sans objet est refusée. Il refuse aussi tout secret dans un `env:` de job ou de workflow, hors l'identifiant `DOCKERHUB_USERNAME`, pour que la règle ne se contourne pas en remontant le jeton d'un niveau.
- L'étape d'analyse est la seule entrée de cette liste : elle doit lancer `mvnw` pour le but `sonar`.
- La nature du jeton (jeton d'analyse de projet, pas jeton d'utilisateur) est vérifiée et écrite dans `docs/developer/supply-chain.md`.

Aucune rupture : le check `SonarCloud Code Analysis` (source `application`) et le nom du job restent inchangés.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `controles-de-fusion` : une exigence ajoutée (un secret n'accompagne pas la compilation du code d'une pull request). Aucune exigence existante modifiée.

## Hors périmètre

- Remplacer le plugin Maven Sonar par le scanner autonome (nouvelle action à épingler, autre mode d'import de la couverture) : décision séparée, voir le design (D3).
- Les secrets des workflows non déclenchés par `pull_request` (`NVD_API_KEY` de `code-audit.yml`) et le login Docker Hub (déjà couvert par `DOCKER_LOGIN_KEPT`).
- Le secret Dependabot `SONAR_TOKEN` (création et rotation : actions du propriétaire).
- AUD-DEP-03 (qualification Docker par PR) : changement `borner-la-qualification-docker-aux-pr-concernees`.

## Découpage

Un constat, un workflow (`pr-ci.yml`), un gate (`check-workflow-pins.py`). Il n'est pas regroupé avec AUD-DEP-03, qui modifie un autre workflow et attend une décision du propriétaire ; les deux touchent `check-workflow-pins.py` dans des tables différentes ; celui-ci se fusionne d'abord (design, « Ordre de fusion »).

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| L'étape d'analyse porte `SONAR_TOKEN` et compile le head de la PR | [Existant vérifié] | `.github/workflows/pr-ci.yml:544-547`, `:582-588`, checkout `:518` |
| Aucune extension Maven de niveau dépôt | [Existant vérifié] | `.mvn/` ne contient que `wrapper/` |
| Le gate n'a de liste fermée que pour le login Docker | [Existant vérifié] | `scripts/quality/check-workflow-pins.py:126-131` |
| Le jeton est un jeton d'analyse de projet | [Question] | à vérifier dans SonarCloud (tâche manuelle 2.1) |
| `DOCKERHUB_USERNAME` est posé par un `env:` de job dans deux workflows `pull_request` | [Existant vérifié] | `.github/workflows/pr-ci.yml:289-290` ; `.github/workflows/release-promotion-gate.yml:41-42` |
| Le but `sonar` lancé seul résout les modules du reactor | [Question] | à mesurer en CI avant de figer la forme (tâche 1.2) ; repli prévu : `install -DskipTests` dans l'étape sans secret |
| L'étape d'analyse peut tourner hors ligne (`-o`) | [Question] | à mesurer en CI (tâche 1.2) |

## Impact

- **Modules du reactor** : aucun.
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune.
- **ADR** : aucun (application d'une règle de chaîne d'approvisionnement existante).
- **Gates à rejouer** : `scripts/quality/check-workflow-pins.py` et `scripts/quality/test_check_workflow_pins.py`, `scripts/quality/check-ci-wiring.py`, `scripts/quality/check-single-execution.py`, `scripts/quality/check-toolchain-baseline.py` (lit les `java-version:` du workflow), `python .claude/scripts/run_gates.py`.
- **Plateformes** : CI Ubuntu 24.04 (le job `sonar` n'existe que là) ; le gate s'exécute sur Windows et Linux.
- **Documentation durable** : `docs/developer/supply-chain.md`, `docs/developer/quality-gates.md`, `docs/architecture/diagrams/capacites/controles-de-fusion.md`, `.claude/rules/workflows-ci.md`.
