# Design

## Context

Deuxième module du rangement demandé par le propriétaire le 11 octobre 2026 (option « les trois modules restants, en acceptant les classes rendues publiques »). HEAD analysé : `13921d11` (`develop`, après la fusion de la PR #411).

## État vérifié au HEAD

- `minos-storage-postgresql/src/main/java/com/minos/storage/postgresql/` : 18 classes, un seul package ; **une seule classe publique**, `PostgresStorageBackendProvider`, déclarée dans `META-INF/services` et nommée par `ShadedJarCompositionRootIT.java:44`.
- Aucun import `com.minos.storage.postgresql` hors du module (`grep` sur tous les `src` : seules des mentions du nom de module dans `ModuleArchitectureTest`, `DuplicationGuardTest`, `JsonEscapeGuardTest`).
- Analyse des dépendances internes (outil d'analyse corrigé, commentaires retirés ligne par ligne) pour le découpage retenu :
  - arêtes : `project → support` (8 références), `snapshot → support` (8), `semantic → support` (2), `racine → support/snapshot/project/semantic` ; **aucun cycle** ;
  - **13 types package-private** référencés depuis un autre groupe : `PostgresCodeKnowledgeSnapshotStore`, `PostgresConnectionFactory`, `PostgresFingerprintSnapshotStore`, `PostgresIndexStateStore`, `PostgresJsonCodec`, `PostgresProjectMutationLock`, `PostgresProjectRegistry`, `PostgresRuntimeObservationStore`, `PostgresSchemaMigrator`, `PostgresSemanticVectorStore`, `PostgresSnapshotPayloadCodec`, `PostgresStorageRetentionService`, `ProjectMutationIndexStateStore` ;
  - membres statiques package-private appelés depuis un autre groupe : `PostgresProjectMutationLock.acquire` (6 appelants), `.enterLifecycle`, `.exitLifecycle`, `.key`, et les types imbriqués `PostgresConnectionFactory.DedicatedConnectionContext` / `DedicatedConnectionLease`. Les membres d'instance concernés sont révélés par le compilateur.
- Tests : 20 fichiers dans le même package, dont `PostgresTestSupport`, partagé par tous.

### Écart avec la première analyse

Le tableau présenté au propriétaire avant sa première décision annonçait « environ 28 références » sans nommer les membres : l'outil d'analyse supprimait le fichier à partir du premier `//`. Les chiffres ci-dessus viennent de l'outil corrigé et ont été présentés au propriétaire avant sa décision finale.

## Goals / Non-Goals

**Goals** : un package par responsabilité, dépendances orientées vers `support`, aucun cycle, aucun comportement modifié.

**Non-Goals** : restaurer une encapsulation par JPMS ; réduire la complexité des classes ; modifier le SQL.

## Decisions

### D1. Découpage

| Sous-package | Classes |
|---|---|
| racine | `PostgresStorageBackend`, `PostgresStorageBackendProvider` |
| `support` | `PostgresConnectionFactory`, `PostgresJdbcUrlPolicy`, `PostgresSchemaMigrator`, `PostgresJsonCodec`, `PostgresSnapshotPayloadCodec`, `PostgresProjectMutationLock` |
| `snapshot` | `PostgresCodeKnowledgeSnapshotStore`, `PostgresFingerprintSnapshotStore`, `PostgresRuntimeObservationStore` |
| `project` | `PostgresProjectRegistry`, `ProjectMutationIndexStateStore`, `PostgresIndexStateStore`, `PostgresStorageRetentionService` |
| `semantic` | `PostgresSemanticReadQueries`, `PostgresSemanticWriteQueries`, `PostgresSemanticVectorStore` |

Les codecs et le verrou de mutation vont dans `support` parce que `snapshot` et `project` les utilisent tous deux : les laisser dans l'un créerait le cycle `snapshot ↔ project` mesuré sur un premier essai.

### D2. Visibilité élargie au strict nécessaire

Une classe ou un membre ne devient `public` que si le compilateur le refuse depuis un autre sous-package. Rien n'est ouvert par anticipation. Le coût est assumé par le propriétaire : sans JPMS, Java n'a pas de visibilité « module ».

### D3. Direction des dépendances (ADR 0022, ADR 0044)

Aucune dépendance Maven ne change. Les quatre packages appartiennent au seul module `minos-storage-postgresql` (règle A3) ; la règle A8 (aucun cycle de packages, table vide) est rejouée.

## Risks / Trade-offs

- Encapsulation affaiblie : la fabrique de connexions, la politique d'URL et le verrou de mutation deviennent publics. Atténuation : aucun autre module ne dépend de ce module à la compilation (découverte par `ServiceLoader`), ce que `check-module-boundaries.py` garde.
- Couverture SonarCloud : les fichiers déplacés comptent comme code neuf. La couverture du module dépend de PostgreSQL réel en CI ; si le seuil de 80 % n'est pas atteint, des tests sont ajoutés dans le module (jamais ailleurs).
- Baseline SpotBugs : à régénérer pour le module.

## Windows et Linux

Aucun comportement propre à une plateforme. Les tests du module exigent PostgreSQL (Testcontainers ou service de CI) sur les deux jobs `Verify`.

## Qualification de la capacité

`frontieres-de-modules` : **qualifiée** quand `check-module-boundaries.py` est vert et que les deux jobs `Verify` passent ; aucune capacité produit n'est ajoutée ni retirée.

## Migration

Aucune donnée, aucun schéma. Retour arrière : revert du commit.
