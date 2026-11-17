# Spec Delta

## ADDED Requirements

### Requirement: Le budget des vues et les caches applicatifs tiennent sous le tas par défaut du plan de requête
Sous la limite mémoire et le pourcentage de tas par défaut du plan de requête, la somme du budget de mémoire des vues, de la marge de décodage d'une vue déclarée et des plafonds effectifs de tous les caches applicatifs pondérés SHALL être inférieure ou égale au tas maximal de la JVM. Le test de contrat de l'image de requête SHALL calculer cette somme depuis les fichiers compose et les valeurs du code, et SHALL échouer en nommant chaque terme quand elle dépasse le tas.

#### Scenario: Somme sous le tas par défaut
- **GIVEN** les deux fichiers compose, le pourcentage de tas déclaré, la marge de décodage et les parts du tas déclarées dans le code
- **WHEN** le test de contrat calcule le tas par défaut, le budget des vues, la marge de décodage et le plafond effectif de chaque cache, en stockage local puis PostgreSQL
- **THEN** la somme est inférieure ou égale au tas

#### Scenario: Part relevée au-delà du tas
- **GIVEN** une part du tas, une marge ou un plafond relevé de sorte que la somme dépasse le tas par défaut
- **WHEN** le test de contrat s'exécute
- **THEN** il échoue en nommant le tas, le budget des vues, la marge de décodage et le plafond effectif de chaque cache

#### Scenario: Cache pondéré ajouté sans part déclarée
- **GIVEN** un cache pondéré construit en production sans passer par la part du tas, qu'il soit un cache LRU pondéré ou un cache nommé dans la liste fermée du recensement
- **WHEN** le test qui recense les caches pondérés s'exécute
- **THEN** il échoue en nommant la classe qui construit ce cache
