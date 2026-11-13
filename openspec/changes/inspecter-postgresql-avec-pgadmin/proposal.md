# Proposal

## Why

Le PostgreSQL géré par le runtime Docker n'est joignable par aucun outil d'inspection : il vit sur un réseau interne et ne publie aucun port (`docker/compose-mcp.connected.yaml:281-300`, `:393-394`). Vérifier ce qu'un index sémantique contient — documents, modèle, dimensions, alignement sur le snapshot actif — demande d'ouvrir un shell dans un conteneur. Demande du propriétaire du 2026-11-12, recommandations acceptées le 2026-11-13 ; étude : `docs/project/research/explorateur-vectoriel-2026-11/PGADMIN.md`.

Ce lot est indépendant de l'explorateur : il ne crée aucune surface MINOS.

## What Changes

- **Ajout** d'un service Compose `minos-pgadmin`, hors profil par défaut (profil `pgadmin`), publié uniquement sur `127.0.0.1`.
- **Ajout** d'un rôle PostgreSQL `minos_inspect`, limité à `SELECT` sur une liste fermée de tables et de colonnes, créé par une action d'administration explicite.
- **Ajout** des actions `EnablePgAdmin`, `StopPgAdmin`, `RemovePgAdmin` au script de cycle de vie Docker.
- **Ajout** d'une page utilisateur avec les requêtes de diagnostic.
- **Inchangé** : `minos-postgres` reste non publié ; schéma, migrations et rôle applicatif inchangés.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities
- `distribution-docker` : trois exigences ajoutées (service optionnel sur la boucle locale, rôle limité à la lecture, retrait sans perte).

## Hors périmètre

- pgAdmin pour une instance PostgreSQL externe (MINOS n'y crée aucun rôle).
- Toute surface MINOS, toute lecture applicative de l'index : changement `explorer-l-index-semantique-dans-un-navigateur`.
- Accès à `knowledge_snapshots`, `indexing_runs`, `runtime_sessions`, `fingerprint_*`, `workspaces` et à `projects.root_value`.
- Publication de PostgreSQL sur l'hôte ; accès distant à pgAdmin ; TLS.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| `minos-postgres` : réseau interne, aucun port publié, mot de passe par fichier | [Existant vérifié] | `docker/compose-mcp.connected.yaml:281-300`, `:393-394` |
| Le volume PostgreSQL est externe au projet Compose | [Existant vérifié] | `docker/compose-mcp.connected.yaml:408-410` |
| Limites et images des services sont gardées par des gates | [Existant vérifié] | `scripts/quality/check-compose-limits.py`, `scripts/quality/check-image-pins.py` |
| Aucun `GRANT` ni rôle secondaire dans le schéma | [Existant vérifié] | `minos-storage/src/main/java/com/minos/storage/postgresql/support/PostgresSchemaMigrator.java:78-137` |
| `projects.root_value` contient la racine du projet | [Existant vérifié] | `PostgresSchemaMigrator.java:80` |
| pgAdmin en service optionnel, rôle `minos_inspect`, liste fermée de tables | [Décision] | propriétaire, 2026-11-13 |
| Un conteneur sur le seul réseau interne ne peut pas publier de port | [Question] | tâche 1.1 |
| Version et digest de l'image `dpage/pgadmin4` | [Question] | à fixer à la tâche 1.3 |

## Impact

- **Modules du reactor** : `minos-storage` (un test PostgreSQL des droits ; aucune classe de production).
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune.
- **ADR** : aucun. Un service d'inspection optionnel ne contraint ni module ni contrat ; SEC-0075 et ARCH-0069 ne sont pas amendés.
- **Gates à rejouer** : `scripts/quality/check-compose-limits.py`, `scripts/quality/check-image-pins.py`, `scripts/docs/check-capability-dossiers.py`, `scripts/docs/check-current-docs.py` ; scope JaCoCo de `minos-storage` si du code Java de production est ajouté.
- **Plateformes** : Windows (scripts PowerShell du runtime Docker) et Linux (conteneurs). Aucun comportement propre attendu.
- **Documentation durable et diagrammes** : `docs/architecture/diagrams/capacites/distribution-docker.md`, `docs/user/docker-runtime.md`, nouvelle page `docs/user/` sur pgAdmin, `STATUS.md` et `ROADMAP.md` à la livraison.

## Questions ouvertes

Non bloquantes : résultat de la tâche 1.1 (réseau d'entrée dédié nécessaire ou non) ; restriction de l'accès sortant de pgAdmin si ce réseau est nécessaire.
