## ADDED Requirements

### Requirement: La couverture des tests du plugin est mesurée et publiée
Le build Gradle du plugin IntelliJ SHALL produire un rapport JaCoCo des tests unitaires limité aux classes `com.minos.intellij`. Le workflow du plugin SHALL produire ce rapport sur Linux et sur Windows et le publier en artefact. L'absence du rapport après les tests SHALL faire échouer le job.

#### Scenario: Rapport produit localement
- **GIVEN** le dossier `minos-intellij`
- **WHEN** `./gradlew --no-daemon test jacocoTestReport` est exécuté
- **THEN** un rapport XML de couverture existe sous `build/reports/jacoco/` et ne contient que des classes `com/minos/intellij/`

#### Scenario: Rapport absent
- **GIVEN** une copie du build où la tâche de rapport est retirée
- **WHEN** la vérification de couverture est exécutée
- **THEN** elle échoue au lieu de réussir sans mesure

### Requirement: Une couverture du plugin sous son plancher est refusée
Le build du plugin SHALL échouer quand la couverture de lignes ou de branches mesurée est sous le plancher déclaré dans `build.gradle.kts`, pour le plugin entier et pour le paquet `protocol`. Cet échec SHALL faire échouer le check exigé du plugin. Un plancher SHALL NOT être présenté comme tenu sur un système où il n'est pas évalué.

#### Scenario: Test supprimé
- **GIVEN** une copie du plugin où une classe de test de `protocol` est supprimée
- **WHEN** `./gradlew --no-daemon test jacocoTestCoverageVerification` est exécuté
- **THEN** le build échoue en nommant la règle violée, la mesure et le plancher

#### Scenario: Dépôt réel
- **GIVEN** le plugin au HEAD du changement
- **WHEN** la même commande est exécutée sous Linux
- **THEN** elle réussit

### Requirement: La logique du plugin est testable sans la plateforme IntelliJ
La logique du plugin qui ne manipule pas l'IDE (choix et score du symbole candidat, texte des notices, interprétation des réponses du CLI) SHALL vivre dans des classes sans type `com.intellij` et SHALL être testée en JUnit simple. Les tests du plugin SHALL NOT exiger une fixture de plateforme. Les classes d'interface laissées sans test unitaire SHALL être nommées dans la documentation du plugin.

#### Scenario: Score du symbole candidat
- **GIVEN** deux symboles renvoyés par le CLI, dont un seul dans le fichier et à la ligne du curseur
- **WHEN** la logique extraite choisit le candidat
- **THEN** elle retient ce symbole, sans instancier de projet ni d'éditeur

#### Scenario: Aucun candidat
- **GIVEN** une réponse du CLI sans symbole correspondant
- **WHEN** la logique extraite choisit le candidat
- **THEN** elle signale l'absence (résultat vide ou erreur de protocole explicite, selon le comportement relevé au HEAD) et ne choisit pas un symbole par défaut

#### Scenario: Dépendance à la plateforme réintroduite
- **GIVEN** une classe de logique extraite qui importe un type `com.intellij`
- **WHEN** `./gradlew --no-daemon test` est exécuté
- **THEN** le test de garde des classes sans plateforme échoue en nommant la classe
