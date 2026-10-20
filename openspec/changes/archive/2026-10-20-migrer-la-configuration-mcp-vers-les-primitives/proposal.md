# Proposal

## Why

**AUD-SEC-03, lot « application et registre », partie `minos-app`** (sprint 8 de l'audit du 10 octobre 2026). Une entrée de `scripts/architecture/private-io-allowlist.json` (`McpBackendConfigurationStore.java`, `Files.newBufferedWriter`, maximum 1) nomme ce lot. Le magasin de configuration du backend MCP écrit un petit fichier `Properties` : il crée un temporaire privé (`createPrivateTempFile`, `:88`), le rouvre par un `Files.newBufferedWriter` brut (`:90`) qui suit un lien substitué, puis le publie (`DurableAtomicFile.replace`, `:97`) et revérifie sa confidentialité (`:98`).

## What Changes

- Le contenu est encodé en mémoire (même jeu de caractères : UTF-8) puis écrit par `PrivateLocalStorage.writePrivateFile`.
- Test de lien substitué au temporaire et test de contenu identique à l'octet près.
- L'entrée est retirée.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `stockage-prive-et-secrets` : une exigence ajoutée (le fichier de configuration du backend MCP est écrit par une primitive privée, sans suivre de lien).

## Hors périmètre

- Le format du fichier, ses clés autorisées et sa lecture (`BoundedProperties`).
- Le routage du backend (`McpBackendRouter`).
- La vérification de propriétaire du dossier (`verifier-le-proprietaire-des-dossiers-prives`).

## Impact

- **Modules du reactor** : `minos-app` seul. **Indépendant du socle** : `writePrivateFile` existe déjà.
- **Surfaces publiques** : aucune ; le fichier de configuration garde son format.
- **ADR** : aucun créé ni amendé.
- **Gates à rejouer** : `python scripts/architecture/check-private-io.py`, `python scripts/remediation/check-post-mne.py` (exige `BoundedProperties` dans le magasin, `:178-184`), `python scripts/quality/check-jacoco.py` (scope `m29-backend-routing`, rapport de `minos-app`, plancher 0,55 / 0,30), `python .claude/scripts/run_gates.py --fast`.
- **Plateformes** : Windows et Linux ; aucune branche de plateforme.
