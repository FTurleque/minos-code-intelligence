## ADDED Requirements

### Requirement: Un échec de tâche d'arrière-plan est journalisé et jamais avalé
Toute tâche d'arrière-plan du plugin SHALL laisser passer `ProcessCanceledException` et les `Error`, SHALL journaliser toute autre exception dans `idea.log` avec sa pile et son message, et SHALL transmettre l'échec à l'affichage. Quand l'exception n'a pas de message ou un message vide, l'affichage SHALL nommer le type de l'exception.

#### Scenario: Exception sans message
- **GIVEN** une opération qui lève une `NullPointerException` sans message
- **WHEN** la tâche d'arrière-plan l'exécute
- **THEN** l'enregistreur reçoit l'exception avec sa pile, et le texte affiché contient `NullPointerException`, jamais `Unknown failure` seul

#### Scenario: Exception avec message
- **GIVEN** une opération qui lève une `IllegalStateException("projet absent")`
- **WHEN** la tâche d'arrière-plan l'exécute
- **THEN** l'enregistreur la reçoit avec sa pile et l'affichage contient `projet absent`

#### Scenario: Erreur de la JVM
- **GIVEN** une opération qui lève une `OutOfMemoryError`
- **WHEN** la tâche d'arrière-plan l'exécute
- **THEN** l'erreur est relancée telle quelle, sans être mémorisée comme un échec affichable

#### Scenario: Annulation
- **GIVEN** une opération qui lève une `ProcessCanceledException`
- **WHEN** la tâche d'arrière-plan l'exécute
- **THEN** l'exception est relancée sans journal ni affichage d'erreur

### Requirement: Une seule tâche d'arrière-plan sert toutes les actions du plugin
Les actions de l'éditeur, les actions M21 et le panneau d'outils SHALL exécuter leurs opérations par la même classe de tâche, sans `catch (Throwable)` dans le code des actions.

#### Scenario: Aucune capture générique dans le plugin
- **WHEN** `grep -rn "catch (Throwable" minos-intellij/src/main/java` est exécuté
- **THEN** il ne renvoie aucune ligne

#### Scenario: Pas de cycle de packages introduit
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté
- **THEN** la règle A8 rapporte 0 cycle, plugin compris
