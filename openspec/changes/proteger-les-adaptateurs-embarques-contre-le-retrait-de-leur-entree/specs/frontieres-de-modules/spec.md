## MODIFIED Requirements

### Requirement: Une classe d'adaptateur embarquée n'est nommée que par sa famille
Le garde de frontières SHALL tenir la liste fermée des classes d'adaptateur que `minos-application` embarque, chacune avec sa justification. Aucune source de production de `minos-application` hors de cette liste SHALL nommer une de ces classes, par import, nom qualifié ou nom simple du même package. Chaque classe de `INFRASTRUCTURE_API_USERS` SHALL figurer dans cette liste par son nom simple. La liste SHALL seulement rétrécir.

#### Scenario: Le service nomme de nouveau un adaptateur
- **GIVEN** un arbre de test où `ProgramGraphService` instancie `FileProgramGraphProvider`
- **WHEN** l'auto-test du garde est exécuté
- **THEN** le cas vérifie que le garde échoue en nommant le fichier et la classe

#### Scenario: Classes de la liste entre elles
- **GIVEN** le dépôt après le changement, où `JavaProgramGraphEngine` nomme `JavaAstParser` et `SourceStateKeyMemo` nomme `JavaProgramGraphEngine.SourceStateKey`
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté
- **THEN** le garde réussit

#### Scenario: Entrée sans classe
- **GIVEN** un arbre de test où la liste nomme une classe absente des sources de production de `minos-application`
- **WHEN** l'auto-test du garde est exécuté
- **THEN** le cas vérifie que le garde échoue en demandant de retirer l'entrée

#### Scenario: Entrée retirée alors que la classe nomme encore une API d'infrastructure
- **GIVEN** un arbre de test où `OllamaEmbeddingProvider` nomme `java.net.HttpURLConnection`, figure dans `INFRASTRUCTURE_API_USERS` et manque à la liste des adaptateurs embarqués
- **WHEN** `python scripts/architecture/test_check_module_boundaries.py` est exécuté
- **THEN** le cas vérifie que le garde échoue en nommant `OllamaEmbeddingProvider` et la liste où l'ajouter
- **AND** le même arbre où une autre source nomme `OllamaEmbeddingProvider` échoue aussi
