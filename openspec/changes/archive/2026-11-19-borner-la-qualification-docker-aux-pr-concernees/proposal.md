# Proposal

## Why

Constat AUD-DEP-03 de l'audit du 2026-11-15 (`docs/project/audits/2026-11-15/findings.json`, Moyenne, confirmé au HEAD `89be3fb4`). Depuis le retrait de `develop` (changement archivé `2026-11-13-retirer-la-branche-develop`), `release-promotion-gate.yml` se déclenche sur chaque pull request vers `main`, sans calcul de portée : la qualification Docker A → B réelle (délai maximal de 90 minutes, deux builds Maven, deux builds d'image) est un check exigé de toute fusion, PR documentaires et Dependabot comprises. Le propriétaire a décidé le 2026-11-13 de la garder telle quelle dans le changement de nettoyage et de trancher sa restriction à part (D4 de ce changement archivé) : c'est l'objet de celui-ci.

Contrainte qui borne la solution : le check `Docker upgrade evidence gate` est exigé par le ruleset « Release promotion gate (main only) » (`.github/required-checks.json`). Un check exigé dont le workflow ne démarre pas reste en attente et bloque toute fusion ; un filtre `paths:` est donc exclu, et `check-ci-wiring.py` le refuse déjà.

## What Changes

- `release-promotion-gate.yml` reste déclenché sur toute PR vers `main`, sans filtre de chemins. Un job de portée calcule si la PR peut changer l'image ou sa mise à niveau.
- La qualification A → B ne s'exécute que si la PR est concernée. Le job `Docker upgrade evidence gate` s'exécute toujours et rend un verdict : preuve vérifiée quand la PR est concernée, « non concerné » sinon.
- Le calcul est fail-closed : une PR est hors portée seulement si **tous** ses fichiers modifiés appartiennent à une liste fermée de chemins sans effet sur l'image ; tout doute (base inconnue, erreur Git, déclenchement manuel) la déclare concernée.
- La qualification reste obligatoire pour une release : un déclenchement manuel est toujours concerné, et la procédure de release la lance toujours sur le SHA à taguer.
- `check-ci-wiring.py` exige `if: always()` de tout job exigé qui attend d'autres jobs.
- L'entrée `DOCKER_LOGIN_KEPT` de ce job est réévaluée (le candidat A est la base de la PR, lisible avant la connexion).

Aucune rupture : le nom du check exigé et la déclaration `.github/required-checks.json` ne changent pas.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `controles-de-fusion` : trois exigences ajoutées (verdict toujours rendu par le gate Docker ; condition `if: always()` gardée pour tout job exigé à dépendances ; portée fail-closed par liste fermée). Les exigences existantes « Un check exigé peut toujours rendre un verdict » et « Les identifiants de registre ne restent pas exposés au code d'une pull request sans nécessité » ne sont pas modifiées : ce changement les applique.

## Hors périmètre

- Le contenu de la qualification (`scripts/ci/qualify-docker-upgrade.ps1`) et de la vérification de preuve (`scripts/release/check-docker-upgrade-evidence.py`, hors le cas « non concerné »).
- `docker-upgrade-qualification.yml` (déclenchement manuel) : inchangé ici ; son étape Java 25 relève d'AUD-DEP-04 (changement `solder-les-restes-de-java-25-et-de-develop`).
- La modification du ruleset GitHub : aucune, le check garde son nom.
- Les secrets Dependabot Docker Hub : action du propriétaire, planifiée en tâche manuelle.
- AUD-DEP-05 (jeton SonarCloud) : changement `separer-le-jeton-sonarcloud-de-la-compilation-de-la-pr`.

## Découpage

Un constat, un workflow, une décision du propriétaire en attente (D1 du design). Il est séparé d'AUD-DEP-04, qui touche le même fichier `release-promotion-gate.yml` mais est mécanique et sans décision : AUD-DEP-04 se fusionne d'abord, celui-ci se rebase dessus. Il suit aussi `separer-le-jeton-sonarcloud-de-la-compilation-de-la-pr`, qui modifie le même `check-workflow-pins.py` dans une autre table.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| Le workflow démarre sur toute PR vers `main`, sans `paths` | [Existant vérifié] | `.github/workflows/release-promotion-gate.yml:20-23` |
| La qualification a un délai de 90 minutes | [Existant vérifié] | `release-promotion-gate.yml:37` |
| Le check exigé est le job `Docker upgrade evidence gate` | [Existant vérifié] | `release-promotion-gate.yml:118-121`, `.github/required-checks.json` |
| Un workflow filtré par chemins est refusé pour un check exigé | [Existant vérifié] | `scripts/quality/check-ci-wiring.py:143`, `:216` |
| Le schéma « portée calculée + job de verdict » existe pour le plugin | [Existant vérifié] | `.github/workflows/intellij-plugin.yml:4-6`, `:22-49` ; `scripts/ci/plugin-gate.py:65-82` |
| La qualification reste sur toute PR jusqu'à décision séparée | [Décision] | propriétaire, 2026-11-13 (D4 du changement archivé) |
| La qualification est bornée aux PR concernées | [Proposition] | D1 du design, en attente du propriétaire |
| Durée réelle de la qualification par PR | [Question] | non mesurée (tâche 1.1) |

## Impact

- **Modules du reactor** : aucun.
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune.
- **ADR** : aucun (périmètre d'un contrôle de CI, convention de gouvernance tenue par les gates).
- **Gates à rejouer** : `scripts/quality/check-ci-wiring.py` (check exigé joignable, auto-tests câblés), `scripts/remediation/check-audit-remediation-v2.py` (affirme `docker-upgrade-qualification`, `docker-upgrade-evidence`, `needs: docker-upgrade-qualification` à la ligne 184, `qualify-docker-upgrade.ps1`, `check-docker-upgrade-evidence.py`, `runs-on: ubuntu-24.04` dans le workflow, `:169-187`), `scripts/quality/check-workflow-pins.py` (`DOCKER_LOGIN_KEPT`, délais, checkouts), `scripts/quality/check-toolchain-baseline.py` (table qui nomme le job), `scripts/quality/check-single-execution.py`, `python .claude/scripts/run_gates.py`.
- **Plateformes** : CI Ubuntu 24.04 ; les scripts de portée et leurs auto-tests s'exécutent sur Windows et Linux.
- **Documentation durable** : `docs/developer/quality-gates.md`, `docs/developer/supply-chain.md`, `docs/architecture/diagrams/capacites/controles-de-fusion.md`, `.claude/rules/workflows-ci.md`, `.claude/skills/minos-release-promotion/SKILL.md`, `docs/architecture/risks/register.md` (R-25, avec AUD-DEP-06).
