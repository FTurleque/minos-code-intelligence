# Proposal

## Why

Suite du constat **AUD-ARC-17** de l'audit `docs/project/audits/2026-11-15/` : le design du changement `lire-les-ecritures-d-import-dans-les-regles-de-frontieres` nomme une troisième écriture légale qu'il ne traite pas (écart 3). Java accepte des blancs, des retours à la ligne et des commentaires autour des points d'un nom (`com.minos.registry . ProjectRegistry`). Une sonde sur arbres temporaires établit que A2, A8, A10, A11, A13, R1 et l'isolation des packages du stockage laissent passer un nom ainsi coupé, importé ou écrit en entier dans le code, et que A15 déclare sans appelant un type qui n'est nommé que par lui.

Un cliquet qui ne lit pas une écriture laisse la dette qu'il mesure s'accroître sans échec.

## What Changes

- `module_boundary_sources.py` : une lecture unique, `name_reading_code`, efface commentaires et littéraux puis recolle les parties de chaque nom, `.*` d'un joker compris. Toutes les règles qui lisent des noms passent par elle (design, « Lecteurs »).
- A11 sur la façade `MinosApplication` lit aussi un joker coupé, par la même expression de recollage.
- Les auto-tests des cliquets, des adaptateurs et des packages reçoivent ces cas ; `docs/developer/quality-gates.md` les nomme.

Aucun code Java, aucun workflow, aucune sortie publique ne change.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `frontieres-de-modules` : une exigence ajoutée (un nom coupé autour d'un point est lu en entier).

## Hors périmètre

- Les échappements Unicode (`com.minos.registry\u002eProjectRegistry`) : la sonde montre qu'aucune règle de nom ne les lit, façade comprise (design, écart 2).
- Une annotation de type à l'intérieur d'un nom qualifié du code (`com.minos.hosted.@Nullable HostedRole`), lue dans la façade seulement (design, écart 3).
- La règle Archgate d'ARCH-0027 sur les imports du plugin, ancrée sur le début de ligne (design, écart 4).

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| Un nom coupé, importé ou qualifié, passe A2, A8, A10, A11, A13, R1 et l'isolation du stockage | [Existant vérifié] | sonde sur arbres temporaires, design « Sonde avant correction » |
| La façade lit un import et un type coupés, pas un joker coupé | [Existant vérifié] | même sonde |
| A15 déclare sans appelant un type nommé par un seul nom coupé | [Existant vérifié] | même sonde |
| Le verdict du dépôt ne change pas | [Existant vérifié] | sortie du garde identique avant et après (design, preuves) |

## Impact

- **Modules du reactor** : aucun code de production.
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune.
- **ADR** : aucun créé ni amendé. La mise à jour d'ARCH-0087 (« Compliance and Enforcement ») reste la tâche manuelle ouverte du sprint 11 ; une ligne s'y ajoute.
- **Gates à rejouer** : `check-module-boundaries.py`, les cinq `scripts/architecture/test_check_module_boundaries*.py`, `test_gate_runner.py`, `check-ci-wiring.py`, `check-comment-language.py`, `check-current-docs.py`, `check-capability-dossiers.py`, `python .claude/scripts/run_gates.py`. Aucun scope JaCoCo.
- **Plateformes** : lecture de texte, bibliothèque standard ; rien de propre à Windows ou Linux.
- **Documentation durable et diagrammes** : `docs/developer/quality-gates.md` ; `docs/architecture/diagrams/capacites/frontieres-de-modules.md` à l'archivage.
- **Dépendance** : s'applique après `lire-les-ecritures-d-import-dans-les-regles-de-frontieres`, dont le code est la base ; archiver dans le même ordre.

## Questions ouvertes

Aucune bloquante.
