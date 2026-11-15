# Proposal

## Why

Constat **AUD-TST-09** (audit du 2026-11-15, sprint 1, sévérité faible, effort M). Dix gates n'ont ni option `--self-test` ni fichier `test_*.py` : les huit gates de cohérence `scripts/quality/check-*-consistency.py`, `scripts/quality/check-current-docs-vertical-extension.py` et `scripts/docs/check-current-docs.py` (271 lignes). Ils affirment des chaînes littérales lues dans les sources : après un renommage, un motif qui ne trouve plus rien, ou une règle dont l'attente est devenue triviale, peut rendre le gate vacuitaire sans qu'aucun test ne le voie. `check-current-docs.py` garde notamment la présence de `-Dminos.sandbox.tests.required` sur chaque commande Maven de la CI.

La relecture au HEAD montre que le défaut est plus large : huit autres gates exécutés par la CI ou par une release sont dans le même cas, et rien n'impose qu'un gate ait un auto-test (`check-ci-wiring.py` n'exige que le câblage des auto-tests qui existent).

## What Changes

- Les dix gates cités reçoivent une option `--self-test` : pour chaque fait qu'ils affirment, une mutation du fichier lu doit être refusée.
- Le mécanisme de mutation est écrit une fois, dans un module commun des gates littéraux ; un auto-test échoue si une règle du gate n'a produit aucune mutation.
- `check-ci-wiring.py` reçoit une règle : tout `scripts/**/check-*.py` hors `history/` déclare `--self-test` ou possède un `test_*.py` jumeau ; les gates qui n'en ont pas encore sont une liste fermée qui ne peut que diminuer, chacun avec sa raison.
- Les dix auto-tests sont exécutés par le job `invariants` de `pr-ci.yml`.

Aucun comportement d'un gate sur le dépôt réel ne change : mêmes règles, mêmes messages, mêmes codes de sortie.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities
- `controles-de-fusion` : trois exigences ajoutées (tout gate a un auto-test ; l'auto-test d'un gate littéral refuse une mutation par fait affirmé ; les gates sans auto-test sont une liste fermée décroissante). L'exigence existante « Chaque auto-test de gate est exécuté par le job des invariants » n'est pas modifiée : elle s'applique telle quelle aux nouveaux auto-tests.

## Hors périmètre

- Les scripts de `scripts/history/` (`check-results.py`, `check-m21-parity.py`, `check-s8-results.py`) : relevés figés, exclus du câblage par la règle existante.
- Doter d'un auto-test les huit gates de la liste fermée (`scripts/remediation/check-audit-remediation-v2.py`, `check-minos-01.py`, `check-mnd.py`, `check-mne.py`, `check-p0-p2.py`, `check-post-mne.py`, `check-post228-hardening.py`, `scripts/release/check-supply-chain.py`) : décision D4 du design ; ce changement les nomme et les fige.
- Changer ce que les gates vérifient, ou corriger un littéral devenu faux : un auto-test qui révèle une règle vacuitaire donne lieu à un constat, pas à une réécriture silencieuse.
- Les scripts PowerShell de contrôle.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| Les dix gates cités n'ont ni `--self-test` ni `test_*.py` | [Existant vérifié] | recherche de `self-test` / `self_test` : 0 occurrence dans chacun ; aucun `test_check_*consistency*.py`, `test_check_current_docs*.py` dans `scripts/` |
| Huit autres gates hors `history/` sont dans le même cas (dix-huit en tout) | [Existant vérifié] | recherche de `add_argument("--self-test"` et d'un `test_*.py` jumeau sur tous les `scripts/**/check-*.py` suivis par Git (liste dans le design) |
| Rien n'impose qu'un gate ait un auto-test | [Existant vérifié] | `scripts/quality/check-ci-wiring.py:11-12`, `:268-298` (règle 2 : câblage des auto-tests existants) |
| Les huit gates de cohérence et l'extension sont lancés par `check-post-mne.py`, lui-même lancé par `invariants` | [Existant vérifié] | `scripts/remediation/check-post-mne.py:15-23` ; `.github/workflows/pr-ci.yml:244` |
| `check-current-docs.py` est lancé par `invariants` | [Existant vérifié] | `.github/workflows/pr-ci.yml:166-167` |
| Les fonctions `read`, `require`, `forbid` sont recopiées d'un gate à l'autre | [Existant vérifié] | `scripts/quality/check-java-ast-provider-consistency.py:13-38` ; `scripts/docs/check-current-docs.py:38-72` |
| Traiter les huit gates restants plus tard | [Proposition] | design, D4 |

## Impact

- **Modules du reactor** : aucun.
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune.
- **ADR** : aucun (GEN-0043 régit déjà les gates littéraux ; rien n'y est contredit).
- **Gates à rejouer** : les dix gates et leur `--self-test`, `python scripts/remediation/check-post-mne.py`, `python scripts/quality/check-ci-wiring.py`, `python scripts/quality/test_check_ci_wiring.py`, `python scripts/quality/check-single-execution.py`, `python scripts/quality/check-comment-language.py`, `python .claude/scripts/run_gates.py`.
- **Fusion** : quatre changements du sprint 1 ajoutent des étapes au job `invariants` de `.github/workflows/pr-ci.yml` (`executer-le-front-de-l-explorateur-en-test`, `compter-comme-sautes-les-tests-propres-a-une-plateforme`, `doter-chaque-gate-d-un-auto-test`, `epingler-les-fins-de-ligne-des-scripts-posix`), et `borner-la-couverture-des-zones-nouvelles` y modifie l'artefact de validation : les fusionner en série, chaque PR remise à jour sur `main` avant la suivante.
- **Plateformes** : Python de la bibliothèque standard, identique sous Windows et Linux ; les auto-tests ne lancent ni Maven ni réseau.
- **Documentation durable et diagrammes** : `docs/developer/quality-gates.md`, `.claude/rules/gates-python.md` si la règle « tout gate a un auto-test » y est énoncée, `docs/architecture/diagrams/capacites/controles-de-fusion.md`.

## Questions ouvertes

Non bloquante : D4 du design (sort des huit gates de la liste fermée), recommandation donnée.
