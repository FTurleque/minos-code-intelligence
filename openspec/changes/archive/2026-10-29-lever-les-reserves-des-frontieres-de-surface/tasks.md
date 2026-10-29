# Tasks

Ordre dans chaque section : rouge, correction, preuve. Les tests vivent dans le module de leur classe. Gates à rejouer à chaque tâche : `python scripts/architecture/check-module-boundaries.py` et `python -m unittest scripts/architecture/test_check_module_boundaries.py`, `python scripts/quality/check-hosted-control-plane-consistency.py`, `python .claude/scripts/run_gates.py --fast`. Portée JaCoCo concernée : `m29-docker-runtime-bootstrap` (inchangée).

## 1. Façade de la correspondance Docker (AUD-ARC-01, réserve 1)

- [x] 1.1 [AUD-ARC-01] Rouge : `ProjectPathMappingServiceTest` (`minos-application`, package `com.minos.application`) ne compile pas. Cas : création d'une correspondance absente (`CREATED`, écrite), même correspondance (`UNCHANGED`, rien d'écrit), autre correspondance (`CONFLICT`, rien d'écrit), `null` refusé, `forHome` sans racine de composition échoue avec le message de `MinosApplicationComposers`.
- [x] 1.2 [AUD-ARC-01] Créer `ProjectPathMappingService` (D1) ; `DockerRuntimeBootstrap` l'appelle après validation des arguments et ne nomme plus le port. Javadoc en anglais. Preuve : `mvnw -pl minos-application,minos-cli -am test` vert, `DockerRuntimeBootstrapTest` 3/3 inchangé.
- [x] 1.3 [AUD-ARC-01] Rouge : avec `registry.ProjectPathMappings` retiré de la liste CLI d'`ENGINE_TYPES_NAMED_BY_SURFACE` et de `ENGINE_TYPES_USED_BY_SURFACE_BYTECODE`, le garde du HEAD échoue (type encore utilisé). Correction : la tâche 1.2 le fait passer ; garde 9 → 8 types (CLI 4), bytecode 6 → 5 (CLI 1). Preuve : garde vert, `ModuleArchitectureTest` vert.

## 2. Accesseurs de `MinosApplication` (AUD-ARC-01, réserve 2)

- [x] 2.1 [AUD-ARC-01] Rouge : auto-tests de `check_minos_application_accessors` sur arbre temporaire (accesseur hors table refusé avec son nom et son type ; type rendu différent refusé ; entrée sans accesseur refusée ; table exacte acceptée) et `main` appelle la règle ; avec la table réduite à 15 entrées, le garde du HEAD échoue (4 accesseurs publics hors table).
- [x] 2.2 [AUD-ARC-01] Rendre `gitIntelligence`, `providerCatalog`, `scipArtifactImporter`, `hostedControlPlaneService` privés au package (D2) ; adapter `MinosApplicationTest`, `SnapshotScalabilityBenchmark`, `ProviderCatalogPortTest`, `MinosApplicationMcpBackendM27Test`, `LazyApplicationTest` et l'auto-test `test_the_real_application_exposes_the_accessors_the_use_cases_call` ; refuser aussi l'accès d'une surface à la racine de composition. Preuve : garde et auto-tests verts ; `check-hosted-control-plane-consistency.py` vert ; `mvnw -pl minos-bootstrap,minos-mcp,minos-app -am test` (classes adaptées) vert.
- [x] 2.3 [AUD-ARC-01] Mutation sur l'arbre réel, restauré : un accesseur public `git()` ajouté à `MinosApplication`, un import du port dans `minos-cli` et un appel à `MinosApplicationComposers.resolve()` dans `minos-cli` font chacun échouer A11.

## 3. Portée de la dépendance vers le moteur (AUD-ARC-01, réserve 3)

- [x] 3.1 [AUD-ARC-01] Aucune modification de POM (D3). Preuve : raison écrite dans la PR.

## 4. Clôture

- [x] 4.1 Documentation au présent : ADR 0058 (mise en œuvre, réserves levées, comptes) ; ADR 0042 § 8.2 ; `docs/user/java-api.md` § Ruptures ; `docs/developer/public-surfaces.md` (diagramme de `MinosApplication`) ; `docs/developer/quality-gates.md` (A11, table des accesseurs). Le suivi d'audit (`SUIVI.md`, `findings.json`) est mis à jour par la session de clôture du sprint 10. Rejouer `run_gates.py --fast`, `check-current-docs.py`, `product-facts.py --check`, `openspec validate --all --strict`.
- [x] 4.2 Relecture par l'agent `minos-architecture-reviewer` (faite : réserves corrigées, contournement par la racine de composition fermé) ; CI de la PR (Linux, Windows, SonarCloud ≥ 80 % sur le nouveau code de `minos-application`). Docs à revoir avant archivage : `STATUS.md` si le décompte A11 y figure. — PR #601 fusionnée (`5105c188`) : Linux, Windows et SonarCloud verts, run https://github.com/FTurleque/minos-code-intelligence/actions/runs/40797345140 ; `STATUS.md` ne porte pas de décompte A11.
