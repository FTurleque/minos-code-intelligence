## ADDED Requirements

### Requirement: Un dossier privé préexistant n'est durci que s'il appartient à l'utilisateur
Sur un système de fichiers à ACL, avant de rendre privé un dossier qui existait déjà, MINOS SHALL refuser le profil de l'utilisateur, un de ses parents et une racine de volume, ainsi qu'un dossier qui n'appartient pas à l'utilisateur courant. Un refus SHALL laisser la DACL inchangée et SHALL être signalé sans chemin absolu.

#### Scenario: MINOS_HOME posé sur le profil
- **GIVEN** un `MINOS_HOME` égal au profil de l'utilisateur sous Windows
- **WHEN** MINOS ouvre ce dossier comme stockage privé
- **THEN** l'ouverture échoue avec un message qui demande un dossier dédié, et la DACL du profil est inchangée

#### Scenario: Dossier préexistant d'un autre compte
- **GIVEN** un dossier existant dont le propriétaire n'est pas l'utilisateur courant, sous Windows
- **WHEN** MINOS le demande comme dossier privé
- **THEN** la demande échoue sans chemin dans le message, et la DACL est inchangée

#### Scenario: Dossier de l'utilisateur
- **GIVEN** un dossier absent, ou existant et propriété de l'utilisateur courant
- **WHEN** MINOS le demande comme dossier privé
- **THEN** le dossier est réservé à son propriétaire comme avant
