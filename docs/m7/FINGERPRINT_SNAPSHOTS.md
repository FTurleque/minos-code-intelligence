# M7.2 — Snapshots persistants d’empreintes et alignement d’index

Date : **23 juillet 2026**

Statut : **TERMINÉ, VALIDÉ LOCALEMENT ET LIVRÉ**

Suivi : issue #22.

PR : **#24**.

Head validé :
`f1b3e619335ab7e0dc766ebae12df1ff88a7b47d`.

Merge dans `main` :
`379b5a28a92cb58b340dc8801d66fad1b853e4ce`.

## Objectif

Conserver durablement les empreintes M7.1 et les associer explicitement au
snapshot d’index MINOS auquel elles correspondent, sans décider à ce stade si
une réindexation partielle est sûre.

## Contrats livrés

- `ProjectFingerprintSnapshot` ;
- `ProjectFingerprintSnapshotStore` ;
- `FileProjectFingerprintSnapshotStore` ;
- `ProjectFingerprintSnapshotAlignmentService`.

## Association explicite

Chaque snapshot persistant porte :

```text
projectId
indexSnapshotId
ProjectFingerprint
```

`indexSnapshotId` est fourni explicitement par l’orchestration. M7.2 ne rattache
jamais implicitement l’état courant du workspace à un ancien index encore actif.

## Format fichier

Le backend utilise un format binaire versionné `v1` :

```text
magic MNFP
formatVersion
projectId
indexSnapshotId
projectSha256
buildSha256
fileCount
[file path, size, sha256]...
```

Les entrées gardent l’ordre déterministe produit par M7.1.

## Historique immuable

Un même couple :

```text
projectId + indexSnapshotId
```

est immuable :

- republication identique → idempotente ;
- republication avec contenu différent → refus explicite.

Les snapshots antérieurs restent chargeables par `indexSnapshotId`.

## Publication et promotion

Les opérations sont séparées :

```text
publish(...)
promote(projectId, indexSnapshotId)
```

`publish` ne modifie jamais le pointeur actif.

`promote` remplace atomiquement `active.pointer`. Le remplacement concerne
uniquement ce pointeur ; les snapshots historiques ne sont jamais écrasés.

## Intégrité

Toute lecture vérifie notamment :

- magic et version ;
- projet attendu ;
- checksum complet du fichier ;
- structure et nombre d’entrées ;
- ordre et unicité des chemins ;
- recalcul de `projectSha256` ;
- recalcul de `buildSha256` ;
- cohérence des métadonnées du pointeur actif.

Une corruption est refusée avant retour au consommateur.

## Alignement avec le lifecycle M1

`ProjectFingerprintSnapshotAlignmentService` confronte le pointeur fingerprint
actif à `ProjectIndexState.activeSnapshotId` :

- index actif sans baseline fingerprint → absence explicite ;
- IDs identiques → baseline alignée ;
- IDs différents → erreur ;
- fingerprint actif sans index actif → erreur.

Ce service vérifie l’association persistée ; il ne compare pas encore le
fingerprint au workspace courant.

## API historique

```text
load(projectId, indexSnapshotId)
loadActive(projectId)
listIndexSnapshotIds(projectId)
```

La liste historique est triée et refuse les IDs dupliqués.

## Tests qualifiés

La suite couvre :

- publication sans promotion implicite ;
- remplacement du pointeur actif ;
- historique et réouverture ;
- republication idempotente ;
- refus de réécriture historique ;
- promotion d’un snapshot absent ;
- corruption ;
- isolation projet ;
- alignement et désalignement avec `ProjectIndexState` ;
- replay file-backed de la fixture TypeScript réelle.

## Replay réel

Fixture :

```text
fixtures/typescript/typescript-modules
```

Résultat validé :

```text
M7.2 typescript-modules fingerprint-snapshot: index=typescript-modules-index, files=13, history=1, project=9103c5ddd376bad13d2f59c4dc923dd58eda6b8e0d0b0a0a991d29af96cb58bd, build=5b5b6d352221ca53a8844f9df644b3dd60b048b93d81e0f1bbade0359b504fb6
```

## Porte locale acquise

```text
.\mvnw.cmd clean verify
124 sources main compilées en release 24
63 sources test compilées en release 24
176 tests exécutés
0 failure
0 error
0 skipped
BUILD SUCCESS
```

Le warning `sun.misc.Unsafe` de `protobuf-java 4.34.2` reste non bloquant.

## Hors périmètre M7.2

- décision d’invalidation ;
- capacité fournisseur d’indexation incrémentale ;
- choix final `INCREMENTAL` vs `FULL` ;
- modification de `IndexingLifecycleService` ;
- capture automatique au moment de la promotion ;
- watcher filesystem.

## Suite

M7.3 introduit les règles d’invalidation conservatrices à partir du
`ProjectChangeSet`, du changement de build et de l’alignement avec le snapshot
d’index actif.
