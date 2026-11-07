# Proposal

## Why

`minos import-scip` publie un snapshot de connaissance sans jamais appliquer la rétention des snapshots. Seule l'indexation (`minos index`) compacte : `ProjectIndexingUseCase` appelle `retentionService.compact(projectId)` après un échec (`compactAfterFailure`) et après un succès (`compactAfterSuccess`), alors que `LocalProjectOperations.importScipLocked` publie le snapshot, enregistre l'état du projet et l'historique, puis rend la main.

Mesuré le 2026-11-07 avec un build non modifié de `develop` `bc2995a8` : quatre imports de variantes distinctes du SCIP de `morpheus-engine` (1 488 fichiers Java) laissent cinq snapshots, 1 697,7 Mio, alors que la politique par défaut garde l'actif et 2 historiques (environ 1 Gio pour un snapshot de 340 Mio). Rien ne borne cette croissance avant la prochaine indexation du projet.

Pourquoi maintenant : sous Windows, `import-scip` est la voie d'indexation du Java. `scip-java` est refusé en natif (ADR SEC-0074), l'indexation passe par WSL2 puis par l'import du SCIP produit. Le relèvement du plafond de persistance à 400 Mio (changement `supporter-les-gros-snapshots`, ADR DATA-0078) rend importables des snapshots de 340 Mio : chaque réimport d'un gros projet ajoute autant sur disque. L'écart est consigné par la tâche 1.5 de ce changement (PR #673, non fusionnée à la rédaction) dans les « Écarts constatés » de DATA-0078 et dans `docs/user/autonomous-indexing.md`, section « Import manuel ».

Aucun constat d'audit ne porte sur ce besoin : il vient d'une mesure faite pour `supporter-les-gros-snapshots`.

## What Changes

- **Rétention après un import** : un import qui a publié son snapshot applique ensuite au projet la même rétention que l'indexation (politique par défaut : l'actif, plus 2 snapshots historiques, plus les runs bornés), sur le store fichier comme sur PostgreSQL.
- **Verrou de rétention occupé** : l'import reste réussi (code de sortie 0, `commitStatus` inchangé) ; le champ `diagnostic` du résultat porte la ligne que l'indexation rend déjà dans ce cas, sans cause ni chemin.
- **Import refusé ou en échec** : aucune rétention n'est lancée ; le refus reste le seul résultat.
- **Documentation** : l'écart est retiré de DATA-0078 et la page utilisateur dit que l'import compacte comme l'indexation.
- Aucun champ ajouté, retiré ou renommé ; aucune **RUPTURE**.

## Capabilities

### New Capabilities

Aucune. La rétention des snapshots relève de la capacité `persistance-snapshots`, qui porte déjà la récupération des pointeurs temporaires orphelins et la protection du run offert à la reprise.

### Modified Capabilities

- `persistance-snapshots` : trois exigences ajoutées, « Un import manuel applique la même rétention que l'indexation », « Un verrou de rétention occupé ne fait pas échouer un import publié » et « La rétention après un import s'applique au store PostgreSQL ».

## Hors périmètre

- **Valeurs de la politique** (2 snapshots historiques, 20 runs réussis, 10 runs non réussis) : inchangées. Les rendre réglables est un autre sujet.
- **Rétention après un import en échec** : non lancée (décision D3 du design). L'indexation compacte après un échec pour libérer ce que le run a préparé ; un import en échec ne publie rien, et les temporaires orphelins restent à la charge de la rétention suivante (exigence existante « Les pointeurs temporaires orphelins sont récupérés sans toucher une écriture en cours »).
- **Point 11 de DATA-0078** (ADR 0023 et 0024 disent qu'aucune politique automatique n'est adoptée, le code en applique une) : écart distinct, laissé au propriétaire. Ce changement ne le tranche pas : il applique à l'import la politique qui existe.
- **Commande de compaction à la demande** (`minos storage compact` ou équivalent) : non proposée.
- **Indexation Java depuis Windows** (voie WSL2) : inchangée.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| L'indexation compacte après un échec et après un succès, y compris sans changement | [Existant vérifié] | `minos-application/src/main/java/com/minos/application/indexing/ProjectIndexingUseCase.java:308`, `:327`, `:366`, `:376`, `:382-388`, `:457-464` |
| `importScipLocked` publie, enregistre l'état et l'historique, sans compacter | [Existant vérifié] | `minos-application/src/main/java/com/minos/application/LocalProjectOperations.java:118-157` |
| Un verrou de rétention occupé après une indexation réussie donne un diagnostic sans cause, pas un échec | [Existant vérifié] | `ProjectIndexingUseCase.java:457-464` ; tests `ProjectIndexingUseCaseTest#aBusyRetentionAfterASuccessIsADiagnosticNotAFailure`, `minos-cli/src/test/java/com/minos/cli/RetentionFailureAfterIndexTest.java` |
| Politique par défaut : 2 snapshots historiques, 20 runs réussis, 10 runs non réussis | [Existant vérifié] | `minos-engine/src/main/java/com/minos/storage/PersistentRetentionPolicy.java:9-16` |
| Store fichier : le snapshot actif du store et celui désigné par l'état du projet sont protégés | [Existant vérifié] | `minos-storage/src/main/java/com/minos/storage/local/LocalStorageRetentionService.java:83-107` |
| PostgreSQL : les mêmes deux pointeurs protègent le snapshot ; la rétention s'exécute dans la session du bail de cycle de vie quand il est détenu | [Existant vérifié] | `minos-storage/src/main/java/com/minos/storage/postgresql/project/PostgresStorageRetentionService.java:23-37`, `:40-69` ; `minos-storage/src/main/java/com/minos/storage/postgresql/support/PostgresProjectMutationLock.java:24-31` ; `minos-storage/src/main/java/com/minos/storage/postgresql/project/ProjectMutationIndexStateStore.java:39-80` |
| Quatre imports distincts de `morpheus-engine` laissent cinq snapshots, 1 697,7 Mio | [Existant vérifié] | mesure du 2026-11-07, `develop` `bc2995a8` (PR #673, `docs/developer/indexing-and-storage.md`) |
| Après un import, l'indexation suivante est complète : l'empreinte de référence ne désigne plus le snapshot actif | [Existant vérifié] | `minos-engine/src/main/java/com/minos/orchestration/ProjectInvalidationService.java:69-80` (`BASELINE_INDEX_MISMATCH`) |
| Le diagnostic de verrou occupé est le même texte pour l'import et pour l'indexation | [Proposition] | design, D2 |
| Pas de rétention après un import en échec | [Proposition] | design, D3 |

## Impact

- **Modules du reactor** : `minos-application` (`LocalProjectOperations`, `ProjectIndexingUseCase`, une classe de rétention partagée), `minos-storage` (test PostgreSQL seulement), `minos-cli` (test de bout en bout seulement). Aucune dépendance Maven ajoutée.
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : additif. `import-scip` et `index --scip` (déprécié) gardent leurs champs ; `diagnostic`, qui existe déjà, peut porter la ligne de rétention. MCP : lecture seule, non concerné. IntelliJ : client du protocole CLI, aucun champ changé.
- **ADR** : aucun nouveau. DATA-0078 : l'écart « rétention et `import-scip` » des « Écarts constatés » est retiré une fois le correctif livré (tâche dépendante de la fusion de la PR #673).
- **Gates à rejouer** : `scripts/remediation/check-post-mne.py` (chaînes `retentionService.compact(projectId)`, `compactAfterFailure(`, `compactAfterSuccess(prepared.project().id())` dans `ProjectIndexingUseCase.java`), `scripts/architecture/check-module-boundaries.py` (A8, aucun cycle de packages), `check-comment-language.py`, `check-capability-dossiers.py`, `check-current-docs.py`, `run_gates.py --fast`.
- **Plateformes** : Windows et Linux, comportement identique ; c'est sous Windows que l'import est la voie du Java.
- **Documentation durable et diagrammes** : dossier `docs/architecture/diagrams/capacites/persistance-snapshots.md` (vue de la rétention après publication ; lignes de traçabilité à l'archivage) ; `docs/user/autonomous-indexing.md`, section « Import manuel » ; DATA-0078, « Écarts constatés ».

## Questions ouvertes

**Bloquantes** : aucune.

**Non bloquantes**
- Le texte du diagnostic dit « it runs after the next indexing » ; après ce changement, il court aussi au prochain import. Recommandation : garder le texte tel quel (il reste vrai, et l'indexation l'émet déjà vers toutes les surfaces) plutôt que de changer une sortie existante (design, D2).
