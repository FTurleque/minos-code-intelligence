## ADDED Requirements

### Requirement: L'installation d'un outil échoue fermée quand le déplacement atomique manque
L'installation d'un outil de provider SHALL publier ses fichiers et ses répertoires par un déplacement atomique et SHALL échouer, sans copie ni déplacement non atomique de repli, quand le système de fichiers ne le permet pas. L'arbre précédent SHALL être restauré quand le déplacement du nouvel arbre échoue.

#### Scenario: Déplacement atomique refusé
- **GIVEN** un déplaceur de répertoire qui signale l'absence de déplacement atomique
- **WHEN** `./mvnw -B -ntp -pl minos-provider-scip -am test` exécute le test d'installation d'un arbre
- **THEN** l'installation échoue, aucune copie n'est faite et la destination est inchangée

#### Scenario: Échec du déplacement du nouvel arbre
- **GIVEN** un arbre installé et un déplacement du nouvel arbre qui échoue
- **WHEN** l'échange est exécuté
- **THEN** l'arbre précédent est à sa place et l'échec est propagé
