# Design

## Contexte

Motivation : `proposal.md` (Why). Fichiers touchés : `scripts/ci/plugin-gate.py` (fonction `changed_files`, partagée), `scripts/ci/test_docker_gate.py`, `scripts/ci/test_plugin_gate.py`. Aucun module du reactor, aucune surface publique.

## État vérifié au HEAD

HEAD `fc1002e0`. Lecture du code et exécution des auto-tests.

| Fait | Preuve |
|---|---|
| [Existant vérifié] `changed_files` exécute `git diff --name-only <base>...<head>` (PR) ou `<base>..<head>` (push) | `scripts/ci/plugin-gate.py:51-62` |
| [Existant vérifié] `docker-gate.py` charge `plugin-gate.py` et appelle `changed_files` | `scripts/ci/docker-gate.py:37-41`, `:105` |
| [Existant vérifié] La liste d'exclusion Docker contient `docs/`, `openspec/`, `.claude/`, `minos-intellij/`, les `.md` racine et `^[^/]+/src/test/` | `scripts/ci/docker-gate.py:43-51` |
| [Existant vérifié] La détection de renommage de `git diff` est active par défaut : `--name-only` ne liste que le nouveau chemin | reproduction par les tests 1.1, rouges au HEAD |
| [Existant vérifié] Les deux auto-tests sont exécutés par le job `invariants` | `.github/workflows/pr-ci.yml:210-211` |

## Écarts

| Écart | Source A | Source B | Suite proposée |
|---|---|---|---|
| E1. L'audit vise la portée Docker ; le plugin partage la fonction | AUD-DEP-08 (Action) | `ETAT-2026-11-17.md` : « la portée du plugin IntelliJ a le même défaut » | Corriger la fonction partagée et tester les deux portées |

## Exigences couvertes

- `controles-de-fusion › Les deux chemins d'un renommage comptent dans une portée calculée` : D1.

## Objectifs et non-objectifs

Objectif : qu'un renommage ne puisse pas faire sortir une pull request d'une portée. Non-objectif : changer les listes de chemins.

## Responsabilités et frontières des composants

Non applicable : une option ajoutée à une commande Git existante.

## Modèle de domaine et invariants

Invariant : l'ensemble des chemins lus contient tout chemin présent dans `base` ou `head` dont le contenu ou l'existence diffère.

## Traitements et interactions

Non applicable : même appel Git, une option de plus.

## États et transitions

Non applicable.

## Contrats d'interface

`changed_files(event, base, head, root)` garde sa signature ; la liste rendue peut contenir un chemin de plus par renommage.

## Persistance et intégrité

Non applicable.

## Transactions, concurrence et idempotence

Non applicable.

## Sécurité et isolation

Fail-closed renforcé : une portée ne peut que s'élargir. Aucun nouveau processus ; l'appel Git existant garde son délai.

## Erreurs et reprise

Inchangé : une erreur Git rend `None`, donc la portée `true`.

## Observabilité

Non applicable.

## Performance et mesure

Non applicable : `--no-renames` évite le calcul de similarité.

## Compatibilité, migration et retour arrière

Sans renommage, la liste est identique. Retour arrière : retirer l'option.

## Diagrammes

Non applicable : une option d'une commande, sans flux nouveau.

## Décisions

### D1 — `--no-renames` sur la commande partagée

[Décision] Ajouter `--no-renames` à `git diff --name-only` dans `changed_files`. Écarté : `--name-status` avec analyse des lignes `R` (plus de code pour le même ensemble de chemins).

## Qualification des capacités

`controles-de-fusion` : **qualifiée** pour cette exigence quand les deux auto-tests sont verts dans le job `invariants`.

## Risques et compromis

- [Risque] Un déplacement de documentation vers un chemin concerné met la PR en portée : voulu.

## Vérification

- `python scripts/ci/test_docker_gate.py`, `python scripts/ci/test_plugin_gate.py`, `python scripts/quality/check-ci-wiring.py`, `python .claude/scripts/run_gates.py --fast`.

## Documentation durable à mettre à jour

`docs/architecture/diagrams/capacites/controles-de-fusion.md` (prose : renommages comptés).

## Traçabilité

| Exigence (capacité › titre) | Scénario | Conception ou diagramme | Tâche | Test ou preuve |
|---|---|---|---|---|
| controles-de-fusion › Les deux chemins d'un renommage comptent dans une portée calculée | Source principale déplacée sous les tests | D1 | 1.1, 1.2 | `test_docker_gate.py::test_a_main_source_moved_under_test_sources_or_documents_is_in_scope` — écrit |
| idem | Source principale déplacée sous la documentation | D1 | 1.1, 1.2 | même test — écrit |
| idem | Rendu déplacé hors des chemins du plugin | D1, E1 | 1.1, 1.2 | `test_plugin_gate.py::test_a_renderer_moved_out_of_the_plugin_paths_is_in_scope` — écrit |
| idem | Renommage interne à la liste d'exclusion | D1 | 1.1 | `test_docker_gate.py::test_a_document_renamed_within_the_excluded_list_stays_out_of_scope` — écrit |

## Questions ouvertes

Aucune.
