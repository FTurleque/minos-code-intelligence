# controles-de-fusion Specification

## Purpose
Définit ce qui rend un contrôle de qualité opposable à une fusion : la liste versionnée des checks que chaque ruleset doit exiger, la vérification hors ligne que chacun résout vers un job qui peut rendre un verdict sur une pull request, le câblage de chaque auto-test de gate dans le job des invariants, le verdict toujours rendu du plugin IntelliJ et le rapport de couverture lu dans le module qui produit les classes.

## Requirements

### Requirement: Les checks exigés pour une fusion sont déclarés dans le dépôt
Le dépôt SHALL déclarer, dans `.github/required-checks.json`, la liste des checks que chaque ruleset de branche doit exiger. Cette liste SHALL contenir au minimum `Verify (ubuntu-24.04)`, `Verify (windows-2022)`, `Dependency vulnerability gate / osv-scan`, `Static invariants (single run)`, `Gitleaks` et `IntelliJ plugin (gate)` pour le ruleset de la branche `main`.

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
`intellij-plugin.yml` SHALL s'exécuter sur toute pull request vers `main`, sans filtre de chemins au niveau du workflow, et SHALL exposer un job `IntelliJ plugin (gate)` exécuté même si un job amont a échoué ou a été annulé. Ce job SHALL réussir seulement si le calcul de portée a réussi et que chaque job du plugin a réussi ou a été sauté parce que le plugin n'est pas dans la portée. Une erreur du calcul de portée SHALL lancer le plugin (fail-closed).

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

### Requirement: La couverture globale du reactor est bornée sur chaque système de qualification
Le gate JaCoCo SHALL évaluer un scope `global` couvrant `com/minos/`, avec un plancher de lignes et de branches déclaré pour Linux et pour Windows. Il SHALL échouer quand la mesure est sous le plancher du système courant et SHALL écrire la mesure, le plancher et les exclusions nommées du scope dans `jacoco-gate.json`.

#### Scenario: Mesure sous le plancher du système courant
- **GIVEN** un rapport JaCoCo dont la couverture de `com/minos/` est inférieure au plancher déclaré pour le système courant
- **WHEN** `python scripts/quality/check-jacoco.py <rapport>` est exécuté
- **THEN** le gate échoue en nommant `global`, la mesure et le plancher

#### Scenario: Résultat publié
- **GIVEN** un rapport dont la couverture respecte le plancher
- **WHEN** le gate est exécuté
- **THEN** `jacoco-gate.json` contient pour `global` la mesure, le plancher du système courant et la liste des exclusions

#### Scenario: Exclusion sans objet
- **GIVEN** un scope dont une exclusion ne désigne aucune classe du rapport
- **WHEN** `python scripts/quality/check-jacoco.py --self-test` est exécuté
- **THEN** le scénario correspondant constate l'échec du scope

### Requirement: Un scope sans plancher pour le système courant échoue
Quand un scope déclare ses planchers par système, l'absence de plancher pour le système courant SHALL faire échouer le gate. Seuls un scope réservé à un autre système et une exclusion explicite en ligne de commande SHALL produire le statut `SKIPPED`, avec leur raison.

#### Scenario: Plancher manquant
- **GIVEN** un scope qui déclare un plancher pour Linux seulement, sans être réservé à Linux
- **WHEN** l'auto-test du gate l'évalue sur un autre système
- **THEN** le scope est en échec et non sauté

#### Scenario: Scope réservé à un autre système
- **GIVEN** un scope réservé à un système qui n'est pas le système courant
- **WHEN** le gate est exécuté
- **THEN** le scope est `SKIPPED` et la raison nomme le système courant

### Requirement: Les classes du cœur nommées sont sous un scope ciblé
Le codec binaire des snapshots, les stores d'état d'index et d'empreinte, le registre de projets, les primitives d'E/S de `com/minos/io/` et la rétention des dossiers de run SHALL chacun appartenir à un scope JaCoCo ciblé. Le gate SHALL échouer si l'une de ces classes disparaît du rapport ou passe sous son plancher.

#### Scenario: Classe renommée ou supprimée
- **GIVEN** un rapport JaCoCo où `SnapshotBinaryCodecSupport` n'apparaît plus
- **WHEN** le gate est exécuté
- **THEN** il échoue en nommant le préfixe qui ne désigne plus aucune classe

#### Scenario: Érosion d'une primitive d'E/S
- **GIVEN** un rapport où la couverture de `com/minos/io/` est sous le plancher du scope
- **WHEN** le gate est exécuté
- **THEN** il échoue en nommant le scope des primitives d'E/S confinées

### Requirement: Une classe propre à une plateforme est jugée sur sa plateforme
Une classe qui ne s'exécute que sur un système SHALL appartenir à un scope réservé à ce système et y porter un plancher de classe. La propriété de processus par cgroup SHALL être jugée sur Linux et la propriété par Job Object sur Windows ; aucune des deux SHALL NOT être évaluée sur l'autre système.

#### Scenario: Job Object sous Windows
- **GIVEN** un rapport produit sous Windows où `WindowsJobObjectProcessOwnership` est sous son plancher de classe
- **WHEN** le gate est exécuté sous Windows
- **THEN** il échoue en nommant le scope Windows et la classe

#### Scenario: Job Object sous Linux
- **GIVEN** le gate exécuté sous Linux
- **WHEN** il atteint le scope Windows
- **THEN** ce scope est `SKIPPED` et n'entre pas dans le verdict

### Requirement: Un plancher de classe empêche un store de se cacher dans son paquet
Dans un scope défini par un préfixe de paquet, une classe désignée SHALL porter son propre plancher. Le store PostgreSQL des observations d'exécution SHALL en porter un dans le scope du stockage PostgreSQL. Là où ce scope n'est pas évalué, le résultat SHALL le dire (`SKIPPED`) et SHALL NOT être présenté comme réussi.

#### Scenario: Classe faible dans un paquet bien couvert
- **GIVEN** un rapport où le paquet respecte son plancher mais où la classe désignée est sous le sien
- **WHEN** le gate est exécuté
- **THEN** il échoue en nommant la classe, sa mesure et son plancher

#### Scenario: Scope écarté sous Windows
- **GIVEN** le gate exécuté avec `--skip-scope m30-postgresql-pgvector`
- **WHEN** `jacoco-gate.json` est lu
- **THEN** le scope y figure avec le statut `SKIPPED` et sa raison

### Requirement: Les fonctions de scripts recopiées restent identiques
Une fonction PowerShell recopiée entre scripts livrés, d'installation, de release ou de CI SHALL figurer dans une liste fermée de jumelles. Un gate exécuté par le job des invariants SHALL échouer si deux copies listées diffèrent hors fins de ligne et espaces de fin de ligne, si un fichier ou une définition listée manque, si une entrée nomme moins de deux fichiers, ou si une copie identique n'est couverte par aucune entrée.

#### Scenario: Dépôt réel accepté
- **GIVEN** le dépôt, où chaque fonction listée a le même corps dans tous ses fichiers
- **WHEN** `python scripts/quality/check-twin-script-functions.py` est exécuté
- **THEN** le gate se termine avec le code 0 et affiche le nombre d'entrées et de copies comparées

#### Scenario: Correction faite dans une seule copie
- **GIVEN** une copie du dépôt où une ligne de `Read-BoundedUtf8` a été modifiée dans un seul des deux scripts
- **WHEN** le gate est exécuté avec `--root` sur cette copie
- **THEN** il se termine avec un code non nul en nommant la fonction et les deux fichiers

#### Scenario: Entrée morte
- **GIVEN** une copie du dépôt où un fichier listé est absent, ne définit plus la fonction listée, ou reste seul dans son entrée
- **WHEN** le gate est exécuté avec `--root` sur cette copie
- **THEN** il se termine avec un code non nul en nommant l'entrée

#### Scenario: Copie non déclarée
- **GIVEN** une copie du dépôt où une fonction d'un script du périmètre est recopiée à l'identique dans un autre script du périmètre sans entrée qui les couvre
- **WHEN** le gate est exécuté avec `--root` sur cette copie
- **THEN** il se termine avec un code non nul en nommant la fonction et les deux fichiers

#### Scenario: Fins de ligne différentes
- **GIVEN** deux copies listées dont les corps ne diffèrent que par CRLF et LF ou par des espaces de fin de ligne
- **WHEN** le gate est exécuté sur cet arbre
- **THEN** elles sont tenues pour identiques

#### Scenario: Auto-test du gate
- **GIVEN** le dépôt
- **WHEN** `python scripts/quality/test_check_twin_script_functions.py -v` est exécuté
- **THEN** il réussit, avec au moins un cas refusé par règle et le dépôt réel accepté

### Requirement: La vérification du câblage CI des gates n'est écrite qu'une fois
La vérification qu'un gate et son auto-test sont des étapes sans condition du job des invariants SHALL être définie dans un seul module de `scripts/quality`, importé par les gates qui s'en servent ; chaque gate SHALL garder son auto-test et son refus d'une étape absente ou conditionnée. Un gate qui ne peut pas importer ce module SHALL échouer avec un code non nul, sans repli sur une copie locale.

#### Scenario: Définition unique
- **GIVEN** le dépôt après le changement
- **WHEN** `git grep -l "def find_ci_wiring_violations" -- scripts` est exécuté
- **THEN** il ne renvoie qu'un fichier

#### Scenario: Auto-tests conservés
- **GIVEN** le dépôt après le changement
- **WHEN** `python scripts/quality/test_check_compose_limits.py -v` puis `python scripts/quality/test_check_image_pins.py -v` sont exécutés
- **THEN** les deux réussissent, y compris leurs cas où l'étape du gate manque ou porte une condition `if:`

#### Scenario: Étape conditionnée refusée
- **GIVEN** une copie du dépôt où l'étape du gate d'identité dans le job des invariants porte `if: false`
- **WHEN** `python scripts/quality/check-twin-script-functions.py` est exécuté avec `--root` sur cette copie
- **THEN** il se termine avec un code non nul en nommant l'étape

#### Scenario: Module partagé absent
- **GIVEN** une copie de `scripts/quality` sans le module partagé
- **WHEN** `check-compose-limits.py` ou `check-image-pins.py` de cette copie est exécuté
- **THEN** il se termine avec un code non nul

### Requirement: Le jeton du dépôt n'est pas conservé après le checkout
Chaque étape `actions/checkout` d'un workflow SHALL déclarer `persist-credentials: false`. Le gate des workflows SHALL échouer sur un checkout qui ne le déclare pas.

#### Scenario: Checkout sans l'option
- **GIVEN** une copie du dépôt où un workflow contient un `actions/checkout` sans `persist-credentials: false`
- **WHEN** `python scripts/quality/check-workflow-pins.py` est exécuté sur cette copie
- **THEN** le gate échoue en nommant le fichier et la ligne

#### Scenario: Dépôt réel
- **GIVEN** les workflows du dépôt
- **WHEN** le gate est exécuté
- **THEN** il réussit et compte les checkouts contrôlés

### Requirement: Chaque job a un délai maximal
Tout job qui s'exécute sur un runner SHALL déclarer `timeout-minutes`. Un job qui appelle un workflow réutilisable est la seule exception. Le gate SHALL échouer sur un job à `runs-on` sans délai.

#### Scenario: Job sans délai
- **GIVEN** une copie du dépôt où un job à `runs-on` n'a pas de `timeout-minutes`
- **WHEN** le gate est exécuté sur cette copie
- **THEN** il échoue en nommant le workflow et le job

#### Scenario: Appel de workflow réutilisable
- **GIVEN** un job défini par `uses:` sans `runs-on`
- **WHEN** le gate est exécuté
- **THEN** ce job n'est pas signalé

### Requirement: Le runner et l'outil de build d'un workflow sont épinglés
Aucun job SHALL NOT s'exécuter sur une image de runner flottante (`*-latest`). Un workflow qui construit le plugin IntelliJ SHALL utiliser le wrapper Gradle du dépôt et SHALL NOT installer une version de Gradle par `gradle-version:`.

#### Scenario: Runner flottant
- **GIVEN** une copie du dépôt où un job déclare `runs-on: ubuntu-latest`
- **WHEN** le gate est exécuté sur cette copie
- **THEN** il échoue en nommant le job

#### Scenario: Gradle installé hors wrapper
- **GIVEN** une copie du dépôt où un workflow déclare `gradle-version:`
- **WHEN** `python scripts/quality/check-toolchain-baseline.py` est exécuté sur cette copie
- **THEN** le gate échoue en nommant le fichier et la ligne

### Requirement: Les identifiants de registre ne restent pas exposés au code d'une pull request sans nécessité
Dans un job qui se connecte à un registre d'images, une étape `run:` postérieure à la connexion SHALL être précédée d'une déconnexion, sauf pour les jobs d'une liste fermée dont la raison est écrite. Le jeton utilisé SHALL être en lecture seule et sa portée SHALL être documentée.

#### Scenario: Script exécuté sans déconnexion
- **GIVEN** une copie du dépôt où un job hors de la liste fermée exécute un script après `docker/login-action` sans `docker logout`
- **WHEN** le gate des workflows est exécuté sur cette copie
- **THEN** il échoue en nommant le job

#### Scenario: Job de la liste fermée
- **GIVEN** un job de la liste fermée, avec sa raison
- **WHEN** le gate est exécuté
- **THEN** ce job est accepté et la liste est affichée dans la sortie

#### Scenario: Entrée de liste sans objet
- **GIVEN** une liste fermée qui nomme un job qui ne se connecte plus à un registre
- **WHEN** le gate est exécuté
- **THEN** il échoue en nommant l'entrée périmée

### Requirement: Un script POSIX est extrait en LF quel que soit le réglage du clone
Tout fichier suivi par Git dont le nom finit par `.sh`, ou dont la première ligne désigne un interpréteur de commandes POSIX, SHALL porter l'attribut `eol=lf` et SHALL être enregistré sans retour chariot. Un gate du job des invariants SHALL échouer en nommant tout fichier qui déroge. Le wrapper Maven POSIX et son fichier de propriétés SHALL être couverts.

#### Scenario: État du dépôt
- **GIVEN** le dépôt au commit candidat
- **WHEN** `python scripts/quality/check-posix-script-eol.py` est exécuté
- **THEN** il se termine avec le code 0 et affiche le nombre de scripts POSIX contrôlés, supérieur à zéro

#### Scenario: Clone Windows avec conversion automatique
- **GIVEN** un clone neuf fait avec `git -c core.autocrlf=true clone`
- **WHEN** `git ls-files --eol '*.sh' mvnw` est exécuté dans ce clone
- **THEN** chaque ligne commence par `i/lf    w/lf` et porte `attr/text eol=lf`

#### Scenario: Script ajouté sans règle
- **GIVEN** un dépôt temporaire où un fichier `outil` commençant par `#!/usr/bin/env bash` est suivi sans attribut `eol=lf`
- **WHEN** `python scripts/quality/check-posix-script-eol.py --self-test` l'évalue
- **THEN** le cas constate le code 1 et un message qui nomme le fichier et la règle à ajouter à `.gitattributes`

#### Scenario: Script enregistré avec des retours chariot
- **GIVEN** un dépôt temporaire où un `*.sh` porte l'attribut mais a été enregistré en CRLF
- **WHEN** l'auto-test l'évalue
- **THEN** le cas constate le code 1

#### Scenario: Auto-tests des scripts shell sur le poste
- **GIVEN** un clone Windows à `core.autocrlf=true` remis à niveau
- **WHEN** `python scripts/dev/test_start_test_postgres.py` et `python scripts/deploy/test_cgroup_root_validation.py` sont exécutés avec le bash de Git pour Windows
- **THEN** les deux se terminent avec le code 0

### Requirement: Le contrôle des fins de ligne échoue fermé
Le gate des fins de ligne SHALL lire les attributs et le contenu enregistré par Git lui-même. Quand Git est absent, quand la racine n'est pas un dépôt ou quand aucun script POSIX n'est trouvé, il SHALL se terminer avec le code 1 et un message qui nomme la cause ; il SHALL NOT conclure à un succès sur zéro fichier.

#### Scenario: Aucun script trouvé
- **GIVEN** un dépôt temporaire sans aucun script POSIX suivi
- **WHEN** l'auto-test du gate l'évalue
- **THEN** le cas constate le code 1

#### Scenario: Racine hors dépôt
- **GIVEN** un dossier qui n'est pas un dépôt Git
- **WHEN** l'auto-test évalue le gate sur ce dossier
- **THEN** le cas constate le code 1 et un message qui nomme la cause sans afficher de chemin absolu

#### Scenario: Git absent
- **GIVEN** un dépôt temporaire conforme et un `PATH` qui ne nomme qu'un dossier vide
- **WHEN** l'auto-test évalue le gate sur ce dépôt
- **THEN** le cas constate le code 1 et un message qui dit que `git` est introuvable

#### Scenario: Auto-test câblé
- **GIVEN** un `pr-ci.yml` dont le job `invariants` ne lance pas `check-posix-script-eol.py --self-test`
- **WHEN** `python scripts/quality/check-ci-wiring.py` est exécuté
- **THEN** il se termine avec le code 1 et nomme le script

### Requirement: Tout gate a un auto-test
Tout script `scripts/**/check-*.py` hors `history/` SHALL déclarer l'option `--self-test` ou posséder un fichier `test_*.py` jumeau dans son dossier. `check-ci-wiring.py` SHALL échouer en nommant tout gate qui n'a ni l'un ni l'autre et qui ne figure pas dans la liste fermée des gates en attente.

#### Scenario: État du dépôt
- **GIVEN** le dépôt au commit candidat
- **WHEN** `python scripts/quality/check-ci-wiring.py` est exécuté
- **THEN** il se termine avec le code 0 et son message de succès compte les gates dotés d'un auto-test et les gates en attente

#### Scenario: Gate ajouté sans auto-test
- **GIVEN** un arbre où `scripts/quality/check-exemple.py` existe sans `--self-test`, sans `test_check_exemple.py` et hors de la liste fermée
- **WHEN** `python scripts/quality/test_check_ci_wiring.py` évalue cet arbre
- **THEN** le cas constate le code 1 et un message qui nomme `scripts/quality/check-exemple.py`

#### Scenario: Les dix gates du constat
- **GIVEN** les huit gates `check-*-consistency.py`, `check-current-docs-vertical-extension.py` et `scripts/docs/check-current-docs.py`
- **WHEN** chacun est lancé avec `--self-test`
- **THEN** chacun se termine avec le code 0 et affiche le nombre de mutations refusées, supérieur à zéro

### Requirement: L'auto-test d'un gate littéral refuse une mutation par fait affirmé
L'auto-test d'un gate qui affirme des chaînes ou des motifs lus dans des fichiers SHALL, pour chaque fait affirmé sur l'état du dépôt, appliquer une mutation qui le rend faux et constater que le gate échoue en nommant le fichier. Il SHALL échouer quand une mutation est acceptée, quand un fait n'a produit aucune mutation ou quand aucun fait n'a été relevé. Il SHALL NOT modifier un fichier du dépôt.

#### Scenario: Littéral exigé retiré
- **GIVEN** un fait « le fichier contient ce littéral » d'un gate de cohérence
- **WHEN** `--self-test` retire ce littéral d'une copie en mémoire du fichier et réévalue le gate
- **THEN** le gate échoue avec un message qui nomme le fichier

#### Scenario: Littéral interdit introduit
- **GIVEN** un fait « le fichier ne contient pas ce littéral »
- **WHEN** `--self-test` l'introduit dans la copie et réévalue le gate
- **THEN** le gate échoue

#### Scenario: Règle devenue vacuitaire
- **GIVEN** un gate dont une règle accepte la copie mutée
- **WHEN** `--self-test` est exécuté
- **THEN** il se termine avec le code 1 et nomme la règle, le fichier et le littéral

#### Scenario: Aucun fait relevé
- **GIVEN** un gate dont l'évaluation ne relève aucun fait
- **WHEN** `--self-test` est exécuté
- **THEN** il se termine avec le code 1 et non par un succès sur zéro mutation

#### Scenario: Dépôt intact
- **GIVEN** un arbre de travail propre
- **WHEN** les dix auto-tests sont exécutés
- **THEN** `git status --porcelain` ne liste aucun fichier

### Requirement: Les gates sans auto-test sont une liste fermée qui ne peut que diminuer
La liste des gates tolérés sans auto-test SHALL être déclarée dans `check-ci-wiring.py`, chaque entrée avec sa raison. Le gate SHALL échouer quand une entrée désigne un script absent ou un script qui a désormais un auto-test, de sorte que la liste ne puisse ni s'allonger sans relecture ni garder une entrée périmée.

#### Scenario: Entrée périmée
- **GIVEN** une liste fermée qui nomme un gate déclarant maintenant `--self-test`
- **WHEN** `python scripts/quality/test_check_ci_wiring.py` évalue cet arbre
- **THEN** le cas constate le code 1 et un message qui demande de retirer l'entrée

#### Scenario: Entrée sans script
- **GIVEN** une liste fermée qui nomme un script qui n'existe pas
- **WHEN** l'auto-test évalue cet arbre
- **THEN** le cas constate le code 1 et nomme l'entrée

### Requirement: Les zones créées après la mesure de référence sont sous un scope nominatif
Le gate JaCoCo SHALL évaluer un scope nominatif pour le module de l'explorateur sémantique, pour la voie d'indexation WSL2 et pour la projection 2D, chacun avec un plancher de lignes et de branches tiré d'une mesure citée du rapport agrégé de la CI. Il SHALL échouer quand un préfixe de ces scopes ne désigne plus aucune classe du rapport, et SHALL NOT laisser le scope `global` seul juge de ces zones.

#### Scenario: Les trois scopes sont évalués
- **GIVEN** un rapport agrégé produit par `./mvnw -B -ntp verify`
- **WHEN** `python scripts/quality/check-jacoco.py` est exécuté
- **THEN** `jacoco-gate.json` contient `explorer-surface`, `wsl-lane` et `semantic-projection` avec leur mesure et leur plancher pour le système courant

#### Scenario: Les tests d'une zone disparaissent
- **GIVEN** un rapport où la couverture des classes de `com/minos/explorer/` est tombée sous le plancher du scope alors que `global` respecte le sien
- **WHEN** le gate est exécuté
- **THEN** il se termine avec le code 1 et nomme `explorer-surface`, la mesure et le plancher

#### Scenario: Une zone est renommée sans son scope
- **GIVEN** un rapport où aucune classe ne commence par `com/minos/runtime/local/wsl/`
- **WHEN** le gate est exécuté
- **THEN** il échoue en nommant le préfixe mort du scope `wsl-lane`

#### Scenario: Déclaration retirée du gate
- **GIVEN** une copie de la table des scopes privée de `semantic-projection`
- **WHEN** `python scripts/quality/check-jacoco.py --self-test` l'évalue
- **THEN** le cas correspondant de l'auto-test échoue en nommant le scope manquant

### Requirement: La garde de la surface HTTP locale a un plancher de classe
Dans le scope de l'explorateur, les classes qui décident de servir une requête (contrôle de l'hôte, de l'origine et du jeton, jeton de session, politique d'écoute) SHALL chacune porter un plancher de lignes et de branches. Le gate SHALL échouer quand l'une d'elles passe sous son plancher, même si le scope respecte le sien.

#### Scenario: Garde affaiblie dans un module bien couvert
- **GIVEN** un rapport où `explorer-surface` respecte son plancher et où `RequestGuard` est sous son plancher de classe
- **WHEN** `python scripts/quality/check-jacoco.py` est exécuté
- **THEN** le gate se termine avec le code 1 et nomme `com/minos/explorer/RequestGuard`, sa mesure et son plancher

#### Scenario: Classe de garde absente du rapport
- **GIVEN** un rapport où `com/minos/explorer/SessionToken` ne figure plus
- **WHEN** le gate est exécuté
- **THEN** il échoue en nommant la classe et ne la compte pas comme conforme

### Requirement: Le budget mémoire des vues a un plancher de classe
La classe qui tient le budget mémoire des vues de requête SHALL appartenir à un scope ciblé du stockage et y porter un plancher de classe. Le gate SHALL échouer quand elle disparaît du rapport ou passe sous ce plancher.

#### Scenario: Budget sous son plancher
- **GIVEN** un rapport où `com/minos/storage/codec/QueryViewMemoryBudget` est sous son plancher de classe
- **WHEN** `python scripts/quality/check-jacoco.py` est exécuté
- **THEN** le gate échoue en nommant la classe, sa mesure et son plancher

#### Scenario: État du dépôt
- **GIVEN** un rapport agrégé produit par `./mvnw -B -ntp verify` au commit candidat
- **WHEN** `python scripts/quality/check-jacoco.py` est exécuté
- **THEN** il se termine avec le code 0 et `target/m21-quality/jacoco-gate.json` porte la mesure et le plancher de la classe

### Requirement: Aucun test ne passe à vide derrière un retour anticipé de plateforme
Dans les sources de test Java de tous les modules, plugin IntelliJ compris, une instruction `return` sans valeur gardée par une condition sur le système d'exploitation SHALL être refusée par un gate du job des invariants, que la garde tienne sur une ou plusieurs lignes et quelle que soit l'expression qui lit le système. Un test propre à un système SHALL être restreint par `@EnabledOnOs` et SHALL figurer comme sauté, avec sa raison, dans le rapport de l'autre système.

#### Scenario: État du dépôt
- **GIVEN** le dépôt au commit candidat
- **WHEN** `python scripts/quality/check-test-platform-guards.py` est exécuté
- **THEN** il se termine avec le code 0 et affiche le nombre de fichiers de test lus et le nombre d'exceptions déclarées

#### Scenario: Garde sur deux lignes
- **GIVEN** un fichier de test contenant `if (!CommandLocator.isWindows()) {` suivi de `return;` sur la ligne suivante
- **WHEN** `python scripts/quality/check-test-platform-guards.py --self-test` l'évalue
- **THEN** le cas constate le code 1 et un message qui nomme le fichier et la ligne

#### Scenario: Garde par variable locale
- **GIVEN** un fichier de test qui lit `os.name` dans une variable puis contient `if (!os.contains("linux")) return;`
- **WHEN** l'auto-test l'évalue
- **THEN** le cas constate le code 1

#### Scenario: Test Windows sous Linux
- **GIVEN** les tests de `minos-runtime-local` exécutés sous Linux
- **WHEN** le rapport Surefire de `CommandLocatorTest` est lu
- **THEN** `realWindowsBatchInvocationUsesCanonicalSystem32Cmd` et `realWindowsCmdPreservesAdversarialQuotedArguments` y figurent comme sautés

#### Scenario: Aucun fichier lu
- **GIVEN** une racine sans aucune source de test Java
- **WHEN** l'auto-test évalue le gate sur cette racine
- **THEN** le cas constate le code 1 et non un succès sur zéro fichier

### Requirement: Les retours anticipés de plateforme tolérés sont une liste fermée et motivée
Un retour anticipé gardé par le système d'exploitation SHALL n'être toléré que hors d'une méthode de test (extension ou outillage de test) et seulement s'il figure dans la liste d'exceptions du gate, par fichier et par nombre d'occurrences, avec sa raison. Le gate SHALL échouer quand une exception ne correspond plus à aucune occurrence, et SHALL NOT accepter une exception située dans une méthode annotée `@Test`.

#### Scenario: Exception devenue sans objet
- **GIVEN** une liste d'exceptions qui déclare deux occurrences pour un fichier qui n'en contient plus qu'une
- **WHEN** l'auto-test du gate l'évalue
- **THEN** le cas constate le code 1 et un message qui nomme le fichier et les deux nombres

#### Scenario: Exception dans une méthode de test
- **GIVEN** une exception déclarée pour un fichier dont l'occurrence est dans une méthode annotée `@Test`
- **WHEN** l'auto-test l'évalue
- **THEN** le cas constate le code 1

#### Scenario: Auto-test câblé
- **GIVEN** un `pr-ci.yml` dont le job `invariants` ne lance pas `check-test-platform-guards.py --self-test`
- **WHEN** `python scripts/quality/check-ci-wiring.py` est exécuté
- **THEN** il se termine avec le code 1 et nomme le script

### Requirement: Les deux chemins d'un renommage comptent dans une portée calculée
Le calcul de portée du plugin IntelliJ et celui de la qualification Docker SHALL lire le diff sans détection de renommage, de sorte que l'ancien chemin d'un fichier renommé ou supprimé compte comme son nouveau chemin. Une pull request qui déplace un fichier d'un chemin concerné vers un chemin exclu SHALL être déclarée concernée.

#### Scenario: Source principale déplacée sous les tests
- **WHEN** `python scripts/ci/test_docker_gate.py` rejoue, dans un dépôt temporaire, le renommage de `minos-engine/src/main/java/.../Cache.java` vers `minos-engine/src/test/java/.../Cache.java`
- **THEN** la portée Docker est `true`

#### Scenario: Source principale déplacée sous la documentation
- **WHEN** le même auto-test rejoue le renommage d'une source principale vers `docs/`
- **THEN** la portée Docker est `true`

#### Scenario: Rendu déplacé hors des chemins du plugin
- **WHEN** `python scripts/ci/test_plugin_gate.py` rejoue le renommage d'un fichier de `minos-application/src/main/java/com/minos/output/` vers `docs/`
- **THEN** la portée du plugin est `true`

#### Scenario: Renommage interne à la liste d'exclusion
- **WHEN** l'auto-test Docker rejoue le renommage de `docs/a.md` vers `openspec/a.md`
- **THEN** la portée Docker est `false`

### Requirement: Les classes de la frontière de confiance des providers ont un plancher de branches minimal
Le filtre d'environnement des processus de provider, l'exécuteur à propriété forte, la fabrique du plan de scip-java et le gestionnaire des runtimes polyglottes SHALL chacun porter un plancher de classe JaCoCo, jugé sur chaque système où leur scope est jugé. Ce plancher SHALL valoir au moins 0,50 en branches, et au moins 0,80 pour le filtre d'environnement. Un plancher inférieur ou absent MUST faire échouer l'auto-test du gate.

#### Scenario: Plancher abaissé sous le minimum
- **GIVEN** une copie de la table des scopes où le plancher de branches du filtre d'environnement vaut 0,20
- **WHEN** `python scripts/quality/check-jacoco.py --self-test` est exécuté sur cette copie
- **THEN** l'auto-test échoue en nommant la classe, son plancher et le minimum exigé

#### Scenario: Plancher de classe retiré
- **GIVEN** une copie de la table où l'une des quatre classes n'a plus de plancher de classe
- **WHEN** l'auto-test est exécuté
- **THEN** il échoue en nommant la classe sans plancher

#### Scenario: Érosion de la couverture du filtre d'environnement
- **GIVEN** un rapport JaCoCo où le filtre d'environnement a moins de branches couvertes que son plancher
- **WHEN** `python scripts/quality/check-jacoco.py` est exécuté
- **THEN** le gate échoue en nommant le scope, la classe, sa mesure et son plancher

#### Scenario: Table réelle
- **GIVEN** la table des scopes du dépôt
- **WHEN** l'auto-test est exécuté
- **THEN** il réussit : les quatre classes portent un plancher de branches au moins égal à leur minimum

### Requirement: Les documents courants nomment les rulesets déclarés et aucun risque ouvert ne vise une branche retirée
Dans `docs/project/governance/STATUS.md`, `docs/project/governance/ROADMAP.md` et les pages de `docs/developer/`, tout nom de ruleset cité sous la forme « Protect … » SHALL être un ruleset de `.github/required-checks.json`. Une ligne du registre des risques dont le statut est ouvert SHALL NOT citer la branche `develop`. Le gate des documents courants SHALL échouer dans les deux cas. Les relevés datés ne sont pas concernés.

#### Scenario: Ancien nom de ruleset dans l'état courant
- **GIVEN** une copie du dépôt où `STATUS.md` cite le ruleset « Protect main & develop »
- **WHEN** `python scripts/docs/check-current-docs.py` est exécuté sur cette copie
- **THEN** le gate échoue en nommant le fichier et le nom de ruleset inconnu

#### Scenario: Risque ouvert sur la branche retirée
- **GIVEN** une copie du dépôt où une ligne `OPEN` de `docs/architecture/risks/register.md` cite `develop`
- **WHEN** le gate est exécuté sur cette copie
- **THEN** il échoue en nommant l'identifiant du risque

#### Scenario: Dépôt réel
- **GIVEN** les documents courants après le changement
- **WHEN** `python scripts/docs/check-current-docs.py` et `python scripts/docs/product-facts.py --check` sont exécutés
- **THEN** les deux réussissent

#### Scenario: Relevé daté
- **GIVEN** une section de sprint de `STATUS.md` qui dit « fusionné dans `develop` » pour une PR antérieure au 2026-11-13
- **WHEN** le gate est exécuté
- **THEN** cette mention n'est pas signalée

### Requirement: Un secret n'accompagne pas la compilation du code d'une pull request
Dans un workflow déclenché par `pull_request`, une étape `run:` dont l'`env:` d'étape référence un secret SHALL NOT exécuter le wrapper Maven ni un script du dépôt, sauf entrée nominative d'une liste fermée du gate, avec sa raison ; une telle étape SHALL NOT lancer de phase du cycle de vie Maven. Un `env:` de job ou de workflow SHALL NOT référencer de secret autre que `DOCKERHUB_USERNAME`. Une entrée de liste périmée SHALL faire échouer le gate.

#### Scenario: Compilation avec le jeton d'analyse
- **GIVEN** une copie du dépôt où une étape de `pr-ci.yml` déclare `SONAR_TOKEN: ${{ secrets.SONAR_TOKEN }}` et exécute `./mvnw … test-compile`
- **WHEN** `python scripts/quality/check-workflow-pins.py` est exécuté sur cette copie
- **THEN** le gate échoue en nommant le workflow, le job et l'étape

#### Scenario: Étape hors liste qui reçoit un secret et lance un script du dépôt
- **GIVEN** une copie du dépôt où une étape hors de la liste fermée reçoit un secret et exécute un script de `scripts/`
- **WHEN** le gate est exécuté sur cette copie
- **THEN** il échoue en nommant l'étape

#### Scenario: Dépôt réel
- **GIVEN** les workflows du dépôt, où la compilation du candidat et l'analyse sont deux étapes et où seule l'analyse reçoit le jeton
- **WHEN** le gate est exécuté
- **THEN** il réussit et affiche la liste fermée avec ses raisons

#### Scenario: Entrée de liste sans objet
- **GIVEN** une liste fermée qui nomme une étape qui ne reçoit plus de secret ou n'existe plus
- **WHEN** le gate est exécuté
- **THEN** il échoue en nommant l'entrée périmée

#### Scenario: Secret remonté au niveau du job
- **GIVEN** une copie du dépôt où `SONAR_TOKEN: ${{ secrets.SONAR_TOKEN }}` est déclaré dans l'`env:` du job `sonar` ou du workflow `pr-ci.yml`
- **WHEN** le gate est exécuté sur cette copie
- **THEN** il échoue en nommant le workflow, le niveau et la variable

#### Scenario: Identifiant Docker Hub au niveau du job
- **GIVEN** les jobs du dépôt qui déclarent `DOCKERHUB_USERNAME` dans leur `env:` de job
- **WHEN** le gate est exécuté
- **THEN** cette déclaration n'est pas signalée par cette règle

#### Scenario: Workflow sans déclencheur pull_request
- **GIVEN** un workflow lancé seulement à la demande ou par planification, dont une étape reçoit un secret et exécute le wrapper Maven
- **WHEN** le gate est exécuté
- **THEN** cette étape n'est pas signalée par cette règle

### Requirement: Le gate de preuve Docker rend un verdict sur toute pull request
`release-promotion-gate.yml` SHALL s'exécuter sur toute pull request vers `main`, sans filtre de chemins, et SHALL exposer le job `Docker upgrade evidence gate`, exécuté même si un job amont a échoué, a été annulé ou a été sauté. Ce job SHALL réussir seulement si le calcul de portée a réussi et que, selon la portée, la preuve de qualification du candidat est valide ou la qualification a été sautée parce que la pull request n'est pas concernée.

#### Scenario: Pull request hors portée
- **WHEN** `python scripts/ci/test_docker_gate.py` rejoue le verdict avec un calcul de portée réussi, la portée `false` et une qualification sautée
- **THEN** le verdict est « réussi » et nomme la raison « non concerné »

#### Scenario: Pull request concernée dont la qualification échoue
- **WHEN** le verdict reçoit la portée `true` et le résultat `failure` ou `cancelled` de la qualification
- **THEN** le verdict est « échec » (code 1)

#### Scenario: Saut injustifié
- **WHEN** le verdict reçoit la portée `true` et une qualification sautée
- **THEN** le verdict est « échec »

#### Scenario: Calcul de portée en échec
- **WHEN** le verdict reçoit un calcul de portée qui n'a pas réussi, ou une portée vide
- **THEN** la pull request est traitée comme concernée et le verdict est « échec » sans preuve valide

#### Scenario: Check exigé toujours joignable
- **GIVEN** le workflow après le changement
- **WHEN** `python scripts/quality/check-ci-wiring.py` est exécuté
- **THEN** il se termine avec le code 0 et résout le contexte `Docker upgrade evidence gate` vers un job d'un workflow sans `paths:`

### Requirement: Un job exigé qui attend d'autres jobs s'exécute quel que soit leur résultat
Un job dont le nom est un check exigé et qui déclare `needs:` SHALL déclarer `if: always()` (sous cette forme ou `${{ always() }}`), afin de rendre un verdict quand un job amont a échoué, a été annulé ou a été sauté. `check-ci-wiring.py` SHALL échouer sur un tel job sans cette condition.

#### Scenario: Job exigé sans la condition
- **GIVEN** une copie du dépôt où le job `Docker upgrade evidence gate` déclare `needs:` sans `if: always()`
- **WHEN** `python scripts/quality/check-ci-wiring.py` est exécuté sur cette copie
- **THEN** il se termine avec le code 1 en nommant le workflow et le job

#### Scenario: Dépôt réel
- **GIVEN** les workflows du dépôt, où les deux jobs exigés à `needs:` (`IntelliJ plugin (gate)`, `Docker upgrade evidence gate`) déclarent la condition
- **WHEN** le gate est exécuté
- **THEN** il se termine avec le code 0

#### Scenario: Job exigé sans dépendance
- **GIVEN** un job exigé qui ne déclare pas `needs:`
- **WHEN** le gate est exécuté
- **THEN** ce job n'est pas signalé par cette règle

### Requirement: Une pull request n'échappe à la qualification Docker que par une liste fermée de chemins
Le calcul de portée SHALL déclarer une pull request hors portée seulement si chacun de ses fichiers modifiés appartient à une liste fermée de chemins sans effet sur l'image ni sur sa mise à niveau. Un chemin inconnu, une base introuvable, une erreur Git ou un déclenchement manuel SHALL la déclarer concernée. La liste SHALL NOT contenir le workflow du gate, ses scripts, `docker/`, un POM ni les sources principales d'un module.

#### Scenario: Documentation seule
- **WHEN** `python scripts/ci/test_docker_gate.py` rejoue une pull request qui ne modifie que des fichiers sous `docs/` et `openspec/`
- **THEN** la portée est `false`

#### Scenario: Un fichier de l'image parmi des documents
- **WHEN** la pull request modifie `docs/user/cli.md` et `docker/Dockerfile.mcp.release`
- **THEN** la portée est `true`

#### Scenario: Chemin absent de la liste
- **WHEN** la pull request modifie un fichier d'un dossier que la liste ne nomme pas
- **THEN** la portée est `true`

#### Scenario: Base introuvable ou déclenchement manuel
- **WHEN** la base du diff est vide ou introuvable, ou l'événement est `workflow_dispatch`
- **THEN** la portée est `true`

#### Scenario: Liste élargie à un chemin de l'image
- **GIVEN** une copie du script de portée dont la liste fermée contient `docker/` ou `pom.xml`
- **WHEN** l'auto-test est exécuté sur cette copie
- **THEN** il échoue en nommant l'entrée interdite

## Documentation liée

- Dossier durable (vues, traçabilité, écarts) : [controles-de-fusion](../../../docs/architecture/diagrams/capacites/controles-de-fusion.md)
