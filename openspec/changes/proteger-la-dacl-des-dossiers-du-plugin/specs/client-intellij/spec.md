## ADDED Requirements

### Requirement: Le lanceur du plugin ne réécrit jamais la DACL d'un dossier qui n'est pas à lui
Sous Windows, le lanceur du plugin SHALL refuser, avant toute modification, un dossier MINOS qui est le profil de l'utilisateur, un ancêtre du profil ou une racine de volume. Il SHALL ne rendre privé un dossier préexistant que s'il appartient à l'utilisateur courant, et SHALL sinon échouer sans changer sa DACL.

#### Scenario: Dossier MINOS configuré sur le profil
- **GIVEN** des réglages dont le dossier MINOS est le profil de l'utilisateur
- **WHEN** le plugin lance une commande MINOS
- **THEN** le lancement échoue avec un message qui demande un dossier dédié, et la DACL du profil est inchangée

#### Scenario: Dossier préexistant d'un autre propriétaire
- **GIVEN** un dossier MINOS qui existe déjà et dont le propriétaire n'est pas l'utilisateur courant
- **WHEN** le plugin lance une commande MINOS
- **THEN** le lancement échoue en nommant le problème de propriété, et la DACL du dossier est inchangée

#### Scenario: Dossier de l'utilisateur
- **GIVEN** un dossier MINOS absent, ou présent et appartenant à l'utilisateur courant
- **WHEN** le plugin lance une commande MINOS
- **THEN** le dossier est réservé à son propriétaire et la commande démarre
