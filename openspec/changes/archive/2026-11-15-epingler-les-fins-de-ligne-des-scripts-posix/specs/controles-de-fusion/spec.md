## ADDED Requirements

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
