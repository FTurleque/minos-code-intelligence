## ADDED Requirements

### Requirement: L'export NEXUS passe par un cas d'usage applicatif
Les données de l'export NEXUS SHALL être produites par `KnowledgeExportUseCase` (`minos-application`), qui lit le registre et le snapshot actif. `minos-nexus` SHALL seulement traduire ce résultat en `NexusExportContract`, sans nommer ni atteindre par accesseur un type de `minos-engine`, et la CLI SHALL lui passer l'application ouverte. Le JSON, les limitations, les codes de sortie et les messages de refus SHALL rester identiques à l'octet.

#### Scenario: Sortie de l'export inchangée
- **GIVEN** le dépôt et le golden `nexus-export.golden` écrit avant la refonte
- **WHEN** `A2SurfaceCharacterizationTest` est exécuté
- **THEN** la sortie de `minos nexus-export --root` sur la fixture TypeScript égale le golden à l'octet, et les 12 autres goldens sont inchangés

#### Scenario: Racine non enregistrée refusée par son nom
- **GIVEN** un répertoire qui n'est pas une racine enregistrée
- **WHEN** `KnowledgeExportUseCase` exporte ce répertoire
- **THEN** il échoue avec « project root is not registered in MINOS: » suivi du seul nom de feuille, sans chemin absolu, et la CLI sort en 1

#### Scenario: Un type du moteur réintroduit dans NEXUS
- **GIVEN** une copie du dépôt où une classe de `minos-nexus` importe `com.minos.store.CodeKnowledgeSnapshot` ou appelle `snapshotStore()`
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté sur cette copie
- **THEN** le garde échoue en A11 en nommant `minos-nexus`, le fichier et le type

#### Scenario: Tables A11 réduites
- **GIVEN** le dépôt
- **WHEN** le garde et `ModuleArchitectureTest` sont exécutés
- **THEN** aucune table A11 ne liste de type pour `minos-nexus`, la CLI ne liste plus `registry.ProjectRegistry` ni `store.CodeKnowledgeSnapshotStore`, et les deux réussissent

### Requirement: `minos-nexus` ne dépend plus de `minos-engine`
La dépendance `minos-nexus` → `minos-engine` SHALL être absente du POM de `minos-nexus`, de `ALLOWED_DEPENDENCIES` et de `ModuleArchitectureTest.ALLOWED`. Une classe de production de `minos-nexus` qui utilise un type du moteur SHALL faire échouer le build, et le document généré des dépendances SHALL ne plus montrer l'arête.

#### Scenario: Arête réintroduite dans le POM
- **GIVEN** une copie du dépôt où `minos-nexus/pom.xml` déclare de nouveau `minos-engine`
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté sur cette copie
- **THEN** il échoue en citant `minos-nexus` et la dépendance interdite `minos-engine`

#### Scenario: Usage transitif du moteur
- **GIVEN** une copie du dépôt où une classe de production de `minos-nexus` utilise `com.minos.io.Sha256`
- **WHEN** `ModuleArchitectureTest.everyModuleOnlyUsesItsAllowedModules` est exécuté après compilation
- **THEN** il échoue en citant l'usage non déclaré `minos-nexus` → `minos-engine`

#### Scenario: Document généré à jour
- **GIVEN** le dépôt
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté
- **THEN** il réussit, ce qui atteste que `docs/architecture/diagrams/module-dependencies.md` liste pour `minos-nexus` seulement `minos-application`, `minos-bootstrap` et `minos-domain`
