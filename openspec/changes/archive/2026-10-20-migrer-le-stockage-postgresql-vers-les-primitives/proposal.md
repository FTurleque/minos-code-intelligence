# Proposal

## Why

**AUD-SEC-03, lot « stockage PostgreSQL »** (sprint 8 de l'audit du 10 octobre 2026). Une entrée de `scripts/architecture/private-io-allowlist.json` (`PostgresCodeKnowledgeSnapshotStore.java`, `Files.newOutputStream`, maximum 2) nomme ce lot. Le magasin décode un snapshot de PostgreSQL vers un fichier de travail pour le relire par le codec local (`:292` relecture d'un snapshot existant, `:388` lecture de l'actif). Le fichier est créé privé par `PrivateLocalStorage.createPrivateFile` (`:412`) dans un dossier vérifié privé (`:425-430`), puis rouvert par un `Files.newOutputStream` brut qui suit un lien substitué entre les deux appels.

## What Changes

- Les deux ouvertures passent par `PrivateLocalStorage.openPrivateOutputStream` (socle `completer-les-primitives-d-e-s-privees`).
- Test de lien substitué au fichier de travail pour les deux chemins.
- L'entrée de liste est retirée.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `stockage-prive-et-secrets` : une exigence ajoutée (le magasin PostgreSQL n'écrit pas ses fichiers de travail par un appel brut).

## Hors périmètre

- Le schéma SQL, les requêtes et le contenu des snapshots.
- `copyBounded`, la suppression des fichiers de travail (`Files.deleteIfExists`, hors du gate) et le cache en mémoire.
- Le magasin local : `migrer-le-stockage-local-vers-les-primitives`.

## Impact

- **Modules du reactor** : `minos-storage-postgresql` seul. Dépend du socle.
- **Surfaces publiques** : aucune.
- **ADR** : aucun créé ni amendé.
- **Gates à rejouer** : `python scripts/architecture/check-private-io.py`, `python scripts/remediation/check-mnd.py`, `python scripts/remediation/check-mne.py`, `python scripts/quality/check-jacoco.py` (scope `m30-postgresql-pgvector`, Linux seulement), `python .claude/scripts/run_gates.py --fast`.
- **Plateformes** : les tests PostgreSQL exigent Docker (Testcontainers) ; la CI Linux fait foi, un poste Windows sans Docker les ignore.
