# Proposal

## Why

Constat **AUD-ARC-11** de l'audit `docs/project/audits/2026-11-15/` (sprint 9, sévérité faible, ouvert par la vérification du 19/11) : la règle A19 du garde de frontières, qui ferme AUD-ARC-01, tient deux listes fermées, `INFRASTRUCTURE_API_USERS` (fichiers de `minos-application` qui nomment une API d'infrastructure du JDK) et `EMBEDDED_ADAPTER_CLASSES` (classes d'adaptateur que nulle autre source de l'application ne nomme). Rien ne lie les deux : retirer de la seconde l'entrée d'une classe qui nomme encore `java.net.HttpURLConnection` ou `javax.tools` ne fait pas échouer le garde. L'application peut alors instancier de nouveau le client HTTP Ollama ou l'analyseur `javac` : la garantie d'ARCH-0087 ne repose plus que sur la revue.

## What Changes

- La règle A19 de `scripts/architecture/check-module-boundaries.py` exige que chaque classe de `INFRASTRUCTURE_API_USERS` figure, par son nom simple, dans `EMBEDDED_ADAPTER_CLASSES` ; une entrée manquante fait échouer le garde avec un message qui nomme la classe et l'API.
- L'auto-test `EmbeddedAdapterRuleTest` de `scripts/architecture/test_check_module_boundaries.py` gagne le cas rouge (entrée retirée alors que la classe nomme encore une API) et le cas vert (les deux listes cohérentes).
- Le point 3 d'ARCH-0087 dit le lien entre les deux listes.

Aucun code Java, aucune sortie de la CLI, de l'API Java, du MCP, du plugin IntelliJ ni de NEXUS ne change.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `frontieres-de-modules` : l'exigence « Une classe d'adaptateur embarquée n'est nommée que par sa famille » lie les deux listes de la règle A19.

## Hors périmètre

- `FileProgramGraphProvider`, `JavaAstSupport` et `JavaProgramModel` : la mesure du design montre que le retrait de leur entrée est silencieux. Les deux derniers nomment une API d'infrastructure et sont couverts par ce changement ; `FileProgramGraphProvider` n'en nomme aucune et reste protégé par la revue seule (décision D2).
- Le retrait de `compositionRoot()` et des accesseurs de `MinosApplication` (AUD-ARC-02, partiel, groupe 4 reporté par le propriétaire) et le commentaire périmé du garde sur ces accesseurs (`check-module-boundaries.py:734-736`) : sprint 7.
- Tout déplacement de classe, tout changement de la liste des API surveillées (`INFRASTRUCTURE_API`).

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| A19 ne vérifie pas que les classes de `INFRASTRUCTURE_API_USERS` figurent dans `EMBEDDED_ADAPTER_CLASSES` | [Existant vérifié] | `scripts/architecture/check-module-boundaries.py:1176-1210` |
| Une entrée d'adaptateur n'est refusée comme périmée que si sa classe n'existe plus | [Existant vérifié] | `check-module-boundaries.py:1203-1204` |
| Retirer `OllamaEmbeddingProvider`, `JavaAstSupport`, `JavaProgramModel` ou `FileProgramGraphProvider` de la liste laisse le garde vert ; les 13 autres retraits sont refusés | [Existant vérifié] | design, « État vérifié au HEAD » (mutation en mémoire) |
| Les 10 classes de `INFRASTRUCTURE_API_USERS` figurent toutes aujourd'hui dans `EMBEDDED_ADAPTER_CLASSES` | [Existant vérifié] | `scripts/architecture/module-boundaries.json:222-252` |
| Les deux listes ne font que rétrécir | [Décision] | `docs/architecture/adr/ARCH-0087-adaptateurs-embarques-et-facade-reduite.md:37` |
| Le lien se vérifie par inclusion des noms simples | [Proposition] | design, D1 |

## Impact

- **Modules du reactor** : aucun.
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune.
- **ADR** : ARCH-0087 (`Proposed`) amendé au point 3 ; aucun ADR nouveau.
- **Gates à rejouer** : `check-module-boundaries.py`, `test_check_module_boundaries.py`, `check-ci-wiring.py`, `check-single-execution.py`, `python .claude/scripts/run_gates.py`.
- **Plateformes** : Windows et Linux — script Python sans dépendance de plateforme.
- **Documentation durable** : `docs/architecture/diagrams/capacites/frontieres-de-modules.md` (ligne de traçabilité de l'exigence modifiée), ARCH-0087.

## Questions ouvertes

Aucune bloquante : D2 (résidu `FileProgramGraphProvider`) a une recommandation et n'empêche pas l'implémentation.
