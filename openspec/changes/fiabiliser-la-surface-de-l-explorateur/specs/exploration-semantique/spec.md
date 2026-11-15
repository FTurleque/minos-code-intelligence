## ADDED Requirements

### Requirement: Un échec interne de l'explorateur laisse une ligne de diagnostic avec sa cause racine
Pour tout échec interne d'une requête, la surface SHALL écrire sur stderr une ligne qui porte le motif de la route, la classe de l'exception, la classe de la cause racine, son origine dans le code MINOS et une référence, et SHALL rendre `500 INTERNAL_ERROR` avec cette référence. Ni la ligne ni la réponse ne SHALL contenir le message de l'exception, l'adresse demandée, une valeur de paramètre ou le jeton.

#### Scenario: Exception enveloppée dans un service
- **GIVEN** la surface démarrée et un service qui lève une exception enveloppant un `NullPointerException`
- **WHEN** une requête valide atteint ce service (`./mvnw -B -ntp -pl minos-explorer -am test "-Dtest=ExplorerDiagnosticsTest" "-Dsurefire.failIfNoSpecifiedTests=false"`)
- **THEN** la réponse est `500` de code `INTERNAL_ERROR` et son message se termine par une référence
- **AND** stderr porte une ligne avec le motif de la route, les deux classes, l'origine et la même référence

#### Scenario: Aucune donnée de requête dans la ligne
- **GIVEN** une requête dont le chemin porte une clé de document et dont l'exception a un message contenant un chemin absolu
- **WHEN** elle échoue en interne
- **THEN** la ligne ne contient ni la clé, ni le chemin, ni le jeton de session

#### Scenario: Échec hors d'une route d'API
- **GIVEN** un échange dont l'écriture de la réponse échoue
- **WHEN** l'échec est attrapé par la surface
- **THEN** stderr porte la même forme de ligne, avec la classe de la cause racine

#### Scenario: Journal privé inactif
- **GIVEN** aucun réglage de diagnostic et l'arborescence de `MINOS_HOME` relevée
- **WHEN** une requête échoue en interne
- **THEN** l'arborescence de `MINOS_HOME` est identique au relevé

#### Scenario: Journal privé actif
- **GIVEN** `MINOS_DIAGNOSTIC_LOG=enabled`, un `MINOS_HOME` inscriptible et son arborescence relevée
- **WHEN** des consultations réussissent, puis une requête échoue en interne
- **THEN** l'arborescence est identique au relevé après les consultations réussies
- **AND** après l'échec, seul le dossier du journal de diagnostic a changé, et l'index est identique

### Requirement: L'explorateur ne rend 400 que pour une erreur d'usage explicite
La surface SHALL rendre `400 INVALID_REQUEST` seulement pour une erreur d'usage explicite levée par la validation de l'adresse, des paramètres ou du corps. Toute autre erreur d'argument levée pendant le traitement SHALL être un échec interne : `500 INTERNAL_ERROR` et ligne de diagnostic.

#### Scenario: Paramètre invalide
- **GIVEN** la surface démarrée
- **WHEN** la liste des documents est demandée avec `limit=abc` ou un paramètre inconnu
- **THEN** la réponse est `400` de code `INVALID_REQUEST` avec un message qui nomme ce qui est à corriger
- **AND** aucune ligne d'échec interne n'est écrite

#### Scenario: Erreur d'argument levée par un service
- **GIVEN** un service qui lève une `IllegalArgumentException` non explicite, ou une `InvalidPathException`, sur une requête aux paramètres valides
- **WHEN** la requête est traitée
- **THEN** la réponse est `500` de code `INTERNAL_ERROR` et ne contient pas le message de l'exception
- **AND** une ligne d'échec interne est écrite

#### Scenario: Les erreurs d'usage du contrat restent des 400
- **GIVEN** la table des entrées invalides des routes du contrat (échappement cassé dans l'adresse, curseur mal formé, genre inconnu, borne hors plage, corps non JSON ou trop gros, membre inconnu, clé trop longue)
- **WHEN** chaque entrée est jouée (`./mvnw -B -ntp -pl minos-explorer -am test "-Dtest=ExplorerRoutesTest" "-Dsurefire.failIfNoSpecifiedTests=false"`)
- **THEN** chaque réponse est `400` ou le code d'usage déjà attendu, jamais `500`

### Requirement: Les refus de l'explorateur sont journalisés avec limitation
La surface SHALL écrire aussitôt la première requête refusée de chaque raison (hôte, origine, jeton), puis SHALL compter les refus suivants et les résumer par au plus une ligne par raison et par fenêtre de temps, avec leur nombre. Une ligne de refus SHALL NOT contenir l'adresse demandée, un en-tête ni le jeton.

#### Scenario: Rafale de refus
- **GIVEN** la surface démarrée avec une horloge injectée
- **WHEN** mille requêtes sans jeton arrivent dans la même fenêtre (`./mvnw -B -ntp -pl minos-explorer -am test "-Dtest=ExplorerDiagnosticsTest" "-Dsurefire.failIfNoSpecifiedTests=false"`)
- **THEN** stderr porte une ligne de refus pour la raison « jeton », et aucune autre avant la fin de la fenêtre
- **AND** chaque requête a reçu `401`

#### Scenario: Résumé de la fenêtre
- **GIVEN** la rafale précédente
- **WHEN** la fenêtre se termine et qu'un nouveau refus arrive
- **THEN** une ligne donne la raison et le nombre de refus comptés depuis la ligne précédente

#### Scenario: Raisons distinctes
- **GIVEN** un refus d'origine après une rafale de refus de jeton
- **WHEN** il arrive dans la même fenêtre
- **THEN** il est écrit aussitôt, sous sa propre raison

### Requirement: Une requête de l'explorateur abandonnée au délai libère son créneau
Quand une requête d'API dépasse le délai de la surface, la surface SHALL rendre `503 TIMEOUT` et SHALL libérer aussitôt son créneau de concurrence, même si la lecture sous-jacente ignore l'interruption. Les requêtes suivantes SHALL être servies sans attendre la fin de cette lecture.

#### Scenario: Lectures qui ignorent l'interruption
- **GIVEN** la surface démarrée avec un délai court et un traitement qui ignore l'interruption et reste bloqué
- **WHEN** autant de requêtes que de créneaux sont abandonnées au délai, puis une requête normale arrive (`./mvnw -B -ntp -pl minos-explorer -am test "-Dtest=ExplorerServerTest" "-Dsurefire.failIfNoSpecifiedTests=false"`)
- **THEN** les requêtes bloquées reçoivent `503` de code `TIMEOUT`
- **AND** la requête normale reçoit `200`

### Requirement: Les lectures abandonnées encore en cours sont bornées et dites
La surface SHALL compter les lectures abandonnées qui n'ont pas rendu la main et SHALL en borner le nombre. À la borne, toute nouvelle requête d'API SHALL recevoir `503 BUSY` sans qu'un service soit appelé, jusqu'à ce qu'une lecture abandonnée se termine. Le passage par la borne et le nombre de lectures abandonnées SHALL être écrits sur stderr, avec la même limitation que les refus.

#### Scenario: Borne atteinte
- **GIVEN** des lectures bloquées abandonnées jusqu'à la borne
- **WHEN** une nouvelle requête d'API arrive
- **THEN** elle reçoit `503` de code `BUSY` et aucun service n'est appelé
- **AND** stderr porte une ligne qui donne le nombre de lectures abandonnées en cours

#### Scenario: Retour à la normale
- **GIVEN** la borne atteinte
- **WHEN** une lecture abandonnée se termine, puis une requête arrive
- **THEN** la requête reçoit `200`

#### Scenario: Arrêt de la surface
- **GIVEN** des lectures abandonnées en cours
- **WHEN** la surface est arrêtée
- **THEN** l'arrêt se termine sans attendre ces lectures

### Requirement: Un segment de chemin point ou point-point est refusé
La surface SHALL refuser par `400 INVALID_REQUEST`, avant tout appel de service, une adresse d'API dont un segment décodé vaut `.` ou `..`, y compris sous sa forme encodée en pourcentage. Le message SHALL NOT recopier l'adresse.

#### Scenario: Segment point-point
- **GIVEN** la surface démarrée et un jeton valide
- **WHEN** `GET /api/v1/projects/../documents` ou `GET /api/v1/projects/%2E%2E/documents` est demandé (`./mvnw -B -ntp -pl minos-explorer -am test "-Dtest=ExplorerServerTest" "-Dsurefire.failIfNoSpecifiedTests=false"`)
- **THEN** la réponse est `400` de code `INVALID_REQUEST`
- **AND** aucun service n'est appelé

#### Scenario: Point dans un nom
- **GIVEN** un projet nommé `mon.projet` et une clé de document contenant des points et un `/` encodé
- **WHEN** ses routes sont demandées
- **THEN** elles sont servies comme avant
