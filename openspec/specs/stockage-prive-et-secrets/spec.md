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
