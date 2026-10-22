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
`minos-storage-postgresql` SHALL répartir ses classes entre `support`, `snapshot`, `project` et `semantic`, sa racine ne gardant que le backend et son fournisseur. Les dépendances SHALL aller vers `support` et de la racine vers les autres, sans cycle. Le nom du fournisseur déclaré dans `META-INF/services` SHALL rester inchangé.

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
Le garde de frontières SHALL tenir, pour chaque surface (`minos-cli`, `minos-api`, `minos-mcp`, `minos-nexus`), la liste des types de `minos-engine` qu'elle nomme, par import ou nom qualifié. Un type hors liste SHALL faire échouer le garde. Une entrée que la surface ne nomme plus, ou qui désigne un type inexistant, SHALL le faire échouer aussi.

#### Scenario: Nouveau type du moteur importé par la CLI
- **GIVEN** une copie du dépôt où un fichier de `minos-cli` importe un type de `minos-engine` absent de la liste
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté sur cette copie
- **THEN** le garde échoue en nommant la surface, le fichier et le type

#### Scenario: Entrée devenue inutile
- **GIVEN** une copie du dépôt où le dernier usage d'un type listé a été retiré sans retirer l'entrée
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en nommant l'entrée à retirer

#### Scenario: Dépôt réel
- **GIVEN** le dépôt
- **WHEN** le garde est exécuté
- **THEN** il réussit et affiche le nombre de types listés par surface

### Requirement: Le lancement de processus hors du runtime local est tenu par liste fermée
Dans `minos-engine`, `minos-application` et `minos-domain`, une classe qui lance un processus (`ProcessBuilder`, `Runtime.exec`) SHALL figurer dans une liste fermée du garde de frontières. Une classe hors liste SHALL faire échouer le garde ; une entrée qui ne lance plus de processus aussi.

#### Scenario: Second site de lancement dans le moteur
- **GIVEN** une copie du dépôt où une classe de `minos-engine` hors liste instancie `ProcessBuilder`
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en nommant la classe

#### Scenario: Site existant
- **GIVEN** le dépôt, où seule la classe listée lance un processus
- **WHEN** le garde est exécuté
- **THEN** il réussit

### Requirement: Les types d'un adaptateur nommés par un autre adaptateur le sont par liste fermée
Le garde de frontières SHALL tenir, par couple d'adaptateurs, la liste des types de l'un que l'autre nomme, par import ou nom qualifié. Un type hors liste SHALL faire échouer le garde, ainsi qu'une entrée devenue inutile.

#### Scenario: Nouveau type du backend local nommé par PostgreSQL
- **GIVEN** une copie du dépôt où `minos-storage-postgresql` nomme un type de `minos-storage-local` absent de la liste
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en nommant les deux modules et le type

#### Scenario: Usage en nom qualifié sans import
- **GIVEN** une copie du dépôt où un type hors liste est utilisé par son nom qualifié complet
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue comme pour un import

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
