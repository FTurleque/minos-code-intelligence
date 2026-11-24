## ADDED Requirements

### Requirement: Un document n'est décrit par les symboles de son fichier qu'une fois par requête et sans table gardée
Pour décrire un document, le service d'exploration SHALL lire son symbole par identifiant ; il SHALL NOT lire les symboles de son fichier pour un document de symbole ou de fragment dont le symbole est trouvé. Un document de fichier SHALL lire les symboles de son fichier au plus une fois par requête. Le service SHALL NOT garder ce qu'il a lu au-delà de la requête. Les pages rendues SHALL rester identiques.

#### Scenario: Page mêlée de symboles, fragments et fichiers
- **GIVEN** un index aligné sur le snapshot actif, dont les documents de symbole, de fragment et de fichier décrivent trois fichiers, et une vue dont le lecteur compte ses appels
- **WHEN** `./mvnw -B -ntp -pl minos-application -am test` exécute le test qui lit toutes les pages de documents
- **THEN** les symboles d'un fichier sont lus trois fois, une par document de fichier, et jamais pour un document de symbole ou de fragment
- **AND** langage et module de chaque document sont ceux rendus avant le changement

#### Scenario: Symbole introuvable
- **GIVEN** un document de fragment dont le symbole n'est pas dans la vue
- **WHEN** la page qui le contient est lue
- **THEN** ses champs de symbole sont absents et son langage et son module viennent du symbole de plus petite clé de son fichier

#### Scenario: Vue évincée après la page
- **GIVEN** une page servie, puis l'éviction de la vue par le store
- **WHEN** aucune requête n'est en cours et une collecte du ramasse-miettes a eu lieu
- **THEN** aucun symbole de la vue évincée n'est joignable depuis le service

### Requirement: Le temps d'une page de documents est mesurable sur des fichiers à nombreux symboles
Une mesure rejouable SHALL donner, sur des index synthétiques dont les fichiers portent de 20 à 5 000 symboles, le temps médian et p95 de la page filtrée la plus lente, la durée d'un parcours complet, le temps de construction de la vue et le nombre de lectures des symboles d'un fichier. Elle SHALL écrire son relevé sous `target/`, sans chemin absolu. Elle SHALL NOT s'exécuter sans sa propriété système ni affirmer de durée dans le build.

#### Scenario: Mesure demandée
- **GIVEN** le module `minos-application` construit
- **WHEN** `./mvnw -B -ntp -pl minos-application -am test "-Dtest=SemanticCorpusPageMeasurementTest" "-Dminos.explorer.page.measure=true" "-Dsurefire.failIfNoSpecifiedTests=false"` est exécuté
- **THEN** `minos-application/target/explorer-page-measurements.md` contient une ligne par fixture avec ces mesures
- **AND** le relevé ne contient aucun chemin absolu

#### Scenario: Mesure absente du build
- **GIVEN** `./mvnw -B -ntp -pl minos-application -am test` sans la propriété `minos.explorer.page.measure`
- **WHEN** le rapport Surefire est lu
- **THEN** la classe de mesure y figure comme sautée et aucun relevé n'est écrit
