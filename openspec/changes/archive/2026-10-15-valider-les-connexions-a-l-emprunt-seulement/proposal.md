# Proposal

## Why

**AUD-PERF-12** (faible, sprint 4 de l'audit du 10 octobre 2026). Le pool de connexions PostgreSQL appelle `Connection.isValid()`, soit un aller-retour réseau, **à l'emprunt et à la restitution** de chaque connexion (`PostgresConnectionFactory.java:417` et `:434`, `usable` en `:474-482`). Chaque `withConnection` paie donc deux allers-retours en plus de sa requête.

Une requête hybride sous PostgreSQL ouvre une quinzaine de `withConnection` (AUD-PERF-01) : une trentaine d'allers-retours de validation, sensibles dès que la base n'est pas sur la même machine.

## What Changes

- **À la restitution** : plus d'aller-retour ; contrôle local seulement (connexion non fermée, aucune transaction ouverte).
- **À l'emprunt** : validation réseau seulement si la connexion est restée inactive au-delà d'un seuil.
- **Une connexion morte ne casse pas l'appelant en silence** : le comportement en cas de connexion rompue sous le seuil est défini et testé.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `persistance-snapshots` : deux exigences ajoutées (validation des connexions du pool bornée ; une connexion rendue dans un état incertain n'est pas réutilisée).

## Hors périmètre

- Remplacer le pool maison par une bibliothèque (HikariCP ou autre) : nouvelle dépendance, non justifiée par un constat faible.
- Réduire le nombre de `withConnection` par requête (AUD-PERF-01).
- La taille du pool, les délais de connexion et la politique TLS (`stockage-prive-et-secrets`).

## Impact

- **Modules du reactor** : `minos-storage-postgresql` seulement (`support/PostgresConnectionFactory`).
- **Surfaces publiques** : aucune.
- **ADR** : aucun (aucun ADR ne décrit le pool).
- **Gates à rejouer** : scope JaCoCo `m30-postgresql-pgvector` ; `PostgresConnectionFactoryPoolTest` ; aucun gate littéral ne cite ces appels (relevé au HEAD).
- **Plateformes** : indépendant de la plateforme ; tests unitaires sur connexions simulées, tests d'intégration sur PostgreSQL réel (CI Linux).
