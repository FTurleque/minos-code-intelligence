---
paths:
  - "minos-intellij/**"
---

# Plugin IntelliJ (`minos-intellij`)

- **Hors du reactor Maven** : Gradle (wrapper `minos-intellij/gradlew`, distribution épinglée par SHA-256), Java 21, IntelliJ Platform 2026.1. Pas de `gradlew` à la racine.
- **Client externe** du protocole CLI `minos-ide` v1 : aucune dépendance `com.minos:*`. Le plugin lance la CLI dans des processus de fond et ne lit aucun artefact interne.
- **Tâches d'arrière-plan** (cible du changement OpenSpec `rendre-la-journalisation-observable`, à appliquer à toute nouvelle tâche) : une seule classe de tâche ; relancer `ProcessCanceledException` et les `Error`, journaliser le reste avec `Logger.getInstance` (pile dans `idea.log`), afficher le type quand le message manque. Pas de `catch (Throwable)` dans les actions.
- **Packages** : aucun cycle (règle A8 inclut le plugin) ; un nouveau package utilitaire est une feuille (rappels en paramètres plutôt que dépendance vers `ui`/`actions`).
- **Lancement de la CLI** : un lanceur `.cmd` reçoit sa chaîne de commande brute dans le même Job Object ; les arguments contenant `"` ou `%` sont refusés avant le démarrage ; aucun interpréteur pour les lanceurs natifs. Le répertoire courant d'un processus est le projet ouvert : ne jamais y résoudre un exécutable.
- **CI** : le workflow `intellij-plugin.yml` s'exécute sur toute PR, calcule la portée (`scripts/ci/plugin-gate.py` : inclut `minos-cli`, `minos-integration-git`, les rendus JSON de `minos-application`, `minos-domain`, les goldens) et `IntelliJ plugin (gate)` est le seul check exigé. Un changement de rendu JSON côté noyau met donc le plugin dans la portée.
- **Tests** : JUnit simple (`./gradlew test` dans `minos-intellij/`), sans fixture de plateforme ; tests de propriété de processus sous Windows. Qualification : `buildPlugin`, `verifyPluginProjectConfiguration`, `verifyPluginStructure`, `verifyPlugin`.
- **Documentation** : `docs/user/intellij-plugin.md` suit le comportement (réglage `minos.cmd`, onglets, diagnostics).
