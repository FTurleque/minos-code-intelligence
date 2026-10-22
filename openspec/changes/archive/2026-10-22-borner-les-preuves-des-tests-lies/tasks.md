# Tasks

Ordre : mesure, décision d'abandon ou de poursuite, puis rouge, correction, preuve. Tout `-pl` s'accompagne de `-am`. Les exigences de `specs/` décrivent D2-A ; si le propriétaire choisit B ou C, ou l'abandon, les réviser par `/opsx:update` (ou retirer le changement) avant d'implémenter.

## 1. Mesure et décision (AUD-PERF-09)

- [x] 1.1 [AUD-PERF-09] Mesurer sur le snapshot de ce dépôt (banc `SnapshotScalabilityBenchmark`, `benchmarks/scalability/README.md`, ou lecture directe du snapshot actif après `minos index`) : nombre de relations `RELATED_TEST`, nombre total de preuves, médiane, 95e centile et maximum par relation, taille encodée des preuves rapportée à la taille persistée. Consigner dans `design.md` ; requalifier la confiance du constat (« confirmé » ou « non reproduit »).
- [x] 1.2 [AUD-PERF-09] Établir si la confiance, l'ordre ou la sélection d'une relation dépendent du nombre de preuves (au-delà de `RelatedTestDerivationService.java:629`) ; relever quelles sorties publiques rendent la liste de preuves (CLI, API Java, MCP) et le maximum de preuves par relation dans les fixtures des trois goldens. Consigner dans `design.md`.
- [x] 1.3 [AUD-PERF-09] **Décision à obtenir** : D1 (seuil d'abandon ; proposition : moins de 2 % de la taille persistée et aucune relation au-delà de 100 preuves). **Si le seuil n'est pas atteint : passer directement à la section 4**, sans modifier de code. Consigner dans `design.md`.
- [x] 1.4 [AUD-PERF-09] **Décision à obtenir** (si poursuite) : D2 (A recommandé, B ou C) et la valeur de N. Consigner dans `design.md`.

## 2. Plafond des preuves (AUD-PERF-09)

- [x] 2.1 [AUD-PERF-09] Rouge : tests de `RelatedTestDerivationService` dans `minos-engine` — N + 3 emplacements : N preuves et une preuve de synthèse annonçant 3 ; exactement N : aucune synthèse ; occurrences fournies dans un autre ordre : mêmes preuves conservées ; corpus avec relations au-dessus du plafond : mêmes paires, confiances et rangs qu'avant. **Preuve du rouge : N + 3 preuves et aucune synthèse au HEAD.**
- [x] 2.2 [AUD-PERF-09] Appliquer le plafond dans la dérivation (D2, D3), sans toucher au codec. Preuve : tests verts ; `./mvnw -B -ntp -pl minos-engine -am verify` ; `python .claude/scripts/run_gates.py --fast` (aucun gate littéral ni scope JaCoCo ne cite cette classe au HEAD : le revérifier par une recherche dans `scripts/`) ; la couverture du code neuf est jugée par SonarCloud dans `minos-engine`.

## 3. Sorties et compatibilité (AUD-PERF-09)

- [x] 3.1 [AUD-PERF-09] Rouge : test de relecture d'un snapshot écrit par le code du HEAD (fixture) dont une relation dépasse N — décodé, toutes les preuves rendues. Preuve : `./mvnw -B -ntp -pl minos-storage-local -am verify`.
- [x] 3.2 [AUD-PERF-09] `./mvnw -B -ntp -pl minos-app -am verify` : les trois goldens sont identiques à l'octet ; si l'un change, le montrer dans la décision D2 avant de le régénérer.
- [x] 3.3 [AUD-PERF-09] Vérifier que le plugin IntelliJ et l'export NEXUS tolèrent une preuve sans emplacement (`minos-intellij/gradlew test`, tests de `minos-nexus`) ; documenter la limite dans `docs/user/mcp.md` et `docs/user/cli.md`. Rejouer `python scripts/docs/check-current-docs.py`. **Fait (2026-10-22)** : le plugin ne lit les preuves qu'en les comptant (`ArchitectureGraphPanel`, `array(edge, "evidence").size()`) ; l'action « tests liés » rend la réponse sans lire les emplacements. Nouveau test `ArchitectureGraphPanelTest.countsAnEvidenceWithoutLocationLikeAnyOther` vert (`gradlew test`, tests UI et protocole JSON, Gradle sous JDK 25 : sous le JDK 21 Microsoft du poste, `:instrumentCode` cherche un dossier `Packages` absent). `minos-nexus` : 10 tests verts sur `develop` après fusion. Doc `cli.md` / `mcp.md` faite à la tâche 2.2 ; `check-current-docs.py` vert.
- [x] 3.4 [AUD-PERF-09] Refaire la mesure de 1.1 après réindexation ; consigner avant / après.

## 4. Clôture

- [x] 4.1 Mettre à jour `docs/project/audits/2026-10-10/SUIVI.md` (AUD-PERF-09 : confiance requalifiée, mesure 1.1, décisions D1 et D2, issue « abandon » ou « borné » avec les mesures 3.4).
- [x] 4.2 `python .claude/scripts/run_gates.py` ; `openspec validate --all --strict` ; docs à mettre à jour avant archivage : `docs/project/governance/STATUS.md`, `docs/user/mcp.md`, `docs/user/cli.md` (seulement si le plafond est appliqué). **Fait (2026-10-22)** : `run_gates.py` 46/46, `openspec validate --all --strict`, `check-current-docs.py` ; STATUS, ROADMAP et SUIVI mis à jour avec l'archivage.
