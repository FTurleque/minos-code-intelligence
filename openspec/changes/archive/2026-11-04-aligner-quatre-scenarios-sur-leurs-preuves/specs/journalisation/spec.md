## MODIFIED Requirements

### Requirement: La cause d'un journal n'est transmise que par une liste nominative
Le garde SHALL échouer quand un appel `LOGGER.log` des sources de production transmet un `Throwable` ou appelle `getMessage()`, sauf pour les entrées nommées d'une liste qui porte pour chacune un maximum d'occurrences et une justification ; une entrée au maximum supérieur au décompte réel SHALL elle aussi échouer.

#### Scenario: Cause transmise hors liste
- **GIVEN** un appel `LOGGER.log(WARNING, "…", exception)` dans un fichier absent de la liste
- **WHEN** le garde est exécuté
- **THEN** il échoue en nommant le fichier

#### Scenario: Entrée de liste obsolète
- **GIVEN** une entrée dont le maximum dépasse le nombre d'occurrences réel du fichier
- **WHEN** le garde est exécuté
- **THEN** il échoue en demandant de resserrer la liste

#### Scenario: Diagnostic de bac à sable listé
- **GIVEN** la liste nominative du dépôt, dont chaque entrée nomme un fichier, un maximum et une justification (diagnostics de bac à sable, refus de reprise d'indexation, journaux serveur du plan de contrôle hébergé)
- **WHEN** le garde est exécuté sur le dépôt
- **THEN** il réussit
- **AND** chaque appel qui transmet une cause appartient à un fichier de la liste, sans dépasser son maximum
