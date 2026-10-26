## ADDED Requirements

### Requirement: Le noyau transverse sans E/S est dans le domaine
`PublicErrorMessages`, `UnreadableRegistryException`, `DegradedEntry` et `ProjectPathMapping` SHALL être des classes de `minos-domain`, sans dépendance au moteur. Les surfaces SHALL les utiliser sans entrée dans A11. Un type du noyau transverse resté dans le moteur SHALL porter dans la table A11 la raison écrite de sa place : port d'E/S ou emplacement fixé par un ADR.

#### Scenario: Le message d'erreur public réintroduit dans le moteur
- **GIVEN** une copie du dépôt où `minos-engine` déclare de nouveau une classe du paquet `com.minos.diagnostics`
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté sur cette copie
- **THEN** il échoue, car le paquet appartient à `minos-domain` (A3) et la racine `diagnostics` n'est plus au moteur (A14)

#### Scenario: Les surfaces n'utilisent plus le noyau transverse du moteur
- **GIVEN** le dépôt
- **WHEN** le garde et `ModuleArchitectureTest` sont exécutés
- **THEN** ni la table A11 du garde ni la table bytecode ne listent `PublicErrorMessages`, `UnreadableRegistryException`, `DegradedEntry` ou `ProjectPathMapping`, et les deux réussissent

#### Scenario: Le domaine reste sans dépendance
- **GIVEN** le dépôt
- **WHEN** `ModuleArchitectureTest.coreModulesStayAtTheBottom` est exécuté
- **THEN** aucune classe de `minos-domain`, y compris les classes descendues, ne dépend d'un autre module

#### Scenario: Sorties inchangées
- **GIVEN** le dépôt
- **WHEN** `A2SurfaceCharacterizationTest` est exécuté
- **THEN** les goldens de la CLI, de l'API et du MCP sont inchangés
