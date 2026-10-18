## ADDED Requirements

### Requirement: L'ouverture confinée ne dépend d'aucun état statique modifiable
La primitive d'ouverture confinée (`ConfinedFileOpener`) SHALL NOT déclarer de champ statique non `final`. Un traitement inséré entre la dernière validation et l'ouverture SHALL n'être fourni que par l'appelant, pour son seul appel, par une entrée non publique ; les entrées publiques SHALL n'en exécuter aucun. Un fichier remplacé par un lien entre la validation et l'ouverture SHALL rester refusé.

#### Scenario: Aucun champ statique modifiable
- **GIVEN** la classe `ConfinedFileOpener` chargée par un test de `minos-engine`
- **WHEN** ses champs déclarés sont énumérés par réflexion
- **THEN** aucun n'est à la fois `static` et non `final`

#### Scenario: Remplacement par un lien juste avant l'ouverture
- **GIVEN** un fichier régulier validé, et un traitement passé par l'appelant qui le remplace par un lien symbolique
- **WHEN** l'ouverture confinée est appelée avec ce traitement
- **THEN** l'ouverture échoue par un refus de confinement et aucun octet de la cible du lien n'est lu

#### Scenario: Un appel n'influence pas le suivant
- **GIVEN** une ouverture qui vient d'être faite avec un traitement de remplacement
- **WHEN** une entrée publique ouvre ensuite un autre fichier régulier, sans aucune remise à zéro
- **THEN** le traitement n'est pas exécuté et le fichier est ouvert
