## ADDED Requirements

### Requirement: Le gate de preuve Docker rend un verdict sur toute pull request
`release-promotion-gate.yml` SHALL s'exécuter sur toute pull request vers `main`, sans filtre de chemins, et SHALL exposer le job `Docker upgrade evidence gate`, exécuté même si un job amont a échoué, a été annulé ou a été sauté. Ce job SHALL réussir seulement si le calcul de portée a réussi et que, selon la portée, la preuve de qualification du candidat est valide ou la qualification a été sautée parce que la pull request n'est pas concernée.

#### Scenario: Pull request hors portée
- **WHEN** `python scripts/ci/test_docker_gate.py` rejoue le verdict avec un calcul de portée réussi, la portée `false` et une qualification sautée
- **THEN** le verdict est « réussi » et nomme la raison « non concerné »

#### Scenario: Pull request concernée dont la qualification échoue
- **WHEN** le verdict reçoit la portée `true` et le résultat `failure` ou `cancelled` de la qualification
- **THEN** le verdict est « échec » (code 1)

#### Scenario: Saut injustifié
- **WHEN** le verdict reçoit la portée `true` et une qualification sautée
- **THEN** le verdict est « échec »

#### Scenario: Calcul de portée en échec
- **WHEN** le verdict reçoit un calcul de portée qui n'a pas réussi, ou une portée vide
- **THEN** la pull request est traitée comme concernée et le verdict est « échec » sans preuve valide

#### Scenario: Check exigé toujours joignable
- **GIVEN** le workflow après le changement
- **WHEN** `python scripts/quality/check-ci-wiring.py` est exécuté
- **THEN** il se termine avec le code 0 et résout le contexte `Docker upgrade evidence gate` vers un job d'un workflow sans `paths:`

### Requirement: Un job exigé qui attend d'autres jobs s'exécute quel que soit leur résultat
Un job dont le nom est un check exigé et qui déclare `needs:` SHALL déclarer `if: always()` (sous cette forme ou `${{ always() }}`), afin de rendre un verdict quand un job amont a échoué, a été annulé ou a été sauté. `check-ci-wiring.py` SHALL échouer sur un tel job sans cette condition.

#### Scenario: Job exigé sans la condition
- **GIVEN** une copie du dépôt où le job `Docker upgrade evidence gate` déclare `needs:` sans `if: always()`
- **WHEN** `python scripts/quality/check-ci-wiring.py` est exécuté sur cette copie
- **THEN** il se termine avec le code 1 en nommant le workflow et le job

#### Scenario: Dépôt réel
- **GIVEN** les workflows du dépôt, où les deux jobs exigés à `needs:` (`IntelliJ plugin (gate)`, `Docker upgrade evidence gate`) déclarent la condition
- **WHEN** le gate est exécuté
- **THEN** il se termine avec le code 0

#### Scenario: Job exigé sans dépendance
- **GIVEN** un job exigé qui ne déclare pas `needs:`
- **WHEN** le gate est exécuté
- **THEN** ce job n'est pas signalé par cette règle

### Requirement: Une pull request n'échappe à la qualification Docker que par une liste fermée de chemins
Le calcul de portée SHALL déclarer une pull request hors portée seulement si chacun de ses fichiers modifiés appartient à une liste fermée de chemins sans effet sur l'image ni sur sa mise à niveau. Un chemin inconnu, une base introuvable, une erreur Git ou un déclenchement manuel SHALL la déclarer concernée. La liste SHALL NOT contenir le workflow du gate, ses scripts, `docker/`, un POM ni les sources principales d'un module.

#### Scenario: Documentation seule
- **WHEN** `python scripts/ci/test_docker_gate.py` rejoue une pull request qui ne modifie que des fichiers sous `docs/` et `openspec/`
- **THEN** la portée est `false`

#### Scenario: Un fichier de l'image parmi des documents
- **WHEN** la pull request modifie `docs/user/cli.md` et `docker/Dockerfile.mcp.release`
- **THEN** la portée est `true`

#### Scenario: Chemin absent de la liste
- **WHEN** la pull request modifie un fichier d'un dossier que la liste ne nomme pas
- **THEN** la portée est `true`

#### Scenario: Base introuvable ou déclenchement manuel
- **WHEN** la base du diff est vide ou introuvable, ou l'événement est `workflow_dispatch`
- **THEN** la portée est `true`

#### Scenario: Liste élargie à un chemin de l'image
- **GIVEN** une copie du script de portée dont la liste fermée contient `docker/` ou `pom.xml`
- **WHEN** l'auto-test est exécuté sur cette copie
- **THEN** il échoue en nommant l'entrée interdite
