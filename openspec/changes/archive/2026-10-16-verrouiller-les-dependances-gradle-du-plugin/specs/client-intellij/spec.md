## ADDED Requirements

### Requirement: Les dépendances du plugin IntelliJ sont verrouillées et analysées
Le build du plugin SHALL verrouiller, en mode strict, les dépendances de son classpath de compilation, d'exécution, de test et de script de build dans un fichier de verrou versionné. Le gate de vulnérabilités des pull requests SHALL analyser ce verrou. Un gate statique SHALL échouer si le verrou ou son activation disparaît.

#### Scenario: Verrou absent
- **GIVEN** une copie du dépôt sans `minos-intellij/gradle.lockfile`
- **WHEN** le gate du plugin est exécuté sur cette copie
- **THEN** il échoue en nommant le fichier attendu

#### Scenario: Dépendance résolue hors verrou
- **GIVEN** une copie du dépôt où la version d'une dépendance du plugin est modifiée sans régénérer le verrou
- **WHEN** le build du plugin est exécuté
- **THEN** il échoue en nommant la dépendance

#### Scenario: Dépendance vulnérable dans le verrou
- **GIVEN** une copie du dépôt dont le verrou référence une version touchée par un avis connu
- **WHEN** OSV-Scanner est exécuté avec les arguments du job de pull request
- **THEN** l'analyse échoue en nommant la dépendance et l'avis

#### Scenario: Build identique sur les deux systèmes
- **GIVEN** le verrou versionné
- **WHEN** les tests du plugin sont exécutés sous Linux puis sous Windows
- **THEN** les deux builds réussissent sans modifier le verrou

### Requirement: L'intégrité des artefacts résolus par le plugin est vérifiée ou sa limite déclarée
Le build du plugin SHALL vérifier la somme SHA-256 des artefacts qu'il résout d'après des métadonnées versionnées, pour le périmètre retenu par la décision du design. Tout artefact hors de ce périmètre SHALL être nommé dans la documentation de la chaîne d'approvisionnement comme non vérifié.

#### Scenario: Artefact dont la somme diffère
- **GIVEN** des métadonnées de vérification où la somme d'une dépendance a été altérée
- **WHEN** le build du plugin est exécuté
- **THEN** il échoue en nommant l'artefact

#### Scenario: Métadonnées absentes alors que la vérification est retenue
- **GIVEN** une copie du dépôt sans métadonnées de vérification
- **WHEN** le gate du plugin est exécuté sur cette copie
- **THEN** il échoue

#### Scenario: Périmètre déclaré
- **GIVEN** la documentation de la chaîne d'approvisionnement
- **WHEN** elle est relue après ce changement
- **THEN** elle nomme les artefacts vérifiés par somme et ceux qui ne le sont pas
