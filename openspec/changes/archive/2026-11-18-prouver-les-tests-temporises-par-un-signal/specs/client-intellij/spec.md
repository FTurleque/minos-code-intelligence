# Spec Delta

## ADDED Requirements

### Requirement: La cadence du superviseur du plugin est jugée sur le temps réellement mesuré
Le test de cadence du superviseur de processus du plugin SHALL observer un superviseur réel attaché à un processus factice, ouvrir sa fenêtre après une première énumération observée et la phase initiale, et comparer le nombre d'énumérations à la durée mesurée par l'horloge monotone : au moins une, au plus 15 par seconde mesurée, même borne que le runtime local. Il SHALL NOT tenir la durée de son propre sommeil pour la durée de la fenêtre.

#### Scenario: Fenêtre réelle dans le plugin
- **GIVEN** un superviseur réel attaché à un processus factice vivant qui compte ses énumérations, après une première énumération observée et la phase initiale
- **WHEN** une fenêtre d'au moins deux secondes mesurées se termine
- **THEN** le nombre d'énumérations de la fenêtre est d'au moins une et d'au plus 15 par seconde mesurée

#### Scenario: Cadence trop élevée dans le plugin
- **GIVEN** le verdict de cadence du plugin appelé avec 40 énumérations et 2 secondes mesurées
- **WHEN** il est évalué
- **THEN** il lève une erreur d'assertion qui donne le nombre, la durée et la borne

#### Scenario: Superviseur qui n'énumère plus
- **GIVEN** le verdict de cadence du plugin appelé avec aucune énumération et 2 secondes mesurées
- **WHEN** il est évalué
- **THEN** il lève une erreur d'assertion
