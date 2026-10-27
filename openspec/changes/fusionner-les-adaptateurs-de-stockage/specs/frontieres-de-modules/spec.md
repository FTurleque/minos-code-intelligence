## ADDED Requirements

### Requirement: Les adaptateurs de stockage forment un seul module
Le reactor SHALL compter un seul module de stockage, `minos-storage`, qui contient `com.minos.storage.local` et `com.minos.storage.postgresql` sans classe dupliquée ni package déclaré ailleurs. `ALLOWED_DEPENDENCIES` et `ModuleArchitectureTest.ALLOWED` SHALL lui donner `minos-domain` et `minos-engine` pour seules cibles. Le garde SHALL refuser un module `minos-storage-local` ou `minos-storage-postgresql` réapparu.

#### Scenario: Reactor fusionné
- **GIVEN** le dépôt après la fusion
- **WHEN** `python scripts/architecture/check-module-boundaries.py` s'exécute
- **THEN** il réussit, avec 13 modules, et les tables A2 et A17 nomment `minos-storage` sans aucun des deux anciens nœuds

#### Scenario: Ancien module réintroduit
- **GIVEN** une copie du dépôt dont le reactor déclare de nouveau `minos-storage-postgresql`
- **WHEN** le garde s'exécute
- **THEN** il échoue en nommant le module absent de `MODULES`

### Requirement: La composition et le provider SCIP ne nomment pas le backend PostgreSQL
Aucune source de production de `minos-bootstrap` ni de `minos-provider-scip` SHALL nommer un type de `com.minos.storage.postgresql` ou de `org.postgresql`, par import simple, statique, joker ou nom qualifié. Le backend PostgreSQL SHALL n'être enregistré que par `ServiceLoader`. Une mention en commentaire ou en littéral reste acceptée.

#### Scenario: Import du backend dans la composition
- **GIVEN** une copie du dépôt où `DefaultMinosApplicationComposer` importe `com.minos.storage.postgresql.PostgresStorageBackend`
- **WHEN** le garde s'exécute
- **THEN** il échoue en nommant le fichier, le type et la règle

#### Scenario: Provider SCIP qui nomme le pilote
- **GIVEN** une copie où une source de `minos-provider-scip` nomme `org.postgresql.Driver` par son nom qualifié
- **WHEN** le garde s'exécute
- **THEN** il échoue ; la même chaîne dans un commentaire est acceptée

### Requirement: Le mode local ne charge aucune classe PostgreSQL
Une application composée avec le backend `local` SHALL fonctionner sans serveur PostgreSQL ni Docker, et SHALL ne charger aucune classe `org.postgresql.*` ni `com.minos.storage.postgresql.*`. Un test de `minos-bootstrap` SHALL le vérifier sur les deux systèmes de la CI.

#### Scenario: Composition locale
- **GIVEN** un `MINOS_HOME` temporaire configuré sur le backend `local`
- **WHEN** le test compose l'application puis interroge le chargeur de classes
- **THEN** aucune classe des deux espaces de noms n'est chargée et le test réussit sans Docker

#### Scenario: Chargement forcé
- **GIVEN** le même test, avec une variante qui charge `PostgresStorageBackend` avant la vérification
- **WHEN** la variante s'exécute
- **THEN** la vérification échoue en nommant la classe chargée

### Requirement: Les backends de stockage ne se nomment l'un l'autre que dans une liste qui rétrécit
Le garde SHALL tenir la table `BACKEND_TYPES_NAMED_ACROSS_PACKAGES` des types de `com.minos.storage.local` nommés par la production de `com.minos.storage.postgresql`, avec leur nombre de fichiers. Il SHALL refuser un type ou un fichier en plus, une entrée périmée, et toute référence de `com.minos.storage.local` vers `com.minos.storage.postgresql`.

#### Scenario: Nouveau type local nommé par PostgreSQL
- **GIVEN** une copie où une source de `com.minos.storage.postgresql` importe `com.minos.storage.local.store.FileSymbolSnapshotStore`
- **WHEN** le garde s'exécute
- **THEN** il échoue en nommant le fichier et le type absent de la table

#### Scenario: Sens interdit
- **GIVEN** une copie où une source de `com.minos.storage.local` nomme `com.minos.storage.postgresql.support.PostgresJdbcUrlPolicy`
- **WHEN** le garde s'exécute
- **THEN** il échoue, quelle que soit la table

#### Scenario: Entrée devenue inutile
- **GIVEN** une copie où plus aucune source de `com.minos.storage.postgresql` ne nomme `store.SnapshotIntegrityService`
- **WHEN** le garde s'exécute
- **THEN** il échoue en demandant de retirer l'entrée de la table

### Requirement: Le garde refuse l'ajout de sources par build-helper
`check_pom_layout` SHALL refuser `org.codehaus.mojo:build-helper-maven-plugin` dans tout `<build>` d'un POM du reactor, à la racine comme dans un profil. Les autres plugins déclarés dans un profil SHALL rester acceptés.

#### Scenario: Sources d'un autre module ajoutées
- **GIVEN** une copie où un POM déclare `build-helper-maven-plugin` avec `add-source` vers `../minos-storage/src/main/java`, à la racine ou dans un profil
- **WHEN** le garde s'exécute
- **THEN** il échoue en nommant le POM et le plugin

#### Scenario: Plugin ordinaire dans un profil
- **GIVEN** un profil qui configure `maven-surefire-plugin`
- **WHEN** le garde s'exécute
- **THEN** la règle de mise en page réussit

## MODIFIED Requirements

### Requirement: Chaque règle de frontière du garde Python est couverte par un cas refusé et un cas accepté
Chaque règle de `scripts/architecture/check-module-boundaries.py` (A2 hexagonale, politique de dépendances, dépendances cachées, mise en page, frontières de sources, disposition Java, document généré) SHALL avoir au moins un cas refusé et un cas accepté dans `test_check_module_boundaries.py`. Le test SHALL échouer si une fonction `check_*` du module n'est référencée par aucun test.

#### Scenario: Règle sans test
- **WHEN** une fonction `check_*` est ajoutée au script sans qu'aucun test la référence
- **THEN** `python scripts/architecture/test_check_module_boundaries.py` se termine avec le code 1 et nomme la fonction

#### Scenario: Arête interdite par l'ADR 0042
- **WHEN** une copie des POM réels déclare une dépendance de `minos-cli` vers `minos-storage` (ou de `minos-application` vers `minos-provider-scip`)
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

### Requirement: Les listes de dépendances de la documentation d'architecture courante sont confrontées aux POM
Pour chaque titre `### minos-…` de `docs/architecture/arc42/05-vue-blocs.md`, le garde SHALL exiger une ligne à puce « Dépendances » dont l'ensemble des modules `minos-…` cités est égal aux dépendances internes directes du POM correspondant, toutes portées. Le texte « tous les modules » SHALL n'être admis que pour `minos-app`, dont le POM déclare les autres modules.

#### Scenario: Liste fausse
- **WHEN** la section de `minos-cli` cite `minos-storage` alors que son POM ne le déclare pas
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

### Requirement: Le module PostgreSQL est rangé en sous-packages sans cycle
Le backend PostgreSQL (`com.minos.storage.postgresql`, dans `minos-storage`) SHALL répartir ses classes entre `support`, `snapshot`, `project` et `semantic`, sa racine ne gardant que le backend et son fournisseur. Les dépendances SHALL aller vers `support` et de la racine vers les autres, sans cycle. Le nom du fournisseur déclaré dans `META-INF/services` SHALL rester inchangé.

#### Scenario: Aucun cycle entre les sous-packages
- **GIVEN** le module rangé
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté
- **THEN** il réussit, la table des cycles connus restant vide

#### Scenario: Le fournisseur reste découvert par son nom
- **GIVEN** le jar ombré construit
- **WHEN** `ShadedJarCompositionRootIT` est exécuté
- **THEN** il trouve `com.minos.storage.postgresql.PostgresStorageBackendProvider`

#### Scenario: Une classe de support ne dépend pas d'un store
- **GIVEN** une classe de `support` qui importerait une classe de `snapshot`, `project` ou `semantic`
- **WHEN** `check-module-boundaries.py` est exécuté
- **THEN** il échoue en nommant le cycle de packages

### Requirement: Les types d'un adaptateur nommés par un autre adaptateur le sont par liste fermée
Le garde de frontières SHALL tenir, par couple d'adaptateurs, les types de l'un que l'autre nomme par import ou nom qualifié, chacun avec le nombre de fichiers de production qui le nomment ; dans `minos-storage`, les deux backends suivent la même règle par package. Un type hors liste, un compteur différent du nombre de fichiers observé ou une entrée devenue inutile SHALL faire échouer le garde.

#### Scenario: Nouveau type du backend local nommé par PostgreSQL
- **GIVEN** une copie du dépôt où `com.minos.storage.postgresql` nomme un type de `com.minos.storage.local` absent de la liste
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en nommant les deux packages et le type

#### Scenario: Usage en nom qualifié sans import
- **GIVEN** une copie du dépôt où un type hors liste est utilisé par son nom qualifié complet
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue comme pour un import

#### Scenario: Nouveau fichier du provider SCIP qui nomme un type déjà listé
- **GIVEN** une copie du dépôt où un nouveau fichier de `minos-provider-scip` importe `runtime.local.process.CommandLocator`, déjà listé
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en nommant le couple, le type, le compteur listé et le compteur observé
