# Proposal

## Why

La règle A11 tient la façade `MinosApplication` à zéro type de `minos-engine` sur ses méthodes et constructeurs publics (AUD-ARC-01, -02, -13, -17). Deux autres voies publiques restent ouvertes au commit `a2988f693` : un champ public typé moteur (`public ProjectRegistry registry;`) et un type imbriqué public (`public record Pair(ProjectRegistry r)`, `public interface Port { ProjectRegistry registry(); }`) passent le garde. Le design du changement `lire-toutes-les-ecritures-de-la-facade-dans-a11` les relève (écarts 2 et 3). Le propriétaire a demandé le 2026-11-27 de les spécifier.

## What Changes

- A11 refuse un champ public de `MinosApplication`, statique ou non, dont le type déclaré nomme un type de `minos-engine` ou `MinosApplicationComposer`.
- A11 refuse un type imbriqué public de `MinosApplication` autre que `Builder` : classe, `record`, interface, enum ou annotation, qu'il nomme ou non un type du moteur.
- L'exigence « L'écriture d'une déclaration de la façade ne contourne pas A11 » change un scénario : le `record` imbriqué accepté est un `record` non public.

La façade déclare au commit `a2988f693` douze champs publics, tous des `String`, et un seul type imbriqué public, `Builder` : la règle reste verte sur le dépôt. Aucun code Java ni aucune sortie publique ne change.

Ce changement est une **spécification** : le garde n'est pas modifié (tâches ouvertes).

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `frontieres-de-modules` : deux exigences ajoutées (champs publics, type imbriqué public), une exigence du changement prédécesseur modifiée.

## Hors périmètre

- Le contenu du `Builder` : surface de personnalisation de la composition, hors règle.
- La valeur d'un champ dont le type déclaré n'est pas du moteur (`public static final Object X = …`), comme le type réel rendu par une méthode.
- Les membres hérités d'une interface ou d'une classe mère.
- Les angles morts d'import des cliquets (joker côté surface, deux imports sur une ligne), traités par un changement distinct.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| Champs et types imbriqués publics passent le garde | [Existant vérifié] | sonde sur arbres temporaires, design « État vérifié » |
| La façade a douze champs publics `String` et un type imbriqué public, `Builder` | [Existant vérifié] | `MinosApplication.java:72-83`, `:461` |
| La règle couvre champs et types imbriqués publics | [Décision] | propriétaire, 2026-11-27 |
| Liste fermée des types imbriqués publics plutôt que lecture de leurs membres | [Proposition] | design, D2 |

## Impact

- **Modules du reactor** : aucun code de production.
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune.
- **ADR** : aucun créé. La proposition de mise à jour d'ARCH-0087 (« Compliance and Enforcement », tâche manuelle) reçoit ces deux refus.
- **Gates à rejouer** : `check-module-boundaries.py`, les `scripts/architecture/test_check_module_boundaries*.py`, `check-comment-language.py`, `check-current-docs.py`, `python .claude/scripts/run_gates.py`. Aucun scope JaCoCo.
- **Plateformes** : lecture de texte, bibliothèque standard ; rien de propre à Windows ou Linux.
- **Documentation durable et diagrammes** : `docs/developer/quality-gates.md` ; `docs/architecture/diagrams/capacites/frontieres-de-modules.md` à l'archivage.
- **Dépendance** : s'implémente et s'archive après `lire-toutes-les-ecritures-de-la-facade-dans-a11`.

## Questions ouvertes

Aucune bloquante. D2 attend la confirmation du propriétaire, avec une recommandation.
