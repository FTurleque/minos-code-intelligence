# Spec Delta

## ADDED Requirements

### Requirement: L'absence de dépassement de quota est prouvée par des échantillons observés
Le superviseur de quota d'écriture SHALL compter les échantillons qu'il a pris. Un test qui affirme qu'un provider conforme n'est pas arrêté, ou qui lit le volume observé, SHALL attendre au moins deux échantillons comptés, dans une échéance dont l'expiration lève une erreur, avant d'affirmer. Il SHALL NOT s'appuyer sur un délai fixe.

#### Scenario: Provider conforme
- **GIVEN** un superviseur démarré sur un dossier dont le contenu respecte le quota
- **WHEN** deux échantillons au moins ont été comptés avant l'échéance
- **THEN** aucun dépassement n'est rapporté et l'arrêt du job n'a pas été demandé

#### Scenario: Superviseur qui n'échantillonne pas
- **GIVEN** un superviseur dont l'échantillonneur injecté reste bloqué
- **WHEN** l'aide d'attente des échantillons est appelée avec une échéance courte
- **THEN** elle lève une erreur d'assertion qui nomme l'absence d'échantillon

#### Scenario: Racines imbriquées comptées une fois
- **GIVEN** deux racines dont l'une contient l'autre et un fichier de 8 Kio
- **WHEN** deux échantillons au moins ont été comptés avant l'échéance
- **THEN** le volume observé est d'au moins 8 Kio et inférieur à 16 Kio

### Requirement: La cadence du suivi de propriété est jugée sur le temps réellement mesuré
Le test de cadence du suivi de propriété SHALL observer un suivi réel attaché à un processus factice, ouvrir sa fenêtre après une première énumération observée et la phase initiale, et comparer le nombre d'énumérations à la durée mesurée par l'horloge monotone entre ses deux lectures : au moins une, au plus 15 par seconde mesurée. Il SHALL NOT tenir la durée de son propre sommeil pour la durée de la fenêtre.

#### Scenario: Fenêtre réelle en régime établi
- **GIVEN** un suivi réel attaché à un processus factice vivant qui compte ses énumérations, après une première énumération observée et la phase initiale
- **WHEN** une fenêtre d'au moins deux secondes mesurées se termine
- **THEN** le nombre d'énumérations de la fenêtre est d'au moins une et d'au plus 15 par seconde mesurée

#### Scenario: Fil de test endormi plus longtemps que prévu
- **GIVEN** le verdict de cadence appelé avec 45 énumérations et 3,5 secondes mesurées
- **WHEN** il est évalué
- **THEN** il accepte, alors que la borne fixe de 30 l'aurait refusé

#### Scenario: Cadence trop élevée
- **GIVEN** le verdict de cadence appelé avec 40 énumérations et 2 secondes mesurées
- **WHEN** il est évalué
- **THEN** il lève une erreur d'assertion qui donne le nombre, la durée et la borne

#### Scenario: Suivi arrêté
- **GIVEN** le verdict de cadence appelé avec aucune énumération et 2 secondes mesurées
- **WHEN** il est évalué
- **THEN** il lève une erreur d'assertion : un suivi arrêté n'est pas une économie
