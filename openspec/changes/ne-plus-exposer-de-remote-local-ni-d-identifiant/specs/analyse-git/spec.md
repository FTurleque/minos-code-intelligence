## ADDED Requirements

### Requirement: Le remote rendu ne contient ni identifiant ni chemin local
L'activité Git rendue par la CLI et par l'API Java SHALL rendre `originRemote` seulement pour un remote réseau reconnu (URL à schéma et hôte autre que `file`, ou forme SCP), sans identifiants, requête ni fragment. Toute autre valeur SHALL être rendue `null` avec la limitation `ORIGIN_REMOTE_NOT_DISCLOSED`. L'identité du dépôt SHALL rester celle calculée avant ce changement.

#### Scenario: URL réseau à identifiants
- **GIVEN** un dépôt dont `remote.origin.url` vaut `https://user:secret@example.com/org/repo.git?token=x`
- **WHEN** l'activité Git est demandée
- **THEN** `originRemote` vaut `https://example.com/org/repo`

#### Scenario: URL non analysable à identifiants
- **GIVEN** un remote `https://user:p ss@example.com/org/repo.git`
- **WHEN** l'activité Git est demandée
- **THEN** `originRemote` est `null`, la limitation `ORIGIN_REMOTE_NOT_DISCLOSED` est présente et la sortie ne contient pas `p ss`

#### Scenario: Remote local
- **GIVEN** un remote `/srv/depots/repo.git`, `C:\depots\repo` ou `file:///srv/repo.git`
- **WHEN** l'activité Git est demandée
- **THEN** `originRemote` est `null` et la limitation `ORIGIN_REMOTE_NOT_DISCLOSED` est présente

#### Scenario: Forme SCP
- **GIVEN** un remote `git@github.com:org/repo.git`
- **WHEN** l'activité Git est demandée
- **THEN** `originRemote` vaut `github.com:org/repo`

#### Scenario: Identité inchangée
- **GIVEN** un dépôt à remote local connu avant ce changement
- **WHEN** son identité est recalculée
- **THEN** `repositoryId` est identique
