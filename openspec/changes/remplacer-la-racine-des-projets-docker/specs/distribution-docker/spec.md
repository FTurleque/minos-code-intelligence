## ADDED Requirements

### Requirement: La racine des projets Docker ne se remplace que sur demande explicite
Le bootstrap Docker SHALL refuser une correspondance de chemins différente de celle enregistrée sans l'option `--replace`, sans rien écrire. Avec `--replace`, il SHALL remplacer la correspondance. Il SHALL refuser le remplacement, sans rien écrire, si une inscription du registre est illisible ou si le stockage configuré est PostgreSQL.

#### Scenario: Racine différente sans option
- **GIVEN** un MINOS_HOME dont la correspondance enregistrée a pour racine hôte `C:/old`
- **WHEN** `configure-project-paths D:/new /workspace/projects` est exécuté
- **THEN** le code de sortie est 1, le message nomme `--replace`, et la correspondance reste `C:/old`

#### Scenario: Racine différente avec option
- **GIVEN** le même MINOS_HOME
- **WHEN** `configure-project-paths --replace D:/new /workspace/projects` est exécuté
- **THEN** le code de sortie est 0 et la correspondance enregistrée a pour racine hôte `D:/new`

#### Scenario: Inscription illisible
- **GIVEN** un registre local qui contient une inscription illisible
- **WHEN** `configure-project-paths --replace D:/new /workspace/projects` est exécuté
- **THEN** le remplacement est refusé, la correspondance et les inscriptions restent inchangées

#### Scenario: Stockage PostgreSQL
- **GIVEN** un MINOS_HOME configuré avec `minos.storage.backend=postgresql`
- **WHEN** `configure-project-paths --replace D:/new /workspace/projects` est exécuté
- **THEN** le remplacement est refusé et rien n'est écrit

### Requirement: Un remplacement de racine rebase ou isole les projets sans perte
Lors d'un remplacement, chaque projet du registre local SHALL être rebasé s'il est sous la nouvelle racine hôte, sinon isolé : il reste inscrit avec son identifiant et son index, `rootAvailable` vaut `false`, et il SHALL NOT être résolu vers un dossier de la nouvelle racine. Chaque inscription modifiée SHALL être sauvegardée avant d'être réécrite. Une inscription isolée que la racine contient de nouveau SHALL être rattachée.

#### Scenario: Projet sous la nouvelle racine
- **GIVEN** un projet inscrit sous `C:/old/team/app` et une nouvelle racine `C:/old/team`
- **WHEN** la racine est remplacée
- **THEN** le projet garde son identifiant et se résout vers `app` sous la nouvelle racine

#### Scenario: Projet hors de la nouvelle racine
- **GIVEN** un projet inscrit sous `C:/old/app` et un dossier `D:/new/app` qui existe
- **WHEN** la racine est remplacée par `D:/new`
- **THEN** le projet reste inscrit, `rootAvailable` vaut `false`, sa racine n'est pas `D:/new/app`, et une sauvegarde de l'inscription existe

#### Scenario: Retour à l'ancienne racine
- **GIVEN** un projet isolé par le remplacement de `C:/old` par `D:/new`
- **WHEN** la racine est de nouveau remplacée par `C:/old`
- **THEN** le projet se résout vers son ancien dossier

### Requirement: Les installeurs Windows ne changent la racine des projets qu'après confirmation
L'installeur Windows, `switch-mcp-backend.ps1` et `install-windows.ps1` SHALL comparer la racine choisie à la racine enregistrée du runtime Docker avant toute écriture. Différente et non confirmée, ils SHALL échouer avec un message qui nomme la confirmation. Confirmée, ils SHALL transmettre `--replace` au bootstrap et restaurer la correspondance et le registre si le basculement échoue ensuite.

#### Scenario: Changement non confirmé
- **GIVEN** des données Docker dont la correspondance a pour racine hôte un autre dossier
- **WHEN** `switch-mcp-backend.ps1 -TargetBackend docker` est lancé sans `-ReplaceDockerProjectsRoot`
- **THEN** il échoue avant de préparer le runtime et le message nomme `-ReplaceDockerProjectsRoot` et `/REPLACEDOCKERPROJECTSROOT=1`

#### Scenario: Changement confirmé
- **GIVEN** les mêmes données Docker
- **WHEN** `switch-mcp-backend.ps1 -ReplaceDockerProjectsRoot` est lancé
- **THEN** le bootstrap reçoit `configure-project-paths --replace` et l'installation réussit

#### Scenario: Même racine
- **GIVEN** des données Docker dont la correspondance a pour racine hôte la racine choisie, à la casse et au séparateur près
- **WHEN** le commutateur est lancé sans confirmation
- **THEN** aucun refus n'a lieu et `--replace` n'est pas transmis
