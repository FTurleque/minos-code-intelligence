# Proposal

## Why

Décision du propriétaire du 2026-11-13 : la branche `develop` n'est plus utilisée. Sa dernière promotion vers `main` est la PR #720 (commit de fusion `cdd27588`). Les workflows, Dependabot, la déclaration des checks exigés, les hooks et les consignes d'agents citent encore `develop` : la supprimer sans les adapter laisserait des déclencheurs morts, des PR Dependabot sans cible et des consignes fausses.

## What Changes

- Les workflows ne se déclenchent plus que pour `main` (`branches: [main]`) ; Dependabot cible `main`.
- `.github/required-checks.json` déclare le ruleset « Protect main », limité à `main`, nom du ruleset actif sur GitHub.
- Les scripts qui prenaient `origin/develop` pour base (`scripts/ci/qualify-docker-upgrade.ps1`, `scripts/remediation/run-final.ps1` et `.sh`, `.archgate/config.json`) prennent `main`.
- Les hooks, commandes, skills et règles de `.claude/`, `AGENTS.md`, `openspec/config.yaml`, `README.md` et `SECURITY.md` décrivent le flux « branche thématique → PR vers `main` ».
- Deux exigences de `controles-de-fusion` ne citent plus `develop`.
- **Conséquence** : le gate de promotion (qualification Docker A → B réelle) s'exécute désormais sur **toute** PR, puisque toute PR vise `main`.

Aucune rupture de contrat produit. La suppression de la branche elle-même est une action manuelle, après fusion.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities
- `controles-de-fusion` : « Les checks exigés pour une fusion sont déclarés dans le dépôt » et « Le plugin IntelliJ rend un verdict sur toute pull request » ne visent plus que `main`.

## Hors périmètre

- Les relevés datés qui citent `develop` comme un fait de leur époque : `docs/project/history/`, `docs/project/audits/`, `docs/project/roadmap/M*_EXECUTION.md`, `docs/project/research/`, le texte des ADR, les commentaires de mesure de `scripts/quality/check-jacoco.py`, les notes de version, les changements OpenSpec archivés et les sections datées de `STATUS.md`.
- Le scénario de `frontieres-de-modules` qui cite `git diff --stat develop` : commande de vérification d'un changement passé.
- Toute réduction du coût de la CI par PR (filtrage du gate de promotion) : décision séparée.
- Les jeux de données des auto-tests de gates (`scripts/quality/test_*.py`) : ils décrivent des workflows fictifs.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| `main` contient tout `develop` | [Existant vérifié] | API GitHub après la fusion de la PR #720 : `main...develop` = 0 commit d'avance |
| Le ruleset actif se nomme « Protect main » | [Existant vérifié] | `gh api repos/FTurleque/minos-code-intelligence/rulesets`, 2026-11-13 |
| `main` est la branche par défaut ; aucune PR ouverte | [Existant vérifié] | `gh repo view`, `gh pr list`, 2026-11-13 |
| Le gate de promotion se déclenche sur toute PR vers `main` | [Existant vérifié] | `.github/workflows/release-promotion-gate.yml:21-24` |
| `develop` n'est plus utilisée | [Décision] | propriétaire, 2026-11-13 |
| Les checks du ruleset GitHub correspondent à la déclaration | [Question] | `python scripts/quality/verify-ruleset.py`, tâche 2.1 |

## Impact

- **Modules du reactor** : `minos-app` (un test de contrat de workflow, deux chaînes).
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune.
- **ADR** : aucun. Le flux de branches est une convention de gouvernance tenue par `AGENTS.md` et les rulesets, pas une décision d'architecture.
- **Gates à rejouer** : `python .claude/scripts/run_gates.py` (tous les gates et leurs auto-tests), dont `check-ci-wiring.py`, `check-current-docs.py`, `check-audit-remediation-v2.py`, `test_verify_ruleset.py`.
- **Plateformes** : Windows et Linux, sans différence.
- **Documentation durable et diagrammes** : `docs/architecture/diagrams/capacites/controles-de-fusion.md` et `chaine-de-livraison.md`, `docs/developer/quality-gates.md`, `STATUS.md`, `ROADMAP.md`.

## Questions ouvertes

Non bloquante : garder la qualification Docker A → B sur toute PR, ou la restreindre (chemins, étiquette, déclenchement manuel) pour limiter la CI. Recommandation : la garder telle quelle dans ce changement et trancher à part, mesure de durée à l'appui.
