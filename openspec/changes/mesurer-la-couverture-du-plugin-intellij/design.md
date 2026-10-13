# Design

## Context

Sprint 6 de l'audit du 10 octobre 2026 : **AUD-TST-05**. HEAD analysé : `949e1569` (`develop`). `PLG` = `minos-intellij/src/main/java/com/minos/intellij`. Le plugin est en Gradle, Java 21, hors du reactor Maven, client externe du protocole CLI.

Ordre dans le sprint 6 : indépendant des cinq autres changements du sprint ; à ordonner avec le changement actif `verrouiller-les-dependances-gradle-du-plugin` (D5).

## État vérifié au HEAD

### AUD-TST-05 — confirmé, décomptes changés

- `minos-intellij/build.gradle.kts:4-12` : greffons `java`, IntelliJ Platform, SpotBugs (`:10`), PIT (`:11`) ; aucun `jacoco`. `:70-72` : `useJUnitPlatform()`. `:89-102` : configuration PIT.
- 25 classes de production, 3 656 lignes ; 16 fichiers de test, qui couvrent `protocol/`, `navigation/MinosLocation`, `task/MinosBackgroundTask` et `ui/ArchitectureGraphPanel`.
- 10 classes sans référence dans un test, 1 382 lignes (38 %) : `PLG/ui/MinosToolWindowPanel.java`, `PLG/actions/MinosM21Actions.java`, `PLG/actions/MinosEditorActions.java`, `PLG/service/MinosProjectService.java`, `PLG/settings/MinosSettingsConfigurable.java`, `PLG/settings/MinosSettingsState.java`, `PLG/ui/MinosUiController.java`, `PLG/navigation/MinosNavigation.java`, `PLG/protocol/MinosRegistryNotice.java`, `PLG/ui/MinosToolWindowFactory.java`.
- `PLG/service/MinosProjectService.java` : importe `Editor`, `Project`, `PsiFile` (`:6-14`), mais porte de la logique pure — `score(JsonObject, String, int)` (`:83`), `relative(Path, Path)` (`:135`), `nullableString` (`:144`).
- `PLG/protocol/MinosRegistryNotice.java:19-24` : appelle `NotificationGroupManager` et reçoit un `Project`.
- `.github/workflows/intellij-plugin.yml:87` : `./gradlew --no-daemon test buildPlugin verifyPluginProjectConfiguration verifyPluginStructure` (Linux) ; `:135` : `./gradlew.bat --no-daemon test` (Windows) ; `:100-103` : `minos-intellij/build/reports/**` est déjà publié en artefact.
- Check exigé : `IntelliJ plugin (gate)` (`.github/required-checks.json`). Version JaCoCo du reactor : 0.8.15 (`pom.xml:66`).
- Aucun `gradle.lockfile` ni `verification-metadata.xml` dans `minos-intellij/` au HEAD ; le changement actif `verrouiller-les-dependances-gradle-du-plugin` les introduit.

**Non mesuré** : la couverture réelle du plugin (aucun Gradle exécuté). **Non vérifié** : que SonarCloud n'analyse pas le plugin (l'analyse est lancée par Maven, `pr-ci.yml:416-420` ; la configuration côté serveur n'a pas été lue).

### Écarts avec l'action proposée par l'audit

1. **`BasePlatformTestCase` contredit la règle des tests du dépôt** (`.claude/rules/tests-java.md` : « JUnit simple sans fixture de plateforme ; extraire la logique testable »). L'extraction est retenue, la fixture non (D4).
2. **`MinosRegistryNotice` est dans `protocol/` mais dépend de la plateforme** (`:19-24`) : elle n'est pas testable telle quelle ; seule la composition de son message s'extrait. Le fait fourni par la contre-vérification (« dans `protocol/`, donc testable ») est à corriger sur ce point.
3. **Un rapport sans plancher ne signale aucune érosion** : l'audit demande un rapport publié, pas un seuil. Sans seuil, l'impact décrit par le constat demeure (D2).
4. **JaCoCo ajoute des artefacts résolus** (agent, tâche de rapport) : le verrou du changement `verrouiller-les-dependances-gradle-du-plugin` en dépend (D5).
5. **La couverture diffère entre Linux et Windows** : le lanceur à propriété forte ne s'exécute que sous Windows. Un plancher unique jugé sur les deux jobs serait faux sur l'un (D3).
6. **`check-current-docs.py:199-204` exige des chaînes de `intellij-plugin.yml`** (`buildPlugin`, `verifyPluginProjectConfiguration`, `verifyPluginStructure`, `verifyPlugin`, « Checkout exact candidate », `branches: [main, develop]`) : la ligne de commande du workflow se complète, elle ne se réécrit pas.

## Goals / Non-Goals

**Goals** : la couverture du plugin est mesurée à chaque PR qui le touche ; une érosion fait échouer un check exigé ; la logique d'orchestration du plugin a des tests ; ce qui reste non couvert est nommé.

**Non-Goals** : atteindre un taux donné ; tester l'interface Swing ; intégrer le plugin à SonarCloud.

## Decisions

### D1. Mesure par le greffon JaCoCo de Gradle

Greffon `jacoco` (fourni par Gradle, aucun greffon tiers), version de l'outil alignée sur celle du reactor ; rapport XML et HTML produit après `test`. La tâche de rapport s'ajoute à la commande existante du workflow, sur les deux jobs. Le rapport est publié par l'envoi d'artefacts existant.

### D2. Plancher dans le build du plugin — **décision en attente du propriétaire**

Recommandation : règle de vérification JaCoCo dans `build.gradle.kts` (lignes et branches du plugin entier, plus un plancher propre à `protocol/`, qui porte le lancement de processus), valeur = mesure de la tâche 1.1 moins 2 points, exécutée par le job Linux ; l'échec remonte par `IntelliJ plugin (gate)`. Alternatives : (a) rapport publié sans seuil — ne ferme pas le constat ; (b) seuil lu par `scripts/quality/check-jacoco.py` — ce gate tourne dans `Verify`, où le plugin n'est pas construit : il faudrait le rejouer dans un second job, contre `check-single-execution.py`.

### D3. Linux juge, Windows publie

Le plancher global est jugé sur le job Linux. Le job Windows publie son rapport ; un plancher de classe pour `MinosStrongProcessLauncher` y est ajouté seulement si la mesure 1.1 montre une couverture stable. Aucun plancher n'est présenté comme tenu sur un système où il n'est pas évalué.

### D4. Extraction, pas de fixture de plateforme — **décision en attente du propriétaire**

Recommandation : garder la règle. Sont extraits vers des classes sans type `com.intellij.*`, testées en JUnit simple : le choix du symbole candidat et son score (`MinosProjectService`), le texte de la notice de registre, et ce que la tâche 1.3 relèvera de logique indépendante de l'IDE dans `MinosM21Actions`, `MinosEditorActions` et `MinosSettingsState` (ces trois classes n'ont pas été lues en détail pendant l'analyse). Restent sans test unitaire, **nommément** : `MinosToolWindowPanel`, `MinosToolWindowFactory`, `MinosSettingsConfigurable`, `MinosUiController`, `MinosNavigation` et les enveloppes d'action. Alternative : autoriser `BasePlatformTestCase` pour la fenêtre d'outils, ce qui amende la règle des tests et allonge le job du plugin.

### D5. Ordre avec le verrou Gradle

`verrouiller-les-dependances-gradle-du-plugin` passe **d'abord** ; ce changement régénère ensuite le verrou (et les sommes, si elles existent) dans la tâche qui ajoute JaCoCo. Si l'ordre est inversé, le verrou naît avec JaCoCo et rien n'est à régénérer. Les deux ne se développent pas en parallèle sur `build.gradle.kts`.

### D6. Les noms publics du plugin ne bougent pas

Les classes d'action et de service référencées par `plugin.xml` gardent leur nom et leur package ; l'extraction crée des classes nouvelles à côté. Aucun identifiant d'action ne change.

## Direction des dépendances

Sans objet pour le reactor (ADR 0022). Dans le plugin : les classes extraites ne dépendent d'aucun type de la plateforme ; les classes d'IDE dépendent d'elles, jamais l'inverse. Aucune dépendance `com.minos:*`.

## Qualification des capacités

- Mesure de couverture du plugin : **qualifiée** sur Linux (plancher), **partielle** sur Windows (rapport publié, plancher éventuel sur une classe).
- Couverture de l'interface (fenêtre d'outils, réglages) : **non supportée** par des tests unitaires, déclarée.

## Windows et Linux

Les deux jobs du workflow produisent un rapport. Les tests du lanceur Windows ne tournent que sous Windows ; ils n'entrent pas dans le plancher Linux.

## Risks / Trade-offs

- Un plancher trop proche de la mesure bloque un check exigé sur une PR qui ajoute de l'interface : marge de 2 points, et plancher séparé pour `protocol/`.
- L'extraction touche des classes sans test : chaque extraction commence par un test de caractérisation de la logique déplacée.
- L'agent JaCoCo et l'instrumentation de l'IntelliJ Platform Gradle Plugin peuvent interférer (classes de la plateforme instrumentées, durée) : à mesurer en 1.1 ; le rapport se limite à `com.minos.intellij.*`.
- Le plugin étant vraisemblablement hors SonarCloud, le seuil de 80 % sur le nouveau code ne s'applique pas aux classes extraites ; si la tâche 1.2 montre le contraire, leurs tests vivent déjà dans le plugin.
