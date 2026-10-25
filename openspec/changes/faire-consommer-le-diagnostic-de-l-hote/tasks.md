# Tasks

Ordre dans chaque section : rouge, correction, preuve. Les tests vivent dans la même tâche que le code. Gates à rejouer : `python scripts/architecture/check-module-boundaries.py` et son auto-test, `python .claude/scripts/run_gates.py --fast`. Portée JaCoCo concernée : `global`.

## 0. Préalable

- [x] 0.1 [SH-08] Archiver `extraire-le-port-d-acl-de-plateforme` (PR #564, fusion `47c02b89`) ; mettre à jour `SUIVI.md` (AUD-ARC-05 fermable) et le plan du chantier (SH-08). Preuve : `openspec validate --all --strict`.

## 1. Service applicatif (AUD-ARC-01, lot 2)

- [x] 1.1 [AUD-ARC-01] Rouge (`minos-application`) : `HostDiagnosticsServiceTest`, avec une sonde factice et un localisateur factice. Cas couverts : sélection disponible et refusée par décision, prérequis manquant, commande trouvée et absente, niveaux de confidentialité sur de vrais dossiers temporaires (`ENFORCED`, `ABSENT`) et `UNKNOWN` sur une erreur de lecture, ordre des dix libellés. **Preuve du rouge : le service n'existe pas.**
- [x] 1.2 [AUD-ARC-01] `HostDiagnosticsService` (`defaults()` et constructeur à sonde et localisateur, D1) et le record `WorkerSandboxDiagnosis` (D2), dans `com.minos.application`. Preuve : tests 1.1 verts ; `check-module-boundaries.py` vert (A8, A14, A15).

## 2. La commande consomme le service (AUD-ARC-01, lot 2)

- [x] 2.1 [AUD-ARC-01] `DoctorCommand` passe par `HostDiagnosticsService.defaults()`. La vue `WorkerSandboxReport` est projetée depuis `WorkerSandboxDiagnosis`, et la liste des emplacements passe dans le service (D3). Les imports de `PrivateLocalStorage`, `WorkerSandboxProbe` et `MinosApplicationComposers` disparaissent. Preuve : `DoctorCommandTest` et `ProviderVerdictConsistencyTest` verts sans attente modifiée.
- [x] 2.2 [AUD-ARC-01] Cliquets : `ENGINE_TYPES_NAMED_BY_SURFACE` de la CLI perd `io.PrivateLocalStorage` et `runtime.WorkerSandboxProbe` (43 → 41). `ENGINE_TYPES_USED_BY_SURFACE_BYTECODE` de la CLI perd ces deux types et `runtime.HostCommandLocator` (39 → 36). Le cas de `test_check_module_boundaries.py` qui lit `io.PrivateLocalStorage` dans la liste réelle prend un autre type listé. Mutation sur copie : réimporter `PrivateLocalStorage` dans `DoctorCommand` fait échouer A11. Preuve : le garde, son auto-test et `ModuleArchitectureTest` sont verts.

## 3. Sortie inchangée

- [x] 3.1 [AUD-ARC-01] `mvnw -pl minos-app -am test "-Dtest=A2SurfaceCharacterizationTest,ModuleArchitectureTest" "-Dsurefire.failIfNoSpecifiedTests=false"` vert : `cli-doctor.golden` inchangé, et aucun golden régénéré.

## 4. Clôture

- [x] 4.1 ADR 0058 (mise en œuvre, lot 2) ; `SUIVI.md` (compteurs A11 avant et après) ; plan du chantier. Rejouer `python .claude/scripts/run_gates.py`, `check-current-docs.py`, `product-facts.py --check`, `openspec validate --all --strict` et `archgate check`.
- [x] 4.2 SpotBugs (profil `audit-spotbugs`) sur `minos-application` et `minos-cli` : 0 nouvelle alerte. `mvnw verify` complet sous Windows ; Linux par la CI.
