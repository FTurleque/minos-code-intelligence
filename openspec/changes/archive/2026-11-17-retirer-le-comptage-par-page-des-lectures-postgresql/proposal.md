# Proposal

## Why

Constat **AUD-PERF-01** de l'audit `docs/project/audits/2026-11-15/` (sévérité moyenne, sprint 4). Sous PostgreSQL, chaque requête de page de l'index sémantique porte une sous-requête `SELECT COUNT(*) FROM semantic_documents` sur tout l'index du projet : pages de vecteurs lues par `minos semantic projection prepare`, pages de documents et détail lus par l'explorateur. Une passe de préparation lit `N / 1 000` pages et recompte `N` lignes à chacune. La préparation fait jusqu'à 43 passes (AUD-PERF-02).

Ce coût est **calculé, pas mesuré** : l'audit le dit, et aucun banc ne couvre aujourd'hui la projection ni l'explorateur (AUD-PERF-13, sprint 1, non livré). Ce changement ne présente donc aucun gain comme acquis : il exige une mesure avant et après sur le banc de AUD-PERF-13, et ne s'ouvre pas tant que ce banc n'existe pas.

## What Changes

- Le nombre de documents d'un index sémantique PostgreSQL est tenu à l'écriture dans `semantic_index_meta` (colonne `document_count`, migration de schéma 6 avec reprise de l'existant) et lu avec les métadonnées ; les requêtes de page (`documentPage`, `vectorPage`), de document (`storedDocument`) et de métadonnées (`metadata`) ne comptent plus `semantic_documents`.
- Les requêtes de page sont scindées en « première page » et « page suivante » : la page suivante porte `stable_key > ?` sans le prédicat `CAST(? AS text) IS NULL OR …`, pour que la borne reste une condition d'index quel que soit le plan retenu par le serveur.
- Les textes SQL des lectures paginées deviennent des constantes visibles du package, pour qu'un test en lise le plan.
- Un test PostgreSQL prépare ces requêtes (`PREPARE`), lit leur plan en plan générique forcé (`EXPLAIN (ANALYZE) EXECUTE`) et échoue si un agrégat ou un filtre hors index y revient.
- Mesure avant et après sur le banc `benchmarks/scalability` étendu par AUD-PERF-13 : durée de `semantic projection prepare` et latence des routes liste, détail et voisins sur PostgreSQL, sur un index que la synchronisation peut construire (32 768 documents à 768 dimensions au plus) et, à part, sur un index synthétique plus grand écrit par le harnais.
- Aucune rupture : le contrat du port `SemanticVectorStore`, les réponses de l'explorateur et la sortie de la commande ne changent pas.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `exploration-semantique` : trois exigences **ajoutées** (aucune exigence existante modifiée) — page PostgreSQL sans recomptage, page suivante bornée par l'index, nombre de documents repris par la migration. Les garanties générales de migration restent celles de `persistance-snapshots`.

## Hors périmètre

- Le nombre de passes de la préparation (AUD-PERF-02, changement `ajuster-la-projection-sur-l-echantillon-en-memoire`).
- Le stockage local (AUD-PERF-03, changement `paginer-l-index-semantique-local-sans-le-charger`).
- Le transfert de `embedding::text` et son analyse en Java (`PostgresSemanticReadQueries.java:215`, `:328-342`) : relevé par l'audit dans la preuve, sans action demandée ; à reprendre seulement si la mesure après correction le désigne.
- Un index secondaire sur `(project_id, snapshot_id)` : la clé primaire `(project_id, stable_key)` sert déjà le parcours ordonné ; à décider sur le plan mesuré.
- La relecture de la charge utile des snapshots par `project_id` (AUD-PERF-05) et le budget mémoire (AUD-PERF-04) : sprint 5.
- L'extension du banc (AUD-PERF-13, sprint 1) : prérequis, spécifié ailleurs.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| `vectorPage` évalue un `COUNT(*)` de l'index dans chaque requête de page | [Existant vérifié] | `minos-storage/src/main/java/com/minos/storage/postgresql/semantic/PostgresSemanticReadQueries.java:211-218` |
| `documentPage` et `storedDocument` portent la même sous-requête | [Existant vérifié] | même fichier `:133-135`, `:177-179` |
| `metadata` compte par jointure et `GROUP BY` | [Existant vérifié] | même fichier `:46-49` |
| Le schéma n'a ni colonne de compte ni index sur `snapshot_id` | [Existant vérifié] | `minos-storage/src/main/java/com/minos/storage/postgresql/support/PostgresSchemaMigrator.java:96-98` |
| Le coût quadratique par passe est un calcul | [Existant vérifié] | `findings.json`, AUD-PERF-01 (« calculé et non mesuré ») |
| La synchronisation ne construit pas d'index de plus de 32 768 documents à 768 dimensions (192 Mio à 8 octets par dimension), ni de plus de 250 000 documents | [Existant vérifié] | `minos-application/src/main/java/com/minos/application/semantic/SemanticIndexBudget.java:15-19`, `:59-75` ; `SemanticIndexService.java:137-138` |
| Un test encode la version de schéma 5 et son retour en version 4 | [Existant vérifié] | `minos-storage/src/test/java/com/minos/storage/postgresql/support/PostgresSchemaUpgradeFromV4Test.java:36`, `:49-57` |
| Le compte est tenu à l'écriture dans `semantic_index_meta` | [Proposition] | design D1, à valider par le propriétaire |
| Les requêtes de page sont scindées sans condition | [Proposition] | design D2 |
| Le gain en durée | [Question] | mesure avant et après sur le banc de AUD-PERF-13 |

## Impact

- **Modules du reactor** : `minos-storage` (packages `com.minos.storage.postgresql.semantic` et `com.minos.storage.postgresql.support`). Aucun autre module.
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune modification ; sorties identiques.
- **ADR** : aucun à écrire. La colonne ajoutée est une migration additive du schéma PostgreSQL ; si le propriétaire y voit un changement de format persisté à tracer, il s'ajoute à la « Mise en œuvre » de l'ADR du stockage PostgreSQL, en tâche.
- **Gates à rejouer** : `python .claude/scripts/run_gates.py --fast` ; `scripts/remediation/check-p0-p2.py` (il lit `PostgresSchemaMigrator.java`, `:182`, `:299`) ; `scripts/quality/check-comment-language.py` ; scope JaCoCo `m30-postgresql-pgvector`.
- **Plateformes** : Windows et Linux — le code est commun ; les tests PostgreSQL tournent là où un PostgreSQL/pgvector est fourni (CI Linux, poste avec Docker).
- **Documentation durable et diagrammes** : `docs/architecture/diagrams/capacites/exploration-semantique.md` (traçabilité des trois exigences) ; `docs/developer/quality/code-audit-couverture.md` (elle cite `CURRENT_VERSION = 5`, `:64`) ; `docs/user/semantic-explorer.md` seulement si une mesure qualifie la préparation PostgreSQL.

## Questions ouvertes

Non bloquantes pour la rédaction ; **D1 bloque l'implémentation** (voir design, « Décisions ») :

- D1 — compte tenu à l'écriture (migration 6, recommandé) ou compte lu une fois par parcours sans migration.
- Seuil d'acceptation du gain : aucun chiffre n'a de source ; il se fixe après la mesure de référence.
