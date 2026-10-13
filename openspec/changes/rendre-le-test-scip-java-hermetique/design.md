# Design

## Context

Sprint 6 de l'audit du 10 octobre 2026 : **AUD-TST-18**. HEAD analysé : `949e1569` (`develop`). `SCIP` = `minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime`, `SCIPT` = `minos-provider-scip/src/test/java/com/minos/adapter/scip/runtime`.

Ordre dans le sprint 6 : indépendant des autres changements. `borner-la-couverture-globale-et-du-coeur` (recommandé d'abord) rattache `ManagedScipProviderRuntimeManager` au scope `m24-polyglot-provider-platform` : si les deux se suivent, la mesure de couverture de l'un tient compte de l'autre.

## État vérifié au HEAD

### AUD-TST-18 — confirmé par lecture, **non rejoué**

- `SCIPT/install/ManagedScipProviderRuntimeManagerTest.java:35` : un seul test, `fetchesPinnedScipJavaWithoutLaunchingItAndPropagatesKotlinToTheWindowsRuntime`. `:45` : `if (CommandLocator.isWindows())` ; `:61-62` : `new ScipJavaProcessPlanFactory(coursier, …, runner, maven).create(…)` dans la JVM de test.
- `SCIP/plan/ScipJavaProcessPlanFactory.java:87` : `requireProjectJdk()` ; `:298-301` : `System.getenv("JAVA_HOME")`, exception si absent ou vide ; `:306` : exception si le dossier ne contient pas `javac`.
- Second site de lecture : `SCIP/install/ManagedScipProviderRuntimeManager.java:241` (diagnostic « JAVA_HOME is not set to the project JDK »).
- Constructeurs publics de la fabrique : `ScipJavaProcessPlanFactory.java:48`, `:52`, `:64`.
- Modèle hermétique existant : `SCIPT/install/ScipJavaProcessPlanFactoryToolchainTest.java` exécute la fabrique dans une JVM fille ; `SCIPT/install/ChildJvm.java:57` y pose `JAVA_HOME` à partir de `java.home`, après avoir vidé l'environnement. Ses tests `:72` (variable non posée) et `:62` (JDK sans `javac`) prouvent déjà le refus ; `:175` (`@EnabledOnOs(OS.WINDOWS)`) construit déjà le plan Windows avec le Maven géré.
- Gates littéraux sur la fabrique : `scripts/remediation/check-p0-p2.py:213` (lecture), `:334-337` (deux expressions exigées), `:338-339` (plancher de classe exigé dans `check-jacoco.py`) ; `scripts/remediation/check-post-mne.py:90`, `:171`.
- Surefire : version 3.6.0 (`pom.xml:69`).

**Non vérifié** : l'échec n'a pas été reproduit (aucun Maven exécuté) ; le recouvrement exact entre la branche Windows du test et `ScipJavaProcessPlanFactoryToolchainTest:175` (propagation de `-Language KOTLIN`) n'a pas été comparé ligne à ligne ; la présence d'autres tests du module atteignant `getenv` dans la JVM de test n'a pas été inventoriée.

### Écarts avec l'action proposée par l'audit

1. **Injecter un fournisseur de variables ajoute un quatrième constructeur public** à une classe qui en a déjà trois. L'ADR 0045 (« constructeur unique et point d'entrée nommé ») vise les classes de surface ; que la fabrique en relève ou non, en ajouter un va à rebours de sa direction. Ce n'est pas un détail de test (D1).
2. **Le défaut est dans le test, pas dans la production.** Lire `JAVA_HOME` est le contrat du provider ; le module sait déjà tester ce contrat sans dépendre de l'hôte.
3. **Le test fautif a une seconde forme interdite** : `if (CommandLocator.isWindows())` fait passer la moitié Windows pour exécutée sous Linux. La règle demande `@EnabledOnOs` et un test compté comme sauté.
4. **Le repli « poser `JAVA_HOME` dans surefire » masque le défaut** et le reproduirait au prochain test. L'inverse est utile : retirer la variable de l'environnement des tests du module rend la règle vérifiable en CI (D2).
5. **Un second site lit `JAVA_HOME`** (`ManagedScipProviderRuntimeManager.java:241`) : l'audit ne le cite pas.

## Goals / Non-Goals

**Goals** : `mvnw verify` ne dépend plus du `JAVA_HOME` de l'hôte pour ce module ; la moitié Windows du test est comptée comme sautée sous Linux ; le refus sans JDK reste prouvé ; la régression ne peut pas revenir sans signal.

**Non-Goals** : changer la découverte du JDK en production ; refondre les constructeurs de la fabrique.

## Decisions

### D1. Où corriger — **décision en attente du propriétaire**

**Option A (recommandée) : corriger le test seul.** La partie commune (commande de résolution Coursier) reste un test sans condition. La partie Windows devient un test `@EnabledOnOs(OS.WINDOWS)` exécuté par `ChildJvm`, environnement posé par le test, sur le modèle de `ScipJavaProcessPlanFactoryToolchainTest` ; si la tâche 1.1 montre qu'il double le test `:175`, seule l'affirmation manquante (`-Language KOTLIN`) y est ajoutée et la branche est supprimée. Aucun code de production, aucun gate littéral touché, aucune question d'ADR.

**Option B : injecter la lecture d'environnement** dans `ScipJavaProcessPlanFactory` et `ManagedScipProviderRuntimeManager`. Rend la fabrique testable dans la JVM de test, mais ajoute un constructeur (ou impose de les réduire à un seul avec point d'entrée nommé, selon l'ADR 0045, en modifiant les appelants dans le même commit), touche une classe gardée par deux gates littéraux et compte comme code neuf pour SonarCloud.

**Écartée** : `JAVA_HOME` posé par surefire (écart 4).

### D2. Garde durable — **décision en attente du propriétaire**

Recommandation : retirer `JAVA_HOME` de l'environnement des tests de `minos-provider-scip` par la configuration surefire du module (`excludedEnvironmentVariables`, à confirmer avec surefire 3.6.0 en tâche 1.2). Un test qui lirait le `JAVA_HOME` de l'hôte échouerait alors **partout**, y compris en CI. Les JVM filles ne sont pas touchées : `ChildJvm` pose sa propre valeur. Alternative : ne rien configurer et s'en remettre à la preuve locale de la tâche 2.3 (la régression redeviendrait invisible en CI). À écarter si la tâche 1.2 montre qu'un test légitime du module a besoin du `JAVA_HOME` de l'hôte.

### D3. Le refus n'est pas affaibli

Les tests `:62` et `:72` de `ScipJavaProcessPlanFactoryToolchainTest` restent la preuve du refus. Le message « scip-java requires a project JDK: JAVA_HOME is not set » est inchangé.

## Direction des dépendances

Tests de `minos-provider-scip` seulement (option A). Aucune arête nouvelle. Option B : changement interne à l'adaptateur, sans arête nouvelle non plus ; `application ↛ adaptateurs` inchangé.

## Qualification des capacités

Aucune capacité nouvelle. Provider scip-java : qualification inchangée. Le build local sous Windows sans `JAVA_HOME` passe de **non supporté de fait** à supporté pour ce module, sous réserve de la preuve 2.3.

## Windows et Linux

Le défaut n'apparaît que sous Windows (branche conditionnelle). Après correction : sous Linux le test Windows est **compté comme sauté** ; sous Windows il s'exécute avec un environnement posé. La garde D2 s'applique aux deux systèmes.

## Risks / Trade-offs

- D2 peut révéler d'autres tests dépendants de l'hôte : c'est l'objet de l'inventaire 1.2, et chaque cas se corrige de la même façon.
- Une JVM fille coûte plus cher qu'un appel direct : un test de plus, négligeable devant la durée du module.
- Scope `m24-polyglot-provider-platform` : rouge sur poste Windows hors régression ; toute lecture locale se compare à `develop`. Le plancher de classe de la fabrique (0,35 / 0,15, exigé au caractère près par `check-p0-p2.py`) ne doit pas être touché ; vérifier que déplacer l'exécution dans une JVM fille ne fait pas baisser la couverture mesurée de la fabrique (les JVM filles sont-elles instrumentées ? à relever en 1.1).
