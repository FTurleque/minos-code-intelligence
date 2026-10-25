## ADDED Requirements

### Requirement: Le diagnostic de l'hôte est consommé par un service applicatif
`minos doctor` SHALL obtenir la confidentialité des emplacements de `MINOS_HOME`, la sélection des bacs à sable et la localisation des commandes de l'hôte par `HostDiagnosticsService` de `minos-application`. Les sources de production de `minos-cli` SHALL NOT nommer ni utiliser `io.PrivateLocalStorage`, `runtime.WorkerSandboxProbe` ou `runtime.HostCommandLocator`, que ce soit par import, par nom qualifié, par chaînage ou en bytecode.

#### Scenario: Le moteur réintroduit dans la commande
- **GIVEN** une copie du dépôt où `DoctorCommand` importe de nouveau `com.minos.io.PrivateLocalStorage`
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté sur cette copie
- **THEN** il échoue en nommant `minos-cli`, le fichier et le type, absent de `ENGINE_TYPES_NAMED_BY_SURFACE`

#### Scenario: Un appel chaîné sur la racine de composition
- **GIVEN** une `DoctorCommand` qui appelle `MinosApplicationComposers.resolve().hostCommandLocator().find(…)`
- **WHEN** `ModuleArchitectureTest` est exécuté
- **THEN** il échoue en nommant `runtime.HostCommandLocator`, absent de la table bytecode de la CLI

#### Scenario: Sortie inchangée
- **GIVEN** le dépôt
- **WHEN** `A2SurfaceCharacterizationTest` et `DoctorCommandTest` sont exécutés
- **THEN** la sortie texte de `minos doctor` égale `cli-doctor.golden` et ses clés JSON sont inchangées

#### Scenario: Service testé sans racine de composition
- **GIVEN** une sonde des bacs à sable et un localisateur de commandes factices
- **WHEN** `HostDiagnosticsService` est construit avec eux dans un test de `minos-application`
- **THEN** il rend la sélection des bacs à sable, la décision, la commande trouvée ou absente et le niveau de confidentialité de chaque emplacement, `UNKNOWN` compris
