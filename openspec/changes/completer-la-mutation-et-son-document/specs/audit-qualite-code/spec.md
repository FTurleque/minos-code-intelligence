## ADDED Requirements

### Requirement: L'état de la mutation par module est écrit une seule fois
Le document de couverture de l'audit SHALL porter un tableau unique donnant, pour chaque module du reactor et pour le plugin IntelliJ, un statut de mutation (exécuté, partiel, non exécuté, bloqué), le système, l'exécution et la raison. Les autres sections SHALL y renvoyer et SHALL NOT redonner un décompte de modules mutés.

#### Scenario: Décompte unique
- **GIVEN** `docs/quality/code-audit-couverture.md`
- **WHEN** on y cherche les décomptes de la forme « modules sur 14 » relatifs à PIT
- **THEN** il n'en existe qu'à un seul endroit, celui du tableau d'état

#### Scenario: Décompte contradictoire réintroduit
- **GIVEN** une copie du document où une section hors du tableau affirme un autre nombre de modules mutés
- **WHEN** on compte dans cette copie les lignes contenant « modules sur 14 » (par exemple `grep -c "modules sur 14"`)
- **THEN** le compte dépasse un et la copie n'est pas acceptée à la clôture

#### Scenario: Tous les modules nommés
- **GIVEN** la liste des modules du `pom.xml` racine et le plugin IntelliJ
- **WHEN** le tableau d'état est lu
- **THEN** chacun y figure avec un statut

### Requirement: Un module sans rapport complet n'est pas compté comme analysé
Un module dont le rapport PIT est absent, vide, ou porte des mutants non analysés (minion non démarré) SHALL être écrit « non exécuté » ou « partiel », jamais « exécuté ». Les mutants en délai dépassé ou en erreur SHALL être comptés à part des mutants tués. Un module muté sur un seul système SHALL porter ce système, et le code propre à l'autre SHALL être déclaré non analysé.

#### Scenario: Rapport manquant
- **GIVEN** des rapports PIT pour tous les modules sauf un qui a des classes compilées
- **WHEN** `python scripts/quality/audit-report-summary.py pit --all` est exécuté
- **THEN** la commande échoue en nommant le module sans rapport

#### Scenario: Erreurs d'exécution massives
- **GIVEN** un rapport où des mutants sont en `RUN_ERROR`
- **WHEN** la synthèse est produite
- **THEN** ces mutants sont comptés à part des mutants tués et le module est écrit « partiel » tant qu'ils ne sont pas rejoués

#### Scenario: Rejeu sous Linux d'un module à code Windows
- **GIVEN** `minos-runtime-local` muté sur un runner Linux
- **WHEN** son statut est écrit dans le tableau
- **THEN** il porte « Linux » et ses classes propres à Windows sont déclarées non analysées
