## ADDED Requirements

### Requirement: La preuve inter-processus du verrou de synchronisation échoue quand son détenteur ne démarre pas
Le test qui prouve qu'un verrou de fichier tenu par un autre processus bloque la synchronisation sémantique d'un projet SHALL échouer quand le processus détenteur ne peut pas être lancé ou ne signale pas qu'il tient le verrou. Le message d'échec SHALL contenir le code de sortie et la sortie d'erreur bornée de ce processus. Le test SHALL NOT être compté comme sauté dans ces cas.

#### Scenario: Détenteur prêt
- **GIVEN** un processus détenteur qui signale `READY` après avoir pris le verrou de fichier du projet
- **WHEN** `./mvnw -B -ntp -pl minos-storage -am test "-Dtest=FileSemanticSyncConsistencyTest" "-Dsurefire.failIfNoSpecifiedTests=false"` est exécuté
- **THEN** le test inter-processus s'exécute, observe la synchronisation en attente puis aboutie après la libération, et le rapport Surefire ne compte aucun test sauté pour cette classe

#### Scenario: Détenteur qui ne démarre pas
- **GIVEN** un lancement du détenteur avec une classe principale introuvable
- **WHEN** le test de garde de ce lanceur s'exécute
- **THEN** la preuve échoue par une erreur d'assertion dont le message contient le code de sortie du processus et un extrait de sa sortie d'erreur
- **AND** aucune hypothèse de saut n'est levée

#### Scenario: Sortie d'erreur volumineuse
- **GIVEN** un détenteur qui écrit une sortie d'erreur plus longue que la borne retenue
- **WHEN** la preuve échoue
- **THEN** le message ne contient que la fin bornée de cette sortie et le test ne reste pas bloqué sur un tube plein
