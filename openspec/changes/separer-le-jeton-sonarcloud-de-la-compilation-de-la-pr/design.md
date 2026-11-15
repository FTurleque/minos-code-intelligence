# Design

Changement d'outillage et de configuration CI : seules les sections Contexte, État vérifié, Écarts, Décisions, Vérification et Traçabilité sont développées. Les autres sections du modèle sont « Non applicable : aucun code du produit, aucune surface, aucune donnée persistée n'est touché ».

## Contexte

Motivation : `proposal.md` (Why). Fichiers touchés : `.github/workflows/pr-ci.yml` (job `sonar`), `scripts/quality/check-workflow-pins.py` et son auto-test `scripts/quality/test_check_workflow_pins.py`, `docs/developer/supply-chain.md`. Le job `sonar` s'exécute sur `ubuntu-24.04` ; le gate tourne dans le job `invariants`.

## État vérifié au HEAD

HEAD `89be3fb4`. Lecture ; aucun workflow exécuté.

| Fait | Preuve |
|---|---|
| [Existant vérifié] Le job `sonar` extrait `github.event.pull_request.head.sha` | `.github/workflows/pr-ci.yml:514-519` |
| [Existant vérifié] L'étape « SonarCloud CI-based analysis » déclare `SONAR_TOKEN` dans son `env:` | `pr-ci.yml:544-547` |
| [Existant vérifié] Cette même étape place les rapports JaCoCo, puis lance `./mvnw -B -ntp -DskipTests -Djacoco.skip=true test-compile org.sonarsource.scanner.maven:sonar-maven-plugin:5.1.0.4751:sonar` | `pr-ci.yml:555-588` |
| [Existant vérifié] Elle échoue si le jeton est absent (Dependabot, fork) et si le scanner importe moins de rapports que de modules | `pr-ci.yml:550-554`, `:589-595` |
| [Existant vérifié] Le job ne tourne que si `vars.SONAR_CI_ANALYSIS_ENABLED == 'true'` | `pr-ci.yml:510` |
| [Existant vérifié] Aucune extension Maven de dépôt (`.mvn/extensions.xml` absent) | `.mvn/` : `wrapper/` seul |
| [Existant vérifié] Le gate connaît le motif « code du dépôt » (`scripts/` ou `mvnw`) et une liste fermée pour le login Docker, avec refus des entrées périmées | `scripts/quality/check-workflow-pins.py:126-136`, `:289-330`, `:420-424` |
| [Existant vérifié] Secrets lus par les workflows : `SONAR_TOKEN` (`pr-ci.yml:547`), `DOCKERHUB_USERNAME` / `DOCKERHUB_TOKEN` (5 workflows), `NVD_API_KEY` (`code-audit.yml:244`) | `rg "secrets\." .github/workflows` |
| [Existant vérifié] Deux jobs de workflows `pull_request` posent `DOCKERHUB_USERNAME: ${{ secrets.DOCKERHUB_USERNAME }}` dans leur `env:` de **job**, et leurs étapes lancent `mvnw` ou `scripts/` | `.github/workflows/pr-ci.yml:289-290` (job `verify`) ; `.github/workflows/release-promotion-gate.yml:41-42` (job `docker-upgrade-qualification`) |
| [Existant vérifié] Le job `sonar` n'exécute qu'une invocation Maven, phase et but ensemble ; le reactor a 14 modules qui dépendent les uns des autres | `pr-ci.yml:582-583` ; `pom.xml` (`<modules>`) |
| [Existant vérifié] Le check exigé `SonarCloud Code Analysis` est de source `application` : il ne dépend d'aucun nom de job | `.github/required-checks.json` |

Non mesuré : le type du jeton dans SonarCloud ; la possibilité d'un mode hors ligne ; le comportement de `sonar:sonar` lancé seul sur ce reactor. Sur ce dernier point, le risque est connu : sans phase dans la même invocation, Maven ne résout pas les dépendances entre modules depuis `target/classes`, et le but échoue si les artefacts des modules ne sont pas dans le dépôt Maven local (E5, D1).

## Écarts

| Écart | Source A | Source B | Suite proposée |
|---|---|---|---|
| E1. Le critère de sortie du sprint demande que `SONAR_TOKEN` ne soit dans « aucune étape qui exécute `./mvnw` sur le head de la PR » | `docs/project/audits/2026-11-15/sprints.md` (sprint 2) | l'analyse elle-même est un but Maven lancé par `./mvnw` : la seconde étape reste dans ce cas | Seconde branche du même critère : entrée nominative du gate, avec raison (D2). Risque résiduel écrit (D3) |
| E2. L'audit propose « `-o` hors ligne si possible » | action d'AUD-DEP-05 | le plugin Sonar télécharge son moteur depuis SonarCloud à l'exécution, hors résolution Maven ; `-o` ne vaut que si le plugin et ses dépendances sont déjà dans le dépôt local | Résoudre le plugin dans l'étape sans secret, tenter `-o` dans l'étape d'analyse, garder ou retirer l'option selon la mesure en CI (tâche 1.2) |
| E3. Séparer les étapes ne retire pas tout code de la PR de l'étape d'analyse : Maven y lit encore les POM du candidat | action d'AUD-DEP-05 | un POM peut déclarer une extension de build chargée à la lecture du modèle | Risque résiduel nommé dans la raison de l'entrée et dans `supply-chain.md` ; suppression complète = scanner autonome (D3, hors périmètre) |
| E5. Un but `sonar` lancé seul après un `test-compile` séparé échoue en général à résoudre les modules du reactor | action d'AUD-DEP-05 (« `sonar:sonar` seul ») | résolution du reactor liée aux phases de l'invocation courante | L'étape sans secret installe les artefacts (`install -DskipTests`) ; la forme retenue est mesurée en CI **avant** d'être figée (D1, tâche 1.2) |
| E6. Une règle limitée à l'`env:` d'étape se contourne en remontant le secret au niveau du job ; une règle « toute étape qui reçoit un secret » ferait échouer le dépôt réel sur `DOCKERHUB_USERNAME` | `pr-ci.yml:289-290`, `release-promotion-gate.yml:41-42` | — | La règle porte sur l'`env:` d'étape **et** interdit tout `secrets.` dans un `env:` de job ou de workflow, hors `DOCKERHUB_USERNAME` (D2) |
| E4. Une règle générale « secret + code du dépôt » toucherait `NVD_API_KEY` | `code-audit.yml:244` | ce workflow n'est pas déclenché par `pull_request` | Règle bornée aux workflows déclenchés par `pull_request` (D2) |

## Décisions

### D1 — Deux étapes : compiler sans secret, analyser avec

[Décision] Étape « Build the candidate » : placement des rapports, puis `./mvnw -B -ntp -DskipTests -Djacoco.skip=true install` (les artefacts des 14 modules entrent dans le dépôt Maven local du runner, E5) et résolution du plugin Sonar ; aucun `env:` de secret. Étape « SonarCloud CI-based analysis » : `env: SONAR_TOKEN`, contrôle de présence du jeton, `./mvnw … org.sonarsource.scanner.maven:sonar-maven-plugin:5.1.0.4751:sonar` seul, contrôle du nombre de rapports importés. La forme est **mesurée avant d'être figée** (tâche 1.2, sur une PR d'essai) : si `test-compile` suffit dans l'étape sans secret, il est préféré à `install` ; si `install` est retenu, les artefacts `com.minos` installés sont retirés du dépôt local avant la sauvegarde du cache Maven de `setup-java`, pour qu'un build de PR n'alimente pas le cache. Dans les deux cas l'étape qui porte le jeton ne lance aucune phase : l'exigence tient sans repli. Pour ne pas construire inutilement une PR sans jeton, l'étape de build est conditionnée par une sortie d'une étape préalable qui ne lit que la présence du secret, sans l'exposer. Écarté : `env:` de niveau job (défaut actuel en plus large, et refusé par D2).

### D2 — Règle du gate bornée aux workflows de pull request, liste fermée nominative

[Décision] `check-workflow-pins.py` reçoit deux contrôles, bornés aux workflows dont le `on:` contient `pull_request`. (1) Une étape `run:` dont l'`env:` d'étape référence `secrets.` et dont le script correspond à `REPOSITORY_CODE` est refusée hors de la table `SECRET_WITH_REPOSITORY_CODE_KEPT = {(workflow, job, nom de l'étape): raison}` ; une étape de la table qui nomme une phase (`compile`, `test-compile`, `test`, `package`, `verify`, `install`) est refusée ; une entrée périmée est refusée (mécanique de `find_stale_docker_login_entries`). (2) Un `env:` de job ou de workflow qui référence `secrets.` est refusé, sauf la seule variable `DOCKERHUB_USERNAME` (constante du gate) : c'est un identifiant, pas un jeton, et les jobs qui le posent (`pr-ci.yml:289-290`, `release-promotion-gate.yml:41-42`) restent régis par `DOCKER_LOGIN_KEPT` et la déconnexion. Sans (2), remonter `SONAR_TOKEN` au niveau du job contournerait (1). `github.token` n'est pas un `secrets.` et reste hors de cette règle (jeton en lecture seule, permissions minimales déclarées par job). Écarté : couvrir l'`env:` de job par des entrées nominatives (deux exemptions de plus pour un identifiant non secret) ; règle sur tous les workflows (E4) ; simple commentaire dans le YAML.

### D3 — Garder le plugin Maven Sonar, écrire le risque résiduel

[Proposition] L'étape d'analyse continue de lancer `mvnw` sur l'arbre de la PR (E3). Alternative : scanner autonome par action épinglée, qui n'exécute aucun code du dépôt mais demande de décrire à la main les 14 modules, leurs sources et leurs rapports. Recommandation : garder le plugin dans ce lot (effort S, le chemin exposé se réduit des plugins et processeurs de la compilation au seul modèle Maven) et rouvrir la question si le jeton s'avère être un jeton d'utilisateur (tâche 2.1).

## Vérification

- Auto-test : `python scripts/quality/test_check_workflow_pins.py` (cas ajoutés : compilation avec secret, étape hors liste, entrée périmée, secret dans un `env:` de job, `DOCKERHUB_USERNAME` de job admis, workflow sans `pull_request`).
- Gates : `python scripts/quality/check-workflow-pins.py`, `python scripts/quality/check-ci-wiring.py`, `python scripts/quality/check-single-execution.py`, `python scripts/quality/check-toolchain-baseline.py`, `python .claude/scripts/run_gates.py`.
- CI : sur la PR du changement, le job `SonarCloud analysis (Linux and Windows coverage)` est vert, importe autant de rapports que de modules, et le check `SonarCloud Code Analysis` rend son verdict. Non vérifiable localement.
- Non vérifié par ce changement : une PR Dependabot (secret Dependabot `SONAR_TOKEN`, action du propriétaire).

## Traçabilité

| Exigence (capacité › titre) | Scénario | Conception ou diagramme | Tâche | Test ou preuve |
|---|---|---|---|---|
| controles-de-fusion › Un secret n'accompagne pas la compilation du code d'une pull request | Compilation avec le jeton d'analyse | D2 | 1.1, 1.3 | `test_check_workflow_pins.py` — cas à écrire |
| idem | Étape hors liste qui reçoit un secret et lance un script du dépôt | D2 | 1.1, 1.3 | idem — à écrire |
| idem | Dépôt réel | D1, D2 | 1.2, 1.3, 1.4 | `check-workflow-pins.py` ; run CI de la PR — manquante jusqu'à 1.4 |
| idem | Entrée de liste sans objet | D2 | 1.1, 1.3 | `test_check_workflow_pins.py` — à écrire |
| idem | Secret remonté au niveau du job | D2 (E6) | 1.1, 1.3 | idem — à écrire |
| idem | Identifiant Docker Hub au niveau du job | D2 (E6) | 1.1, 1.3 | idem — à écrire |
| idem | Workflow sans déclencheur pull_request | D2 (E4) | 1.1, 1.3 | idem — à écrire |

## Questions ouvertes

- [Question] Type du jeton SonarCloud (analyse de projet ou utilisateur) : tâche manuelle 2.1 ; un jeton d'utilisateur est à remplacer.
- [Question] Mode hors ligne de l'étape d'analyse, et `test-compile` ou `install` dans l'étape sans secret : tranchés par la mesure de la tâche 1.2.

## Ordre de fusion

Ce changement et `borner-la-qualification-docker-aux-pr-concernees` modifient tous deux `scripts/quality/check-workflow-pins.py` et son auto-test, dans des tables différentes (`SECRET_WITH_REPOSITORY_CODE_KEPT` ici, `DOCKER_LOGIN_KEPT` là). Celui-ci, sans décision du propriétaire en attente, se fusionne d'abord ; l'autre se rebase. Le contrôle (2) de D2 lit l'`env:` de job de `release-promotion-gate.yml` : si l'autre changement y ajoute un job, il n'y pose aucun `secrets.` hors `DOCKERHUB_USERNAME`.
