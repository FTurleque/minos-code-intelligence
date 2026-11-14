# Design

## Contexte

Motivation : `proposal.md`. Parcours détaillé, script du rôle et requêtes : `docs/project/research/explorateur-vectoriel-2026-11/PGADMIN.md`. Conception commune aux trois changements du chantier : `openspec/changes/explorer-l-index-semantique-dans-un-navigateur/design.md` (vue de conteneurs, décision D6). Le changement touche `docker/` et ses scripts, et ajoute un test à `minos-storage`.

## État vérifié au HEAD

Commit `63e0c4ac5288ebd6d52b3462e40e7067d06b36ce` (`develop`). Lectures de source ; aucun conteneur n'a été démarré pour cette spécification.

| Fait | Preuve |
|---|---|
| `minos-postgres` : profil `postgresql`, image épinglée par digest, réseau `minos-runtime` seul, aucun `ports:` | `docker/compose-mcp.connected.yaml:281-300` |
| `minos-runtime` est `internal: true` | `docker/compose-mcp.connected.yaml:393-394` |
| Utilisateur et base par `MINOS_POSTGRES_USER` / `MINOS_POSTGRES_DATABASE`, mot de passe par fichier monté en lecture seule | `docker/compose-mcp.connected.yaml:289-291`, `:296-299` |
| Volume `minos-postgres-data` externe | `docker/compose-mcp.connected.yaml:408-410` |
| Chaque rôle de service a un bloc `x-limits-*` documenté | `docker/compose-mcp.connected.yaml:11-36`, `docker/.env.example:1-34` |
| Tables et colonnes du schéma | `minos-storage/src/main/java/com/minos/storage/postgresql/support/PostgresSchemaMigrator.java:78-137` |
| Support de test PostgreSQL existant | `minos-storage/src/test/java/com/minos/storage/postgresql/PostgresTestSupport.java` |

Les lignes citées sont identiques sur `main` au commit `ed17df21` (relues le 2026-11-13).

### Publication de port et réseau interne (tâche 1.1)

Relevé manuel du 2026-11-13 sur le poste du propriétaire : Docker client et serveur 29.9.3 (Docker Desktop, moteur `linux/amd64`). Deux conteneurs jetables de l'image PostgreSQL épinglée, chacun lancé avec `-p 127.0.0.1:<port>:5432` ; le port est sondé par une requête `SSLRequest` PostgreSQL, pas par une simple connexion TCP.

| Cas | Réseaux du conteneur | `docker port` | `127.0.0.1:<port>` |
|---|---|---|---|
| A | un réseau `--internal` seul | vide (`{"5432/tcp":[]}`) | connexion refusée |
| B | le même réseau interne et un réseau non interne | `5432/tcp -> 127.0.0.1:<port>` | PostgreSQL répond |

Docker accepte `-p` sans erreur dans le cas A et ne publie rien. Un service attaché au seul `minos-runtime` ne peut donc pas être joint depuis l'hôte : D2 (réseau d'entrée dédié) est nécessaire. Limite : résultat propre à Docker 29.9.3 sous Docker Desktop ; non relevé sur un moteur Linux natif.

Non mesuré : empreinte mémoire de pgAdmin ; durée de premier démarrage.

### Relevés de mise en œuvre (2026-11-13, Docker 29.9.3, Compose 5.7.0, Docker Desktop)

| Fait | Preuve |
|---|---|
| Compose interpole les services des profils inactifs : une variable requise (`${VAR:?}`) d'un service hors profil fait échouer toute commande, `config`, `ps` et `stop` compris | `docker compose config` sur un fichier d'essai, avec et sans le profil |
| Compose refuse un projet où un service actif dépend d'un service d'un profil inactif, même pour l'arrêter (`depends on undefined service`) | `StopPgAdmin` avec le seul profil `pgadmin`, sur un runtime jetable |
| pgAdmin 9.19.0 démarre sans aucune capacité et écoute alors sur 8080 ; racine en lecture seule possible avec trois `tmpfs` ; son point d'entrée ne peut plus écrire `config_distro.py` | conteneur réel : `CapEff` et `CapBnd` nuls, `NoNewPrivs: 1`, journal du conteneur |
| `UPGRADE_CHECK_ENABLED` vaut `False` par `/etc/pgadmin/config_system.py` | `import config` dans le conteneur |
| libpq n'utilise un fichier de mots de passe que s'il appartient au processus avec le mode `0600` : le fichier monté est copié dans le dossier de stockage du compte pgAdmin | connexion `minos_inspect` par `psql -w` depuis le conteneur pgAdmin |
| pgAdmin au repos : 235 Mio, 5 processus | `docker stats` |
| `com.docker.network.bridge.enable_ip_masquerade: "false"` sur le réseau d'entrée ne coupe pas l'accès sortant : le port local reste joignable, et pgAdmin atteint toujours Internet, par nom comme par adresse | `wget` depuis le conteneur, avant et après, réseau recréé avec l'option (lue par `docker network inspect`) |
| `information_schema.tables` ne liste que les tables sur lesquelles le rôle détient un droit ; `projects` y figure avec `SELECT` faux (droits par colonne) | requête de vérification exécutée avec le rôle |

### Relevé dans le navigateur (tâche 1.9)

Relevé manuel du propriétaire, 2026-11-13, sur le runtime jetable activé par `EnablePgAdmin` (données synthétiques : trois projets, cinq documents). Connexion au compte pgAdmin par le mot de passe du fichier privé ; le serveur « MINOS (read-only) » s'ouvre sans demander de mot de passe. Dans l'outil de requête, avec le rôle `minos_inspect` : la vérification des droits (six lignes, aucune écriture), le `DELETE` et `SELECT root_value` refusés par « permission denied », puis les sept requêtes de diagnostic de la page utilisateur rendent les résultats attendus, identiques à ceux obtenus par `psql` avec le même rôle. Limite : données synthétiques, pas un index produit par MINOS.

## Écarts

| Id | Écart | Suite |
|---|---|---|
| E1 | Le scénario « Secret absent » ne peut pas être tenu par une variable Compose requise (premier relevé ci-dessus) | D5 : chemin sentinelle dans le Compose et garde dans le script. Exigence et scénario inchangés |
| E2 | Invariant « aucun droit de création » : le rôle garde ce que PostgreSQL accorde à `PUBLIC` (`TEMPORARY`, création de grands objets) ; le retirer modifierait `PUBLIC` pour toute la base | Limite déclarée dans la page utilisateur ; `CREATE` sur le schéma et sur la base sont refusés et testés ; le rôle est borné à 8 connexions et 256 Mo de fichiers temporaires. [Question] ci-dessous |
| E3 | `pgpass` et le mot de passe du compte sont écrits dans `secrets/` du dossier de données Docker, pas dans le dossier d'exécution : `Install` copie le dossier d'exécution dans ses sauvegardes | `servers.json`, sans secret, reste dans le dossier d'exécution |
| E4 | L'activation est refusée hors Windows : les fichiers privés sont restreints par ACL Windows, et un fichier `0600` d'un hôte Linux serait illisible par l'uid 5050 du conteneur | Même limite que le configurateur du PostgreSQL géré. Arrêt et retrait sont portables |
| E5 | Le script SQL du rôle est un bloc du script de cycle de vie, pas un fichier séparé : un fichier livré de plus toucherait les listes de la distribution Windows et sa mise à jour transactionnelle | Source unique, lue telle quelle par le test PostgreSQL |

## Exigences couvertes

| Exigence | Section |
|---|---|
| `distribution-docker` › pgAdmin est un service optionnel publié sur la seule boucle locale | Responsabilités ; D1, D2 |
| `distribution-docker` › pgAdmin se connecte par un rôle PostgreSQL limité à la lecture | Sécurité et isolation ; D3 |
| `distribution-docker` › Retirer pgAdmin ne supprime aucune donnée MINOS | Transactions ; D4 |

## Objectifs et non-objectifs

Objectif : inspecter sans élargir l'exposition de PostgreSQL ni donner un droit d'écriture. Non-objectif : reproduire dans pgAdmin les règles applicatives de MINOS.

## Responsabilités et frontières des composants

| Composant | Responsabilité |
|---|---|
| Service `minos-pgadmin` (Compose, profil `pgadmin`) | interface web de pgAdmin, sur la boucle locale |
| Script SQL du rôle (ressource versionnée, sans mot de passe) | créer `minos_inspect` et ses droits |
| `docker/scripts/mcp-lifecycle.ps1` | `EnablePgAdmin`, `StopPgAdmin`, `RemovePgAdmin` ; génération des fichiers privés |
| `PostgresSchemaMigrator` | **inchangé** : ne crée ni rôle ni droit |

Aucune dépendance Java nouvelle ; ARCH-0068 n'est pas concernée.

## Modèle de domaine et invariants

Non applicable : aucun type de domaine. Invariants d'exploitation : le compte pgAdmin et le rôle PostgreSQL sont deux identités ; `minos_inspect` n'a aucun droit d'écriture ni de création ; aucune table future n'est lisible sans `GRANT` explicite (pas d'`ALTER DEFAULT PRIVILEGES`).

## Traitements et interactions

`EnablePgAdmin` : vérifie que le profil `postgresql` est actif → génère les mots de passe dans des fichiers privés → applique le script du rôle par `psql` dans `minos-postgres` → écrit `servers.json` et `pgpass` dans le dossier d'exécution → démarre le service. Tout échec d'une étape arrête l'action sans démarrer le service.

## États et transitions

Non applicable : trois états d'exploitation simples (absent, démarré, arrêté), sans cycle de vie à modéliser.

## Contrats d'interface

Actions du script de cycle de vie (additives) :

| Action | Effet | Erreur |
|---|---|---|
| `EnablePgAdmin` | rôle créé ou mis à niveau, fichiers privés écrits, service démarré ; idempotente | PostgreSQL géré absent ; fichier de secret illisible |
| `StopPgAdmin` | service arrêté, configuration conservée | — |
| `RemovePgAdmin` | conteneur, volume `minos-pgadmin-data`, fichiers privés et rôle supprimés | — |

Variables : `MINOS_PGADMIN_PORT`, `MINOS_PGADMIN_EMAIL`, `MINOS_PGADMIN_PASSWORD_FILE`, `MINOS_PGADMIN_PGPASS_FILE`, `MINOS_PGADMIN_SERVERS_FILE` (les trois fichiers montés), `MINOS_PGADMIN_IMAGE` (surcharge de l'image épinglée, comme pour PostgreSQL), limites `MINOS_PGADMIN_MEM_LIMIT`, `MINOS_PGADMIN_PIDS_LIMIT`, `MINOS_PGADMIN_CPUS`. `Install` reporte les cinq premières et les limites dans le `.env` régénéré.

Erreurs complémentaires : `EnablePgAdmin` refuse aussi un runtime sans service `minos-pgadmin`, un hôte non Windows, une variable `MINOS_PGADMIN_*` dans l'environnement du processus (Compose la préférerait au `.env`), un PostgreSQL géré arrêté, un schéma sans tables MINOS et un rôle `minos_inspect` membre d'un autre rôle ou propriétaire d'un objet ; `RemovePgAdmin` refuse un PostgreSQL géré arrêté avant de rien retirer. `Uninstall` retire le conteneur, le volume et les deux fichiers privés de pgAdmin ; le rôle reste dans la base conservée.

Rotation : un mot de passe n'est généré que si son fichier est absent ; `EnablePgAdmin` retire alors le conteneur et le volume de pgAdmin avant de démarrer le service, parce que pgAdmin ne lit le mot de passe de son compte qu'à la création de sa configuration.

## Persistance et intégrité

Volume `minos-pgadmin-data` (configuration de pgAdmin). Aucune donnée MINOS écrite ; aucune version de schéma.

## Transactions, concurrence et idempotence

Le script du rôle s'exécute dans une transaction ; `EnablePgAdmin` rejouée ne duplique rien ; `RemovePgAdmin` ne référence jamais le volume `minos-postgres-data`.

## Sécurité et isolation

- Lecture seule par les **droits** ; `default_transaction_read_only` et `statement_timeout` ne sont que des bornes de session.
- `projects` : droits par colonne, sans `root_value` (pas de chemin du poste lisible).
- Secrets : fichiers privés hors dépôt, montés en lecture seule ; jamais dans `servers.json`, une variable d'environnement en clair ni un argument.
- Publication : `127.0.0.1` seulement ; `cap_drop: [ALL]`, `no-new-privileges` ; vérification de mises à jour de pgAdmin désactivée.
- pgAdmin sur un réseau non interne obtient un accès sortant : risque documenté, service hors profil par défaut.

## Erreurs et reprise

Erreurs actionnables qui nomment la variable ou le profil manquant, sans chemin absolu. Un `EnablePgAdmin` interrompu se rejoue.

## Observabilité

Sortie des actions sur la console ; journaux du conteneur pgAdmin par `docker logs`.

## Performance et mesure

Aucun objectif. Limites mémoire du service à fixer après un relevé au premier démarrage : [Question].

## Compatibilité, migration et retour arrière

Additif. Une installation existante n'est pas modifiée tant que le profil n'est pas activé. Retour arrière : `RemovePgAdmin`.

## Diagrammes

Vue de conteneurs : `openspec/changes/explorer-l-index-semantique-dans-un-navigateur/design.md`, « Quels conteneurs et quels réseaux en runtime Docker ? ». À reporter dans le dossier `distribution-docker` par la tâche 2.2.

## Décisions

### D1 — Service dans le fichier Compose existant, sous profil
[Décision — propriétaire, 2026-11-13] Écarté : fichier Compose séparé (les gates de limites et d'images lisent les fichiers livrés ; un seul cycle de vie).

### D2 — Réseau d'entrée dédié
[Décision] Confirmée par le relevé de la tâche 1.1 (« État vérifié au HEAD ») : un service attaché au seul réseau interne ne publie aucun port. Écarté : rendre `minos-runtime` non interne.

### D3 — Rôle `minos_inspect`, liste fermée, droits par colonne
[Décision — propriétaire, 2026-11-13] Écarté : rôle prédéfini de lecture globale (il lirait `knowledge_snapshots` et `root_value`).

### D4 — Rôle créé par une action d'administration
[Décision — propriétaire, 2026-11-13] Écarté : création par `PostgresSchemaMigrator` (exigerait le droit de créer des rôles pour toute installation).

### D5 — Secret absent : chemin sentinelle et garde du script
[Décision — propriétaire, 2026-11-13] Chaque fichier monté a pour source `${VAR:-/VAR-is-required}` avec `create_host_path: false` : sans la variable, Docker refuse le montage en nommant le chemin, donc la variable. `EnablePgAdmin` rejoue le contrôle (`Assert-PgAdminSecretsConfigured`) avant de démarrer le service. Écarté : variable requise `${VAR:?}` (casse les installations sans pgAdmin, E1) ; valeur factice écrite par chaque script ; fichier Compose séparé (D1).

### D6 — Image et durcissement
[Décision] `dpage/pgadmin4:9.19.0`, épinglée par digest, dernière version publiée le 2026-10-29. `cap_drop: [ALL]`, `no-new-privileges`, racine en lecture seule, port d'écoute 8080, vérification de mises à jour désactivée par un fichier de configuration système écrit sur `tmpfs`. Chaque appel Compose qui nomme le service active les profils `postgresql` et `pgadmin`.

## Qualification des capacités

| Capacité | Qualification |
|---|---|
| pgAdmin — PostgreSQL géré, runtime Docker, hôte Windows | qualifiée sur un runtime jetable (Docker Desktop 29.9.3) : tâches 1.2 à 1.9 vertes, relevé dans le navigateur compris. Non rejouée sur une installation faite par l'installateur, ni sur un index produit par MINOS |
| pgAdmin — hôte Linux | non supportée : activation refusée (E4) |
| pgAdmin — PostgreSQL externe | non supportée, hors périmètre |

## Risques et compromis

- [Risque] Accès sortant de pgAdmin → hors profil par défaut, mises à jour désactivées, documenté.
- [Risque] Image pgAdmin volumineuse et fréquemment mise à jour → épinglée par digest, suivie par Dependabot comme les autres.
- [Risque] Une migration future ajoute une colonne sensible à une table accordée → revue du script du rôle à chaque montée de version de schéma (à inscrire dans la page développeur).

## Vérification

Auto-tests des scripts Docker (service, port, secret, retrait) ; test PostgreSQL des droits dans `minos-storage` ; `check-compose-limits.py`, `check-image-pins.py`. Non vérifiable sans Docker : tâches 1.1 et 1.9, manuelles.

## Documentation durable à mettre à jour

`docs/architecture/diagrams/capacites/distribution-docker.md`, `docs/user/docker-runtime.md`, nouvelle page `docs/user/` (pgAdmin), `docs/user/README.md`, `STATUS.md`, `ROADMAP.md`.

## Traçabilité

| Exigence (capacité › titre) | Scénario | Conception ou diagramme | Tâche | Test ou preuve |
|---|---|---|---|---|
| distribution-docker › pgAdmin optionnel, boucle locale | Installation sans pgAdmin | D1 | 1.2–1.3 | `scripts/install/test_mcp_lifecycle_pgadmin.py` : `test_compose_resolves_no_pgadmin_and_no_port_without_the_profile`, `test_without_the_profile_no_service_publishes_a_port`, `test_the_base_runtime_has_no_pgadmin_and_publishes_no_port` |
| idem | pgAdmin activé | D1, D2, D6 | 1.2–1.3 | même fichier : `test_compose_resolves_one_loopback_port_with_the_profile`, `test_postgresql_stays_unpublished_on_the_internal_network` ; relevé du runtime jetable (seul port : `127.0.0.1` vers pgAdmin) |
| idem | Secret absent | D5 | 1.2–1.3 | même fichier : `test_a_missing_password_file_variable_is_refused_by_name`, `test_a_variable_that_names_a_missing_file_is_refused_by_name`, `test_secrets_come_from_read_only_files_and_never_from_the_environment` ; relevé : `docker compose up` sans la variable échoue sur `/MINOS_PGADMIN_PASSWORD_FILE-is-required` |
| distribution-docker › rôle limité à la lecture | Écriture refusée | D3 | 1.4–1.5 | `minos-storage/src/test/java/com/minos/storage/postgresql/PostgresInspectionRoleTest.java` : `writesAreRefusedByThePrivilegesOnceTheSessionIsNoLongerReadOnly`, `onlyTheClosedListOfTablesIsReadable`, `applyingTheScriptAgainRestoresTheClosedList` |
| idem | Racine des projets non lisible | D3 | 1.4–1.5 | même classe : `theRootOfAProjectIsNotReadableWhileItsOtherColumnsAre` |
| distribution-docker › retrait sans perte | Retrait | D4 | 1.6–1.7 | `test_mcp_lifecycle_pgadmin.py` : `test_removal_takes_pgadmin_and_the_role_and_nothing_else` ; `PostgresInspectionRoleTest#removingTheRoleLeavesTheDataUntouched` ; relevé du runtime jetable (conteneur, volume, réseau d'entrée et rôle absents, 5 lignes inchangées) |

Preuves manquantes : aucun test automatique ne démarre pgAdmin (le démarrage réel est un relevé manuel, daté) ; le volume PostgreSQL « inchangé » est prouvé par l'absence de toute commande qui le nomme et par le relevé, pas par une empreinte du volume ; les actions n'ont pas été rejouées sur une installation faite par l'installateur Windows.

## Questions ouvertes

- [Décision — propriétaire, 2026-11-13] E2 : les droits que PostgreSQL accorde à `PUBLIC` (`TEMPORARY`, création de grands objets) ne sont pas retirés dans la base gérée ; la limite est déclarée dans la page utilisateur.
- [Décision — propriétaire, 2026-11-13] Un fichier privé existant dont la liste d'accès était plus large est restreint de nouveau, vérifié, puis utilisé : même règle que le mot de passe PostgreSQL.
- [Décision — propriétaire, 2026-11-13] Couper l'accès sortant de pgAdmin : demandé, essayé, sans effet sous Docker Desktop (relevé ci-dessus). L'option n'est pas posée ; le risque reste celui déclaré (service hors profil par défaut, vérification de mises à jour désactivée). [Question] Reste à trouver un moyen qui tienne sous Docker Desktop sans donner de capacité au conteneur.
- [Question] Les limites de pgAdmin (1 Gio, 256 PID) reposent sur une mesure au repos : à revoir après un relevé en session de requêtes.
