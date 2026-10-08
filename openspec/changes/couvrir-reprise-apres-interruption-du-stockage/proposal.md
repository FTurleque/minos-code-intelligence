# Proposal

## Why

MINOS promet qu'un arrêt brutal pendant une publication de snapshot ne laisse jamais un état actif incohérent (ADR 0023, `DurableAtomicFile`). Les tests actuels vérifient des pannes injectées et des fichiers corrompus, mais aucun ne tue réellement un processus entre l'écriture temporaire et le renommage avant de rouvrir le magasin, aucun ne lit un pointeur actif tronqué, et aucun ne migre une base PostgreSQL déjà peuplée de la version 1 à la version 4 du schéma (MINOS-AUD-H12). Par ailleurs, les pointeurs temporaires `.active-*.tmp` laissés par un arrêt brutal ne sont jamais récupérés (H13). Constats : `docs/quality/code-audit-constats.md` § 5.

## What Changes

- Tests de reprise après arrêt brutal **réel** (processus fils tué) pour le magasin local de snapshots et le magasin d'empreintes, avec réouverture et vérification du snapshot actif.
- Test de lecture d'un `active.pointer` tronqué ou vide : refus explicite et message vérifié.
- Test de migration d'une base PostgreSQL peuplée en v1 jusqu'à la v4 (Testcontainers), données relues après migration.
- Décision puis, si elle est retenue, récupération bornée des `.active-*.tmp` orphelins (même borne d'âge que les résidus de snapshot), test d'abord.
- Aucune rupture de contrat.

## Capabilities

### New Capabilities

- `persistance-snapshots` (nom de `docs/audit/CAPACITES.md`) : reprise et cohérence des snapshots persistés après une interruption.

### Modified Capabilities

(aucune)

## Hors périmètre

- Décodage complet du snapshot pour le statut (D02, D03) et limite de tas (E03).
- Récupération des répertoires de transit PostgreSQL (E08), verrous sans `lock_timeout` (E06).
- Changement de format de snapshot.

## Impact

- Modules : `minos-storage-local` (tests ; code de production seulement pour la récupération des `.active-*.tmp` si elle est décidée), `minos-storage-postgresql` (tests), `minos-engine` (`io`, lecture seule sauf décision).
- Surfaces publiques : aucune.
- ADR : aucun nouvel ADR ; s'inscrit dans ADR 0023 et 0024. Si la récupération des pointeurs orphelins change la politique de rétention, amender la section correspondante de l'ADR 0023.
