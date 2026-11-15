## ADDED Requirements

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
Le garde de frontières SHALL tenir la liste fermée des classes d'adaptateur que `minos-application` embarque, chacune avec sa justification. Aucune source de production de `minos-application` hors de cette liste SHALL nommer une de ces classes, par import, nom qualifié ou nom simple du même package. La liste SHALL seulement rétrécir.

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
