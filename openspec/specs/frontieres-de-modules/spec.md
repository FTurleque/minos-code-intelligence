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
Aucune surface (`minos-cli`, `minos-api`, `minos-mcp`, `minos-nexus`) SHALL NOT construire `IndexingLifecycleService` ni enchaîner elle-même découverte, négociation, planification et exécution. Ces étapes SHALL être assemblées dans `minos-application`, qui SHALL NOT nommer de classe d'adaptateur pour le faire.

#### Scenario: Construction du cycle hors des surfaces
- **GIVEN** le dépôt après le changement
- **WHEN** on recherche `new IndexingLifecycleService` dans les sources de production des quatre surfaces
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
Tout appel `ServiceLoader.load` des sources de production du reactor SHALL passer un chargeur de classes explicite. Le garde de frontières SHALL échouer sur un appel à un seul argument, en nommant le fichier et la ligne.

#### Scenario: Appel sans chargeur
- **GIVEN** une copie du dépôt où une classe de production appelle `ServiceLoader.load(X.class)`
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté sur cette copie
- **THEN** le garde échoue en nommant le fichier et la ligne

#### Scenario: Dépôt réel
- **GIVEN** le dépôt après le changement
- **WHEN** le garde est exécuté
- **THEN** il réussit et les quatre découvertes de production passent un chargeur

### Requirement: Les types du moteur nommés par une surface le sont par liste fermée
Le garde de frontières SHALL tenir, pour chaque surface (`minos-cli`, `minos-api`, `minos-mcp`, `minos-nexus`), les types de `minos-engine` qu'elle utilise, chacun avec le nombre de fichiers de production qui l'utilisent. Un type hors liste SHALL faire échouer le garde, ainsi qu'un compteur différent du nombre de fichiers observé, une entrée inutilisée ou une entrée désignant un type inexistant.

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
- **THEN** il réussit et affiche, par surface, le nombre de types listés et de fichiers qui les utilisent

### Requirement: Le lancement de processus hors du runtime local est tenu par liste fermée
Dans `minos-engine`, `minos-application` et `minos-domain`, une classe qui lance un processus SHALL figurer dans une liste fermée du garde avec son nombre de sites de lancement (`new ProcessBuilder`, `ProcessBuilder::new`, `ProcessBuilder.startPipeline`, `Runtime.getRuntime().exec`). Sur le dépôt, la liste SHALL être vide. Une classe hors liste, un nombre de sites différent ou une entrée qui ne lance plus rien SHALL faire échouer le garde.

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
- **GIVEN** une copie du dépôt où un fichier de `minos-api` appelle `app.snapshotStore()` sans importer `CodeKnowledgeSnapshotStore`, absent de la liste de l'API
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en nommant la surface, le fichier et le type rendu

#### Scenario: Import remplacé par var
- **GIVEN** une copie du dépôt où un fichier qui importait `GitIntelligence` écrit `var g = app.gitIntelligence()` sans l'import
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il réussit avec le même compteur : le fichier utilise toujours le type

#### Scenario: Méthode homonyme d'arité différente
- **GIVEN** une copie du dépôt où une surface appelle `builder.gitIntelligence(supplier)`, à un argument
- **WHEN** le garde est exécuté sur cette copie
- **THEN** cet appel n'est pas compté comme un usage de `GitIntelligence`

### Requirement: Les types du moteur utilisés par le bytecode d'une surface le sont par liste fermée
Le test `ModuleArchitectureTest` SHALL tenir, par surface, les types de premier niveau de `minos-engine` dont dépendent ses classes compilées, chacun avec le nombre de classes de premier niveau qui en dépendent. Un type hors table, un compteur différent ou une entrée inutilisée SHALL faire échouer le test.

#### Scenario: Appel chaîné sur un résultat du moteur
- **GIVEN** une classe de `minos-cli` qui appelle une méthode d'un type de `minos-engine` absent de la table, sans le nommer
- **WHEN** `ModuleArchitectureTest` est exécuté
- **THEN** il échoue en nommant la surface, le type, le compteur attendu et les classes

#### Scenario: Usage retiré sans mettre la table à jour
- **GIVEN** une table du test qui liste un type dont plus aucune classe de la surface ne dépend
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
