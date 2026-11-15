# Tasks

Constat **AUD-ARC-07**. Dernier changement du sprint 8 : prérequis `reclasser-minos-nexus-en-module-de-contrat` (AUD-ARC-08) et les changements du sprint 7 (AUD-ARC-01, AUD-ARC-02) fusionnés, puisque la prose se réécrit sur le découpage final. `docs/architecture/diagrams/module-dependencies.md` est généré : il ne se modifie que par `--write-doc`.

## 1. Portée des dépendances

- [ ] 1.1 [AUD-ARC-07] Relire la vue des blocs et le garde sur le `main` du moment ; corriger dans `design.md` tout numéro de ligne qui a bougé. Chercher les lecteurs de `docs/architecture/diagrams/module-dependencies.md` et de la forme des lignes « Dépendances » dans `scripts/`, `.claude/`, `.github/`, `docs/architecture/adr/*.rules.ts` (skill `minos-literal-gates`). Vérifier : relevé consigné dans `design.md`.
- [ ] 1.2 [AUD-ARC-07] Rouge : dans `scripts/architecture/test_check_module_boundaries.py`, cas « portée absente », « portée fausse », « portée écrite pour une dépendance de compilation », « double déclaration jar et test-jar », « parenthèse (voir …) après une dépendance de compilation » (acceptée, non lue comme portée) et cas accepté ; cas du rendu généré avec portée (frontieres-de-modules › La portée d'une dépendance hors compilation est écrite dans la documentation de dépendances). Vérifier : l'auto-test échoue sur ces cas, sortie notée ; le garde modifié en 1.3 échoue d'abord sur le dépôt réel (ligne de `minos-explorer`), sortie notée.
- [ ] 1.3 [AUD-ARC-07] Correction : le rendu du document reçoit les dépendances avec leur portée ; A9 exige la portée dans l'arc42 (design, « Contrats d'interface ») ; corriger les lignes « Dépendances » de `docs/architecture/arc42/05-vue-blocs.md` (`minos-explorer`, `minos-nexus`, `minos-provider-scip` dont « (tests seulement : … ) » devient « (test : … ) », surfaces → `minos-bootstrap`) ; dans la ligne de `minos-nexus` (`:99`), la portée `(test)` suit `minos-bootstrap` et le renvoi « (voir … ) » vient après ; régénérer par `python scripts/architecture/check-module-boundaries.py --write-doc`. Vérifier : garde au code 0 ; second `--write-doc` sans différence ; `archgate check`.

## 2. Sources et interfaces

- [ ] 2.1 [AUD-ARC-07] Rouge : cas d'auto-test « racine omise », « racine d'un autre module », « section sans ligne Sources », « répertoire de sources seul » (accepté), « port attribué au mauvais module » (le module réel nommé dans une autre phrase de la ligne ne suffit pas), « aucune pour un module qui déclare une interface publique », « type disparu », « section sans ligne Interfaces » (accepté) (frontieres-de-modules › Les racines de packages citées par la documentation d'un module sont les siennes ; › Les interfaces citées par la documentation d'un module existent et sont attribuées au bon module). Vérifier : rouge noté.
- [ ] 2.2 [AUD-ARC-07] Correction : étendre `check_architecture_documentation` (A9) aux lignes « Sources » (table `PACKAGE_ROOTS_BY_MODULE`) et « Interfaces » (types de production par module ; modules d'attribution pris dans la seule tournure « défini dans », design « Contrats d'interface »), messages avec la ligne attendue quand elle est calculable ; chaque fonction `check_*` nouvelle est référencée par un test. Vérifier : `python scripts/architecture/test_check_module_boundaries.py` ; le garde échoue sur le dépôt réel en nommant `minos-domain`, `minos-engine` et `minos-storage` (rouge du dépôt, noté).
- [ ] 2.3 [AUD-ARC-07] Corriger la prose de `docs/architecture/arc42/05-vue-blocs.md` : interfaces de `minos-domain` (`SemanticVectorStore`, `SemanticProjectionStore`) ; module de définition des ports dans la ligne de `minos-storage` ; quatorze racines de `minos-engine`, dont `com/minos/io/` (boîte à outils d'E/S privées) et `com/minos/remote/` ; racine `diagnostics` de `minos-domain` ; noms qui ne sont pas des types du reactor sortis des accents graves ; route de l'explorateur dans le diagramme 5.1 et la section `minos-app` (`ExplorerLaunchRouteProvider`). Vérifier : garde au code 0 ; `python scripts/docs/check-current-docs.py` ; `python scripts/docs/product-facts.py --check` ; liens relatifs.
- [ ] 2.4 [AUD-ARC-07] Documentation du garde : `docs/developer/quality-gates.md` (A9 : dépendances avec portée, sources, interfaces ; ce qui reste de la prose non gardée). Vérifier : `python scripts/docs/check-current-docs.py`.

## 3. Nombre de modules

- [ ] 3.1 [AUD-ARC-07] Rejouer les cas A7 de l'auto-test qui couvrent le scénario modifié (frontieres-de-modules › Les adaptateurs de stockage forment un seul module) ; corriger l'écart E2 de `docs/architecture/diagrams/capacites/frontieres-de-modules.md` (le reactor et `MODULES` comptent 14 modules ; le nombre se lit dans `MODULES`). Vérifier : `python scripts/architecture/test_check_module_boundaries.py`, `python scripts/docs/check-capability-dossiers.py`.

## 4. Clôture

- [ ] 4.1 [AUD-ARC-07] Mettre à jour la matrice de traçabilité du design en nommant les preuves manquantes.
- [ ] 4.2 [AUD-ARC-07] Mettre à jour `docs/architecture/diagrams/capacites/frontieres-de-modules.md` (trois exigences ajoutées, une modifiée) ; vérifier par `python scripts/docs/check-capability-dossiers.py`.
- [ ] 4.3 [AUD-ARC-07] `openspec validate --all --strict`, `python .claude/scripts/run_gates.py`, `archgate check`.
- [ ] 4.4 [AUD-ARC-07] Lister les documents à mettre à jour avant archivage (`docs/project/audits/2026-11-15/SUIVI.md`, `docs/architecture/SYNTHESE.md` et `docs/developer/architecture.md` s'ils reprennent un point corrigé) ; vérifier que chacun est fait ou nommé comme reste.

## Workflow follow-up

- Archiver le changement après revue (`openspec archive aligner-la-prose-d-architecture-sur-le-code -y`), puis vérifier que `openspec/specs/frontieres-de-modules/spec.md` garde sa section `## Documentation liée`.
