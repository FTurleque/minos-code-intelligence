# tests-lies-et-impact Specification

## Purpose
Garantit que l'analyse d'impact conservatrice et la dérivation des tests liés disent ce qu'elles ne
couvrent pas : un résultat vide ou incomplet n'est jamais présenté comme une preuve d'absence de
chemin, et la classification des fichiers de test suit les conventions des langages qualifiés.

## Requirements

### Requirement: L'analyse d'impact déclare les références par occurrence non projetées
Quand le snapshot analysé contient au moins une occurrence résolue qui n'est pas une définition, le rapport d'impact MUST déclarer la limitation `OCCURRENCE_REFERENCES_NOT_PROJECTED`, car ces occurrences ne produisent aucune arête du graphe d'impact. La limitation MUST figurer même quand la liste d'impacts est vide.

#### Scenario: Snapshot issu d'un indexeur SCIP avec références résolues
- **WHEN** l'impact d'un symbole est calculé sur un snapshot qui contient une occurrence résolue non définitionnelle visant ce symbole, sans relation correspondante
- **THEN** le rapport contient `OCCURRENCE_REFERENCES_NOT_PROJECTED` en plus des limitations de base
- **AND** cette limitation est restituée à l'identique par la CLI (JSON et texte), par MCP et par l'API Java

#### Scenario: Résultat vide sans fausse assurance
- **WHEN** l'impact d'un symbole renvoie zéro impact sur un snapshot contenant des références par occurrence
- **THEN** le rapport déclare `OCCURRENCE_REFERENCES_NOT_PROJECTED`
- **AND** une absence d'impact n'est donc pas lisible comme une absence de chemin statique

#### Scenario: Snapshot sans référence par occurrence
- **WHEN** l'impact est calculé sur un snapshot qui ne contient aucune occurrence résolue non définitionnelle
- **THEN** la limitation `OCCURRENCE_REFERENCES_NOT_PROJECTED` est absente du rapport
- **AND** les limitations de base du rapport sont inchangées

#### Scenario: Limitations existantes conservées
- **WHEN** le rapport contient la nouvelle limitation
- **THEN** `DYNAMIC_DISPATCH_NOT_PROVEN`, `REFLECTION_NOT_PROVEN` et `RUNTIME_CONFIGURATION_NOT_PROVEN` y figurent toujours
- **AND** l'ordre des limitations est déterministe d'une exécution à l'autre, sous Windows comme sous Linux

### Requirement: L'impact avancé conserve la limite de l'impact de base
L'analyse d'impact avancée MUST inclure, sans les retirer ni les atténuer, les limitations de l'impact de base qu'elle prolonge. Un chemin ajouté par une analyse de programme ne dispense pas de déclarer les références par occurrence non projetées.

#### Scenario: Impact avancé sur snapshot avec références par occurrence
- **WHEN** l'impact avancé d'un symbole est calculé sur un snapshot contenant des références résolues non définitionnelles
- **THEN** le rapport de base embarqué déclare `OCCURRENCE_REFERENCES_NOT_PROJECTED`
- **AND** les limitations propres à l'analyse avancée restent présentes

### Requirement: Les sorties d'impact restent additives et reproductibles
L'ajout de la limitation MUST NOT retirer, renommer ni réordonner une clé ou une valeur existante des sorties d'impact, et MUST NOT dépendre de l'hôte, de l'outillage installé ou du système d'exploitation.

#### Scenario: Sortie de caractérisation inchangée hors ajout
- **WHEN** la sortie JSON d'impact d'un index de référence est comparée à sa version précédente
- **THEN** la seule différence est la présence de la nouvelle valeur dans la liste `limitations`
- **AND** la sortie est identique sous Windows et sous Linux

### Requirement: Les fichiers de test des conventions polyglottes sont classés comme tests
La classification d'un chemin comme fichier de test MUST reconnaître les conventions de nommage des langages qualifiés : fichier `_test.go`, fichiers Python `test_*.py` et `*_test.py`, répertoire de projet .NET terminé par `.Tests` ou `.Test`, fichiers C/C++ `*_test.c`, `*_test.cc` et `*_test.cpp`. Les conventions déjà reconnues restent reconnues.

#### Scenario: Chemins de test reconnus
- **WHEN** les chemins `pkg/foo_test.go`, `pkg/test_foo.py`, `pkg/foo_test.py`, `src/Foo.Tests/FooTests.cs` et `src/foo_test.cc` sont classés
- **THEN** chacun est un chemin de test

#### Scenario: Séparateurs Windows
- **WHEN** le chemin `pkg\foo_test.go` est classé
- **THEN** le résultat est identique à celui de `pkg/foo_test.go`

#### Scenario: Le mot nu n'est pas une convention
- **WHEN** les chemins `src/main/java/com/acme/protests/Vote.java`, `contest/Runner.py`, `latest/Widget.java`, `src/Test.java` et `pkg/contest.py` sont classés
- **THEN** aucun n'est un chemin de test

#### Scenario: Conventions existantes préservées
- **WHEN** les chemins `src/test/java/com/acme/FooTest.java`, `src/foo.test.ts`, `tests/test_foo.py` et `crates/foo/tests/integration.rs` sont classés
- **THEN** chacun reste un chemin de test

### Requirement: Un symbole de test polyglotte n'est pas un impact de production ordinaire
Quand un fichier d'un langage qualifié respecte une convention de test, ses symboles MUST être traités comme symboles de test par la dérivation des tests liés, qui MUST pouvoir produire une relation de test lié vers le symbole de production, au moins par convention de nommage.

#### Scenario: Test Go voisin du code
- **WHEN** un fichier `pkg/foo_test.go` contient un symbole `TestFoo` et que `pkg/foo.go` contient le symbole `Foo`
- **THEN** une relation de test lié heuristique est dérivée de `TestFoo` vers `Foo`
- **AND** `TestFoo` est présenté comme test potentiellement impacté, non comme symbole de production ordinaire

#### Scenario: Fichier de production homonyme
- **WHEN** un fichier `pkg/contest.go` contient un symbole `Contest`
- **THEN** aucune relation de test lié n'est dérivée depuis ce fichier

### Requirement: Les index d'impact sont construits une fois par vue ou par graphe en cache
L'analyse d'impact SHALL utiliser un index des arêtes entrantes construit une seule fois pour une vue de requête en cache, et l'impact avancé SHALL utiliser une adjacence construite une seule fois pour un graphe de programme en cache. Le contenu et l'ordre des rapports SHALL être identiques à ceux d'un index reconstruit à chaque appel.

#### Scenario: Deux analyses sur la même vue
- **GIVEN** une vue de requête en cache et un compteur de constructions d'index
- **WHEN** deux analyses d'impact sont exécutées sur des symboles différents
- **THEN** le compteur de constructions de l'index des arêtes entrantes vaut 1

#### Scenario: Deux impacts avancés sur le même graphe
- **GIVEN** un graphe de programme en cache et un compteur de constructions d'adjacence
- **WHEN** deux analyses d'impact avancé sont exécutées
- **THEN** le compteur vaut 1

#### Scenario: Rapports identiques
- **GIVEN** les fixtures et les goldens d'impact
- **WHEN** les rapports d'impact et d'impact avancé sont produits
- **THEN** ils sont identiques, ordre compris, à ceux d'avant ce changement

#### Scenario: Premières analyses simultanées
- **GIVEN** une vue dont l'index n'est pas encore construit
- **WHEN** plusieurs analyses démarrent en même temps
- **THEN** toutes rendent le rapport attendu

### Requirement: Les preuves d'une relation de test lié sont bornées et l'omission est déclarée
Une relation de test lié SHALL conserver au plus N emplacements par type de preuve, choisis dans un ordre déterministe indépendant de l'hôte. Quand des emplacements sont omis, la relation SHALL porter une preuve de synthèse qui en donne le nombre. L'ensemble des tests liés, leur confiance et leur ordre SHALL NOT dépendre du plafond.

#### Scenario: Test qui référence un symbole plus de N fois
- **GIVEN** un fichier de test qui référence le même symbole de production à N + 3 emplacements
- **WHEN** les tests liés sont dérivés
- **THEN** la relation porte N preuves de référence directe avec emplacement et une preuve de synthèse qui annonce 3 emplacements omis

#### Scenario: Score inchangé par le plafond
- **GIVEN** un corpus dont plusieurs relations dépassent le plafond
- **WHEN** les relations sont dérivées avec puis sans plafond
- **THEN** les paires test et symbole, leurs confiances et leur ordre sont identiques

#### Scenario: Ordre reproductible
- **GIVEN** le même corpus dérivé deux fois, les occurrences étant fournies dans un ordre différent
- **WHEN** les preuves conservées sont comparées
- **THEN** ce sont les mêmes emplacements, dans le même ordre

### Requirement: Sous le plafond, les sorties de tests liés sont inchangées
Une relation de test lié dont aucun type de preuve ne dépasse N emplacements SHALL être persistée et rendue à l'identique : aucune preuve retirée, ajoutée ni réordonnée, aucune preuve de synthèse. Le format de snapshot SHALL NOT changer, et un snapshot produit avant le plafond SHALL rester lisible.

#### Scenario: Goldens de caractérisation
- **GIVEN** les goldens de caractérisation de `minos-app`, dont aucune relation ne dépasse le plafond
- **WHEN** `./mvnw -B -ntp -pl minos-app -am verify` est exécuté
- **THEN** les sorties CLI et MCP sont identiques à l'octet

#### Scenario: Snapshot antérieur
- **GIVEN** un snapshot persisté avant le changement, dont une relation porte plus de N preuves
- **WHEN** il est relu
- **THEN** il est décodé sans erreur et toutes ses preuves sont rendues

#### Scenario: Relation exactement au plafond
- **GIVEN** un test qui référence un symbole à exactement N emplacements
- **WHEN** les tests liés sont dérivés
- **THEN** la relation porte N preuves et aucune preuve de synthèse
