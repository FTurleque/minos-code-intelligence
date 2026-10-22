# Proposal

## Why

**AUD-QUA-03** (moyenne, sprint 9 de l'audit du 10 octobre 2026). Le port `IndexStateStore` (`minos-engine/src/main/java/com/minos/orchestration/IndexStateStore.java:18-26`, et `acquireProjectLease` en `:38`) ne déclare **aucun contrat d'erreur**. Ses **4** implémentations de production (l'audit en comptait 3) lèvent des types différents pour la même panne :

- `FileIndexStateStore` : `UncheckedIOException` (`:173`, `:222`, `:240`, `:636`, `:644`) et `IllegalStateException` ;
- `PostgresIndexStateStore` : `IllegalStateException` (`:109-110`) ;
- `ProjectMutationIndexStateStore` : `IllegalStateException` (`:312`, et le bail) ;
- `InMemoryIndexStateStore` : `IllegalStateException` pour le bail.

Les appelants attrapent donc `RuntimeException` : `ProjectIndexStateReconciler` le fait 5 fois (`:154`, `:183`, `:191`, `:199`, `:207`) et requalifie tout en `IOException` « failed to read project index state ». Un `NullPointerException` ou une violation d'invariant dans un store devient ainsi une panne d'E/S : diagnostic trompeur, et la tolérance ou la reprise ne peuvent pas distinguer une panne d'un défaut de programmation.

Par ailleurs, plusieurs messages de `FileIndexStateStore` contiennent le **chemin absolu** du fichier d'état (`:173`, `:636`, `:644`, et six `IllegalStateException`), contre la règle de sécurité du dépôt.

## What Changes

- **Un type d'échec unique** pour le port, levé par les quatre implémentations, bail compris, avec la cause conservée.
- **Les appelants n'attrapent plus `RuntimeException`** autour du port : un défaut de programmation remonte tel quel.
- **Les messages du port ne contiennent plus de chemin absolu.**
- Le choix entre exception vérifiée et non vérifiée revient au propriétaire (`design.md`, D1).

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `persistance-snapshots` : trois exigences ajoutées (échec de stockage de l'état d'index d'un type unique ; un défaut de programmation n'est pas requalifié en panne d'E/S ; aucun chemin absolu dans un message du port).

## Hors périmètre

- Les autres `catch (RuntimeException)` du dépôt : 53 sites au HEAD avec une recherche mono-type (le « 43 » de l'audit n'est pas reproduit et n'est pas repris). Seuls ceux qui entourent un appel du port sont traités.
- Une exception dédiée pour les **refus métier** (`resume-only indexing refused`, `minos-engine/src/main/java/com/minos/orchestration/IndexingRunExecutor.java:78`, et la CLI) : elle touche la correspondance des codes de sortie de la CLI ; reportée (D5).
- Le contrat d'erreur des autres ports de stockage (`CodeKnowledgeSnapshotStore`, magasin d'empreintes), qui déclarent déjà `IOException`.
- Les autres sites de fuite de chemin absolu : changement du sprint 7 `ne-plus-laisser-fuir-de-chemin-absolu-dans-les-erreurs`.

## Impact

- **Modules du reactor** : `minos-engine` (port, `InMemoryIndexStateStore`, `AuthoritativeProjectStateReconciler`, `IndexingRunExecutor`), `minos-storage-local`, `minos-storage-postgresql`, `minos-application` (`ProjectIndexStateReconciler`, `LocalProjectOperations`), `minos-cli` ; 6 doubles de test du port à aligner. Aucune dépendance ajoutée.
- **Surfaces publiques** : le port n'est importé ni par `minos-api` ni par `minos-mcp` (ils en transmettent seulement la référence à `ResumableRunSummary`). API Java : aucune signature publique ne change avec l'option recommandée. CLI et MCP : les messages d'erreur d'une panne de stockage d'état restent ceux d'aujourd'hui ; un défaut de programmation n'est plus présenté comme une panne d'E/S.
- **ADR** : aucun nouvel ADR attendu avec l'option recommandée ; si l'exception vérifiée est retenue, le SPI `StorageBackend` change en source et une note de « Mise en œuvre » de l'ADR 0057 le consigne.
- **Gates à rejouer** : `python scripts/quality/check-jacoco.py` (scopes `critical-orchestration`, `resume-orchestration`, `m30-postgresql-pgvector`), `python scripts/architecture/check-private-io.py`, `python scripts/remediation/check-post-mne.py`, `python scripts/remediation/check-mne.py`, `python scripts/architecture/check-module-boundaries.py`.
- **Plateformes** : Windows et Linux pour le store fichier ; PostgreSQL réel pour les deux autres (en CI sous Linux).
