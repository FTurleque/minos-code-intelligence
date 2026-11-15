# Spec Delta

## ADDED Requirements

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
