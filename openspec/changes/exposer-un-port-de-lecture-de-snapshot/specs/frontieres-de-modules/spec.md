## ADDED Requirements

### Requirement: La classe d'index mémoire n'est nommée que par le cœur
Le garde `check-module-boundaries.py` SHALL refuser toute classe de production qui nomme `InMemoryCodeKnowledgeStore` (import, nom qualifié ou type imbriqué) hors du package `com.minos.store` de `minos-engine`, et toute classe qui nomme `SnapshotQueryView` hors de ce package et d'une liste fermée. La liste SHALL ne contenir que les implémentations dépréciées des deux stores de snapshots et la surcharge dépréciée de l'analyse d'impact.

#### Scenario: Cas d'usage qui nomme la classe mémoire
- **GIVEN** une copie temporaire du dépôt où une classe de `minos-application` importe `com.minos.store.InMemoryCodeKnowledgeStore`
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté sur cette copie
- **THEN** il sort en code 1 en nommant la classe et le type

#### Scenario: Ancienne vue nommée hors de la liste
- **GIVEN** une copie temporaire où une classe de `minos-application` nomme `SnapshotQueryView`
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il sort en code 1 en nommant la classe

#### Scenario: Dépôt conforme
- **GIVEN** le dépôt après ce changement
- **WHEN** le garde et `python scripts/architecture/test_check_module_boundaries.py` sont exécutés
- **THEN** les deux sortent en code 0
