## MODIFIED Requirements

### Requirement: L'empreinte de référence est capturée avant la découverte
Dans l'unique orchestration d'indexation du produit, l'empreinte de référence SHALL être capturée avant la découverte, de sorte qu'une structure apparue entre la capture et la découverte, ou pendant le run, empêche la promotion de la baseline au lieu d'être déclarée couverte sans avoir été indexée.

#### Scenario: Un module apparaît juste après la découverte
- **GIVEN** un projet indexé par le cas d'usage d'indexation
- **WHEN** un module est créé après que la découverte a rendu son résultat et avant la fin du run, puis que le run se termine avec succès
- **THEN** l'empreinte de référence n'est pas promue, un diagnostic signale que l'espace de travail a changé, et l'exécution suivante est une indexation complète

#### Scenario: Arbre stable
- **GIVEN** un projet indexé par le cas d'usage d'indexation
- **WHEN** aucun fichier ne change entre la capture de l'empreinte et la fin du run
- **THEN** l'empreinte de référence est promue comme avant

## ADDED Requirements

### Requirement: Le produit n'a qu'une orchestration d'indexation
La préparation, l'exécution, la reprise, la promotion de l'empreinte, la synchronisation sémantique et la rétention d'une indexation SHALL être coordonnées par un seul cas d'usage de `minos-application`, invocable sans la CLI. La commande `minos index` SHALL lui déléguer. Aucune autre classe de production SHALL NOT coordonner ces étapes.

#### Scenario: Indexation invoquée sans CLI
- **GIVEN** une application MINOS ouverte dans un test, sans classe de `minos-cli`
- **WHEN** le cas d'usage d'indexation est appelé sur un projet enregistré
- **THEN** le run réussit, le snapshot est promu et l'empreinte de référence est publiée

#### Scenario: Sorties de la commande inchangées
- **GIVEN** les goldens de caractérisation de `minos-app`
- **WHEN** `./mvnw -B -ntp -pl minos-app -am verify` est exécuté après la délégation
- **THEN** les sorties de `minos index` sont identiques à l'octet, hors diagnostic additif décidé

#### Scenario: Coordinateur sans appelant retiré
- **GIVEN** le dépôt après le changement
- **WHEN** on recherche `IncrementalIndexingCoordinator` dans les sources de production et de test
- **THEN** la recherche ne retourne aucune ligne

### Requirement: Une baseline d'empreinte illisible est déclarée
Quand la baseline d'empreinte active ne peut pas être lue, l'orchestration SHALL exiger une indexation complète avec la raison `FINGERPRINT_BASELINE_UNREADABLE` et SHALL rendre un diagnostic qui le dit, sans chemin absolu ni cause interne. Elle SHALL NOT traiter la baseline comme absente sans le signaler.

#### Scenario: Baseline illisible
- **GIVEN** un projet dont le magasin d'empreintes lève une erreur d'E/S à la lecture de la baseline active
- **WHEN** l'indexation est exécutée
- **THEN** le plan est complet, sa raison est `FINGERPRINT_BASELINE_UNREADABLE` et le résultat porte un diagnostic de baseline illisible

#### Scenario: Diagnostic sans chemin
- **GIVEN** une erreur de lecture dont le message contient un chemin absolu
- **WHEN** le diagnostic est rendu
- **THEN** il ne contient ni ce chemin ni la racine du projet
