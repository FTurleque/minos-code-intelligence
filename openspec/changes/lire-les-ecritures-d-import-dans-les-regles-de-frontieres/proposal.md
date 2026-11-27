# Proposal

## Why

Suite du constat **AUD-ARC-17** de l'audit `docs/project/audits/2026-11-15/` : le design du changement `lire-toutes-les-ecritures-de-la-facade-dans-a11` nomme deux écritures d'import Java légales qu'il ne traite pas (écarts 5 et 6). Une sonde sur arbres temporaires établit que des règles de `check-module-boundaries.py` les laissent passer :

1. un **joker non statique sur un type** (`import com.minos.orchestration.IndexingRuntimePorts.*;`) dans une surface, un adaptateur ou une source qui ne doit nommer aucun adaptateur ;
2. un **import qui n'ouvre pas sa ligne** (`import java.util.List; import com.minos.registry.*;`).

Un cliquet qui ne lit pas une écriture laisse la dette qu'il mesure s'accroître sans échec.

## What Changes

- `module_boundary_sources.py` : `SOURCE_IMPORT` lit une déclaration d'import où qu'elle soit dans la ligne. Toutes les règles qui le partagent en bénéficient (design, « Lecteurs »).
- A11 (surfaces et module de contrat) et A13 (adaptateur vers adaptateur) refusent un joker non statique dont la cible est un type du module visé ou un type imbriqué d'un tel type, et comptent ce type comme utilisé.
- A2 (sources) refuse un joker sur les membres d'une classe d'adaptateur.
- Les auto-tests des cliquets, des adaptateurs et des packages reçoivent ces cas ; `docs/developer/quality-gates.md` les nomme.

Aucun code Java, aucun workflow, aucune sortie publique ne change.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `frontieres-de-modules` : trois exigences ajoutées (position d'un import dans la ligne, joker sur un type en A11 et A13, joker sur une classe d'adaptateur en A2).

## Hors périmètre

- Un import dont le nom qualifié contient des blancs ou un retour à la ligne autour d'un point (`import com.minos.registry . ProjectRegistry;`) : la sonde montre qu'une surface l'accepte. Troisième écriture, à instruire à part (design, écart 3).
- `check-private-io.py`, qui a son propre lecteur d'imports et n'est pas une règle de frontière.
- Les champs publics et les types imbriqués de la façade : décision séparée.
- Un type membre hérité nommé sans import : la lecture de la source ne le voit pas.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| Le joker sur un type passe A11 (surfaces), A13 et A2 | [Existant vérifié] | sonde sur arbres temporaires, design « Sonde avant correction » |
| Un import simple en second sur sa ligne est déjà lu par son nom qualifié, sauf dans la façade | [Existant vérifié] | même sonde |
| Un joker en second sur sa ligne passe A11, A13, A2, A10, R1, A8 | [Existant vérifié] | même sonde |
| Le dépôt n'emploie aucune des deux écritures | [Existant vérifié] | sortie du garde identique avant et après (design, preuves) |

## Impact

- **Modules du reactor** : aucun code de production.
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune.
- **ADR** : aucun créé ni amendé. La mise à jour d'ARCH-0087 (« Compliance and Enforcement ») reste la tâche manuelle ouverte du sprint 11 ; une ligne s'y ajoute.
- **Gates à rejouer** : `check-module-boundaries.py`, les cinq `scripts/architecture/test_check_module_boundaries*.py`, `test_gate_runner.py`, `check-ci-wiring.py`, `check-comment-language.py`, `check-current-docs.py`, `check-capability-dossiers.py`, `python .claude/scripts/run_gates.py`. Aucun scope JaCoCo.
- **Plateformes** : lecture de texte, bibliothèque standard ; rien de propre à Windows ou Linux.
- **Documentation durable et diagrammes** : `docs/developer/quality-gates.md` ; `docs/architecture/diagrams/capacites/frontieres-de-modules.md` à l'archivage.
- **Dépendance** : s'applique après `lire-les-methodes-generiques-de-la-facade-dans-a11` puis `lire-toutes-les-ecritures-de-la-facade-dans-a11`, dont le code est la base ; archiver dans le même ordre.

## Questions ouvertes

Aucune bloquante.
