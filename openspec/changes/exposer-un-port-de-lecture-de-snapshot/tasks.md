# Tasks

Ordre dans chaque section : rouge, correction, preuve. Tout `-pl` s'accompagne de `-am` ; Maven par le wrapper, avec `JAVA_HOME` sur un JDK 25. Chaque tâche porte AUD-ARC-07 (SH-04, ADR 0056).

## 0. Décisions

- [ ] 0.1 **(manuelle)** [AUD-ARC-07] Obtenir du propriétaire D1 à D6 (`design.md`, « Décisions en attente du propriétaire ») et les consigner dans `design.md`. Aucune tâche suivante ne commence avant.
- [ ] 0.2 [AUD-ARC-07] Relire au HEAD de la branche d'implémentation les sites de `design.md` (« État vérifié au HEAD ») et corriger les numéros de ligne qui ont bougé.

## 1. Port de lecture et vue neutre dans le cœur (`minos-engine`)

- [ ] 1.1 [AUD-ARC-07] Rouge : dans `minos-engine/src/test/java/com/minos/store`, tests écrits contre les types à créer (donc en échec de compilation) : un lecteur non mémoire de test sert les sept lectures ; `SnapshotReadView` refuse les composants nuls et une durée négative ; l'index des arêtes entrantes est construit au plus une fois par vue, y compris sous lecteurs concurrents ; un faux store qui ne redéfinit que `loadActiveQueryView` sert `loadActiveReadView` ; `SnapshotQueryView.of` réutilise l'index mémoire et en construit un pour un lecteur non mémoire, sans boucle.
- [ ] 1.2 [AUD-ARC-07] Créer `CodeKnowledgeReader`, `IncomingRelationships`, `SnapshotReadView` (avec sa fabrique indexée et la statique neutre de poids) ; `CodeKnowledgeStore extends CodeKnowledgeReader` ; `SymbolEdgeIndex implements IncomingRelationships` ; `CodeKnowledgeSnapshotStore.loadActiveReadView` en `default` ; `SnapshotQueryView.readView()` et `of(...)` ; dépréciations `@Deprecated(since = "1.3.0")` de `SnapshotQueryView`, `queryStore()`, `indexMetrics()`, `loadActiveQueryView`, avec Javadoc qui nomme le remplaçant. Selon D4, élargir les paramètres de `SymbolQueryService`, `RelationshipQueryService`, `ModuleFilterGuard.require` à `CodeKnowledgeReader`.
- [ ] 1.3 [AUD-ARC-07] Preuve : tests de 1.1 verts ; `RelationshipQueryServiceTest`, `SymbolEdgeIndexTest`, `CodeKnowledgeSnapshotStoreActiveIdFallbackTest` verts ; `./mvnw -B -ntp -pl minos-engine -am test`. Rapport JaCoCo de `minos-engine` : couverture des lignes neuves du cœur ≥ 80 % **par les seuls tests de `minos-engine`** (la classe mémoire est surtout testée ailleurs).

## 2. Stores de snapshots

- [ ] 2.1 [AUD-ARC-07] Rouge : dans `minos-storage-local`, test à compteurs (`cacheStats`) — vue neutre puis ancienne vue sur un même snapshot actif : 1 construction de vue, au plus 1 index des arêtes, rapports d'impact égaux ; promotion par une autre instance pendant la construction de la vue neutre (sur le modèle de `FileSymbolSnapshotStoreRetryTest`). Le premier cas échoue au HEAD faute de `loadActiveReadView` redéfinie (la dérivation par défaut passe par l'ancienne vue).
- [ ] 2.2 [AUD-ARC-07] `FileSymbolSnapshotStore` et `PostgresCodeKnowledgeSnapshotStore` : cache de `SnapshotReadView` obtenue par la fabrique du cœur, `loadActiveReadView` redéfinie, `loadActiveQueryView` par `SnapshotQueryView.of`, poids par la statique neutre ; aucun nom de `InMemoryCodeKnowledgeStore`. Conserver tels quels les littéraux de `check-mne.py`, `check-mnd.py`, `check-p0-p2.py` ; ajouter `return loadActiveReadView(projectId);` au `forbid` de `check-mne.py:70-71` (relance récursive du store PostgreSQL). Migrer `QueryViewWeightTest.java:58` vers la vue neutre ; tests PostgreSQL à compteurs équivalents dans `PostgresCodeKnowledgeSnapshotStoreCacheTest`.
- [ ] 2.3 [AUD-ARC-07] Preuve : `./mvnw -B -ntp -pl minos-storage-local,minos-storage-postgresql -am test` ; tests PostgreSQL exécutés avec Docker et `"-Dminos.postgresql.tests.required=true"` (sinon le dire : un test sauté n'est pas un succès) ; `python scripts/remediation/check-mne.py`, `check-mnd.py`, `check-p0-p2.py` verts ; `python scripts/quality/check-jacoco.py` sur le scope `persistence-cache-indexes`.

## 3. Cas d'usage de lecture (`minos-application`)

- [ ] 3.1 [AUD-ARC-07] Rouge : dans `minos-application`, tests avec un store dont la vue neutre porte un lecteur non mémoire, passant par `ProjectQueryService` (symboles, fichier, usages, relations, recherche de code), `LocalProjectImpactQuery` et `RuntimeIntelligenceService` (rapport d'un symbole) ; réponses comparées à celles de l'index mémoire sur le même snapshot ; cas sans snapshot actif. Rouge au HEAD : ces services lisent `loadActiveQueryView().queryStore()`.
- [ ] 3.2 [AUD-ARC-07] Migrer `ProjectQueryService`, `ImpactAnalysisService` (`analyze(SnapshotReadView, …)` ajouté, l'ancienne surcharge `analyze(SnapshotQueryView, …)` dépréciée et déléguant), `LocalProjectImpactQuery`, `RuntimeIntelligenceService` ; selon D4, `CodeSearchService` reçoit `CodeKnowledgeReader`. Migrer `ImpactIndexReuseTest` (compteur par la vue neutre ; un cas garde l'ancien contrat), `ProgramGraphSourceFixture`, `RuntimeIntelligenceServiceTest`, `SnapshotScalabilityBenchmark` (`minos-bootstrap`).
- [ ] 3.3 [AUD-ARC-07] Preuve : `./mvnw -B -ntp -pl minos-application,minos-bootstrap,minos-app -am test` ; `ImpactReferenceReportsTest`, `ProjectQueryServiceRelationshipLimitationsTest`, `LocalProjectImpactQueryTest`, goldens de caractérisation verts sans fichier de référence modifié ; `python scripts/quality/check-runtime-dynamic-consistency.py` et `python scripts/remediation/check-mne.py` verts ; couverture des lignes modifiées de `minos-application` par les tests de ce module.

## 4. Cliquet de frontière

- [ ] 4.1 [AUD-ARC-07] Rouge : auto-tests dans `scripts/architecture/test_check_module_boundaries.py` sur arbres temporaires — import de `InMemoryCodeKnowledgeStore` hors du cœur refusé, type imbriqué refusé, nom qualifié refusé, `SnapshotQueryView` hors de la liste refusé, liste nommant un fichier absent refusée, arbre conforme accepté.
- [ ] 4.2 [AUD-ARC-07] Ajouter la règle A17 (D5) à `scripts/architecture/check-module-boundaries.py` avec sa liste fermée (les deux stores et `minos-application/com.minos.impact.ImpactAnalysisService`), son message actionnable et sa ligne de résumé.
- [ ] 4.3 [AUD-ARC-07] Preuve : `python scripts/architecture/check-module-boundaries.py` et `python scripts/architecture/test_check_module_boundaries.py` verts ; rouge prouvé sur une copie mutée (code 1) ; règle A15 verte pour les trois nouveaux types publics ; `python scripts/quality/check-ci-wiring.py` vert ; `python .claude/scripts/run_gates.py --fast` vert.

## 5. Compatibilité et qualité

- [ ] 5.1 [AUD-ARC-07] Consommateur compilé avant/après (ADR 0056) : compiler `scripts/m15/M15FinalQueryProbe.java` contre les classes du HEAD de départ puis contre celles de la branche (`javac -Xlint:deprecation`) ; seules des alertes de dépréciation sont admises. Consigner les commandes et sorties dans la PR.
- [ ] 5.2 [AUD-ARC-07] `check-jacoco.py` : ajouter `com/minos/store/SnapshotReadView` et `com/minos/store/CodeKnowledgeReader` au scope `persistence-cache-indexes` (seuils inchangés) ; rejouer `python scripts/quality/check-jacoco.py` sur les rapports des deux systèmes ou dire lequel manque.
- [ ] 5.3 [AUD-ARC-07] SpotBugs : rejouer `python scripts/quality/spotbugs-cli.py --output target/spotbugs-cli` puis `python scripts/quality/audit-report-summary.py spotbugs --reports-dir target/spotbugs-cli --strict` ; toute alerte nouvelle (attendues : `EI_EXPOSE_REP` sur l'accesseur du lecteur et `EI_EXPOSE_REP2` sur le constructeur de la vue) est corrigée ou ajoutée à `quality/spotbugs-baseline/minos-engine.xml` avec justification dans la PR.
- [ ] 5.4 [AUD-ARC-07] `scripts/m15/run-final.ps1` (exige `loadActiveQueryView(project.id())` dans `ProjectQueryService`, chemins déjà absents au HEAD) : appliquer D6 et, s'il est déplacé, mettre à jour `.github/workflows/historical-qualification.yml:147` ; rejouer `python scripts/quality/check-ci-wiring.py`.
- [ ] 5.5 [AUD-ARC-07] `./mvnw -B -ntp verify` complet vert sous Windows ; Linux par la CI de la PR (le scope JaCoCo `m24-polyglot-provider-platform` rouge sur poste Windows est hors régression : comparer à `develop`).

## 6. Clôture

- [ ] 6.1 Note de mise en œuvre dans l'ADR 0056 (noms retenus, migration additive, D3, coût de l'index inchangé) et statut mis à jour dans `docs/architecture/adr/README.md` et `docs/architecture/arc42/09-decisions.md` ; `arc42/05-vue-blocs.md`, `08-concepts-transverses.md`, `12-glossaire.md` mentionnent la vue neutre ; selon D4, `docs/user/java-api.md` § Ruptures ; `python scripts/docs/check-current-docs.py` et `python scripts/docs/product-facts.py --check` verts.
- [ ] 6.2 `docs/project/roadmap/storage-hexagonal-2026-10/TASKS.md` (SH-04 : état, cases cochées) et `README.md` (suivi) ; ligne AUD-ARC-07 de `docs/project/audits/2026-10-10/SUIVI.md` mise à jour avec les preuves.
- [ ] 6.3 `openspec validate --all --strict` ; docs à mettre à jour avant archivage : `docs/project/governance/STATUS.md` (port de lecture livré, aucune capacité nouvelle), ADR 0056 ; aucune page utilisateur hors `java-api.md` (aucune sortie ne change).
