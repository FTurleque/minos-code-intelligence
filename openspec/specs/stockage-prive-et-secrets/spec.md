# stockage-prive-et-secrets Specification

## Purpose
Garantit que la configuration du stockage et des secrets de MINOS est lue, validée et publiée de
façon fail-closed : une propriété ou un secret n'est jamais ignoré ou altéré en silence, une
connexion PostgreSQL externe n'est jamais ouverte sans la vérification TLS annoncée, et un fichier
publié comme immuable n'est jamais remplacé.

## Requirements

### Requirement: Une connexion PostgreSQL externe exige un mode TLS reconnu à l'identique par le pilote
Pour un hôte PostgreSQL externe, la politique d'URL MUST exiger `sslmode=verify-full` et MUST refuser tout nom de paramètre qui ne serait pas reconnu, tel quel, par le pilote JDBC : la comparaison porte sur le nom brut, sans décodage ni changement de casse. Un URL refusé n'ouvre aucune connexion.

#### Scenario: Nom de paramètre en majuscules
- **WHEN** l'URL `jdbc:postgresql://db.example.com:5432/minos?SSLMODE=verify-full` est validée pour un hôte externe
- **THEN** la validation échoue avec une erreur d'entrée-sortie
- **AND** aucune connexion n'est ouverte

#### Scenario: Nom de paramètre encodé en pourcentage
- **WHEN** l'URL `jdbc:postgresql://db.example.com:5432/minos?ssl%6Dode=verify-full` est validée pour un hôte externe
- **THEN** la validation échoue avec une erreur d'entrée-sortie

#### Scenario: Nom exact accepté
- **WHEN** l'URL `jdbc:postgresql://db.example.com:5432/minos?sslmode=verify-full` est validée pour un hôte externe
- **THEN** la validation réussit
- **AND** le pilote JDBC interprète ce même URL avec le mode `verify-full`

#### Scenario: Valeur en majuscules reconnue par le pilote
- **WHEN** l'URL `jdbc:postgresql://db.example.com:5432/minos?sslmode=VERIFY-FULL` est validée
- **THEN** la validation réussit
- **AND** le pilote JDBC interprète ce même URL avec le mode `verify-full`

#### Scenario: Hôte externe sans mode ou avec un mode plus faible
- **WHEN** l'URL d'un hôte externe n'a pas de `sslmode`, ou porte `sslmode=require`
- **THEN** la validation échoue avec le message « external PostgreSQL requires sslmode=verify-full »

#### Scenario: Doublon et variantes de casse
- **WHEN** l'URL porte `sslmode=verify-full` et `SSLMODE=verify-full`, ou deux fois `sslmode`
- **THEN** la validation échoue

#### Scenario: Hôte de bouclage avec nom de paramètre non reconnu
- **WHEN** l'URL d'un hôte de bouclage porte `SSLMODE=disable`
- **THEN** la validation échoue, car le pilote ignorerait ce paramètre

### Requirement: Tout URL accepté par la politique a le même mode TLS pour le pilote
Pour tout URL que la politique accepte, le mode TLS effectif retenu par le pilote JDBC MUST être celui que la politique a validé ; une différence d'analyse entre la politique et le pilote MUST NOT permettre un mode plus faible.

#### Scenario: Parité sur un échantillon d'URL
- **WHEN** un ensemble d'URL (casse, encodage de la clé, encodage de la valeur, doublon, paramètre inconnu, bouclage IPv4 et IPv6) est soumis à la politique puis, pour les URL acceptés, à l'analyse du pilote
- **THEN** chaque URL accepté donne au pilote le mode TLS validé par la politique
- **AND** chaque URL dont l'interprétation différerait est refusé par la politique

#### Scenario: Assertion de l'installateur Windows
- **WHEN** l'installateur Windows valide un URL PostgreSQL externe dont le nom de paramètre est en majuscules ou encodé en pourcentage
- **THEN** il refuse l'URL avec le même motif que la politique du runtime

### Requirement: L'IPv6 de bouclage est reconnu comme bouclage
La politique d'URL MUST reconnaître `[::1]` et `[0:0:0:0:0:0:0:1]` comme hôtes de bouclage, y compris pour un stockage géré, et MUST continuer à exiger `verify-full` pour toute autre adresse IPv6. Le diagnostic d'un URL à hôte IPv6 MUST porter une seule paire de crochets.

#### Scenario: Bouclage IPv6 sans mode TLS
- **WHEN** l'URL `jdbc:postgresql://[::1]:5432/minos` est validée
- **THEN** la validation réussit

#### Scenario: Stockage géré sur bouclage IPv6
- **WHEN** l'URL `jdbc:postgresql://[::1]:5432/minos` est validée pour un stockage géré
- **THEN** la validation réussit

#### Scenario: Autre adresse IPv6
- **WHEN** l'URL `jdbc:postgresql://[2001:db8::1]:5432/minos` est validée sans `sslmode=verify-full`
- **THEN** la validation échoue
- **AND** la même URL avec `sslmode=verify-full` réussit

#### Scenario: Forme non listée
- **WHEN** l'URL `jdbc:postgresql://[::ffff:127.0.0.1]:5432/minos` est validée sans `sslmode=verify-full`
- **THEN** la validation échoue, l'hôte restant traité comme externe

#### Scenario: Diagnostic d'URL
- **WHEN** le diagnostic de configuration affiche l'URL `jdbc:postgresql://[::1]:5432/minos`
- **THEN** il affiche `jdbc:postgresql://[::1]:5432/minos` avec une seule paire de crochets
- **AND** il n'affiche ni mot de passe ni paramètre d'URL

### Requirement: Un BOM UTF-8 initial n'altère ni une propriété ni un secret
La lecture d'un fichier de configuration ou de secret MUST retirer un seul BOM UTF-8 initial avant de l'analyser, de sorte que la première propriété soit lue sous son vrai nom et que le secret ne contienne pas le BOM. Un BOM répété MUST être refusé avec un message explicite. Un BOM situé ailleurs qu'en tête MUST rester une donnée.

#### Scenario: Première propriété d'un fichier avec BOM
- **WHEN** le fichier de configuration commence par un BOM suivi de `minos.storage.backend=postgresql`
- **THEN** la propriété `minos.storage.backend` vaut `postgresql`
- **AND** le stockage sélectionné est PostgreSQL, non le stockage local

#### Scenario: Secret lu d'un fichier avec BOM
- **WHEN** le fichier de secret contient un BOM, `s3cret` et une fin de ligne
- **THEN** le secret lu est exactement `s3cret`

#### Scenario: Fichier sans BOM
- **WHEN** un fichier de configuration ou de secret ne commence pas par un BOM
- **THEN** sa lecture est identique à celle d'avant ce changement

#### Scenario: BOM répété refusé
- **WHEN** un fichier de configuration commence par deux BOM
- **THEN** la lecture échoue avec un message explicite, sans ignorer la première propriété

#### Scenario: Encodage invalide et plafond d'octets inchangés
- **WHEN** un fichier avec BOM contient un octet UTF-8 invalide, ou dépasse le plafond d'octets
- **THEN** la lecture échoue comme pour un fichier sans BOM
- **AND** les octets du BOM comptent dans le plafond

### Requirement: Une publication n'écrase jamais une cible existante
La publication d'un fichier comme immuable MUST échouer avec une erreur « existe déjà » quand la cible existe, MUST laisser le contenu de la cible inchangé, et MUST réussir quand la cible est absente. Ce comportement MUST être identique sous Windows et sous Linux. Le remplacement explicite d'un fichier MUST continuer à remplacer la cible.

#### Scenario: Cible existante
- **WHEN** un fichier est publié vers une cible qui existe déjà
- **THEN** la publication échoue avec une erreur « existe déjà »
- **AND** la cible garde son contenu d'origine

#### Scenario: Cible absente
- **WHEN** un fichier est publié vers une cible qui n'existe pas
- **THEN** la cible porte le contenu publié
- **AND** le fichier source n'existe plus

#### Scenario: Deux publications concurrentes
- **WHEN** deux publications visent simultanément la même cible absente
- **THEN** exactement une réussit
- **AND** l'autre échoue avec une erreur « existe déjà »

#### Scenario: Remplacement explicite inchangé
- **WHEN** un fichier est remplacé par le remplacement explicite sur une cible existante
- **THEN** la cible porte le nouveau contenu

### Requirement: Une publication fail-closed quand l'absence d'écrasement n'est pas garantie
Quand le système de fichiers ne peut pas garantir qu'une publication n'écrasera pas la cible, la publication MUST échouer avec un message explicite et MUST NOT se replier sur un déplacement qui pourrait écraser la cible.

#### Scenario: Système de fichiers sans garantie d'absence d'écrasement
- **WHEN** une publication est tentée sur un système de fichiers qui ne supporte pas l'opération de publication sans remplacement
- **THEN** la publication échoue avec un message explicite
- **AND** la cible existante, le cas échéant, reste inchangée

### Requirement: Un secret écrit par un script d'installation n'existe jamais avec une ACL héritée
Les scripts d'installation qui écrivent le mot de passe PostgreSQL (`configure-runtime-settings.ps1`, `configure-m30-docker-services.ps1`) SHALL créer le fichier vide, le restreindre à l'utilisateur courant (héritage coupé, une seule entrée d'autorisation) et vérifier cette restriction avant d'y écrire le secret. Si la restriction ou sa vérification échoue, le fichier SHALL être supprimé, le secret SHALL NOT avoir été écrit et le script SHALL échouer.

#### Scenario: Écriture nominale dans un dossier d'ACL large
- **GIVEN** un dossier de données dont l'ACL accorde la lecture au groupe Utilisateurs
- **WHEN** la fonction d'écriture du secret est exécutée
- **THEN** le fichier final n'a pas d'héritage et une seule entrée d'autorisation, celle du SID courant, et son contenu est le secret

#### Scenario: Le secret arrive dans un fichier déjà restreint
- **GIVEN** une sonde qui lit l'ACL du fichier au moment où son contenu devient non vide
- **WHEN** la fonction d'écriture est exécutée
- **THEN** l'ACL observée par la sonde est déjà restreinte à l'utilisateur courant

#### Scenario: Échec de la restriction
- **GIVEN** une restriction d'ACL qui échoue (échec injecté)
- **WHEN** la fonction d'écriture est exécutée
- **THEN** le script échoue, le fichier n'existe plus et le secret n'a été écrit à aucun moment

### Requirement: Un fichier de secret existant est revérifié
Quand le fichier du mot de passe existe déjà, le script SHALL le restreindre de nouveau et vérifier son ACL avant de le lire ou de le conserver. Si la restriction n'est pas possible, le script SHALL échouer sans modifier le contenu et SHALL indiquer que le mot de passe peut avoir été exposé.

#### Scenario: Fichier existant d'ACL héritée
- **GIVEN** un fichier de mot de passe existant dont l'ACL est héritée d'un dossier large
- **WHEN** `New-ManagedPassword` est exécutée
- **THEN** l'ACL du fichier est restreinte à l'utilisateur courant avant que son contenu ne soit lu

#### Scenario: Fichier existant non restreignable
- **GIVEN** un fichier existant dont la restriction échoue (échec injecté)
- **WHEN** `New-ManagedPassword` est exécutée
- **THEN** le script échoue avec un message qui mentionne l'exposition possible, et le contenu du fichier est inchangé

### Requirement: Le message d'une primitive d'E/S privée ne contient pas de chemin absolu
Une exception levée par une primitive d'E/S privée pour une cible refusée (lien symbolique, type de fichier inattendu, durabilité non garantie) SHALL nommer cette cible par une étiquette fixe ou par son nom de fichier. Son message SHALL NOT contenir le chemin absolu de la cible ni celui de son répertoire.

#### Scenario: Répertoire à la place d'un fichier de propriétés
- **GIVEN** un répertoire temporaire `T` qui contient un répertoire nommé `minos.properties`
- **WHEN** `BoundedProperties.load` est appelé sur ce chemin
- **THEN** l'appel échoue et le message de l'exception ne contient pas le chemin de `T`

#### Scenario: Lien symbolique refusé
- **GIVEN** un lien symbolique vers un fichier régulier, sur un système où le lien a pu être créé
- **WHEN** un store du backend local de `minos-storage` le rencontre à la place de son fichier
- **THEN** la lecture est refusée et le message ne contient pas le chemin absolu du lien

### Requirement: Une configuration d'exécution présente mais non régulière est refusée
Le chargement de la configuration d'exécution SHALL tester l'existence de `config/minos.properties` sans suivre les liens. Si rien n'existe à cet emplacement, il SHALL réussir avec une configuration vide. Si l'emplacement est occupé par autre chose qu'un fichier régulier (répertoire, lien symbolique, lien cassé), il SHALL échouer et SHALL NOT appliquer les valeurs par défaut.

#### Scenario: Aucun fichier de configuration
- **GIVEN** un `MINOS_HOME` sans `config/minos.properties`
- **WHEN** `MinosRuntimeSettings.load` est appelé
- **THEN** le chargement réussit avec une configuration vide

#### Scenario: Répertoire à la place du fichier
- **GIVEN** un `MINOS_HOME` où `config/minos.properties` est un répertoire
- **WHEN** `MinosRuntimeSettings.load` est appelé
- **THEN** le chargement échoue, sans chemin absolu dans le message

#### Scenario: Lien cassé à la place du fichier
- **GIVEN** un `MINOS_HOME` où `config/minos.properties` est un lien symbolique vers une cible absente, sur un système où le lien a pu être créé
- **WHEN** `MinosRuntimeSettings.load` est appelé
- **THEN** le chargement échoue au lieu de rendre une configuration vide

### Requirement: Une écriture brute nouvelle hors des primitives est refusée par le gate
Le gate `check-private-io.py` SHALL refuser, dans le code de production du reactor et hors des primitives d'E/S, tout appel, référence de méthode ou import statique de `Files.newOutputStream`, `newBufferedWriter`, `newByteChannel`, `createFile`, `createDirectory`, `createTempFile`, `createTempDirectory`, `copy` ou `move`, et toute redirection de la sortie d'un processus vers un fichier, sauf occurrence inscrite nominativement dans la liste de tolérance.

#### Scenario: Appel brut ajouté dans un fichier non listé
- **GIVEN** un arbre où un fichier de `src/main/java` hors primitives appelle `Files.newOutputStream`, sans entrée de liste
- **WHEN** `python scripts/architecture/check-private-io.py` est exécuté sur cet arbre
- **THEN** le gate échoue et nomme le fichier et l'API

#### Scenario: Chaque API nouvelle est refusée seule
- **GIVEN** l'auto-test du gate, avec un témoin par règle
- **WHEN** `python scripts/architecture/test_check_private_io.py -v` est exécuté
- **THEN** chaque témoin produit exactement une violation qui nomme sa règle, y compris par référence de méthode et par import statique

#### Scenario: Redirection d'un processus vers un fichier
- **GIVEN** un fichier de production qui redirige la sortie d'un processus vers un fichier
- **WHEN** le gate est exécuté
- **THEN** il échoue ; la même source redirigée vers `Redirect.DISCARD` passe

#### Scenario: Appels permis non signalés
- **GIVEN** un fichier de production qui n'appelle que `Files.readAllBytes` et les primitives d'E/S
- **WHEN** le gate est exécuté
- **THEN** il réussit

### Requirement: La liste de tolérance des écritures brutes ne fait que se resserrer
Chaque occurrence existante d'une API refusée SHALL être inscrite par une entrée nominative (fichier, API, maximum, justification écrite). Le gate SHALL échouer quand un fichier listé compte une occurrence de plus que son maximum, quand un maximum dépasse le décompte réel, et quand la liste est illisible ou contient un dossier, un joker ou une justification vide : il SHALL NOT se replier sur une liste vide ou ignorée.

#### Scenario: Une occurrence de plus dans un fichier listé
- **GIVEN** un fichier listé pour deux `Files.move` qui en contient trois
- **WHEN** le gate est exécuté
- **THEN** il échoue et cite le maximum de l'entrée

#### Scenario: Entrée périmée après migration
- **GIVEN** une entrée dont le fichier ne contient plus l'API listée
- **WHEN** le gate est exécuté
- **THEN** il échoue et demande de retirer ou d'abaisser l'entrée

#### Scenario: Liste illisible
- **GIVEN** une liste de tolérance qui n'est pas un tableau JSON d'entrées complètes
- **WHEN** le gate est exécuté
- **THEN** il échoue sans examiner les sources comme si la liste était vide

#### Scenario: Dépôt réel
- **GIVEN** le dépôt après ce changement, sans aucun fichier Java modifié
- **WHEN** `python scripts/architecture/check-private-io.py` est exécuté
- **THEN** il réussit et son décompte de règles a augmenté de dix

### Requirement: L'ouverture confinée ne dépend d'aucun état statique modifiable
La primitive d'ouverture confinée (`ConfinedFileOpener`) SHALL NOT déclarer de champ statique non `final`. Un traitement inséré entre la dernière validation et l'ouverture SHALL n'être fourni que par l'appelant, pour son seul appel, par une entrée non publique ; les entrées publiques SHALL n'en exécuter aucun. Un fichier remplacé par un lien entre la validation et l'ouverture SHALL rester refusé.

#### Scenario: Aucun champ statique modifiable
- **GIVEN** la classe `ConfinedFileOpener` chargée par un test de `minos-engine`
- **WHEN** ses champs déclarés sont énumérés par réflexion
- **THEN** aucun n'est à la fois `static` et non `final`

#### Scenario: Remplacement par un lien juste avant l'ouverture
- **GIVEN** un fichier régulier validé, et un traitement passé par l'appelant qui le remplace par un lien symbolique
- **WHEN** l'ouverture confinée est appelée avec ce traitement
- **THEN** l'ouverture échoue par un refus de confinement et aucun octet de la cible du lien n'est lu

#### Scenario: Un appel n'influence pas le suivant
- **GIVEN** une ouverture qui vient d'être faite avec un traitement de remplacement
- **WHEN** une entrée publique ouvre ensuite un autre fichier régulier, sans aucune remise à zéro
- **THEN** le traitement n'est pas exécuté et le fichier est ouvert

### Requirement: Un refus conditionnel n'est jamais retiré par le durcissement
Sous Windows, le durcissement d'un emplacement privé SHALL NOT retirer une entrée de refus conditionnel, explicite ou héritée : après l'appel, elle SHALL être encore présente dans la DACL, que le durcissement ait réussi ou qu'il ait échoué avant d'écrire. Une autorisation conditionnelle héritée SHALL continuer d'être retirée. Si la DACL complète ne peut pas être lue, le durcissement SHALL échouer sans écrire, par une erreur sans chemin absolu.

#### Scenario: Refus conditionnel explicite
- **GIVEN** sous Windows, un répertoire dont la DACL porte un refus conditionnel posé par un administrateur
- **WHEN** le durcissement d'emplacement privé est appelé sur ce répertoire
- **THEN** le SDDL relu contient toujours l'entrée de refus conditionnel

#### Scenario: Refus conditionnel hérité du parent
- **GIVEN** un fichier qui hérite un refus conditionnel de son répertoire parent
- **WHEN** le durcissement est appelé sur ce fichier
- **THEN** le refus conditionnel s'applique toujours au fichier

#### Scenario: Autorisation conditionnelle héritée
- **GIVEN** un répertoire qui hérite une autorisation conditionnelle de son parent
- **WHEN** le durcissement est appelé
- **THEN** l'autorisation conditionnelle n'est plus dans sa DACL et l'héritage est coupé

#### Scenario: DACL illisible
- **GIVEN** une lecture de la DACL complète qui échoue ou dépasse son délai (échec injecté)
- **WHEN** le durcissement est appelé
- **THEN** il échoue, la DACL n'a pas été réécrite et le message ne contient pas le chemin absolu

### Requirement: Un octroi d'ACL du bac à sable est additif, borné et fail-closed
Le code Java du bac à sable Windows SHALL accorder ou retirer un droit par une primitive unique qui ajoute ou retire l'entrée d'une seule identité désignée par SID, sans réécrire la DACL, avec un délai borné et un répertoire Windows vérifié. Un octroi qui échoue, dépasse son délai ou ne trouve pas le répertoire Windows SHALL être rapporté comme non accordé et SHALL faire échouer l'opération qui en dépend.

#### Scenario: Octroi sur un répertoire porteur d'un refus
- **GIVEN** sous Windows, une racine d'écriture dont la DACL porte un refus posé par un administrateur
- **WHEN** la primitive accorde un droit à une identité
- **THEN** l'entrée de l'identité est ajoutée et toutes les autres entrées, dont le refus, sont inchangées

#### Scenario: Répertoire Windows inconnu
- **GIVEN** un environnement sans `SystemRoot`
- **WHEN** la primitive est appelée
- **THEN** elle rapporte un octroi non accordé sans lancer de processus ni lever d'erreur non contrôlée

#### Scenario: Délai dépassé
- **GIVEN** un lanceur injecté qui ne se termine pas
- **WHEN** la primitive est appelée
- **THEN** le processus est tué au terme du délai et l'octroi est rapporté comme non accordé

### Requirement: Une écriture en flux dans le stockage privé ne suit jamais un lien
Le moteur SHALL offrir trois écritures en flux : créer ou durcir un fichier privé, créer exclusivement un fichier privé durci, créer un fichier nouveau dans un arbre déjà privé sans réécrire de DACL. Toutes SHALL refuser un lien et un objet spécial, les deux dernières un fichier existant, sans nommer de chemin absolu.

#### Scenario: Lien à la place du fichier
- **GIVEN** un lien symbolique à l'emplacement d'un fichier à écrire
- **WHEN** `./mvnw -B -ntp -pl minos-engine -am test` exécute `PrivateLocalStorageWritesTest`
- **THEN** l'ouverture échoue et la cible du lien est inchangée

#### Scenario: Création exclusive d'un nom déjà pris
- **GIVEN** un fichier existant au nom d'un fichier à créer exclusivement
- **WHEN** le test de `createPrivateOutputStream` s'exécute
- **THEN** la création échoue et le contenu existant est intact

#### Scenario: Fichier d'arbre déjà présent
- **GIVEN** un fichier existant à l'emplacement d'un fichier d'arbre à créer
- **WHEN** le test de l'ouverture « arbre privé » s'exécute
- **THEN** l'ouverture échoue et le contenu existant est intact

#### Scenario: Aucune entrée de liste nouvelle
- **GIVEN** le dépôt après ce changement
- **WHEN** `python scripts/architecture/check-private-io.py` est exécuté
- **THEN** il réussit sans qu'aucun maximum d'une entrée de `PrivateLocalStorage` ait augmenté

### Requirement: La lecture par canal et la publication de répertoire échouent fermées
Le moteur SHALL offrir une lecture par canal d'un fichier régulier qui refuse lien et objet spécial, et une publication de répertoire par rename atomique vers un nom inexistant. Un système de fichiers sans rename atomique SHALL faire échouer la publication, sans repli par copie.

#### Scenario: Canal sur un lien
- **GIVEN** un lien symbolique à la place du fichier à lire
- **WHEN** `ConfinedFileOpenerTest` ouvre le canal
- **THEN** une `ConfinementException` sans chemin est levée

#### Scenario: Répertoire vers une cible existante
- **GIVEN** un répertoire source et une cible qui existe déjà
- **WHEN** `DurableAtomicFileTest` publie le répertoire
- **THEN** la publication échoue et la source est intacte

#### Scenario: Rename atomique non supporté
- **GIVEN** une plateforme simulée où `ATOMIC_MOVE` est refusé
- **WHEN** le répertoire est publié
- **THEN** une `IOException` est levée et aucune copie n'est faite

### Requirement: Le magasin PostgreSQL n'écrit pas ses fichiers de travail par un appel brut
Le magasin de snapshots PostgreSQL SHALL écrire ses fichiers de travail par les primitives d'E/S privées. Un fichier de travail remplacé par un lien symbolique SHALL faire échouer l'opération sans modifier la cible du lien et sans laisser de fichier de travail.

#### Scenario: Lien substitué au fichier de travail
- **GIVEN** un fichier de travail remplacé par un lien symbolique vers un fichier sentinelle
- **WHEN** `./mvnw -B -ntp -pl minos-storage -am test` exécute le test du magasin
- **THEN** l'opération échoue, la sentinelle est intacte et le fichier de travail est supprimé

#### Scenario: Plus d'entrée de liste pour le magasin
- **GIVEN** le dépôt après ce changement
- **WHEN** `python scripts/architecture/check-private-io.py` est exécuté
- **THEN** il réussit et la liste ne contient plus d'entrée sous `minos-storage/src/main/java/com/minos/storage/postgresql/`

### Requirement: Une entrée de cache de dépôt distant est publiée par un déplacement atomique de répertoire
Le matérialiseur de dépôts distants SHALL publier une entrée de cache par un déplacement atomique de répertoire, sans repli par copie et sans remplacer une entrée existante. Un système de fichiers sans rename atomique SHALL faire échouer la matérialisation avec le message public existant.

#### Scenario: Rename atomique non supporté
- **GIVEN** un déplaceur de répertoire qui signale l'absence de rename atomique
- **WHEN** `./mvnw -B -ntp -pl minos-integration-git -am test` exécute le test de matérialisation
- **THEN** la matérialisation échoue avec « remote repository cache requires atomic directory publication » et aucune entrée n'est créée

#### Scenario: Entrée apparue avant le déplacement
- **GIVEN** une entrée de cache créée entre la suppression de l'ancienne et le déplacement
- **WHEN** le répertoire temporaire est publié
- **THEN** la publication échoue et l'entrée présente n'est pas remplacée

#### Scenario: Plus d'entrée de liste pour le matérialiseur
- **GIVEN** le dépôt après ce changement
- **WHEN** `python scripts/architecture/check-private-io.py` est exécuté
- **THEN** il réussit et la liste ne contient plus d'entrée pour `JGitRemoteRepositoryMaterializer.java`

### Requirement: L'artefact d'un provider est lu sans suivre de lien et l'index figé est écrit par une primitive privée
L'ingestion SCIP SHALL ouvrir l'artefact d'index par un canal qui refuse lien symbolique et objet spécial, et SHALL écrire le fichier figé par une primitive d'E/S privée. Un artefact remplacé par un lien entre le contrôle et l'ouverture SHALL être refusé.

#### Scenario: Index remplacé par un lien
- **GIVEN** un index SCIP remplacé par un lien symbolique entre le contrôle de chemin et l'ouverture
- **WHEN** `./mvnw -B -ntp -pl minos-provider-scip -am test` exécute les tests de l'ingestion
- **THEN** l'ingestion est refusée et le contenu visé par le lien n'est pas lu

#### Scenario: Plus d'entrée de liste pour l'ingestion
- **GIVEN** le dépôt après ce changement
- **WHEN** `python scripts/architecture/check-private-io.py` est exécuté
- **THEN** il réussit et la liste ne contient plus d'entrée pour `ScipIndexReader.java` ni `ScipSymbolSnapshotImporter.java`

### Requirement: Le stockage local écrit ses fichiers par les primitives d'E/S privées
Le code de production du backend local (`com.minos.storage.local`, dans `minos-storage`) SHALL écrire ses snapshots, pointeurs, empreintes, vecteurs, sessions d'observation, fichiers de propriétés et sauvegardes par les primitives d'E/S privées, sans appel brut de `Files` listé dans la liste de tolérance. Une écriture dont la cible est un lien symbolique SHALL échouer sans modifier la cible du lien.

#### Scenario: Lien substitué au fichier d'un snapshot
- **GIVEN** un lien symbolique vers un fichier sentinelle à l'emplacement du fichier de snapshot à écrire
- **WHEN** `./mvnw -B -ntp -pl minos-storage -am test` exécute les tests de lien du module
- **THEN** l'écriture échoue et la sentinelle est intacte

#### Scenario: Plus aucune entrée de liste pour le module
- **GIVEN** le dépôt après ce changement
- **WHEN** `python scripts/architecture/check-private-io.py` est exécuté
- **THEN** il réussit et la liste ne contient plus d'entrée sous `minos-storage/src/main/java/com/minos/storage/local/`

### Requirement: Une sauvegarde de migration et une session publiée ne sont jamais écrasées
La sauvegarde `.m29-v1.bak` d'une entrée de registre et le fichier d'une session d'observation SHALL être publiés sans remplacer une cible existante. Un système de fichiers qui ne peut pas garantir cette exclusivité SHALL faire échouer la publication.

#### Scenario: Sauvegarde déjà présente
- **GIVEN** une sauvegarde `.m29-v1.bak` au contenu sentinelle
- **WHEN** une entrée de registre au format hérité est migrée
- **THEN** la sauvegarde garde son contenu et l'entrée migrée est écrite

#### Scenario: Session déjà publiée avec un contenu différent
- **GIVEN** une session stockée sous un identifiant
- **WHEN** une session de même identifiant et de contenu différent est enregistrée
- **THEN** l'enregistrement échoue et le fichier existant est inchangé

### Requirement: L'historique d'indexation et les empreintes de sources ne suivent pas de lien
L'historique d'indexation de la CLI SHALL être écrit par une primitive d'E/S privée avec un contenu identique à l'octet près, et les sources du projet et le fichier de configuration de sécurité SHALL être lus pour l'empreinte par une ouverture qui refuse un lien au dernier composant. Une source remplacée par un lien SHALL faire échouer l'empreinte.

#### Scenario: Source remplacée par un lien
- **GIVEN** une source Java dont le dernier composant devient un lien symbolique entre la résolution et la lecture
- **WHEN** `./mvnw -B -ntp -pl minos-application -am test` exécute le test d'empreinte
- **THEN** l'empreinte échoue et le contenu visé par le lien n'est pas haché

#### Scenario: Lien substitué au temporaire de l'historique
- **GIVEN** un lien symbolique vers un fichier sentinelle à l'emplacement du temporaire de l'historique
- **WHEN** l'historique est écrit
- **THEN** l'écriture échoue et la sentinelle est intacte

#### Scenario: Plus d'entrée de liste pour le module
- **GIVEN** le dépôt après ce changement
- **WHEN** `python scripts/architecture/check-private-io.py` est exécuté
- **THEN** il réussit et la liste ne contient plus d'entrée sous `minos-application/`

### Requirement: La configuration du backend MCP est écrite par une primitive privée
Le fichier de configuration du backend MCP SHALL être écrit par une primitive d'E/S privée, sans appel brut de `Files` listé dans la liste de tolérance, avec un contenu identique à l'octet près. Un fichier temporaire remplacé par un lien symbolique SHALL faire échouer la sauvegarde sans modifier la cible du lien.

#### Scenario: Lien substitué au temporaire
- **GIVEN** un lien symbolique vers un fichier sentinelle à l'emplacement du fichier temporaire
- **WHEN** `./mvnw -B -ntp -pl minos-app -am test` exécute le test de sauvegarde
- **THEN** la sauvegarde échoue et la sentinelle est intacte

#### Scenario: Plus d'entrée de liste pour le magasin
- **GIVEN** le dépôt après ce changement
- **WHEN** `python scripts/architecture/check-private-io.py` est exécuté
- **THEN** il réussit et la liste ne contient plus d'entrée pour `McpBackendConfigurationStore.java`

### Requirement: Le mécanisme d'ACL Windows est un port découvert, fail-closed
Sur un système de fichiers à ACL, `PrivateLocalStorage` SHALL protéger un objet de l'héritage et lire sa DACL par le port `PlatformAccessControl`, découvert une fois par processus avec son propre chargeur de classes. Si aucune implémentation n'est découverte, ou si plusieurs le sont, le durcissement SHALL échouer par une erreur sans chemin absolu. Le chemin POSIX SHALL NOT consulter le port.

#### Scenario: Aucune implémentation sous Windows
- **GIVEN** un processus Windows dont le chargeur de classes ne fournit aucune implémentation du port
- **WHEN** un répertoire privé est créé
- **THEN** la création échoue, l'erreur ne contient aucun chemin absolu et le répertoire créé est supprimé

#### Scenario: Deux implémentations
- **GIVEN** un chargeur de classes qui fournit deux implémentations du port
- **WHEN** le durcissement consulte le port
- **THEN** il échoue sans écrire la DACL

#### Scenario: Système POSIX sans implémentation
- **GIVEN** un système de fichiers POSIX et aucune implémentation du port
- **WHEN** un répertoire privé est créé
- **THEN** il est créé avec les droits du propriétaire seul et le port n'est pas consulté

#### Scenario: JAR distribué
- **GIVEN** le JAR ombré de `minos-app`
- **WHEN** le port est découvert
- **THEN** exactement une implémentation est trouvée, celle de `minos-runtime-local`

### Requirement: Le lanceur d'icacls accepte les chemins longs
Le lanceur d'`icacls` de `minos-runtime-local` SHALL passer chaque chemin cible absolu avec le préfixe `\\?\` (`\\?\UNC\` pour un chemin UNC), après avoir refusé un chemin relatif ou porteur d'un caractère de contrôle ou de `*?"<>|`. Il SHALL servir à la fois au port d'ACL et aux octrois du bac à sable.

#### Scenario: Objet au chemin de plus de 260 caractères
- **GIVEN** sous Windows, un répertoire privé à créer sous un chemin de plus de 260 caractères
- **WHEN** il est créé par `PrivateLocalStorage`
- **THEN** il est créé, protégé de l'héritage, et sa DACL a été lue

#### Scenario: Chemin refusé avant le lancement
- **GIVEN** un chemin cible relatif ou porteur d'un caractère interdit
- **WHEN** le lanceur est appelé
- **THEN** aucun processus n'est lancé et l'appel échoue sans nommer le chemin

### Requirement: La doublure de test du port reste alignée sur le mécanisme réel
La doublure de test du port publiée par le `test-jar` de `minos-engine` SHALL produire, sous Windows, la même DACL que l'implémentation de `minos-runtime-local` sur des objets identiques. Seuls les modules qui ne voient pas `minos-runtime-local` SHALL l'enregistrer, et le `test-jar` SHALL NOT porter son enregistrement.

#### Scenario: Parité des deux implémentations
- **GIVEN** sous Windows, deux répertoires identiques dont le parent porte un refus conditionnel et une autorisation conditionnelle héritables
- **WHEN** l'un est durci par la doublure et l'autre par l'implémentation réelle
- **THEN** les deux SDDL relus sont égaux, au SID près des objets

#### Scenario: Module supérieur
- **GIVEN** les tests de `minos-bootstrap`, qui voient le `test-jar` du moteur et `minos-runtime-local`
- **WHEN** le port est découvert
- **THEN** une seule implémentation est trouvée, celle de `minos-runtime-local`

### Requirement: Un dossier privé préexistant n'est durci que s'il appartient à l'utilisateur
Sur un système de fichiers à ACL, avant de rendre privé un dossier qui existait déjà, MINOS SHALL refuser le profil de l'utilisateur, un de ses parents et une racine de volume, ainsi qu'un dossier qui n'appartient pas à l'utilisateur courant. Un refus SHALL laisser la DACL inchangée et SHALL être signalé sans chemin absolu.

#### Scenario: MINOS_HOME posé sur le profil
- **GIVEN** un `MINOS_HOME` égal au profil de l'utilisateur sous Windows
- **WHEN** MINOS ouvre ce dossier comme stockage privé
- **THEN** l'ouverture échoue avec un message qui demande un dossier dédié, et la DACL du profil est inchangée

#### Scenario: Dossier préexistant d'un autre compte
- **GIVEN** un dossier existant dont le propriétaire n'est pas l'utilisateur courant, sous Windows
- **WHEN** MINOS le demande comme dossier privé
- **THEN** la demande échoue sans chemin dans le message, et la DACL est inchangée

#### Scenario: Dossier de l'utilisateur
- **GIVEN** un dossier absent, ou existant et propriété de l'utilisateur courant
- **WHEN** MINOS le demande comme dossier privé
- **THEN** le dossier est réservé à son propriétaire comme avant
