# Tasks

Ordre dans chaque section : rouge, correction, preuve. Tout `-pl` s'accompagne de `-am`. Ce changement s'implémente **après** `indexer-les-aretes-d-impact-avec-la-vue` (même classe).

## 1. Relevé (AUD-PERF-10)

- [ ] 1.1 [AUD-PERF-10] Relever les tests et goldens qui encodent l'ordre de `findSymbols` ou le rang 6 (`InMemoryCodeKnowledgeStoreIndexTest`, tests de `find-symbols` de `minos-cli`, de `minos_find_symbols` de `minos-mcp`, goldens de `minos-app/src/test/resources/characterization/`). Consigner dans `design.md` ce qui est déjà figé et ce qui ne l'est pas.

## 2. Un rang par candidat (AUD-PERF-10)

- [ ] 2.1 [AUD-PERF-10] Rouge : dans `minos-engine` (package `com.minos.store`), test de caractérisation — jeu de symboles couvrant les rangs 0 à 6 et des ex æquo, liste attendue capturée au HEAD (vert avant et après) ; test de comptage par une fonction de rang injectée (D4) : au plus un appel par candidat, zéro sans texte, refus d'un critère nul. **Preuve du rouge : le test de comptage échoue au HEAD (le tri rappelle le rang à chaque comparaison).**
- [ ] 2.2 [AUD-PERF-10] `InMemoryCodeKnowledgeStore.findSymbols` : paire rang/symbole calculée après les filtres bon marché, chaque champ mis en minuscules au plus une fois par calcul (D1) ; constructeur de visibilité package pour la fonction de rang (D4). Preuve : tests de 2.1 verts ; `./mvnw -B -ntp -pl minos-engine -am test` ; `./mvnw -B -ntp -pl minos-storage-local -am test` (tests existants de la classe) ; scope JaCoCo `persistence-cache-indexes` (`scripts/quality/check-jacoco.py`) ; `python scripts/ci/test_plugin_gate.py` ; `python .claude/scripts/run_gates.py --fast`.
- [ ] 2.3 [AUD-PERF-10] Preuve de surface : `./mvnw -B -ntp -pl minos-app -am verify` (goldens de caractérisation inchangés).

## 3. Mesure et décisions (AUD-PERF-10)

- [ ] 3.1 [AUD-PERF-10] Mesurer sur ce dépôt la durée d'une recherche courte (`minos find-symbols`) avant 2.2, après 2.2, et avec un précalcul des minuscules sur une branche jetable ; mesurer le tas retenu par la vue avec et sans précalcul (méthode de `HybridCorpusWeightTest`). Consigner les chiffres dans `design.md` et dans `SUIVI.md`.
- [ ] 3.2 [AUD-PERF-10] **Décision à obtenir** : D2 (précalcul dans la vue : non recommandé sans mesure favorable ; s'il est retenu, il fait l'objet d'un changement après `ramener-le-poids-des-vues-a-la-mesure`, avec `python scripts/remediation/check-mne.py` rejoué). Consigner dans `design.md`.
- [ ] 3.3 [AUD-PERF-10] **Décision à obtenir** : D3 (rang 6 conservé, recommandé, ou retiré dans un changement propre avec note de version). Consigner dans `design.md` ; ne rien retirer ici.

## 4. Clôture

- [ ] 4.1 Mettre à jour `docs/audit/2026-10-10/SUIVI.md` (AUD-PERF-10 : statut, mesures 3.1, décisions D2 et D3, écart sur le rang 6).
- [ ] 4.2 `openspec validate --all --strict` ; docs à mettre à jour avant archivage : `docs/STATUS.md` seulement si une mesure y est citée ; aucune page utilisateur (aucune sortie ne change).
