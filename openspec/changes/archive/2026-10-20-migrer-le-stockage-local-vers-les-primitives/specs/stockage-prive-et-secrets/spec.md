## ADDED Requirements

### Requirement: Le stockage local écrit ses fichiers par les primitives d'E/S privées
Le code de production de `minos-storage-local` SHALL écrire ses snapshots, pointeurs, empreintes, vecteurs, sessions d'observation, fichiers de propriétés et sauvegardes par les primitives d'E/S privées, sans appel brut de `Files` listé dans la liste de tolérance. Une écriture dont la cible est un lien symbolique SHALL échouer sans modifier la cible du lien.

#### Scenario: Lien substitué au fichier d'un snapshot
- **GIVEN** un lien symbolique vers un fichier sentinelle à l'emplacement du fichier de snapshot à écrire
- **WHEN** `./mvnw -B -ntp -pl minos-storage-local -am test` exécute les tests de lien du module
- **THEN** l'écriture échoue et la sentinelle est intacte

#### Scenario: Plus aucune entrée de liste pour le module
- **GIVEN** le dépôt après ce changement
- **WHEN** `python scripts/architecture/check-private-io.py` est exécuté
- **THEN** il réussit et la liste ne contient plus d'entrée sous `minos-storage-local/`

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
