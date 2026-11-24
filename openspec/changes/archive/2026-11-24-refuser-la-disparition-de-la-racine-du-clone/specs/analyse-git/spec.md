## ADDED Requirements

### Requirement: La mesure du budget d'un clone distant échoue quand sa racine disparaît
La mesure du budget d'un clone distant SHALL échouer quand la racine mesurée disparaît pendant le parcours de son arbre ; elle SHALL NOT rendre une mesure partielle ou nulle comme valide. Seule une entrée située sous la racine qui disparaît pendant le parcours SHALL être ignorée, après avoir été comptée parmi les entrées parcourues. Toute autre erreur d'accès SHALL faire échouer la mesure. Le message d'échec SHALL NOT contenir de chemin absolu.

#### Scenario: Racine absente au moment du parcours
- **GIVEN** un budget de clone dont la racine mesurée n'existe pas
- **WHEN** `./mvnw -B -ntp -pl minos-integration-git -am test "-Dtest=RemoteCloneBudgetTest" "-Dsurefire.failIfNoSpecifiedTests=false"` parcourt cette racine avec le compteur du budget
- **THEN** le parcours échoue par une `IOException`
- **AND** le message de l'exception ne contient pas le chemin absolu de la racine

#### Scenario: Racine qui disparaît à la fin de son listage
- **GIVEN** le compteur d'un budget de clone
- **WHEN** le même test lui signale la fin du listage de la racine mesurée avec une `NoSuchFileException`
- **THEN** le compteur échoue par une `IOException` dont le message ne contient pas de chemin absolu

#### Scenario: Répertoire sous la racine qui disparaît pendant un parcours réel
- **GIVEN** une racine qui contient deux répertoires, et un parcours réel qui supprime le second pendant la visite du premier
- **WHEN** le même test parcourt la racine avec le compteur du budget, sur Windows et sur Linux
- **THEN** le parcours se termine sans exception
- **AND** le répertoire supprimé compte parmi les entrées parcourues, pas parmi les répertoires

#### Scenario: Autre erreur d'accès à une entrée
- **GIVEN** le compteur d'un budget de clone
- **WHEN** le même test lui signale une `AccessDeniedException` sur une entrée sous la racine
- **THEN** le compteur relance cette exception et la mesure échoue

#### Scenario: Arbre stable
- **GIVEN** un arbre de clone qui ne change pas pendant la mesure
- **WHEN** le même test mesure cet arbre
- **THEN** les octets, fichiers et répertoires rendus sont ceux d'avant le changement
