## ADDED Requirements

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
- **WHEN** le garde est exécuté sur le dépôt corrigé
- **THEN** il réussit, les six sites de diagnostic ou de refus étant les seules entrées de la liste
