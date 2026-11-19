# journalisation Specification

## Purpose
Définit ce qu'un journal de MINOS doit laisser voir et ce qu'il ne doit pas révéler : les erreurs des bibliothèques du livrable atteignent stderr sans toucher stdout, réservé au protocole MCP ; un message de récupération de fichiers ne porte ni chemin absolu ni cause ; aucun journal ne concatène un chemin, et la cause d'une exception n'est transmise que par les sites d'une liste nominative gardée en CI.

## Requirements

### Requirement: Les erreurs des bibliothèques atteignent stderr dans le livrable
Le jar ombré SHALL embarquer exactement un fournisseur SLF4J qui écrit, et ce fournisseur SHALL NOT être le fournisseur muet. Un message de niveau ERROR émis par SLF4J SHALL apparaître sur stderr du processus et SHALL NOT apparaître sur stdout.

#### Scenario: Fournisseur unique et non muet
- **WHEN** la liste de `META-INF/services/org.slf4j.spi.SLF4JServiceProvider` du jar ombré est lue
- **THEN** elle contient exactement `org.slf4j.jul.JULServiceProvider` et aucun fournisseur `nop`

#### Scenario: Erreur émise par SLF4J dans le jar ombré
- **GIVEN** un processus Java lancé avec le jar ombré et une classe d'essai sur son classpath
- **WHEN** la classe journalise `error("marqueur-d-essai")` par `LoggerFactory`
- **THEN** stderr contient `marqueur-d-essai`, stdout ne le contient pas, et stderr ne contient pas l'avertissement `No SLF4J providers were found`

#### Scenario: Le protocole MCP reste propre
- **WHEN** une poignée de main MCP complète est jouée contre le jar ombré
- **THEN** chaque ligne de stdout est un message JSON-RPC

### Requirement: Un message de récupération de fichiers ne contient ni chemin absolu ni cause
Les journaux WARNING de `RunDirectoryRetention` et de `ProviderResidueReclamation` SHALL désigner l'entrée par son chemin relatif à la racine gérée et SHALL nommer la classe de l'exception ; ils SHALL NOT contenir de chemin absolu ni transmettre la cause à l'enregistreur.

#### Scenario: Répertoire de run non récupérable
- **GIVEN** un répertoire de run dont le déplacement vers la quarantaine échoue
- **WHEN** la récupération est exécutée avec un enregistreur capturé
- **THEN** le message contient le nom relatif du répertoire et le nom de la classe de l'exception, aucun enregistrement n'a de cause, et le texte capturé ne contient ni la racine du répertoire temporaire du test ni le nom de l'utilisateur

#### Scenario: Résidu de provider non supprimable
- **GIVEN** un résidu dans une copie de travail de provider que la suppression ne peut pas retirer
- **WHEN** `ProviderResidueReclamation` est exécutée avec un enregistreur capturé
- **THEN** le message contient le chemin relatif au répertoire géré et la classe de l'exception, sans cause et sans chemin absolu

### Requirement: Un journal ne concatène jamais un chemin
Le garde d'architecture SHALL échouer quand un appel `LOGGER.log` des sources de production concatène une variable déclarée de type `Path`, sans exception possible par liste.

#### Scenario: Chemin concaténé
- **GIVEN** une source de production dont un appel `LOGGER.log` contient `"…" + target` avec `Path target` déclaré dans le fichier
- **WHEN** `python scripts/architecture/check-private-io.py` est exécuté sur cet arbre
- **THEN** il échoue en nommant le fichier et la ligne

#### Scenario: Chemin relatif ou classe de l'exception
- **GIVEN** un appel `LOGGER.log` qui n'écrit que `relative` (obtenu par `relativize`) et `exception.getClass().getSimpleName()`
- **WHEN** le garde est exécuté
- **THEN** il réussit

### Requirement: La cause d'un journal n'est transmise que par une liste nominative
Le garde SHALL échouer quand un appel `LOGGER.log` des sources de production transmet un `Throwable` ou appelle `getMessage()`, sauf pour les entrées nommées d'une liste qui porte pour chacune un maximum d'occurrences et une justification ; une entrée au maximum supérieur au décompte réel SHALL elle aussi échouer.

#### Scenario: Cause transmise hors liste
- **GIVEN** un appel `LOGGER.log(WARNING, "…", exception)` dans un fichier absent de la liste
- **WHEN** le garde est exécuté
- **THEN** il échoue en nommant le fichier

#### Scenario: Entrée de liste obsolète
- **GIVEN** une entrée dont le maximum dépasse le nombre d'occurrences réel du fichier
- **WHEN** le garde est exécuté
- **THEN** il échoue en demandant de resserrer la liste

#### Scenario: Diagnostic de bac à sable listé
- **GIVEN** la liste nominative du dépôt, dont chaque entrée nomme un fichier, un maximum et une justification (diagnostics de bac à sable, refus de reprise d'indexation, journaux serveur du plan de contrôle hébergé)
- **WHEN** le garde est exécuté sur le dépôt
- **THEN** il réussit
- **AND** chaque appel qui transmet une cause appartient à un fichier de la liste, sans dépasser son maximum

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

## Documentation liée

- Dossier durable (vues, traçabilité, écarts) : [journalisation](../../../docs/architecture/diagrams/capacites/journalisation.md)
