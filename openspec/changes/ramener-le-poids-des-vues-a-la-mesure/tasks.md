# Tasks

Ordre dans chaque section : rouge, correction, preuve. Tout `-pl` s'accompagne de `-am`. Deux lots séparables : 1-2 (poids) puis 3 (parcours).

## 1. Relevé (AUD-PERF-03)

- [ ] 1.1 [AUD-PERF-03] Relever les tests existants du cache des deux stores et ceux qui encodent l'ordre « empreinte avant décodage » (message d'erreur d'un fichier corrompu) ; relire `check-mne.py:85-88` et `check-mnd.py:82-83,99-100` pour savoir s'ils figent la **valeur** du facteur. Consigner dans `design.md`.

## 2. Poids fondé sur la mesure (AUD-PERF-03)

- [ ] 2.1 [AUD-PERF-03] Rouge : dans `minos-storage-local`, test de cache — snapshot V3 de plus de 64 Mio persistés (généré), deux `loadActiveQueryView`, attendu `fullSnapshotLoads == 1`. **Preuve du rouge : 2 au HEAD.** Test de poids par format (méthode de `HybridCorpusWeightTest`) : estimation ≥ tas retenu.
- [ ] 2.2 [AUD-PERF-03] `FileSymbolSnapshotStore` : facteur rationnel mesuré pour V3, facteur d'origine pour un format non mesuré (D1) ; conserver les chaînes exigées par `check-mne.py`. Preuve : tests verts ; `./mvnw -B -ntp -pl minos-storage-local -am verify` ; `python scripts/remediation/check-mne.py` et `check-mnd.py` (mis à jour dans cette tâche s'ils figent la valeur) ; scope JaCoCo `persistence-cache-indexes`.
- [ ] 2.3 [AUD-PERF-03] `PostgresCodeKnowledgeSnapshotStore` : même rouge puis même correction ; scope `m30-postgresql-pgvector`. Tests PostgreSQL réels : dire s'ils ont été exécutés localement ou seulement en CI.
- [ ] 2.4 [AUD-PERF-03] Mesure avant/après sur ce dépôt : `minos index` puis deux requêtes MCP identiques, relever `fullSnapshotLoads` et la durée de la seconde. Consigner dans `SUIVI.md`.

## 3. Parcours unique et intégrité (AUD-PERF-03)

- [ ] 3.1 [AUD-PERF-03] **Décision à obtenir** : D2 (parcours unique sous le store fichier, recommandé, ou deux parcours conservés). Consigner dans `design.md`.
- [ ] 3.2 [AUD-PERF-03] Rouge : tests d'intégrité des deux stores (D4) — octet modifié, fichier tronqué, octets ajoutés en queue : erreur d'intégrité, cache vide, compteur inchangé, seconde lecture identique ; test du nombre de parcours (flux compteur). **Preuve du rouge sur le nombre de parcours.**
- [ ] 3.3 [AUD-PERF-03] Store PostgreSQL : empreinte calculée pendant la copie (D3). Store fichier : selon D2. Preuve : tests de 3.2 verts ; `python scripts/architecture/check-private-io.py` vert ; `./mvnw -B -ntp -pl minos-storage-postgresql -am verify`.

## 4. Clôture

- [ ] 4.1 Compléter la « Mise en œuvre » de l'ADR 0047 (décision 1 : valeur retenue, mesures) ; rejouer `python scripts/docs/check-current-docs.py`.
- [ ] 4.2 Mettre à jour `docs/audit/2026-10-10/SUIVI.md` (AUD-PERF-03 : statut, décision D2, mesures 2.4) ; signaler AUD-PERF-11 et AUD-ARC-07 comme débloqués.
- [ ] 4.3 `openspec validate --all --strict` ; docs à mettre à jour avant archivage : `docs/STATUS.md`, ADR 0047, documentation d'exploitation (tas du serveur MCP).
