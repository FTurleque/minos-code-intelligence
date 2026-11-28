# frontieres-de-modules Specification

## Purpose
Garantit que les gardes d'architecture internes de MINOS (dépendances Maven, sources, packages, cycles de packages, documentation d'architecture courante) refusent ce qui contredit les décisions établies (ADR 0022, 0042, 0044, 0057, 0058), se testent elles-mêmes règle par règle, et que le code de production ne contient aucun cycle de packages.

## Requirements

### Requirement: Chaque règle de frontière du garde Python est couverte par un cas refusé et un cas accepté
Chaque règle du garde `scripts/architecture/check-module-boundaries.py` et de ses modules de règles `module_boundary_*.py` (A2 hexagonale, politique de dépendances, dépendances cachées, mise en page, frontières de sources, disposition Java, document généré, A3 et A7 à A20) SHALL avoir au moins un cas refusé et un cas accepté dans les auto-tests `test_check_module_boundaries*.py`. L'auto-test SHALL échouer si une fonction `check_*` n'est référencée par aucun test.

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
- **WHEN** le garde s'exécute sur le dépôt (profils `audit-*` du POM racine)
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
Dans `minos-engine`, `com.minos.incremental` SHALL ne pas importer `com.minos.orchestration`. Les classes de planification incrémentale qui utilisent l'orchestration (`IncrementalIndexingPlan`, `IncrementalIndexingPlanner`, `ProjectInvalidationService`) SHALL se trouver dans `com.minos.orchestration`. Une classe retirée du produit SHALL NOT être exigée par cette règle.

#### Scenario: Plus d'arête incremental vers orchestration
- **GIVEN** les sources de production de `minos-engine`
- **WHEN** on recherche `import com.minos.orchestration` dans les sources de `com.minos.incremental`
- **THEN** la recherche ne retourne aucune ligne

#### Scenario: Classe replacée dans l'incrémental
- **GIVEN** une copie du dépôt où `IncrementalIndexingPlanner` est replacé dans `com.minos.incremental`
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté sur cette copie
- **THEN** le garde échoue en nommant le cycle entre `incremental` et `orchestration`

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

### Requirement: Le module CLI est rangé en sous-packages sans cycle
`minos-cli` SHALL répartir ses classes entre `command`, `operations` et `support`, sa racine ne gardant que les points d'entrée et le SPI `McpLaunchRoute`, dont les noms qualifiés SHALL rester inchangés. Les dépendances SHALL aller de la racine vers les autres, de `command` vers `operations` et `support`, et d'`operations` vers `support`, sans cycle.

#### Scenario: Aucun cycle entre les sous-packages
- **GIVEN** le module rangé
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté
- **THEN** il réussit, la table des cycles connus restant vide

#### Scenario: Le point d'entrée garde son nom
- **GIVEN** le jar ombré construit
- **WHEN** `ShadedJarMcpEntryPointIT` est exécuté
- **THEN** il trouve `com.minos.cli.MinosLauncher` et le SPI `com.minos.cli.McpLaunchRoute`

#### Scenario: Une classe de support ne dépend pas d'une commande
- **GIVEN** une classe de `support` qui importerait une classe de `command`
- **WHEN** `check-module-boundaries.py` est exécuté
- **THEN** il échoue en nommant le cycle de packages

### Requirement: Les types de confinement de l'exécution locale ne sont nommés que par liste fermée
Hors de `minos-runtime-local`, les sources de production et de test SHALL ne nommer, parmi les types de ses packages `process`, `sandbox` et `workspace`, que ceux d'une liste fermée tenue par `check-module-boundaries.py`. L'import par joker d'un de ces packages et l'usage des membres réservés SHALL être refusés. La liste SHALL être un cliquet : une entrée sans fichier ou sans utilisateur échoue.

#### Scenario: Un type exporté est accepté
- **GIVEN** un module qui importe `com.minos.runtime.local.process.CommandLocator`
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté
- **THEN** il réussit

#### Scenario: Une primitive non exportée est refusée
- **GIVEN** un module, ou l'un de ses tests, qui importe `com.minos.runtime.local.process.ProviderProcessEnvironment`
- **WHEN** `check-module-boundaries.py` est exécuté
- **THEN** il échoue en nommant le fichier et le type

#### Scenario: Une entrée devenue inutile est refusée
- **GIVEN** un type de la liste qu'aucun autre module ne nomme plus
- **WHEN** `check-module-boundaries.py` est exécuté
- **THEN** il échoue en demandant de retirer l'entrée

### Requirement: Le module d'exécution locale est rangé en sous-packages sans cycle
`minos-runtime-local` SHALL répartir ses classes entre `process`, `sandbox`, `workspace` et `distributed`, sa racine ne gardant que l'orchestration. Les dépendances SHALL aller de la racine vers les autres, de `sandbox` vers `process` et `workspace`, et de `process` vers `workspace`, sans cycle. Les ressources empaquetées SHALL rester chargées par chemin absolu.

#### Scenario: Aucun cycle entre les sous-packages
- **GIVEN** le module rangé
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté
- **THEN** il réussit, la table des cycles connus restant vide

#### Scenario: Le fournisseur d'identité de chemin reste découvert
- **GIVEN** le module rangé
- **WHEN** les tests de `minos-runtime-local` sont exécutés sous Windows
- **THEN** `ServiceLoader` trouve le fournisseur déclaré dans `META-INF/services` et son script se charge

#### Scenario: Un espace de travail ne dépend pas du bac à sable
- **GIVEN** une classe de `workspace` qui importerait une classe de `sandbox` ou de `process`
- **WHEN** `check-module-boundaries.py` est exécuté
- **THEN** il échoue en nommant le cycle de packages

### Requirement: Les surfaces n'assemblent pas le cycle d'indexation
Aucune surface (`minos-cli`, `minos-api`, `minos-mcp`) ni aucun module de contrat (`minos-nexus`) SHALL NOT construire `IndexingLifecycleService` ni enchaîner elle-même découverte, négociation, planification et exécution. Ces étapes SHALL être assemblées dans `minos-application`, qui SHALL NOT nommer de classe d'adaptateur pour le faire.

#### Scenario: Construction du cycle hors des surfaces
- **GIVEN** le dépôt après le changement
- **WHEN** on recherche `new IndexingLifecycleService` dans les sources de production de ces quatre modules
- **THEN** la recherche ne retourne aucune ligne

#### Scenario: Cas d'usage sans adaptateur
- **GIVEN** le cas d'usage d'indexation de `minos-application`
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté
- **THEN** le garde réussit et ne signale aucun import d'adaptateur depuis `minos-application`

### Requirement: Une classe publique de production est référencée par la production ou déclarée
Tout type public de premier niveau des sources de production du reactor SHALL être nommé par un autre fichier de production, ou par un fichier `META-INF/services` de production, ou déclarer un point d'entrée `main`, ou figurer dans la liste nominative du garde avec une raison écrite. Le garde de frontières SHALL échouer sinon, en nommant le type.

#### Scenario: Classe sans appelant ajoutée
- **GIVEN** une copie du dépôt où une classe publique de `src/main` n'est nommée par aucun autre fichier de production ni inscrite sur la liste
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté sur cette copie
- **THEN** le garde échoue en nommant la classe et son module

#### Scenario: Classe nommée seulement par des tests
- **GIVEN** une copie du dépôt où une classe publique de `src/main` n'est nommée que par des fichiers de `src/test`
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue : une référence de test ne compte pas

#### Scenario: Implémentation découverte par service
- **GIVEN** une classe publique nommée seulement dans un fichier `META-INF/services` de production
- **WHEN** le garde est exécuté
- **THEN** la classe est acceptée sans figurer sur la liste

#### Scenario: Dépôt réel
- **GIVEN** le dépôt après le changement
- **WHEN** le garde est exécuté
- **THEN** il réussit et affiche le nombre de types inscrits sur la liste

### Requirement: La liste des classes sans appelant est un cliquet justifié
Chaque entrée de la liste nominative SHALL nommer un seul type et porter une raison non vide. Une entrée dont le type n'existe plus, ou est désormais nommé par la production, SHALL faire échouer le garde, de sorte que la liste ne peut que rétrécir.

#### Scenario: Entrée périmée
- **GIVEN** une liste qui nomme une classe supprimée
- **WHEN** le garde est exécuté
- **THEN** il échoue en nommant l'entrée périmée

#### Scenario: Entrée devenue inutile
- **GIVEN** une liste qui nomme une classe qu'un fichier de production référence désormais
- **WHEN** le garde est exécuté
- **THEN** il échoue en demandant de retirer l'entrée

#### Scenario: Entrée sans raison
- **GIVEN** une liste dont une entrée a une raison vide
- **WHEN** le garde est exécuté
- **THEN** il échoue en nommant l'entrée

### Requirement: Les fournisseurs optionnels sont découverts par le chargeur de MINOS
La découverte du backend de stockage optionnel et celle du fournisseur d'identité de chemin SHALL utiliser le chargeur de classes qui a chargé MINOS, et SHALL NOT dépendre du chargeur de contexte du thread. Un fournisseur absent SHALL produire le refus existant, jamais un mode dégradé.

#### Scenario: Chargeur de contexte vide, backend PostgreSQL
- **GIVEN** un thread dont le chargeur de contexte ne voit aucun service, et le fournisseur PostgreSQL sur le chemin de classes de MINOS
- **WHEN** le backend `postgresql` est sélectionné
- **THEN** le fournisseur PostgreSQL est trouvé

#### Scenario: Chargeur de contexte vide, identité de chemin
- **GIVEN** un thread dont le chargeur de contexte ne voit aucun service, sous Windows
- **WHEN** l'identité physique d'une racine de projet est capturée
- **THEN** l'identité est rendue par le fournisseur enregistré

#### Scenario: Fournisseur absent
- **GIVEN** un chargeur de MINOS sans fournisseur pour le backend demandé
- **WHEN** ce backend est sélectionné
- **THEN** l'ouverture échoue en disant que le fournisseur n'est pas disponible, et aucun autre backend n'est ouvert à sa place

### Requirement: Des fournisseurs en double sont refusés
Deux fournisseurs de stockage de l'identifiant demandé, comparé sans casse avec `Locale.ROOT`, SHALL faire échouer l'ouverture avec un message qui nomme leurs classes. Plus d'un fournisseur d'identité de chemin SHALL rendre l'identité indisponible, donc le lancement refusé. Aucun fournisseur SHALL NOT être choisi par ordre de découverte.

#### Scenario: Deux fournisseurs de stockage de même identifiant
- **GIVEN** un chargeur qui expose deux fournisseurs d'identifiant `postgresql`
- **WHEN** le backend `postgresql` est sélectionné
- **THEN** l'ouverture échoue, le message nomme les deux classes et ne contient aucun chemin absolu

#### Scenario: Doublon sans effet sur le backend local
- **GIVEN** le même chargeur à deux fournisseurs `postgresql`
- **WHEN** le backend `local` est sélectionné
- **THEN** le stockage local est ouvert

#### Scenario: Deux fournisseurs d'identité de chemin
- **GIVEN** un chargeur qui expose deux fournisseurs d'identité de chemin, sur un système sans identité de fichier native
- **WHEN** une autorisation de chemin d'exécution est capturée
- **THEN** aucune autorisation n'est rendue et le lancement d'un provider local est refusé

### Requirement: Aucune découverte de service de production n'omet le chargeur
Tout appel `ServiceLoader.load` des sources de production du reactor SHALL passer un chargeur de classes explicite. Le garde de frontières SHALL échouer sur un appel à un seul argument, en nommant le fichier et la ligne. Ni la spécification ni le dossier de la capacité n'écrivent le nombre de découvertes : il se lit dans la sortie du garde.

#### Scenario: Appel sans chargeur
- **GIVEN** une copie du dépôt où une classe de production appelle `ServiceLoader.load(X.class)`
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté sur cette copie
- **THEN** le garde échoue en nommant le fichier et la ligne

#### Scenario: Dépôt réel
- **GIVEN** le dépôt après le changement
- **WHEN** le garde est exécuté
- **THEN** il réussit et sa ligne `A16 service discovery` annonce que chaque appel `ServiceLoader.load` de production passe un chargeur explicite
- **AND** le compte de cette ligne est celui qu'affirme `test_the_real_tree_passes_a_loader_at_every_site` dans `scripts/architecture/test_check_module_boundaries_adapters.py`

### Requirement: Les types du moteur nommés par une surface le sont par liste fermée
Le garde de frontières SHALL tenir, pour chaque surface (`minos-cli`, `minos-api`, `minos-mcp`) et chaque module de contrat (`minos-nexus`), les types de `minos-engine` qu'elle utilise, chacun avec le nombre de fichiers de production qui l'utilisent. Un type hors liste SHALL faire échouer le garde, ainsi qu'un compteur différent du nombre de fichiers observé, une entrée inutilisée ou une entrée désignant un type inexistant.

#### Scenario: Nouveau type du moteur importé par la CLI
- **GIVEN** une copie du dépôt où un fichier de `minos-cli` importe un type de `minos-engine` absent de la liste
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté sur cette copie
- **THEN** le garde échoue en nommant la surface, le fichier et le type

#### Scenario: Nouveau fichier qui nomme un type déjà listé
- **GIVEN** une copie du dépôt où un nouveau fichier de `minos-cli` importe `io.PrivateLocalStorage`, déjà listé pour un fichier
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en nommant le type, le compteur listé, le compteur observé et les fichiers qui l'utilisent

#### Scenario: Compteur devenu trop grand
- **GIVEN** une copie du dépôt où l'un des fichiers qui nomment un type listé ne le nomme plus
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en donnant le compteur à écrire

#### Scenario: Entrée devenue inutile
- **GIVEN** une copie du dépôt où le dernier usage d'un type listé a été retiré sans retirer l'entrée
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en nommant l'entrée à retirer

#### Scenario: Dépôt réel
- **GIVEN** le dépôt
- **WHEN** le garde est exécuté
- **THEN** il réussit et affiche, par surface et par module de contrat, le nombre de types listés et de fichiers qui les utilisent

### Requirement: Le lancement de processus hors du runtime local est tenu par liste fermée
Dans `minos-engine`, `minos-application` et `minos-domain`, une classe qui lance un processus SHALL figurer dans une liste fermée du garde avec son nombre de sites (`new ProcessBuilder`, `ProcessBuilder::new`, `ProcessBuilder.startPipeline`, `exec` sur `Runtime.getRuntime()` ou sur un nom de type `Runtime` ou affecté de `Runtime.getRuntime()`). Sur le dépôt, la liste SHALL être vide. Une classe hors liste, un nombre de sites différent ou une entrée sans lancement SHALL faire échouer le garde.

#### Scenario: Second site de lancement dans le moteur
- **GIVEN** une copie du dépôt où une classe de `minos-engine` hors liste instancie `ProcessBuilder`
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en nommant la classe

#### Scenario: Troisième appel dans la classe listée
- **GIVEN** un arbre de test où une classe listée avec 2 sites construit un troisième `ProcessBuilder`
- **WHEN** le garde est exécuté sur cet arbre
- **THEN** il échoue en nommant la classe, le nombre de sites listé et le nombre observé

#### Scenario: Site retiré sans baisser le compteur
- **GIVEN** un arbre de test où une classe listée avec 2 sites n'en a plus qu'un
- **WHEN** le garde est exécuté sur cet arbre
- **THEN** il échoue en donnant le nombre de sites à écrire

#### Scenario: Site existant
- **GIVEN** le dépôt, où aucune classe du domaine, du moteur ni de l'application ne lance de processus
- **WHEN** le garde est exécuté
- **THEN** il réussit et affiche 0 classe et 0 site de lancement

#### Scenario: Retour d'icacls dans PrivateLocalStorage
- **GIVEN** une copie du dépôt où `PrivateLocalStorage` construit un `ProcessBuilder` pour `icacls`
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en nommant la classe

#### Scenario: Runtime tenu par une variable
- **GIVEN** un arbre de test où une classe du moteur hors liste écrit `Runtime r = Runtime.getRuntime(); r.exec("x")`, ou appelle `exec` sur un champ ou un paramètre `Runtime`, ou référence `Runtime.getRuntime()::exec`
- **WHEN** `python scripts/architecture/test_check_module_boundaries.py ProcessLauncherRuleTest` est exécuté
- **THEN** le garde échoue en nommant la classe, et accepte la même classe listée avec 1 site

#### Scenario: Requête sur le Runtime sans lancement
- **GIVEN** un arbre de test où une classe du moteur appelle `Runtime.getRuntime().availableProcessors()` et `maxMemory()` sur une variable `Runtime`
- **WHEN** le garde est exécuté sur cet arbre
- **THEN** il réussit et ne compte aucun site

### Requirement: Les types d'un adaptateur nommés par un autre adaptateur le sont par liste fermée
Le garde de frontières SHALL tenir, par couple d'adaptateurs, les types de l'un que l'autre nomme par import ou nom qualifié, chacun avec le nombre de fichiers de production qui le nomment, et une raison écrite par couple. Un type hors liste, un compteur différent du nombre de fichiers observé, une entrée devenue inutile, un couple sans raison ou une raison sans couple SHALL faire échouer le garde.

#### Scenario: Nouveau type du backend local nommé par PostgreSQL
- **GIVEN** une copie du dépôt où `com.minos.storage.postgresql` nomme un type de `com.minos.storage.local`
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue par la règle des packages du stockage, sans table qui l'admette, car les deux backends sont dans le même module

#### Scenario: Usage en nom qualifié sans import
- **GIVEN** une copie du dépôt où un type hors liste est utilisé par son nom qualifié complet
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue comme pour un import

#### Scenario: Nouveau fichier du provider SCIP qui nomme un type déjà listé
- **GIVEN** une copie du dépôt où un nouveau fichier de `minos-provider-scip` importe `runtime.local.process.CommandLocator`, déjà listé
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en nommant le couple, le type, le compteur listé et le compteur observé

#### Scenario: Couple listé sans raison
- **GIVEN** une table où le couple `minos-provider-scip` → `minos-runtime-local` n'a pas d'entrée dans `ADAPTER_COUPLING_REASONS`, ou une raison vide
- **WHEN** le garde est exécuté
- **THEN** il échoue en nommant le couple et en demandant sa raison

#### Scenario: Raison d'un couple disparu
- **GIVEN** une raison écrite pour un couple d'adaptateurs absent de `ADAPTER_TYPES_NAMED_BY_ADAPTER`
- **WHEN** le garde est exécuté
- **THEN** il échoue en demandant de retirer la raison

### Requirement: Un module ne crée pas de nouvelle racine de packages
Le garde de frontières SHALL tenir, par module, la liste des racines de packages `com.minos.<nom>` qu'il possède. Un package dont la racine n'est pas dans la liste de son module SHALL faire échouer le garde.

#### Scenario: Nouvelle racine dans le moteur
- **GIVEN** une copie du dépôt où `minos-engine` contient un package `com.minos.nouveau`
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en nommant le module et la racine

#### Scenario: Sous-package d'une racine existante
- **GIVEN** une copie du dépôt où un module ajoute un sous-package sous une de ses racines listées
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il réussit

### Requirement: Les arêtes d'exécution par service figurent dans le document de dépendances
Le document de dépendances généré SHALL lister les arêtes d'exécution par service, où un module charge l'implémentation fournie par un autre. Son contrôle SHALL échouer quand une telle arête existe dans les sources et manque au document versionné.

#### Scenario: Arête de la CLI vers l'assemblage
- **GIVEN** le dépôt, où `minos-app` fournit le service de route MCP déclaré par `minos-cli`
- **WHEN** le document est généré
- **THEN** il contient l'arête d'exécution de `minos-cli` vers `minos-app`

#### Scenario: Document sans l'arête
- **GIVEN** une copie du dépôt où cette ligne est retirée du document versionné
- **WHEN** le contrôle du document est exécuté sur cette copie
- **THEN** il échoue en nommant l'arête manquante

### Requirement: La classe d'index mémoire n'est nommée que par le cœur
Le garde `check-module-boundaries.py` SHALL refuser toute classe de production hors du package `com.minos.store` de `minos-engine` qui nomme `InMemoryCodeKnowledgeStore` (import simple, statique ou du package entier, nom qualifié, type imbriqué), et toute classe hors de ce package et d'une liste fermée qui nomme `SnapshotQueryView` ou appelle `loadActiveQueryView(` ou `queryStore()`. La liste SHALL ne contenir que les deux stores de snapshots et l'analyse d'impact.

#### Scenario: Cas d'usage qui nomme la classe mémoire
- **GIVEN** une copie temporaire du dépôt où une classe de `minos-application` importe `com.minos.store.InMemoryCodeKnowledgeStore`
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté sur cette copie
- **THEN** il sort en code 1 en nommant la classe et le type

#### Scenario: Import du package entier du cœur
- **GIVEN** une copie temporaire où une classe de `minos-application` importe `com.minos.store.*` et déclare une variable de type `InMemoryCodeKnowledgeStore`
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il sort en code 1 en nommant la classe

#### Scenario: Ancienne vue nommée hors de la liste
- **GIVEN** une copie temporaire où une classe de `minos-application` nomme `SnapshotQueryView`
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il sort en code 1 en nommant la classe

#### Scenario: Index mémoire atteint par chaînage, sans aucun nom
- **GIVEN** une copie temporaire où une classe de `minos-application` écrit `var store = snapshots.loadActiveQueryView(id).orElseThrow().queryStore();` sans importer de type de `com.minos.store`
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il sort en code 1 en nommant la classe et l'appel

#### Scenario: Liste fermée qui nomme une classe absente
- **GIVEN** une copie temporaire où l'une des classes de la liste fermée n'existe plus ou n'utilise plus l'ancien contrat
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il sort en code 1 en demandant de retirer l'entrée

#### Scenario: Dépôt conforme
- **GIVEN** le dépôt après ce changement
- **WHEN** le garde et `python scripts/architecture/test_check_module_boundaries.py` sont exécutés
- **THEN** les deux sortent en code 0

### Requirement: Les cas d'usage de lecture ne reçoivent pas le port d'écriture
Le garde `check-module-boundaries.py` SHALL refuser toute classe de production de `minos-application` qui nomme le port d'écriture `CodeKnowledgeStore` ; un cas d'usage de lecture reçoit `CodeKnowledgeReader`. Les adaptateurs d'ingestion restent libres de le nommer.

#### Scenario: Cas d'usage qui reçoit le port d'écriture
- **GIVEN** une copie temporaire où une classe de `minos-application` déclare un champ de type `CodeKnowledgeStore`
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté sur cette copie
- **THEN** il sort en code 1 en nommant la classe et le port

### Requirement: Les familles de l'indexation ne sont plus nommées par les surfaces
Les sources de production de `minos-cli`, `minos-api` et `minos-mcp` SHALL NOT nommer de type de `com.minos.orchestration` ni de `com.minos.remote`, ni appeler `indexStateStore()`, `providerRuntimeManager()`, `indexerDescriptors()` ou `compositionRoot()` de `MinosApplication`. La table `ENGINE_TYPES_NAMED_BY_SURFACE` SHALL compter 15 entrées pour la CLI, 14 pour l'API, 4 pour le MCP et 6 pour NEXUS.

#### Scenario: Aucun nom ni accesseur retiré dans les surfaces
- **GIVEN** le dépôt après le changement
- **WHEN** on recherche `com\.minos\.(orchestration|remote)\.` puis `\.(indexStateStore|providerRuntimeManager|indexerDescriptors|compositionRoot)\(\)` dans `minos-cli/src/main`, `minos-api/src/main` et `minos-mcp/src/main`
- **THEN** aucune des deux recherches ne retourne de ligne

#### Scenario: Décomptes du cliquet
- **GIVEN** le dépôt après le changement
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté
- **THEN** il réussit et affiche `minos-api=14, minos-cli=15, minos-mcp=4, minos-nexus=6`

#### Scenario: Retour d'un type retiré refusé
- **GIVEN** un arbre temporaire de l'auto-test du garde, muni de la liste réelle de `minos-cli`, où une classe de production de la CLI importe `com.minos.remote.RemoteIndexingRuntime`
- **WHEN** `python scripts/architecture/test_check_module_boundaries.py` est exécuté
- **THEN** le cas attend et obtient l'échec d'A11 nommant `minos-cli`, le fichier et le type

### Requirement: L'indexation distante est un cas d'usage qui refuse avant tout effet
Le flux `remote materialize` et `remote index` SHALL être composé par un cas d'usage de `minos-application` invocable sans la CLI. Sans sandbox qualifiée pour du code non fiable (ADR 0041), `index` SHALL refuser avant toute matérialisation, prise de bail, enregistrement ou épinglage, par un message sans chemin. Tout échec après matérialisation SHALL libérer la source et le bail, et retirer l'enregistrement et l'épinglage d'un projet que l'appel a créé.

#### Scenario: Refus anticipé sans effet de bord
- **GIVEN** un runtime distant dont la sélection de sandbox refuse le code non fiable et un `MINOS_HOME` temporaire
- **WHEN** `./mvnw -B -ntp -pl minos-application -am test "-Dtest=RemoteIndexingUseCase*Test" "-Dsurefire.failIfNoSpecifiedTests=false"` exécute l'indexation distante
- **THEN** elle échoue avec l'identifiant du backend et ses codes de refus, sans chemin absolu, le matérialiseur n'est jamais appelé, aucun dossier `remote-index-leases` n'est créé et le registre est inchangé

#### Scenario: Échec après enregistrement
- **GIVEN** un runtime qualifié dont l'exécution distribuée échoue après l'enregistrement d'un nouveau projet
- **WHEN** l'indexation distante est exécutée par le test du cas d'usage
- **THEN** l'échec est propagé, le projet est retiré du registre, la source est désépinglée et libérée une seule fois, et le bail est relâché

#### Scenario: Requête distante invalide refusée par la commande
- **GIVEN** `minos remote materialize` (cas existant `rejectsUnsafeUrlBeforeCallingOperations`) puis `minos remote index` avec une URL portant un secret (`https://token@github.com/...`)
- **WHEN** `./mvnw -B -ntp -pl minos-cli -am test "-Dtest=RemoteIndexCommandTest" "-Dsurefire.failIfNoSpecifiedTests=false"` est exécuté
- **THEN** la commande échoue avec le message actuel, sans le secret, et la doublure des opérations distantes n'est jamais appelée

#### Scenario: Même refus par la commande
- **GIVEN** la CLI sur un hôte sans sandbox qualifiée
- **WHEN** `./mvnw -B -ntp -pl minos-cli -am test "-Dtest=RemoteIndexCommandTest,LocalRemoteIndexOperations*Test" "-Dsurefire.failIfNoSpecifiedTests=false"` est exécuté
- **THEN** les tests réussissent sans attente modifiée : message et code de sortie de `minos remote index` identiques

### Requirement: L'état de reprise est lu sans écriture par l'application
La CLI, l'API Java et le MCP SHALL obtenir l'état d'un run reprenable par une même requête de `minos-application`. Cette requête SHALL n'appeler aucune méthode d'écriture ni de bail du magasin d'état et, l'application ouverte, SHALL NOT créer, modifier ni verrouiller de fichier sous `MINOS_HOME`. Un identifiant de projet qui n'est pas un UUID SHALL donner une absence de run reprenable, pas une erreur.

#### Scenario: Aucune écriture demandée au magasin
- **GIVEN** un magasin d'état de test contenant un run interrompu et qui échoue sur toute écriture ou prise de bail
- **WHEN** `./mvnw -B -ntp -pl minos-application -am test "-Dtest=ProjectInspectionService*Test" "-Dsurefire.failIfNoSpecifiedTests=false"` lit l'état du projet
- **THEN** la requête rend le run, ses cibles reprenables et sa phase, sans qu'aucune méthode refusée soit appelée

#### Scenario: Lecture sans trace sur un vrai MINOS_HOME
- **GIVEN** une application ouverte sur un `MINOS_HOME` temporaire contenant un run interrompu, dont on relève chemins, tailles et dates de modification après l'ouverture
- **WHEN** `./mvnw -B -ntp -pl minos-mcp -am test "-Dtest=MinosMcpReadOnlyHomeTest" "-Dsurefire.failIfNoSpecifiedTests=false"` appelle `minos_index_status`
- **THEN** la réponse porte le run reprenable et le relevé d'après est identique, sous Linux comme sous Windows

#### Scenario: Identifiant invalide
- **GIVEN** l'identifiant `pas-un-uuid`
- **WHEN** la requête de reprise est appelée par le test de `minos-application`
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
Les sorties de `minos index`, `minos remote`, `minos project`, `minos index-status`, `minos tools` et `minos doctor`, les réponses MCP et les valeurs rendues par `com.minos.api` SHALL rester identiques à l'octet, codes de sortie compris. Aucune signature de `com.minos.api` ni méthode publique de `MinosApplication` SHALL être retirée ou modifiée, et aucun golden SHALL être régénéré.

#### Scenario: Tests des surfaces inchangés
- **GIVEN** le dépôt après le changement
- **WHEN** `./mvnw -B -ntp -pl minos-cli,minos-api,minos-mcp -am test` est exécuté
- **THEN** il réussit et `git diff --stat develop -- "*golden*"` ne liste aucun fichier

#### Scenario: Golden de caractérisation inchangés
- **GIVEN** le dépôt après le changement
- **WHEN** `./mvnw -B -ntp -pl minos-app -am test "-Dtest=A2SurfaceCharacterizationTest,ApplicationOwnershipGuardTest" "-Dsurefire.failIfNoSpecifiedTests=false"` est exécuté
- **THEN** il réussit : `index-status`, `ProjectDto`, `ProviderDto` et `minos_index_status` égalent leurs golden

#### Scenario: Capacités triées comme avant
- **GIVEN** un `ProviderDto` construit avec des capacités non triées
- **WHEN** le test de `ProviderPlatformApi` est exécuté
- **THEN** les capacités sont rendues triées, non modifiables, et une clé ou une valeur nulle est refusée par `NullPointerException` comme avant

### Requirement: Un accesseur de MinosApplication appelé par une surface compte comme un usage du type rendu
Le garde SHALL lire dans la source de `MinosApplication` ses méthodes publiques d'instance dont le type de retour nomme un type de `minos-engine`. Dans une surface, un appel de même arité ou une référence `::nom` à l'une d'elles SHALL compter comme un usage du type rendu, dans le compteur de la règle A11. Un appel homonyme d'arité différente SHALL être ignoré.

#### Scenario: Accesseur appelé sans nommer le type
- **GIVEN** un arbre de test où `MinosApplication` déclare un accesseur public `snapshotStore()` qui rend `CodeKnowledgeSnapshotStore`, et où un fichier de `minos-api` l'appelle sans importer ce type, absent de la liste de l'API
- **WHEN** `python -m unittest scripts/architecture/test_check_module_boundaries_ratchets.py` est exécuté
- **THEN** le cas de test vérifie que le garde échoue en nommant la surface, le fichier et le type rendu

#### Scenario: Import remplacé par var
- **GIVEN** un arbre de test où `MinosApplication` déclare un accesseur public qui rend `GitIntelligence`, et où un fichier qui importait ce type écrit `var g = app.gitIntelligence()` sans l'import
- **WHEN** `python -m unittest scripts/architecture/test_check_module_boundaries_ratchets.py` est exécuté
- **THEN** le cas de test vérifie que le garde réussit avec le même compteur : le fichier utilise toujours le type

#### Scenario: Méthode homonyme d'arité différente
- **GIVEN** un arbre de test où une surface appelle `builder.gitIntelligence(supplier)`, à un argument
- **WHEN** `python -m unittest scripts/architecture/test_check_module_boundaries_ratchets.py` est exécuté
- **THEN** le cas de test vérifie que cet appel n'est pas compté comme un usage de `GitIntelligence`

### Requirement: Les types du moteur utilisés par le bytecode d'une surface le sont par liste fermée
Le test `ModuleArchitectureTest` SHALL appliquer, par surface et par module de contrat, la liste des types de premier niveau de `minos-engine` dont dépendent ses classes compilées, chacun avec le nombre de classes de premier niveau qui en dépendent. Cette liste SHALL être lue dans la source unique de la politique de frontières. Un type hors liste, un compteur différent ou une entrée inutilisée SHALL faire échouer le test.

#### Scenario: Appel chaîné sur un résultat du moteur
- **GIVEN** une classe de `minos-cli` qui appelle une méthode d'un type de `minos-engine` absent de la liste, sans le nommer
- **WHEN** `ModuleArchitectureTest` est exécuté
- **THEN** il échoue en nommant la surface, le type, le compteur attendu et les classes

#### Scenario: Usage retiré sans mettre la table à jour
- **GIVEN** une liste de la source unique qui nomme un type dont plus aucune classe de la surface ne dépend
- **WHEN** le test est exécuté
- **THEN** il échoue en nommant l'entrée à retirer

#### Scenario: Dépôt réel
- **GIVEN** le reactor compilé
- **WHEN** `ModuleArchitectureTest` est exécuté dans les jobs `Verify` Linux et Windows
- **THEN** il réussit

### Requirement: Le mécanisme d'ACL du runtime local n'est nommé par aucun autre module
Le package `com.minos.runtime.local.acl` SHALL être un package gardé du runtime local, sans type exporté : une source de production ou de test d'un autre module qui nomme l'un de ses types SHALL faire échouer le garde. Le port qu'il implémente SHALL être atteint par `ServiceLoader` seulement.

#### Scenario: Nom d'une classe du package acl dans le moteur
- **GIVEN** une copie du dépôt où un fichier de `minos-engine` importe `com.minos.runtime.local.acl.WindowsIcacls`
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en nommant le fichier et le type

#### Scenario: Dépôt réel
- **GIVEN** le dépôt
- **WHEN** le garde est exécuté
- **THEN** il réussit, et le package `acl` figure parmi les packages gardés

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

### Requirement: Le contrôle hébergé est consommé par une façade applicative
`minos-api`, `minos-cli` et `minos-mcp` SHALL obtenir le plan de contrôle de l'équipe par `TeamControlPlane` de `minos-application`, que rend `MinosApplication.teamControlPlane()`. Leurs sources de production SHALL NOT nommer ni utiliser un type de `com.minos.hosted`, par import, nom qualifié, chaînage d'accesseur ou en bytecode. Leurs sorties SHALL rester identiques.

#### Scenario: Le moteur réimporté dans l'API
- **GIVEN** une copie du dépôt où `LocalMinosTeamApi` importe de nouveau `com.minos.hosted.HostedRole`
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté sur cette copie
- **THEN** il échoue en A11 en nommant `minos-api`, le fichier et le type, absent de `ENGINE_TYPES_NAMED_BY_SURFACE`

#### Scenario: Un accesseur du moteur chaîné par la CLI
- **GIVEN** une copie du dépôt où `MinosCliRunner` appelle de nouveau `opened.hostedControlPlaneService()`
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en A11 sur `hosted.HostedControlPlaneService` pour `minos-cli`

#### Scenario: Aucune entrée hosted dans les tables
- **GIVEN** le dépôt
- **WHEN** `test_check_module_boundaries.py` et `ModuleArchitectureTest` sont exécutés
- **THEN** ils sont verts et aucune surface n'a d'entrée `hosted.*` dans l'une ou l'autre table

#### Scenario: Sorties inchangées
- **GIVEN** le dépôt
- **WHEN** `A2SurfaceCharacterizationTest` et `RendererKeyOrderTest` sont exécutés
- **THEN** `team.golden`, `retention.golden`, `api.golden` et `mcp-all-tools.golden` sont égaux à l'octet et l'ordre des clés est inchangé

### Requirement: L'activité Git est consommée par une requête applicative
La CLI et l'API Java SHALL obtenir l'inspection et l'activité Git d'un projet par `ProjectGitQuery` de `minos-application`, qui rend les records de `com.minos.domain.git`. Les sources de production de `minos-cli` et `minos-api` SHALL NOT nommer ni utiliser `git.GitIntelligence`, ni par import, ni par nom qualifié, ni par l'accesseur `gitIntelligence()`, ni en bytecode.

#### Scenario: Le port Git réimporté par la commande
- **GIVEN** une copie du dépôt où `GitActivityCommand` importe de nouveau `com.minos.git.GitIntelligence`
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté sur cette copie
- **THEN** il échoue en nommant `minos-cli`, le fichier et le type, absent de `ENGINE_TYPES_NAMED_BY_SURFACE`

#### Scenario: L'accesseur appelé par l'API
- **GIVEN** une copie du dépôt où `LocalMinosMultiRepositoryApi` appelle `app.gitIntelligence()` sans import
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en nommant `minos-api` et `com.minos.git.GitIntelligence`

#### Scenario: Requête testée sans racine de composition
- **GIVEN** un faux port `GitIntelligence` dans un test de `minos-application`
- **WHEN** `LocalProjectGitQuery` inspecte et analyse un dépôt par lui
- **THEN** elle rend les réponses du port, refuse une requête absente sans appeler le port, et propage l'exception du port sans enveloppe

#### Scenario: Les bornes de la requête Git sont tenues par le domaine
- **GIVEN** `GitActivityFactsTest` dans `minos-domain`
- **WHEN** une `ActivityQuery` est construite hors de ses bornes
- **THEN** elle est refusée avec le message qui nomme la borne, et les adresses d'auteurs sont masquées par défaut

#### Scenario: Sortie inchangée
- **GIVEN** le dépôt
- **WHEN** `A2SurfaceCharacterizationTest`, `GitActivityCommandTest` et `LocalMinosMultiRepositoryApiIntegrationTest` sont exécutés
- **THEN** `cli-git.golden` est inchangé et les valeurs de `inspectGit` et `analyzeGitActivity` sont celles attendues aujourd'hui

### Requirement: La requête de symboles de l'API est construite par l'application
`LocalMinosApi` SHALL construire sa requête de symboles à partir de `MinosApplication` par `LocalProjectSymbolQuery`, sans appeler `projectRegistry()` ni `snapshotStore()`. Les sources de production de `minos-api` SHALL NOT utiliser `registry.ProjectRegistry` ni `store.CodeKnowledgeSnapshotStore`.

#### Scenario: Les accesseurs réintroduits dans l'API
- **GIVEN** une copie du dépôt où `LocalMinosApi` appelle de nouveau `app.projectRegistry()`
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté sur cette copie
- **THEN** il échoue en nommant `minos-api` et `com.minos.registry.ProjectRegistry`

#### Scenario: Réponses inchangées
- **GIVEN** le dépôt
- **WHEN** `A2SurfaceCharacterizationTest` est exécuté
- **THEN** `api.golden` est inchangé

### Requirement: Les résultats de requête sont des modèles de lecture publiés
Les seules entrées `query.*` des surfaces dans les deux tables A11 SHALL être `SymbolResult`, `UsageResult` et `RelationshipResult`, rendus par le port applicatif `ProjectSymbolQuery`. Chacune SHALL porter dans la table la raison écrite de sa présence (ADR 0057, point 6). Un autre type de `com.minos.query` nommé par une surface SHALL faire échouer A11.

#### Scenario: Les entrées de requête du dépôt réel
- **GIVEN** le dépôt
- **WHEN** l'auto-test `test_check_module_boundaries.py` est exécuté
- **THEN** l'ensemble des entrées `query.*` de `ENGINE_TYPES_NAMED_BY_SURFACE` est inclus dans les trois modèles de lecture publiés

#### Scenario: Un service de requête du moteur nommé par la CLI
- **GIVEN** une copie du dépôt où un fichier de `minos-cli` importe `com.minos.query.SymbolQueryService`
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en nommant `minos-cli`, le fichier et le type

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

### Requirement: Les adaptateurs de stockage forment un seul module
Le reactor SHALL compter un seul module de stockage, `minos-storage`, qui contient `com.minos.storage.local` et `com.minos.storage.postgresql` sans classe dupliquée ni package déclaré ailleurs. `ALLOWED_DEPENDENCIES` et `ModuleArchitectureTest.ALLOWED` SHALL lui donner `minos-domain` et `minos-engine` pour seules cibles. Le garde SHALL refuser un module `minos-storage-local` ou `minos-storage-postgresql` réapparu.

#### Scenario: Reactor fusionné
- **GIVEN** le dépôt après la fusion
- **WHEN** `python scripts/architecture/check-module-boundaries.py` s'exécute
- **THEN** il réussit, la liste `MODULES` égale la liste des modules du reactor (règle A7), et la politique de dépendances lue par le garde et par `ModuleArchitectureTest` nomme `minos-storage` sans aucun des deux anciens nœuds

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

### Requirement: Les backends de stockage ne se nomment pas l'un l'autre
Aucune source de production ni de test de `com.minos.storage.local` SHALL nommer `com.minos.storage.postgresql` ou `org.postgresql`, et aucune de `com.minos.storage.postgresql` SHALL nommer `com.minos.storage.local`, par import simple, statique, joker ou nom qualifié. Le garde SHALL échouer en nommant le fichier, le type et la règle. Une mention en commentaire ou dans un littéral SHALL rester acceptée.

#### Scenario: Test PostgreSQL qui nomme le backend local
- **GIVEN** une copie du dépôt où une classe de test de `com.minos.storage.postgresql` importe `com.minos.storage.local.store.FileSymbolSnapshotStore`
- **WHEN** `python scripts/architecture/check-module-boundaries.py` s'exécute
- **THEN** il échoue en nommant le fichier de test, le type et la règle

#### Scenario: Backend local qui nomme PostgreSQL par nom qualifié
- **GIVEN** une copie où une source de production de `com.minos.storage.local` nomme `com.minos.storage.postgresql.support.PostgresJdbcUrlPolicy` sans import
- **WHEN** le garde s'exécute
- **THEN** il échoue en nommant le fichier et le type

#### Scenario: Mention en commentaire
- **GIVEN** une copie où une Javadoc de `com.minos.storage.local` cite `com.minos.storage.postgresql`
- **WHEN** le garde s'exécute
- **THEN** il réussit

### Requirement: Le codec et le mapping de chemins ne dépendent d'aucun backend
Aucune source de production ni de test de `com.minos.storage.codec` ou de `com.minos.storage.home` SHALL nommer `com.minos.storage.local`, `com.minos.storage.postgresql`, `org.postgresql` ou `java.sql`. Le garde SHALL refuser toute violation en nommant le fichier, le type et la règle, et `ModuleArchitectureTest` SHALL refuser la même dépendance dans le bytecode de production.

#### Scenario: Codec qui ouvre une connexion JDBC
- **GIVEN** une copie où `com.minos.storage.codec.KnowledgeSnapshotCodecs` importe `java.sql.Connection`
- **WHEN** le garde s'exécute
- **THEN** il échoue en nommant le fichier, `java.sql.Connection` et la règle du codec

#### Scenario: Mapping qui appelle le backend local
- **GIVEN** une copie où `com.minos.storage.home.ProjectPathMappingStore` nomme `com.minos.storage.local.registry.LocalProjectRegistry`
- **WHEN** le garde s'exécute
- **THEN** il échoue en nommant le fichier et le type

#### Scenario: Dépendance du codec dans le bytecode
- **GIVEN** une classe compilée de `com.minos.storage.codec` qui dépend d'une classe de `com.minos.storage.local`
- **WHEN** `ModuleArchitectureTest` s'exécute
- **THEN** il échoue en nommant les deux classes

### Requirement: Le backend PostgreSQL ne dépend pas du backend local dans le bytecode
`ModuleArchitectureTest` SHALL refuser toute dépendance d'une classe de production de `com.minos.storage.postgresql` vers `com.minos.storage.local`. Le garde et cette règle SHALL rester vérifiables sans base PostgreSQL ni Docker.

#### Scenario: Registre PostgreSQL qui utilise le registre local
- **GIVEN** une classe compilée de `com.minos.storage.postgresql` qui appelle `com.minos.storage.local.registry.LocalProjectRegistry`
- **WHEN** `ModuleArchitectureTest` s'exécute
- **THEN** il échoue en nommant les deux classes

#### Scenario: Module conforme
- **GIVEN** le dépôt après ce changement
- **WHEN** `./mvnw -B -ntp -pl minos-app -am test "-Dtest=ModuleArchitectureTest"` s'exécute sans Docker
- **THEN** toutes les règles réussissent

### Requirement: Le provider SCIP ne nomme pas le stockage en production
Aucune source de production de `minos-provider-scip` SHALL nommer un type de `minos-storage`. Le provider SHALL recevoir le magasin actif et ses magasins de travail par les ports du moteur `CodeKnowledgeSnapshotStore` et `StagingSnapshotStores`, que `minos-bootstrap` lui fournit. Son POM SHALL déclarer `minos-storage` en portée `test` seulement, et A13 SHALL n'avoir aucune entrée `minos-provider-scip` → `minos-storage`.

#### Scenario: Retour d'une création de magasin dans le provider
- **GIVEN** une copie du dépôt où `ScipProjectSnapshotLifecycle` crée de nouveau un `FileSymbolSnapshotStore`
- **WHEN** le garde est exécuté
- **THEN** A13 échoue en nommant le fichier et le type absent de la table

#### Scenario: Dépendance de compilation rétablie
- **GIVEN** une copie du dépôt où le POM de `minos-provider-scip` déclare `minos-storage` sans portée
- **WHEN** le garde est exécuté
- **THEN** il échoue en nommant le module, la dépendance et la portée attendue

### Requirement: Les sous-packages de com.minos.storage appartiennent à minos-storage
Hors de `minos-storage`, aucune source de production ni de test SHALL déclarer un package sous `com.minos.storage.` ; `minos-engine` garde le seul package racine `com.minos.storage`. Le garde SHALL refuser toute violation en nommant le fichier. Aucun package existant n'est renommé.

#### Scenario: Sous-package du stockage dans le moteur
- **GIVEN** un arbre de test où `minos-engine` (production), `minos-application` (production) ou `minos-bootstrap` (test) déclare `com.minos.storage.cache`
- **WHEN** `python scripts/architecture/test_check_module_boundaries.py StoragePackageIsolationRuleTest` est exécuté
- **THEN** le garde échoue en nommant le fichier et « below com.minos.storage outside minos-storage »

#### Scenario: Ports du stockage dans le moteur
- **GIVEN** le dépôt, où `minos-engine` déclare `com.minos.storage` et `minos-storage` déclare `codec`, `home`, `local` et `postgresql`
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté
- **THEN** il réussit

### Requirement: La part de code concret du moteur est mesurée sans cliquet
Le garde de frontières SHALL afficher à chaque exécution, pour `minos-engine`, les fichiers et lignes de production par genre de première déclaration de premier niveau (classe, interface, record, enum, `info`, `unclassified`). Il SHALL NOT échouer sur cette mesure (ADR 0068, décision D1 du 2026-10-28).

#### Scenario: Mesure sur un arbre de test
- **GIVEN** un arbre de test avec deux classes, une interface, un record, un enum, un `package-info` et un fichier sans déclaration dans `minos-engine`
- **WHEN** `python scripts/architecture/test_check_module_boundaries.py EngineConcreteCodeMeasureTest` est exécuté
- **THEN** chaque fichier est compté une fois dans son genre avec toutes ses lignes, et les tests et les autres modules sont ignorés

#### Scenario: Mesure du dépôt
- **GIVEN** le dépôt
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté
- **THEN** il affiche la ligne `AUD-ARC-05 minos-engine concrete code` avec le nombre de fichiers de classes, leurs lignes et le total, et aucun fichier `unclassified`

### Requirement: La correspondance des chemins Docker passe par une façade applicative
`DockerRuntimeBootstrap` SHALL configurer la correspondance hôte/conteneur par `ProjectPathMappingService` (`minos-application`), sans nommer ni atteindre un type de `minos-engine`. La façade SHALL créer une correspondance absente, accepter la même et refuser d'en remplacer une autre sans rien écrire. Sorties, messages et codes de sortie SHALL rester identiques.

#### Scenario: Correspondance conflictuelle refusée
- **GIVEN** un `MINOS_HOME` qui contient la correspondance `N:/workspace-dev` ↔ `/workspace/projects`
- **WHEN** `DockerRuntimeBootstrapTest` invoque `configure-project-paths D:/other-projects /workspace/projects`
- **THEN** le code de sortie vaut 1, l'erreur contient « refusing to replace », et le fichier garde `N:/workspace-dev`

#### Scenario: Création puis invocation identique
- **GIVEN** un `MINOS_HOME` vide
- **WHEN** la même correspondance est configurée deux fois, la seconde avec des séparateurs Windows
- **THEN** les deux invocations sortent en 0, la première écrit « mapping created », la seconde « already matches »

#### Scenario: Le port réintroduit dans la CLI
- **GIVEN** une copie du dépôt où une classe de production de `minos-cli` importe `com.minos.registry.ProjectPathMappings`
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté sur cette copie
- **THEN** le garde échoue en A11 en nommant `minos-cli`, le fichier et le type

### Requirement: Les accesseurs de MinosApplication typés moteur sont tenus par un cliquet
La table `MINOS_APPLICATION_ENGINE_ACCESSORS` SHALL être vide, et les accesseurs publics d'instance de `MinosApplication` qui rendent un type de `minos-engine` SHALL être exactement ceux de cette table. Un accesseur hors table, un type rendu différent, une entrée sans accesseur ou une surface de production qui atteint la racine de composition SHALL faire échouer A11. `gitIntelligence`, `providerCatalog`, `scipArtifactImporter` et `hostedControlPlaneService` SHALL ne plus être publics.

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

#### Scenario: Entrée ajoutée à la table vide refusée
- **GIVEN** un arbre de test où la table reçoit l'entrée `projectRegistry` pendant que `MinosApplication` garde cet accesseur privé au package
- **WHEN** `python -m unittest scripts/architecture/test_check_module_boundaries_ratchets.py` est exécuté
- **THEN** le cas de test vérifie que le garde échoue en demandant de retirer l'entrée

#### Scenario: Table à jour au HEAD
- **GIVEN** le dépôt
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté
- **THEN** il réussit et annonce 0 accesseur public typé moteur, et la CLI ne liste plus `registry.ProjectPathMappings` dans aucune table A11

### Requirement: Un module de contrat reste sous les surfaces
Le garde SHALL tenir une famille de modules de contrat, distincte des surfaces, qui contient `minos-nexus`. Un module de contrat SHALL ne dépendre que de `minos-domain` et de `minos-application` en production, et de `minos-bootstrap` en portée test seulement. Il SHALL NOT dépendre d'une surface, d'un adaptateur, de `minos-engine` ni de `minos-app`. `ModuleArchitectureTest` SHALL appliquer la même règle sur le bytecode.

#### Scenario: Dépôt réel
- **GIVEN** le dépôt, où `minos-nexus` appartient à la famille de contrat et plus à celle des surfaces
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté
- **THEN** il se termine avec le code 0

#### Scenario: Module de contrat qui dépend d'une surface
- **GIVEN** une copie des POM où `minos-nexus` déclare `minos-cli`
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il se termine avec le code 1 en nommant `minos-nexus`, `minos-cli` et la famille de contrat

#### Scenario: Racine de composition hors portée test
- **GIVEN** une copie des POM où `minos-nexus` déclare `minos-bootstrap` en portée `runtime`
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en nommant l'arête et la portée attendue

#### Scenario: Type du moteur réintroduit
- **GIVEN** une copie du dépôt où une classe de `minos-nexus` importe un type de `minos-engine`
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en A11 en nommant `minos-nexus`, le fichier et le type

### Requirement: Aucune surface ne dépend d'une autre surface
Le garde SHALL refuser toute dépendance interne d'un module de surface vers un autre module de surface, quelle que soit sa portée Maven. Une surface SHALL pouvoir dépendre d'un module de contrat. L'assemblage `minos-app` n'est pas une surface et reste hors de cette règle.

#### Scenario: Arête de la CLI vers le contrat NEXUS
- **GIVEN** le dépôt, où `minos-cli` déclare `minos-nexus`
- **WHEN** le garde est exécuté
- **THEN** il réussit, l'arête allant d'une surface vers un module de contrat

#### Scenario: Surface qui dépend d'une surface
- **GIVEN** une copie des POM où `minos-mcp` déclare `minos-cli` en portée `test`
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il se termine avec le code 1 en nommant les deux surfaces

### Requirement: Le signal sémantique NEXUS est déclaré non qualifié
Tant qu'aucune commande ni aucun client ne consomme `NexusSemanticSignalService`, la classe SHALL rester déclarée dans la liste des types publics sans appelant avec sa raison, et `docs/developer/public-surfaces.md` SHALL la présenter comme non qualifiée par un client. La documentation SHALL NOT la présenter comme une capacité disponible.

#### Scenario: Documentation honnête
- **GIVEN** le dépôt
- **WHEN** on cherche `NexusSemanticSignalService` dans `docs/developer/public-surfaces.md`
- **THEN** la section NEXUS porte la mention « non qualifié » sur la même ligne ou le même paragraphe

#### Scenario: Entrée du cliquet retirée sans appelant
- **GIVEN** une copie du dépôt où l'entrée de `NexusSemanticSignalService` est retirée de la liste des types sans appelant
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en A15 en nommant la classe

### Requirement: La politique de frontières partagée a une source unique
La liste des modules, leur famille, les dépendances internes autorisées, les couples admis en portée test seulement et les deux vues de la dette A11 (source et bytecode) SHALL être écrits dans un seul fichier de données versionné. Le garde Python et `ModuleArchitectureTest` SHALL lire ce fichier et SHALL NOT en tenir de copie. Un mouvement de cliquet SHALL se faire par une seule édition.

#### Scenario: Dépôt réel
- **GIVEN** le dépôt
- **WHEN** `python scripts/architecture/check-module-boundaries.py` puis `ModuleArchitectureTest` sont exécutés
- **THEN** les deux réussissent, et le garde affiche le même nombre de modules et de règles qu'avant la bascule

#### Scenario: Arête retirée de la source unique
- **GIVEN** une copie du dépôt où l'arête `minos-cli` → `minos-nexus` est retirée du seul fichier de données
- **WHEN** le garde, puis `ModuleArchitectureTest`, sont exécutés sur cette copie
- **THEN** le garde échoue en nommant l'arête du POM hors politique, et la règle ArchUnit des modules autorisés échoue sur le même couple, sans autre édition

#### Scenario: Compteur de la vue bytecode
- **GIVEN** une copie du dépôt où le compteur d'un type du moteur de la vue bytecode est augmenté de un dans le fichier de données
- **WHEN** `ModuleArchitectureTest` est exécuté
- **THEN** il échoue en nommant la surface, le type, le compteur écrit et le compteur observé

### Requirement: Une source de politique illisible fait échouer les deux lecteurs
Le garde Python et `ModuleArchitectureTest` SHALL échouer, sans appliquer de valeur par défaut, quand le fichier de politique est absent, n'est pas analysable, porte une clé inconnue ou une clé en double, omet une section attendue, donne à un module une famille inconnue ou nomme un module absent de la liste des modules. Le message SHALL nommer le fichier par son chemin relatif au dépôt et la cause.

#### Scenario: Fichier absent
- **GIVEN** une copie du dépôt sans le fichier de politique
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il se termine avec le code 1 en nommant le fichier attendu, et n'affiche aucune ligne de succès

#### Scenario: Clé inconnue
- **GIVEN** une copie du fichier où une section est mal orthographiée
- **WHEN** le garde, puis `ModuleArchitectureTest`, sont exécutés
- **THEN** chacun échoue en nommant la clé inconnue ; aucun ne traite la section manquante comme vide

#### Scenario: Module inconnu dans une arête
- **GIVEN** une copie du fichier où une dépendance autorisée nomme `minos-inconnu`
- **WHEN** le garde est exécuté
- **THEN** il échoue en nommant le module et l'entrée

### Requirement: Le test ArchUnit ne redéclare aucune table de politique
La règle A17 du garde SHALL échouer quand `ModuleArchitectureTest` ne lit plus le fichier de politique, ou déclare de nouveau une table littérale de dépendances autorisées, de modules, d'adaptateurs, de surfaces ou de types du moteur. La règle SHALL échouer aussi quand le fichier du test est absent ou illisible.

#### Scenario: Table recopiée dans le test
- **GIVEN** une copie du dépôt où le test déclare de nouveau une table littérale `ALLOWED` par `Map.ofEntries`
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en A17 en nommant le test et la table recopiée

#### Scenario: Test qui ne lit plus la source
- **GIVEN** une copie du dépôt où le test ne nomme plus le fichier de politique
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en A17

#### Scenario: Dépôt réel
- **GIVEN** le dépôt
- **WHEN** le garde est exécuté
- **THEN** la ligne A17 annonce que le test lit la source unique, et `archgate check` réussit

### Requirement: La portée d'une dépendance hors compilation est écrite dans la documentation de dépendances
Le document de dépendances généré SHALL afficher, à côté de chaque dépendance interne déclarée dans une portée autre que la compilation, cette portée (`test` ou `runtime`). Dans `docs/architecture/arc42/05-vue-blocs.md`, la ligne « Dépendances » d'un module SHALL faire suivre un tel module d'une parenthèse qui commence par sa portée, et le garde SHALL échouer si la portée manque, est fausse, ou est écrite pour une dépendance de compilation.

#### Scenario: Document généré
- **GIVEN** le dépôt, où `minos-explorer` déclare `minos-engine` en portée `test` et `minos-bootstrap` en portée `runtime`
- **WHEN** `python scripts/architecture/check-module-boundaries.py --write-doc` est exécuté
- **THEN** la ligne de `minos-explorer` du document porte ces deux portées, et une seconde exécution sans `--write-doc` se termine avec le code 0

#### Scenario: Portée absente de l'arc42
- **GIVEN** une copie de la vue des blocs où la ligne de `minos-explorer` cite `minos-engine` sans portée
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il se termine avec le code 1 en nommant le module, la dépendance et la portée attendue `test`

#### Scenario: Parenthèse qui n'est pas une portée
- **GIVEN** le dépôt, où la ligne de `minos-bootstrap` fait suivre sa dernière dépendance d'une parenthèse « (voir … ) »
- **WHEN** le garde est exécuté
- **THEN** cette parenthèse n'est pas lue comme une portée, et la ligne est acceptée puisque la dépendance est déclarée en compilation

#### Scenario: Portée écrite à tort
- **GIVEN** une copie de la vue des blocs où la ligne de `minos-cli` écrit `minos-application` suivi de la portée `test`
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue : la dépendance est déclarée en compilation

### Requirement: Les racines de packages citées par la documentation d'un module sont les siennes
Pour chaque section de module de `docs/architecture/arc42/05-vue-blocs.md`, le garde SHALL exiger une ligne « Sources ». Les racines `com/minos/<racine>/` qu'elle cite SHALL être exactement celles que la table des racines de packages attribue au module. Une ligne qui ne cite que le répertoire de sources du module, sans racine, SHALL être acceptée.

#### Scenario: Racine omise
- **GIVEN** une copie de la vue des blocs où la ligne « Sources » de `minos-engine` cite ses racines sauf `com/minos/io/`
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il se termine avec le code 1 en nommant `minos-engine` et la racine `io` manquante

#### Scenario: Racine d'un autre module
- **GIVEN** une copie où la ligne « Sources » de `minos-domain` cite `com/minos/store/`
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en nommant la racine que le module ne possède pas

#### Scenario: Section sans ligne Sources
- **GIVEN** une copie où la section d'un module n'a pas de ligne « Sources »
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue (jamais un succès par défaut)

#### Scenario: Répertoire de sources seul
- **GIVEN** le dépôt, où la ligne de `minos-application` cite seulement son répertoire de sources
- **WHEN** le garde est exécuté
- **THEN** la ligne est acceptée

### Requirement: Les interfaces citées par la documentation d'un module existent et sont attribuées au bon module
Dans une ligne « Interfaces » de la vue des blocs, chaque type cité entre accents graves SHALL être un type de production du reactor, appartenant au module de la section ou à un module que la ligne nomme par une tournure « défini dans » ; un module nommé autrement SHALL NOT compter. La mention « aucune » SHALL être refusée pour un module qui déclare une interface publique de premier niveau. Une section sans ligne « Interfaces » SHALL rester acceptée.

#### Scenario: Port attribué au mauvais module
- **GIVEN** une copie où la ligne de `minos-storage` cite `SemanticVectorStore` parmi des types « définis dans `minos-engine` », et nomme `minos-application` dans une autre phrase
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il se termine avec le code 1 en nommant le type et son module réel, `minos-domain`

#### Scenario: Aucune interface déclarée à tort
- **GIVEN** une copie où la ligne de `minos-domain` vaut « aucune »
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en nommant au moins une interface publique du module

#### Scenario: Type disparu
- **GIVEN** une copie où une ligne « Interfaces » cite un type qu'aucun module ne déclare
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en nommant le type

#### Scenario: Documentation corrigée
- **GIVEN** le dépôt après correction de la vue des blocs
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté
- **THEN** il se termine avec le code 0

### Requirement: L'historique d'import est lu et écrit par un port
`minos-application` SHALL lire et écrire l'historique d'import d'un projet par un port de `minos-engine`, dont l'implémentation fichier vit dans `minos-storage` et est fournie par `minos-bootstrap`. Aucune source de production de `minos-application` SHALL nommer le dossier `cli-index-history` ni les clés du fichier. `MinosApplication` SHALL NOT exposer ce port par une méthode publique.

#### Scenario: Littéral absent de l'application
- **GIVEN** le dépôt après le changement
- **WHEN** on recherche `cli-index-history` dans `minos-application/src/main`
- **THEN** la recherche ne retourne aucune ligne

#### Scenario: Un seul détenteur du format
- **GIVEN** le dépôt après le changement
- **WHEN** on recherche `cli-index-history` dans les sources de production de tous les modules
- **THEN** une seule classe, dans `minos-storage`, le contient

#### Scenario: Import réussi écrit l'historique sans avertissement
- **GIVEN** un projet enregistré et un artefact SCIP valide
- **WHEN** `./mvnw -B -ntp -pl minos-cli -am test "-Dtest=ImportHistoryFailureTest" "-Dsurefire.failIfNoSpecifiedTests=false"` est exécuté
- **THEN** l'import est rapporté `COMMITTED`, le fichier d'historique du projet existe et aucun avertissement n'est journalisé

#### Scenario: Inspection après import
- **GIVEN** une application ouverte par la racine de composition de production, après l'import d'un artefact SCIP de provider et de version connus
- **WHEN** un test de `minos-bootstrap` inspecte le projet
- **THEN** la vue rend l'instantané actif, le provider, sa version et la date de fin de l'import

#### Scenario: Port non exposé par la façade
- **GIVEN** le dépôt après le changement
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté
- **THEN** le garde A11 réussit sans entrée nouvelle dans `MINOS_APPLICATION_ENGINE_ACCESSORS`

### Requirement: Le format de l'historique d'import reste lisible
L'implémentation fichier SHALL conserver l'emplacement `MINOS_HOME/cli-index-history/<identifiant du projet>.properties` et les clés `snapshotId`, `providerId`, `providerVersion`, `completedAt`. Un fichier écrit par une version antérieure SHALL être relu à l'identique. Un fichier absent SHALL se lire comme « aucun historique ».

#### Scenario: Fichier d'une version antérieure
- **GIVEN** un fichier d'historique copié tel qu'il est écrit au HEAD, avec `providerVersion` vide
- **WHEN** `./mvnw -B -ntp -pl minos-storage -am test "-Dtest=FileIndexImportHistoryStoreTest" "-Dsurefire.failIfNoSpecifiedTests=false"` est exécuté
- **THEN** les quatre valeurs sont rendues, la version du provider étant absente

#### Scenario: Projet sans historique
- **GIVEN** un `MINOS_HOME` sans dossier `cli-index-history`
- **WHEN** l'historique d'un projet est lu
- **THEN** le résultat est vide et aucun dossier n'est créé

#### Scenario: Écriture atomique et privée
- **GIVEN** un historique existant pour un projet
- **WHEN** un nouvel historique est écrit
- **THEN** le fichier est remplacé de façon atomique, garde des droits privés et aucun fichier temporaire ne subsiste

### Requirement: Un historique d'import illisible est refusé sans fuite
Un fichier d'historique hors bornes ou privé d'une clé obligatoire SHALL être refusé par une erreur qui nomme la clé manquante et la feuille du fichier, jamais son chemin absolu. Un échec d'écriture de l'historique SHALL NOT annuler un import dont l'instantané est actif.

#### Scenario: Clé obligatoire manquante
- **GIVEN** un fichier d'historique sans `snapshotId`
- **WHEN** `./mvnw -B -ntp -pl minos-storage -am test "-Dtest=FileIndexImportHistoryStoreTest" "-Dsurefire.failIfNoSpecifiedTests=false"` est exécuté
- **THEN** l'erreur nomme `snapshotId` et le nom du fichier, sans chemin absolu

#### Scenario: Refus du port relayé par l'inspection
- **GIVEN** un port d'historique de test qui lève l'erreur de clé manquante
- **WHEN** `./mvnw -B -ntp -pl minos-application -am test "-Dtest=ProjectInspectionServiceRefusalMessageTest" "-Dsurefire.failIfNoSpecifiedTests=false"` est exécuté
- **THEN** l'inspection relaie le message du port sans y ajouter de chemin absolu

#### Scenario: Fichier hors bornes
- **GIVEN** un fichier d'historique plus grand que la borne de lecture
- **WHEN** l'implémentation fichier le lit
- **THEN** la lecture est refusée avant d'avoir chargé le contenu en entier

#### Scenario: Dossier d'historique inutilisable
- **GIVEN** un `MINOS_HOME` où `cli-index-history` est un fichier ordinaire
- **WHEN** un import SCIP réussit (`ImportHistoryFailureTest`)
- **THEN** l'import est rapporté réussi avec un avertissement qui cite le projet et l'instantané actif, sans chemin absolu ni nom du dossier

### Requirement: Le fournisseur d'embeddings appris est fourni par la racine de composition
Aucune source de production de `minos-application` hors de la classe du fournisseur SHALL nommer `OllamaEmbeddingProvider`. L'application SHALL lire et valider les réglages sémantiques, puis obtenir le fournisseur par une fabrique de `MinosApplicationComposer` implémentée par `minos-bootstrap`. Un fournisseur configuré que la racine de composition ne sait pas fournir SHALL faire échouer l'ouverture, sans repli sur « désactivé ».

#### Scenario: Fournisseur Ollama configuré
- **GIVEN** un `MINOS_HOME` dont les réglages fixent le fournisseur `ollama`, un modèle et des dimensions valides
- **WHEN** `./mvnw -B -ntp -pl minos-bootstrap -am test "-Dtest=M23SemanticProviderConfigurationTest" "-Dsurefire.failIfNoSpecifiedTests=false"` est exécuté
- **THEN** l'application ouverte porte un fournisseur de même identifiant, modèle, dimensions, point d'accès et limites qu'avant le changement

#### Scenario: Aucune instanciation dans l'application
- **GIVEN** le dépôt après le changement
- **WHEN** on recherche `OllamaEmbeddingProvider` dans `minos-application/src/main` hors de `OllamaEmbeddingProvider.java`
- **THEN** la recherche ne retourne aucune ligne

#### Scenario: Fournisseur que la racine ne fournit pas
- **GIVEN** un composeur de test dont la fabrique ne reconnaît pas le fournisseur demandé
- **WHEN** l'application est ouverte avec ce fournisseur configuré
- **THEN** l'ouverture échoue par une erreur d'usage qui nomme le fournisseur, sans chemin absolu
- **AND** aucun fournisseur désactivé n'est installé à sa place

#### Scenario: Réglage sémantique invalide
- **GIVEN** des réglages `ollama` sans modèle, ou avec des dimensions non positives
- **WHEN** l'application est ouverte
- **THEN** l'ouverture échoue avec le même message qu'avant le changement, avant tout appel à la fabrique

### Requirement: Les fournisseurs du graphe de programme sont fournis par la racine de composition
`ProgramGraphService` SHALL utiliser la liste de fournisseurs qu'il reçoit et SHALL NOT en instancier. La liste de production et la complétion d'une liste surchargée SHALL être fournies par `MinosApplicationComposer`, implémenté par `minos-bootstrap` : l'analyseur de sources Java est ajouté seulement si le fournisseur de relations est présent, le lecteur de sidecar toujours. Une liste vide SHALL être refusée avant toute complétion.

#### Scenario: Liste de production
- **GIVEN** une application ouverte par `MinosApplication.open(home)` sans surcharge
- **WHEN** les identifiants de fournisseurs du graphe de programme sont lus par un test de `minos-bootstrap`
- **THEN** ils sont identiques, dans le même ordre, à ceux du HEAD

#### Scenario: Liste surchargée par le Builder
- **GIVEN** un `Builder` qui reçoit seulement le fournisseur fondé sur les relations
- **WHEN** l'application est construite avec la racine de composition de production
- **THEN** le graphe porte aussi les contributions de l'analyseur de sources Java et du lecteur de sidecar, comme au HEAD

#### Scenario: Liste surchargée sans le fournisseur de relations
- **GIVEN** un `Builder` qui reçoit seulement un fournisseur de test d'un autre identifiant
- **WHEN** l'application est construite avec la racine de composition de production
- **THEN** les fournisseurs sont celui du test puis le lecteur de sidecar, sans analyseur de sources Java

#### Scenario: Service sans instanciation
- **GIVEN** le dépôt après le changement
- **WHEN** on recherche `new JavaSourceProgramGraphProvider`, `new FileProgramGraphProvider`, `new FingerprintConstrainedJavaProgramGraphProvider` et `new RelationshipProgramGraphProvider` dans `minos-application/src/main`
- **THEN** la recherche ne retourne aucune ligne

#### Scenario: Liste vide refusée
- **GIVEN** un composeur de test dont la liste de production est vide
- **WHEN** l'application est construite sans surcharge du `Builder`
- **THEN** la construction échoue avec `programGraphProviders must not be empty`
- **AND** la complétion n'est pas demandée à la racine de composition

### Requirement: Les API d'infrastructure du JDK nommées par l'application le sont par liste fermée
Le garde de frontières SHALL refuser toute source de production de `minos-application` qui nomme `java.net.HttpURLConnection`, un type de `java.net.http`, de `javax.tools` ou de `com.sun.source`, sauf les fichiers d'une table à cliquet. Un fichier hors table et une entrée qui ne nomme plus aucune de ces API SHALL faire échouer le garde. Commentaires et littéraux SHALL être ignorés.

#### Scenario: Nouveau client HTTP dans l'application
- **GIVEN** un arbre de test où une classe de production de `minos-application` importe `java.net.http.HttpClient`
- **WHEN** `python scripts/architecture/test_check_module_boundaries.py` est exécuté
- **THEN** le cas vérifie que le garde échoue en nommant le fichier et l'API

#### Scenario: Table du HEAD acceptée
- **GIVEN** le dépôt après le changement
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté
- **THEN** il se termine avec le code 0 et annonce le nombre de fichiers de la table

#### Scenario: Entrée périmée
- **GIVEN** un arbre de test où un fichier de la table ne nomme plus aucune API surveillée
- **WHEN** l'auto-test du garde est exécuté
- **THEN** le cas vérifie que le garde échoue en demandant de retirer l'entrée

#### Scenario: Mention en commentaire
- **GIVEN** un arbre de test où une classe hors table cite `javax.tools.ToolProvider` dans une Javadoc seulement
- **WHEN** l'auto-test du garde est exécuté
- **THEN** le cas vérifie que le garde réussit

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

### Requirement: Le câblage des fournisseurs par la racine ne change aucune sortie publique
Le déplacement de l'instanciation des fournisseurs d'embeddings et de graphe de programme vers `minos-bootstrap` SHALL laisser identiques à l'octet les sorties caractérisées de la CLI, de l'API Java et du MCP, ainsi que l'ordre de composition figé par les tests de caractérisation.

#### Scenario: Goldens inchangés
- **GIVEN** le dépôt après le changement
- **WHEN** `./mvnw -B -ntp -pl minos-app -am verify` puis `git diff --exit-code minos-app/src/test/resources/characterization/` sont exécutés
- **THEN** la commande `git diff` se termine avec le code 0

#### Scenario: Ordre de composition
- **GIVEN** le dépôt après le changement
- **WHEN** `A2CompositionCharacterizationTest` est exécuté
- **THEN** il réussit sans régénération de golden

### Requirement: Les classes de production de minos-app sont tenues par liste fermée
Le garde de frontières SHALL tenir la liste fermée des classes de production de `minos-app`, chacune avec son rôle (assemblage, route de lancement, transport, configuration du backend du home). Une classe de production absente de la liste et une entrée sans classe SHALL faire échouer le garde. La liste SHALL NOT contenir de classe dont le rôle est une politique applicative.

#### Scenario: Liste du HEAD acceptée
- **GIVEN** le dépôt après le changement
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté
- **THEN** il se termine avec le code 0 et annonce le nombre de classes de `minos-app`

#### Scenario: Nouvelle classe dans l'assemblage
- **GIVEN** un arbre de test où `minos-app` gagne une classe de production absente de la liste
- **WHEN** `python scripts/architecture/test_check_module_boundaries.py` est exécuté
- **THEN** le cas vérifie que le garde échoue en nommant la classe et en demandant de la placer dans un module applicatif ou une surface

#### Scenario: Entrée périmée
- **GIVEN** un arbre de test où la liste nomme une classe que `minos-app` ne déclare plus
- **WHEN** l'auto-test du garde est exécuté
- **THEN** le cas vérifie que le garde échoue en demandant de retirer l'entrée

#### Scenario: Rôle absent
- **GIVEN** un arbre de test où une entrée de la liste n'a pas de rôle écrit
- **WHEN** l'auto-test du garde est exécuté
- **THEN** le cas vérifie que le garde échoue en nommant l'entrée

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

### Requirement: La documentation courante ne cite aucune ligne du garde de frontières
Hors des documents figés (`docs/project/history/`, `docs/project/audits/`, `docs/project/roadmap/`, archives, `.claude/agent-memory/`), `docs/` et `.claude/` SHALL ne citer aucune ligne de `scripts/architecture/check-module-boundaries.py`, ni décrire une copie de la politique de frontières dans `ModuleArchitectureTest`, ni situer « dans le script » une table que porte `module-boundaries.json` ou un module de règles.

#### Scenario: Documentation alignée
- **GIVEN** le dépôt après correction
- **WHEN** `git grep -nE "check-module-boundaries\.py:[0-9]|liste .MODULES. du script|lue dans le script|ALLOWED. égale" -- docs .claude ':!docs/project/history' ':!docs/project/audits' ':!docs/project/roadmap' ':!**/archive/**' ':!.claude/agent-memory'` est exécuté
- **THEN** la commande ne rend aucune ligne et se termine avec le code 1

#### Scenario: Citation de ligne réintroduite
- **GIVEN** une copie de travail où le dossier de la capacité cite de nouveau `check-module-boundaries.py:59-85`
- **WHEN** la même commande est exécutée
- **THEN** elle rend cette ligne et se termine avec le code 0, ce que la revue refuse

### Requirement: La documentation des gates nomme chaque fichier du garde de frontières
Le chapitre « Gardes d'architecture » de `docs/developer/quality-gates.md` SHALL nommer `check-module-boundaries.py`, chaque `module_boundary_*.py` avec ses règles, `gate_runner.py`, `module_boundaries_testing.py`, chaque auto-test `test_check_module_boundaries*.py`, `test_gate_runner.py`, et `module-boundaries.json` comme source unique de la politique, et décrire chaque règle `A<n>` du garde. Un auto-test sur le dépôt réel SHALL échouer sur un fichier ou une règle non nommés.

#### Scenario: Chaque fichier du garde est nommé
- **GIVEN** le dépôt après correction
- **WHEN** pour chaque fichier `f` de `scripts/architecture/module_boundary_*.py`, `scripts/architecture/test_check_module_boundaries*.py`, `test_gate_runner.py`, `gate_runner.py`, `module_boundaries_testing.py` et `module-boundaries.json`, `grep -c "$(basename f)" docs/developer/quality-gates.md` est exécuté
- **THEN** chaque compte est au moins 1
- **AND** `python scripts/architecture/test_check_module_boundaries_documents.py` se termine avec le code 0

#### Scenario: Module de règles ajouté sans documentation
- **GIVEN** une copie du dépôt où un fichier `scripts/architecture/module_boundary_x.py` est ajouté sans modifier `docs/developer/quality-gates.md`
- **WHEN** `python scripts/architecture/test_check_module_boundaries_documents.py` est exécuté dans la copie
- **THEN** l'auto-test se termine avec le code 1 et nomme `module_boundary_x.py`

#### Scenario: Auto-test non nommé
- **GIVEN** une copie du dépôt où la mention de `test_gate_runner.py` est retirée de `docs/developer/quality-gates.md`
- **WHEN** `python scripts/architecture/test_check_module_boundaries_documents.py` est exécuté dans la copie
- **THEN** l'auto-test se termine avec le code 1 et nomme `test_gate_runner.py`

#### Scenario: Règle du garde sans description
- **GIVEN** une copie du dépôt où la puce de `docs/developer/quality-gates.md` qui décrit A20 est retirée
- **WHEN** `python scripts/architecture/test_check_module_boundaries_documents.py` est exécuté dans la copie
- **THEN** l'auto-test se termine avec le code 1 et nomme `A20`

### Requirement: Le point d'entrée du garde exécute chacune de ses règles
L'auto-test du câblage SHALL dériver la liste des règles de toutes les fonctions `check_*` du garde et de chaque fichier `scripts/architecture/module_boundary_*.py` du disque, sans liste recopiée, et échouer sur un glob vide. Il SHALL prouver pour chaque règle qu'un espion qui lève une exception rend `main()` rouge (code 1, message sur stderr). Une `check_*` que `main()` n'exécute pas SHALL faire échouer l'auto-test en la nommant, sauf exemption fermée et justifiée, vide à la livraison.

#### Scenario: Appel d'une règle retiré du point d'entrée
- **GIVEN** une copie de `scripts/architecture/` dans un répertoire temporaire, où la ligne `service_loaders = check_service_loader_class_loaders()` de `run_rules` est remplacée par `service_loaders = 0`
- **WHEN** `python test_check_module_boundaries.py MainWiringTest` est exécuté dans la copie
- **THEN** l'auto-test se termine avec le code 1 et nomme `check_service_loader_class_loaders`
- **AND** la même mutation sur `check_embedded_adapters` (`api_users, embedded_adapters = 0, 0`) échoue de même

#### Scenario: Règle atteinte par une autre règle
- **GIVEN** une copie de `scripts/architecture/` où l'appel `check_pom_layout(module, pom)` de `check_pom_rules` est supprimé
- **WHEN** `python test_check_module_boundaries.py MainWiringTest` est exécuté dans la copie
- **THEN** l'auto-test se termine avec le code 1 et nomme `check_pom_layout`

#### Scenario: Règle nouvelle non câblée
- **GIVEN** une copie de `scripts/architecture/` où un module de règles définit une fonction `check_demo` que `run_rules` n'appelle pas, référencée par un test
- **WHEN** `python test_check_module_boundaries.py MainWiringTest` est exécuté dans la copie
- **THEN** l'auto-test se termine avec le code 1 et nomme `check_demo`

#### Scenario: Module de règles nouveau non câblé
- **GIVEN** une copie du dépôt où `scripts/architecture/module_boundary_extra.py` définit `check_extra_rule`, que le garde n'importe pas, ou qu'il atteint seulement par `import module_boundary_extra` sans l'appeler
- **WHEN** `python scripts/architecture/test_check_module_boundaries.py` est exécuté dans la copie
- **THEN** l'auto-test se termine avec le code 1 et nomme `check_extra_rule`, dans les deux variantes

#### Scenario: Modules de règles introuvables
- **GIVEN** une copie du dépôt où le glob des modules de règles ne trouve aucun fichier `module_boundary_*.py`
- **WHEN** `python scripts/architecture/test_check_module_boundaries.py` est exécuté dans la copie
- **THEN** l'auto-test se termine avec le code 1 au lieu de ne contrôler que le script d'entrée

#### Scenario: Erreur d'une règle avalée
- **GIVEN** une copie de `scripts/architecture/` où `run_rules` entoure l'appel de `check_package_cycles` d'un `try` / `except Exception` qui rend `0, 0`
- **WHEN** `python test_check_module_boundaries.py MainWiringTest` est exécuté dans la copie
- **THEN** l'auto-test se termine avec le code 1 et nomme `check_package_cycles`

#### Scenario: Dépôt au HEAD
- **WHEN** `python scripts/architecture/test_check_module_boundaries.py` est exécuté sur le dépôt
- **THEN** l'auto-test réussit et chaque fonction `check_*` du garde et de ses modules de règles a son sous-test de câblage

### Requirement: Le lanceur partagé des gardes d'architecture a son propre auto-test
`scripts/architecture/gate_runner.py` SHALL avoir un auto-test exécuté par le job `invariants` de `pr-ci.yml`. Il SHALL prouver qu'un rapport rendu est imprimé sur stdout avec le code 0 et rien sur stderr, qu'une exception est rapportée sur stderr sous la forme `<label> FAILED: <message>` avec le code 1 et rien sur stdout, et qu'une interruption qui n'est pas une `Exception` (`SystemExit`, `KeyboardInterrupt`) n'est jamais rendue comme un succès.

#### Scenario: Rapport d'un garde vert
- **WHEN** `python scripts/architecture/test_gate_runner.py` est exécuté sur le dépôt
- **THEN** l'auto-test réussit, dont le cas où `run_gate` reçoit une vérification qui rend un rapport : code 0, rapport sur stdout, stderr vide

#### Scenario: Échec rendu comme un succès
- **GIVEN** une copie de `scripts/architecture/` dans un répertoire temporaire, où `return 1` de `run_gate` est remplacé par `return 0`
- **WHEN** `python test_gate_runner.py` est exécuté dans la copie
- **THEN** l'auto-test se termine avec le code 1

#### Scenario: Auto-test du lanceur sans exécutant
- **GIVEN** une copie du dépôt dont le job `invariants` de `.github/workflows/pr-ci.yml` n'a plus d'étape qui lance `scripts/architecture/test_gate_runner.py`
- **WHEN** `python scripts/quality/check-ci-wiring.py --root <copie>` est exécuté
- **THEN** le gate se termine avec le code 1 et nomme `scripts/architecture/test_gate_runner.py`

### Requirement: La façade ne rend ni type du moteur ni racine de composition
`MinosApplication` SHALL NOT déclarer, au niveau de son corps, de méthode publique ni de constructeur public dont le type de retour ou un paramètre nomme un type de `minos-engine` ou `MinosApplicationComposer` ; son `Builder` imbriqué est hors de cette règle. Ses ports et sa racine de composition SHALL rester lisibles par les seules classes du package `com.minos.application`. Le garde SHALL faire échouer A11 dès qu'une telle signature apparaît, en nommant la méthode et le type.

#### Scenario: Façade réduite au HEAD
- **GIVEN** le dépôt après le changement, `minos-application` compilé
- **WHEN** `javap -public -cp minos-application/target/classes com.minos.application.MinosApplication` est exécuté
- **THEN** la sortie ne contient ni `compositionRoot`, ni `projectRegistry`, ni `snapshotStore`, ni `providerRuntimeManager`, ni aucune méthode publique dont le type de retour ou un paramètre appartient à `com.minos.orchestration`

#### Scenario: Racine de composition réintroduite refusée
- **GIVEN** un arbre de test où `MinosApplication` déclare `public MinosApplicationComposer compositionRoot()`
- **WHEN** `python -m unittest scripts/architecture/test_check_module_boundaries_ratchets.py` est exécuté
- **THEN** le cas de test vérifie que le garde échoue en A11 en nommant `compositionRoot` et `MinosApplicationComposer`

#### Scenario: Accesseur typé moteur réintroduit refusé
- **GIVEN** un arbre de test où `MinosApplication` déclare `public ProjectRegistry projectRegistry()` et où la table des accesseurs est vide
- **WHEN** `python -m unittest scripts/architecture/test_check_module_boundaries_ratchets.py` est exécuté
- **THEN** le cas de test vérifie que le garde échoue en A11 en nommant `projectRegistry` et `registry.ProjectRegistry`

#### Scenario: Paramètre typé moteur refusé
- **GIVEN** un arbre de test où `MinosApplication` déclare `public void leak(ProjectRegistry registry)`, puis `public static MinosApplication of(com.minos.registry.ProjectRegistry registry)`, puis `public ProjectIndexingUseCase projectIndexing(UnaryOperator<IndexerExecutor> decorator)`
- **WHEN** `python -m unittest scripts/architecture/test_check_module_boundaries_ratchets.py` est exécuté
- **THEN** le cas de test vérifie que, pour chaque variante, le garde échoue en A11 en nommant la méthode et le type du moteur

#### Scenario: Le Builder garde ses paramètres du moteur
- **GIVEN** un arbre de test où seul le `Builder` imbriqué de `MinosApplication` déclare `public Builder projectRegistry(ProjectRegistry value)`
- **WHEN** `python -m unittest scripts/architecture/test_check_module_boundaries_ratchets.py` est exécuté
- **THEN** le cas de test vérifie que la règle des paramètres réussit

#### Scenario: Aucune signature typée moteur au HEAD
- **GIVEN** le dépôt après le changement
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté
- **THEN** il se termine avec le code 0

### Requirement: Les tests lisent un port de l'application par un accès de test
Un test d'un autre module qui lit un port d'une application ouverte, ou qui construit l'indexation avec un décorateur d'exécuteur, SHALL passer par une classe d'accès des sources de test de `minos-application`, dans le package `com.minos.application`, publiée par le `test-jar` de ce module. Ce `test-jar` SHALL être déclaré en portée `test` seulement, et le garde SHALL refuser toute autre portée. Aucun jar livré SHALL contenir la classe d'accès.

#### Scenario: Jar livré sans accès de test
- **GIVEN** le reactor construit par `./mvnw -B -ntp verify`
- **WHEN** le test de packaging liste le contenu du jar de `minos-application` et du jar ombré `target/minos-code-intelligence-*-all.jar`
- **THEN** aucun des deux ne contient la classe d'accès de test

#### Scenario: Test d'une surface sur un home protégé
- **GIVEN** un `MINOS_HOME` en lecture seule préparé par la classe d'accès de test sur une application ouverte par `MinosApplication.open`
- **WHEN** `./mvnw -B -ntp -pl minos-mcp -am test "-Dtest=MinosMcpWriteProtectedHomeTest" "-Dsurefire.failIfNoSpecifiedTests=false"` est exécuté
- **THEN** le test réussit

#### Scenario: Accès de test hors portée de test refusé
- **GIVEN** un arbre de test où le `pom.xml` d'une surface déclare le `test-jar` de `minos-application` en portée `compile`
- **WHEN** `python -m unittest scripts/architecture/test_check_module_boundaries.py` est exécuté
- **THEN** le cas de test vérifie que le garde échoue en nommant le module, `minos-application`, le type `test-jar` et la portée

#### Scenario: Indexation décorée lue par l'accès de test
- **GIVEN** le dépôt après le changement
- **WHEN** `./mvnw -B -ntp -pl minos-application -am test "-Dtest=MinosApplicationTestAccessTest" "-Dsurefire.failIfNoSpecifiedTests=false"` est exécuté
- **THEN** le test réussit : `decoratedProjectIndexing` est un relais de `MinosApplicationTestAccess` et n'est pas une méthode publique de `MinosApplication`

### Requirement: Le retrait des accesseurs de la façade ne change aucune sortie publique
Le retrait du public de `compositionRoot()` et des accesseurs typés moteur de `MinosApplication` SHALL laisser identiques à l'octet les sorties caractérisées de la CLI, de l'API Java et du MCP. Aucune signature de `com.minos.api` SHALL changer, et le refus de l'indexation distante SHALL intervenir avant tout effet.

#### Scenario: Goldens inchangés
- **GIVEN** le dépôt après le changement
- **WHEN** `./mvnw -B -ntp -pl minos-app -am verify` puis `git diff --exit-code minos-app/src/test/resources/characterization/` sont exécutés
- **THEN** la commande `git diff` se termine avec le code 0

#### Scenario: Refus de l'indexation distante conservé
- **GIVEN** le dépôt après le changement
- **WHEN** `./mvnw -B -ntp -pl minos-cli -am test "-Dtest=LocalRemoteIndexOperationsRefusalTest" "-Dsurefire.failIfNoSpecifiedTests=false"` est exécuté
- **THEN** le test réussit : le refus intervient avant toute matérialisation, bail, enregistrement ou épingle

### Requirement: La documentation courante de la façade décrit son état en vigueur
Hors des relevés figés (`docs/project/history/`, `docs/project/audits/`, archives), `docs/` et les sources de production de `minos-application` SHALL ne présenter `MinosApplication` ni comme une racine de composition, ni comme un accès public à `compositionRoot()`, et SHALL ne recopier le statut d'aucun ADR à côté d'un lien vers lui. La ligne d'ARCH-0087 de l'index des ADR SHALL citer les trois changements que l'ADR nomme.

#### Scenario: Prose en vigueur
- **GIVEN** le dépôt après correction
- **WHEN** `git grep -nE 'Long-lived composition root|composition root long-lived|via .MinosApplication\.compositionRoot\(\).|ARCH-0087\]\([^)]*\) \(Proposed\)' -- docs minos-application/src/main ':!docs/project/history' ':!docs/project/audits' ':!**/archive/**'` est exécuté
- **THEN** la commande ne rend aucune ligne et se termine avec le code 1

#### Scenario: Ancienne description réintroduite
- **GIVEN** une copie de travail où le Javadoc de classe de `MinosApplication` redevient « Long-lived composition root for one MINOS home »
- **WHEN** la même commande est exécutée
- **THEN** elle rend cette ligne et se termine avec le code 0, ce que la revue refuse

#### Scenario: Index des ADR complet pour ARCH-0087
- **GIVEN** le dépôt après correction
- **WHEN** `git grep -n 'retirer-la-racine-de-composition-de-la-facade' -- docs/architecture/adr/README.md` est exécuté
- **THEN** la commande rend la ligne d'ARCH-0087 et se termine avec le code 0

### Requirement: La table de rupture de la façade ne promet aucun remplaçant absent
Dans `docs/user/java-api.md`, chaque remplaçant donné par une table de rupture de `MinosApplication` ou de ses cas d'usage SHALL être une méthode ou un constructeur public qui rend ce service, ou SHALL être écrit « aucun remplaçant public ». `retentionService()` SHALL être documenté sans remplaçant à la demande, la rétention suivant chaque indexation et chaque import qui publie un snapshot.

#### Scenario: Rétention sans remplaçant promis
- **GIVEN** le dépôt après correction
- **WHEN** `git grep -nE 'retentionService\(\)` \| `new LocalProjectOperations' -- docs/user/java-api.md` est exécuté
- **THEN** la commande ne rend aucune ligne et se termine avec le code 1

#### Scenario: Promesse réintroduite
- **GIVEN** une copie de travail où la ligne `retentionService()` de la table redonne `new LocalProjectOperations(application)` comme remplaçant
- **WHEN** la même commande est exécutée
- **THEN** elle rend cette ligne et se termine avec le code 0, ce que la revue refuse

#### Scenario: Décoration des exécuteurs sans remplaçant public
- **GIVEN** le dépôt après correction
- **WHEN** `git grep -n 'projectIndexing(executorDecorator)' -- docs/user/java-api.md` est exécuté
- **THEN** la commande ne rend aucune ligne et se termine avec le code 1
- **AND** la ligne de `ProjectIndexingUseCase.of(MinosApplication, UnaryOperator<IndexerExecutor>)` contient « aucun remplaçant public »

#### Scenario: Méthode privée au package promise de nouveau
- **GIVEN** une copie de travail où cette ligne redonne `application.projectIndexing(executorDecorator)` comme remplaçant
- **WHEN** la commande `git grep` du scénario précédent est exécutée
- **THEN** elle rend cette ligne et se termine avec le code 0, ce que la revue refuse

### Requirement: Le test-jar de l'application ne publie que la classe d'accès
`minos-application/pom.xml` SHALL déclarer une seule exécution du but `test-jar`, dont `<includes>` contient exactement `com/minos/application/MinosApplicationTestAccess.class`. Le garde SHALL refuser un `<includes>` absent, élargi ou complété, et une seconde exécution, en nommant le POM et la valeur. Le `test-jar` construit SHALL n'avoir, hors de `META-INF/`, que cette entrée.

#### Scenario: Inclusion élargie refusée par le garde
- **GIVEN** un arbre de test où `minos-application/pom.xml` remplace l'inclusion par `com/minos/application/**`, puis ajoute une seconde inclusion, puis supprime `<includes>`
- **WHEN** `python -m unittest scripts/architecture/test_check_module_boundaries.py` est exécuté
- **THEN** le cas de test vérifie que, pour chaque variante, le garde échoue en nommant `minos-application/pom.xml` et la valeur trouvée

#### Scenario: Seconde exécution test-jar refusée
- **GIVEN** un arbre de test où `minos-application/pom.xml` déclare une seconde exécution du but `test-jar` sans `<includes>`
- **WHEN** `python -m unittest scripts/architecture/test_check_module_boundaries.py` est exécuté
- **THEN** le cas de test vérifie que le garde échoue en nommant `minos-application/pom.xml`

#### Scenario: Contenu du test-jar construit
- **GIVEN** le reactor construit
- **WHEN** `./mvnw -B -ntp -pl minos-app -am verify "-Dit.test=ApplicationTestAccessPackagingIT"` est exécuté
- **THEN** l'IT réussit : les entrées fichiers du `test-jar` hors de `META-INF/` valent exactement `com/minos/application/MinosApplicationTestAccess.class`, et aucune déclaration de service n'y figure

#### Scenario: Configuration du test-jar au HEAD
- **GIVEN** le dépôt après le changement
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté
- **THEN** il se termine avec le code 0

### Requirement: Les opérations locales reçoivent leurs collaborateurs et seule la façade lit ses ports
`LocalProjectOperations`, `ProviderPlatformService` et `LocalProjectGitQuery` SHALL être construits à partir de leurs collaborateurs, assemblés par `MinosApplication`. Aucune classe de production autre que `MinosApplication` SHALL appeler une méthode non publique de `MinosApplication` qui rend un type de `minos-engine` ou `MinosApplicationComposer`. `ModuleArchitectureTest` SHALL échouer en nommant la classe appelante et la méthode.

#### Scenario: Port relu par une classe du package
- **GIVEN** une copie du dépôt où `LocalProjectGitQuery(MinosApplication)` appelle de nouveau `application.gitIntelligence()`, accesseur rétabli dans `MinosApplication`
- **WHEN** `./mvnw -B -ntp -pl minos-app -am test "-Dtest=ModuleArchitectureTest" "-Dsurefire.failIfNoSpecifiedTests=false"` est exécuté sur cette copie
- **THEN** le test échoue en nommant `LocalProjectGitQuery` et `MinosApplication.gitIntelligence()`

#### Scenario: Seule la façade lit ses ports au HEAD
- **GIVEN** le dépôt après le changement
- **WHEN** `./mvnw -B -ntp -pl minos-app -am test "-Dtest=ModuleArchitectureTest" "-Dsurefire.failIfNoSpecifiedTests=false"` est exécuté
- **THEN** le test réussit

#### Scenario: Import exercé sans application
- **GIVEN** un test de `minos-application` qui fournit des doubles des sept collaborateurs de `LocalProjectOperations`
- **WHEN** `./mvnw -B -ntp -pl minos-application -am test "-Dtest=LocalProjectOperations*Test" "-Dsurefire.failIfNoSpecifiedTests=false"` est exécuté
- **THEN** l'import publié applique la rétention dans l'ordre publication, état, rétention, sans qu'aucune `MinosApplication` soit ouverte

#### Scenario: Collaborateur manquant refusé
- **GIVEN** des `Dependencies` de `LocalProjectOperations` dont un collaborateur est `null`
- **WHEN** les opérations sont construites dans un test de `minos-application`
- **THEN** la construction échoue par `NullPointerException` qui nomme le collaborateur, avant toute lecture du registre

### Requirement: Les points d'entrée publics des opérations locales sont conservés
`LocalProjectOperations(Path)`, `LocalProjectOperations(MinosApplication)`, `ProviderPlatformService.defaults(MinosApplication)`, `LocalProjectGitQuery(MinosApplication)` et `LocalProjectSymbolQuery(MinosApplication)` SHALL garder leur signature publique et leur comportement. `defaults` SHALL lire le catalogue des fournisseurs à chaque appel. Les sorties caractérisées de la CLI, de l'API Java et du MCP SHALL rester identiques à l'octet.

#### Scenario: Signatures publiques inchangées
- **GIVEN** le dépôt après le changement, `minos-application` compilé
- **WHEN** `javap -public -cp minos-application/target/classes com.minos.application.LocalProjectOperations com.minos.application.ProviderPlatformService com.minos.application.LocalProjectGitQuery com.minos.application.LocalProjectSymbolQuery` est exécuté
- **THEN** la sortie contient les cinq points d'entrée qui prennent `Path` ou `MinosApplication`, et aucun type `Dependencies` public

#### Scenario: Catalogue lu à chaque appel
- **GIVEN** un test de `minos-application` dont l'application a un catalogue qui change de fournisseurs entre deux appels
- **WHEN** `ProviderPlatformService.defaults(application).listStaticProfiles()` est appelé deux fois
- **THEN** le second appel rend les fournisseurs du catalogue modifié

#### Scenario: Goldens inchangés
- **GIVEN** le dépôt après le changement
- **WHEN** `./mvnw -B -ntp -pl minos-app -am verify` puis `git diff --exit-code minos-app/src/test/resources/characterization/` sont exécutés
- **THEN** la commande `git diff` se termine avec le code 0

### Requirement: Les ports sans lecteur sont retirés de la façade
`MinosApplication` SHALL NOT déclarer `gitIntelligence()`, `providerCatalog()`, `scipArtifactImporter()`, `indexImportHistoryStore()` ni `hostedControlPlaneService()`. Leurs collaborateurs SHALL rester requis non nuls par la construction de l'application.

#### Scenario: Façade sans les ports retirés
- **GIVEN** le dépôt après le changement, `minos-application` compilé
- **WHEN** `javap -p -cp minos-application/target/classes com.minos.application.MinosApplication` est exécuté
- **THEN** la sortie ne contient ni `gitIntelligence()`, ni `providerCatalog()`, ni `scipArtifactImporter()`, ni `indexImportHistoryStore()`, ni `hostedControlPlaneService()`

#### Scenario: Collaborateur de la composition toujours exigé
- **GIVEN** un test de `minos-application` qui construit une application par le `Builder` avec un importeur SCIP nul et une racine de composition qui n'en fournit pas
- **WHEN** l'application est construite
- **THEN** la construction échoue par `NullPointerException` qui nomme `scipArtifactImporter`

### Requirement: Le dossier de la capacité nomme chaque règle du garde
Le paragraphe « Règles du garde » de `docs/architecture/diagrams/capacites/frontieres-de-modules.md` SHALL citer exactement les étiquettes `A<n>` portées par les sources du garde (`check-module-boundaries.py`, `module_boundary_*.py`), chacune par ce qu'elle refuse. Le dossier SHALL n'écrire aucun nombre dérivé de la politique (modules, dépendances, découvertes). Un auto-test sur le dépôt réel SHALL échouer, en la nommant, sur une étiquette manquante ou en trop, et sur un paragraphe introuvable.

#### Scenario: Dossier aligné sur le garde
- **GIVEN** le dépôt après correction
- **WHEN** `python scripts/architecture/test_check_module_boundaries_documents.py` est exécuté
- **THEN** l'auto-test se termine avec le code 0
- **AND** `grep -nE "12 autres|A2 à A18|tables Java et Python|en affirme cinq" docs/architecture/diagrams/capacites/frontieres-de-modules.md` ne rend aucune ligne et se termine avec le code 1

#### Scenario: Règle retirée du dossier
- **GIVEN** une copie du dépôt où `A19` est retirée du paragraphe « Règles du garde » du dossier
- **WHEN** `python scripts/architecture/test_check_module_boundaries_documents.py` est exécuté dans la copie
- **THEN** l'auto-test se termine avec le code 1 et nomme `A19`

#### Scenario: Règle nouvelle du garde non documentée
- **GIVEN** une copie du dépôt où un message d'un module de règles porte l'étiquette `A21`
- **WHEN** `python scripts/architecture/test_check_module_boundaries_documents.py` est exécuté dans la copie
- **THEN** l'auto-test se termine avec le code 1 et nomme `A21`

#### Scenario: Paragraphe introuvable
- **GIVEN** une copie du dépôt où l'amorce « Règles du garde » du paragraphe est réécrite
- **WHEN** `python scripts/architecture/test_check_module_boundaries_documents.py` est exécuté dans la copie
- **THEN** l'auto-test se termine avec le code 1 au lieu de réussir sur un ensemble vide

### Requirement: Les points d'entrée publics des opérations locales sont éprouvés sur une application
Un test de `minos-application` SHALL faire passer un import SCIP publié par `new LocalProjectOperations(application)` et affirmer la publication, l'état et la rétention de cette application, dans cet ordre. Un test SHALL affirmer par un appel observable que `new LocalProjectSymbolQuery(application)` lit le registre de cette application.

#### Scenario: Import publié par le constructeur sur application
- **GIVEN** une application construite sur les ports de la fixture de `LocalProjectOperationsRetentionTest`
- **WHEN** `./mvnw -B -ntp -pl minos-application -am test "-Dtest=LocalProjectOperationsRetentionTest" "-Dsurefire.failIfNoSpecifiedTests=false"` est exécuté
- **THEN** le cas sur application constate les événements `publish`, `state` puis `retention` du snapshot importé

#### Scenario: Rétention de l'assemblage retirée
- **GIVEN** une copie de travail où `localProjectOperationsDependencies()` passe une autre rétention que `stores.retentionService()`
- **WHEN** la même commande est exécutée
- **THEN** le cas sur application échoue

#### Scenario: Requête de symboles sur le registre de l'application
- **GIVEN** une application dont le registre double enregistre ses appels
- **WHEN** `./mvnw -B -ntp -pl minos-application -am test "-Dtest=LocalOperationsAssemblyTest" "-Dsurefire.failIfNoSpecifiedTests=false"` est exécuté
- **THEN** le cas constate qu'une recherche de symboles par `LocalProjectSymbolQuery(application)` a appelé ce registre

### Requirement: Une méthode générique ou un import joker de la façade ne contourne pas A11
A11 SHALL lire les méthodes publiques, statiques comprises, et les constructeurs publics de `MinosApplication`, y compris quand ils déclarent des paramètres de type à bornes imbriquées. Un type de `minos-engine` nommé dans une borne SHALL compter comme rendu et pris par la méthode ; une méthode statique qui rend un tel type SHALL échouer. Un import joker d'un package de `minos-engine` dans `MinosApplication` SHALL faire échouer A11 en nommant le package.

#### Scenario: Paramètre typé moteur d'une méthode générique
- **GIVEN** une copie du dépôt où `MinosApplication` déclare `public <T> void leakGeneric(com.minos.registry.ProjectRegistry r) {}`
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté dans la copie
- **THEN** le garde se termine avec le code 1 en A11 et nomme `leakGeneric` et `registry.ProjectRegistry`

#### Scenario: Retour typé moteur d'une méthode générique
- **GIVEN** une copie du dépôt où `MinosApplication` déclare `public <T> com.minos.registry.ProjectRegistry leakRet() { return null; }`
- **WHEN** le garde est exécuté dans la copie
- **THEN** il se termine avec le code 1 en A11 et nomme `leakRet`

#### Scenario: Retour typé moteur d'une méthode statique
- **GIVEN** l'auto-test du garde, avec `public static ProjectRegistry leakStatic()` puis `public static <T> ProjectRegistry leakStaticGeneric()`
- **WHEN** `python scripts/architecture/test_check_module_boundaries_ratchets.py` est exécuté
- **THEN** chaque variante est refusée en A11, message nommant la méthode et `registry.ProjectRegistry`

#### Scenario: Type du moteur porté par une borne
- **GIVEN** l'auto-test du garde, avec `public <T extends ProjectRegistry> void leakBound(T r)` puis `public <T extends java.util.Map<String, ProjectRegistry>> T nested()`
- **WHEN** `python scripts/architecture/test_check_module_boundaries_ratchets.py` est exécuté
- **THEN** chaque variante est refusée en A11, message nommant la méthode et `registry.ProjectRegistry`

#### Scenario: Import joker d'un package du moteur
- **GIVEN** l'auto-test du garde, avec `import com.minos.registry.*;` dans `MinosApplication`
- **WHEN** le même auto-test est exécuté
- **THEN** le cas constate le refus en A11 et le message qui nomme `com.minos.registry`

#### Scenario: Méthode générique sans type du moteur
- **GIVEN** l'auto-test du garde, avec `public <T extends Comparable<T>> java.util.List<T> sorted(java.util.List<T> values)`
- **WHEN** le même auto-test est exécuté
- **THEN** la règle des paramètres et celle des retours réussissent

#### Scenario: Façade au HEAD
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté sur le dépôt
- **THEN** il se termine avec le code 0

### Requirement: Le dossier des frontières n'écrit aucun décompte de la politique
`docs/architecture/diagrams/capacites/frontieres-de-modules.md` SHALL ne contenir aucun nombre en chiffres suivi d'un module, d'une dépendance, d'une découverte, d'un package ou d'un cycle. Aucune preuve d'écart SHALL nommer une méthode que `MinosApplication` ne déclare plus. `test_check_module_boundaries_documents.py` SHALL échouer en nommant chaque ligne fautive.

#### Scenario: Dossier sans décompte
- **GIVEN** le dépôt après correction
- **WHEN** `python scripts/architecture/test_check_module_boundaries_documents.py` est exécuté
- **THEN** l'auto-test se termine avec le code 0

#### Scenario: Décompte réintroduit
- **GIVEN** une copie du dépôt où une ligne du dossier écrit « minos-app dépend des 12 autres modules »
- **WHEN** `python scripts/architecture/test_check_module_boundaries_documents.py` est exécuté dans la copie
- **THEN** l'auto-test se termine avec le code 1 et nomme la ligne

#### Scenario: Preuve d'écart sur des accesseurs retirés
- **GIVEN** le dépôt après correction
- **WHEN** `git grep -nE "gitIntelligence\(\)|hostedControlPlaneService\(\)" -- docs/architecture/diagrams/capacites/frontieres-de-modules.md` est exécuté
- **THEN** la commande ne rend aucune ligne et se termine avec le code 1

### Requirement: L'écriture d'une déclaration de la façade ne contourne pas A11
A11 SHALL lire une méthode ou un constructeur public de `MinosApplication` quels que soient les annotations placées dans sa déclaration, la casse de son nom et les retours à la ligne de son en-tête, nom qualifié compris. Une déclaration publique suivie d'une parenthèse dont A11 ne lit pas le nom SHALL faire échouer le garde.

#### Scenario: Annotation à l'intérieur de la déclaration
- **GIVEN** l'auto-test du garde, avec `public @Deprecated ProjectRegistry x()`, une annotation à arguments imbriqués, entre deux modificateurs, après les paramètres de type ou dans un nom qualifié
- **WHEN** `python scripts/architecture/test_check_module_boundaries_ratchets.py` est exécuté
- **THEN** chaque variante est refusée en A11, message nommant la méthode et `registry.ProjectRegistry`

#### Scenario: Nom de méthode qui n'est pas en minuscules
- **GIVEN** l'auto-test du garde, avec `public ProjectRegistry Leak()`, `public void Leak(ProjectRegistry r)` et `public ProjectRegistry $leak()`
- **WHEN** le même auto-test est exécuté
- **THEN** chaque variante est refusée en A11 et nomme la méthode

#### Scenario: En-tête sur plusieurs lignes
- **GIVEN** l'auto-test du garde, avec un retour `Map<String,` puis `ProjectRegistry>` à la ligne suivante, puis un nom qualifié coupé avant son dernier segment
- **WHEN** le même auto-test est exécuté
- **THEN** chaque variante est refusée en A11 et nomme `registry.ProjectRegistry`

#### Scenario: Déclarations sans type du moteur
- **GIVEN** l'auto-test du garde, avec des membres annotés, un champ initialisé par un appel et un `record` imbriqué non public
- **WHEN** le même auto-test est exécuté
- **THEN** les règles de la façade réussissent

#### Scenario: Déclaration illisible
- **GIVEN** l'auto-test du garde, avec `public ProjectRegistry (`
- **WHEN** le même auto-test est exécuté
- **THEN** le cas constate l'échec du garde, message `A11 unreadable MinosApplication member`

#### Scenario: Façade au HEAD
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté sur le dépôt
- **THEN** il se termine avec le code 0

### Requirement: Un import qui passe par un type du moteur ne contourne pas A11
Dans `MinosApplication`, un import joker, statique ou non, dont la cible est un type de `minos-engine` ou un type imbriqué d'un tel type SHALL faire échouer A11 en nommant la cible. Un type importé par `import static` depuis un type de `minos-engine` SHALL compter comme un type du moteur quand une signature publique de la façade le nomme.

#### Scenario: Joker sur les membres d'un type du moteur
- **GIVEN** l'auto-test du garde, avec `import com.minos.orchestration.IndexingRuntimePorts.*;`, puis sa forme `import static`
- **WHEN** `python scripts/architecture/test_check_module_boundaries_ratchets.py` est exécuté
- **THEN** chaque forme est refusée en A11, message nommant `com.minos.orchestration.IndexingRuntimePorts.*`

#### Scenario: Import statique d'un type imbriqué
- **GIVEN** l'auto-test du garde, avec `import static com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;` et `public IndexerExecutor x()`
- **WHEN** le même auto-test est exécuté
- **THEN** la méthode est refusée en A11, message nommant `x` et `orchestration.IndexingRuntimePorts`

#### Scenario: Import statique hors du moteur
- **GIVEN** l'auto-test du garde, avec `import static` d'une méthode d'un type de `minos-domain` et un joker sur les membres d'un type du JDK
- **WHEN** le même auto-test est exécuté
- **THEN** la règle réussit

### Requirement: La façade ne rend pas la racine de composition par une méthode statique
Une méthode statique publique de `MinosApplication` dont le type de retour ou une borne de paramètre de type nomme `MinosApplicationComposer` SHALL faire échouer A11 en nommant la méthode, qu'elle soit générique ou non.

#### Scenario: Racine de composition rendue par une méthode statique
- **GIVEN** l'auto-test du garde, avec `public static MinosApplicationComposer root()`, `public static <T> MinosApplicationComposer root()` et `public static <T extends MinosApplicationComposer> T root()`
- **WHEN** `python scripts/architecture/test_check_module_boundaries_ratchets.py` est exécuté
- **THEN** chaque variante est refusée en A11, message nommant `root` et la racine de composition

### Requirement: La façade n'a aucun champ public typé moteur
`MinosApplication` SHALL NOT déclarer de champ public, statique ou non, dont le type déclaré nomme un type de `minos-engine` ou `MinosApplicationComposer`. A11 SHALL échouer en nommant le champ et le type.

#### Scenario: Champ typé moteur refusé
- **GIVEN** l'auto-test du garde, avec `public ProjectRegistry registry;`, un champ `static final`, `java.util.List<ProjectRegistry>`, puis `public ProjectRegistry[] a, b;`
- **WHEN** `python scripts/architecture/test_check_module_boundaries_ratchets.py` est exécuté
- **THEN** chaque variante est refusée en A11, message nommant le champ et `registry.ProjectRegistry`

#### Scenario: Champ typé racine de composition refusé
- **GIVEN** l'auto-test du garde, avec `public MinosApplicationComposer root;`
- **WHEN** le même auto-test est exécuté
- **THEN** le champ est refusé en A11, message nommant `root` et `MinosApplicationComposer`

#### Scenario: Champ sans type du moteur ou non public
- **GIVEN** l'auto-test du garde, avec `public static final String NAME = "x";` et `private ProjectRegistry registry;`
- **WHEN** le même auto-test est exécuté
- **THEN** la règle réussit

#### Scenario: Champs de la façade au HEAD
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté sur le dépôt
- **THEN** il se termine avec le code 0

### Requirement: Le `Builder` est le seul type imbriqué public de la façade
Un type imbriqué public de `MinosApplication` autre que `Builder` (classe, `record`, interface, enum ou annotation) SHALL faire échouer A11 en le nommant, qu'il nomme ou non un type du moteur. Un type imbriqué non public SHALL rester accepté.

#### Scenario: Type imbriqué public refusé
- **GIVEN** l'auto-test du garde, avec tour à tour un `public record` générique ou non, une `public static final class`, une `public interface` dont les méthodes sont implicitement publiques, un `public enum` et une `public @interface`
- **WHEN** `python scripts/architecture/test_check_module_boundaries_ratchets.py` est exécuté
- **THEN** chaque variante est refusée en A11, message nommant le type imbriqué

#### Scenario: Builder et types non publics acceptés
- **GIVEN** l'auto-test du garde, avec `public static final class Builder` portant une méthode typée moteur, un `record` privé au package et un `private record`
- **WHEN** le même auto-test est exécuté
- **THEN** la règle réussit

### Requirement: Un import est lu quelle que soit sa position dans la ligne
Les règles de `check-module-boundaries.py` qui lisent les déclarations d'import SHALL lire chacune d'elles où qu'elle soit dans sa ligne : à son début, ou à la suite d'un autre import, de la déclaration de package, d'un point-virgule seul ou d'un commentaire. Un `import` écrit dans un commentaire ou dans un littéral SHALL rester ignoré.

#### Scenario: Second import d'une ligne dans la façade
- **GIVEN** l'auto-test des cliquets, avec `MinosApplication` qui écrit sur une même ligne `import java.util.List;` puis un import simple, joker ou statique qui atteint un type de `minos-engine`
- **WHEN** `python scripts/architecture/test_check_module_boundaries_ratchets.py` est exécuté
- **THEN** chaque forme est refusée en A11

#### Scenario: Joker de package en second sur sa ligne
- **GIVEN** les auto-tests, avec ce joker dans une surface (A11), un adaptateur (A13), une source qui ne doit nommer aucun adaptateur (A2), un module hors de `minos-runtime-local` (A10), `minos-bootstrap` (R1) et un package du stockage
- **WHEN** les auto-tests des cliquets et des adaptateurs sont exécutés
- **THEN** chaque règle refuse et nomme le package ou le fichier

#### Scenario: Cycle et type référencé par un second import
- **GIVEN** l'auto-test des packages, avec un cycle fermé par un joker en second sur sa ligne, puis un type public nommé par le seul joker de son package, en second sur sa ligne
- **WHEN** `python scripts/architecture/test_check_module_boundaries_packages.py` est exécuté
- **THEN** le cycle est refusé en A8
- **AND** A15 ne déclare pas le type sans appelant

#### Scenario: Autres positions
- **GIVEN** l'auto-test des cliquets, avec un import de façade placé après la déclaration de package, après un point-virgule seul ou après un commentaire de bloc fermé sur la ligne d'un autre import
- **WHEN** `python scripts/architecture/test_check_module_boundaries_ratchets.py` est exécuté
- **THEN** chaque forme est refusée en A11

#### Scenario: Import écrit dans un commentaire ou un littéral
- **GIVEN** l'auto-test des cliquets, avec le même texte d'import dans un commentaire de ligne, un commentaire de bloc, une chaîne et un bloc de texte
- **WHEN** le même auto-test est exécuté
- **THEN** la règle réussit

#### Scenario: Dépôt au HEAD
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté sur le dépôt
- **THEN** il se termine avec le code 0

### Requirement: Un joker sur un type du module visé échoue en A11 et en A13
Dans les sources de production d'une surface, d'un module de contrat (A11) ou d'un adaptateur (A13), un import joker non statique dont la cible est un type du module visé, ou un type imbriqué d'un tel type, SHALL faire échouer le garde en nommant le fichier et la cible, que ce type soit listé ou non.

#### Scenario: Joker sur un type non listé
- **GIVEN** l'auto-test des cliquets, avec `import com.minos.orchestration.IndexingRuntimePorts.*;` dans une surface puis dans `minos-nexus`, sur le type ou sur un de ses types imbriqués
- **WHEN** `python scripts/architecture/test_check_module_boundaries_ratchets.py` est exécuté
- **THEN** chaque forme est refusée en A11, message nommant le fichier et la cible suivie de `.*`
- **AND** le type est aussi signalé absent de la table

#### Scenario: Joker sur un type listé
- **GIVEN** le même auto-test, avec la table qui liste le type pour la surface
- **WHEN** l'auto-test est exécuté
- **THEN** le joker est refusé
- **AND** le message ne demande pas de retirer l'entrée

#### Scenario: Joker sur un type d'un autre adaptateur
- **GIVEN** le même auto-test, avec ce joker entre deux adaptateurs
- **WHEN** l'auto-test est exécuté
- **THEN** il est refusé en A13, message nommant le couple, le fichier et la cible

#### Scenario: Joker hors du module visé et joker statique
- **GIVEN** le même auto-test, avec un joker sur les membres d'un type du JDK ou de `minos-domain`, puis un `import static` joker sur un type listé
- **WHEN** l'auto-test est exécuté
- **THEN** la règle réussit et compte le fichier de l'import statique comme un usage du type listé

### Requirement: Un joker sur une classe d'adaptateur échoue en A2
Hors des adaptateurs et de `minos-bootstrap`, une source de production qui importe par joker les membres d'une classe d'adaptateur, ou d'un type imbriqué d'une telle classe, SHALL faire échouer A2 en nommant le fichier, la classe et son module.

#### Scenario: Joker sur les membres d'une classe d'adaptateur
- **GIVEN** l'auto-test des adaptateurs, avec ce joker dans une source de `minos-cli`, sur la classe puis sur un de ses types imbriqués
- **WHEN** `python scripts/architecture/test_check_module_boundaries_adapters.py` est exécuté
- **THEN** chaque forme est refusée en A2, message nommant le fichier, la classe et son module

#### Scenario: Joker sur les membres d'une classe hors des adaptateurs
- **GIVEN** le même auto-test, avec un joker sur les membres d'un type de `minos-domain`
- **WHEN** l'auto-test est exécuté
- **THEN** la règle réussit

### Requirement: Un nom coupé autour d'un point est lu en entier
Les règles de `check-module-boundaries.py` qui lisent des noms, importés ou qualifiés, SHALL lire un nom dont les points sont entourés de blancs, de retours à la ligne ou de commentaires comme le même nom écrit d'un seul tenant, le `.*` d'un joker compris. Un tel texte dans un commentaire ou un littéral SHALL rester ignoré.

#### Scenario: Import coupé
- **GIVEN** les auto-tests, avec un import coupé autour de ses points dans une surface (A11), un adaptateur (A13), une source qui ne doit nommer aucun adaptateur (A2), un module hors de `minos-runtime-local` (A10), `minos-bootstrap` (R1) et un package du stockage
- **WHEN** les auto-tests des cliquets et des adaptateurs sont exécutés
- **THEN** chaque règle refuse et nomme la cible d'un seul tenant

#### Scenario: Nom qualifié coupé dans le code
- **GIVEN** les mêmes auto-tests, avec le nom qualifié coupé écrit dans le code sans import
- **WHEN** les mêmes auto-tests sont exécutés
- **THEN** chaque règle refuse et nomme le type d'un seul tenant

#### Scenario: Joker coupé
- **GIVEN** les mêmes auto-tests, avec un import joker dont le `.*` est coupé, dans une surface, un adaptateur et la façade `MinosApplication`
- **WHEN** les mêmes auto-tests sont exécutés
- **THEN** chaque forme est refusée comme le joker écrit d'un seul tenant

#### Scenario: Cycle et type référencé
- **GIVEN** l'auto-test des packages, avec un cycle fermé par un nom coupé, puis un type public nommé par un seul nom coupé
- **WHEN** `python scripts/architecture/test_check_module_boundaries_packages.py` est exécuté
- **THEN** le cycle est refusé en A8
- **AND** A15 ne déclare pas le type sans appelant

#### Scenario: Commentaire, littéral et ellipse
- **GIVEN** l'auto-test des cliquets, avec un nom coupé dans un commentaire et dans une chaîne, puis un paramètre à nombre variable `com.minos.hosted . HostedRole ... roles`
- **WHEN** `python scripts/architecture/test_check_module_boundaries_ratchets.py` est exécuté
- **THEN** le commentaire et la chaîne n'ajoutent aucun usage
- **AND** le paramètre est refusé en A11 en nommant `com.minos.hosted.HostedRole`

#### Scenario: Dépôt au HEAD
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté sur le dépôt
- **THEN** il se termine avec le code 0

### Requirement: Un échappement Unicode ne cache rien aux règles de frontières
Les règles de `check-module-boundaries.py` qui lisent un source Java SHALL le lire échappements Unicode décodés, comme le compilateur, avant d'écarter commentaires et littéraux. Une barre oblique inverse elle-même échappée par une autre SHALL ne pas ouvrir d'échappement.

#### Scenario: Nom ou mot écrit avec un échappement
- **GIVEN** les auto-tests, avec un point ou une lettre écrits en échappement dans un nom que A2 refuse, puis dans un mot que A12, A16 et A18 comptent
- **WHEN** les auto-tests des cliquets et des adaptateurs sont exécutés
- **THEN** chaque règle refuse comme pour l'écriture directe

#### Scenario: Échappement qui termine un commentaire de ligne
- **GIVEN** l'auto-test des adaptateurs, avec un nom d'adaptateur placé dans un commentaire après un échappement de saut de ligne, puis de retour chariot
- **WHEN** `python scripts/architecture/test_check_module_boundaries_adapters.py` est exécuté
- **THEN** le nom est refusé en A2

#### Scenario: Barre oblique inverse échappée
- **GIVEN** le même auto-test, avec le même échappement précédé d'une seconde barre oblique inverse
- **WHEN** l'auto-test est exécuté
- **THEN** la règle réussit

### Requirement: Une annotation dans un nom qualifié ne le cache pas
Les règles de `check-module-boundaries.py` qui lisent des noms SHALL lire un nom qualifié interrompu par une ou plusieurs annotations de type comme le nom sans elles. Le type de chaque annotation et ses arguments SHALL rester lus.

#### Scenario: Nom annoté
- **GIVEN** les auto-tests, avec une annotation, avec ou sans arguments, puis deux annotations, devant la dernière partie d'un nom qualifié (A2, A11, A13, A8)
- **WHEN** les auto-tests des cliquets, des adaptateurs et des packages sont exécutés
- **THEN** chaque règle refuse ; A2, A11 et A13 nomment le type d'un seul tenant, A8 les deux packages du cycle
- **AND** des arguments d'annotation non fermés font échouer le garde en nommant le fichier

#### Scenario: Type nommé par l'annotation
- **GIVEN** l'auto-test des cliquets, avec un type du moteur nommé par une annotation placée dans un autre nom, comme annotation puis comme argument
- **WHEN** `python scripts/architecture/test_check_module_boundaries_ratchets.py` est exécuté
- **THEN** ce type est refusé en A11

### Requirement: A19 lit le joker sur une API d'infrastructure
Dans `minos-application`, A19 SHALL compter comme nommant une API d'infrastructure un source qui importe par joker `java.net.http`, `javax.tools`, `com.sun.source` ou l'un de ses sous-packages, et un source qui importe `java.net.*` et nomme `HttpURLConnection`.

#### Scenario: Joker sur un package d'infrastructure
- **GIVEN** l'auto-test des adaptateurs, avec chacun de ces jokers dans un source absent de la liste
- **WHEN** `python scripts/architecture/test_check_module_boundaries_adapters.py` est exécuté
- **THEN** chaque forme est refusée en A19

#### Scenario: Joker de java.net
- **GIVEN** le même auto-test, avec `import java.net.*;` dans un source qui nomme `HttpURLConnection`, puis dans un source qui ne nomme que `URI`
- **WHEN** l'auto-test est exécuté
- **THEN** le premier est refusé en A19
- **AND** le second est accepté

#### Scenario: Dépôt au HEAD
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté sur le dépôt
- **THEN** il se termine avec le code 0

## Documentation liée

- Dossier durable (vues, traçabilité, écarts) : [frontieres-de-modules](../../../docs/architecture/diagrams/capacites/frontieres-de-modules.md)
