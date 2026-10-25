## MODIFIED Requirements

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

## ADDED Requirements

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
