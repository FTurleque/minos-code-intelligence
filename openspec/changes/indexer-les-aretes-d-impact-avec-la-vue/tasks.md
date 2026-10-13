# Tasks

Ordre dans chaque section : rouge, correction, preuve. Prérequis : `ramener-le-poids-des-vues-a-la-mesure` (AUD-PERF-03) fusionné. Tout `-pl` s'accompagne de `-am`.

## 1. Relevé (AUD-PERF-11, AUD-PERF-13)

- [ ] 1.1 [AUD-PERF-11, AUD-PERF-13] Relire les littéraux de `check-mnd.py:101-102`, `check-mne.py:106-107`, `check-runtime-dynamic-consistency.py:81-86` ; figer, avant modification, les rapports d'impact et d'impact avancé des fixtures (référence de comparaison). Consigner dans `design.md`.

## 2. Recherche par identifiant (AUD-PERF-13)

- [ ] 2.1 [AUD-PERF-13] Rouge : test de `RuntimeIntelligenceService` avec un faux store dont `loadActiveKnowledge` lève — rapport d'un symbole existant rendu ; identifiant inconnu : réponse inchangée. **Preuve du rouge : l'exception remonte au HEAD.**
- [ ] 2.2 [AUD-PERF-13] `RuntimeIntelligenceService` : vue de requête active et `findSymbolById` (D3). Preuve : tests verts ; `check-runtime-dynamic-consistency.py` et `check-mne.py` verts ; scope JaCoCo `m26-runtime-dynamic-intelligence`.

## 3. Index des arêtes entrantes (AUD-PERF-11)

- [ ] 3.1 [AUD-PERF-11] Rouge : compteur de constructions — deux `analyze` sur la même vue → 1 ; premières analyses simultanées ; rapports identiques à la référence de 1.1. **Preuve du rouge : 2 constructions au HEAD.**
- [ ] 3.2 [AUD-PERF-11] `InMemoryCodeKnowledgeStore` : accesseur des arêtes entrantes triées, construction unique et publication sûre (D1) ; `LocalProjectImpactQuery` passe à `loadActiveQueryView` ; `ImpactAnalysisService` utilise la vue. Étendre le test de poids des vues à l'index dérivé. Preuve : `./mvnw -B -ntp -pl minos-application -am verify` ; scope `persistence-cache-indexes` ; `check-mnd.py` ; `python scripts/architecture/check-module-boundaries.py`.

## 4. Adjacence du graphe de programme (AUD-PERF-11)

- [ ] 4.1 [AUD-PERF-11] Rouge : compteur de constructions d'adjacence — deux impacts avancés sur le même graphe en cache → 1 ; rapports identiques à la référence.
- [ ] 4.2 [AUD-PERF-11] Entrée de cache de `ProgramGraphService` : adjacence triée et poids associé (D2) ; `AdvancedImpactService` l'utilise. Preuve : tests verts ; scope `advanced-impact-security` ; `./mvnw -B -ntp -pl minos-app -am verify` (goldens).

## 5. Clôture

- [ ] 5.1 Mettre à jour `docs/audit/2026-10-10/SUIVI.md` (AUD-PERF-11, AUD-PERF-13 : statut, compteurs avant/après, écart sur l'ADR 0056).
- [ ] 5.2 `openspec validate --all --strict` ; docs à mettre à jour avant archivage : `docs/STATUS.md` (aucune autre attendue).
