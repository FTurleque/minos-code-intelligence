## MODIFIED Requirements

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

## ADDED Requirements

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
