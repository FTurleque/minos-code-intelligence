## ADDED Requirements

### Requirement: La ligne de diagnostic d'un échec interne nomme son origine et une référence
Pour tout échec interne rendu par une erreur opaque, le système SHALL écrire sur stderr une ligne qui porte l'opération, la classe de l'exception, la classe de la cause racine, la première trame de la cause racine appartenant au code MINOS (classe, méthode, ligne) et une référence de corrélation tirée au hasard. La ligne SHALL NOT contenir de message d'exception, de valeur d'argument ni de chemin.

#### Scenario: Cause racine dans le code MINOS
- **GIVEN** un outil MCP dont le service lève une exception qui enveloppe un `NullPointerException` levé par une classe `com.minos.*`
- **WHEN** l'outil est appelé (`./mvnw -B -ntp -pl minos-mcp -am test "-Dtest=MinosMcpInternalFailureTraceTest" "-Dsurefire.failIfNoSpecifiedTests=false"`)
- **THEN** la ligne de journal contient le nom de l'outil, les deux classes, `at=` suivi de la classe, de la méthode et de la ligne de la trame, et `ref=` suivi de la référence
- **AND** stdout ne contient que des messages du protocole

#### Scenario: Message porteur d'un chemin et d'un jeton
- **GIVEN** une exception interne dont le message contient un chemin absolu et un jeton
- **WHEN** l'échec est journalisé
- **THEN** la ligne ne contient ni ce chemin ni ce jeton

#### Scenario: Aucune trame MINOS
- **GIVEN** une cause racine dont aucune trame n'appartient à `com.minos`
- **WHEN** l'échec est journalisé
- **THEN** la ligne porte `at=unknown` et garde les classes et la référence

### Requirement: Le journal de diagnostic privé est désactivé par défaut
Le système SHALL n'écrire un journal de diagnostic privé que si `MINOS_DIAGNOSTIC_LOG` ou la propriété `minos.diagnostic.log` vaut `enabled`. Sans ce réglage, ou avec toute autre valeur, il SHALL NOT créer de fichier ni de dossier de journal ; une valeur non reconnue SHALL être signalée une fois sur stderr sans être recopiée.

#### Scenario: Réglage absent
- **GIVEN** un `MINOS_HOME` sans dossier `logs` et aucun réglage de diagnostic
- **WHEN** un échec interne survient sur une surface
- **THEN** `MINOS_HOME` ne contient aucun dossier `logs` après l'appel

#### Scenario: Valeur non reconnue
- **GIVEN** `MINOS_DIAGNOSTIC_LOG=oui`
- **WHEN** un échec interne survient
- **THEN** aucun fichier de journal n'est créé
- **AND** stderr signale une fois que le réglage n'est pas reconnu, sans recopier sa valeur

### Requirement: Le journal privé reçoit la pile d'un échec interne sans chemin absolu ni secret
Journal activé, chaque échec interne SHALL y ajouter une entrée : horodatage UTC, surface, opération, référence, classes de la chaîne de causes et trames (classe, méthode, fichier source, ligne). Un message d'exception SHALL n'y figurer que si la politique publique de redaction ne le juge pas sensible ; sinon il est remplacé par un texte fixe. L'entrée SHALL NOT contenir de valeur d'argument de l'appelant.

#### Scenario: Pile conservée sous la référence de la ligne stderr
- **GIVEN** le journal activé et un outil MCP qui échoue en interne
- **WHEN** l'outil est appelé
- **THEN** le journal contient une entrée dont la référence est celle de la ligne stderr, avec la chaîne des classes et les trames de la cause racine

#### Scenario: Message sensible retenu
- **GIVEN** le journal activé et une exception dont le message contient un chemin absolu, un mot de passe et une chaîne de connexion
- **WHEN** l'échec est journalisé
- **THEN** l'entrée ne contient aucune de ces valeurs et porte le texte fixe de retenue
- **AND** le contenu du fichier ne contient ni la racine du répertoire temporaire du test ni le nom de l'utilisateur

#### Scenario: Message non sensible conservé
- **GIVEN** le journal activé et une exception dont le message est `limit must be positive`
- **WHEN** l'échec est journalisé
- **THEN** l'entrée contient ce message

### Requirement: Le journal privé est borné en nombre d'entrées et en profondeur
Le journal privé SHALL garder un fichier par échec et un nombre maximal fixe d'entrées ; au-delà, les plus anciennes SHALL être supprimées. Une entrée SHALL borner le nombre de causes et de trames écrites et dire ce qui a été omis.

#### Scenario: Purge à la borne
- **GIVEN** le journal activé avec un nombre maximal d'entrées réduit par le test
- **WHEN** plus d'échecs que cette borne sont journalisés
- **THEN** le dossier du journal ne contient pas plus d'entrées que la borne, et ce sont les plus récentes

#### Scenario: Pile très profonde
- **GIVEN** une exception dont la pile dépasse la borne de trames
- **WHEN** l'échec est journalisé
- **THEN** l'entrée s'arrête à la borne et indique le nombre de trames omises

### Requirement: Le journal privé est écrit par les primitives d'E/S privées
Le dossier et les fichiers du journal SHALL être créés et écrits par les primitives d'E/S privées existantes, avec des permissions réservées à l'utilisateur courant sous Windows et sous Linux, sans appel brut nouveau ni entrée ajoutée à la liste nominative du garde. Chaque entrée SHALL être un fichier entier, y compris quand plusieurs processus MINOS journalisent dans le même répertoire.

#### Scenario: Permissions privées
- **GIVEN** le journal activé
- **WHEN** un premier échec est journalisé
- **THEN** le dossier du journal et le fichier de l'entrée ont la confidentialité privée rapportée par la primitive de vérification

#### Scenario: Le garde reste vert sans édition de sa liste
- **GIVEN** le dépôt avec le journal implémenté et la liste nominative d'E/S privées inchangée
- **WHEN** `python scripts/architecture/check-private-io.py` est exécuté
- **THEN** il réussit

#### Scenario: Deux processus
- **GIVEN** deux JVM qui journalisent chacune cinquante échecs dans le même `MINOS_HOME`, sous une borne supérieure à cent
- **WHEN** elles se terminent
- **THEN** le journal contient cent entrées, chacune complète et sous sa propre référence

### Requirement: Un journal privé indisponible ne change pas la réponse de la surface
Si le journal activé ne peut pas être ouvert ou écrit, le système SHALL rendre la même réponse que sans journal, SHALL garder la ligne de diagnostic sur stderr et SHALL signaler l'indisponibilité une seule fois par processus, par la classe de l'erreur et sans chemin. Il SHALL NOT réessayer en boucle ni écrire ailleurs.

#### Scenario: Répertoire de données en lecture seule
- **GIVEN** le journal activé et un `MINOS_HOME` protégé en écriture
- **WHEN** un outil MCP échoue en interne deux fois
- **THEN** les deux réponses sont l'erreur opaque habituelle, stderr porte les deux lignes de diagnostic et une seule ligne d'indisponibilité
- **AND** l'état du répertoire relevé avant et après est identique
