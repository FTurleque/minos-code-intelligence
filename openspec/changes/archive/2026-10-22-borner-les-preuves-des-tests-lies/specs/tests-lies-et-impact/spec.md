## ADDED Requirements

### Requirement: Les preuves d'une relation de test lié sont bornées et l'omission est déclarée
Une relation de test lié SHALL conserver au plus N emplacements par type de preuve, choisis dans un ordre déterministe indépendant de l'hôte. Quand des emplacements sont omis, la relation SHALL porter une preuve de synthèse qui en donne le nombre. L'ensemble des tests liés, leur confiance et leur ordre SHALL NOT dépendre du plafond.

#### Scenario: Test qui référence un symbole plus de N fois
- **GIVEN** un fichier de test qui référence le même symbole de production à N + 3 emplacements
- **WHEN** les tests liés sont dérivés
- **THEN** la relation porte N preuves de référence directe avec emplacement et une preuve de synthèse qui annonce 3 emplacements omis

#### Scenario: Score inchangé par le plafond
- **GIVEN** un corpus dont plusieurs relations dépassent le plafond
- **WHEN** les relations sont dérivées avec puis sans plafond
- **THEN** les paires test et symbole, leurs confiances et leur ordre sont identiques

#### Scenario: Ordre reproductible
- **GIVEN** le même corpus dérivé deux fois, les occurrences étant fournies dans un ordre différent
- **WHEN** les preuves conservées sont comparées
- **THEN** ce sont les mêmes emplacements, dans le même ordre

### Requirement: Sous le plafond, les sorties de tests liés sont inchangées
Une relation de test lié dont aucun type de preuve ne dépasse N emplacements SHALL être persistée et rendue à l'identique : aucune preuve retirée, ajoutée ni réordonnée, aucune preuve de synthèse. Le format de snapshot SHALL NOT changer, et un snapshot produit avant le plafond SHALL rester lisible.

#### Scenario: Goldens de caractérisation
- **GIVEN** les goldens de caractérisation de `minos-app`, dont aucune relation ne dépasse le plafond
- **WHEN** `./mvnw -B -ntp -pl minos-app -am verify` est exécuté
- **THEN** les sorties CLI et MCP sont identiques à l'octet

#### Scenario: Snapshot antérieur
- **GIVEN** un snapshot persisté avant le changement, dont une relation porte plus de N preuves
- **WHEN** il est relu
- **THEN** il est décodé sans erreur et toutes ses preuves sont rendues

#### Scenario: Relation exactement au plafond
- **GIVEN** un test qui référence un symbole à exactement N emplacements
- **WHEN** les tests liés sont dérivés
- **THEN** la relation porte N preuves et aucune preuve de synthèse
