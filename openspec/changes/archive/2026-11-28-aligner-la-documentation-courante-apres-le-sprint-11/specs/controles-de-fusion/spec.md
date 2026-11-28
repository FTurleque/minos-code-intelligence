## ADDED Requirements

### Requirement: Les documents d'état ne citent aucun décompte d'auto-test non relu
`docs/project/governance/STATUS.md` et `docs/project/governance/ROADMAP.md` SHALL NOT écrire le nombre de cas d'un auto-test de gate, qu'aucun gate ne compare au dépôt.

#### Scenario: Documents d'état sans décompte
- **GIVEN** le dépôt après correction
- **WHEN** `git grep -nE "[0-9]+ cas\b" -- docs/project/governance/STATUS.md docs/project/governance/ROADMAP.md` est exécuté
- **THEN** la commande ne rend aucune ligne et se termine avec le code 1

#### Scenario: Décompte réintroduit
- **GIVEN** une copie de travail où `STATUS.md` écrit de nouveau « garde de frontières testée règle par règle (332 cas) »
- **WHEN** la même commande est exécutée
- **THEN** elle rend cette ligne et se termine avec le code 0, ce que la revue refuse

#### Scenario: Faits produit inchangés
- **GIVEN** le dépôt après correction
- **WHEN** `python scripts/docs/check-current-docs.py` puis `python scripts/docs/product-facts.py --check` sont exécutés
- **THEN** les deux commandes se terminent avec le code 0
