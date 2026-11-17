# Spec Delta

## ADDED Requirements

### Requirement: PostgreSQL rapatrie la charge utile de la version dont la taille a été réservée
Le store PostgreSQL SHALL demander la charge utile du snapshot actif en la désignant par le projet, l'identifiant de snapshot et l'empreinte lus avec les métadonnées qui ont servi à la réservation. Si cette version n'est plus la version active, il SHALL reprendre la résolution par une nouvelle lecture des métadonnées et une nouvelle réservation, dans la limite de tentatives existante. Il SHALL NOT demander la charge utile d'une autre version sous cette réservation.

#### Scenario: Publication concurrente d'un snapshot plus gros
- **GIVEN** un projet dont le snapshot actif A a été lu en métadonnées et sa réservation accordée, puis un snapshot B plus gros publié avant la demande de la charge utile
- **WHEN** le chargement de la vue active se poursuit
- **THEN** aucune requête ne rend la charge utile de B sous la réservation calculée pour A
- **AND** la résolution reprend, réserve d'après la taille de B et rend la vue de B

#### Scenario: Nouvelle version plus lourde que le budget
- **GIVEN** la même situation, où la réservation de B dépasse le budget de mémoire des vues
- **WHEN** la résolution reprend
- **THEN** la lecture échoue par l'erreur dont le message commence par « insufficient memory to load this snapshot »
- **AND** la charge utile de B n'a jamais été demandée et aucun fichier temporaire ne subsiste

#### Scenario: Version active stable
- **GIVEN** un projet dont le snapshot actif ne change pas pendant le chargement
- **WHEN** sa vue active est demandée
- **THEN** la charge utile est demandée une seule fois et la vue rendue est celle de ce snapshot

#### Scenario: Version active remplacée à chaque tentative
- **GIVEN** un projet dont le snapshot actif est remplacé avant chaque demande de charge utile
- **WHEN** la limite de tentatives est atteinte
- **THEN** la lecture échoue par l'erreur existante de dépassement de la limite de tentatives
- **AND** aucune réservation n'est retenue

### Requirement: Une vue admise plus lourde que le plafond du cache reste comptée par le budget
Une vue admise par le budget de mémoire des vues dont le poids estimé dépasse le plafond effectif du cache SHALL être retenue seule par son store et comptée comme poids résident tant qu'il la retient. Elle SHALL être servie aux lectures suivantes sans rechargement et libérée par l'éviction du budget, une publication du projet ou son remplacement. Un store SHALL NOT rendre une vue admise sans que son poids soit compté.

#### Scenario: Vue retenue et comptée
- **GIVEN** un snapshot dont la vue estimée dépasse le plafond effectif du cache sans dépasser le budget
- **WHEN** sa vue active est lue, par le store fichier puis par le store PostgreSQL
- **THEN** le poids résident rapporté par le budget est au moins égal au poids estimé de cette vue
- **AND** aucune réservation n'est retenue

#### Scenario: Les autres vues du cache lui cèdent la place
- **GIVEN** la vue du projet A en cache et une vue du projet B plus lourde que le plafond effectif, qui tient dans le budget
- **WHEN** la vue de B est lue
- **THEN** la vue de A n'est plus en cache et la somme des poids résidents et des réservations ne dépasse pas le budget

#### Scenario: Libération pour admettre un autre chargement
- **GIVEN** une vue retenue au-dessus du plafond effectif et un budget qui ne contient pas aussi la vue d'un autre projet
- **WHEN** la vue de cet autre projet est demandée
- **THEN** la vue retenue est libérée et le chargement est admis sans attendre l'expiration du délai

#### Scenario: Publication du projet
- **GIVEN** une vue retenue au-dessus du plafond effectif
- **WHEN** un nouveau snapshot du même projet est publié
- **THEN** la vue retenue est libérée et le poids résident du store revient à zéro

### Requirement: Le poids de la vue la plus lourde persistable se calcule index dérivés compris
Les limites de snapshot SHALL fournir le poids estimé d'une vue au format courant de la taille du plafond de persistance pour des nombres donnés de symboles, d'occurrences et de relations, index des arêtes entrantes compris. Ce poids SHALL être égal à celui que les deux stores estiment pour un snapshot de cette taille et de ces comptes, que l'amplification ou la somme des comptes domine.

#### Scenario: Même estimation que les stores
- **GIVEN** un plafond de persistance abaissé pour le test et un snapshot de cette taille portant des nombres connus de symboles, d'occurrences et de relations
- **WHEN** le test compare le poids fourni par les limites au poids que chaque store attribue à la vue
- **THEN** les valeurs sont égales

#### Scenario: Somme des comptes dominante
- **GIVEN** des comptes dont la somme pondérée dépasse le produit du plafond de persistance par l'amplification
- **WHEN** le poids est demandé pour ces comptes
- **THEN** il vaut cette somme des comptes augmentée du poids de l'index des arêtes entrantes pour ce nombre de relations

#### Scenario: Sans aucune entité
- **GIVEN** les limites de production
- **WHEN** le poids est demandé pour zéro symbole, zéro occurrence et zéro relation
- **THEN** il vaut le produit du plafond de persistance par l'amplification du format courant, augmenté de la part fixe de l'index des arêtes entrantes

## MODIFIED Requirements

### Requirement: Un snapshot de la taille de ce dépôt est admis au cache de vues
Les deux stores de snapshots SHALL estimer le poids d'une vue de requête d'après une amplification mesurée de la taille persistée. Un snapshot au format courant dont la vue tient sous le plafond effectif du cache SHALL être mis en cache : deux lectures consécutives de la vue active SHALL ne provoquer qu'un seul chargement complet.

#### Scenario: Snapshot de plus de 64 Mio persistés
- **GIVEN** un snapshot actif au format courant de plus de 64 Mio persistés, dont la vue pèse moins que le plafond effectif du cache
- **WHEN** la vue active est lue deux fois de suite
- **THEN** le compteur de chargements complets vaut 1

#### Scenario: Snapshot de plus de 300 Mio persistés
- **GIVEN** un snapshot actif au format courant de plus de 300 Mio persistés, dont le poids estimé est inférieur au plafond effectif du cache
- **WHEN** la vue active est lue deux fois de suite
- **THEN** le compteur de chargements complets vaut 1
- **AND** la seconde lecture est servie sans relire le fichier

#### Scenario: Vue plus lourde que le plafond
- **GIVEN** un snapshot dont la vue estimée dépasse le plafond effectif du cache sans dépasser le budget de mémoire des vues
- **WHEN** la vue active est lue deux fois de suite
- **THEN** elle est retenue seule par le store et le compteur de chargements complets vaut 1
- **AND** son poids est compté par le budget de mémoire des vues tant que le store la retient
