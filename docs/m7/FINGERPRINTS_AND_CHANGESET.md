# M7.1 — Empreintes reproductibles et ChangeSet

Date : **23 juillet 2026**

Statut : **TERMINÉ, VALIDÉ LOCALEMENT ET LIVRÉ**

Suivi : issue #22.

Base : M6 clôturé et fusionné dans `main` au commit
`5252e4498456feece21a5903548221e1ce1ba20f`.

Validation : head `48715066ddb808a4ad4e821212d6eaa450738284`, **167/167 tests**, `BUILD SUCCESS`.

Livraison : PR #23 fusionnée dans `main` au commit
`34b57dfadad962b98c2d5c028957595cee575400`.

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
Le hash est un SHA-256 du contenu brut. Timestamps, propriétaires, permissions et
chemin absolu du checkout n’entrent pas dans l’identité.

## Visibilité

La capture réutilise `ProjectIgnorePolicy` afin de rester cohérente avec M1 :

- répertoires techniques hard-ignored (`.git`, `.idea`, `.minos-m0`,
  `node_modules`, `target`, `dist`, `out`) non parcourus ;
- `.gitignore` et `.minosignore` racine appliqués ;
- répertoires soft-ignored non coupés prématurément afin de préserver les
  négations supportées ;
- symlinks non suivis ;
- seuls les fichiers réguliers visibles sont fingerprintés.

`.gitignore` et `.minosignore` racine restent eux-mêmes fingerprintés lorsqu’ils
existent : une modification de politique d’exclusion doit rester observable.

## Empreintes agrégées

`ProjectFingerprint.projectSha256` agrège dans l’ordre lexical :

```text
relativePath + taille + sha256 contenu
```

`ProjectFingerprint.buildSha256` utilise uniquement les descripteurs actuellement
qualifiés :

```text
pom.xml
package.json
package-lock.json
```

M7.1 n’invente pas de support Gradle, pnpm ou Yarn.

## ChangeSet

`ProjectFingerprintService.compare(previous, current)` produit quatre listes
triées, uniques et disjointes :

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

Le rapport expose aussi :

```text
projectChanged
buildDefinitionChanged
changedFileCount
```

`buildDefinitionChanged` est un fait de comparaison, pas encore une règle
d’invalidation.

## Porte locale acquise

```text
.\mvnw.cmd clean verify
120 sources main compilées en release 24
60 sources test compilées en release 24
167 tests exécutés
0 failure
0 error
0 skipped
BUILD SUCCESS
```

Le warning `sun.misc.Unsafe` de `protobuf-java 4.34.2` sous Java 24 reste non
bloquant.

## Replay réel

Fixture :

```text
fixtures/typescript/typescript-modules
```

Résultat observé :

```text
M7.1 typescript-modules fingerprints: files=13, project=9103c5ddd376bad13d2f59c4dc923dd58eda6b8e0d0b0a0a991d29af96cb58bd, build=5b5b6d352221ca53a8844f9df644b3dd60b048b93d81e0f1bbade0359b504fb6
```

## Hors périmètre M7.1

- persistance des snapshots d’empreintes ;
- association avec le snapshot d’index actif ;
- granularité fournisseur ;
- capacité `INCREMENTAL_INDEXING` ;
- règles d’invalidation ;
- décision `INCREMENTAL` vs `FULL` ;
- orchestration de l’exécution partielle ;
- watcher filesystem temps réel.

Ces sujets sont traités par les incréments suivants de M7, en commençant par
M7.2 pour la persistance et l’association explicite aux snapshots d’index.
