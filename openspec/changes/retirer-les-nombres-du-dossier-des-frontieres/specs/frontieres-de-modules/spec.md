## ADDED Requirements

### Requirement: Le dossier des frontières n'écrit aucun décompte de la politique
`docs/architecture/diagrams/capacites/frontieres-de-modules.md` SHALL ne contenir aucun nombre en chiffres suivi d'un module, d'une dépendance, d'une découverte, d'un package ou d'un cycle. Aucune preuve d'écart SHALL nommer une méthode que `MinosApplication` ne déclare plus. `test_check_module_boundaries_documents.py` SHALL échouer en nommant chaque ligne fautive.

#### Scenario: Dossier sans décompte
- **GIVEN** le dépôt après correction
- **WHEN** `python scripts/architecture/test_check_module_boundaries_documents.py` est exécuté
- **THEN** l'auto-test se termine avec le code 0

#### Scenario: Décompte réintroduit
- **GIVEN** une copie du dépôt où une ligne du dossier écrit « minos-app dépend des 12 autres modules »
- **WHEN** `python scripts/architecture/test_check_module_boundaries_documents.py` est exécuté dans la copie
- **THEN** l'auto-test se termine avec le code 1 et nomme la ligne

#### Scenario: Preuve d'écart sur des accesseurs retirés
- **GIVEN** le dépôt après correction
- **WHEN** `git grep -nE "gitIntelligence\(\)|hostedControlPlaneService\(\)" -- docs/architecture/diagrams/capacites/frontieres-de-modules.md` est exécuté
- **THEN** la commande ne rend aucune ligne et se termine avec le code 1
