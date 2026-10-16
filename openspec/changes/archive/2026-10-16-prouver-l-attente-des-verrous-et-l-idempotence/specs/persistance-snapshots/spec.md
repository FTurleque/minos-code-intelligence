## ADDED Requirements

### Requirement: Une seconde publication identique ne change pas l'état persisté
Publier deux fois le même snapshot, ou la même empreinte, sous le même identifiant SHALL laisser le même nombre de lignes persistées et le même contenu rechargé qu'après la première publication. Le test d'idempotence de chaque store PostgreSQL SHALL relire l'état après le second appel et SHALL échouer si une ligne est dupliquée ou si le contenu rechargé diffère.

#### Scenario: Snapshot publié deux fois
- **GIVEN** un snapshot publié une fois dans le store PostgreSQL
- **WHEN** il est publié une seconde fois avec le même contenu
- **THEN** le nombre de lignes du projet est inchangé et le snapshot rechargé est égal au snapshot publié

#### Scenario: Empreinte publiée deux fois
- **GIVEN** une empreinte de projet publiée une fois
- **WHEN** elle est publiée une seconde fois
- **THEN** une seule empreinte active est relue et elle est égale à l'empreinte publiée

#### Scenario: Duplication détectée
- **GIVEN** une publication modifiée localement pour insérer sans contrôle d'existence
- **WHEN** `./mvnw -B -ntp -pl minos-storage-postgresql -am verify` est exécuté avec PostgreSQL disponible
- **THEN** le test d'idempotence échoue

### Requirement: Une migration rejouée laisse le schéma à la version courante
Exécuter deux fois la migration du schéma PostgreSQL SHALL laisser dans `schema_version` chaque version de 1 à la version courante exactement une fois. Une base dont la version est supérieure à la version courante SHALL être refusée sans modification.

#### Scenario: Migration rejouée
- **GIVEN** une base migrée à la version courante
- **WHEN** la migration est exécutée de nouveau
- **THEN** `schema_version` contient les mêmes versions, une fois chacune

#### Scenario: Schéma plus récent que le runtime
- **GIVEN** une base dont `schema_version` porte une version supérieure à la version courante
- **WHEN** la migration est exécutée
- **THEN** elle échoue en nommant la version trouvée et `schema_version` est inchangée

### Requirement: L'attente d'un verrou de mutation est prouvée par un signal observé
Un écrivain concurrent d'un même projet SHALL attendre la libération du verrou de mutation ou du bail du projet. Le test qui le prouve SHALL observer l'entrée du concurrent dans l'attente (verrou PostgreSQL non accordé dans `pg_locks`, file d'attente d'un verrou de la JVM, ou signal positif borné pour un verrou de fichier) avant d'affirmer l'absence d'effet, et SHALL NOT s'appuyer sur un délai fixe.

#### Scenario: Concurrent en attente d'un verrou consultatif
- **GIVEN** un détenteur du verrou de mutation d'un projet et un concurrent dont le verrou figure comme non accordé dans `pg_locks`
- **WHEN** l'état du projet est relu avant la libération
- **THEN** il est inchangé, puis le concurrent aboutit après la libération

#### Scenario: Verrou retiré
- **GIVEN** une copie locale où l'acquisition du verrou est neutralisée
- **WHEN** le test de verrou correspondant est exécuté
- **THEN** il échoue

#### Scenario: Signal d'attente jamais observé
- **GIVEN** un concurrent qui n'entre pas en attente avant l'échéance du test
- **WHEN** l'échéance est atteinte
- **THEN** le test échoue avec un message et ne passe pas par défaut
