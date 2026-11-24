# Proposal

## Why

Constat AUD-DEP-05 de l'audit du 2026-11-15 (`docs/project/audits/2026-11-15/findings.json`, Faible, sprint 2), **partiel** depuis la PR #749 (changement archivé `2026-11-19-separer-le-jeton-sonarcloud-de-la-compilation-de-la-pr`) et maintenu partiel au relevé du 2026-11-20 (HEAD `535a5017`). La compilation du candidat a quitté l'étape qui porte `SONAR_TOKEN`, mais l'analyse reste un but Maven lancé par `./mvnw` sur l'arbre de la pull request, dans le **même job** que la compilation, sur le même runner : même `$HOME`, même `~/.m2`, même `.mvn/`. Le code de la PR (modèle Maven, configuration du wrapper, dépôt local préparé par l'étape précédente) peut donc encore atteindre le jeton. L'audit attend une analyse dans un job distinct qui n'exécute aucun code de la PR, ou une exception écrite et acceptée.

## What Changes

- L'analyse SonarCloud quitte tout job qui exécute du code du dépôt : le job `sonar` de `.github/workflows/pr-ci.yml` n'appelle plus `./mvnw` ni un script de `scripts/`. Il télécharge les classes compilées et les rapports JaCoCo produits par les jobs `Verify`, extrait les sources comme des données, et lance le scanner autonome par une action épinglée par SHA (option A du design, D1).
- Le job `Verify (ubuntu-24.04)` publie en plus les classes principales et de test de chaque module (et, selon la mesure de la PR d'essai, les bibliothèques de compilation) ; l'analyse ne recompile plus rien.
- Les paramètres de l'analyse (hôte, organisation, projet, sources, binaires, rapports) sont écrits dans le workflow ; le job échoue si l'arbre de la PR contient un fichier de configuration du scanner (`sonar-project.properties`), pour que la PR ne puisse pas rediriger l'analyse ni le jeton (D4).
- `scripts/quality/check-workflow-pins.py` passe d'une règle par étape à une règle **par job** : dans un workflow déclenché par `pull_request`, un job dont une étape reçoit un secret (par `env:` d'étape ou `with:` d'une action autre que `docker/login-action`, déjà régie par sa propre règle) n'exécute aucun code du dépôt (`mvnw`, `scripts/`, action locale `./`). La liste fermée `SECRET_WITH_REPOSITORY_CODE_KEPT` disparaît : aucune exception (D2).
- Le type du jeton (jeton d'analyse de projet) est vérifié et consigné, sans la valeur, dans `docs/developer/supply-chain.md`.

Aucune rupture de surface : le check exigé `SonarCloud Code Analysis` (source `application`) ne dépend d'aucun nom de job.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `controles-de-fusion` : l'exigence « Un secret n'accompagne pas la compilation du code d'une pull request » est modifiée (règle par job, plus de liste fermée) ; une exigence ajoutée (l'analyse ne lit aucune configuration du scanner venue de la PR).

## Hors périmètre

- Les jobs qui se connectent à Docker Hub (`DOCKER_LOGIN_KEPT`, AUD-DEP-13) : régis par l'exigence « Les identifiants de registre ne restent pas exposés au code d'une pull request sans nécessité », inchangée.
- Les workflows non déclenchés par `pull_request` (`NVD_API_KEY` de `code-audit.yml`).
- La création et la rotation du secret Dependabot `SONAR_TOKEN` (action du propriétaire).
- Le passage de l'analyse en `workflow_run` (option B) et l'exception écrite (option C) : évaluées et écartées dans le design, sauf décision contraire du propriétaire (D1).
- Les autres constats du sprint 10 et AUD-ARC-02 (autres changements).

## Découpage

Un constat, un workflow (`pr-ci.yml`, jobs `verify` et `sonar`), un gate (`check-workflow-pins.py` et son auto-test). Le changement se fusionne seul ; il ne touche pas les tables Docker du gate.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| L'étape d'analyse reçoit `SONAR_TOKEN` et lance `./mvnw … sonar` sur l'arbre de la PR, dans le job qui vient de lancer `./mvnw … install` | [Existant vérifié] | `.github/workflows/pr-ci.yml:567-579`, `:622-655`, `:657-671` |
| Le gate liste cette étape comme seule exception | [Existant vérifié] | `scripts/quality/check-workflow-pins.py:140-151` |
| `Verify` ne publie que les rapports JaCoCo XML, pas les classes | [Existant vérifié] | `pr-ci.yml:532-541` |
| `.mvn/` contient `maven.config`, lu par chaque invocation du wrapper | [Existant vérifié] | `.mvn/maven.config` |
| Aucun `sonar-project.properties` dans le dépôt, aucune propriété `sonar.*` dans les POM | [Existant vérifié] | `find . -name sonar-project.properties` vide ; `grep sonar pom.xml */pom.xml` vide |
| Le scanner autonome importe la couverture de 14 modules décrits comme répertoires de sources avec le même résultat que le plugin Maven | [Question] | PR d'essai (tâche 2.1) |
| Taille et utilité des bibliothèques de compilation (`sonar.java.libraries`) | [Question] | PR d'essai (tâche 2.1) |
| Un argument de ligne de commande du scanner prime sur un fichier de configuration de l'arbre | [Question] | non vérifié en ligne ; D4 refuse le fichier pour ne pas dépendre de cette priorité |
| Le jeton est un jeton d'analyse de projet | [Question] | tâche manuelle 3.1 (reprise de la tâche 2.1 non faite du changement archivé) |

## Impact

- **Modules du reactor** : aucun.
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune.
- **ADR** : aucun pour l'option recommandée (application de la règle de chaîne d'approvisionnement existante). Si le propriétaire retient l'option C, un ADR `SEC-` d'exception est à rédiger (numéro à lire dans l'index au moment de la tâche).
- **Gates à rejouer** : `scripts/quality/check-workflow-pins.py` et `scripts/quality/test_check_workflow_pins.py`, `scripts/quality/check-ci-wiring.py`, `scripts/quality/check-single-execution.py`, `scripts/quality/check-toolchain-baseline.py`, `scripts/docs/check-current-docs.py`, `python .claude/scripts/run_gates.py`.
- **Plateformes** : CI Ubuntu 24.04 (le job `sonar` et l'envoi des classes) ; le gate s'exécute sous Windows et Linux.
- **Documentation durable** : `docs/developer/supply-chain.md` (section AUD-DEP-05 réécrite), `docs/developer/quality-gates.md`, `.claude/rules/workflows-ci.md` (puce « Secret et code d'une PR »), `docs/architecture/diagrams/capacites/controles-de-fusion.md`.

## Questions ouvertes

- [Question] Option retenue (A recommandée, B ou C) : D1.
- [Question] Bibliothèques de compilation transmises ou analyse sans `sonar.java.libraries` : D3, tranché par la PR d'essai.
