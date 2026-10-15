# Tasks

Ordre dans chaque section : rouge, correction, preuve. Tout `-pl` s'accompagne de `-am`. Deux lots séparables : 1-2 (poids) puis 3 (parcours).

## 1. Relevé (AUD-PERF-03)

- [x] 1.1 [AUD-PERF-03] Relever les tests existants du cache des deux stores et ceux qui encodent l'ordre « empreinte avant décodage » (message d'erreur d'un fichier corrompu) ; relire `check-mne.py:85-88` et `check-mnd.py:82-83,99-100` pour savoir s'ils figent la **valeur** du facteur. Consigner dans `design.md`. **Fait (2026-10-13)** : les deux gates exigent des noms, pas la valeur du facteur ; mesure et écart consignés dans `design.md`.
- [x] 1.2 [AUD-PERF-03] **Décision obtenue (2026-10-13)** : dédoublonner d'abord les chaînes au décodage (ADR 0047, option A), puis fixer le facteur sur la nouvelle mesure. Rouge : `SnapshotDecodeStringSharingTest` (2 échecs sur 3). Correction : table de chaînes locale à la passe de décodage dans `SnapshotBinaryCodecSupport`. Preuve : test vert (3/3), tas retenu divisé par deux dans `QueryViewWeightTest`.

## 2. Poids fondé sur la mesure (AUD-PERF-03)

- [x] 2.1 [AUD-PERF-03] Rouge : dans `minos-storage-local`, test de cache (plafond réglé à trois fois la taille persistée plutôt qu'un snapshot de 64 Mio généré, même condition d'admission), deux `loadActiveQueryView`, attendu `fullSnapshotLoads == 1`. **Preuve du rouge : 2 au HEAD.** Test de poids par format (méthode de `HybridCorpusWeightTest`) : estimation ≥ tas retenu.
- [x] 2.2 [AUD-PERF-03] `FileSymbolSnapshotStore` : facteur 2, entier, pour tous les formats (après dédoublonnage le rapport mesuré est 1,41 à 1,46 en V3 ; V1 et V2, en UTF-16, ont un fichier plus gros pour le même tas : écart avec D1, voir `design.md`) ; conserver les chaînes exigées par `check-mne.py`. Preuve : tests verts ; `./mvnw -B -ntp -pl minos-storage-local -am verify` ; `python scripts/remediation/check-mne.py` et `check-mnd.py` (mis à jour dans cette tâche s'ils figent la valeur) ; scope JaCoCo `persistence-cache-indexes`.
- [x] 2.3 [AUD-PERF-03] `PostgresCodeKnowledgeSnapshotStore` : même facteur (pas de test rouge propre : le store réutilise le codec et la même formule). `verify -pl minos-storage-postgresql -am` exécuté sur le poste : 283 tests, 0 échec, 0 sauté. Scope `m30-postgresql-pgvector` non rejoué par `check-jacoco.py`.
- [ ] 2.4 [AUD-PERF-03] Mesure avant/après sur ce dépôt : `minos index` puis deux requêtes MCP identiques, relever `fullSnapshotLoads` et la durée de la seconde. Consigner dans `SUIVI.md`. **Non faite (2026-10-14), tâche laissée ouverte** : Tentative d'indexation réelle de ce dépôt sur le poste (2026-10-14) : la CLI de développement n'a pas le lanceur scip-java Windows de la distribution ; la distribution installée (1.2.0, MINOS_HOME temporaire, corpus réduit au reactor Java au commit `949e1569`) s'arrête sur `index failed: IllegalStateException`, cause interne opaque par conception ; aucun index réel n'a donc été produit. À faire sur un poste Linux (ou WSL avec JDK 25) où scip-java indexe ce dépôt : deux `loadActiveQueryView` successifs, `fullSnapshotLoads` attendu 2 avant la PR #442 et 1 après.

## 3. Parcours unique et intégrité (AUD-PERF-03)

- [x] 3.1 [AUD-PERF-03] **Décision obtenue (2026-10-13)** : D2, parcours unique sous le store fichier. Consignée dans `design.md`.
- [x] 3.2 [AUD-PERF-03] Tests d'intégrité (D4) — octet modifié, fichier tronqué, octets ajoutés en queue : erreur d'intégrité, cache vide, compteurs inchangés, seconde lecture identique (`FileSymbolSnapshotStoreIntegrityTest`, 4 tests) ; nombre de parcours par flux compteur (`SnapshotVerifiedReadTest`, 7 tests, V2 et V3). **Écart : pas de rouge au sens strict.** Les tests d'intégrité du store sont des caractérisations, vertes avant et après le branchement ; le parcours unique est prouvé au niveau du codec, dont la lecture authentifiée n'existait pas avant. Côté PostgreSQL, aucun test nouveau : les 283 tests existants couvrent la lecture.
- [x] 3.3 [AUD-PERF-03] Store PostgreSQL : empreinte calculée pendant la copie (D3), deux parcours au lieu de trois. Store fichier : `SnapshotCodec.readVerified`, un parcours pour V2 et V3 (V1 garde deux parcours par l'implémentation par défaut). Preuve : tests de 3.2 verts ; `python scripts/architecture/check-private-io.py` vert ; `./mvnw -B -ntp -pl minos-storage-postgresql -am verify`.

## 4. Clôture

- [x] 4.1 Compléter la « Mise en œuvre » de l'ADR 0047 (décision 1 : valeur retenue, mesures) ; rejouer `python scripts/docs/check-current-docs.py`. **Preuve (2026-10-15)** : `docs/adr/0047-snapshot-pagine-ou-mappe-et-table-de-chaines.md`, « Mise en œuvre » complétée (lot 3 et mesure réelle absente) ; `check-current-docs.py` vert.
- [x] 4.2 Mettre à jour `docs/audit/2026-10-10/SUIVI.md` (AUD-PERF-03 : statut, décision D2, mesures 2.4) ; signaler AUD-PERF-11 et AUD-ARC-07 comme débloqués. **Preuve (2026-10-15)** : `docs/audit/2026-10-10/SUIVI.md`, ligne du constat dans le tableau du sprint 4 et section « Implémentation » ou « Clôture du sprint 4 ».
- [ ] 4.3 `openspec validate --all --strict` ; docs à mettre à jour avant archivage : `docs/STATUS.md`, ADR 0047, documentation d'exploitation (tas du serveur MCP).
