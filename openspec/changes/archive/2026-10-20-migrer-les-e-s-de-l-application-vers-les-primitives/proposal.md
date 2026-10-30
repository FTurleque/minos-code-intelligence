# Proposal

## Why

**AUD-SEC-03, lot « application et registre », partie `minos-application`** (sprint 8 de l'audit du 10 octobre 2026). Une entrée de `scripts/architecture/private-io-allowlist.json` du lot (`LocalProjectOperations.java`, `Files.newOutputStream`, maximum 1) écrit l'historique d'indexation de la CLI : un fichier `Properties` rouvert par un `Files.newOutputStream` brut après `createPrivateTempFile` (`:204`, `:211`).

Deux autres entrées du même module, plus anciennes et rangées « hors S5 nommé », attendent la même migration : `FingerprintConstrainedJavaProgramGraphProvider.java:214` et `JavaSourceWorkspace.java:150` lisent les sources du projet de l'utilisateur (et le fichier de configuration de sécurité) avec `Files.newInputStream`, options fournies par l'appelant. Elles ne font pas partie des 44 écritures du sprint 8, mais appartiennent au même module et à la même primitive de lecture : les traiter ici évite un quatrième changement d'une entrée.

## What Changes

- `LocalProjectOperations.writeHistory` : `Properties` encodé en mémoire puis `PrivateLocalStorage.writePrivateFile`.
- `FingerprintConstrainedJavaProgramGraphProvider.sha256Exact` et `JavaSourceWorkspace.updateBounded` ouvrent par `ConfinedFileOpener.openRegularFileNoFollow` (le leaf ne suit jamais de lien ; l'option variable disparaît de leurs signatures).
- Tests : lien substitué au temporaire (historique) ; source remplacée par un lien entre le contrôle et la lecture (empreinte) ; contenu de l'historique identique à l'octet près.
- Trois entrées retirées (une du lot nommé, deux plus anciennes).

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `stockage-prive-et-secrets` : une exigence ajoutée (l'historique d'indexation et les empreintes de sources passent par les primitives, sans suivre de lien).

## Hors périmètre

- Le calcul des empreintes (`sha256Exact`, bornes, messages « grew while being fingerprinted »), le cache d'empreintes et la découverte des sources.
- `BoundedFileDigest` (migré par le socle) et ses appelants.
- Le format de l'historique et la logique d'import (`LocalProjectOperations`).
- Le lot `minos-app` (`migrer-la-configuration-mcp-vers-les-primitives`) et le lot Git (`migrer-la-publication-du-cache-git-vers-les-primitives`).

## Impact

- **Modules du reactor** : `minos-application` seul. **Indépendant du socle** : `writePrivateFile` et `openRegularFileNoFollow` existent déjà.
- **Surfaces publiques** : aucune ; empreintes et fichiers inchangés.
- **ADR** : aucun créé ni amendé.
- **Gates à rejouer** : `python scripts/architecture/check-private-io.py`, `python scripts/remediation/check-mne.py` (exige `sha256Exact`, « grew while being fingerprinted » et `MAX_SECURITY_CONFIG_BYTES` dans le fichier d'empreintes), `check-mnd.py`, `check-p0-p2.py`, `python scripts/quality/check-java-ast-provider-consistency.py`, `python scripts/quality/check-vertical-decomposition-consistency.py`, `python scripts/quality/check-jacoco.py` (scopes `program-graph-analysis`, `java-advanced-provider`, `global`), `python .claude/scripts/run_gates.py --fast`.
- **Plateformes** : Windows et Linux ; aucune branche de plateforme.
