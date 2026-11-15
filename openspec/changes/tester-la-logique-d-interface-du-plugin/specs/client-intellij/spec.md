# Spec Delta

## ADDED Requirements

### Requirement: La présentation des réponses du CLI par le plugin est testée hors des classes d'interface
Les textes et listes que le plugin tire d'une réponse JSON du CLI (état de connexion, résumé d'architecture et de module, emplacements navigables, champ requis d'une action avancée) et l'analyse des réglages saisis SHALL être calculés par des classes sans type `com.intellij` ni `javax.swing`, testées en JUnit simple. Une réponse sans le champ attendu SHALL donner le texte de repli ou l'erreur de protocole existants, jamais une exception non gérée.

#### Scenario: Emplacements collectés dans une réponse imbriquée
- **GIVEN** une réponse JSON du CLI qui contient deux emplacements à des profondeurs différentes et un objet qui n'en est pas un
- **WHEN** la logique extraite collecte les emplacements
- **THEN** elle rend les deux emplacements, dans l'ordre du document, avec leur chemin d'origine
- **AND** aucun composant Swing ni projet n'est instancié

#### Scenario: Champ absent
- **GIVEN** une réponse d'état sans le champ lu par le résumé
- **WHEN** le texte est calculé
- **THEN** il contient le texte de repli existant

#### Scenario: Champ requis absent d'une action avancée
- **GIVEN** une réponse du CLI sans le champ exigé par une action avancée
- **WHEN** la logique extraite le lit
- **THEN** elle lève l'erreur de protocole existante, qui nomme le champ

#### Scenario: Réglage hors bornes ou non numérique
- **GIVEN** une valeur de réglage saisie au-delà du maximum, puis une valeur non numérique
- **WHEN** la logique extraite l'analyse
- **THEN** elle rend la valeur de repli dans les deux cas

#### Scenario: Réglage saisi avec des espaces
- **GIVEN** une valeur de réglage dans les bornes, saisie entourée d'espaces
- **WHEN** la logique extraite l'analyse
- **THEN** elle rend la valeur saisie

#### Scenario: Dépendance à l'interface réintroduite
- **GIVEN** une classe de présentation extraite qui importe un type `com.intellij` ou `javax.swing`
- **WHEN** `./gradlew --no-daemon test` est exécuté
- **THEN** le test de garde des classes sans plateforme échoue en nommant la classe

### Requirement: Les planchers de couverture du plugin suivent sa mesure
Les planchers de lignes et de branches déclarés dans `build.gradle.kts`, pour le plugin et pour le paquet `protocol`, SHALL valoir la mesure Linux qui les fonde moins deux points, arrondie au centième inférieur ; la mesure, sa date et son système SHALL être écrits à côté. Un changement qui ajoute des tests au plugin SHALL relever les planchers dans le même changement. Un plancher SHALL NOT être abaissé sans raison écrite dans le suivi de l'audit en cours.

#### Scenario: Tests de présentation supprimés
- **GIVEN** une copie du plugin où les classes de test de la présentation extraite sont supprimées
- **WHEN** `./gradlew --no-daemon test jacocoTestCoverageVerification` est exécuté sous Linux
- **THEN** le build échoue en nommant la règle violée, la mesure et le plancher

#### Scenario: Dépôt réel après le changement
- **GIVEN** le plugin au HEAD du changement
- **WHEN** la même commande est exécutée sous Linux
- **THEN** elle réussit, et les planchers du plugin sont supérieurs à 0,33

#### Scenario: Mesure non consignée
- **GIVEN** `minos-intellij/build.gradle.kts`
- **WHEN** on lit le commentaire qui précède les planchers
- **THEN** il donne les décomptes couverts sur total, la date et le système de la mesure qui fonde chaque plancher
