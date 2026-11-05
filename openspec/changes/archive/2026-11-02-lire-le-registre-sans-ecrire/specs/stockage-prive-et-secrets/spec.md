## ADDED Requirements

### Requirement: Une lecture du registre des projets n'écrit rien
Une lecture du registre local (recherche, liste ou inventaire de projets et d'espaces) SHALL NOT créer, durcir ni ouvrir en écriture un fichier, fichier de verrou compris, y compris quand le registre vient d'être rouvert. Une mutation du registre SHALL garder son verrou exclusif.

#### Scenario: Lectures sur un registre protégé en écriture
- **GIVEN** un registre contenant un projet rattaché à un espace, puis protégé en écriture
- **WHEN** les six lectures sont appelées (`./mvnw -B -ntp -pl minos-storage-local -am test "-Dtest=InterProcessLocalProjectRegistryWriteProtectedStorageTest"`)
- **THEN** chacune rend le projet ou l'espace, et l'état du répertoire est identique avant et après

#### Scenario: Registre rouvert sur un support protégé
- **GIVEN** un registre protégé en écriture
- **WHEN** une nouvelle instance l'ouvre puis recherche le projet
- **THEN** le projet est rendu et rien n'a été modifié

### Requirement: Une lecture du registre reste exclue par un écrivain
Un lecteur du registre SHALL attendre la fin d'une mutation en cours dans un autre processus, et une mutation SHALL attendre la fin des lectures en cours, dans la limite de l'échéance du bail. Deux processus lecteurs SHALL NOT s'exclure. Une échéance dépassée SHALL échouer ; la lecture SHALL NOT se poursuivre sans le bail.

#### Scenario: Lecteur pendant une mutation
- **GIVEN** un processus qui tient le bail exclusif du registre
- **WHEN** un second processus lance une lecture
- **THEN** la lecture ne rend son résultat qu'après la libération du bail

#### Scenario: Deux lecteurs en même temps
- **GIVEN** un processus qui tient le bail de lecture du registre
- **WHEN** un second processus lance une lecture
- **THEN** elle aboutit sans attendre la libération

#### Scenario: Échéance dépassée
- **GIVEN** un bail exclusif tenu au-delà de l'échéance du lecteur
- **WHEN** le lecteur attend le bail
- **THEN** la lecture échoue par une erreur d'E/S et ne rend aucune donnée

### Requirement: Un fichier de verrou non conforme est refusé à la lecture, jamais réparé
Un lecteur SHALL refuser un fichier de verrou qui est un lien symbolique, un objet spécial ou un fichier accordant un accès à un autre principal que son propriétaire. Il SHALL NOT modifier ses permissions. Un fichier de verrou absent SHALL NOT être créé par un lecteur. Le message d'erreur SHALL NOT contenir de chemin.

#### Scenario: Verrou remplacé par un lien symbolique
- **GIVEN** un registre dont le fichier de verrou est un lien symbolique
- **WHEN** une lecture est lancée
- **THEN** elle échoue sans suivre le lien, et le message ne contient aucun chemin

#### Scenario: Verrou absent
- **GIVEN** un registre ouvert dont le fichier de verrou n'existe pas
- **WHEN** une liste des projets est demandée
- **THEN** la liste est rendue et le fichier de verrou n'existe toujours pas

### Requirement: Une lecture ne migre pas une entrée de registre héritée
Une lecture qui rencontre une entrée de projet au format hérité SHALL rendre le projet résolu comme aujourd'hui et SHALL NOT écrire de sauvegarde, de fichier temporaire ni d'entrée migrée. La migration sur disque SHALL être faite par une opération qui tient le verrou exclusif du registre.

#### Scenario: Entrée héritée lue sur un support protégé
- **GIVEN** une entrée de projet au format hérité, une correspondance de chemins configurée et un registre protégé en écriture
- **WHEN** le projet est recherché
- **THEN** il est rendu avec sa racine résolue et aucun fichier n'est créé ni modifié

#### Scenario: Migration par la mutation suivante
- **GIVEN** la même entrée héritée sur un registre inscriptible
- **WHEN** une mutation du registre touche ce projet
- **THEN** l'entrée est au format courant et sa sauvegarde existe

### Requirement: La lecture des observations runtime n'écrit rien
La recherche et la liste des sessions d'observation runtime d'un projet SHALL NOT créer, durcir ni ouvrir en écriture un fichier, fichier de verrou compris. Elles SHALL rester exclues par un import ou une suppression en cours dans un autre processus.

#### Scenario: Sessions listées sur un support protégé
- **GIVEN** un projet ayant une session runtime importée, puis le répertoire des observations protégé en écriture
- **WHEN** les sessions du projet sont listées et l'une est recherchée
- **THEN** les deux lectures aboutissent et l'état du répertoire est identique avant et après
