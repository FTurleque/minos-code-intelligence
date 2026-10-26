# Tasks

Ordre dans chaque section : rouge, correction, preuve. Les tests vivent dans le module de leur classe. Gates à rejouer à chaque tâche : `python scripts/architecture/check-module-boundaries.py` et `test_check_module_boundaries.py`, `python scripts/remediation/check-mne.py`, `python scripts/quality/check-comment-language.py`, `python .claude/scripts/run_gates.py --fast`. Portée JaCoCo concernée : `nexus-export`.

## 0. Préalable

- [ ] 0.1 [AUD-ARC-01] Décisions du propriétaire Q1 (constructeur), Q2 (arête) et Q3 (golden). Les tâches 1, 3.2, 4.1 et 5.1 se recalculent si l'une vaut B.

## 1. Caractériser l'export avant la refonte (AUD-ARC-01, lot 5)

- [ ] 1.1 [AUD-ARC-01] Ajouter à `A2SurfaceCharacterizationTest` le test de `minos nexus-export --root` sur la fixture TypeScript (enregistrement, identité figée, import SCIP), écrire `nexus-export.golden` une fois par `-Dminos.characterization.write=true` et le relire. Preuve : `A2SurfaceCharacterizationTest` 13/13 vert au HEAD sans propriété d'écriture ; golden indépendant de l'hôte (aucun chemin absolu, aucun identifiant aléatoire).

## 2. Le cas d'usage applicatif (AUD-ARC-01, lot 5)

- [ ] 2.1 [AUD-ARC-01] Rouge : `KnowledgeExportUseCaseTest`, `KnowledgeExportRefusalTest` et `KnowledgeExportBoundedSelectionTest` (`minos-application`, package `com.minos.application.export`) ne compilent pas. Cas : racine non enregistrée et racine fichier refusées par leur nom de feuille ; snapshot absent ; registre illisible expliqué ; symboles externes et sans emplacement omis ; relations non symboliques et non locales omises ; chemins directs et identifiants stables `file:` résolus ; identifiants non résolus ; troncature par nombre et par poids ; ordre des limitations.
- [ ] 2.2 [AUD-ARC-01] Créer `KnowledgeExportUseCase` (`of(MinosApplication)`, lecture par `loadActiveKnowledge`, D1), `KnowledgeExport`, `ExportedSymbol`, `ExportedRelationship` (D2) ; y porter la sélection, la résolution des chemins et `BoundedById` (D3). Javadoc en anglais, sans jalon. Preuve : `mvnw -pl minos-application -am test` vert ; A8, A14, A15, A18 verts.

## 3. L'adaptateur et le câblage (AUD-ARC-01, lot 5)

- [ ] 3.1 [AUD-ARC-01] Rouge : `NexusExportServiceTest` (`minos-nexus`, application réelle par `minos-bootstrap` en test) appelle `new NexusExportService(MinosApplication)` et ne compile pas. Cas : traduction d'un symbole et d'une relation (énumérations, repli du nom qualifié, origine, preuves), refus d'une racine non enregistrée par son nom.
- [ ] 3.2 [AUD-ARC-01] Réduire `NexusExportService` à la traduction de `KnowledgeExport` ; constructeur `(MinosApplication)` (Q1) ; constantes `MAX_*` renvoyant au cas d'usage. Retirer `NexusExportBoundedSelectionTest` et `NexusExportRefusalMessageTest` (couverts par la tâche 2). `MinosCliRunner` passe `application.get()`. `NexusExportIntegrationTest` construit le service sur `MinosApplication.open`. `check-mne.py` (MNE-12) lit le cas d'usage ; `check-jacoco.py` : préfixe `com/minos/application/export/` dans `nexus-export`. Preuve : `mvnw -pl minos-nexus,minos-cli -am test`, `RegistryToleranceCliTest`, `LazyWiringGuardTest`, `check-mne.py` verts ; golden de la tâche 1 inchangé.

## 4. Arête et cliquets (AUD-ARC-01, lot 5)

- [ ] 4.1 [AUD-ARC-01] Rouge : avec les 5 entrées NEXUS et les 2 entrées CLI retirées des tables, le garde du HEAD échoue (A11 : types encore utilisés) ; avec `minos-engine` retiré de `ALLOWED` pour `minos-nexus`, `everyModuleOnlyUsesItsAllowedModules` échoue au HEAD. Correction : retirer l'arête du POM, de `ALLOWED_DEPENDENCIES` et de `ModuleArchitectureTest.ALLOWED` (Q2) ; A11 garde 33 → 26 (API 15, CLI 9, MCP 2), bytecode 28 → 23 (API 13, CLI 8, MCP 2) ; `--write-doc`. Auto-test : un cas refuse le retour d'un type du moteur dans `minos-nexus`. Mutation sur copie : un import de `com.minos.store.CodeKnowledgeSnapshot` dans `minos-nexus` échoue en A11. Preuve : garde, auto-test, `ModuleArchitectureTest` 9/9.
- [ ] 4.2 [AUD-ARC-01] Cliquet de langue : maximum de jalons de `minos-nexus` recalculé. SpotBugs (profil `audit-spotbugs`, `minos-application` et `minos-nexus`) : 0 alerte nouvelle ; une alerte sur un record neuf se corrige dans le code.

## 5. Clôture

- [ ] 5.1 Documentation au présent : ADR 0058 (mise en œuvre, « Point 3, lot 5 résorbé », arête retirée) ; arc42 § 5 (`minos-nexus`) ; `docs/developer/architecture.md` ; `docs/user/java-api.md` § Ruptures (Q1) ; `SUIVI.md` (ligne du lot 5, compteurs) ; plan `docs/project/roadmap/storage-hexagonal-2026-10/README.md` (SH-09 : export NEXUS fait). Rejouer `run_gates.py`, `check-current-docs.py`, `product-facts.py --check`, `openspec validate --all --strict` et `archgate check`.
- [ ] 5.2 `mvnw verify` complet sous Windows (`A2SurfaceCharacterizationTest` 13/13, `ModuleArchitectureTest`) ; Linux et SonarCloud (couverture du nouveau code de `minos-application` et `minos-nexus` ≥ 80 %) par la CI de la PR. Liste des `docs/` à revoir avant archivage : `STATUS.md` si le décompte A11 y figure.
