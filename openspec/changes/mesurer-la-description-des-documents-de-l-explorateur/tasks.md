# Tasks

Changement indépendant des autres changements du sprint 10 ; aucun ordre de fusion imposé. Ne jamais lancer deux builds Maven en parallèle sur le poste. Le groupe 3 n'est fait que si la tâche 2.1 le déclenche.

## 1. Mesure « avant » et caractérisation (AUD-PERF-15)

- [ ] 1.1 AUD-PERF-15 — Écrire `minos-application/src/test/java/com/minos/application/semantic/SemanticCorpusPageMeasurementTest.java` (design, D2 option A ; protocole de « Performance et mesure ») : `@EnabledIfSystemProperty(named = "minos.explorer.page.measure", matches = "true")`, sur le modèle de `minos-bootstrap/src/test/java/com/minos/bootstrap/semantic/SemanticProjectionMeasurementTest.java:39-68` ; fixtures `2 000 × 20`, `400 × 500`, `40 × 5 000` et distribution `morpheus-engine`, documents à la forme de `SemanticDocumentFactory` (`:80-107`), doubles `FixedSnapshotStore` et `InMemoryVectorStore` ; un lecteur délégant passé au constructeur public de `SnapshotReadView` (`SnapshotReadView.java:25-38`) compte les appels `findFileSymbols`, sans nommer `InMemoryCodeKnowledgeStore` ; relevé sans chemin absolu dans `target/explorer-page-measurements.md`. Exigence : exploration-semantique › Le temps d'une page de documents est mesurable sur des fichiers à nombreux symboles. Vérifier : `./mvnw -B -ntp -pl minos-application -am test` (classe sautée, aucun relevé) ; `python scripts/architecture/check-module-boundaries.py` vert.
- [ ] 1.2 AUD-PERF-15 — Mesure « avant » au HEAD : `./mvnw -B -ntp -pl minos-application -am test "-Dtest=SemanticCorpusPageMeasurementTest" "-Dminos.explorer.page.measure=true" "-Dsurefire.failIfNoSpecifiedTests=false"`, trois exécutions sur le poste Windows (JDK 21, aucune autre charge Maven). Vérifier : relevé recopié dans « Performance et mesure » du design avec le commit, le JDK et le système ; ce qui n'est pas mesuré est nommé.
- [ ] 1.3 AUD-PERF-15 / AUD-PERF-14 — Consigner que le relevé de 1.2 ferme la tâche 1.4 d'AUD-PERF-14 (`openspec/changes/archive/2026-11-20-liberer-la-table-de-symboles-avec-sa-vue/tasks.md:14`), sans modifier l'archive : la mention va dans ce design et dans la PR. Vérifier : la matrice de traçabilité du design cite le relevé.
- [ ] 1.4 AUD-PERF-15 — Caractérisation (verte au HEAD) dans `SemanticCorpusQueryServiceTest` : page mêlée de documents `SYMBOL`, `CHUNK` et `FILE` sur trois fichiers, lecteur compteur ; attendu : trois appels `findFileSymbols`, aucun pour un `SYMBOL` ou `CHUNK` résolu, langage et module inchangés ; cas d'un `CHUNK` dont le symbole manque (langage et module du symbole de plus petite clé du fichier). Exigence : exploration-semantique › Un document n'est décrit par les symboles de son fichier qu'une fois par requête et sans table gardée. Vérifier : `./mvnw -B -ntp -pl minos-application -am test "-Dtest=SemanticCorpusQueryServiceTest"` vert, dont `anEvictedViewIsNoLongerReachableFromTheService` ; scope JaCoCo `explorer-surface` (`scripts/quality/check-jacoco.py:60-73`) sur les rapports CI.

## 2. Décision (AUD-PERF-15)

- [ ] 2.1 AUD-PERF-15 — **(manuelle, propriétaire)** Sur le relevé de 1.2, confirmer D1 (mesurer d'abord), le seuil de D3 (250 ms recommandés) et la forme de D4 (B recommandée) ; dire si le groupe 3 est fait. Vérifier : choix consigné dans la PR et dans « Décisions » du design ; si le seuil n'est pas franchi, le groupe 3 est marqué « sans objet » avec la mesure pour preuve.

## 3. Correction, seulement si 2.1 la déclenche (AUD-PERF-15)

- [ ] 3.1 AUD-PERF-15 — Rouge (D4 option B) : dans `minos-engine/src/test/java/com/minos/store/`, test qui exige que deux appels `findFileSymbols(projectId, fileId, Integer.MAX_VALUE)` rendent la même instance de liste (`assertSame`) et le même ordre qu'un tri de référence (ligne, colonne, nom qualifié, nom, id), y compris pour un symbole rangé sous `location().fileId()` différent de son `fileId()`. Vérifier : `./mvnw -B -ntp -pl minos-engine -am test` ; le test échoue au HEAD sur `assertSame`.
- [ ] 3.2 AUD-PERF-15 — Correction : `InMemoryCodeKnowledgeStore.SymbolIndexes.build` (`InMemoryCodeKnowledgeStore.java:654-686`) range chaque liste de `byFileId` dans l'ordre de `findFileSymbols` ; `findFileSymbols` (`:224-240`) rend la liste figée ou son préfixe, sans copie ni tri. Aucune table hors de la vue, aucune signature changée. Vérifier : tests de `minos-engine` et `SemanticCorpusQueryServiceTest` verts ; `python scripts/architecture/check-module-boundaries.py` vert.
- [ ] 3.3 AUD-PERF-15 — Preuve : mesure « après » par la commande de 1.2, trois exécutions, temps de construction de vue compris ; relevé dans le design et la PR. `./mvnw -B -ntp -pl minos-engine,minos-application -am verify` ; scopes JaCoCo `persistence-cache-indexes` (`check-jacoco.py:124-130`) et `explorer-surface` sur les rapports CI Linux et Windows.
- [ ] 3.4 AUD-PERF-15 — DATA-0085 (D5) : ajouter au point 19 (`docs/architecture/adr/DATA-0085-port-de-connaissance-et-persistance-des-snapshots-vues-comptees.md:56`) une phrase au présent : l'index en mémoire range une fois par vue les symboles de chaque fichier. Vérifier : `archgate check` et `python .claude/scripts/run_gates.py --fast` verts.

## 4. Mesures complémentaires (AUD-PERF-15)

- [ ] 4.1 AUD-PERF-15 — **(manuelle)** Temps d'une page filtrée sur un snapshot réel indexé avec son index sémantique (par exemple `morpheus-engine`, voie WSL si Java), servi par l'explorateur : `documents` avec un filtre de module absent, pages de la région `file:`, trois relevés. Vérifier : relevé dans le design ; sinon « non mesuré sur snapshot réel » nommé dans la qualification et dans le dossier de capacité.
- [ ] 4.2 AUD-PERF-14 — **(manuelle, facultative)** Comparaison avec la table d'avant la PR #797 : la classe de 1.1 copiée dans un worktree jetable `.claude/worktrees/<nom>` au commit `acd79cb86`, même protocole. Vérifier : relevé dans le design, ou mention « non faite ».
- [ ] 4.3 AUD-PERF-15 — **(manuelle, facultative)** Même mesure sous Linux (WSL, même commande que 1.2) et sur PostgreSQL de bout en bout. Vérifier : relevé dans le design, ou écart nommé dans le dossier de capacité (Linux et PostgreSQL non mesurés).

## 5. Documentation (AUD-PERF-15)

- [ ] 5.1 AUD-PERF-15 — `docs/developer/semantic-explorer.md` : réécrire `:49-52` (module et langage résolus par les lectures de la vue active, le temps de la requête), retirer « tables de symboles gardées : 4 projets » de `:197` (garder « empreintes d'index : 64 projets »), ajouter à `:203-206` la commande de mesure de 1.2. Vérifier : liens relatifs valides ; `python scripts/docs/check-current-docs.py` et `python .claude/scripts/run_gates.py --fast` verts.

## 6. Clôture

- [ ] 6.1 AUD-PERF-15 — Mettre à jour la matrice de traçabilité du design en nommant les preuves manquantes (CI, mesures manuelles, groupe 3 sans objet le cas échéant) ; aucune ligne n'affirme une couverture sans preuve.
- [ ] 6.2 AUD-PERF-15 — Valider : `openspec validate --all --strict` et `python .claude/scripts/run_gates.py`.
- [ ] 6.3 AUD-PERF-15 — Après archivage, ajouter à `docs/architecture/diagrams/capacites/exploration-semantique.md` (section Traçabilité, près de `:299`) les lignes des deux exigences ajoutées avec leurs tests, et un écart pour toute mesure non faite (snapshot réel, Linux, PostgreSQL). Vérifier : `python scripts/docs/check-capability-dossiers.py` vert.
- [ ] 6.4 AUD-PERF-15 — Lister les documents à mettre à jour avant archivage : `docs/developer/semantic-explorer.md`, dossier de capacité, DATA-0085 si 3.4, ligne AUD-PERF-15 (et réserve AUD-PERF-14) de `docs/project/audits/2026-11-15/SUIVI.md` ; STATUS et ROADMAP ne citent pas le constat. Vérifier : chacun fait ou nommé comme reste.

## Workflow follow-up

- Archiver après revue (`openspec archive mesurer-la-description-des-documents-de-l-explorateur -y`), puis vérifier que la spec de référence garde sa section `## Documentation liée`.
