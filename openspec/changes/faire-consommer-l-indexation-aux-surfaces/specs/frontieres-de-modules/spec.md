## ADDED Requirements

### Requirement: Les familles de l'indexation ne sont plus nommées par les surfaces
Les sources de production de `minos-cli`, `minos-api` et `minos-mcp` SHALL NOT nommer de type de `com.minos.orchestration` ni de `com.minos.remote`, ni appeler `indexStateStore()`, `providerRuntimeManager()`, `indexerDescriptors()` ou `compositionRoot()` de `MinosApplication`. La table `ENGINE_TYPES_NAMED_BY_SURFACE` SHALL compter 15 entrées pour la CLI, 14 pour l'API, 4 pour le MCP et 6 pour NEXUS.

#### Scenario: Aucun nom ni accesseur retiré dans les surfaces
- **GIVEN** le dépôt après le changement
- **WHEN** on recherche `com\.minos\.(orchestration|remote)\.` puis `\.(indexStateStore|providerRuntimeManager|indexerDescriptors|compositionRoot)\(\)` dans `minos-cli/src/main`, `minos-api/src/main` et `minos-mcp/src/main`
- **THEN** aucune des deux recherches ne retourne de ligne

#### Scenario: Décomptes du cliquet
- **GIVEN** le dépôt après le changement
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté
- **THEN** il réussit et affiche 15 types pour `minos-cli`, 14 pour `minos-api`, 4 pour `minos-mcp` et 6 pour `minos-nexus`

#### Scenario: Retour d'un type retiré refusé
- **GIVEN** une copie du dépôt où un fichier de `minos-cli` importe `com.minos.remote.RemoteIndexingRuntime`
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté sur cette copie
- **THEN** il échoue en nommant la surface, le fichier et le type

### Requirement: L'indexation distante est un cas d'usage qui refuse avant tout effet
Le flux `remote materialize` et `remote index` SHALL être composé par un cas d'usage de `minos-application` invocable sans la CLI. Sans sandbox qualifiée pour du code non fiable (ADR 0041), `index` SHALL refuser avant toute matérialisation, prise de bail, enregistrement ou épinglage, par un message sans chemin. Tout échec après matérialisation SHALL libérer la source et le bail, et retirer l'enregistrement et l'épinglage d'un projet que l'appel a créé.

#### Scenario: Refus anticipé sans effet de bord
- **GIVEN** un runtime distant dont la sélection de sandbox refuse le code non fiable et un `MINOS_HOME` temporaire
- **WHEN** `./mvnw -B -ntp -pl minos-application -am test "-Dtest=RemoteIndexingUseCase*Test"` exécute l'indexation distante
- **THEN** elle échoue avec l'identifiant du backend et ses codes de refus, sans chemin absolu, le matérialiseur n'est jamais appelé, aucun dossier de bail n'est créé et le registre est inchangé

#### Scenario: Échec après enregistrement
- **GIVEN** un runtime qualifié dont l'exécution distribuée échoue après l'enregistrement d'un nouveau projet
- **WHEN** l'indexation distante est exécutée par le test du cas d'usage
- **THEN** l'échec est propagé, le projet est retiré du registre, la source est désépinglée et libérée une seule fois, et le bail est relâché

#### Scenario: Même refus par la commande
- **GIVEN** la CLI sur un hôte sans sandbox qualifiée
- **WHEN** `./mvnw -B -ntp -pl minos-cli -am test "-Dtest=RemoteIndexCommandTest,LocalRemoteIndexOperations*Test"` est exécuté
- **THEN** les tests réussissent sans attente modifiée : message et code de sortie de `minos remote index` identiques

### Requirement: L'état de reprise est lu sans écriture par l'application
La CLI, l'API Java et le MCP SHALL obtenir l'état d'un run reprenable par une même requête de lecture de `minos-application`. Cette requête SHALL NOT créer, modifier ni verrouiller de fichier sous `MINOS_HOME`. Un identifiant de projet qui n'est pas un UUID SHALL donner une absence de run reprenable, pas une erreur.

#### Scenario: Lecture sans trace
- **GIVEN** un `MINOS_HOME` temporaire contenant un run interrompu, dont on relève l'arborescence et les dates de modification
- **WHEN** le test de la requête de reprise de `minos-application` lit l'état du projet
- **THEN** il rend le run, ses cibles reprenables et sa phase, et l'arborescence relevée après est identique

#### Scenario: Identifiant invalide
- **GIVEN** l'identifiant `pas-un-uuid`
- **WHEN** la requête de reprise est appelée
- **THEN** elle rend une absence de run sans lever d'exception

### Requirement: Les choix applicatifs couvrent les énumérations du moteur
Pour chaque énumération du moteur qu'une surface fournissait en entrée (`IndexingResumePolicy`, `DistributedIndexing.WorkerNetworkPolicy`), `minos-application` SHALL définir un choix aux valeurs de mêmes noms, converti à l'entrée du cas d'usage. Une valeur présente d'un seul côté SHALL faire échouer un test de `minos-application`.

#### Scenario: Valeur ajoutée au moteur
- **GIVEN** une valeur ajoutée à `IndexingResumePolicy` sans pendant applicatif
- **WHEN** `./mvnw -B -ntp -pl minos-application -am test` est exécuté
- **THEN** le test d'exhaustivité échoue en nommant la valeur sans pendant

#### Scenario: Correspondance actuelle
- **GIVEN** le dépôt après le changement
- **WHEN** le même test est exécuté
- **THEN** il réussit pour les deux énumérations

### Requirement: Le premier lot ne change aucune sortie publique
Les sorties de `minos index`, `minos remote`, `minos project`, `minos tools` et `minos doctor`, les réponses MCP et les valeurs rendues par `com.minos.api` SHALL rester identiques à l'octet, codes de sortie compris. Aucune signature de `com.minos.api` ni méthode publique de `MinosApplication` SHALL être retirée ou modifiée, et aucun golden SHALL être régénéré.

#### Scenario: Tests des surfaces inchangés
- **GIVEN** le dépôt après le changement
- **WHEN** `./mvnw -B -ntp -pl minos-cli,minos-api,minos-mcp -am test` est exécuté
- **THEN** il réussit et `git diff --stat develop -- "*golden*"` ne liste aucun fichier

#### Scenario: Capacités triées comme avant
- **GIVEN** un `ProviderDto` construit avec des capacités non triées
- **WHEN** le test de `ProviderPlatformApi` est exécuté
- **THEN** les capacités sont rendues triées, non modifiables, et une clé ou une valeur nulle est refusée comme avant
