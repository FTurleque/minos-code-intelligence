## MODIFIED Requirements

### Requirement: Une seule tâche d'arrière-plan sert toutes les actions du plugin
Les actions de l'éditeur, les actions d'analyse avancée et de recherche sémantique et le panneau d'outils SHALL exécuter leurs opérations par la même classe de tâche, sans `catch (Throwable)` dans le code des actions.

#### Scenario: Aucune capture générique dans le plugin
- **WHEN** `grep -rn "catch (Throwable" minos-intellij/src/main/java` est exécuté
- **THEN** il ne renvoie aucune ligne

#### Scenario: Pas de cycle de packages introduit
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté
- **THEN** la règle A8 rapporte 0 cycle, plugin compris
