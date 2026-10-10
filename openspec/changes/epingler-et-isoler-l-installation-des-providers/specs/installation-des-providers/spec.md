## ADDED Requirements

### Requirement: Une commande d'installation ou de sondage ne reçoit pas l'environnement complet
Toute commande externe lancée par `minos tools install` ou par une sonde de toolchain (`coursier`, `npm`, `dotnet`, `go`) SHALL recevoir un environnement construit depuis une liste d'autorisation, jamais l'environnement hérité de MINOS. Le répertoire personnel, les variables de proxy et d'autorité de certification et les caches nécessaires SHALL y figurer.

#### Scenario: Secrets de MINOS absents de la commande
- **GIVEN** un processus MINOS dont l'environnement contient `MINOS_TEAM_KEY_ALPHA`, `MINOS_TEAM_TOKEN`, `GITHUB_TOKEN` et `NPM_TOKEN`
- **WHEN** une commande d'installation est lancée par chacun des trois gestionnaires de providers
- **THEN** l'environnement observé dans la commande ne contient aucune de ces quatre variables

#### Scenario: Variables nécessaires conservées
- **GIVEN** un environnement contenant `PATH`, `HOME` (Linux), `HTTPS_PROXY`, `NO_PROXY` et `COURSIER_CACHE`
- **WHEN** une commande d'installation est lancée
- **THEN** ces cinq variables sont présentes avec leur valeur

#### Scenario: Sonde de toolchain
- **WHEN** `dotnet --version` ou `go version` est lancé pour sonder une toolchain
- **THEN** l'environnement de la sonde est celui de la liste d'autorisation, sans variable à motif de secret

### Requirement: Un motif de secret l'emporte sur toute liste d'autorisation
Un nom de variable correspondant à `MINOS_*`, `*TOKEN*`, `*SECRET*`, `*PASSWORD*`, `*KEY*`, `*CREDENTIAL*`, `GITHUB_*` ou `NPM_*` SHALL être retiré de l'environnement d'une commande d'installation, même s'il figure dans la liste d'autorisation ou parmi les variables déclarées par l'appelant.

#### Scenario: Variable déclarée mais à motif de secret
- **GIVEN** un appelant qui déclare `MINOS_REMOTE_TOKEN_X` parmi les variables à transmettre
- **WHEN** la façade construit l'environnement
- **THEN** cette variable est absente du résultat

#### Scenario: Variable ordinaire déclarée
- **GIVEN** un appelant qui déclare `GOBIN` et `GOPROXY`
- **WHEN** la façade construit l'environnement
- **THEN** ces deux variables sont présentes

### Requirement: Le classpath de scip-java est vérifié jar par jar avant tout usage
Après résolution par `coursier fetch --classpath`, l'installation de scip-java SHALL comparer l'ensemble des jars obtenus au verrou du catalogue (noms et SHA-256), SHALL refuser tout jar absent, en trop ou d'empreinte différente, et SHALL supprimer le résultat partiel avant de signaler l'échec. Elle SHALL NOT écrire `classpath.txt` avant la fin de la vérification.

#### Scenario: Classpath conforme
- **GIVEN** un faux `coursier` qui produit exactement les jars du verrou de test
- **WHEN** l'installation de scip-java est exécutée
- **THEN** `classpath.txt` est écrit et l'état du provider est prêt

#### Scenario: Jar modifié
- **GIVEN** un classpath dont un jar a un octet changé
- **WHEN** l'installation est exécutée
- **THEN** elle échoue en nommant le jar (nom de fichier sans chemin absolu), aucun `classpath.txt` n'existe et le répertoire partiel est supprimé

#### Scenario: Jar en trop ou manquant
- **GIVEN** un classpath qui contient un jar absent du verrou, puis un classpath auquel il en manque un
- **WHEN** l'installation est exécutée dans chaque cas
- **THEN** elle échoue dans les deux cas, avec un message distinct pour l'ajout et pour le manque

#### Scenario: Verrou absent
- **GIVEN** une ressource de verrou absente ou illisible
- **WHEN** l'installation est exécutée
- **THEN** elle échoue (fail-closed) sans lancer `coursier`

### Requirement: L'installation n'exécute pas scip-java pour en lire la version
L'installation de scip-java SHALL NOT lancer `coursier launch` ni la classe principale de scip-java. La version installée SHALL être celle du verrou dont les empreintes ont été vérifiées.

#### Scenario: Commandes lancées par l'installation
- **GIVEN** un faux `coursier` qui enregistre ses arguments
- **WHEN** l'installation de scip-java est exécutée
- **THEN** aucun enregistrement ne contient `launch` ni `--main`, et la seule sous-commande de résolution utilisée est `fetch`

### Requirement: Le verrou est tenu par le catalogue et contrôlé en CI
Le catalogue des outils embarqués SHALL déclarer le verrou du classpath de scip-java avec la version de scip-java, et `scripts/quality/check-tools-manifest.py` SHALL échouer quand le verrou ne correspond pas à la version du catalogue.

#### Scenario: Version du catalogue et verrou concordants
- **WHEN** `python scripts/quality/check-tools-manifest.py` est exécuté sur le dépôt corrigé
- **THEN** il réussit

#### Scenario: Version du catalogue changée sans régénérer le verrou
- **GIVEN** une copie du catalogue dont la version de scip-java est modifiée
- **WHEN** le gate est exécuté
- **THEN** il échoue en nommant le verrou à régénérer
