# controles-de-fusion Specification

## Purpose
Définit ce qui rend un contrôle de qualité opposable à une fusion : la liste versionnée des checks que chaque ruleset doit exiger, la vérification hors ligne que chacun résout vers un job qui peut rendre un verdict sur une pull request, le câblage de chaque auto-test de gate dans le job des invariants, le verdict toujours rendu du plugin IntelliJ et le rapport de couverture lu dans le module qui produit les classes.

## Requirements

### Requirement: Les checks exigés pour une fusion sont déclarés dans le dépôt
Le dépôt SHALL déclarer, dans `.github/required-checks.json`, la liste des checks que chaque ruleset de branche doit exiger. Cette liste SHALL contenir au minimum `Verify (ubuntu-24.04)`, `Verify (windows-2022)`, `Dependency vulnerability gate / osv-scan`, `Static invariants (single run)`, `Gitleaks` et `IntelliJ plugin (gate)` pour le ruleset des branches `main` et `develop`.

#### Scenario: Liste complète
- **WHEN** `python scripts/quality/check-ci-wiring.py` s'exécute sur le dépôt
- **THEN** il se termine avec le code 0 et affiche le nombre de contextes résolus vers un job

#### Scenario: Contexte sans job
- **WHEN** un contexte de la liste ne correspond à aucun `name:` de job après développement des matrices
- **THEN** la commande se termine avec le code 1 et nomme le contexte et le workflow déclaré

### Requirement: Un check exigé peut toujours rendre un verdict
`check-ci-wiring.py` SHALL refuser un contexte exigé dont le workflow est déclenché par `pull_request` avec un filtre `paths:` ou `paths-ignore:`, car un tel check resterait en attente sur toute PR hors filtre. Un `if:` de niveau job SHALL rester admis. Une cible introuvable SHALL être un échec et jamais « rien à vérifier » (ADR 0043 § 3).

#### Scenario: Workflow filtré par chemins
- **WHEN** le workflow d'un contexte exigé déclare `paths:` sous `pull_request`
- **THEN** `check-ci-wiring.py` se termine avec le code 1 et nomme le workflow et le filtre

#### Scenario: Job renommé
- **WHEN** le `name:` du job `invariants` change sans que la liste soit mise à jour
- **THEN** `check-ci-wiring.py` se termine avec le code 1 sur le contexte `Static invariants (single run)`

#### Scenario: Workflow introuvable
- **WHEN** le workflow déclaré pour un contexte n'existe pas
- **THEN** la commande se termine avec le code 1 (jamais 0)

### Requirement: L'écart entre le ruleset réel et la liste déclarée se mesure à la demande
`scripts/quality/verify-ruleset.py` SHALL comparer les checks exigés des rulesets réels à `.github/required-checks.json`, lister les contextes manquants et les contextes en trop, et SHALL NOT modifier le ruleset. Il SHALL se terminer avec le code 2 quand l'état réel n'a pas pu être lu. Il SHALL NOT être exécuté par un workflow.

#### Scenario: Ruleset conforme
- **WHEN** `python scripts/quality/test_verify_ruleset.py` rejoue une charge JSON dont les checks exigés sont exactement ceux de la liste
- **THEN** la comparaison conclut à l'égalité (code 0)

#### Scenario: Contexte manquant ou en trop
- **WHEN** la charge rejouée ne contient pas `Gitleaks`, ou contient un contexte absent de la liste
- **THEN** la comparaison conclut à l'écart (code 1) et nomme chaque contexte

#### Scenario: Lecture impossible
- **WHEN** `gh` est absent, non authentifié ou répond une charge illisible
- **THEN** `verify-ruleset.py` se termine avec le code 2 et non avec le code 0

#### Scenario: Aucun appel en CI
- **WHEN** `python scripts/quality/check-ci-wiring.py` examine les workflows
- **THEN** aucune ligne `run:` de workflow ne lance `verify-ruleset.py` sans `--from-json`

### Requirement: Chaque auto-test de gate est exécuté par le job des invariants
Tout fichier `scripts/**/test_*.py` hors `history/`, et tout script qui déclare l'option `--self-test`, SHALL être exécuté par une étape du job `invariants` de `pr-ci.yml`. Les auto-tests des gates JaCoCo (`check-jacoco.py --self-test`) et du garde de références de jalons (`check-milestone-artifact-references.py --self-test`) SHALL en faire partie.

#### Scenario: Auto-test non câblé
- **WHEN** un script `scripts/quality/test_check_exemple.py` existe sans qu'aucune étape de `invariants` ne le lance
- **THEN** `check-ci-wiring.py` se termine avec le code 1 et nomme le fichier

#### Scenario: Option `--self-test` non câblée
- **WHEN** un script déclare `--self-test` et qu'aucune étape de `invariants` ne l'appelle avec cette option
- **THEN** `check-ci-wiring.py` se termine avec le code 1 et nomme le script

#### Scenario: État du dépôt
- **WHEN** `python scripts/quality/check-jacoco.py --self-test` et `python scripts/quality/check-milestone-artifact-references.py --self-test` s'exécutent
- **THEN** les deux se terminent avec le code 0, et `check-ci-wiring.py` constate qu'une étape de `invariants` lance chacun

### Requirement: Un auto-test n'est pas compté comme une seconde exécution d'un gate
`check-single-execution.py` SHALL ne pas compter une ligne contenant `--self-test` comme exécution d'un des contrôles qu'il possède, et SHALL continuer à exiger exactement une exécution de chacun par système d'exploitation dans `pr-ci.yml`. Le gate de remédiation qui interdit l'exécution du gate JaCoCo dans le job `invariants` SHALL viser l'exécution sur un rapport, non la présence du nom du script.

#### Scenario: Auto-test et exécution
- **WHEN** `pr-ci.yml` contient une ligne `check-jacoco.py --self-test` et une seule exécution `check-jacoco.py` par système d'exploitation
- **THEN** `python scripts/quality/check-single-execution.py` se termine avec le code 0

#### Scenario: Deux exécutions
- **WHEN** `pr-ci.yml` contient deux exécutions de `check-jacoco.py` sans `--self-test` pour un même système d'exploitation
- **THEN** `check-single-execution.py` se termine avec le code 1 (`jacoco-linux must run exactly once, found 2`)

#### Scenario: Gate JaCoCo lancé dans les invariants
- **WHEN** une ligne du job `invariants` lance `check-jacoco.py` sans `--self-test`
- **THEN** `python scripts/remediation/check-audit-remediation-v2.py` se termine avec le code 1

### Requirement: Le plugin IntelliJ rend un verdict sur toute pull request
`intellij-plugin.yml` SHALL s'exécuter sur toute pull request vers `main` ou `develop`, sans filtre de chemins au niveau du workflow, et SHALL exposer un job `IntelliJ plugin (gate)` exécuté même si un job amont a échoué ou a été annulé. Ce job SHALL réussir seulement si le calcul de portée a réussi et que chaque job du plugin a réussi ou a été sauté parce que le plugin n'est pas dans la portée. Une erreur du calcul de portée SHALL lancer le plugin (fail-closed).

#### Scenario: Plugin hors portée
- **WHEN** `python scripts/ci/test_plugin_gate.py` rejoue une PR qui ne modifie que `README.md`
- **THEN** la portée est `false` et le verdict avec les jobs du plugin sautés est « réussi »

#### Scenario: Plugin touché et en échec
- **WHEN** le verdict reçoit `plugin=true` et le résultat `failure` (ou `cancelled`) pour un job du plugin
- **THEN** le verdict est « échec » (code 1)

#### Scenario: Saut injustifié
- **WHEN** le verdict reçoit la portée `true` et un job du plugin sauté
- **THEN** le verdict est « échec »

#### Scenario: Calcul de portée en erreur
- **WHEN** la base du diff est introuvable ou l'événement est `workflow_dispatch`
- **THEN** la portée est `true`

### Requirement: La CI du plugin se déclenche quand change le code qui produit le JSON qu'il consomme
La portée du plugin SHALL inclure `minos-application/src/main/java/com/minos/output/`, `minos-domain/`, `minos-app/src/test/resources/characterization/`, `minos-cli/`, `minos-integration-git/` et `minos-intellij/`. Chaque préfixe de la liste SHALL désigner un chemin existant du dépôt.

#### Scenario: Rendu JSON modifié
- **WHEN** la liste des fichiers modifiés contient `minos-application/src/main/java/com/minos/output/ProjectJson.java`
- **THEN** la portée est `true`

#### Scenario: Golden modifié
- **WHEN** la liste des fichiers modifiés contient `minos-app/src/test/resources/characterization/cli-json.golden`
- **THEN** la portée est `true`

#### Scenario: Préfixe devenu mort
- **WHEN** un répertoire de la liste de portée est renommé sans mise à jour de la liste
- **THEN** `python scripts/ci/test_plugin_gate.py` échoue en nommant le préfixe

### Requirement: Le rapport de couverture d'un scope est celui du module qui produit ses classes
`check-jacoco.py` SHALL déterminer le rapport d'un scope qui désigne un module à partir du `<build><directory>` du POM de ce module : `target/site/jacoco/jacoco.xml` à la racine quand le répertoire est `${maven.multiModuleProjectDirectory}/target`, `<module>/target/site/jacoco/jacoco.xml` quand il est absent, et un échec pour toute autre valeur. Le scope `m29-backend-routing` SHALL utiliser ce mécanisme.

#### Scenario: Module redirigé vers le target racine
- **WHEN** `python scripts/quality/check-jacoco.py --self-test` rejoue un POM dont le `<directory>` est `${maven.multiModuleProjectDirectory}/target`
- **THEN** le rapport résolu est `target/site/jacoco/jacoco.xml`

#### Scenario: Module avec son propre target
- **WHEN** l'auto-test rejoue un POM sans `<directory>`
- **THEN** le rapport résolu est `<module>/target/site/jacoco/jacoco.xml`

#### Scenario: Répertoire de build non reconnu
- **WHEN** l'auto-test rejoue un POM dont le `<directory>` a une autre valeur
- **THEN** le gate échoue en nommant le module et la valeur, au lieu de lire un rapport quelconque

#### Scenario: Scope sur le dépôt
- **WHEN** `python scripts/quality/check-jacoco.py` s'exécute après un `verify` complet
- **THEN** le scope `m29-backend-routing` mesure les classes `com/minos/app/McpBackend*` sur le rapport de `minos-app` et son statut est celui des seuils (ligne 0,55, branche 0,30), jamais « rapport introuvable » ni une couverture nulle
