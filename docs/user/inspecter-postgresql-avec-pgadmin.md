# Inspecter PostgreSQL avec pgAdmin

Le runtime Docker de MINOS peut démarrer [pgAdmin](https://www.pgadmin.org/) à côté du PostgreSQL qu'il gère, pour lire ce que contient un index sémantique : documents, modèle, dimensions, alignement sur le snapshot actif. Le service est **optionnel** : il n'existe pas tant qu'il n'est pas activé, et le retirer ne touche à aucune donnée MINOS.

Qualification : pgAdmin 9.19.0 avec le PostgreSQL géré du runtime Docker, sur un hôte Windows (Docker Desktop). Non supporté : un PostgreSQL externe, un hôte Linux, un accès depuis une autre machine.

## Ce que pgAdmin est, et n'est pas

pgAdmin est une interface d'administration SQL. Ses requêtes ne passent par aucun service MINOS : elles ne reproduisent ni la résolution des projets, ni les bornes des recherches. Ce qu'un rôle PostgreSQL peut lire, pgAdmin le montre.

Deux identités distinctes :

| Identité | Rôle | Où elle vit |
|---|---|---|
| **Compte pgAdmin** | ouvre l'interface web | configuration de pgAdmin (volume `minos-pgadmin-data`) |
| **Rôle PostgreSQL** `minos_inspect` | exécute les requêtes | PostgreSQL géré |

Restreindre l'un ne restreint pas l'autre. La lecture seule tient aux **droits** du rôle `minos_inspect`, pas à un réglage de session :

- `SELECT` sur `semantic_documents`, `semantic_index_meta`, `knowledge_active`, `project_index_state`, `schema_version` ;
- `SELECT` sur les colonnes `id`, `display_name`, `workspace_id`, `created_at`, `updated_at` de `projects` — **sans** `root_value`, qui contient le dossier du projet sur le poste ;
- aucun droit sur `knowledge_snapshots`, `indexing_runs`, `runtime_sessions`, `fingerprint_snapshots`, `fingerprint_active`, `workspaces` ;
- aucun droit d'écriture, aucun droit de création dans le schéma MINOS ; une table ajoutée par une version future reste invisible tant qu'elle n'est pas accordée.

Limites :

- le nom, le module et le langage d'un symbole ne se lisent pas en SQL (le snapshot structurel est un binaire) ; `file_id` se lit directement ;
- le rôle garde ce que PostgreSQL donne à tous les rôles : il peut créer une table temporaire privée à sa session et des « grands objets », sans effet sur les tables de MINOS ; il est borné à 8 connexions et à 256 Mo de fichiers temporaires, deux bornes qu'il ne peut pas lever ;
- si le rôle `minos_inspect` a été rendu membre d'un autre rôle ou propriétaire d'un objet, `EnablePgAdmin` refuse : `RemovePgAdmin` le supprime, puis l'activation le recrée ;
- pgAdmin est joint par un réseau Docker qui lui donne un accès sortant ; sa vérification de mises à jour est désactivée.

## Activer

PostgreSQL géré doit être configuré et démarré (stockage PostgreSQL choisi à l'installation).

```powershell
.\docker\scripts\prod-mcp-release.ps1 -Action EnablePgAdmin
```

L'action crée ou met à niveau le rôle `minos_inspect`, écrit les fichiers privés, puis démarre pgAdmin. Elle affiche l'adresse (`http://127.0.0.1:5050` par défaut) et le compte (`inspect@minos.example` par défaut). Le mot de passe du compte est la première ligne du fichier `secrets\pgadmin.password` du dossier de données Docker de MINOS (`%LOCALAPPDATA%\MINOS\docker-data`), lisible par votre seul compte Windows.

Dans pgAdmin, le serveur **MINOS (read-only)** est déjà enregistré : il se connecte avec `minos_inspect` sans demander de mot de passe.

L'action se rejoue sans effet de bord : ni les mots de passe ni le serveur enregistré ne changent. Pour changer un mot de passe, supprimer le fichier privé correspondant (`secrets\pgadmin.password` ou `secrets\pgadmin.pgpass`) puis rejouer l'action : elle en génère un nouveau et recrée la configuration de pgAdmin, pour que l'ancien ne reste pas valide. Après une mise à jour de MINOS, la rejouer remet le rôle à niveau et recrée pgAdmin si sa définition a changé.

| Réglage (fichier `.env` du runtime, avant `EnablePgAdmin`) | Défaut | Rôle |
|---|---|---|
| `MINOS_PGADMIN_PORT` | `5050` | port local, entre 1024 et 65535 |
| `MINOS_PGADMIN_EMAIL` | `inspect@minos.example` | nom du compte pgAdmin (aucun courriel n'est envoyé) ; fixé à la première activation : pour le changer, `RemovePgAdmin`, modifier la valeur, puis `EnablePgAdmin` |

PostgreSQL n'est jamais publié sur l'hôte : le seul port ouvert est celui de pgAdmin, sur `127.0.0.1`.

## Arrêter, retirer

```powershell
.\docker\scripts\prod-mcp-release.ps1 -Action StopPgAdmin     # arrête le service, garde tout
.\docker\scripts\prod-mcp-release.ps1 -Action RemovePgAdmin   # retire tout ce que EnablePgAdmin a créé
```

`RemovePgAdmin` supprime le conteneur, le volume `minos-pgadmin-data`, les fichiers privés et le rôle `minos_inspect`. Il ne modifie ni le volume PostgreSQL ni les tables de MINOS. PostgreSQL géré doit être démarré, sinon l'action refuse avant de rien retirer.

La désinstallation du runtime Docker (`-Action Uninstall`) retire le conteneur, le volume et les deux fichiers privés de pgAdmin, mais conserve la base, donc le rôle `minos_inspect` : lancer `RemovePgAdmin` avant de désinstaller pour retirer aussi le rôle.

## Erreurs fréquentes

| Message | Cause | Action |
|---|---|---|
| `pgAdmin is not available in this MINOS Docker runtime` | le runtime n'a pas de PostgreSQL géré, ou a été configuré par une version sans pgAdmin | choisir le stockage PostgreSQL dans l'installateur de cette version, puis rejouer |
| `The managed PostgreSQL ... is not running` | `minos-postgres` est arrêté | reconfigurer le stockage PostgreSQL, puis rejouer |
| `the configured schema holds no MINOS tables` | le schéma désigné par `minos.postgres.schema` ne contient pas les tables de MINOS | vérifier ce réglage et qu'une commande MINOS a déjà ouvert le stockage PostgreSQL, puis rejouer |
| `pgAdmin cannot be started: MINOS_PGADMIN_..._FILE is missing` | un fichier privé a disparu | rejouer `EnablePgAdmin` |
| `The private file secrets/pgadmin.password ... (File ACL is not restricted to the current user ...)` | le fichier garde un accès pour d'autres comptes : il existait avec une liste d'accès plus large, ou le dossier `secrets` n'a aucune permission héritable et Windows donne alors au fichier des accès explicites que MINOS ne retire pas | supprimer le fichier ; si l'erreur revient, rétablir l'héritage des permissions du dossier `secrets`, puis rejouer |
| `The environment of this session defines MINOS_PGADMIN_...` | une variable `MINOS_PGADMIN_*` du terminal remplacerait le réglage du `.env` | la retirer du terminal, puis rejouer |
| `the role minos_inspect is a member of another role` | le rôle a été modifié à la main | `RemovePgAdmin`, puis `EnablePgAdmin` |
| `bind source path does not exist: /MINOS_PGADMIN_PASSWORD_FILE-is-required` | pgAdmin démarré directement par `docker compose`, sans ses fichiers | passer par `EnablePgAdmin` |

Les journaux de pgAdmin se lisent par `docker logs` sur son conteneur.

## Vérifier les droits réels

Dans l'outil de requête de pgAdmin, sur le serveur **MINOS (read-only)** :

```sql
SELECT table_name,
       has_table_privilege(current_user, format('%I.%I', table_schema, table_name), 'SELECT') AS lecture,
       has_table_privilege(current_user, format('%I.%I', table_schema, table_name), 'INSERT, UPDATE, DELETE, TRUNCATE') AS ecriture
FROM information_schema.tables
WHERE table_schema = current_schema()
ORDER BY table_name;
```

Attendu : six lignes, `ecriture` à `f` partout. La liste ne montre que les tables sur lesquelles le rôle détient un droit : les tables non accordées n'y figurent pas. `projects` y figure avec `lecture` à `f`, parce que son droit porte sur des colonnes et non sur la table.

```sql
SET default_transaction_read_only = off;
DELETE FROM semantic_documents WHERE false;
-- ERROR:  permission denied for table semantic_documents

SELECT root_value FROM projects;
-- ERROR:  permission denied for table projects
```

## Requêtes de diagnostic

Toutes en lecture seule. `<PROJECT_ID>` est l'identifiant d'un projet (requête 1). La jointure sur `semantic_index_meta` restreint aux documents de l'index courant, comme le fait MINOS.

### 1. Projets et index disponibles

```sql
SELECT p.id, p.display_name,
       m.provider_id, m.model_id, m.dimensions,
       m.snapshot_id AS snapshot_indexe,
       to_timestamp(m.built_at / 1000.0) AS construit_le
FROM projects p
LEFT JOIN semantic_index_meta m ON m.project_id = p.id
ORDER BY p.display_name;
```

Un projet sans index sémantique a ses colonnes d'index vides.

### 2. Documents par projet, snapshot et nature

```sql
SELECT d.project_id, d.snapshot_id, d.kind, count(*) AS documents
FROM semantic_documents d
GROUP BY d.project_id, d.snapshot_id, d.kind
ORDER BY d.project_id, d.snapshot_id, d.kind;
```

### 3. Contenu et métadonnées d'un projet

```sql
SELECT d.stable_key, d.kind, d.file_id, d.start_line, d.end_line,
       d.source_id, d.checksum, left(d.content, 200) AS apercu
FROM semantic_documents d
JOIN semantic_index_meta m ON m.project_id = d.project_id AND m.snapshot_id = d.snapshot_id
WHERE d.project_id = '<PROJECT_ID>'::uuid
ORDER BY d.stable_key
LIMIT 50;
```

### 4. Modèle et dimensions réellement stockés

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
```

Attendu : une ligne par projet indexé, `dimensions_declarees` égal à `dimensions_stockees`.

### 5. Voisins d'un document

Les deux côtés de la comparaison viennent du même projet et du même snapshot indexé : même modèle, même espace. `<=>` rend une **distance** cosinus ; la similarité est `1 − distance`.

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

Ce parcours est exact et séquentiel : MINOS ne crée pas d'index vectoriel approché. Une requête est interrompue après 30 secondes (borne de session du rôle).

### 6. Décalage entre index sémantique et snapshot actif

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

Ce diagnostic ne couvre que l'alignement de snapshot. Le décalage de **modèle** dépend de la configuration courante du provider, que la base ne connaît pas : il se lit par `semantic status`.

### 7. Documents hors de l'index courant

```sql
SELECT d.project_id, d.snapshot_id, count(*) AS documents_hors_index
FROM semantic_documents d
LEFT JOIN semantic_index_meta m ON m.project_id = d.project_id AND m.snapshot_id = d.snapshot_id
WHERE m.project_id IS NULL
GROUP BY d.project_id, d.snapshot_id;
```

Attendu : aucune ligne. Une ligne signale des documents d'un snapshot que MINOS ne lit plus.
