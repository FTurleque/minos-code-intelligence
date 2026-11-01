## ADDED Requirements

### Requirement: Le runtime Docker ne monte aucune racine des projets
Les fichiers Compose livrés SHALL NOT monter de dossier de projets dans `minos-mcp` ni dans `minos-admin` en dehors des montages de projets générés. Avec une table de montages vide, `minos-mcp` SHALL démarrer et `/workspace/projects` SHALL être vide dans les deux services.

#### Scenario: Fichiers Compose livrés
- **GIVEN** `docker/compose-mcp.prod.yaml` et `docker/compose-mcp.connected.yaml`
- **WHEN** `M29DockerAdministrationContractTest` les lit
- **THEN** aucun ne contient `MINOS_PROJECTS_DIR` ni un montage de cible `/workspace/projects`

#### Scenario: Table vide
- **GIVEN** une installation Docker sans projet exposé
- **WHEN** `minos-mcp` est démarré puis `ls /workspace/projects` est exécuté dans le conteneur
- **THEN** le démarrage réussit et la liste est vide

### Requirement: Exposer un projet Docker ajoute son montage puis l'inscrit
`-Action AddProject -ProjectPath <dossier>` SHALL ajouter un montage en lecture seule de ce seul dossier sur `/workspace/projects/<nom>` dans `minos-mcp` et `minos-admin`, recréer `minos-mcp`, puis inscrire le projet par ce chemin. Un dossier absent, point de reparse, ou refusé par la règle des racines dangereuses SHALL être refusé avant toute écriture, par un message sans chemin.

#### Scenario: Projet exposé
- **GIVEN** une installation Docker et un dossier de projet dédié `app`
- **WHEN** `AddProject -ProjectPath <app>` est exécuté
- **THEN** `project list` dans `minos-admin` contient le projet sous `/workspace/projects/app`, `rootAvailable` vaut `true`, et un dossier voisin de `app` est absent de `/workspace/projects`

#### Scenario: Dossier dangereux
- **GIVEN** le profil de l'utilisateur, une racine de volume ou `%LOCALAPPDATA%`
- **WHEN** il est passé à `AddProject`
- **THEN** l'action échoue, la table et l'override sont inchangés et `minos-mcp` n'est pas recréé

#### Scenario: Point de reparse
- **GIVEN** une jonction ou un lien symbolique vers un dossier de projet
- **WHEN** il est passé à `AddProject`
- **THEN** l'action échoue avant toute écriture et le message demande le dossier réel

### Requirement: La table des montages est bornée et sans chevauchement
Le bootstrap Docker SHALL refuser, sans écrire, un nom de montage hors de `[a-z0-9][a-z0-9._-]{0,62}`, un nom déjà pris sans tenir compte de la casse, un chemin de montage qui en préfixe un autre, un dossier hôte égal à un autre ou qui le contient, un dossier hôte relatif, et un ajout au-delà du plafond de montages. Une version antérieure SHALL refuser le format 2 sans le modifier.

#### Scenario: Nom en double
- **GIVEN** une table qui contient le montage `app`
- **WHEN** `add-project-mount App D:/autre` est exécuté
- **THEN** le code de sortie est non nul et la table est inchangée

#### Scenario: Dossier contenu dans un autre
- **GIVEN** une table qui contient `D:/src/app`
- **WHEN** un montage de `D:/src/app/sub` ou de `D:/src` est ajouté
- **THEN** l'ajout est refusé et la table est inchangée

#### Scenario: Plafond atteint
- **GIVEN** une table qui contient le nombre maximal de montages
- **WHEN** un montage de plus est ajouté
- **THEN** l'ajout est refusé et le message nomme le plafond

### Requirement: L'override des montages est régénéré depuis la table
Avant chaque démarrage de `minos-mcp`, chaque `minos-admin` et chaque validation, le cycle de vie SHALL régénérer `compose-mcp.projects.yaml` à partir de la table et le passer à Compose. Chaque chemin SHALL y être une chaîne échappée. Un montage dont le dossier hôte n'existe plus SHALL être omis avec un avertissement, sans empêcher le démarrage.

#### Scenario: Override modifié à la main
- **GIVEN** un `compose-mcp.projects.yaml` auquel un montage a été ajouté à la main
- **WHEN** `-Action Start` est exécuté
- **THEN** le fichier est réécrit et le montage ajouté à la main n'est pas monté

#### Scenario: Caractères spéciaux
- **GIVEN** un dossier de projet dont le nom contient une apostrophe, un dièse et deux-points
- **WHEN** l'override est généré
- **THEN** `docker compose config` l'accepte et la source du montage est exactement ce dossier

#### Scenario: Dossier supprimé
- **GIVEN** un projet exposé dont le dossier a été supprimé
- **WHEN** `-Action Start` est exécuté
- **THEN** `minos-mcp` démarre, un avertissement nomme le montage et le projet répond `rootAvailable: false`

### Requirement: Retirer un projet Docker retire son montage sans perdre son index
`-Action RemoveProject -Name <nom>` SHALL retirer le montage de la table, régénérer l'override et recréer `minos-mcp`. Les projets inscrits sous ce montage SHALL rester inscrits avec leur index et répondre `rootAvailable: false`. Un nom inconnu SHALL être refusé sans écriture.

#### Scenario: Retrait
- **GIVEN** un projet exposé sous le montage `app`
- **WHEN** `RemoveProject -Name app` est exécuté
- **THEN** `/workspace/projects/app` est absent de `minos-mcp`, `project list` contient le projet avec `rootAvailable: false`

#### Scenario: Nom inconnu
- **GIVEN** une table sans montage `absent`
- **WHEN** `RemoveProject -Name absent` est exécuté
- **THEN** l'action échoue et `minos-mcp` n'est pas recréé

### Requirement: Une racine unique migre vers un montage par projet inscrit
À l'installation sur des données Docker au format 1, chaque projet inscrit SHALL recevoir un montage sur son chemin conteneur actuel, sans réécrire le registre local ou PostgreSQL. Un projet inscrit à la racine elle-même, isolé ou refusé par la règle des racines dangereuses SHALL NOT être monté et SHALL être signalé. Une inscription illisible SHALL arrêter la migration avant toute écriture.

#### Scenario: Projets sous l'ancienne racine
- **GIVEN** des données Docker au format 1 de racine `R` et deux projets inscrits sous `a` et `team/b`
- **WHEN** l'installation de cette version est exécutée
- **THEN** la table contient `a` et `team/b`, les inscriptions sont inchangées octet pour octet, et un dossier non inscrit sous `R` est absent de `/workspace/projects`

#### Scenario: Projet inscrit à la racine
- **GIVEN** un projet inscrit avec `rootRelativePath=.`
- **WHEN** la migration est exécutée
- **THEN** aucun montage de `R` n'est créé et un avertissement nomme le projet

#### Scenario: Inscription illisible
- **GIVEN** une inscription illisible dans le registre
- **WHEN** l'installation de cette version est exécutée
- **THEN** elle échoue, le fichier de correspondance reste au format 1 et l'ancien runtime est conservé

### Requirement: L'installeur Windows ne demande aucune racine des projets Docker
La page Docker de l'installeur Windows SHALL NOT présenter de champ de racine des projets. `/DOCKERPROJECTSROOT`, `-ProjectsRoot` et la valeur `ProjectsRoot` enregistrée SHALL NOT entraîner de montage ; leur présence SHALL être signalée par un avertissement dans le journal d'installation.

#### Scenario: Gabarit de l'installeur
- **GIVEN** `packaging/windows/minos-installer.iss.template`
- **WHEN** `scripts/install/verify-installer-template.ps1` est exécuté
- **THEN** il échoue si le gabarit contient `Racine des projets` ou transmet `-ProjectsRoot`

#### Scenario: Paramètre hérité en mode silencieux
- **GIVEN** une installation silencieuse lancée avec `/DOCKERPROJECTSROOT=<dossier>`
- **WHEN** le commutateur de backend configure le runtime Docker
- **THEN** la table des montages ne contient pas ce dossier et le journal contient l'avertissement

### Requirement: Un dossier de projet dangereux est refusé avant tout montage
Le cycle de vie Docker SHALL refuser, avant toute écriture, un dossier de projet à exposer qui est une racine de volume, le profil ou l'un de ses parents, ou qui est, contient ou se trouve dans `%LOCALAPPDATA%`, `%APPDATA%` ou un dossier de MINOS. La migration d'une racine unique SHALL NOT monter un tel dossier. Le message SHALL NOT contenir de chemin. Un profil introuvable SHALL entraîner le refus.

#### Scenario: Profil passé à AddProject
- **GIVEN** une installation Docker
- **WHEN** `AddProject -ProjectPath` désigne le profil, l'un de ses parents ou une racine de volume
- **THEN** l'action échoue avec un message qui nomme la catégorie refusée, et la table des montages est inchangée

#### Scenario: Dossier de MINOS à migrer
- **GIVEN** des données Docker au format 1 et un projet inscrit dont le dossier se trouve dans `%LOCALAPPDATA%`
- **WHEN** la migration est exécutée
- **THEN** ce projet n'est pas monté et un avertissement le nomme

#### Scenario: Dossier de projets dédié
- **GIVEN** un sous-dossier du profil hors AppData, ou un dossier d'un autre volume
- **WHEN** il est passé à `AddProject`
- **THEN** il est accepté

## REMOVED Requirements

### Requirement: L'installeur Windows ne propose aucune racine de projets par défaut
**Reason**: la page Docker de l'installeur ne présente plus de champ de racine des projets ; aucune racine n'est montée.
**Migration**: l'exigence « L'installeur Windows ne demande aucune racine des projets Docker » s'applique ; un projet s'expose par `-Action AddProject`.

### Requirement: Une racine de projets dangereuse est refusée avant tout montage
**Reason**: aucune racine des projets n'est plus montée ; le refus porte sur le dossier de chaque projet exposé.
**Migration**: l'exigence « Un dossier de projet dangereux est refusé avant tout montage » s'applique à `AddProject` et à la migration.
