## ADDED Requirements

### Requirement: Le MCP ne rend comme erreur d'argument que l'erreur d'usage explicite
Un outil MCP SHALL rendre un message d'erreur d'argument seulement quand l'échec est une erreur d'usage explicite, levée par la validation d'une entrée de l'appelant. Toute autre erreur d'argument levée pendant l'exécution, sous-classes du JDK comprises, SHALL être rendue par l'erreur générique d'exécution d'outil et journalisée comme échec interne.

#### Scenario: Entrée invalide de l'appelant
- **GIVEN** un outil appelé avec `limit` négatif
- **WHEN** la validation le refuse (`./mvnw -B -ntp -pl minos-mcp -am test "-Dtest=MinosMcpUsageErrorTest" "-Dsurefire.failIfNoSpecifiedTests=false"`)
- **THEN** la réponse est une erreur qui nomme l'argument à corriger
- **AND** aucune ligne de diagnostic d'échec interne n'est écrite

#### Scenario: Précondition violée dans un service
- **GIVEN** un outil dont le service lève une `IllegalArgumentException` qui n'est pas une erreur d'usage explicite, de message `limit must be positive`
- **WHEN** l'outil est appelé avec des arguments valides
- **THEN** la réponse est l'erreur générique d'exécution d'outil et ne contient pas ce message
- **AND** la ligne de diagnostic nomme l'outil et la classe de l'exception

#### Scenario: Sous-classe du JDK
- **GIVEN** un service qui lève `NumberFormatException` ou `InvalidPathException` sur une donnée lue du stockage
- **WHEN** l'outil est appelé
- **THEN** la réponse est l'erreur générique d'exécution d'outil et l'échec est journalisé

#### Scenario: Erreur d'usage au message sensible
- **GIVEN** une erreur d'usage explicite dont le message contient un chemin absolu
- **WHEN** l'outil est appelé
- **THEN** la réponse est l'erreur générique d'exécution d'outil, sans ce chemin

#### Scenario: Genre de symbole inconnu
- **GIVEN** un outil de recherche de symboles appelé avec un genre qui n'existe pas
- **WHEN** la validation le refuse
- **THEN** la réponse est une erreur d'usage qui nomme l'argument `kind`
- **AND** elle ne recopie pas la valeur envoyée

### Requirement: L'API Java ne classe en requête invalide que l'erreur d'usage explicite
Un appel de l'API Java SHALL rendre une `MinosApiException` de requête invalide seulement pour une erreur d'usage explicite, document inconnu compris, ou une référence de projet non résolue. Toute autre erreur d'argument levée pendant l'appel SHALL être rendue comme échec d'exécution, sans cause, avec un message redacté, et journalisée comme échec interne.

#### Scenario: Paramètre invalide de l'appelant
- **GIVEN** un appel dont un paramètre requis est `null`
- **WHEN** la façade le valide (`./mvnw -B -ntp -pl minos-api -am test "-Dtest=MinosApiUsageErrorTest" "-Dsurefire.failIfNoSpecifiedTests=false"`)
- **THEN** l'appelant reçoit une `MinosApiException` de requête invalide qui nomme le paramètre

#### Scenario: Erreur d'argument interne
- **GIVEN** un service qui lève une `IllegalArgumentException` non explicite pendant un appel aux paramètres valides
- **WHEN** l'appel échoue
- **THEN** l'appelant reçoit une `MinosApiException` d'échec d'exécution, sans cause
- **AND** une ligne de diagnostic nomme la classe de l'exception

#### Scenario: Référence de projet inconnue
- **GIVEN** un nom de projet absent du registre
- **WHEN** un appel le désigne
- **THEN** l'appelant reçoit la requête invalide et le message public déjà partagé avec la CLI et le MCP

#### Scenario: Document inconnu de l'exploration sémantique
- **GIVEN** un index sémantique qui ne contient pas la clé demandée
- **WHEN** le détail ou les voisins de cette clé sont demandés par l'API d'exploration
- **THEN** l'appelant reçoit une `MinosApiException` de requête invalide, comme avant
- **AND** aucune ligne de diagnostic d'échec interne n'est écrite

### Requirement: Une indisponibilité non classée de l'API Java est tracée
Quand un appel de l'API Java échoue par une `IllegalStateException` qui n'est ni classée par la façade ni un refus attendu du service (index remplacé pendant la lecture, préparation de projection refusée), le système SHALL rendre l'indisponibilité comme avant et SHALL écrire une ligne de diagnostic d'échec interne qui nomme la classe et son origine, sans message.

#### Scenario: État illégal levé par un service
- **GIVEN** un service qui lève une `IllegalStateException` pendant un appel et un enregistreur capturé
- **WHEN** l'appel échoue
- **THEN** l'appelant reçoit une `MinosApiException` d'indisponibilité sans cause
- **AND** l'enregistreur a reçu une ligne avec la classe de l'exception et son origine, sans son message

#### Scenario: Refus attendu du service
- **GIVEN** un index sémantique remplacé pendant la lecture d'une page, ou une préparation de projection refusée
- **WHEN** l'appel échoue
- **THEN** l'appelant reçoit l'indisponibilité avec le message public du refus
- **AND** aucune ligne de diagnostic d'échec interne n'est écrite

#### Scenario: Indisponibilité classée par la façade
- **GIVEN** une opération que l'implémentation ne fournit pas et qui rend elle-même l'indisponibilité
- **WHEN** elle est appelée
- **THEN** aucune ligne de diagnostic d'échec interne n'est écrite

### Requirement: Les erreurs d'usage de chaque surface sont tenues par une table testée
Pour le MCP et l'API Java, un test SHALL énumérer les entrées invalides documentées de la surface et vérifier que chacune rend une erreur d'usage au message actionnable, et non l'erreur opaque. Une validation atteignable depuis la surface qui lèverait une erreur d'argument non explicite SHALL faire échouer ce test.

#### Scenario: Table du MCP
- **GIVEN** la table des entrées invalides des outils publiés (borne hors plage, genre de symbole inconnu, identifiant mal formé, argument inconnu) et celle de l'API Java (paramètre nul, borne hors plage, curseur invalide, document inconnu)
- **WHEN** chaque entrée est jouée (`./mvnw -B -ntp -pl minos-mcp -am test "-Dtest=MinosMcpUsageErrorTest" "-Dsurefire.failIfNoSpecifiedTests=false"`)
- **THEN** aucune réponse ne vaut l'erreur générique d'exécution d'outil

#### Scenario: Validation oubliée
- **GIVEN** une validation de requête restée en `IllegalArgumentException` simple sur un chemin de la table
- **WHEN** le test est joué
- **THEN** il échoue en nommant l'entrée
