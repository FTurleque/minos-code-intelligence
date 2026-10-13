## ADDED Requirements

### Requirement: Le jar ombré conserve la notice de chaque dépendance redistribuée
Le jar ombré SHALL contenir un `META-INF/NOTICE` qui agrège le contenu des fichiers de notice de toutes les dépendances qu'il embarque. Aucune notice SHALL NOT être écrasée par une autre. Le fichier SHALL NOT contenir d'en-tête qui attribue MINOS à un tiers.

#### Scenario: Notices de deux dépendances homonymes
- **GIVEN** le jar ombré produit par `./mvnw -B -ntp -pl minos-app -am verify`
- **WHEN** le test d'intégration lit `META-INF/NOTICE`
- **THEN** le fichier contient le texte de notice de chaque dépendance qui en fournit une, dont les deux lignes de Jackson

#### Scenario: En-tête d'attribution absent
- **GIVEN** le même jar
- **WHEN** le test recherche un en-tête « developed at The Apache Software Foundation » en tête de `META-INF/NOTICE`
- **THEN** il n'en trouve pas

#### Scenario: Dépendance ajoutée sans notice conservée
- **GIVEN** une dépendance d'exécution Apache-2.0 qui fournit un fichier de notice
- **WHEN** elle est ajoutée au jar et le test d'intégration est rejoué
- **THEN** le test échoue si son texte de notice est absent du jar

### Requirement: Les textes de licence des dépendances ne sont ni supprimés ni écrasés
L'ombrage SHALL NOT retirer les fichiers de licence des dépendances. Quand plusieurs dépendances fournissent un fichier de licence de même nom, le jar SHALL conserver chacun sous un chemin qui identifie la dépendance.

#### Scenario: Licences présentes
- **GIVEN** le jar ombré
- **WHEN** le test d'intégration énumère les fichiers de licence
- **THEN** il en trouve au moins un par dépendance qui en fournit un, chacun non vide

### Requirement: Les images Docker livrent les notices et licences du jar
Chaque image Docker qui embarque le jar SHALL contenir les notices et licences du jar dans un répertoire documenté, lisible sans exécuter MINOS. La construction SHALL échouer si l'extraction ne produit aucun fichier.

#### Scenario: Notices présentes dans l'image
- **GIVEN** l'image de release construite par la qualification
- **WHEN** le répertoire des licences est listé dans le conteneur
- **THEN** il contient un fichier de notices non vide, identique à celui du jar

#### Scenario: Extraction vide
- **GIVEN** une construction dont le jar ne contient pas de notice
- **WHEN** l'étape d'extraction s'exécute
- **THEN** la construction échoue
