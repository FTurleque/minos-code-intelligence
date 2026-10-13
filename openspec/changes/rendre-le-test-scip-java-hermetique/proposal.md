# Proposal

## Why

**AUD-TST-18** (faible, sprint 6 de l'audit du 10 octobre 2026 ; confirmé par lecture, non rejoué). Un test de `minos-provider-scip` dépend de la variable `JAVA_HOME` **de l'hôte** sous Windows :

- `ManagedScipProviderRuntimeManagerTest.java:45-62` : dans une branche `if (CommandLocator.isWindows())`, le test appelle `ScipJavaProcessPlanFactory.create` dans la JVM de test ;
- `ScipJavaProcessPlanFactory.java:87` appelle `requireProjectJdk()`, qui lit `System.getenv("JAVA_HOME")` (`:298-301`) et lève « JAVA_HOME is not set ».

Sur un poste Windows sans `JAVA_HOME`, `mvnw verify` échoue sur ce module et saute les neuf modules suivants du reactor ; en CI, `setup-java` pose la variable et le défaut est invisible. La règle des tests du dépôt (`.claude/rules/tests-java.md`) interdit les deux formes en cause : dépendre du `JAVA_HOME` de l'hôte, et garder un test propre à une plateforme derrière un `if` au lieu de le compter comme sauté.

Le même module contient déjà le modèle sain : `ScipJavaProcessPlanFactoryToolchainTest` exécute la fabrique dans une JVM fille dont il pose lui-même l'environnement (`ChildJvm.java:57`).

## What Changes

- **Le test est scindé** : la partie « résolution par Coursier » reste commune ; la partie Windows devient un test `@EnabledOnOs(OS.WINDOWS)` qui pose lui-même l'environnement du processus qu'il observe.
- **Inventaire** des autres tests du module qui atteindraient, dans la JVM de test, un code lisant `JAVA_HOME` (second site de lecture : `ManagedScipProviderRuntimeManager.java:241`).
- **Garde durable** (selon décision D2) : les tests du module s'exécutent sans le `JAVA_HOME` de l'hôte, en CI comme sur un poste.
- **Le refus reste prouvé** : sans JDK de projet, le plan scip-java est refusé avant toute préparation.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `installation-des-providers` : trois exigences ajoutées (tests du provider SCIP indépendants du `JAVA_HOME` de l'hôte ; plan scip-java refusé sans JDK de projet avant toute préparation ; test propre à Windows compté comme sauté ailleurs).

## Hors périmètre

- Changer la façon dont la production trouve le JDK du projet (`JAVA_HOME` reste la source) : le défaut est dans le test.
- Refondre les constructeurs de `ScipJavaProcessPlanFactory` (trois constructeurs publics au HEAD) : question d'architecture distincte, liée à l'ADR 0045 ; voir `design.md` (D1, option B).
- Poser `JAVA_HOME` dans la configuration surefire : écarté, cela masquerait le défaut.
- Les autres dépendances à l'environnement de l'hôte dans d'autres modules : non relevées par ce constat.
- Les planchers de couverture du module (changement `borner-la-couverture-globale-et-du-coeur`).

## Impact

- **Modules du reactor** : `minos-provider-scip` (tests ; `pom.xml` du module si D2 est retenue). Aucun code de production dans l'option recommandée.
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune. Le message de refus « JAVA_HOME is not set » est inchangé.
- **ADR** : aucun dans l'option recommandée. L'option B de D1 demanderait une note de mise en œuvre de l'ADR 0045.
- **Gates à rejouer** : `python scripts/remediation/check-p0-p2.py` (`:213` lit `ScipJavaProcessPlanFactory.java`, `:334-339` y exige deux expressions et un plancher de classe) ; `python scripts/remediation/check-post-mne.py` (`:90`, `:171`) ; `python scripts/quality/check-polyglot-provider-consistency.py` ; scope JaCoCo `m24-polyglot-provider-platform` (plancher de classe de la fabrique : 0,35 / 0,15 ; rouge sur poste Windows hors régression, à comparer à `develop`).
- **Plateformes** : le défaut n'apparaît que sous Windows ; la garde vaut sur Windows et sur Linux.
