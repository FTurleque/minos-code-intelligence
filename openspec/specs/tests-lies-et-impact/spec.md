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
