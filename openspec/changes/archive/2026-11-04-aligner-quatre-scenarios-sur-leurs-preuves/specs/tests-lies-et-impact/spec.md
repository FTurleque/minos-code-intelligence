## MODIFIED Requirements

### Requirement: Les preuves d'une relation de test lié sont bornées et l'omission est déclarée
Une relation de test lié SHALL conserver au plus N emplacements par type de preuve, choisis dans un ordre déterministe indépendant de l'hôte. Quand des emplacements sont omis, la relation SHALL porter une preuve de synthèse qui en donne le nombre. L'ensemble des tests liés, leur confiance et leur ordre SHALL NOT dépendre du plafond.

#### Scenario: Test qui référence un symbole plus de N fois
- **GIVEN** un fichier de test qui référence le même symbole de production à N + 3 emplacements
- **WHEN** les tests liés sont dérivés
- **THEN** la relation porte N preuves de référence directe avec emplacement et une preuve de synthèse qui annonce 3 emplacements omis

#### Scenario: Score inchangé par le plafond
- **GIVEN** deux corpus de mêmes tests et symboles, l'un avec une seule occurrence par paire, l'autre avec plus d'occurrences que le plafond pour chaque paire
- **WHEN** les relations sont dérivées sur chacun
- **THEN** les paires test et symbole, leurs confiances et leur ordre sont identiques

#### Scenario: Ordre reproductible
- **GIVEN** le même corpus dérivé deux fois, les occurrences étant fournies dans un ordre différent
- **WHEN** les preuves conservées sont comparées
- **THEN** ce sont les mêmes emplacements, dans le même ordre
