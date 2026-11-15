## ADDED Requirements

### Requirement: Les cas d'usage reçoivent leurs collaborateurs et non l'application
`ProjectIndexingUseCase`, `RemoteIndexingUseCase` et `KnowledgeExportUseCase` SHALL être construits par injection de leurs collaborateurs. Aucune source de production de `com.minos.application.indexing`, `com.minos.application.remote` et `com.minos.application.export` SHALL importer un type du package `com.minos.application`, `MinosApplication` et `MinosApplicationComposer` compris. Leur câblage de production SHALL se trouver dans le package `com.minos.application`.

#### Scenario: Cas d'usage sans import du package de la façade
- **GIVEN** le dépôt après le changement
- **WHEN** on recherche `^import com\.minos\.application\.[A-Z]` dans les sources de production de `com.minos.application.indexing`, `com.minos.application.remote` et `com.minos.application.export`
- **THEN** la recherche ne retourne aucune ligne

#### Scenario: Aucun cycle de packages
- **GIVEN** le dépôt après le changement, où `MinosApplication` rend les trois cas d'usage
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté
- **THEN** il se termine avec le code 0 et affiche « A8 package cycles: 0 known cycles over 0 packages, none new »

#### Scenario: Cas d'usage construit sans application
- **GIVEN** un test de `minos-application` qui fournit des doubles des ports de `ProjectIndexingUseCase`
- **WHEN** le cas d'usage est construit et son plan demandé pour un projet enregistré
- **THEN** le plan est rendu sans qu'aucune application ni racine de composition soit ouverte

#### Scenario: Collaborateur manquant refusé
- **GIVEN** des `Dependencies` dont un port est `null`
- **WHEN** le cas d'usage est construit
- **THEN** la construction échoue par `NullPointerException` qui nomme le port

### Requirement: La façade expose les cas d'usage, construits à la demande
`MinosApplication` SHALL exposer l'indexation locale, l'indexation par la voie WSL2, l'indexation distante et l'export de connaissance. Un cas d'usage SHALL être construit à sa première demande : l'ouverture de l'application SHALL NOT créer de marqueur de reprise, de cache de matérialisation ni de moteur distant.

#### Scenario: Surfaces servies par la façade
- **GIVEN** le dépôt après le changement
- **WHEN** on recherche `UseCase.of(` et `UseCase.viaWsl(` dans les sources Java de tous les modules
- **THEN** la recherche ne retourne aucune ligne

#### Scenario: Ouverture sans écriture
- **GIVEN** un `MINOS_HOME` en lecture seule portant un projet indexé
- **WHEN** `./mvnw -B -ntp -pl minos-mcp -am test "-Dtest=MinosMcpWriteProtectedHomeTest" "-Dsurefire.failIfNoSpecifiedTests=false"` est exécuté
- **THEN** le test réussit : aucun cas d'usage n'a écrit à l'ouverture

#### Scenario: Voie WSL2 absente
- **GIVEN** une installation dont la racine de composition n'offre pas la voie WSL2
- **WHEN** l'indexation par la voie WSL2 est demandée à la façade
- **THEN** la façade rend un résultat vide et rien n'est résolu

#### Scenario: Échec de création du moteur distant
- **GIVEN** une racine de composition de test dont le matérialiseur échoue par `IOException`
- **WHEN** l'indexation distante est demandée à la façade
- **THEN** l'`IOException` est propagée à l'appelant et la demande suivante retente la création

### Requirement: La façade ne rend ni type du moteur ni racine de composition
Aucune méthode publique d'instance de `MinosApplication` SHALL rendre un type de `minos-engine` ni `MinosApplicationComposer`. Le garde A11 SHALL échouer pour toute méthode publique de ce genre. La table `MINOS_APPLICATION_ENGINE_ACCESSORS` SHALL être vide.

#### Scenario: Accesseur typé moteur réintroduit
- **GIVEN** une copie du dépôt où `MinosApplication` regagne `public ProjectRegistry projectRegistry()`
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en A11 en nommant `projectRegistry` et `registry.ProjectRegistry`

#### Scenario: Racine de composition réexposée
- **GIVEN** un arbre de test où `MinosApplication` déclare une méthode publique rendant `MinosApplicationComposer`
- **WHEN** `python scripts/architecture/test_check_module_boundaries.py` est exécuté
- **THEN** le cas vérifie que le garde échoue en A11 en nommant la méthode

#### Scenario: Entrée ajoutée à la table vide
- **GIVEN** un arbre de test où la table liste `projectRegistry` alors que la façade ne le déclare plus publiquement
- **WHEN** l'auto-test du garde est exécuté
- **THEN** le cas vérifie que le garde échoue en demandant de retirer l'entrée

### Requirement: Les tests lisent un port de l'application par un accès de test
Les ports que `MinosApplication` ne rend plus publiquement SHALL rester lisibles par les tests des autres modules au moyen d'une classe d'accès publiée dans le `test-jar` de `minos-application` seulement. Aucun jar de production SHALL contenir cette classe.

#### Scenario: Jar de production sans accès de test
- **GIVEN** le jar ombré produit par `./mvnw -B -ntp -pl minos-app -am verify`
- **WHEN** un test de packaging liste ses entrées
- **THEN** la classe d'accès de test n'y figure pas

#### Scenario: Test d'une surface
- **GIVEN** `MinosMcpWriteProtectedHomeTest`, qui prépare des sessions d'exécution dans le magasin d'observations
- **WHEN** il obtient le magasin par l'accès de test
- **THEN** il compile et réussit sans accesseur public sur la façade

#### Scenario: Accès de test hors portée de test refusé
- **GIVEN** une copie des POM réels où un module déclare le `test-jar` de `minos-application` en portée `compile`
- **WHEN** `python scripts/architecture/test_check_module_boundaries.py` est exécuté
- **THEN** le cas vérifie que le garde échoue en nommant le module et la portée

### Requirement: L'injection des cas d'usage ne change aucune sortie publique
Le passage des cas d'usage à l'injection SHALL laisser identiques à l'octet les sorties caractérisées de la CLI, de l'API Java et du MCP, et SHALL conserver le refus de l'indexation distante avant tout effet.

#### Scenario: Goldens inchangés
- **GIVEN** le dépôt après le changement
- **WHEN** `./mvnw -B -ntp -pl minos-app -am verify` puis `git diff --exit-code minos-app/src/test/resources/characterization/` sont exécutés
- **THEN** la commande `git diff` se termine avec le code 0

#### Scenario: Refus de l'indexation distante conservé
- **GIVEN** le dépôt après le changement
- **WHEN** `./mvnw -B -ntp -pl minos-cli -am test "-Dtest=LocalRemoteIndexOperationsRefusalTest" "-Dsurefire.failIfNoSpecifiedTests=false"` est exécuté
- **THEN** le test réussit : le refus intervient avant toute matérialisation, bail, enregistrement ou épingle

## MODIFIED Requirements

### Requirement: Les accesseurs de MinosApplication typés moteur sont tenus par un cliquet
Les accesseurs publics d'instance de `MinosApplication` qui rendent un type de `minos-engine` SHALL être exactement ceux de `MINOS_APPLICATION_ENGINE_ACCESSORS`, chacun avec son type rendu. Un accesseur hors table, un type rendu différent, une entrée sans accesseur ou une surface de production qui atteint la racine de composition SHALL faire échouer A11. `gitIntelligence`, `providerCatalog`, `scipArtifactImporter` et `hostedControlPlaneService` SHALL ne plus être publics.

#### Scenario: Un nouvel accesseur public refusé
- **GIVEN** une copie du dépôt où `MinosApplication` gagne `public GitIntelligence git() { return gitIntelligence; }`
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté sur cette copie
- **THEN** le garde échoue en A11 en nommant `git` et `git.GitIntelligence`

#### Scenario: Une surface qui atteint la racine de composition
- **GIVEN** un arbre de test où une classe de production de `minos-cli` appelle `app.compositionRoot().gitIntelligence()` ou `MinosApplicationComposers.resolve()`
- **WHEN** `python -m unittest scripts/architecture/test_check_module_boundaries.py` est exécuté
- **THEN** le cas de test vérifie que le garde échoue en A11 en nommant le fichier et la racine de composition

#### Scenario: Un accesseur retiré sans mettre la table à jour
- **GIVEN** un arbre de test où la table liste un accesseur que `MinosApplication` ne déclare plus publiquement
- **WHEN** `python -m unittest scripts/architecture/test_check_module_boundaries.py` est exécuté
- **THEN** le cas de test vérifie que le garde échoue en demandant de retirer l'entrée

#### Scenario: Table à jour au HEAD
- **GIVEN** le dépôt
- **WHEN** le garde est exécuté
- **THEN** il réussit et annonce 0 accesseur public typé moteur, et la CLI ne liste plus `registry.ProjectPathMappings` dans aucune table A11
