## ADDED Requirements

### Requirement: Une release publiée par le chemin nominal contient le plugin IntelliJ
Le workflow qui crée une release SHALL appeler explicitement la publication du plugin IntelliJ pour le même tag et le même commit, et SHALL échouer si l'archive du plugin ou sa somme de contrôle est absente de la release. La publication du plugin SHALL NOT dépendre d'un événement de release émis par le jeton du workflow.

#### Scenario: Workflow de release sans appel du plugin
- **GIVEN** une copie du dépôt où le workflow qui crée la release n'appelle pas le workflow de publication du plugin
- **WHEN** `python scripts/quality/check-ci-wiring.py` est exécuté sur cette copie
- **THEN** le gate échoue en nommant le workflow

#### Scenario: Publication du plugin déclenchée par le seul événement de release
- **GIVEN** une copie du dépôt où le workflow du plugin n'a que `release: published` pour déclencheur
- **WHEN** le gate est exécuté sur cette copie
- **THEN** il échoue

#### Scenario: Release nominale
- **GIVEN** une release créée par le workflow de release
- **WHEN** le workflow se termine
- **THEN** la release contient l'archive du plugin et sa somme, et le workflow est vert

#### Scenario: Plugin absent après publication
- **GIVEN** une release dont l'archive du plugin n'a pas été attachée
- **WHEN** le contrôle final du workflow de release s'exécute
- **THEN** le workflow échoue en nommant l'asset manquant

### Requirement: La publication du plugin se rejoue pour une release existante
Le workflow de publication du plugin SHALL pouvoir être lancé à la demande pour un tag existant. Il SHALL vérifier que le tag désigne le commit cible de la release et SHALL refuser un tag déplacé. Il SHALL NOT remplacer un asset déjà présent.

#### Scenario: Rejeu sur une release sans plugin
- **GIVEN** une release existante sans archive du plugin
- **WHEN** le workflow du plugin est lancé à la demande avec son tag
- **THEN** l'archive et sa somme sont attachées à cette release

#### Scenario: Tag déplacé
- **GIVEN** un tag qui ne désigne plus le commit cible de la release
- **WHEN** le workflow du plugin est lancé avec ce tag
- **THEN** il échoue avant toute construction

#### Scenario: Asset déjà présent
- **GIVEN** une release qui contient déjà l'archive du plugin
- **WHEN** le workflow du plugin est relancé pour le même tag
- **THEN** il échoue sans remplacer l'asset
