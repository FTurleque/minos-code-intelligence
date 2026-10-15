## ADDED Requirements

### Requirement: Une recherche de symbole par identifiant utilise l'index de la vue
Un service de requête qui cherche un symbole par son identifiant SHALL utiliser l'index de la vue de requête active et SHALL NOT parcourir la liste des symboles du snapshot. Un identifiant inconnu SHALL produire la même réponse qu'auparavant.

#### Scenario: Rapport runtime d'un symbole
- **GIVEN** un store dont le chargement du snapshot brut échoue et dont la vue de requête est disponible
- **WHEN** le rapport runtime d'un symbole existant est demandé
- **THEN** le rapport est rendu

#### Scenario: Identifiant inconnu
- **GIVEN** une vue de requête active
- **WHEN** le rapport runtime d'un identifiant absent est demandé
- **THEN** la réponse est celle d'avant ce changement

### Requirement: Le poids d'une vue en cache compte ses index dérivés
Le poids d'une vue de requête et celui d'une entrée du cache de graphe de programme SHALL inclure les index dérivés qu'elles retiennent. Le poids estimé SHALL rester supérieur ou égal au tas retenu une fois ces index construits.

#### Scenario: Vue avec index d'arêtes construit
- **GIVEN** une vue de requête dont l'index des arêtes entrantes a été construit
- **WHEN** le test de poids compare l'estimation au tas retenu
- **THEN** l'estimation est supérieure ou égale à la mesure
