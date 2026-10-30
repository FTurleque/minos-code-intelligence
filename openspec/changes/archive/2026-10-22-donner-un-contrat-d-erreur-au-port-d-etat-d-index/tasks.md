# Tasks

Ordre : relevé et décisions, puis dans chaque section rouge, correction, preuve. Tout `-pl` s'accompagne de `-am`. À implémenter de préférence **après** `unifier-l-orchestration-d-indexation` (mêmes fichiers). Les exigences de `specs/` valent pour D1-A comme pour D1-B ; seule la forme du type change.

## 1. Relevé et décisions (AUD-QUA-03)

- [x] 1.1 [AUD-QUA-03] Recompter les appels du port en production (fichiers, méthodes) et les `catch` qui les entourent ; classer chaque `throw` des quatre implémentations en « panne de stockage » ou « violation d'invariant » (D2) ; lister les 6 doubles de test du port et les tests qui affirment un type ou un message d'exception du store ; lire le changement `ne-plus-laisser-fuir-de-chemin-absolu-dans-les-erreurs` pour réutiliser sa primitive de message s'il en définit une. Consigner dans `design.md`.
- [x] 1.2 [AUD-QUA-03] **Décision à obtenir** : D1 (A, exception non vérifiée dédiée, recommandé ; ou B, exception vérifiée). Consigner dans `design.md`.
- [x] 1.3 [AUD-QUA-03] **Décision à obtenir** : D5 (refus métier reportés, recommandé ; ou inclus). Consigner dans `design.md`.

## 2. Type d'échec du port et implémentations (AUD-QUA-03)

- [x] 2.1 [AUD-QUA-03] Rouge : test de contrat commun, appliqué à `InMemoryIndexStateStore`, `FileIndexStateStore`, `PostgresIndexStateStore` et `ProjectMutationIndexStateStore` — panne de lecture, d'écriture, de bail : type du port, cause conservée ; libération du bail par un autre thread : `IllegalStateException`. **Preuve du rouge : `UncheckedIOException` côté fichier, `IllegalStateException` côté PostgreSQL au HEAD.**
- [x] 2.2 [AUD-QUA-03] Déclarer le type à côté du port (Javadoc de chaque méthode, bail compris) ; le lever dans les quatre implémentations selon le classement de 1.1 ; aligner les doubles de test. Preuve : test de contrat vert ; `./mvnw -B -ntp -pl minos-storage-local -am verify` ; `./mvnw -B -ntp -pl minos-storage-postgresql -am verify` (dire si les tests PostgreSQL réels ont été exécutés localement ou seulement en CI) ; `python scripts/quality/check-jacoco.py` (scope `m30-postgresql-pgvector`) ; `python scripts/architecture/check-private-io.py`.

## 3. Appelants (AUD-QUA-03)

- [x] 3.1 [AUD-QUA-03] Rouge : dans `minos-bootstrap` (où vit `ProjectIndexStateReconcilerTest`), un store qui lève `NullPointerException` à la lecture — la réconciliation ne produit pas « failed to read project index state » ; un store qui lève le type du port — l'`IOException` existante est produite. Test d'interruption pendant une écriture : run interrompu et proposé à la reprise. **Preuve du rouge : le `NullPointerException` devient une `IOException` au HEAD (`ProjectIndexStateReconciler.java:191`).**
- [x] 3.2 [AUD-QUA-03] Restreindre au type du port les `catch` de `ProjectIndexStateReconciler` (`:154`, `:191`, `:199`, `:207`), `AuthoritativeProjectStateReconciler` (`:469`), `IndexingRunExecutor` (`:824`) et `LocalProjectOperations` (`:168`), et ceux relevés en 1.1 (D3). Preuve : tests verts ; `./mvnw -B -ntp -pl minos-bootstrap -am verify` ; `python scripts/quality/check-jacoco.py` (scopes `critical-orchestration`, `resume-orchestration`) ; `python scripts/remediation/check-post-mne.py` et `python scripts/remediation/check-mne.py` ; `./mvnw -B -ntp -pl minos-app -am verify` (goldens inchangés).

## 4. Messages sans chemin absolu (AUD-QUA-03)

- [x] 4.1 [AUD-QUA-03] Rouge : tests de `FileIndexStateStore` sous une racine temporaire — fichier d'état illisible, propriété manquante, nom de fichier inattendu, compteur d'exécutions invalide : le message ne contient pas la racine. **Preuve du rouge : le chemin absolu figure dans le message au HEAD (`FileIndexStateStore.java:173`, `:636`, `:673`).**
- [x] 4.2 [AUD-QUA-03] Réécrire les messages (D4), cause chaînée. Preuve : tests verts ; `./mvnw -B -ntp -pl minos-storage-local -am verify` ; `python scripts/architecture/check-private-io.py`.

## 5. Clôture

- [x] 5.1 `docs/developer/architecture.md` : contrat d'erreur du port d'état d'index (type, ce qui est une panne, ce qui est un invariant) ; `docs/user/troubleshooting.md` si un message visible change. Rejouer `python scripts/docs/check-current-docs.py`.
- [x] 5.2 Mettre à jour `docs/project/audits/2026-10-10/SUIVI.md` (AUD-QUA-03 : statut, 4 implémentations, décisions D1 et D5, décompte « 43 » non reproduit, refus métier restant ouvert si D5 reporté).
- [x] 5.3 `python .claude/scripts/run_gates.py` ; `openspec validate --all --strict` ; docs à mettre à jour avant archivage : `docs/project/governance/STATUS.md`, `docs/developer/architecture.md`. **Fait (2026-10-22)** : `run_gates.py` 46/46, `openspec validate --all --strict`, `check-current-docs.py` et `product-facts.py --check` verts sur `develop` après fusion ; STATUS, ROADMAP et SUIVI mis à jour par la PR d'archivage.

## Preuves d'implémentation (2026-10-21)

- 2.1 : rouge exécuté — store fichier : `expected: <IndexStateStoreException> but was: <UncheckedIOException>` (lecture, partition en lien symbolique) et `<IllegalStateException>` (propriété manquante, identité, compteur) ; PostgreSQL : `but was: <IllegalStateException>` (`PostgresIndexStateStoreTest`, `ProjectMutationIndexStateStoreRefusalTest`). Vert après correction : `minos-storage-local` 272 tests, `minos-storage-postgresql` 357 tests (base réelle Testcontainers, exécutés **localement**, 0 sauté).
- 3.1 : rouge exécuté — `expected: <NullPointerException> but was: <IOException>` ; vert après restriction des `catch`. Test d'interruption pendant l'écriture (`IndexingInterruptionTest`, double aligné sur le type du port, cause `ClosedByInterruptException`) : vert.
- 4.1 : rouge et vert avec 2.1 (`FileIndexStateStoreErrorContractTest`, messages sans la racine temporaire).
- `mvnw -pl minos-app -am verify` (avec `-Dmaven.test.failure.ignore` pour tout relever) : seuls 2 doubles de test levaient l'ancien type, alignés puis rejoués verts ; les goldens de `minos-app` sont verts. `check-jacoco.py` vert (dont `critical-orchestration`, `resume-orchestration`, `m30-postgresql-pgvector`) ; `check-private-io.py`, `check-mne.py`, `check-post-mne.py`, `check-module-boundaries.py`, `check-current-docs.py`, `run_gates.py --fast` (21/21), `openspec validate --all --strict`, `archgate check` verts.
- 5.1 : `docs/user/troubleshooting.md` non modifié : aucun message visible d'une panne de stockage d'état ne change de texte, seule la classe journalisée d'un run hérité corrompu passe à `IndexStateStoreException`.
- 5.3 non coché : `run_gates.py` complet, STATUS et archivage hors de cette session.
