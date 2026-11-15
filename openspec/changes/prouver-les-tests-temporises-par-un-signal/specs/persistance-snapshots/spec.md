# Spec Delta

## ADDED Requirements

### Requirement: La mesure du tas retenu par une vue ne dépend d'aucun délai fixe
Le test qui compare le poids estimé d'une vue de requête au tas qu'elle retient SHALL obtenir chaque mesure de tas par des collectes répétées jusqu'à ce que deux lectures consécutives soient égales, dans une limite de tours. Il SHALL NOT attendre une durée fixe entre les collectes. Si la limite de tours est atteinte sans stabilité, la mesure MUST lever une erreur qui le dit, sans conclure sur le poids.

#### Scenario: Tas stabilisé
- **GIVEN** une vue chargée depuis un snapshot publié
- **WHEN** le tas est mesuré avant et après le chargement
- **THEN** chaque mesure est la valeur commune de deux lectures consécutives et le poids estimé est supérieur ou égal au tas retenu

#### Scenario: Tas jamais stable
- **GIVEN** l'aide de mesure appelée avec une source de lectures injectée dont les valeurs diffèrent à chaque tour
- **WHEN** la limite de tours est atteinte
- **THEN** elle lève une erreur d'assertion qui nomme l'instabilité
