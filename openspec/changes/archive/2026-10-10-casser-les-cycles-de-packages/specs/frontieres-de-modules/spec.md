# Spec Delta

## ADDED Requirements

### Requirement: Le code de production ne contient aucun cycle de packages
Les sources de production des 14 modules du reactor et du plugin `minos-intellij` SHALL ne former aucun cycle entre packages. La table `KNOWN_PACKAGE_CYCLES` SHALL être vide ; la règle A8 SHALL rester active et refuser tout cycle futur.

#### Scenario: Dépôt sans cycle
- **WHEN** `python scripts/architecture/check-module-boundaries.py` s'exécute sur le dépôt
- **THEN** il se termine avec le code 0 et affiche « A8 package cycles: 0 known cycles over 0 packages, none new »

#### Scenario: Un cycle est réintroduit
- **WHEN** une source importe un package qui l'importe déjà, par exemple `com.minos.output` depuis `com.minos.output.json`
- **THEN** le garde se termine avec le code 1 et nomme les deux packages et un fichier témoin de chaque sens

#### Scenario: Cycle levé lot par lot
- **WHEN** l'entrée d'un cycle est retirée de la table avant que ses classes soient déplacées
- **THEN** le garde échoue en nommant les packages du cycle, et réussit une fois les déplacements faits

### Requirement: Les packages de `minos-application` sont séparables
Dans `minos-application`, `ProjectResolver` SHALL se trouver dans `com.minos.application.resolution` et `DeterministicJson` dans `com.minos.output.json`, de sorte que `application`, `application.dynamic`, `application.semantic`, `architecture`, `impact`, `output`, `program.analysis` et `workspace` ne forment plus de composante cyclique.

#### Scenario: Pivot extrait
- **WHEN** on recherche les imports de `com.minos.application.ProjectResolver` et de `com.minos.output.DeterministicJson` dans tout le dépôt
- **THEN** la recherche ne retourne aucune ligne

#### Scenario: Aucun module créé
- **WHEN** le garde de frontières s'exécute
- **THEN** la liste `MODULES` est inchangée (14 modules) et la règle A3 (un package, un module) reste satisfaite

### Requirement: L'incrémental ne dépend plus de l'orchestration en retour
Dans `minos-engine`, les classes d'`incremental` qui utilisent l'orchestration (`IncrementalIndexingCoordinator`, `IncrementalIndexingPlan`, `IncrementalIndexingPlanner`, `IncrementalIndexingResult`, `ProjectFingerprintSnapshotAlignmentService`, `ProjectInvalidationService`) SHALL se trouver dans `com.minos.orchestration`, et `com.minos.incremental` SHALL ne plus importer `com.minos.orchestration`.

#### Scenario: Plus d'arête incremental vers orchestration
- **WHEN** on recherche `import com.minos.orchestration` dans les sources de production de `com.minos.incremental`
- **THEN** la recherche ne retourne aucune ligne

### Requirement: Le plugin IntelliJ n'a pas de cycle entre `protocol`, `service` et `ui`
Dans `minos-intellij`, `MinosRegistryNotice` SHALL se trouver dans `com.minos.intellij.protocol` ; `protocol` SHALL ne dépendre ni de `service` ni de `ui`.

#### Scenario: Protocole indépendant de l'interface
- **WHEN** on recherche `import com.minos.intellij.ui` dans les sources de `com.minos.intellij.protocol`
- **THEN** la recherche ne retourne aucune ligne

### Requirement: Le comportement observable est inchangé à l'octet
Les déplacements SHALL NOT modifier une sortie de la CLI, du MCP ou de l'API Java : les goldens de caractérisation de `minos-app/src/test/resources/characterization/` SHALL rester identiques.

#### Scenario: Goldens identiques
- **WHEN** `mvnw verify` s'exécute sur le dépôt après les déplacements
- **THEN** il réussit et `git diff --exit-code minos-app/src/test/resources/characterization` ne montre aucune différence

### Requirement: Les gates et la couverture suivent les classes déplacées
Aucun script ne SHALL citer l'ancien emplacement d'une classe déplacée, et chaque scope de couverture concerné SHALL viser le nouvel emplacement.

#### Scenario: Scope de couverture de la résolution de projet
- **WHEN** `python scripts/quality/check-jacoco.py` s'exécute après un `verify`
- **THEN** le scope `project-resolution` mesure `com/minos/application/resolution/ProjectResolver`, sans préfixe mort, avec les seuils inchangés (ligne 0,70, branche 0,50)

#### Scenario: Gates littéraux
- **WHEN** `check-post-mne.py`, `check-mnd.py`, `check-mne.py`, `check-polyglot-provider-consistency.py` et `check-current-docs.py` s'exécutent
- **THEN** tous se terminent avec le code 0
