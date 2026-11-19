# Tasks

## 1. Décision

- [x] 1.1 AUD-SEC-01 — D1 : la recommandation du design (option B : aucun pilote exécuté) est retenue le 2026-11-15, faute de décision contraire du propriétaire ; à confirmer à la fusion (l'ADR SEC-0083 reste `Proposed` jusque-là)
- [x] 1.2 AUD-SEC-01 — ADR `SEC-0083` « Filtres Git à la matérialisation d'un dépôt distant » écrit (numéro : plus haut numéro des worktrees, 0082, plus un ; la session du sprint 3 prend 0084 ou plus), ajouté à l'index et à `docs/architecture/arc42/09-decisions.md` ; `python .claude/scripts/run_gates.py` vert

## 2. Matérialisation distante par l'ouverture de confiance

- [x] 2.1 AUD-SEC-01 — Rouge : `RemoteMaterializationFilterTest` écrit (JVM fille au répertoire personnel redirigé ; clone, filtre intégré, revalidation du cache, propriétaire d'un autre compte). Couture de portée paquet : `JGitRemoteGitClient.cloneFrom` (reçoit l'URI et le rappel de transport) et constructeur du matérialiseur qui reçoit le propriétaire attendu. Rouge observé avant correction : 4 tests sur 4 en échec parce que le filtre de l'utilisateur s'exécutait au clone (témoin créé), qu'un filtre intégré enregistré s'exécutait, qu'un filtre `clean` de l'utilisateur s'exécutait à la revalidation d'une entrée du cache, et que l'entrée d'un autre compte était acceptée. Comportement de JGit 7.8.1 observé : voir le design
- [x] 2.2 AUD-SEC-01 — Correction : `TrustedRepositoryOpening` (type de portée paquet, deux politiques de filtres, `useJGitBuiltin` forcé à faux) sorti de `GitIntelligenceService` ; clone sans extraction puis extraction par `DirCacheCheckout` sur le dépôt ouvert en politique « distant ». Vérifié : le cas clone passe ; `GitIntelligenceTrustTest` (12, dont `aFilterOfTheUserConfigurationIsStillApplied`), `JGitRemoteRepositoryMaterializerTest` (9) et `JGitCloneDeadlineTest` (5) verts sans changement d'attente ; `check-remote-distributed-consistency.py`, `check-module-boundaries.py`, `check-comment-language.py` rendent 0
- [x] 2.3 AUD-SEC-01 — Correction : `validateCheckout` ouvre par `openExact`, après le clone et à la réutilisation du cache. Vérifié : les cas cache et propriétaire de 2.1 passent ; `-pl minos-integration-git -am test` : 69 tests, 0 échec, 1 sauté (`RemoteCachePrivateStorageTest`, antérieur) ; `GitRefusalMessagePathTest` vert
- [ ] 2.4 AUD-SEC-01 — **(manuelle)** Preuve JaCoCo : `python scripts/quality/check-jacoco.py` sur le rapport agrégé de `verify` (CI) et, si `TrustedRepositoryOpening` porte le contrôle du propriétaire, son ajout au scope qui nomme `JGitRemoteRepositoryMaterializer` et `JGitCloneDeadline` ; non rejoué localement (rapport agrégé de `verify`, long) — **Reste au 2026-11-19** : archivé sans cette tâche, voir `docs/project/audits/2026-11-15/SUIVI.md`.
- [x] 2.5 AUD-SEC-01 — Documentation : `docs/user/remote-indexing.md` (contenu brut, git-lfs), `docs/architecture/diagrams/capacites/analyse-git.md` (prose, vue du clone distant, écarts déplacés vers l'ouverture de confiance), `.claude/rules/securite.md` règle 5 ; liens et `check-current-docs.py` verts. Les lignes de traçabilité des trois exigences ajoutées ne s'écrivent qu'à l'archivage (le gate de dossier refuse une exigence absente de la spec de référence)

## 3. Garde contre l'ouverture directe

- [x] 3.1 AUD-SEC-01 — Gate : `check_single_repository_opening` parcourt tout `minos-integration-git/src/main/java`, refuse `Git.open(` et `new FileRepositoryBuilder` hors de `TrustedRepositoryOpening.java` et exige l'usage de l'ouverture par les trois classes. `scripts/quality/check-remote-distributed-consistency.py` avait déjà son `--self-test` (AUD-TST-09) : chaque interdiction par fichier est une preuve `forbid`, mutée automatiquement (314 mutations refusées pour 314 faits). Rouge observé : le gate échouait sur `JGitRemoteRepositoryMaterializer.java: missing semantic facts: opening.openExact` avant la correction
- [x] 3.2 AUD-SEC-01 — Le gate rend 0 sur le dépôt (`--self-test` : 314 mutations refusées), `check-post-mne.py` et `check-ci-wiring.py` rendent 0 ; `run_gates.py` complet rejoué

## 4. Clôture

- [x] 4.1 Mettre à jour la matrice de traçabilité du design (exigence → scénario → conception ou diagramme → tâche → test ou preuve) en nommant les preuves manquantes ; vérifier qu'aucune ligne n'affirme une couverture sans preuve
- [x] 4.2 Mettre à jour le dossier durable `docs/architecture/diagrams/capacites/analyse-git.md` ; vérifier par `python scripts/docs/check-capability-dossiers.py`
- [x] 4.3 Valider les spécifications ; vérifier par `openspec validate --all --strict`
- [x] 4.4 Lister les documents à mettre à jour avant archivage (STATUS, ROADMAP, `docs/user/remote-indexing.md`, arc42 § 9, ADR `SEC-*`, `SUIVI.md` de l'audit du 2026-11-15) ; vérifier que chacun est fait ou nommé comme reste

## Workflow follow-up

- Archiver le changement après revue (`openspec archive couvrir-le-clone-distant-par-l-ouverture-de-confiance -y`), puis vérifier que la spec de référence garde sa section `## Documentation liée`.
