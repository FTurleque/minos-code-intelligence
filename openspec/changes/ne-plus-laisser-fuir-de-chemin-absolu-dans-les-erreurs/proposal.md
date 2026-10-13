# Proposal

## Why

Deux constats du sprint 7 de l'audit du 10 octobre 2026 décrivent les deux moitiés d'une même fuite : un chemin absolu (nom du compte local, arborescence de `MINOS_HOME`) écrit dans le message d'une exception, puis filtré avant la sortie publique par un détecteur heuristique.

- **AUD-SEC-05** (faible, confirmé) : `PublicErrorMessages` est la dernière barrière avant stdout (CLI), la réponse MCP et l'API Java ; **12** classes de production passent par lui au HEAD. Un chemin absolu collé à un caractère d'identifiant lui échappe (`PublicErrorMessages.java:76`, `:102-104`) : c'est le cas d'un chemin précédé d'une séquence ANSI (`\u001b[31m/home/u/x`, dont le `m` final est une lettre) ou d'un lecteur Windows collé à un mot (`abcC:\Users\u`).
- **AUD-SEC-04** (faible, confirmé) : les primitives d'E/S privées écrivent encore le chemin absolu dans leurs messages. **5** sites relus au HEAD : `BoundedProperties.java:160`, `DurableAtomicFile.java:62` et `:227`, `FileProjectFingerprintSnapshotStore.java:610`, `FileIndexStateStore.java:586`. S'y ajoute un défaut plus large que le constat : `MinosRuntimeSettings.java:64` teste `Files.isRegularFile` (qui suit les liens) avant de lire ; si `config/minos.properties` est un répertoire ou un lien cassé, la configuration est **ignorée en silence** et MINOS démarre sur les valeurs par défaut (fail-open).

L'action proposée par l'audit n'est applicable telle quelle pour aucun des deux (voir `design.md`).

## What Changes

Deux lots, dans cet ordre.

- **Lot 1 — le détecteur (AUD-SEC-05)** : les séquences ANSI et les caractères de contrôle sont retirés avant l'analyse ; la forme lecteur Windows est reconnue où qu'elle apparaisse. Un chemin **relatif** à plusieurs segments reste non sensible.
- **Lot 2 — les messages (AUD-SEC-04)** : les cinq messages nomment la cible par une étiquette fixe ou un nom relatif, jamais par le chemin absolu ; `MinosRuntimeSettings.load` distingue « absent » (configuration vide) de « présent mais pas un fichier régulier » (refus), sans suivre les liens.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `surfaces-publiques` : deux exigences ajoutées (chemin absolu détecté malgré une séquence de contrôle ou un mot accolé ; chemin relatif non pris pour un chemin absolu).
- `stockage-prive-et-secrets` : deux exigences ajoutées (message d'une primitive sans chemin absolu ; configuration présente mais non régulière refusée).

## Hors périmètre

- Remplacer l'heuristique par un typage des messages publics (exception publique distincte de l'exception interne) : refonte plus large, non demandée par l'audit.
- La forme POSIX collée à un identifiant sans séquence de contrôle (`v1.2/home/u`) : indiscernable d'un chemin relatif ; limite déclarée (design, D2).
- Les journaux : la règle « chemin relatif à la racine gérée » y est déjà gardée par `scripts/architecture/check-private-io.py`.
- La racine absolue et les e-mails exposés volontairement par les sorties (AUD-SEC-10) : changement `ne-plus-exposer-la-racine-absolue-ni-les-e-mails-d-auteurs`.
- Les autres constats du sprint 7.

## Impact

- **Modules du reactor** : `minos-engine` (`com.minos.diagnostics.PublicErrorMessages`, `com.minos.io.BoundedProperties`, `com.minos.io.DurableAtomicFile`, `com.minos.storage.MinosRuntimeSettings`) ; `minos-storage-local` (`FileProjectFingerprintSnapshotStore`, `FileIndexStateStore`).
- **Surfaces publiques** : CLI, API Java et MCP — des messages d'erreur aujourd'hui publiés peuvent devenir le message générique (lot 1) ; un `MINOS_HOME` dont `config/minos.properties` est un répertoire ou un lien cassé échoue à l'ouverture au lieu de démarrer (lot 2). Aucun champ, option ni code de sortie nouveau. IntelliJ et NEXUS : aucun changement de protocole.
- **ADR** : aucun.
- **Gates à rejouer** : `python scripts/remediation/check-audit-remediation-v2.py` (`:150-157` exige des littéraux dans `MinosRuntimeSettings.java`), `python scripts/remediation/check-post-mne.py` (`:179-185` exige `BoundedProperties` dans `MinosRuntimeSettings.java`), `python scripts/architecture/check-private-io.py`, `python scripts/quality/check-jacoco.py` (scope qui inclut `com/minos/storage/MinosRuntimeSettings`, `:170`).
- **Plateformes** : Windows et Linux ; les tests de lien symbolique reprennent la garde de `BoundedPropertiesSymlinkTest`.
