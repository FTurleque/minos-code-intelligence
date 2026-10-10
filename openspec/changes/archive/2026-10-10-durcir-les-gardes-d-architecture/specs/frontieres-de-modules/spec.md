# Spec Delta

## ADDED Requirements

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
