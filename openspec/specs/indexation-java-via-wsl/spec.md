# indexation-java-via-wsl Specification

## Purpose
Indexer depuis un poste Windows un projet Maven Java dans une distribution WSL2, sous le bac à sable Linux qualifié, puis importer dans l'installation Windows l'artefact vérifié par le staging et la promotion atomique existants.

## Requirements

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

### Requirement: La voie WSL réutilise l'empreinte du projet connue de l'indexation
Quand l'indexation fournit l'empreinte du projet enregistré avec la requête d'une cible, la voie WSL SHALL l'utiliser comme empreinte de provenance sans hacher l'arbre source. Sans empreinte fournie, elle SHALL capturer l'empreinte au plus une fois par run, quel que soit le nombre de cibles. Une empreinte fournie qui n'est pas un SHA-256 hexadécimal en minuscules MUST être refusée avant tout lancement dans la distribution.

#### Scenario: Deux cibles avec l'empreinte fournie
- **GIVEN** un run de deux cibles scip-java routées par WSL, dont les requêtes portent l'empreinte du projet, et un service d'empreintes qui compte ses captures
- **WHEN** les deux cibles sont exécutées avec un worker factice
- **THEN** le service d'empreintes de la voie n'a fait aucune capture
- **AND** le manifeste de chaque artefact porte l'empreinte fournie et la provenance est acceptée

#### Scenario: Deux cibles sans empreinte fournie
- **GIVEN** le même run dont les requêtes ne portent aucune empreinte
- **WHEN** les deux cibles sont exécutées
- **THEN** le service d'empreintes de la voie a fait exactement une capture
- **AND** les deux requêtes envoyées au worker portent la même empreinte

#### Scenario: Empreinte fournie mal formée
- **GIVEN** une requête de cible dont l'empreinte fournie n'est pas un SHA-256 hexadécimal de 64 caractères en minuscules
- **WHEN** la requête est construite
- **THEN** elle est refusée par une erreur d'argument sans chemin absolu
- **AND** aucun worker n'est appelé et aucun fichier n'est créé sous `runs/`

#### Scenario: Manifeste qui porte une autre empreinte
- **GIVEN** une requête qui porte l'empreinte fournie et un worker dont le manifeste en nomme une autre
- **WHEN** la cible est exécutée
- **THEN** l'exécution échoue par l'erreur de provenance, le paquet est supprimé et le bail du magasin est libéré

### Requirement: L'artefact de WSL est lu au plus deux fois entre l'acceptation du paquet et son retour
Sur le chemin nominal, l'installation Windows SHALL lire le contenu de l'artefact SCIP au plus deux fois entre l'appel d'acceptation du paquet et son retour à l'indexation : une fois pour l'extraire en calculant sa taille, son SHA-256 et celui du paquet, une fois pour le copier sous le run en calculant le SHA-256 des octets copiés. Chaque empreinte MUST rester comparée au manifeste ; une différence MUST faire échouer la cible sans laisser de copie.

#### Scenario: Chemin nominal compté
- **GIVEN** un magasin instrumenté par un compteur d'ouvertures en lecture et un paquet v3 conforme
- **WHEN** le paquet est accepté puis l'artefact copié sous `runs/<runId>/`
- **THEN** le paquet a été ouvert une fois, l'artefact extrait n'a pas été rouvert, l'entrée de cache a été ouverte une fois et la copie n'a pas été rouverte
- **AND** l'empreinte du paquet rapportée est égale au SHA-256 du fichier paquet

#### Scenario: Artefact du paquet différent de son manifeste
- **GIVEN** un paquet dont l'entrée `index.scip` ne correspond pas à l'empreinte ou à la taille de son manifeste
- **WHEN** le paquet est accepté
- **THEN** l'acceptation échoue par l'erreur d'empreinte ou de taille existante
- **AND** aucune entrée de cache n'est créée, le dossier d'extraction est supprimé et aucun fichier n'apparaît sous `runs/`

#### Scenario: Entrée de cache altérée entre l'acceptation et la copie
- **GIVEN** un paquet accepté dont l'entrée de cache est modifiée avant la copie, à taille égale
- **WHEN** l'artefact est copié sous le run
- **THEN** l'exécution échoue par l'erreur d'empreinte de la copie
- **AND** la copie est supprimée, le bail est libéré et le paquet est supprimé

#### Scenario: Entrée de cache déjà présente à l'identique
- **GIVEN** une entrée de cache dont le manifeste est égal à celui du paquet reçu
- **WHEN** le paquet est accepté
- **THEN** l'entrée existante est revérifiée par taille et SHA-256 avant d'être rendue, par une lecture qui s'ajoute aux deux du chemin nominal

### Requirement: Le paquet produit dans la distribution est vérifié pendant son écriture
La fabrication d'un paquet d'artefact SHALL comparer au manifeste la taille et le SHA-256 des octets qu'elle écrit dans le paquet, calculés pendant cette écriture. En cas de différence, elle MUST échouer sans publier de paquet à l'emplacement de sortie et sans y laisser de fichier temporaire.

#### Scenario: Artefact conforme à son manifeste
- **GIVEN** un artefact et un manifeste qui porte sa taille et son SHA-256
- **WHEN** le paquet est fabriqué
- **THEN** le paquet est publié et son acceptation par le magasin réussit

#### Scenario: Artefact modifié après le calcul du manifeste
- **GIVEN** un artefact dont le contenu change, à taille égale, après le calcul du manifeste
- **WHEN** le paquet est fabriqué
- **THEN** la fabrication échoue par l'erreur d'empreinte existante
- **AND** aucun paquet n'existe à l'emplacement de sortie et aucun fichier `.bundle-*.tmp` n'y reste

#### Scenario: Source lue une seule fois par la fabrication
- **GIVEN** une fabrication de paquet instrumentée par un compteur d'ouvertures en lecture de l'artefact source
- **WHEN** un paquet conforme est fabriqué
- **THEN** l'artefact source a été ouvert une seule fois

## Documentation liée

- Dossier durable (vues, traçabilité, écarts, qualification) : [indexation-java-via-wsl](../../../docs/architecture/diagrams/capacites/indexation-java-via-wsl.md)
