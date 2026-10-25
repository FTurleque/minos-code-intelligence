# Proposal

## Why

Les règles A11, A12 et A13 de `scripts/architecture/check-module-boundaries.py` figent la dette d'architecture des constats AUD-ARC-01, AUD-ARC-05 et AUD-ARC-08 de l'audit du 10 octobre 2026. La revérification du 24 octobre (`docs/project/audits/2026-10-10/ETAT-2026-10-13.md`, § « Suite au 24 octobre ») montre que ces cliquets fuient :

- **A11 et A13 comptent des types, pas des usages.** Un nouveau fichier qui importe un type déjà listé, par exemple `io.PrivateLocalStorage` dans une nouvelle commande de la CLI, passe sans alerte.
- **A12 compte des classes, pas des sites de lancement.** `PrivateLocalStorage` lance déjà `icacls` à deux endroits ; un troisième passerait.
- **A11 ne voit que les noms.** Une surface qui appelle un accesseur de `MinosApplication` sans nommer le type qu'il rend (`app.snapshotStore()` passé à un constructeur) n'est pas comptée. Le design du lot 1 (`openspec/changes/archive/2026-10-24-faire-consommer-l-indexation-aux-surfaces/design.md`, « Usages sans nom ») avait retenu cette extension pour le lot 4 ; elle se fait ici, avant que les lots 2 à 6 ne vident la table.
- **A11 n'existe que dans le garde Python.** Les appels chaînés sur un objet du moteur (`service.tenant(id).retentionPolicy()`) ne nomment aucun type et échappent à une lecture des sources.

Les phases suivantes du chantier (SH-08, lots 2 à 6 d'AUD-ARC-01, SH-02, SH-03, SH-06) font baisser ces compteurs. Un compteur qui ne voit pas tout peut baisser par un `var` ou un chaînage, sans correction. Il faut donc d'abord rendre les cliquets étanches.

## What Changes

- **A11 et A13** : chaque entrée porte le nombre de fichiers de production qui utilisent le type. Un fichier de plus fait échouer le garde, et un fichier de moins aussi, tant que le compteur n'est pas abaissé.
- **A11, usages sans nom** : un appel à un accesseur public de `MinosApplication` qui rend un type de `minos-engine` compte comme un usage de ce type. Cela vaut pour un appel de même arité ou une référence de méthode. La table des accesseurs est lue dans la source de `MinosApplication` et n'est jamais recopiée à la main.
- **A12** : chaque classe listée porte son nombre de sites de lancement (`new ProcessBuilder`, `ProcessBuilder::new`, `ProcessBuilder.startPipeline`, `Runtime.getRuntime().exec`). Un site de plus ou de moins fait échouer le garde.
- **A11 en bytecode** : un test ArchUnit de `ModuleArchitectureTest` tient, par surface, les types du moteur dont dépend le bytecode et leur nombre de classes. Il voit les appels chaînés que le garde Python ne voit pas. Le garde Python reste la table de référence des noms (D4).
- Auto-tests par fixtures pour chaque nouvelle règle : un cas refusé, un cas accepté et une entrée périmée qui doit faire échouer le garde.
- Les compteurs de départ des phases 2 à 4 sont écrits dans `docs/project/audits/2026-10-10/SUIVI.md`.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `frontieres-de-modules` : trois exigences modifiées (A11, A12, A13) et deux ajoutées (usages sans nom d'A11, vue bytecode d'A11).

## Hors périmètre

- **Réduire une liste.** Aucun code de production n'est modifié. Les lots qui retirent des entrées sont les phases 2 à 4 du chantier (SH-08, lots 2 à 6 d'AUD-ARC-01, SH-02, SH-03, SH-06).
- **A14 (racines de packages, AUD-ARC-12)** : inchangée. Le constat est clos par décision (ADR 0068, D4 du 2026-10-22).
- **Les accesseurs d'autres types que `MinosApplication`**, par exemple `MinosApplicationComposer` ou les services applicatifs qui rendent un type du moteur. Le garde Python ne les voit pas ; la vue bytecode voit ceux dont la surface appelle une méthode.
- **A13 en bytecode** : aucune vue ArchUnit n'est ajoutée pour A13. Les couplages entre adaptateurs passent par des noms : la phase 4 les retire par déplacement de code, et le garde Python les voit tous (D4).

## Impact

- **Modules du reactor** : aucun code de production. Les fichiers touchés sont `scripts/architecture/check-module-boundaries.py`, `scripts/architecture/test_check_module_boundaries.py` et `minos-app/src/test/java/com/minos/app/architecture/ModuleArchitectureTest.java` (code de test).
- **Surfaces publiques** : aucune (CLI, API Java, MCP, IntelliJ et NEXUS inchangés ; aucun golden touché).
- **ADR** : aucun nouvel ADR. La section « Compliance and Enforcement » de l'ADR 0068 nomme les nouveaux grains et le test ArchUnit, sans changer la décision.
- **Gates à rejouer** : `python scripts/architecture/check-module-boundaries.py`, `python scripts/architecture/test_check_module_boundaries.py`, `python scripts/quality/check-ci-wiring.py`, `python scripts/quality/check-single-execution.py`, `python scripts/docs/check-current-docs.py`, `python .claude/scripts/run_gates.py --fast`, `openspec validate --all --strict`, ainsi que `ModuleArchitectureTest` (job `Verify`, Linux et Windows).
- **Changement actif recouvrant** : `decouper-les-classes-de-plus-de-800-lignes` remplace l'entrée A12 par `minos-engine/io.WindowsDacl`. Avec ce changement, l'entrée devient `{"minos-engine/io.WindowsDacl": 2}`.
- **Constats concernés** : AUD-ARC-01, -05 et -08. Leur dette reste figée et n'est pas résorbée ici.
