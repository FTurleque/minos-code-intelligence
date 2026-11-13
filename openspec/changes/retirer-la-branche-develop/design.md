# Design

Changement de configuration et d'outillage : seules les sections Contexte, Décisions, Vérification et Traçabilité sont exigées.

## Contexte

Motivation : `proposal.md`. Le dépôt intégrait par `develop` puis promouvait vers `main`. Depuis la PR #720, `main` contient tout `develop`. Sont touchés : `.github/` (workflows, Dependabot, déclaration des checks), `scripts/` (deux scripts de base de comparaison, trois gates littéraux, un auto-test), `.claude/`, `.archgate/`, les consignes d'agents et un test de contrat de `minos-app`.

Fait qui borne le changement : `release-promotion-gate.yml` se déclenche sur toute PR vers `main` (`.github/workflows/release-promotion-gate.yml:21-24`) et son check est exigé par le ruleset « Release promotion gate (main only) » (`.github/required-checks.json`). Tant que les PR de travail visaient `develop`, il ne tournait qu'aux promotions ; il tourne maintenant à chaque PR.

## Décisions

### D1 — `main` est la seule branche d'intégration
[Décision — propriétaire, 2026-11-13] Toute branche thématique ouvre une PR vers `main`. Écarté : garder `develop` comme branche tampon.

### D2 — Déclarer le ruleset sous son nom réel, « Protect main »
[Décision] La déclaration suit GitHub, où le ruleset actif porte ce nom ; `verify-ruleset.py` compare par nom. Écarté : garder l'ancien nom dans le dépôt (la comparaison échouerait).

### D3 — Ne pas réécrire les relevés datés
[Décision] Un document qui cite `develop` comme un fait de sa date reste tel quel (règle « liens oui, prose non »). Seuls changent les fichiers opératifs, les consignes en vigueur et les exigences courantes.

### D4 — Laisser le gate de promotion sur toute PR
[Proposition] Aucun affaiblissement d'un check exigé dans un changement de nettoyage. Le coût par PR est signalé au propriétaire (proposition, « Questions ouvertes »).

### D5 — Supprimer la branche en dernier, à la main
[Décision — propriétaire, 2026-11-13] La suppression de `develop` (distante puis locale) suit la fusion de ce changement.

## Vérification

- `python .claude/scripts/run_gates.py` : tous les gates et leurs auto-tests. Rouge observé pendant le changement : `scripts/quality/test_verify_ruleset.py` (`test_the_declared_manifest_of_the_repository_is_readable`) échouait sur l'ancien nom du ruleset, puis passe.
- `openspec validate --all --strict`.
- `M29DockerReleaseCiGateContractTest` : exécuté par la CI (`Verify`) ; localement, seule la présence des deux chaînes attendues dans `docker-release-validation.yml` a été contrôlée.
- Non vérifiable hors GitHub : le comportement réel des workflows et de Dependabot après fusion ; `verify-ruleset.py` contre l'API (tâche 2.1).

## Traçabilité

| Exigence (capacité › titre) | Scénario | Conception ou diagramme | Tâche | Test ou preuve |
|---|---|---|---|---|
| controles-de-fusion › Les checks exigés pour une fusion sont déclarés dans le dépôt | scénarios inchangés | D2 | 1.1–1.2 | `check-ci-wiring.py`, `test_verify_ruleset.py` |
| controles-de-fusion › Le plugin IntelliJ rend un verdict sur toute pull request | scénarios inchangés | D1 | 1.1–1.2 | `check-ci-wiring.py` (déclencheurs de `intellij-plugin.yml`) |
