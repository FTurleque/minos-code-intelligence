# Tasks

Aucun constat d'audit (`AUD-…`) ne porte sur ce besoin : il vient de la tâche 1.5 du changement `supporter-les-gros-snapshots` (mesure du 2026-11-07).

## 1. Rétention après un import, côté application

- [x] 1.1 Rouge : écrire dans `minos-application` `PublishedSnapshotRetentionTest` (rétention appliquée : aucun diagnostic ; `IOException` dont le message cite un chemin absolu : diagnostic constant sans le chemin ; `RuntimeException` propagée) et `LocalProjectOperationsRetentionTest` (application construite par `MinosApplication.builder` avec ports en mémoire et racine de composition factice, aucun adaptateur : rétention appelée une fois, après l'enregistrement de l'état ; verrou occupé : `COMMITTED` et diagnostic constant ; état non enregistré et verrou occupé : `COMMITTED_METADATA_PENDING` et les deux lignes dans l'ordre ; artefact refusé : aucune rétention). Couvre `persistance-snapshots › Un import manuel applique la même rétention que l'indexation` et `persistance-snapshots › Un verrou de rétention occupé ne fait pas échouer un import publié`. Vérifier : `./mvnw -B -ntp -pl minos-application -am test` échoue sur ces tests pour la bonne raison (classe absente, rétention jamais appelée), sortie rouge notée dans le message de commit. Fait : `LocalProjectOperationsRetentionTest` lancé seul, 3 échecs sur 5 (rétention jamais appelée, diagnostic absent), les deux refus déjà verts.
- [x] 1.2 Correction : créer `com.minos.application.PublishedSnapshotRetention` ; l'appeler dans `LocalProjectOperations.importScipLocked` après `saveCommittedState` et avant l'historique, en combinant son diagnostic à celui de la validation ; faire déléguer `ProjectIndexingUseCase.compactAfterSuccess` à la même classe (méthode gardée, `compactAfterFailure` inchangé). Commentaires et Javadoc en anglais. Vérifier : tests 1.1 verts, `ProjectIndexingUseCaseTest` et `minos-application` entiers verts (`./mvnw -B -ntp -pl minos-application -am test`) ; rejouer `python scripts/remediation/check-post-mne.py`, `python scripts/architecture/check-module-boundaries.py` (A8 : aucun cycle de packages), `python scripts/quality/check-comment-language.py`. Fait : 470 tests de `minos-application` verts, les trois gates passent.
- [x] 1.3 Preuve de bout en bout sur le store fichier : écrire dans `minos-cli` `ImportScipRetentionTest` (backend local réel, fixture SCIP `fixtures/typescript/typescript-modules`, quatre `import-scip` avec `--snapshot` distincts : chaque import réussit, le dernier est actif, trois snapshots de connaissance restent et le premier a disparu). Couvre `persistance-snapshots › Un import manuel applique la même rétention que l'indexation` (scénario « Quatre imports distincts sur le store fichier »). Vérifier : le test échoue sans la correction 1.2 (quatre snapshots), passe avec (`./mvnw -B -ntp -pl minos-cli -am test`, lancé seul). Fait : sans la correction, quatre snapshots restent ; avec, trois.
- [x] 1.4 Documentation : ajouter au dossier `docs/architecture/diagrams/capacites/persistance-snapshots.md` la vue « Quand l'import applique-t-il la rétention, et que rend-il si elle ne tourne pas ? » (sequenceDiagram du design). Vérifier : `python scripts/docs/check-capability-dossiers.py`.

## 2. Rétention après un import, store PostgreSQL

- [x] 2.1 Écrire `PostgresStorageRetentionServiceTest#compactsSnapshotsImportedWithoutAnyRun` dans `minos-storage` : quatre snapshots publiés sans run ni empreinte, état du projet sur le dernier sans dernier run, rétention par défaut : trois snapshots restent, l'actif et celui de l'état conservés. Couvre `persistance-snapshots › La rétention après un import s'applique au store PostgreSQL`. Le comportement existe déjà : le test n'a pas de rouge attendu, il prouve l'exigence. Vérifier : test vert par Testcontainers (CI ; sur le poste seulement si Docker est disponible, sinon le dire). Fait : lancé sur le poste avec `-Dminos.postgresql.tests.required=true`, trois tests de la classe exécutés et verts.

## 3. Documentation produit

- [ ] 3.1 Après la fusion de la PR #673 dans `develop` (et la mise à jour de la branche) : retirer de DATA-0078, « Écarts constatés », l'écart « rétention et `import-scip` », sans toucher au point 11 ; réécrire dans `docs/user/autonomous-indexing.md`, section « Import manuel », la phrase sur l'absence de rétention : un import qui publie un snapshot applique la même rétention que l'indexation (l'actif et deux historiques), et un verrou occupé laisse l'import réussi avec un diagnostic. Vérifier : `python scripts/docs/check-current-docs.py`, `python scripts/docs/product-facts.py --check`, liens relatifs des fichiers modifiés.
- [ ] 3.2 **(manuelle)** Mesurer sous Windows, avec le build de la branche, quatre imports distincts du SCIP de `morpheus-engine` dans un `MINOS_HOME` temporaire : nombre de snapshots et espace occupé. Vérifier : trois snapshots, chiffre consigné dans `docs/developer/indexing-and-storage.md` à côté du relevé du 2026-11-07, sans le réécrire.

## 4. Clôture

- [ ] 4.1 Mettre à jour la matrice de traçabilité du design (exigence → scénario → conception ou diagramme → tâche → test ou preuve) en nommant les preuves manquantes ; vérifier qu'aucune ligne n'affirme une couverture sans preuve
- [ ] 4.2 Vérifier que le dossier durable de `persistance-snapshots` est à jour (vue de la tâche 1.4) ; vérifier par `python scripts/docs/check-capability-dossiers.py`
- [ ] 4.3 Valider les spécifications ; vérifier par `openspec validate --all --strict`
- [ ] 4.4 Rejouer `python .claude/scripts/run_gates.py --fast` ; vérifier qu'il réussit
- [ ] 4.5 Lister les documents à mettre à jour avant archivage (STATUS, ROADMAP, `docs/user/`, `docs/developer/`, arc42, ADR) ; vérifier que chacun est fait ou nommé comme reste

## Workflow follow-up

- Archiver le changement après revue (`openspec archive appliquer-la-retention-apres-import-scip -y`), ajouter au dossier `persistance-snapshots.md` les lignes de traçabilité des trois exigences, puis vérifier que la spec de référence garde sa section `## Documentation liée`.
