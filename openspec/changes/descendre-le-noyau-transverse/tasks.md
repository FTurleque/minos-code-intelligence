# Tasks

Ordre dans chaque section : rouge, correction, preuve. Les tests vivent dans le même module que leur classe. Gates à rejouer à chaque tâche : `python scripts/architecture/check-module-boundaries.py` et son auto-test, `python scripts/quality/check-comment-language.py`, `python .claude/scripts/run_gates.py --fast`. Portée JaCoCo concernée : `domain-invariants`.

## 0. Préalable

- [x] 0.1 [AUD-ARC-01] Archiver `faire-consommer-le-diagnostic-de-l-hote` (PR #565, fusion `9b710cbe`) ; mettre à jour `SUIVI.md` et le plan du chantier. Preuve : `openspec validate --all --strict`.
- [ ] 0.2 [AUD-ARC-01] Décision Q1 du propriétaire (`MinosVersion`). Les compteurs et les tâches 2.2 et 4.1 se recalculent si elle vaut B.

## 1. Le message d'erreur public descend (AUD-ARC-01, lot 6)

- [ ] 1.1 [AUD-ARC-01] Rouge : le garde, avec `PublicErrorMessages` dans `minos-domain` mais la racine `diagnostics` toujours au moteur, échoue (A14). `minos-integration-git` sans `minos-domain` déclaré fait échouer `everyModuleOnlyUsesItsAllowedModules`.
- [ ] 1.2 [AUD-ARC-01] Déplacer `com.minos.diagnostics` (classe et deux tests) dans `minos-domain`, nom complet inchangé ; commentaires en anglais (D3). `PACKAGE_ROOTS_BY_MODULE` : `diagnostics` passe du moteur au domaine. Arête `minos-integration-git` → `minos-domain` (POM, `ALLOWED_DEPENDENCIES`, `ModuleArchitectureTest.ALLOWED`, arc42 § 5, `--write-doc`) (D2). Preuve : le garde, son auto-test et `mvnw -pl minos-domain,minos-integration-git -am test` sont verts.

## 2. Les types du registre descendent (AUD-ARC-01, lot 6)

- [ ] 2.1 [AUD-ARC-01] Rouge : `ProjectPathMappingTest` (domaine) ne compile pas tant que la classe est dans le moteur. Cas couverts : emplacement d'exécution par l'environnement puis par les propriétés, chemins Windows et POSIX, refus d'une valeur invalide.
- [ ] 2.2 [AUD-ARC-01] Déplacer `UnreadableRegistryException`, `DegradedEntry` et `ProjectPathMapping` dans `com.minos.domain.registry` (D1), avec leurs tests, et mettre à jour les imports de tous les modules ; commentaires en anglais. Preuve : la compilation de tout le réacteur, puis les tests des modules touchés.

## 3. Cliquets et justifications (AUD-ARC-01, lot 6)

- [ ] 3.1 [AUD-ARC-01] A11 dans le garde : 41 → 33. A11 en bytecode : 36 → 28. Une raison écrite dans la table pour `registry.ProjectPathMappings` (port d'E/S) et `runtime.MinosVersion` (ADR 0068 § 3). Mutation sur copie : réimporter `com.minos.registry.DegradedEntry`, l'ancien nom, dans la CLI échoue à la compilation, et un nouvel import d'un type du moteur échoue en A11. Preuve : le garde, son auto-test et `ModuleArchitectureTest` sont verts.
- [ ] 3.2 [AUD-ARC-01] Cliquet de langue : maximums du moteur et du domaine recalculés. Base SpotBugs : l'entrée de `UnreadableRegistryException` passe dans `minos-domain.xml` (profil `audit-spotbugs`, 0 nouvelle et 0 disparue).

## 4. Clôture

- [ ] 4.1 `A2SurfaceCharacterizationTest` (goldens inchangés) ; `check-jacoco.py` (`domain-invariants`) ; ADR 0058 (mise en œuvre, lot 6) et ADR 0068 (mise en œuvre : racine et arête) ; `SUIVI.md` (compteurs) ; plan du chantier. Rejouer `run_gates.py`, `check-current-docs.py`, `product-facts.py --check`, `openspec validate --all --strict` et `archgate check`.
- [ ] 4.2 `mvnw verify` complet sous Windows ; Linux par la CI.
