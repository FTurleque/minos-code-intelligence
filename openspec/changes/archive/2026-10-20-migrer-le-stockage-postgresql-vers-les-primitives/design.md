# Design

## Context

Suite d'AUD-SEC-03 (sprint 8) : lot « stockage PostgreSQL ». HEAD analysé : `4922f4ac` (`develop`). Dépend de `completer-les-primitives-d-e-s-privees`. Aucun code, test ni gate n'est modifié par cette spécification.

## État vérifié au HEAD

- `check-private-io.py` : une entrée, `minos-storage-postgresql/…/snapshot/PostgresCodeKnowledgeSnapshotStore.java`, `Files.newOutputStream`, maximum 2 ; positions `:292` (`requireSameContent`) et `:388` (lecture de l'actif).
- Les deux chemins obtiennent leur fichier par `createScratchFile` (`:405-418`) : nom à UUID sous `scratchRoot`, vérifié `normalize()` + `getParent()` identique, créé par `PrivateLocalStorage.createPrivateFile` (échec fermé si le nom existe). `requireSafeScratchRoot` (`:425-430`) refuse un lien et appelle `verifyPrivateDirectory`.
- `:288-293` : `result.getBinaryStream(1)` copié dans le fichier par `copyBounded(input, output, MAX_PERSISTED_SNAPSHOT_BYTES)` puis comparaison de l'empreinte avec `SnapshotIntegrityService.checksum`. `:385-394` : même copie avec un `DigestInputStream`, fichier supprimé si la copie échoue.
- Tests : `PostgresCodeKnowledgeSnapshotStoreTest`, `PostgresCodeKnowledgeSnapshotStoreCacheTest` (Testcontainers ; `PostgresTestSupport` ignore l'absence de Docker sur poste mais **échoue** en CI). Scope JaCoCo `m30-postgresql-pgvector` (Linux seulement, plancher ligne 0,60).
- Gates littéraux : `check-mnd.py` et `check-mne.py` lisent le fichier ; aucun ne cite un appel de `Files`.
- Recouvrements (changements actifs, code non encore archivé) : `lire-l-identifiant-du-snapshot-a-la-reconciliation` (5/6), `mutualiser-les-caches-lru-et-nommer-les-additions-bornees` (0/15, touche ce magasin), `figer-la-dette-de-frontieres-par-des-cliquets`, `trancher-les-decisions-d-architecture-du-sprint-10`.

**Non vérifié** : aucun test PostgreSQL exécuté (Docker non démarré dans cette session).

## Écarts avec la justification de la liste

Aucun : « vers `PrivateLocalStorage` / `DurableAtomicFile` » est exact, avec la précision que seule l'écriture en flux privée convient (le fichier est rempli depuis un flux SQL de taille bornée, pas depuis la mémoire).

## Goals / Non-Goals

**Goals** : retirer l'entrée ; un lien substitué au fichier de travail est refusé.

**Non-Goals** : changer la copie bornée, l'ordre des opérations ou le schéma ; migrer `Files.deleteIfExists`.

## Decisions

### D1. Primitive — recommandation : `openPrivateOutputStream`

Le fichier existe déjà, privé, créé par `createPrivateFile` ; l'ouverture durcit (sans effet) puis ouvre `NOFOLLOW`. Option B : `openNewTreeFile` (`CREATE_NEW`) après avoir supprimé le fichier. Rejetée : elle déplacerait la création, aujourd'hui testée.

### Constat de mise en œuvre (2026-10-18)

Les tests PostgreSQL (Testcontainers) s'exécutent sur le poste Windows avec Docker Desktop : 357 tests du module et de ses amonts, 0 échec. Le fichier de travail qui reçoit le **payload encodé d'une publication** (`snapshot-write-`) est écrit par le codec de snapshot du stockage local (`SnapshotBinaryCodecSupport`, 3 `Files.newOutputStream`), non par ce magasin : sa migration est celle du changement `migrer-le-stockage-local-vers-les-primitives`. Le test de lien de ce changement ne vise donc que les fichiers `snapshot-read-` et `snapshot-existing-`. La couture de test est un constructeur de paquet qui reçoit un observateur de création de fichier de travail (état d'instance).

### D2. Un seul changement avec le magasin local ? — recommandation : non

Le module est distinct, ses tests exigent Docker et son scope JaCoCo est Linux seul ; un échec de la CI PostgreSQL ne doit pas bloquer le stockage local.

## Direction des dépendances

`storage → engine` (ADR 0022) : aucune dépendance nouvelle. Le magasin importe déjà `com.minos.io.PrivateLocalStorage` (`:10`).

## Qualification des capacités

- Fichier de travail privé sans suivi de lien : **qualifiée** sur Linux (CI) ; **partielle** sous Windows (tests ignorés sans Docker).

## Windows et Linux

Aucun comportement dépendant de la plateforme n'est ajouté. Le test de lien substitué utilise un fichier de travail créé par le test lui-même dans un `scratchRoot` privé, sans PostgreSQL : il passe sous Windows sans Docker s'il vise `requireSameContent` par un point d'injection de package ; sinon il s'exécute en CI Linux.

## Risks / Trade-offs

- **Preuve CI seule pour ce module** : sur un poste sans Docker, aucun test PostgreSQL ne s'exécute. Mitigation : tâche 1.3 **(manuelle)** avec Docker ou lecture du run CI Linux avant la fusion.
- **Conflit avec `mutualiser-les-caches-lru-…`** sur le même fichier : à rebaser, le maximum de l'entrée se recalcule (le gate le signale).
