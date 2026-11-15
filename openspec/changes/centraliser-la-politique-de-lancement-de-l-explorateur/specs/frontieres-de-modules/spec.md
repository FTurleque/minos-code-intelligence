## ADDED Requirements

### Requirement: Les classes de production de minos-app sont tenues par liste fermée
Le garde de frontières SHALL tenir la liste fermée des classes de production de `minos-app`, chacune avec son rôle (assemblage, route de lancement, transport, configuration du backend du home). Une classe de production absente de la liste et une entrée sans classe SHALL faire échouer le garde. La liste SHALL NOT contenir de classe dont le rôle est une politique applicative.

#### Scenario: Liste du HEAD acceptée
- **GIVEN** le dépôt après le changement
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté
- **THEN** il se termine avec le code 0 et annonce le nombre de classes de `minos-app`

#### Scenario: Nouvelle classe dans l'assemblage
- **GIVEN** un arbre de test où `minos-app` gagne une classe de production absente de la liste
- **WHEN** `python scripts/architecture/test_check_module_boundaries.py` est exécuté
- **THEN** le cas vérifie que le garde échoue en nommant la classe et en demandant de la placer dans un module applicatif ou une surface

#### Scenario: Entrée périmée
- **GIVEN** un arbre de test où la liste nomme une classe que `minos-app` ne déclare plus
- **WHEN** l'auto-test du garde est exécuté
- **THEN** le cas vérifie que le garde échoue en demandant de retirer l'entrée

#### Scenario: Rôle absent
- **GIVEN** un arbre de test où une entrée de la liste n'a pas de rôle écrit
- **WHEN** l'auto-test du garde est exécuté
- **THEN** le cas vérifie que le garde échoue en nommant l'entrée
