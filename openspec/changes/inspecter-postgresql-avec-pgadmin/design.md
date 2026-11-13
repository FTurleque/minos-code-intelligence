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

Non mesuré : publication de port depuis un réseau interne ; empreinte mémoire de pgAdmin ; durée de premier démarrage.

## Écarts

Aucun.

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

Variables : `MINOS_PGADMIN_PORT`, `MINOS_PGADMIN_EMAIL`, `MINOS_PGADMIN_PASSWORD_FILE`, limites `MINOS_PGADMIN_MEM_LIMIT`, `MINOS_PGADMIN_PIDS_LIMIT`, `MINOS_PGADMIN_CPUS`.

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
[Proposition] Conditionnée par la tâche 1.1. Écarté : rendre `minos-runtime` non interne.

### D3 — Rôle `minos_inspect`, liste fermée, droits par colonne
[Décision — propriétaire, 2026-11-13] Écarté : rôle prédéfini de lecture globale (il lirait `knowledge_snapshots` et `root_value`).

### D4 — Rôle créé par une action d'administration
[Décision — propriétaire, 2026-11-13] Écarté : création par `PostgresSchemaMigrator` (exigerait le droit de créer des rôles pour toute installation).

## Qualification des capacités

| Capacité | Qualification |
|---|---|
| pgAdmin — PostgreSQL géré, runtime Docker | non supportée tant que les tâches 1.2 à 1.9 ne sont pas vertes |
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
| distribution-docker › pgAdmin optionnel, boucle locale | Installation sans pgAdmin | D1 | 1.2–1.3 | à écrire |
| idem | pgAdmin activé | D1, D2 | 1.2–1.3 | à écrire |
| idem | Secret absent | Sécurité | 1.2–1.3 | à écrire |
| distribution-docker › rôle limité à la lecture | Écriture refusée | D3 | 1.4–1.5 | à écrire |
| idem | Racine des projets non lisible | D3 | 1.4–1.5 | à écrire |
| distribution-docker › retrait sans perte | Retrait | D4 | 1.6–1.7 | à écrire |

## Questions ouvertes

Aucune décision du propriétaire en attente. Reste technique : résultat de la tâche 1.1.
