## ADDED Requirements

### Requirement: La préparation d'une projection est une commande explicite et bornée
Le système SHALL calculer une projection uniquement par la commande `minos semantic projection prepare`, en lisant les vecteurs par lots bornés, avec une graine et un plafond de points explicites. Un dépassement de budget SHALL échouer sans publier.

#### Scenario: Préparation reproductible
- **GIVEN** un index inchangé, une méthode, des paramètres et une graine
- **WHEN** la préparation est lancée deux fois
- **THEN** les deux projections ont les mêmes coordonnées

#### Scenario: Budget dépassé
- **GIVEN** un corpus dont la lecture dépasse le budget de mémoire configuré
- **WHEN** la préparation est lancée
- **THEN** la commande échoue avec une erreur actionnable et un code de sortie non nul
- **AND** la projection publiée auparavant est intacte

### Requirement: Une projection est publiée atomiquement et liée à l'identité de son index
Le système SHALL publier une projection en une opération atomique et SHALL la lier au projet, au snapshot indexé, au provider, au modèle, aux dimensions, à l'empreinte des documents, à la méthode, à ses paramètres, à la graine et à la sélection. Une projection dont l'identité diffère de l'index courant SHALL être déclarée `PERIMEE`.

#### Scenario: Index resynchronisé
- **GIVEN** une projection `PRETE`
- **WHEN** un document de l'index change puis le statut de projection est lu
- **THEN** l'état rendu est `PERIMEE`

#### Scenario: Préparation interrompue
- **GIVEN** une préparation interrompue avant sa fin
- **WHEN** le statut de projection est lu
- **THEN** aucune projection partielle n'est lisible
- **AND** la projection précédente, si elle existe, reste rendue avec son propre état

### Requirement: Les points d'une projection annoncent méthode, sélection et échantillonnage
Le système SHALL rendre avec les points la méthode, ses paramètres, la graine, le nombre de points et le nombre de documents de l'index, et SHALL indiquer tout échantillonnage. Les points SHALL provenir d'un seul index.

#### Scenario: Corpus échantillonné
- **GIVEN** un index de 1 000 documents et une projection préparée avec un plafond de 200 points
- **WHEN** les points sont lus
- **THEN** la réponse annonce 200 points sur 1 000 documents et l'échantillonnage avec sa graine

### Requirement: La lecture d'une projection ne calcule rien
Le système SHALL rendre le statut et les points d'une projection sans lire de vecteur ni calculer, et SHALL NOT exposer de route HTTP qui prépare une projection. Sans projection préparée, la réponse SHALL indiquer l'état `ABSENTE` et nommer la commande de préparation.

#### Scenario: Projection absente
- **GIVEN** un projet sans projection préparée
- **WHEN** les points de projection sont demandés
- **THEN** la réponse indique l'état `ABSENTE` et nomme la commande de préparation
- **AND** aucun vecteur n'est lu

#### Scenario: Préparation demandée par HTTP
- **GIVEN** la surface d'exploration démarrée et un jeton valide
- **WHEN** une requête `POST` vise la projection d'un projet
- **THEN** la réponse est `405`
- **AND** aucune projection n'est créée ni modifiée
