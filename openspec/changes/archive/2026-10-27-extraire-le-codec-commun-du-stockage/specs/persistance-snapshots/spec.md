## ADDED Requirements

### Requirement: Les deux backends lisent et écrivent les snapshots par le même codec
Les backends local et PostgreSQL SHALL encoder et décoder les snapshots de connaissance par le codec de `com.minos.storage.codec` seul. Chaque fixture V2 publiée SHALL se lire par chacun des deux backends, et la fixture V1 par le backend local, avec le contenu attendu. Une fixture SHALL se réencoder à l'identique, même empreinte SHA-256. Un format inconnu SHALL être refusé.

#### Scenario: Fixture V2 lue par chaque backend
- **GIVEN** `v2-well-formed.knowledge` et `v2-lone-surrogate.knowledge` de `src/test/resources/snapshot-formats/base-d9ae1005/`
- **WHEN** un test du magasin fichier et un test du magasin PostgreSQL les lisent, chacun de son côté
- **THEN** chacun obtient un snapshot égal à `LegacySnapshotContent`, substitut isolé compris

#### Scenario: Fixture V1 lue par le backend local
- **GIVEN** la fixture `v1-symbols`
- **WHEN** le magasin fichier l'ouvre
- **THEN** il obtient les symboles attendus par `LegacySnapshotContent`

#### Scenario: Réencodage identique
- **GIVEN** le snapshot lu depuis `v2-well-formed.knowledge`
- **WHEN** le codec V2 le réencode
- **THEN** les octets écrits ont la même empreinte SHA-256 que la fixture

#### Scenario: Version de format inconnue
- **GIVEN** une charge utile dont l'en-tête déclare la version 9
- **WHEN** le codec la lit
- **THEN** la lecture échoue par `unsupported knowledge snapshot format version: 9`, et aucun snapshot n'est renvoyé

### Requirement: Le pointeur actif garde son format à l'octet près
Le pointeur actif du backend local SHALL écrire et lire ses champs par les primitives publiques du codec (`SnapshotBinaryPrimitives`), sans dépendre d'un membre de paquet du codec. Chaque `active.pointer` des fixtures historiques SHALL se relire puis se réécrire à l'identique. Un pointeur suivi d'octets en trop SHALL être refusé.

#### Scenario: Pointeur historique réécrit
- **GIVEN** chaque fichier `active.pointer` des fixtures historiques
- **WHEN** le pointeur actif le lit, puis le réécrit dans un dossier temporaire
- **THEN** le fichier réécrit est identique, octet pour octet, au fichier d'origine

#### Scenario: Octets en trop
- **GIVEN** un `active.pointer` historique suivi d'un octet supplémentaire
- **WHEN** le pointeur actif le lit
- **THEN** la lecture échoue par `unexpected trailing data in active snapshot pointer`
