# frontieres-de-modules Specification

## Purpose
Garantit que les gardes d'architecture internes de MINOS (dépendances Maven, sources, packages, cycles de packages, documentation d'architecture courante) refusent ce qui contredit les décisions établies (ADR 0022, 0042, 0044, 0057, 0058), se testent elles-mêmes règle par règle, et que le code de production ne contient aucun cycle de packages.

## Requirements

### Requirement: Chaque règle de frontière du garde Python est couverte par un cas refusé et un cas accepté
Chaque règle de `scripts/architecture/check-module-boundaries.py` (A2 hexagonale, politique de dépendances, dépendances cachées, mise en page, frontières de sources, disposition Java, document généré) SHALL avoir au moins un cas refusé et un cas accepté dans `test_check_module_boundaries.py`. Le test SHALL échouer si une fonction `check_*` du module n'est référencée par aucun test.

#### Scenario: Règle sans test
- **WHEN** une fonction `check_*` est ajoutée au script sans qu'aucun test la référence
- **THEN** `python scripts/architecture/test_check_module_boundaries.py` se termine avec le code 1 et nomme la fonction

#### Scenario: Arête interdite par l'ADR 0042
- **WHEN** une copie des POM réels déclare une dépendance de `minos-cli` vers `minos-storage-local` (ou de `minos-application` vers `minos-provider-scip`)
- **THEN** le garde échoue en nommant le module, la cible et la règle hexagonale violée

#### Scenario: Surface vers la racine de composition à la compilation
- **WHEN** un POM de surface déclare `minos-bootstrap` en portée `compile`
- **THEN** le garde échoue ; la même dépendance en portée `runtime` ou `test` est acceptée

#### Scenario: Retrait d'une dépendance
- **WHEN** la politique de dépendances reçoit un graphe où `minos-runtime-local` déclare `minos-engine` sans `minos-domain`
- **THEN** la règle de politique réussit (la table est un maximum, non une obligation ; la fraîcheur du document généré est une règle distincte)

#### Scenario: Classe d'adaptateur nommée par une source
- **WHEN** une source de production de `minos-cli` importe une classe d'un adaptateur par import simple, statique, joker ou nom qualifié
- **THEN** le garde échoue en nommant le fichier et la classe ; une simple mention en commentaire ou en littéral est acceptée

### Requirement: Une dépendance interne est reconnue par son artefact, non par un nom de groupe écrit en dur
Le garde SHALL dériver le `groupId` interne du POM racine, SHALL traiter comme interne toute dépendance dont l'`artifactId` est celui d'un module du reactor, et SHALL refuser toute telle dépendance dont le `groupId` diffère du `groupId` racine ou contient `${`.

#### Scenario: Groupe non résolu
- **WHEN** un POM déclare `minos-engine` avec `<groupId>${project.groupId}</groupId>`
- **THEN** le garde échoue en nommant le module et l'artefact

#### Scenario: Groupe voisin
- **WHEN** un POM déclare l'artefact `minos-engine` avec le `groupId` `com.minos.fake`
- **THEN** le garde échoue au lieu de l'ignorer comme dépendance tierce

#### Scenario: Groupe de la racine
- **WHEN** les POM déclarent leurs dépendances internes avec le `groupId` du POM racine
- **THEN** le garde les reconnaît toutes comme internes

### Requirement: Les règles de mise en page de build valent aussi dans un profil
Le garde SHALL refuser `sourceDirectory`, `testSourceDirectory` et les `includes`/`excludes` du compilateur dans le `<build>` d'un `<profile>` comme dans le `<build>` racine, et SHALL NOT refuser les `plugins` d'un profil.

#### Scenario: Répertoire de sources dans un profil
- **WHEN** un POM déclare `<sourceDirectory>` dans `profiles/profile/build`
- **THEN** le garde échoue en nommant le module et le profil

#### Scenario: Profils existants
- **WHEN** le garde s'exécute sur le dépôt (profils `audit-*` et `windows-docker-desktop-testcontainers`)
- **THEN** il se termine avec le code 0

### Requirement: Un cycle de packages absent de la table des cycles connus est refusé
Le garde SHALL construire le graphe package → package des sources de production des 14 modules et du plugin `minos-intellij`, calculer ses composantes fortement connexes de taille supérieure à 1, et SHALL échouer pour toute composante absente de `KNOWN_PACKAGE_CYCLES` ou plus grande qu'une entrée. La table SHALL contenir, à l'entrée en vigueur, les quatre cycles actuels (15 packages).

#### Scenario: Nouveau cycle
- **WHEN** deux packages d'un arbre de test s'importent mutuellement et ne figurent pas dans la table
- **THEN** le garde se termine avec le code 1 et nomme les deux packages et une arête témoin de chaque sens

#### Scenario: Cycle connu inchangé
- **WHEN** le dépôt est analysé
- **THEN** le garde se termine avec le code 0 et affiche 4 cycles connus couvrant 15 packages

#### Scenario: Cycle connu étendu
- **WHEN** un troisième package importe l'un des deux packages d'un cycle connu et est importé par l'autre
- **THEN** le garde échoue : la composante est plus grande que son entrée

#### Scenario: Arbre sans cycle
- **WHEN** le graphe est acyclique et la table vide
- **THEN** le garde réussit

### Requirement: Le cliquet des cycles de packages ne se desserre pas
Toute entrée de `KNOWN_PACKAGE_CYCLES` qui ne décrit plus exactement une composante fortement connexe du graphe SHALL faire échouer le garde, de sorte que la table ne puisse que rétrécir par une édition visible.

#### Scenario: Cycle levé mais toujours listé
- **WHEN** un cycle connu disparaît du graphe sans que son entrée soit retirée
- **THEN** le garde échoue en demandant de retirer ou de resserrer l'entrée

#### Scenario: Cycle réduit
- **WHEN** un cycle de trois packages est réduit à deux sans mise à jour de la table
- **THEN** le garde échoue en nommant l'entrée périmée

### Requirement: Le garde de cycles couvre le plugin IntelliJ hors du reactor
Les sources de production de `minos-intellij` SHALL faire partie du graphe de packages analysé, bien que ce module n'appartienne pas au reactor Maven.

#### Scenario: Nouveau cycle dans le plugin
- **WHEN** un package de `minos-intellij` importe un package qui l'importe déjà, hors cycle connu
- **THEN** le garde échoue en nommant les deux packages du plugin

### Requirement: Les listes de dépendances de la documentation d'architecture courante sont confrontées aux POM
Pour chaque titre `### minos-…` de `docs/architecture/arc42/05-vue-blocs.md`, le garde SHALL exiger une ligne à puce « Dépendances » dont l'ensemble des modules `minos-…` cités est égal aux dépendances internes directes du POM correspondant, toutes portées. Le texte « tous les modules » SHALL n'être admis que pour `minos-app`, dont le POM déclare les autres modules.

#### Scenario: Liste fausse
- **WHEN** la section de `minos-cli` cite `minos-storage-local` alors que son POM ne le déclare pas
- **THEN** le garde se termine avec le code 1 en nommant le module, l'écart et la ligne attendue

#### Scenario: Module sans section ou section sans ligne
- **WHEN** un module du reactor n'a pas de section, ou qu'une section n'a pas de ligne « Dépendances »
- **THEN** le garde échoue (jamais un succès par défaut)

#### Scenario: Jetons tiers
- **WHEN** la ligne d'un module cite aussi une bibliothèque tierce avec sa version
- **THEN** seule la partie `minos-…` de la ligne est comparée

#### Scenario: Documentation corrigée
- **WHEN** les listes des 7 modules en écart sont alignées sur les POM
- **THEN** `python scripts/architecture/check-module-boundaries.py` se termine avec le code 0, et `--write-doc` ne produit aucune modification de `docs/architecture/diagrams/module-dependencies.md`

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
