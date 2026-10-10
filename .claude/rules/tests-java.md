---
paths:
  - "**/src/test/**/*.java"
  - "minos-intellij/src/test/**"
---

# Tests Java

- **Rouge avant correctif** : écrire le test qui échoue, noter la sortie rouge (dans le message de commit ou le suivi), puis corriger. Les tests sont dans la **même tâche** que le code, jamais dans une tâche finale.
- **Pas de `Thread.sleep` de synchronisation** dans un test de course ; tout nouveau test de concurrence est rejoué **50 fois** (un seul échec est bloquant : le test est réécrit, pas retenté).
- **Plateforme** : un test propre à une plateforme porte `@EnabledOnOs` et se compte comme sauté ailleurs. Un confinement indisponible (bubblewrap, cgroup v2, AppContainer) **échoue** en CI (`-Dminos.sandbox.tests.required=true`, `SandboxTestSupport`) au lieu d'être sauté en silence : pas de `if (plateforme) return;`.
- **Goldens de caractérisation** (`minos-app/src/test/resources/characterization/`) : indépendants de l'hôte, jamais régénérés sans justification écrite ; un changement additif voulu est expliqué dans le commit.
- **Journaux** : capturer par `LogCapture` (`minos-engine/src/test/java/com/minos/testsupport/`) ; vérifier l'absence de chemin absolu et de cause.
- **Environnement** : ne pas dépendre du `PATH`, de `JAVA_HOME`, de la langue ou du fuseau de l'hôte ; un test de processus fils lit l'environnement qu'il a lui-même posé.
- **Couverture** : SonarCloud juge le nouveau code **module par module** (80 %). Une classe testée seulement depuis un autre module apparaît non couverte dans le sien : ajouter des tests unitaires dans le module qui la porte. Scopes JaCoCo ciblés : `scripts/quality/check-jacoco.py`.
- **Garde-fous de test** : `DuplicationGuardTest` et `JsonEscapeGuardTest` citent des chemins de classes ; un déplacement les met à jour. Le baseline SpotBugs est indexé par `instanceHash` (dépend du nom de classe) : le rafraîchir après un déplacement.
- Plugin IntelliJ : JUnit simple sans fixture de plateforme ; extraire la logique testable dans des classes qui ne dépendent pas de l'IDE.
