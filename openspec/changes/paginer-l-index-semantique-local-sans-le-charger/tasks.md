# Tasks

## 1. Prérequis et mesure de référence (AUD-PERF-03)

- [ ] 1.1 AUD-PERF-03 — Vérifier que le banc de AUD-PERF-13 (sprint 1, changement `mesurer-la-projection-et-l-explorateur-au-banc`) est fusionné et mesure les routes liste, détail et voisins de l'explorateur en stockage local ; sinon s'arrêter. Vérifier : scénario décrit dans `benchmarks/scalability/README.md` et lançable.
- [ ] 1.2 AUD-PERF-03 — **(manuelle)** Mesure « avant » sur `main` : stockage local, index construits par la synchronisation — un index sous le plafond du cache, et un index de 32 768 documents à 768 dimensions (plafond de `SemanticIndexBudget`) dont le poids en cache dépasse 256 Mio (entrée refusée, lue sur `cacheStats()`) ; latence p50/p95 des routes liste, liste filtrée, détail, voisins ; tas retenu ; quatre requêtes simultanées à froid. Si aucun index constructible ne dépasse le plafond du cache sur le corpus du banc, l'écrire : le constat est alors sans objet sur ce corpus. Vérifier : sorties jointes à la PR avec machine, révision et corpus.
- [ ] 1.3 AUD-PERF-03 — **(manuelle, propriétaire)** Trancher D3 (voisins sans rétention quand le cache ne tient pas l'index) et D4 (vecteurs en `float[]` reportés au sprint 5) du design, et le seuil d'acceptation au vu de 1.2. Vérifier : décision écrite dans la PR ; si D3 est refusée, retirer le groupe 3 et l'exigence des voisins avant de coder.

## 2. Liste et détail sans charger l'index (AUD-PERF-03)

- [ ] 2.1 AUD-PERF-03 — Rouge : étendre `FileSemanticDocumentReadsContractTest` et `FileSemanticVectorStoreStreamedReadsTest` avec un store au plafond de cache étroit (constructeur de test, `FileSemanticVectorStore.java:75`) : (a) parcours complet par pages de 200 sur 500 documents, égal au chemin chargé, `cacheStats()` sans nouvel échec de cache ; (b) document présent et clé absente par `storedDocument`, même contrôle ; (c) index remplacé entre deux pages de documents : la page suivante vient du nouvel index, lu depuis son début, avec ses métadonnées (modèle : `aWalkResumedAfterTheIndexWasReplacedReadsTheNewIndexFromItsStart`, `FileSemanticVectorStoreStreamedReadsTest.java:76-89`) ; (d) fichier hors ordre refusé sans chemin absolu ; (e) index version 1 servi par le chemin chargé. Vérifier que (a) et (b) échouent au HEAD par le chargement complet compté. Exigences : exploration-semantique › En stockage local, une page de documents est lue sans charger l'index ; › En stockage local, le détail lit le seul document demandé. Vérifier : `./mvnw -B -ntp -pl minos-storage -am test "-Dtest=FileSemantic*Test" "-Dsurefire.failIfNoSpecifiedTests=false"` rouge.
- [ ] 2.2 AUD-PERF-03 — Correction : redéfinir `documentPage` dans `FileSemanticVectorStore` sur le modèle de `vectorPage` (lecteur en flux, saut des entrées antérieures, vecteurs sautés, contrôle d'ordre, empreinte du fichier et contrôle de la fenêtre d'ouverture repris tels quels, point de reprise propre aux documents). Vérifier : cas (a), (c), (d), (e) verts ; rejouer `scripts/remediation/check-mne.py` (chaînes `MAX_INDEX_FILE_BYTES`, `MAX_DECODED_STRING_BYTES`, `MAX_DECODED_VECTOR_BYTES`, `class DecodeBudget`, `requireIndexFileSize`, `Optional<IndexMetadata> metadata` à conserver), `scripts/quality/check-semantic-retrieval-consistency.py`, `scripts/architecture/check-private-io.py`, `scripts/quality/check-comment-language.py`.
- [ ] 2.3 AUD-PERF-03 — Correction : redéfinir `storedDocument` (saut jusqu'à la clé, arrêt à la première clé plus grande, un document et son vecteur décodés). Vérifier : cas (b) vert ; `ProjectMutationSemanticVectorStoreDelegationTest` vert ; mêmes gates que 2.2 ; scope JaCoCo `semantic-vector-store`.

## 3. Voisins sans retenir l'index (AUD-PERF-03)

- [ ] 3.1 AUD-PERF-03 — Rouge : test qui demande les 20 voisins du même vecteur à un store au cache large et à un store au cache étroit sur le même fichier, et attend mêmes documents, même ordre, mêmes scores, sans index retenu en cache par le second ; cas de refus « dimensions différentes » ; cas d'égalité de score départagée par la clé stable. Vérifier qu'il échoue au HEAD par le chargement complet compté. Exigence : exploration-semantique › En stockage local, les voisins sont classés sans retenir l'index.
- [ ] 3.2 AUD-PERF-03 — Correction : redéfinir `search` : chemin chargé quand le cache tient l'index, sinon classement en deux parcours (scores et top-k, puis documents du résultat). Vérifier : 3.1 vert ; `FileSemanticVectorStoreTest` et les tests de recherche sémantique de `minos-application` verts (`./mvnw -B -ntp -pl minos-application -am test "-Dtest=Semantic*Test,Hybrid*Test" "-Dsurefire.failIfNoSpecifiedTests=false"`) ; goldens inchangés (`git diff --exit-code -- minos-app/src/test/resources/characterization/`) ; mêmes gates que 2.2.

## 4. Décodage complet unique (AUD-PERF-03)

- [ ] 4.1 AUD-PERF-03 — Rouge : test de concurrence sur un index que le cache tient (quatre fils libérés ensemble par un verrou de départ, sans `Thread.sleep`, rejoué 50 fois) : un seul échec de cache compté par `cacheStats()` et le même index pour les quatre ; variante « fichier tronqué, deux fils » : deux erreurs, aucune entrée de cache. Vérifier qu'il échoue au HEAD (plusieurs décodages comptés). Exigence : exploration-semantique › Des lectures simultanées d'un index local que le cache tient ne le décodent qu'une fois.
- [ ] 4.2 AUD-PERF-03 — Correction : verrou par projet autour du décodage complet de `load`, avec relecture du cache à l'acquisition ; aucun verrou tenu pendant une lecture en flux. Caractériser par un test le cas que le verrou ne résout pas : quatre chargements d'un index que le cache refuse décodent quatre fois, en série. Vérifier : 4.1 vert sous Windows et sous Linux (CI) ; `FileSemanticSyncConsistencyTest` et `FileSemanticVectorStoreReuseTest` verts ; `./mvnw -B -ntp -pl minos-storage -am test` ; `python .claude/scripts/run_gates.py --fast`.
- [ ] 4.3 AUD-PERF-03 — Documentation : `docs/user/semantic-explorer.md` (comportement sur un index local plus lourd que le cache, sans chiffre non mesuré) ; `docs/developer/indexing-and-storage.md` si la lecture de l'index sémantique y est décrite. Vérifier : `python scripts/docs/check-current-docs.py`, liens valides.

## 5. Mesure après correction (AUD-PERF-03)

- [ ] 5.1 AUD-PERF-03 — **(manuelle)** Mesure « après » : même banc, même machine, mêmes jeux que 1.2. Vérifier : tableau avant/après joint à la PR, gain écrit tel que mesuré, y compris nul ; coût d'un détail sans point de reprise relevé à part.
- [ ] 5.2 AUD-PERF-03 — **(manuelle, propriétaire)** Reporter la mesure dans `docs/project/audits/2026-11-15/SUIVI.md` et décider, sur elle, de l'index d'offsets en mémoire (question ouverte du design). Vérifier : relecture du propriétaire.

## 6. Clôture

- [ ] 6.1 AUD-PERF-03 — Mettre à jour la matrice de traçabilité du design en nommant les preuves manquantes ; vérifier qu'aucune ligne n'affirme une couverture sans preuve.
- [ ] 6.2 AUD-PERF-03 — Mettre à jour `docs/architecture/diagrams/capacites/exploration-semantique.md` ; vérifier par `python scripts/docs/check-capability-dossiers.py`.
- [ ] 6.3 AUD-PERF-03 — Valider les spécifications ; vérifier par `openspec validate --all --strict`.
- [ ] 6.4 AUD-PERF-03 — Lister les documents à mettre à jour avant archivage (STATUS, ROADMAP, `docs/user`, `docs/developer`, arc42, SUIVI de l'audit) ; vérifier que chacun est fait ou nommé comme reste.

## Workflow follow-up

- Archiver le changement après revue (`openspec archive paginer-l-index-semantique-local-sans-le-charger -y`), puis vérifier que la spec de référence garde sa section `## Documentation liée`.
