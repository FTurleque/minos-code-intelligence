## ADDED Requirements

### Requirement: La classe d'index mémoire n'est nommée que par le cœur
Le garde `check-module-boundaries.py` SHALL refuser toute classe de production hors du package `com.minos.store` de `minos-engine` qui nomme `InMemoryCodeKnowledgeStore` (import simple, statique ou du package entier, nom qualifié, type imbriqué), et toute classe hors de ce package et d'une liste fermée qui nomme `SnapshotQueryView` ou appelle `loadActiveQueryView(` ou `queryStore()`. La liste SHALL ne contenir que les deux stores de snapshots et l'analyse d'impact.

#### Scenario: Cas d'usage qui nomme la classe mémoire
- **GIVEN** une copie temporaire du dépôt où une classe de `minos-application` importe `com.minos.store.InMemoryCodeKnowledgeStore`
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté sur cette copie
- **THEN** il sort en code 1 en nommant la classe et le type

#### Scenario: Import du package entier du cœur
- **GIVEN** une copie temporaire où une classe de `minos-application` importe `com.minos.store.*` et déclare une variable de type `InMemoryCodeKnowledgeStore`
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il sort en code 1 en nommant la classe

#### Scenario: Ancienne vue nommée hors de la liste
- **GIVEN** une copie temporaire où une classe de `minos-application` nomme `SnapshotQueryView`
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il sort en code 1 en nommant la classe

#### Scenario: Index mémoire atteint par chaînage, sans aucun nom
- **GIVEN** une copie temporaire où une classe de `minos-application` écrit `var store = snapshots.loadActiveQueryView(id).orElseThrow().queryStore();` sans importer de type de `com.minos.store`
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il sort en code 1 en nommant la classe et l'appel

#### Scenario: Liste fermée qui nomme une classe absente
- **GIVEN** une copie temporaire où l'une des classes de la liste fermée n'existe plus ou n'utilise plus l'ancien contrat
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il sort en code 1 en demandant de retirer l'entrée

#### Scenario: Dépôt conforme
- **GIVEN** le dépôt après ce changement
- **WHEN** le garde et `python scripts/architecture/test_check_module_boundaries.py` sont exécutés
- **THEN** les deux sortent en code 0
