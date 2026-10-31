## ADDED Requirements

### Requirement: scip-java lance Maven hors ligne sur le dépôt désigné
Hors Windows, le plan scip-java d'un projet Maven, avec le classpath installé comme avec le lanceur du `PATH`, SHALL déclarer le dépôt désigné en racine de lecture et passer après `--` : `--batch-mode --offline`, `-s` et `-gs` vers le `settings.xml` de MINOS, un dépôt local de tête dans le run, le dépôt désigné en `maven.repo.local.tail`, puis `clean verify -DskipTests`. Le réseau reste `DENY`.

#### Scenario: Plan avec dépôt désigné
- **GIVEN** sous Linux, un projet Maven et un dépôt Maven désigné valide
- **WHEN** le plan scip-java est construit
- **THEN** sa commande contient `--offline`, `-s`, `-gs`, `-Dmaven.repo.local.tail=` suivi du dépôt, et sa racine de lecture est ce dépôt

#### Scenario: Indexation réelle hors ligne
- **GIVEN** sous Linux avec bac à sable qualifié, `java-simple` et un dépôt désigné qui contient ses plugins et dépendances
- **WHEN** `minos index` est lancé
- **THEN** l'artefact SCIP est produit et le dépôt désigné est inchangé

### Requirement: Une désignation de dépôt qui expose un dossier sensible est refusée avant préparation
Hors Windows, le plan SHALL être refusé avant toute copie du projet sans dépôt désigné, ou si la désignation est relative, absente, un lien, ou si elle est, ou est l'ancêtre de : le dossier personnel, `/mnt/<lecteur>/Users/<compte>`, `/etc`, `/proc`, `/sys`, `/dev`, `/root`, `MINOS_HOME`. Le message MUST nommer le réglage et MUST NOT contenir de chemin absolu.

#### Scenario: Aucun dépôt désigné
- **GIVEN** sous Linux, un projet Maven et aucun dépôt désigné
- **WHEN** le plan scip-java est construit
- **THEN** il est refusé, le message nomme le réglage, et aucun espace de travail n'est créé dans le run

#### Scenario: Profil Windows désigné depuis WSL
- **GIVEN** sous Linux, la désignation `/mnt/c/Users/<compte>` ou `/mnt/c`
- **WHEN** le plan scip-java est construit
- **THEN** il est refusé sans chemin absolu dans le message

### Requirement: Une désignation qui n'a pas la forme d'un dépôt Maven est refusée
Le plan SHALL refuser une désignation dont la racine contient `settings.xml`, `settings-security.xml`, `toolchains.xml`, un dossier caché (`.ssh`, `.m2`, `.config`…) ou un fichier ordinaire hors d'une liste fermée de métadonnées Maven, ou qui est située sous `MINOS_HOME`. Le message MUST NOT contenir de chemin absolu.

#### Scenario: Désignation qui contient les réglages de l'utilisateur
- **GIVEN** sous Linux, un dépôt désigné qui contient `settings.xml` à sa racine
- **WHEN** le plan scip-java est construit
- **THEN** il est refusé sans chemin absolu dans le message

#### Scenario: Désignation qui contient un dossier de secrets
- **GIVEN** sous Linux, une désignation dont la racine contient `.ssh`
- **WHEN** le plan scip-java est construit
- **THEN** il est refusé

### Requirement: Les réglages Maven et le dépôt de tête sont fixés par MINOS pour chaque run
Le `settings.xml` du run SHALL avoir un contenu constant, sans serveur, miroir, proxy, profil, dépôt local ni mode hors ligne. Le dépôt de tête SHALL être créé vide pour le run ; s'il existe déjà, le plan MUST être refusé. Aucun fichier de réglages de l'utilisateur ni son dossier MUST NOT être nommé par la commande ou les racines de lecture.

#### Scenario: Réglages de l'utilisateur présents à côté du dépôt
- **GIVEN** sous Linux, `~/.m2/settings.xml` avec un serveur à mot de passe et le dépôt désigné `~/.m2/repository`
- **WHEN** le plan scip-java est construit
- **THEN** ni la commande ni les racines ne nomment `~/.m2` ou ce `settings.xml`, et le `settings.xml` du run est égal octet pour octet au contenu constant

#### Scenario: Dépôt de tête déjà présent
- **GIVEN** sous Linux, un run dont le dépôt de tête existe déjà
- **WHEN** le plan scip-java est construit
- **THEN** il est refusé avant tout lancement

### Requirement: Le dépôt désigné n'apparaît dans aucune sortie publique ni journal par son chemin
`doctor`, `tools verify`, les messages de refus et les journaux WARNING SHALL rapporter le dépôt désigné par le nom du réglage et son état (absent, refusé, présent), MUST NOT écrire son chemin absolu.

#### Scenario: Doctor avec dépôt désigné refusé
- **GIVEN** sous Linux, une désignation refusée
- **WHEN** `minos doctor` est lancé
- **THEN** la sortie nomme le réglage et l'état refusé, et ne contient pas le chemin désigné
