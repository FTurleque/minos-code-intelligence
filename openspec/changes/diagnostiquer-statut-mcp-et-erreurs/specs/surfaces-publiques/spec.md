# Spec Delta

## Purpose

Garantit que les surfaces publiques versionnées de MINOS (CLI, API Java, MCP) rapportent des
erreurs qui permettent à l'appelant de se corriger sans fuite d'information interne, que les
outils de statut respectent la lecture seule et restent légers, et que les valeurs par défaut
n'invalident pas ce que le schéma publié accepte.

## ADDED Requirements

### Requirement: Une référence de projet inconnue, ambiguë ou invalide reçoit une erreur actionnable sans fuite
Le système SHALL rapporter, sur la CLI, l'API Java et le MCP, une référence de projet inconnue, ambiguë ou invalide par un message qui nomme la cause et l'action corrective (nom enregistré ou identifiant, liste des projets). Le message MUST NOT recopier la valeur de l'appelant lorsqu'elle ressemble à un chemin absolu, à un secret ou à une chaîne de connexion.

#### Scenario: Référence de type chemin Windows sur le MCP
- **WHEN** un client appelle l'outil de statut d'index avec une référence de projet de la forme d'un chemin de lecteur Windows
- **THEN** la réponse est une erreur d'outil qui indique que le projet est inconnu et qu'il faut fournir un nom enregistré ou un identifiant de projet
- **AND** le texte ne contient ni la lettre de lecteur, ni aucun segment du chemin fourni

#### Scenario: Référence de type chemin POSIX sur le MCP
- **WHEN** un client appelle l'outil de statut d'index ou de structure de projet avec une référence de la forme d'un chemin absolu POSIX
- **THEN** la réponse est la même erreur actionnable, sans aucun segment du chemin

#### Scenario: Référence de type chemin sur la CLI
- **WHEN** l'utilisateur exécute la commande de statut d'index avec un chemin de dépôt comme référence
- **THEN** la sortie d'erreur indique que la référence est inconnue et comment la corriger, avec le code de sortie d'erreur d'exécution
- **AND** elle ne se réduit pas au nom d'une classe d'exception et ne contient pas le chemin

#### Scenario: Référence de type chemin sur l'API Java
- **WHEN** un appelant de l'API Java demande l'inspection d'un projet par une référence de la forme d'un chemin
- **THEN** il reçoit une erreur publique de requête invalide dont le message est actionnable et sans le chemin
- **AND** l'erreur n'expose aucune cause interne

#### Scenario: Nom contenant un mot sensible
- **WHEN** la référence est un simple nom qui contient un mot que la politique de redaction juge sensible (par exemple un nom de service contenant « api-key »)
- **THEN** le message actionnable est rendu sans recopier ce nom, sur les trois surfaces

#### Scenario: Nom inconnu non sensible conservé
- **WHEN** la référence est un simple nom inconnu qui ne ressemble ni à un chemin ni à un secret
- **THEN** le message reste identique à celui d'avant ce changement (« unknown project: » suivi du nom) sur les trois surfaces

#### Scenario: Nom ambigu
- **WHEN** la référence désigne plusieurs projets enregistrés de même nom
- **THEN** le message indique l'ambiguïté et demande l'identifiant du projet, sans recopier un nom qui ressemble à un secret

#### Scenario: Référence vide ou trop longue
- **WHEN** la référence est vide ou dépasse la limite d'octets acceptée
- **THEN** le message indique que l'identifiant est invalide, sans recopier la valeur

### Requirement: Les échecs d'usage et de configuration connus du MCP sont diagnostiquables
Le système SHALL rapporter sur le MCP, par un message fixe et sans donnée sensible, les échecs dont la cause est connue et sûre : registre dont des entrées sont illisibles, mode équipe désactivé, jeton d'équipe absent. Ces échecs MUST NOT être confondus avec l'erreur générique d'exécution d'outil.

#### Scenario: Nom introuvable à côté d'une entrée de registre illisible
- **WHEN** un client appelle un outil avec un nom absent alors qu'au moins une entrée du registre est illisible
- **THEN** l'erreur indique le nombre d'entrées illisibles et que l'existence du projet ne peut pas être établie
- **AND** elle ne contient aucun chemin

#### Scenario: Mode équipe désactivé
- **WHEN** un client appelle un outil d'équipe alors que le mode équipe est désactivé
- **THEN** l'erreur indique que le mode équipe est désactivé

#### Scenario: Jeton d'équipe absent
- **WHEN** un client appelle un outil d'équipe sans jeton d'équipe dans l'environnement du processus
- **THEN** l'erreur indique qu'un jeton d'équipe est requis
- **AND** elle ne contient aucune valeur de jeton

### Requirement: Les échecs internes restent opaques et sans fuite
Le système SHALL continuer de rapporter par l'erreur générique d'exécution d'outil tout échec dont la cause n'est pas connue et sûre, ou dont le message contient une chaîne de connexion, un secret, un jeton ou un chemin. Le message interne MUST NOT être exposé à l'appelant.

#### Scenario: Échec d'E/S contenant des informations d'exploitation
- **WHEN** un outil échoue par une erreur d'E/S dont le message contient une chaîne de connexion, un mot de passe et un chemin
- **THEN** la réponse est l'erreur générique d'exécution d'outil
- **AND** elle ne contient ni la chaîne de connexion, ni le mot de passe, ni le chemin

#### Scenario: Argument invalide dont le message contient un secret
- **WHEN** un outil échoue par une erreur d'argument dont le message contient un jeton et un chemin, et qui n'est pas une erreur de référence de projet
- **THEN** la réponse est l'erreur générique d'exécution d'outil, sans ces valeurs

#### Scenario: Échec imprévu
- **WHEN** un outil échoue par une exception imprévue
- **THEN** la réponse est l'erreur générique d'exécution d'outil

### Requirement: Un échec d'outil MCP est diagnostiquable par l'opérateur sans exposer son message
Quand un outil MCP échoue pour une cause interne, le système SHALL écrire dans le journal de l'opérateur le nom de l'outil, la classe de l'exception et la classe de sa cause racine. Le journal MUST NOT contenir le message de l'exception ni la valeur d'un argument de l'appelant.

#### Scenario: Cause racine journalisée
- **WHEN** un outil échoue par une exception qui enveloppe une cause racine
- **THEN** l'entrée de journal nomme l'outil, la classe de l'exception et la classe de la cause racine

#### Scenario: Aucun message dans le journal
- **WHEN** l'exception contient un secret ou un chemin dans son message
- **THEN** l'entrée de journal ne contient ni ce secret ni ce chemin

### Requirement: Le statut MCP n'a aucun effet de bord
Un appel à l'outil de statut d'index ou à l'outil de structure de projet SHALL NOT écrire, créer, supprimer ni modifier de fichier, SHALL NOT calculer de condensat de l'arbre des outils, SHALL NOT lancer de processus et SHALL NOT interroger l'état des runtimes providers, y compris au premier appel et sur Windows.

#### Scenario: Arborescence de MINOS_HOME inchangée
- **WHEN** un client appelle chacun des deux outils sur une application déjà ouverte
- **THEN** l'arborescence de MINOS_HOME (chemins, tailles, dates de modification) est identique avant et après l'appel

#### Scenario: Runtimes providers jamais interrogés
- **WHEN** un client appelle l'un des deux outils alors que le gestionnaire de runtimes compte ses appels
- **THEN** aucun appel d'inspection, de liste ou d'installation n'est observé

#### Scenario: Aucun artefact de sonde sous Windows
- **WHEN** l'un des deux outils est appelé sur Windows
- **THEN** aucun script lanceur ni répertoire de sonde d'isolation n'est créé ou modifié

#### Scenario: Les commandes d'inspection explicites inspectent toujours
- **WHEN** l'utilisateur exécute la commande de liste des providers ou le diagnostic de la CLI
- **THEN** l'état d'exécution réel des runtimes est inspecté et rapporté comme avant ce changement

### Requirement: L'état d'exécution des runtimes n'est jamais présenté comme inspecté par le statut MCP
Le champ `providerProfiles` de l'outil de statut d'index et de l'outil de structure de projet SHALL être conservé, avec ses neuf clés dans leur ordre et ses capacités triées, alimenté par les profils statiques des providers. Son état d'exécution MUST valoir « NOT_INSPECTED » avec un diagnostic qui renvoie aux commandes d'inspection, jamais un état obtenu sans inspection.

#### Scenario: Forme du champ conservée
- **WHEN** un client appelle l'un des deux outils sur un projet enregistré
- **THEN** chaque profil de provider contient les neuf clés habituelles dans le même ordre, avec ses capacités et ses limites

#### Scenario: État non inspecté explicite
- **WHEN** un client lit l'état d'exécution d'un profil
- **THEN** il vaut « NOT_INSPECTED » et le diagnostic indique d'utiliser la commande de liste des providers ou le diagnostic pour l'état réel

#### Scenario: Aucun état réel simulé
- **WHEN** le runtime d'un provider n'est pas installé sur l'hôte
- **THEN** le statut MCP ne rapporte ni « READY » ni aucun autre état déduit sans inspection

### Requirement: Le statut d'index ne dépend pas de la découverte du dépôt
Le système SHALL calculer l'état d'index d'un projet pour la commande de statut d'index de la CLI et pour l'outil de statut d'index du MCP sans parcourir l'arbre du dépôt. Un échec de découverte (répertoire illisible, budget de traversée dépassé) MUST NOT faire échouer le statut quand l'état d'index est lisible.

#### Scenario: Répertoire illisible dans la racine du projet
- **WHEN** la racine d'un projet enregistré contient un sous-répertoire que le processus ne peut pas lire
- **THEN** le statut répond avec l'état d'index, sans erreur

#### Scenario: Budget de traversée dépassé
- **WHEN** le budget de traversée de la découverte est trop petit pour le dépôt
- **THEN** le statut répond avec l'état d'index, sans erreur

#### Scenario: Découverte jamais invoquée
- **WHEN** le statut est demandé avec un service de découverte qui compte ses appels
- **THEN** la découverte n'est pas appelée

#### Scenario: Racine de projet absente
- **WHEN** la racine enregistrée du projet n'existe plus
- **THEN** le statut répond avec l'état d'index et indique que la racine n'est pas disponible

#### Scenario: Les commandes de structure découvrent toujours
- **WHEN** l'utilisateur demande l'inspection d'un projet ou la liste des projets
- **THEN** les langages, systèmes de build et modules sont toujours rapportés par découverte, comme avant ce changement

### Requirement: Une combinaison de bornes acceptée par le schéma publié n'est pas refusée par les défauts
Quand le client fournit `maxTokens` sans `maxTokensPerDocument`, le système SHALL borner le plafond par document par défaut à `maxTokens`. Une combinaison explicitement incohérente fournie par le client MUST rester refusée avec un message actionnable.

#### Scenario: maxTokens bas seul sur le MCP
- **WHEN** un client appelle l'outil de contexte hybride avec `maxTokens` égal à 500 et sans plafond par document
- **THEN** la requête est acceptée et le plafond par document effectif ne dépasse pas 500

#### Scenario: maxTokens bas seul sur la CLI
- **WHEN** l'utilisateur exécute la commande de contexte hybride de la CLI IDE avec un plafond total de 200 jetons et sans plafond par document
- **THEN** la commande est acceptée

#### Scenario: Valeur minimale du schéma
- **WHEN** `maxTokens` vaut la valeur minimale du schéma publié
- **THEN** la requête est acceptée

#### Scenario: Défaut inchangé au-dessus de 800
- **WHEN** `maxTokens` vaut 4 000 ou plus et que le plafond par document est absent
- **THEN** le plafond par document effectif vaut 800 comme avant ce changement

#### Scenario: Combinaison explicite incohérente refusée
- **WHEN** le client fournit `maxTokens` égal à 500 et un plafond par document de 900
- **THEN** la requête est refusée avec un message qui indique que le plafond par document doit être compris entre 32 et `maxTokens`

### Requirement: Tout échec d'ouverture de l'API Java est une erreur publique classée et redactée
Toute exception levée à l'ouverture d'une façade de l'API Java SHALL être traduite en `MinosApiException` : erreur de configuration en requête invalide, état indisponible en indisponibilité, erreur d'E/S en échec d'E/S. Le message MUST passer par la politique de redaction publique et l'erreur MUST NOT exposer de cause interne.

#### Scenario: Backend de stockage inconnu
- **WHEN** la configuration désigne un backend de stockage non supporté et qu'un appelant ouvre une façade de l'API Java
- **THEN** il reçoit une `MinosApiException` de requête invalide, et non une exception d'exécution brute

#### Scenario: Racine de composition absente ou ambiguë
- **WHEN** l'ouverture échoue parce que la racine de composition est absente ou ambiguë
- **THEN** l'appelant reçoit une `MinosApiException` d'indisponibilité

#### Scenario: Valeur de configuration sensible
- **WHEN** la valeur de configuration fautive ressemble à un chemin ou à un secret
- **THEN** le message public ne recopie pas cette valeur

#### Scenario: Les trois façades
- **WHEN** l'ouverture échoue pour une façade principale, multi-dépôts ou de plateforme de providers
- **THEN** les trois rapportent la même classe d'erreur publique

#### Scenario: Aucune ressource conservée
- **WHEN** l'ouverture échoue
- **THEN** l'application partiellement ouverte est fermée et aucune ressource n'est conservée

### Requirement: Une erreur d'exécution d'une opération IDE n'est pas rapportée comme un échec de démarrage
Quand une opération de la commande IDE de la CLI échoue à l'exécution par une erreur d'E/S ou une exception d'exécution, le système SHALL la rapporter comme une erreur de la commande, avec le code de sortie d'erreur d'exécution. Seul un échec d'ouverture de MINOS_HOME MUST être rapporté comme échec de démarrage.

#### Scenario: Erreur d'E/S d'un service
- **WHEN** un service appelé par une opération IDE lève une erreur d'E/S
- **THEN** la sortie d'erreur commence par « error: » sans mentionner un échec de démarrage, et le code de sortie est celui d'une erreur d'exécution

#### Scenario: Échec d'ouverture de MINOS_HOME conservé
- **WHEN** MINOS_HOME ne peut pas être ouvert
- **THEN** la sortie d'erreur mentionne l'échec de démarrage comme avant ce changement

#### Scenario: Erreur d'argument d'un service
- **WHEN** un service lève une erreur d'argument invalide
- **THEN** le comportement reste celui d'avant ce changement (erreur d'exécution, code de sortie 1)
