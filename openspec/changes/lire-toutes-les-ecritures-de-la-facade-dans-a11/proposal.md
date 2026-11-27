# Proposal

## Why

Constat **AUD-ARC-17** de l'audit `docs/project/audits/2026-11-15/`, suite du changement `lire-les-methodes-generiques-de-la-facade-dans-a11`, dont le design nomme les « formes que la règle ne lit pas ». La règle A11 refuse qu'une signature publique de `MinosApplication` nomme un type de `minos-engine`. Elle lit la source par expressions régulières, qui supposent une écriture précise. Cinq écritures Java légales passent le garde au commit `54f23fa37` :

1. une annotation entre `public` et le type de retour (`public @Deprecated ProjectRegistry x()`) ;
2. un nom de méthode commençant par une majuscule ;
3. un type de retour coupé sur deux lignes ;
4. un import joker des membres d'un type du moteur (`import com.minos.orchestration.IndexingRuntimePorts.*;`) ;
5. un `import static` d'un type imbriqué du moteur.

## What Changes

- `module_boundary_ratchets.py` lit l'en-tête d'un membre public par sa structure (du dernier modificateur à la première ponctuation `(`, `;`, `=`, `{` ou `}`), après avoir effacé les annotations et recollé les noms qualifiés. La casse du nom, les retours à la ligne et les annotations cessent de compter. Un en-tête suivi de `(` dont le nom n'est pas lu fait échouer le garde.
- A11 refuse un import joker, statique ou non, dont la cible est un type de `minos-engine` ou un type imbriqué d'un tel type, en plus du joker d'un package du moteur.
- A11 compte comme type du moteur un type importé par `import static` depuis un type du moteur.
- L'exigence écrit que la racine de composition rendue par une méthode statique publique, générique ou non, est refusée. Le garde le fait au commit `54f23fa37` : aucun comportement ne change sur ce point.
- L'auto-test reçoit ces cas, la docstring de la règle et la puce A11 de `docs/developer/quality-gates.md` les nomment.

Aucun code Java, aucun workflow, aucune sortie publique ne change.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `frontieres-de-modules` : trois exigences ajoutées (écriture d'une déclaration, imports qui passent par un type du moteur, racine de composition rendue par une méthode statique).

## Hors périmètre

- Un champ public de la façade typé moteur, les membres d'un type imbriqué public (`Builder`, mais aussi un `record` public) et les méthodes héritées d'une interface : la règle porte sur les méthodes et constructeurs déclarés dans le corps de la façade (design, écarts 2 à 4).
- Deux `import` sur une même ligne : `SOURCE_IMPORT` est partagé avec d'autres règles (design, écart 5).
- Le joker des membres d'un type dans une **surface**, que le cliquet côté surface ne compte pas (design, écart 6).
- Une règle sur le bytecode (ArchUnit ou réflexion) : comparée et écartée de ce changement (design, D1).

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| Les cinq formes passent le garde | [Existant vérifié] | sonde sur arbres temporaires, design « État vérifié » |
| La racine de composition rendue par une méthode statique est refusée | [Existant vérifié] | `test_a_public_static_composition_root_is_refused`, sonde |
| `MinosApplication` n'emploie aucune des cinq formes | [Existant vérifié] | `python scripts/architecture/check-module-boundaries.py` vert avant et après (design, preuves) |
| La lecture de source reste nécessaire même avec une règle sur le bytecode | [Existant vérifié] | `minos_application_accessors` alimente le compte des usages des surfaces (`check_engine_types_named_by_surfaces`) |

## Impact

- **Modules du reactor** : aucun code de production.
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune.
- **ADR** : aucun créé. La mise à jour d'ARCH-0087 (« Compliance and Enforcement ») reste la tâche manuelle ouverte du sprint 11 ; une ligne s'y ajoute.
- **Gates à rejouer** : `check-module-boundaries.py`, les cinq `scripts/architecture/test_check_module_boundaries*.py`, `test_gate_runner.py`, `check-ci-wiring.py`, `check-comment-language.py`, `check-current-docs.py`, `check-capability-dossiers.py`, `python .claude/scripts/run_gates.py`. Aucun scope JaCoCo.
- **Plateformes** : lecture de texte, bibliothèque standard ; rien de propre à Windows ou Linux.
- **Documentation durable et diagrammes** : `docs/developer/quality-gates.md` ; `docs/architecture/diagrams/capacites/frontieres-de-modules.md` à l'archivage.
- **Dépendance** : s'applique après `lire-les-methodes-generiques-de-la-facade-dans-a11`, dont le code est la base ; archiver dans le même ordre.

## Questions ouvertes

Aucune bloquante.
