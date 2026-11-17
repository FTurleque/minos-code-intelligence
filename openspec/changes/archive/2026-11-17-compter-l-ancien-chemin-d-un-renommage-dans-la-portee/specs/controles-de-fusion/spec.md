## ADDED Requirements

### Requirement: Les deux chemins d'un renommage comptent dans une portée calculée
Le calcul de portée du plugin IntelliJ et celui de la qualification Docker SHALL lire le diff sans détection de renommage, de sorte que l'ancien chemin d'un fichier renommé ou supprimé compte comme son nouveau chemin. Une pull request qui déplace un fichier d'un chemin concerné vers un chemin exclu SHALL être déclarée concernée.

#### Scenario: Source principale déplacée sous les tests
- **WHEN** `python scripts/ci/test_docker_gate.py` rejoue, dans un dépôt temporaire, le renommage de `minos-engine/src/main/java/.../Cache.java` vers `minos-engine/src/test/java/.../Cache.java`
- **THEN** la portée Docker est `true`

#### Scenario: Source principale déplacée sous la documentation
- **WHEN** le même auto-test rejoue le renommage d'une source principale vers `docs/`
- **THEN** la portée Docker est `true`

#### Scenario: Rendu déplacé hors des chemins du plugin
- **WHEN** `python scripts/ci/test_plugin_gate.py` rejoue le renommage d'un fichier de `minos-application/src/main/java/com/minos/output/` vers `docs/`
- **THEN** la portée du plugin est `true`

#### Scenario: Renommage interne à la liste d'exclusion
- **WHEN** l'auto-test Docker rejoue le renommage de `docs/a.md` vers `openspec/a.md`
- **THEN** la portée Docker est `false`
