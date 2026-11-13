# Inspection PostgreSQL par pgAdmin — parcours proposé

Complète l'[étude](README.md). Tout ce qui suit est une **proposition** non implémentée. Les faits sur le schéma et le déploiement sont ceux du § 1 de l'étude.

## 1. Ce que pgAdmin est, et n'est pas

pgAdmin est une **surface d'administration technique**. Ses accès SQL ne passent par aucun service MINOS : ils ne reproduisent ni la résolution des projets, ni l'alignement sur le snapshot actif, ni les bornes des recherches. Ce qu'un rôle PostgreSQL peut lire, pgAdmin le montre.

Deux identités distinctes, à ne pas confondre :

| Identité | Rôle | Stockage |
|---|---|---|
| **Compte pgAdmin** | ouvre l'interface web de pgAdmin | base de configuration de pgAdmin (volume dédié) |
| **Rôle PostgreSQL** `minos_inspect` | exécute les requêtes | catalogue PostgreSQL |

Restreindre le compte pgAdmin ne restreint pas ce que le rôle PostgreSQL peut faire, et inversement.

Limites de l'inspection SQL : `file_id` se lit directement ; le nom, le module et le langage d'un symbole ne se lisent **pas** en SQL (le snapshot structurel est un binaire, `PostgresSchemaMigrator.java:83`). Pour eux, utiliser l'explorateur ou la CLI.

## 2. Déploiement

### 2.1 PostgreSQL géré par MINOS (runtime Docker)

Service Compose optionnel, hors profil par défaut :

| Élément | Proposition |
|---|---|
| Service | `minos-pgadmin`, profil `pgadmin`, dans `docker/compose-mcp.connected.yaml` |
| Image | `dpage/pgadmin4`, épinglée `tag@sha256:…` (gate `check-image-pins.py`) ; version à choisir à l'implémentation |
| Limites | bloc `x-limits-pgadmin` (gate `check-compose-limits.py`) |
| Réseaux | `minos-runtime` (pour joindre `minos-postgres`) et un réseau d'entrée dédié `minos-inspect-ingress` |
| Publication | `127.0.0.1:${MINOS_PGADMIN_PORT}:80` uniquement |
| PostgreSQL | **reste non publié** sur l'hôte |
| Compte pgAdmin | `PGADMIN_DEFAULT_EMAIL`, `PGADMIN_DEFAULT_PASSWORD_FILE` (fichier privé hors dépôt, monté en lecture seule) |
| Serveur préenregistré | `servers.json` généré dans le dossier d'exécution : hôte `minos-postgres`, base, utilisateur `minos_inspect` ; chargé au premier lancement |
| Mot de passe du rôle | fichier `pgpass` privé, monté en lecture seule ; jamais dans `servers.json` ni dans un fichier versionné |
| Persistance | volume `minos-pgadmin-data` sur `/var/lib/pgadmin` |
| Durcissement | `cap_drop: [ALL]`, `no-new-privileges`, vérification de mises à jour désactivée |

`[Hypothèse à vérifier]` Un conteneur attaché au seul réseau `minos-runtime` (`internal: true`, `compose-mcp.connected.yaml:393-394`) ne peut pas publier de port : d'où le réseau d'entrée dédié. Contrepartie : ce réseau donne au conteneur pgAdmin un accès sortant, à restreindre ou à documenter.

Cycle de vie, par `docker/scripts/mcp-lifecycle.ps1` :

| Action | Effet |
|---|---|
| `EnablePgAdmin` | crée le rôle `minos_inspect`, écrit les fichiers privés, démarre le service |
| `StopPgAdmin` | arrête le service ; configuration conservée |
| `RemovePgAdmin` | supprime le conteneur, le volume `minos-pgadmin-data`, les fichiers privés et le rôle `minos_inspect` |

Aucune de ces actions ne touche au volume `minos-postgres-data` (externe au projet Compose, `compose-mcp.connected.yaml:408-410`) ni aux données MINOS.

### 2.2 PostgreSQL externe

MINOS ne crée aucun rôle sur une instance qu'il ne gère pas. Le script SQL du § 3 est fourni à l'administrateur de l'instance ; pgAdmin, s'il est voulu, est celui de l'opérateur. Hors périmètre du premier lot.

## 3. Rôle de lecture

Un réglage de session (`default_transaction_read_only`) **n'est pas une protection** : la session peut le lever. La lecture seule vient des **droits**.

```sql
-- Exécuté par l'administrateur de l'instance. <SCHEMA> : schéma MINOS (minos.postgres.schema).
CREATE ROLE minos_inspect LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
-- Mot de passe défini à part, depuis un fichier privé : \password minos_inspect

REVOKE ALL ON SCHEMA <SCHEMA> FROM minos_inspect;
GRANT CONNECT ON DATABASE minos TO minos_inspect;
GRANT USAGE ON SCHEMA <SCHEMA> TO minos_inspect;

GRANT SELECT ON <SCHEMA>.semantic_documents,
                <SCHEMA>.semantic_index_meta,
                <SCHEMA>.knowledge_active,
                <SCHEMA>.project_index_state,
                <SCHEMA>.schema_version
             TO minos_inspect;

-- projects : sans root_value, qui contient la racine du projet sur le poste.
GRANT SELECT (id, display_name, workspace_id, created_at, updated_at)
          ON <SCHEMA>.projects TO minos_inspect;

-- Ceintures, pas protections : bornes de session.
ALTER ROLE minos_inspect SET default_transaction_read_only = on;
ALTER ROLE minos_inspect SET statement_timeout = '30s';
```

Non accordés : `knowledge_snapshots` (binaire du code), `indexing_runs`, `runtime_sessions`, `fingerprint_*`, `workspaces`. Aucun `ALTER DEFAULT PRIVILEGES` : une table créée par une migration future reste invisible tant qu'elle n'est pas accordée explicitement.

**Vérification des droits réels** (critère d'acceptation du lot 1) :

```sql
-- Connecté en tant que minos_inspect.
SELECT table_name,
       has_table_privilege(current_user, format('%I.%I', table_schema, table_name), 'SELECT') AS lecture,
       has_table_privilege(current_user, format('%I.%I', table_schema, table_name), 'INSERT, UPDATE, DELETE, TRUNCATE') AS ecriture
FROM information_schema.tables
WHERE table_schema = current_schema()
ORDER BY table_name;
-- Attendu : ecriture = false partout ; lecture = true pour les seules tables accordées.

SELECT rolsuper, rolcreaterole, rolcreatedb, rolbypassrls FROM pg_roles WHERE rolname = current_user;
-- Attendu : false, false, false, false.

SET default_transaction_read_only = off;
DELETE FROM semantic_documents WHERE false;
-- Attendu : ERROR: permission denied for table semantic_documents
```

## 4. Requêtes de diagnostic

Toutes en lecture seule. `<PROJECT_ID>` est un UUID de projet ; aucune requête ne mélange projets, snapshots ou modèles par défaut. La jointure sur `semantic_index_meta` restreint aux documents de l'index courant, comme le fait MINOS (`PostgresSemanticReadQueries.java:42-45`).

### 4.1 Projets et index disponibles

```sql
SELECT p.id, p.display_name,
       m.provider_id, m.model_id, m.dimensions,
       m.snapshot_id AS snapshot_indexe,
       to_timestamp(m.built_at / 1000.0) AS construit_le
FROM projects p
LEFT JOIN semantic_index_meta m ON m.project_id = p.id
ORDER BY p.display_name;
```

### 4.2 Documents par projet, snapshot et nature

```sql
SELECT d.project_id, d.snapshot_id, d.kind, count(*) AS documents
FROM semantic_documents d
GROUP BY d.project_id, d.snapshot_id, d.kind
ORDER BY d.project_id, d.snapshot_id, d.kind;
```

### 4.3 Contenu et métadonnées d'un projet

```sql
SELECT d.stable_key, d.kind, d.file_id, d.start_line, d.end_line,
       d.source_id, d.checksum, left(d.content, 200) AS apercu
FROM semantic_documents d
JOIN semantic_index_meta m ON m.project_id = d.project_id AND m.snapshot_id = d.snapshot_id
WHERE d.project_id = '<PROJECT_ID>'::uuid
ORDER BY d.stable_key
LIMIT 50;
```

### 4.4 Modèle et dimensions réellement stockés

La colonne `embedding` n'a pas de dimension déclarée : cette requête compare les vecteurs stockés à la déclaration de l'index.

```sql
SELECT m.project_id, m.provider_id, m.model_id,
       m.dimensions AS dimensions_declarees,
       vector_dims(d.embedding) AS dimensions_stockees,
       count(*) AS documents
FROM semantic_index_meta m
JOIN semantic_documents d ON d.project_id = m.project_id AND d.snapshot_id = m.snapshot_id
GROUP BY m.project_id, m.provider_id, m.model_id, m.dimensions, vector_dims(d.embedding)
ORDER BY m.project_id;
-- Attendu : une ligne par projet, dimensions_declarees = dimensions_stockees.
```

### 4.5 Voisins d'un document

Les deux côtés de la comparaison viennent du même projet et du même snapshot indexé : même modèle, même espace. `<=>` rend une **distance** cosinus ; la similarité est `1 − distance`, comme dans MINOS (`PostgresSemanticReadQueries.java:155`).

```sql
SELECT n.stable_key, n.kind, n.file_id, n.start_line, n.end_line,
       n.embedding <=> s.embedding        AS distance_cosinus,
       1.0 - (n.embedding <=> s.embedding) AS similarite_cosinus
FROM semantic_documents s
JOIN semantic_index_meta m ON m.project_id = s.project_id AND m.snapshot_id = s.snapshot_id
JOIN semantic_documents n ON n.project_id = s.project_id
                         AND n.snapshot_id = s.snapshot_id
                         AND n.stable_key <> s.stable_key
WHERE s.project_id = '<PROJECT_ID>'::uuid
  AND s.stable_key = '<STABLE_KEY>'
ORDER BY n.embedding <=> s.embedding, n.stable_key
LIMIT 20;
```

Ce parcours est exact et séquentiel : aucun index vectoriel n'existe (`PostgresSchemaMigrator.java:98`), ce qui est le choix en vigueur (BE-0031).

### 4.6 Décalage entre index sémantique et snapshot actif

```sql
SELECT p.display_name,
       a.snapshot_id AS snapshot_actif,
       m.snapshot_id AS snapshot_indexe,
       CASE
         WHEN a.snapshot_id IS NULL THEN 'aucun snapshot actif'
         WHEN m.snapshot_id IS NULL THEN 'index semantique absent'
         WHEN a.snapshot_id = m.snapshot_id THEN 'aligne'
         ELSE 'index en retard sur le snapshot actif'
       END AS diagnostic
FROM projects p
LEFT JOIN knowledge_active a ON a.project_id = p.id
LEFT JOIN semantic_index_meta m ON m.project_id = p.id
ORDER BY p.display_name;
```

Ce diagnostic ne couvre que l'alignement de snapshot. Le décalage de **modèle** (`SEMANTIC_INDEX_MODEL_STALE`) dépend de la configuration courante du provider, que la base ne connaît pas : il se lit par `semantic-index-status`.

### 4.7 Documents hors de l'index courant

```sql
SELECT d.project_id, d.snapshot_id, count(*) AS documents_hors_index
FROM semantic_documents d
LEFT JOIN semantic_index_meta m ON m.project_id = d.project_id AND m.snapshot_id = d.snapshot_id
WHERE m.project_id IS NULL
GROUP BY d.project_id, d.snapshot_id;
-- Attendu : aucune ligne. Une ligne signale des documents que MINOS ne lit plus.
```

Ces requêtes sont écrites d'après le schéma relu au HEAD ; **elles n'ont pas été exécutées** contre une instance pendant l'étude.
