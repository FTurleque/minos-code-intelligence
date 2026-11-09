## Purpose

Indexer depuis un poste Windows un projet Maven Java dans une distribution WSL2, sous le bac à sable Linux qualifié, puis importer dans l'installation Windows l'artefact vérifié par le staging et la promotion atomique existants.

## ADDED Requirements

### Requirement: L'indexation via WSL n'est offerte que sous Windows
`minos index <projet> --via-wsl` SHALL n'être accepté que par une installation Windows. Ailleurs, la commande MUST être refusée en erreur d'usage qui nomme l'option, avant toute découverte du projet et sans créer de run.

#### Scenario: Option refusée sous Linux
- **GIVEN** sous Linux, un projet Maven enregistré
- **WHEN** `minos index <projet> --via-wsl` est lancé
- **THEN** la commande échoue en erreur d'usage qui nomme `--via-wsl`
- **AND** aucun run n'est créé et l'instantané actif est inchangé

#### Scenario: Option combinée à --scip
- **GIVEN** sous Windows, un projet enregistré
- **WHEN** `minos index <projet> --via-wsl --scip <fichier>` est lancé
- **THEN** la commande échoue en erreur d'usage qui nomme les deux options

### Requirement: Les prérequis de WSL sont vérifiés avant tout lancement d'indexation
Avant de lancer un provider, `--via-wsl` SHALL vérifier : distribution WSL2 (pas WSL1), systemd actif, cgroup v2 avec `memory`, `pids` et `cpu` délégables, bubblewrap, JVM 21 pour MINOS, Maven 3.9.x, JDK du projet et dépôt Maven désigné. Un prérequis absent MUST faire refuser la commande avec un diagnostic qui le nomme et dit l'action, sans chemin absolu.

#### Scenario: Distribution en WSL1
- **GIVEN** sous Windows, `--wsl-distro` désigne une distribution en version 1
- **WHEN** `minos index <projet> --via-wsl --wsl-distro <nom>` est lancé
- **THEN** la commande échoue, le diagnostic nomme la version 2 exigée
- **AND** aucun processus MINOS n'est lancé dans la distribution et aucun run n'est créé

#### Scenario: bubblewrap absent
- **GIVEN** une distribution WSL2 sans `bwrap`
- **WHEN** `minos index <projet> --via-wsl` est lancé
- **THEN** la commande échoue, le diagnostic nomme bubblewrap et ne contient aucun chemin absolu

#### Scenario: Aucune distribution WSL
- **GIVEN** sous Windows, `wsl.exe` absent ou sans distribution installée
- **WHEN** `minos index <projet> --via-wsl` est lancé
- **THEN** la commande échoue avec un diagnostic qui nomme WSL2 comme prérequis

### Requirement: doctor rapporte la voie WSL sous Windows
Sous Windows, `minos doctor` SHALL comporter une section `wslJava`, en texte et en JSON, qui donne pour chaque prérequis de la voie un état `OK`, `MISSING` ou `UNKNOWN` et l'action attendue. La section MUST NOT contenir de chemin absolu et SHALL être absente sous Linux.

#### Scenario: Section complète
- **GIVEN** sous Windows, une distribution WSL2 qui réunit tous les prérequis
- **WHEN** `minos doctor --format json` est lancé
- **THEN** `wslJava` liste chaque prérequis à l'état `OK`

#### Scenario: Section absente sous Linux
- **GIVEN** sous Linux
- **WHEN** `minos doctor --format json` est lancé
- **THEN** la sortie ne contient pas de clé `wslJava`

### Requirement: Le provider tourne dans la distribution sous le bac à sable Linux, réseau coupé
Le provider SHALL s'exécuter dans la distribution, dans un scope systemd utilisateur à cgroup délégué, sous le backend bubblewrap et cgroup v2, avec la politique réseau `DENY`. Si ce backend n'est pas disponible dans la distribution, l'exécution MUST être refusée avant le lancement du provider, sans repli sur une exécution moins confinée.

#### Scenario: Cgroup non délégué
- **GIVEN** une distribution WSL2 où le scope de MINOS ne peut pas recevoir `memory`, `pids` et `cpu`
- **WHEN** `minos index <projet> --via-wsl` est lancé
- **THEN** la commande échoue avant le lancement de scip-java, le diagnostic nomme la délégation du cgroup

#### Scenario: Réseau du provider
- **GIVEN** une indexation via WSL réussie
- **WHEN** le manifeste de l'artefact reçu est lu
- **THEN** la politique réseau est `DENY` et `networkDenyEnforced` vaut `true`

### Requirement: wsl.exe reçoit un environnement en liste d'autorisation
Le processus `wsl.exe` lancé par MINOS SHALL recevoir un environnement construit par liste d'autorisation, avec `WSLENV` vide. Aucune variable de l'environnement de MINOS qui correspond à un motif de secret ne MUST atteindre la distribution.

#### Scenario: Jeton présent dans l'environnement de MINOS
- **GIVEN** sous Windows, `MINOS_TEAM_TOKEN` et `GITHUB_TOKEN` définis dans l'environnement du CLI
- **WHEN** la commande `wsl.exe` de la voie est construite
- **THEN** son environnement ne contient ni ces variables ni `WSLENV` non vide

### Requirement: MINOS ne peuple pas le dépôt Maven désigné
La voie SHALL utiliser le dépôt Maven désigné dans la distribution, en lecture seule et hors ligne. MINOS MUST NOT lancer Maven avec accès réseau sur le projet, ni dans ni hors du bac à sable. Un dépôt absent ou refusé MUST faire refuser la commande en nommant le réglage, sans chemin absolu.

#### Scenario: Dépôt non désigné
- **GIVEN** une distribution WSL2 sans dépôt Maven désigné
- **WHEN** `minos index <projet> --via-wsl` est lancé
- **THEN** la commande échoue, le diagnostic nomme le réglage du dépôt Maven
- **AND** aucun Maven n'est exécuté

### Requirement: L'artefact reçu de WSL est vérifié avant tout import
Avant import, l'installation Windows SHALL vérifier l'artefact reçu : format, bornes de taille, empreinte SHA-256, projet, langage et provider attendus. Un artefact non conforme MUST être rejeté et détruit, sans instantané promu, l'instantané actif restant inchangé.

#### Scenario: Empreinte altérée
- **GIVEN** un artefact dont le contenu ne correspond pas à l'empreinte de son manifeste
- **WHEN** l'installation Windows le reçoit
- **THEN** l'indexation échoue, l'artefact est supprimé et l'instantané actif est inchangé

#### Scenario: Projet différent
- **GIVEN** un artefact dont le manifeste désigne un autre projet que celui demandé
- **WHEN** l'installation Windows le reçoit
- **THEN** il est rejeté sans import

### Requirement: Un artefact conforme est importé par la promotion atomique
Un artefact vérifié SHALL être importé par le staging et la promotion atomique de l'installation Windows. Après succès, `minos index-status <projet>` SHALL rapporter l'état `READY` et le provider scip-java. Un échec d'import MUST laisser l'instantané précédent actif.

#### Scenario: Indexation réussie
- **GIVEN** sous Windows, une distribution WSL2 qui réunit les prérequis et un projet Maven Java enregistré
- **WHEN** `minos index <projet> --via-wsl` se termine avec succès
- **THEN** `minos index-status <projet>` donne `READY` avec le provider `scip-java`
- **AND** `minos find-usages` résout des usages d'une classe du projet

#### Scenario: Échec après un index précédent
- **GIVEN** un projet déjà indexé et un import qui échoue
- **WHEN** `minos index-status <projet>` est lancé
- **THEN** l'instantané actif est celui d'avant l'échec

### Requirement: Un run via WSL interrompu ne laisse rien tourner
Une indexation via WSL interrompue ou hors délai SHALL arrêter le scope systemd de la distribution, ce qui termine tous ses processus, et SHALL supprimer l'artefact partiel côté Windows. L'interruption MUST NOT promouvoir d'instantané.

#### Scenario: Délai dépassé
- **GIVEN** un worker WSL qui ne répond pas avant le délai de la voie
- **WHEN** le délai expire
- **THEN** la commande échoue, aucun processus du run ne reste dans la distribution
- **AND** aucun artefact partiel ne reste dans le cache de l'installation Windows

### Requirement: Les sorties de la voie WSL ne contiennent aucun chemin absolu
Les sorties publiques, diagnostics et journaux WARNING de la voie SHALL nommer projet, distribution et réglages sans chemin absolu, ni Windows (`C:\…`) ni de la distribution (`/mnt/…`, `/home/…`).

#### Scenario: Échec du provider dans la distribution
- **GIVEN** un build Maven du projet qui échoue dans la distribution
- **WHEN** `minos index <projet> --via-wsl --format json` se termine
- **THEN** le diagnostic nomme le projet et le provider, et ne contient ni `C:\`, ni `/mnt/`, ni `/home/`
