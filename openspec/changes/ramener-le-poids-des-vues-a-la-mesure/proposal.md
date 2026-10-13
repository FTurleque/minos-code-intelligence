# Proposal

## Why

**AUD-PERF-03** (moyenne, sprint 4 de l'audit du 10 octobre 2026, sur le chemin critique : il débloque AUD-PERF-11 et AUD-ARC-07). Le cache de vues de requête estime le poids d'une vue à **8 fois** la taille du snapshot persisté (`FileSymbolSnapshotStore.java:44`, `PostgresCodeKnowledgeSnapshotStore.java:47`), contre **1,4** mesuré (ADR 0047, audit d'architecture de septembre § A6.4). Avec un plafond de 512 Mio (`:41` / `:49`), tout snapshot de plus de 64 Mio persistés est refusé à l'admission (`:247`) : il n'est **jamais** mis en cache et chaque requête MCP le relit et le décode. Le snapshot de ce dépôt (194,6 Mo en V3) est dans ce cas ; la relecture mesurée coûte 0,75 à 1,46 s par requête.

Chaque reconstruction lit en outre le fichier plusieurs fois : deux sous le store fichier (SHA-256 complet `:274`, puis décodage `:275`), **trois** sous PostgreSQL après copie dans un fichier temporaire (`:414` empreinte, `:418` version de format, `:419` décodage).

L'ADR 0047 désigne cette correction comme la première à faire (décision 1, « hors de cet ADR ») ; elle n'est pas faite au HEAD.

## What Changes

- **Poids fondé sur la mesure** : le facteur d'amplification est ramené à la valeur mesurée avec marge, par format de snapshot lisible, sous un test qui compare le poids estimé au tas réellement retenu.
- **Un seul parcours du fichier** à la reconstruction d'une vue, sans rien céder sur l'intégrité : aucune vue n'est publiée, mise en cache ni comptée avant que l'empreinte ait été comparée.
- **Gates littéraux mis à jour** dans le même changement (`check-mne.py`, `check-mnd.py`).

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `persistance-snapshots` : trois exigences ajoutées (un snapshot de la taille de ce dépôt est admis au cache ; le poids estimé borne le tas retenu ; un snapshot altéré n'est ni publié ni mis en cache, quel que soit le nombre de parcours).

## Hors périmètre

- Les décisions 2 et suivantes de l'ADR 0047 (table de chaînes, pagination, mappage mémoire) et l'ADR 0056 (port de lecture neutre) : AUD-ARC-07, sprint 10.
- Relever le plafond de 512 Mio ou le rendre configurable.
- Les autres lectures répétées du snapshot (AUD-PERF-01) et les index d'arêtes d'impact (AUD-PERF-11), qui bénéficient de ce changement.
- Changer le format de snapshot (V3, ADR 0046) ou son codec.

## Impact

- **Modules du reactor** : `minos-storage-local` (`FileSymbolSnapshotStore`), `minos-storage-postgresql` (`snapshot/PostgresCodeKnowledgeSnapshotStore`). Aucune dépendance ajoutée (ADR 0022).
- **Surfaces publiques** : aucune sortie ne change ; les statistiques de cache déjà exposées (`fullSnapshotLoads`) évoluent en valeur.
- **ADR** : complète la « Mise en œuvre » de l'ADR 0047 (décision 1 réalisée, mesures) ; aucun nouvel ADR.
- **Gates à rejouer** : `python scripts/remediation/check-mne.py` (`:85-88` exige `QUERY_VIEW_PERSISTED_AMPLIFICATION`, `Files.size(snapshot)`, `safeMultiply`, `estimateWeight(metadata, payloadBytes)`), `python scripts/remediation/check-mnd.py` (`:82-83`, `:99-100`), scopes JaCoCo `persistence-cache-indexes` et `m30-postgresql-pgvector` (`scripts/quality/check-jacoco.py`), `python scripts/architecture/check-private-io.py` (lecture confinée).
- **Plateformes** : Windows et Linux (store fichier) ; PostgreSQL réel pour le second store (tests requis sous Linux en CI).
