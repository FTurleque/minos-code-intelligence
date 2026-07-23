# M7.1 — Empreintes reproductibles et ChangeSet

Date : **23 juillet 2026**

Statut : **IMPLÉMENTÉ — VALIDATION LOCALE EN ATTENTE**

Suivi : issue #22.

Base : M6 clôturé et fusionné dans `main` au commit
`5252e4498456feece21a5903548221e1ce1ba20f`.

## Objectif

Établir les faits nécessaires à l’indexation incrémentale avant toute décision de
réindexation partielle : quels fichiers visibles existent, quel est leur contenu
et qu’est-ce qui a changé entre deux observations du même projet.

M7.1 **ne décide pas** qu’un fournisseur peut réindexer partiellement.

## Contrats

- `FileFingerprint` ;
- `ProjectFingerprint` ;
- `ProjectChangeSet` ;
- `ProjectFingerprintService`.

## Empreinte d’un fichier

Chaque fichier visible expose :

```text
relativePath
sizeBytes
sha256
```

Le chemin est relatif au projet, normalisé et portable avec `/`.

Le hash est un SHA-256 du contenu brut du fichier. Les timestamps, propriétaires,
permissions et le chemin absolu du checkout n’entrent pas dans l’identité.

## Visibilité

La capture réutilise `ProjectIgnorePolicy` afin de rester cohérente avec M1 :

- répertoires techniques hard-ignored (`.git`, `.idea`, `.minos-m0`,
  `node_modules`, `target`, `dist`, `out`) non parcourus ;
- `.gitignore` et `.minosignore` racine appliqués ;
- les répertoires soft-ignored ne sont pas coupés prématurément afin de conserver
  les négations supportées par la politique M1 ;
- les symlinks ne sont pas suivis par `walkFileTree` ;
- seuls les fichiers réguliers visibles sont fingerprintés.

Les deux fichiers de contrôle racine :

```text
.gitignore
.minosignore
```

sont toujours fingerprintés lorsqu’ils existent, même s’ils tentent de
s’ignorer eux-mêmes. Une modification de politique d’exclusion doit rester
observable.

## Empreinte projet

`ProjectFingerprint.projectSha256` agrège dans l’ordre lexical des chemins :

```text
relativePath
sizeBytes
sha256 du contenu
```

L’empreinte est donc reproductible pour deux checkouts identiques placés dans
des répertoires absolus différents.

## Empreinte build

`ProjectFingerprint.buildSha256` agrège seulement les descripteurs actuellement
qualifiés par le périmètre M1 :

```text
pom.xml
package.json
package-lock.json
```

Ils peuvent se trouver à la racine ou dans des modules imbriqués.

M7.1 n’invente pas encore de support Gradle, pnpm, Yarn ou d’autres systèmes non
qualifiés dans `ProjectDiscovery`.

## ChangeSet

`ProjectFingerprintService.compare(previous, current)` produit quatre listes
triées et uniques :

```text
addedFiles
modifiedFiles
deletedFiles
unchangedFiles
```

Règles :

- absent avant, présent après → `ADDED` ;
- présent avant, absent après → `DELETED` ;
- même chemin mais contenu ou taille différents → `MODIFIED` ;
- même chemin, même contenu et même taille → `UNCHANGED`.

Le rapport expose également :

```text
projectChanged
buildDefinitionChanged
changedFileCount
```

`buildDefinitionChanged` signifie uniquement que l’empreinte des descripteurs de
build a changé. Ce n’est pas encore une décision d’invalidation complète.

## Propriétés de sûreté

M7.1 qualifie explicitement :

- stabilité de la capture répétée ;
- indépendance du chemin absolu ;
- indépendance des timestamps ;
- exclusion des fichiers ignorés et générés ;
- visibilité des changements de `.gitignore` / `.minosignore` ;
- distinction source-only / définition de build ;
- ajout, modification et suppression ;
- ordre déterministe des résultats.

## Replay réel

`ProjectFingerprintRealFixtureTest` capture deux fois la fixture versionnée :

```text
fixtures/typescript/typescript-modules
```

La porte exige une capture identique et la présence de fichiers TypeScript et du
`package-lock.json` réel.

La sortie Maven doit contenir :

```text
M7.1 typescript-modules fingerprints: files=..., project=..., build=...
```

Les hashes exacts observés seront enregistrés après validation locale du head de
PR.

## Hors périmètre M7.1

- persistance des snapshots d’empreintes ;
- association avec le snapshot d’index actif ;
- granularité fournisseur ;
- capacité `INCREMENTAL_INDEXING` ;
- règles d’invalidation ;
- décision `INCREMENTAL` vs `FULL` ;
- orchestration de l’exécution partielle ;
- watcher filesystem temps réel.

Ces sujets seront traités dans les incréments suivants de M7.

## Porte locale

```powershell
.\mvnw.cmd clean verify
```

La PR reste Draft jusqu’à validation locale du head exact.

## Suite

Après M7.1, l’incrément suivant devra persister les snapshots d’empreintes et les
lier explicitement au snapshot d’index actif avant de définir les règles
d’invalidation.
