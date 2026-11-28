# Proposal

## Why

Constat **AUD-ARC-18** de l'audit `docs/project/audits/2026-11-15/` (sprint 12, sévérité faible, ouvert le 2026-11-27 au commit `4f4cac81`). L'exigence « Le dossier de la capacité nomme chaque règle du garde » (`openspec/specs/frontieres-de-modules/spec.md:1709`) dit que le dossier `docs/architecture/diagrams/capacites/frontieres-de-modules.md` n'écrit aucun nombre dérivé de la politique. Le dossier en écrit encore : écart E2 (`:221`, « 14 modules », « 13 modules ») et E4 (`:223`, « 3, 4, 1 et 0 », « 14, 15, 4, 6 »), et l'écart E1 et la ligne de traçabilité des cycles connus citent « 4 cycles » et des nombres de packages (`:122`, `:220`). Aucun contrôle ne tient cette phrase : l'auto-test documentaire ne compare que des étiquettes `A<n>` et des noms de fichiers (`scripts/architecture/test_check_module_boundaries_documents.py:438-486`). La preuve de l'écart E8 (`:227`) cite en outre des accesseurs de `MinosApplication` qui n'existent plus (`gitIntelligence()`, `hostedControlPlaneService()`).

## What Changes

- Le dossier est réécrit sans décompte : E2 est clos (la spécification n'écrit plus « 13 modules »), E4 décrit le cliquet sans compte, E1 et la ligne de traçabilité des cycles connus disent la contradiction sans recopier de nombre, E8 cite les scénarios fautifs de la spécification (`spec.md:708`, `:731`) et l'absence des accesseurs au lieu de les nommer comme preuve. L'en-tête cite le commit du relevé.
- `test_check_module_boundaries_documents.py` gagne un cas sur le dépôt réel : le dossier ne contient aucun nombre en chiffres suivi d'un nom de la politique (module, dépendance, découverte, package, cycle) ; un cas sur une copie en mémoire constate le refus et nomme la ligne.
- Une exigence ajoutée tient cette règle.

Aucun code de production, aucun gate nouveau, aucun workflow ne change : l'auto-test est déjà lancé par le job `invariants`.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `frontieres-de-modules` : une exigence ajoutée (le dossier n'écrit aucun décompte de la politique, un auto-test le refuse).

## Hors périmètre

- Corriger dans la spécification les scénarios que les écarts E1 (`spec.md:66`), E4 (`spec.md:524`) et E8 (`spec.md:708`, `:731`) désignent : option écartée pour ce changement (design, D2). Les écarts restent listés dans le dossier.
- La phrase « Aucune règle ne porte… » et les renvois de ligne du dossier (mutant « retrait de « A1 et » » de l'audit) : tenus par la revue, comme la décision du propriétaire du 2026-11-24 pour AUD-ARC-12 l'a retenu.
- Les nombres écrits en lettres (« quatre », « douze ») : hors de la règle automatique (design, D1).
- Le « 154 cas » de `STATUS.md` et `ROADMAP.md` : AUD-QUA-10, changement `aligner-la-documentation-courante-apres-le-sprint-11`.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| Exigence « aucun nombre dérivé de la politique » | [Existant vérifié] | `openspec/specs/frontieres-de-modules/spec.md:1709` |
| Nombres suivis d'un nom de la politique dans le dossier : `:122` (4 cycles), `:220` (15 packages, 4 cycles, 0 packages), `:221` (14 modules ×2, 13 modules) | [Existant vérifié] | `grep -noE "\b[0-9]+ (autres )?(modules?\|dépendances?\|découvertes?\|packages?\|cycles?)\b"` sur le dossier |
| E4 écrit « 3, 4, 1 et 0 » et « 14, 15, 4, 6 » | [Existant vérifié] | `frontieres-de-modules.md:223` |
| E8 cite `gitIntelligence()` et `hostedControlPlaneService()` comme preuve ; ces méthodes n'existent plus ; la spécification a encore des scénarios qui les appellent | [Existant vérifié] | `frontieres-de-modules.md:227` ; `MinosApplication.java` (absentes) ; `spec.md:708`, `:731` |
| L'auto-test documentaire ne lit que les étiquettes et les fichiers | [Existant vérifié] | `test_check_module_boundaries_documents.py:438-486` |
| Mutants « 12 autres modules » et retrait de « A1 et » : auto-test vert | [Existant vérifié par l'audit, non rejoué] | fiche AUD-ARC-18 |
| Règle en chiffres sur une liste fermée de noms | [Proposition] | design, D1 |

## Impact

- **Modules du reactor** : aucun.
- **Surfaces publiques** : aucune.
- **ADR** : aucun.
- **Gates à rejouer** : `python scripts/architecture/test_check_module_boundaries_documents.py`, les quatre autres auto-tests du garde, `scripts/architecture/check-module-boundaries.py`, `scripts/docs/check-capability-dossiers.py`, `scripts/quality/check-comment-language.py`, `python .claude/scripts/run_gates.py`. Aucun scope JaCoCo.
- **Plateformes** : auto-test Python, bibliothèque standard, Ubuntu (job `invariants`) et poste Windows ; lecture en UTF-8 explicite.
- **Documentation durable** : `docs/architecture/diagrams/capacites/frontieres-de-modules.md`.

## Questions ouvertes

Aucune bloquante : D1 et D2 ont une recommandation.
