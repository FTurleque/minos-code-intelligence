# M7.2 — Snapshots persistants d’empreintes et alignement d’index

Date : **23 juillet 2026**

Statut : **IMPLÉMENTÉ — VALIDATION LOCALE EN ATTENTE**

Suivi : issue #22.

Base : M7.1 livré via PR #23 au commit
`34b57dfadad962b98c2d5c028957595cee575400`.

## Objectif

Conserver durablement les empreintes M7.1 et les associer explicitement au
snapshot d’index MINOS auquel elles correspondent, sans encore décider si une
réindexation partielle est sûre.

## Contrats

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

`indexSnapshotId` est une association fournie explicitement par l’orchestration.
M7.2 ne capture pas automatiquement le workspace au moment d’une promotion
d’index : cette intégration sera ajoutée lorsque le lifecycle incrémental sera
conçu.

Cette distinction évite de rattacher par erreur l’état courant du workspace à un
ancien snapshot d’index encore actif.

## Format fichier

Le backend fichier utilise un format binaire versionné `v1` avec :

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

Les entrées restent dans l’ordre déterministe produit par M7.1.

## Publication immuable

La publication écrit d’abord un fichier temporaire puis publie le snapshot dans
le répertoire du projet.

Le nom final contient :

```text
hash(indexSnapshotId)
checksum du fichier snapshot
```

Un même couple :

```text
projectId + indexSnapshotId
```

est immuable :

- republication avec le même contenu → idempotente ;
- republication avec un contenu différent → refusée.

Les anciens snapshots restent conservés et accessibles par leur
`indexSnapshotId`.

## Pointeur actif

La promotion est une opération séparée :

```text
publish(...)
promote(projectId, indexSnapshotId)
```

`publish` ne change jamais le snapshot actif.

`promote` écrit un `active.pointer` temporaire puis remplace atomiquement le
pointeur précédent. Le pointeur conserve :

```text
indexSnapshotId
fileName
checksum
projectSha256
buildSha256
fileCount
```

Le remplacement atomique ne s’applique qu’au pointeur. Les fichiers historiques
ne sont jamais écrasés.

## Vérification d’intégrité

Toute lecture vérifie :

- magic et version du format ;
- projet attendu ;
- checksum complet du fichier via son nom ;
- tailles et chemins des entrées ;
- ordre et unicité des chemins via `ProjectFingerprint` ;
- recalcul de `projectSha256` ;
- recalcul de `buildSha256` ;
- métadonnées du pointeur actif.

Une corruption du fichier historique ou du pointeur actif est donc refusée avant
retour au consommateur.

## Alignement avec le lifecycle M1

`ProjectFingerprintSnapshotAlignmentService` compare le pointeur d’empreintes
actif avec `ProjectIndexState.activeSnapshotId`.

Comportements :

- index actif sans baseline fingerprint → `Optional.empty()` ;
- index actif + fingerprint portant le même ID → résultat aligné ;
- IDs différents → erreur explicite ;
- fingerprint actif alors que le lifecycle n’annonce aucun index actif → erreur.

Le service ne compare pas encore le fingerprint à l’état courant du workspace.
Il vérifie uniquement l’association persistée entre les deux snapshots.

## Historique

Le store permet :

```text
load(projectId, indexSnapshotId)
loadActive(projectId)
listIndexSnapshotIds(projectId)
```

La liste historique est triée et refuse les IDs dupliqués.

## Tests de qualification

`FileProjectFingerprintSnapshotStoreTest` couvre notamment :

- publication sans promotion implicite ;
- promotion du premier snapshot ;
- publication et promotion d’un second snapshot ;
- réouverture d’une nouvelle instance ;
- conservation du premier snapshot ;
- remplacement du pointeur actif ;
- republication identique idempotente ;
- refus de réécriture historique ;
- refus d’une promotion non publiée ;
- détection de corruption ;
- isolation par projet.

`ProjectFingerprintSnapshotAlignmentServiceTest` couvre :

- absence de baseline compatible avec un index actif ;
- alignement exact ;
- désalignement explicite ;
- pointeur fingerprint interdit sans index actif.

## Replay réel

`ProjectFingerprintSnapshotRealFixtureTest` utilise :

```text
fixtures/typescript/typescript-modules
```

Chaîne vérifiée :

```text
capture M7.1
 -> publish fingerprint snapshot
 -> promote fingerprint pointer
 -> nouvelle instance du store
 -> ProjectIndexState READY
 -> alignement
 -> relecture identique
```

La sortie Maven doit contenir :

```text
M7.2 typescript-modules fingerprint-snapshot: index=..., files=13, history=1, project=..., build=...
```

## Hors périmètre M7.2

- décision d’invalidation ;
- capacité fournisseur `INCREMENTAL_INDEXING` ;
- choix `INCREMENTAL` vs `FULL` ;
- calcul d’un périmètre partiel par indexeur ;
- modification de `IndexingLifecycleService` ;
- capture automatique au moment de la promotion d’index ;
- watcher filesystem.

## Porte locale

```powershell
.\mvnw.cmd clean verify
```

La PR reste Draft jusqu’à validation locale de son head exact.

## Suite

M7.3 pourra établir les premières règles d’invalidation conservatrices à partir
du `ProjectChangeSet`, de l’empreinte build et de l’alignement avec le snapshot
d’index, avant d’introduire les capacités incrémentales propres aux fournisseurs.
