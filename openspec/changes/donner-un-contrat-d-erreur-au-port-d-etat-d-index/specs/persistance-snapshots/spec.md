## ADDED Requirements

### Requirement: Une panne du stockage de l'état d'index a un type unique
Toute implémentation de production du port d'état d'index SHALL signaler une panne de stockage (E/S, SQL, contenu illisible, bail non obtenu ou non rendu) par le type d'échec unique déclaré par le port, cause conservée. Elle SHALL NOT la signaler par `UncheckedIOException` ni par `IllegalStateException`, réservée aux violations d'invariant.

#### Scenario: Fichier d'état illisible
- **GIVEN** un store fichier dont le fichier d'état d'un projet est corrompu
- **WHEN** l'état du projet est lu
- **THEN** l'échec est du type du port et sa cause est l'erreur d'origine

#### Scenario: Erreur SQL
- **GIVEN** un store PostgreSQL dont la connexion échoue
- **WHEN** un run est enregistré
- **THEN** l'échec est du type du port, pour le store simple comme pour le store à mutation de projet

#### Scenario: Violation d'invariant
- **GIVEN** un bail de cycle de vie acquis par un thread
- **WHEN** un autre thread le libère
- **THEN** l'échec est une `IllegalStateException` et non le type du port

#### Scenario: Interruption pendant une écriture
- **GIVEN** une écriture d'état interrompue par l'interruption du thread
- **WHEN** l'exécuteur de run traite l'échec
- **THEN** le run est marqué interrompu et proposé à la reprise, comme avant

### Requirement: Un défaut de programmation n'est pas requalifié en panne d'E/S
Les appelants du port d'état d'index SHALL n'attraper que le type d'échec du port. Une exception d'un autre type levée par un store SHALL remonter telle quelle et SHALL NOT être convertie en erreur de lecture ou d'écriture de l'état d'index.

#### Scenario: Store qui lève NullPointerException
- **GIVEN** un store dont la lecture de l'état du projet lève `NullPointerException`
- **WHEN** la réconciliation de l'état du projet est exécutée
- **THEN** l'exception remonte et aucune erreur « failed to read project index state » n'est produite

#### Scenario: Panne de stockage
- **GIVEN** un store dont la lecture lève le type d'échec du port
- **WHEN** la réconciliation est exécutée
- **THEN** elle échoue avec l'erreur d'E/S existante, qui nomme le projet

### Requirement: Les messages du port d'état d'index ne contiennent aucun chemin absolu
Le message d'un échec levé par une implémentation du port SHALL NOT contenir le chemin absolu d'un fichier d'état ni la racine de stockage. Il SHALL nommer la nature de l'état et son identifiant quand il est connu ; la cause technique SHALL rester chaînée.

#### Scenario: Lecture d'un fichier d'état en échec
- **GIVEN** un store fichier placé sous une racine temporaire, dont un fichier d'état est illisible
- **WHEN** l'état est lu et l'échec capturé
- **THEN** le message ne contient pas le chemin de la racine temporaire

#### Scenario: Propriété manquante
- **GIVEN** un fichier d'état auquel il manque une propriété obligatoire
- **WHEN** il est lu
- **THEN** le message nomme la propriété et ne contient aucun chemin absolu
