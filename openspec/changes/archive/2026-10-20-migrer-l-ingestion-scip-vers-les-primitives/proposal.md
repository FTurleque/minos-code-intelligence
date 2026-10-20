# Proposal

## Why

**AUD-SEC-03, lot « providers SCIP », partie ingestion** (sprint 8 de l'audit du 10 octobre 2026). Deux entrées de `scripts/architecture/private-io-allowlist.json` (`ScipIndexReader.java` : 1, `ScipSymbolSnapshotImporter.java` : 2, `Files.newByteChannel`) sont rangées sous « lecture par canal … vers `ConfinedFileOpener` ». Une des trois est en réalité une **écriture** : l'importeur fige l'index SCIP produit par un provider non fiable dans un fichier de travail privé, puis le relit ; il ouvre ce fichier en écriture par un canal brut (`ScipSymbolSnapshotImporter.java:137`). Les deux autres lisent l'artefact du provider, la frontière la plus exposée du produit : un index qu'un provider sandboxé vient d'écrire.

## What Changes

- `ScipIndexReader` et la lecture de la source dans `ScipSymbolSnapshotImporter` ouvrent l'artefact par `ConfinedFileOpener.openRegularFileNoFollowChannel` (même descripteur pour `size()`, le préflight et la lecture).
- L'écriture du fichier figé passe par `PrivateLocalStorage.openPrivateOutputStream`.
- Tests : lien symbolique et fichier spécial à la place de l'index refusés, remplacement par un lien entre contrôle et ouverture refusé.
- Trois occurrences (deux entrées) retirées.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `stockage-prive-et-secrets` : une exigence ajoutée (l'artefact d'un provider est lu par un canal qui ne suit pas de lien ; l'index figé est écrit par une primitive privée).

## Hors périmètre

- Le format SCIP, les limites (`ScipIngestionLimits`), le préflight et le budget de tas.
- L'installation des outils SCIP : `migrer-l-installation-des-outils-scip-vers-les-primitives`.
- Les messages d'erreur de ces classes, qui citent le chemin de l'index (`ScipIndexReader.java:42`, `ScipSymbolSnapshotImporter.java:128`) : un `IOException` interne, non traité ici ; à signaler à `PublicErrorMessages` dans un changement séparé.
- Le dossier de travail par défaut (`user.home` + sous-dossier) et sa vérification.

## Impact

- **Modules du reactor** : `minos-provider-scip` seul. Dépend du socle.
- **Surfaces publiques** : aucune.
- **ADR** : aucun créé ni amendé.
- **Gates à rejouer** : `python scripts/architecture/check-private-io.py`, `python scripts/quality/check-remote-distributed-consistency.py` (cite `ScipSymbolSnapshotImporter`, `:224`), `python scripts/quality/check-polyglot-provider-consistency.py`, `python scripts/quality/check-jacoco.py` (scope `global` ; aucun scope par classe), `python .claude/scripts/run_gates.py --fast`.
- **Plateformes** : Windows et Linux ; les tests de lien suivent la convention de `tests-java`.
