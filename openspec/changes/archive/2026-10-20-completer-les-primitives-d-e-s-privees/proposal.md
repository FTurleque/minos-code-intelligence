# Proposal

## Why

**AUD-SEC-03, suite** (sprint 8 de l'audit du 10 octobre 2026). Le garde `scripts/architecture/check-private-io.py` interdit désormais vingt familles d'appels bruts et fige les 44 occurrences existantes dans `scripts/architecture/private-io-allowlist.json`. Chaque entrée nomme un lot de migration (stockage local, stockage PostgreSQL, providers SCIP, runtime local, application et registre, moteur). Aucun lot n'est spécifié : la migration était hors du sprint 8.

Au HEAD `4922f4ac`, les primitives existantes ne couvrent pas la moitié des sites à migrer :

- **Écrire en flux dans un fichier privé** : `PrivateLocalStorage.writePrivateFile(Path, byte[])` exige le contenu en mémoire. Vingt-trois sites écrivent un snapshot, un vecteur, une archive ou un téléchargement en flux ; treize ouvrent un fichier déjà créé privé par `createPrivateTempFile` / `createPrivateFile` avec un `Files.newOutputStream` brut, les dix autres créent eux-mêmes leur fichier (`CREATE_NEW` brut, `Files.copy`, `Files.createTempFile`).
- **Lire par canal sans suivre de lien** : `ConfinedFileOpener.openRegularFileNoFollow` rend un `InputStream`. `BoundedFileDigest`, `ScipIndexReader` et `ScipSymbolSnapshotImporter` ont besoin de `size()` et de `position(0)` sur le même descripteur.
- **Renommer un répertoire** : `DurableAtomicFile.publish` et `replace` forcent le fichier puis le publient par lien dur ; ils ne s'appliquent pas à un répertoire. Six sites de `Files.move` (une dizaine d'appelants compris) déplacent un répertoire de préparation, de quarantaine ou de cache avec `Files.move`.

Ce changement ajoute ces trois capacités au moteur, sans toucher à aucun autre module, et migre le seul site du lot « moteur » (`BoundedFileDigest`). Les neuf autres changements de la série les consomment.

## What Changes

- `PrivateLocalStorage` : trois ouvertures en flux. La première crée ou durcit un fichier puis l'ouvre sans suivre de lien (niveau « durci », pour les fichiers de plan de contrôle et de snapshot). La deuxième crée **exclusivement** un fichier durci puis l'ouvre (journaux et copies que MINOS relit dans un dossier inscriptible par le provider). La troisième crée un fichier **nouveau** dans un arbre dont la confidentialité vient de sa racine (niveau « arbre privé », pour les extractions et copies en masse) : droits POSIX `0600` demandés à la création, ACL Windows héritée, aucune réécriture de DACL par fichier.
- `ConfinedFileOpener` : une ouverture en lecture par canal, sans suivre de lien, avec les contrôles de type de `openRegularFileNoFollow`.
- `DurableAtomicFile` : une publication de répertoire (rename atomique, échec fermé sans repli par copie, reprise bornée sous Windows).
- `BoundedFileDigest` passe par l'ouverture par canal ; son entrée de liste disparaît.
- Aucune entrée de `PrivateLocalStorage` ne grossit : les deux ouvertures réutilisent les sites bruts existants.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `stockage-prive-et-secrets` : deux exigences ajoutées (écriture en flux privée sans suivi de lien ; lecture par canal et publication de répertoire fail-closed).

## Hors périmètre

- La migration des sites des autres modules : neuf changements séparés (`migrer-…`), qui consomment ces primitives.
- Les entrées définitives : `LinuxCgroupJob` (cgroupfs) et les cinq entrées de `PrivateLocalStorage`.
- Les `Files.createDirectories` / `createDirectory` des arbres de travail de provider (héritage d'ACL du conteneur) et de `MinosMcpHandshakeProbe`.
- Les ACL Windows de `PrivateLocalStorage` (AUD-SEC-06, `conserver-les-refus-conditionnels-windows`) et la vérification de propriétaire (`verifier-le-proprietaire-des-dossiers-prives`).
- Une primitive de copie : une copie est une lecture confinée suivie d'une écriture privée, composée par l'appelant.

## Impact

- **Modules du reactor** : `minos-engine` seul (`com.minos.io`).
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune. L'API Java des primitives s'élargit de façon additive.
- **ADR** : à décider (D5) ; recommandation : un ADR court, proposé en tâche 5.1, pour les deux niveaux de confidentialité ; sinon `docs/developer/quality-gates.md` seul.
- **Gates à rejouer** : `python scripts/architecture/check-private-io.py`, `python scripts/architecture/test_check_private_io.py -v`, `python scripts/architecture/check-module-boundaries.py`, `python scripts/quality/check-jacoco.py` (scope du moteur), `python scripts/docs/check-current-docs.py`, `python .claude/scripts/run_gates.py --fast`.
- **Plateformes** : POSIX et Windows ; les tests d'ACL sont déjà sélectionnés par `@EnabledOnOs`.
