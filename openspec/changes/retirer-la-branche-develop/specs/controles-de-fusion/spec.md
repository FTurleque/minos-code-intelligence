## MODIFIED Requirements

### Requirement: Les checks exigés pour une fusion sont déclarés dans le dépôt
Le dépôt SHALL déclarer, dans `.github/required-checks.json`, la liste des checks que chaque ruleset de branche doit exiger. Cette liste SHALL contenir au minimum `Verify (ubuntu-24.04)`, `Verify (windows-2022)`, `Dependency vulnerability gate / osv-scan`, `Static invariants (single run)`, `Gitleaks` et `IntelliJ plugin (gate)` pour le ruleset de la branche `main`.

#### Scenario: Liste complète
- **WHEN** `python scripts/quality/check-ci-wiring.py` s'exécute sur le dépôt
- **THEN** il se termine avec le code 0 et affiche le nombre de contextes résolus vers un job

#### Scenario: Contexte sans job
- **WHEN** un contexte de la liste ne correspond à aucun `name:` de job après développement des matrices
- **THEN** la commande se termine avec le code 1 et nomme le contexte et le workflow déclaré

### Requirement: Le plugin IntelliJ rend un verdict sur toute pull request
`intellij-plugin.yml` SHALL s'exécuter sur toute pull request vers `main`, sans filtre de chemins au niveau du workflow, et SHALL exposer un job `IntelliJ plugin (gate)` exécuté même si un job amont a échoué ou a été annulé. Ce job SHALL réussir seulement si le calcul de portée a réussi et que chaque job du plugin a réussi ou a été sauté parce que le plugin n'est pas dans la portée. Une erreur du calcul de portée SHALL lancer le plugin (fail-closed).

#### Scenario: Plugin hors portée
- **WHEN** `python scripts/ci/test_plugin_gate.py` rejoue une PR qui ne modifie que `README.md`
- **THEN** la portée est `false` et le verdict avec les jobs du plugin sautés est « réussi »

#### Scenario: Plugin touché et en échec
- **WHEN** le verdict reçoit `plugin=true` et le résultat `failure` (ou `cancelled`) pour un job du plugin
- **THEN** le verdict est « échec » (code 1)

#### Scenario: Saut injustifié
- **WHEN** le verdict reçoit la portée `true` et un job du plugin sauté
- **THEN** le verdict est « échec »

#### Scenario: Calcul de portée en erreur
- **WHEN** la base du diff est introuvable ou l'événement est `workflow_dispatch`
- **THEN** la portée est `true`
