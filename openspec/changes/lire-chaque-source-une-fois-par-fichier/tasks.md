# Tasks

Ordre : rouge, correction, preuve. Changement limité à `minos-application` ; à fusionner en série avec les deux autres changements sémantiques du sprint (même classe `SemanticDocumentFactory`).

## 1. Relevé et références (AUD-PERF-02)

- [ ] 1.1 [AUD-PERF-02] Relever le comportement actuel de `readExcerpt` sur un fichier illisible, trop gros ou disparu, et ce que `SemanticDocumentFactory` en fait ; chercher `readExcerpt` et `LocalSourceReader` dans `scripts/` (gates littéraux). Figer les empreintes des documents produits au HEAD sur les fixtures de qualification (fichier de référence du test). Consigner dans `design.md`.

## 2. Une lecture par fichier (AUD-PERF-02)

- [ ] 2.1 [AUD-PERF-02] Rouge : tests de `minos-application` — compteur d'ouvertures (1 pour un fichier à vingt symboles ; total = nombre de fichiers distincts), empreintes des documents égales à la référence de 1.1, fichier illisible, fichier modifié après la construction. **Preuve du rouge : le compteur vaut le nombre de symboles au HEAD.**
- [ ] 2.2 [AUD-PERF-02] `LocalSourceReader` : lecture par fichier rendant un objet d'extraits, `readExcerpt` réécrit au-dessus du même code (D1) ; `SemanticDocumentFactory.build` : un objet par fichier, de portée locale ; commentaire `:69-70` corrigé. Preuve : tests verts ; `./mvnw -B -ntp -pl minos-application -am verify` ; `check-private-io.py` vert ; scope JaCoCo `semantic-hybrid-retrieval` ; `python scripts/architecture/check-module-boundaries.py`.
- [ ] 2.3 [AUD-PERF-02] Mesure indicative avant/après de la construction des documents sur ce dépôt (durée, nombre de documents). Dire ce qui n'a pas pu être mesuré (provider d'embeddings absent : mesurer la construction seule).

## 3. Clôture

- [ ] 3.1 Mettre à jour `docs/audit/2026-10-10/SUIVI.md` (AUD-PERF-02 : statut, mesure, renvoi « connu » corrigé vers l'audit d'architecture de septembre).
- [ ] 3.2 `openspec validate --all --strict` ; docs à mettre à jour avant archivage : `docs/STATUS.md` si la durée d'indexation avec embeddings y est citée.
