## ADDED Requirements

### Requirement: Une classe publique de production est référencée par la production ou déclarée
Tout type public de premier niveau des sources de production du reactor SHALL être nommé par un autre fichier de production, ou par un fichier `META-INF/services` de production, ou déclarer un point d'entrée `main`, ou figurer dans la liste nominative du garde avec une raison écrite. Le garde de frontières SHALL échouer sinon, en nommant le type.

#### Scenario: Classe sans appelant ajoutée
- **GIVEN** une copie du dépôt où une classe publique de `src/main` n'est nommée par aucun autre fichier de production ni inscrite sur la liste
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté sur cette copie
- **THEN** le garde échoue en nommant la classe et son module

#### Scenario: Classe nommée seulement par des tests
- **GIVEN** une copie du dépôt où une classe publique de `src/main` n'est nommée que par des fichiers de `src/test`
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue : une référence de test ne compte pas

#### Scenario: Implémentation découverte par service
- **GIVEN** une classe publique nommée seulement dans un fichier `META-INF/services` de production
- **WHEN** le garde est exécuté
- **THEN** la classe est acceptée sans figurer sur la liste

#### Scenario: Dépôt réel
- **GIVEN** le dépôt après le changement
- **WHEN** le garde est exécuté
- **THEN** il réussit et affiche le nombre de types inscrits sur la liste

### Requirement: La liste des classes sans appelant est un cliquet justifié
Chaque entrée de la liste nominative SHALL nommer un seul type et porter une raison non vide. Une entrée dont le type n'existe plus, ou est désormais nommé par la production, SHALL faire échouer le garde, de sorte que la liste ne peut que rétrécir.

#### Scenario: Entrée périmée
- **GIVEN** une liste qui nomme une classe supprimée
- **WHEN** le garde est exécuté
- **THEN** il échoue en nommant l'entrée périmée

#### Scenario: Entrée devenue inutile
- **GIVEN** une liste qui nomme une classe qu'un fichier de production référence désormais
- **WHEN** le garde est exécuté
- **THEN** il échoue en demandant de retirer l'entrée

#### Scenario: Entrée sans raison
- **GIVEN** une liste dont une entrée a une raison vide
- **WHEN** le garde est exécuté
- **THEN** il échoue en nommant l'entrée
