# Spec Delta

## Purpose

Garantit que l'architecture factuelle et les requêtes de relations (appelants, appelés, dépendances)
disent quelles relations le snapshot ne contient pas, au lieu de présenter une liste vide ou un
graphe clairsemé comme une information complète, et que le filtre par module des symboles est fiable.

## ADDED Requirements

### Requirement: L'architecture déclare les références par occurrence non agrégées
Le graphe de dépendances d'architecture MUST porter une liste de limitations. Quand le snapshot contient au moins une occurrence résolue qui n'est pas une définition, cette liste MUST contenir `OCCURRENCE_REFERENCES_NOT_PROJECTED`, car le graphe n'agrège que les dépendances persistées. La liste MUST rester vide quand aucune telle occurrence n'existe.

#### Scenario: Architecture d'un snapshot avec références par occurrence
- **WHEN** l'architecture est calculée sur un snapshot contenant des occurrences résolues non définitionnelles
- **THEN** la sortie JSON de l'architecture contient une clé `limitations` avec `OCCURRENCE_REFERENCES_NOT_PROJECTED`
- **AND** la sortie texte contient une ligne `limitations`
- **AND** la vue d'architecture composée, le DTO de l'API Java et la sortie MCP portent la même information

#### Scenario: Formats de graphe
- **WHEN** le graphe d'architecture est rendu en Mermaid ou en DOT sur un tel snapshot
- **THEN** le rendu contient un commentaire déclarant la limitation
- **AND** le rendu reste un document Mermaid ou DOT valide

#### Scenario: Snapshot sans référence par occurrence
- **WHEN** l'architecture est calculée sur un snapshot sans occurrence résolue non définitionnelle
- **THEN** la liste des limitations est vide
- **AND** aucune limitation n'est affichée

#### Scenario: Compteurs inchangés
- **WHEN** la limitation est déclarée
- **THEN** les compteurs de dépendances (total, inter-modules, intra-module, non attribuées) et les arêtes de modules sont identiques à ceux calculés avant le changement

### Requirement: Le message de preuve d'architecture énonce sa couverture
Le message de preuve du graphe de dépendances d'architecture MUST indiquer que le graphe agrège uniquement les relations de dépendance persistées, et qu'il n'inclut pas les références exprimées seulement par des occurrences.

#### Scenario: Message de preuve
- **WHEN** le graphe de dépendances d'architecture est produit
- **THEN** son message de preuve mentionne les dépendances persistées comme seule source agrégée
- **AND** il mentionne l'exclusion des références par occurrence

### Requirement: Les requêtes d'appelants et d'appelés déclarent l'absence de relations d'appel
Quand le snapshot ne contient aucune relation d'appel, la réponse d'une requête d'appelants ou d'appelés MUST déclarer `CALL_RELATIONS_NOT_PRODUCED` en plus de sa liste vide, sur la CLI (JSON et texte) et sur MCP. Quand le snapshot contient au moins une relation d'appel, la réponse MUST NOT porter cette limitation.

#### Scenario: Snapshot SCIP sans relation d'appel
- **WHEN** les appelants d'un symbole sont demandés sur un snapshot qui ne contient aucune relation d'appel
- **THEN** la liste de relations est vide
- **AND** la réponse contient `CALL_RELATIONS_NOT_PRODUCED`

#### Scenario: Appelés
- **WHEN** les appelés d'un symbole sont demandés sur ce même snapshot
- **THEN** la réponse contient la même limitation

#### Scenario: Snapshot contenant des relations d'appel
- **WHEN** les appelants sont demandés sur un snapshot contenant au moins une relation d'appel
- **THEN** la réponse ne contient pas `CALL_RELATIONS_NOT_PRODUCED`

#### Scenario: Contrat de réponse additif
- **WHEN** une requête d'appelants ou d'appelés réussit
- **THEN** les clés `count` et `relationships` existantes sont inchangées et dans le même ordre
- **AND** la clé `limitations` n'est ajoutée qu'après elles

### Requirement: Les requêtes de dépendances déclarent les références par occurrence non projetées
La réponse d'une requête de dépendances ou de dépendants MUST déclarer `OCCURRENCE_REFERENCES_NOT_PROJECTED` quand le snapshot contient au moins une occurrence résolue non définitionnelle. Les requêtes d'implémentations et de tests liés MUST NOT recevoir cette limitation.

#### Scenario: Dépendants sur snapshot avec références par occurrence
- **WHEN** les dépendants d'un symbole sont demandés sur un snapshot contenant des occurrences résolues non définitionnelles
- **THEN** la réponse contient `OCCURRENCE_REFERENCES_NOT_PROJECTED`

#### Scenario: Implémentations
- **WHEN** les implémentations d'un symbole sont demandées sur le même snapshot
- **THEN** la réponse ne contient aucune limitation ajoutée par ce changement

### Requirement: Le filtre par module refuse explicitement un snapshot sans module
Quand une recherche de symboles est filtrée par module et que le snapshot actif ne contient aucun symbole portant un identifiant de module, la recherche MUST échouer avec un message explicite indiquant que le snapshot ne porte pas d'information de module, au lieu de renvoyer une liste vide. Le message MUST NOT contenir de chemin absolu ni de fragment de fichier.

#### Scenario: Filtre sur snapshot sans module
- **WHEN** `find-symbol` est exécuté avec `--module` sur un snapshot dont aucun symbole n'a de module
- **THEN** la commande échoue avec un message explicite sur l'absence d'information de module
- **AND** la même recherche par l'API MCP échoue de la même façon

#### Scenario: Recherche sans filtre de module
- **WHEN** la même recherche est exécutée sans `--module` sur le même snapshot
- **THEN** elle réussit comme avant

#### Scenario: Module inconnu sur snapshot renseigné
- **WHEN** `--module` désigne un module inexistant sur un snapshot dont des symboles portent un module
- **THEN** la liste de résultats est vide et la commande réussit

### Requirement: L'indexation autonome renseigne le module de chaque symbole local
Après une indexation autonome, chaque symbole local MUST porter l'identifiant du module découvert qui contient son fichier, selon la règle d'attribution utilisée par l'architecture, y compris quand un seul passage de l'indexeur couvre plusieurs modules. Un symbole externe ou sans module correspondant MUST rester sans module.

#### Scenario: Projet à deux modules indexé en un seul passage
- **WHEN** un projet de deux modules, couvert par un seul passage d'indexeur à sa racine, est indexé par le chemin autonome
- **THEN** une recherche filtrée par l'identifiant du premier module ne renvoie que ses symboles
- **AND** une recherche filtrée par l'identifiant du second module ne renvoie que les siens

#### Scenario: Identifiant cohérent avec l'architecture
- **WHEN** l'identifiant d'un module est lu dans la sortie d'architecture puis utilisé comme valeur de `--module`
- **THEN** la recherche renvoie les symboles de ce module

#### Scenario: Symbole sans module correspondant
- **WHEN** un symbole local est situé hors de tout module découvert, ou est externe
- **THEN** son identifiant de module reste absent
- **AND** l'indexation n'échoue pas pour autant

#### Scenario: Résultat indépendant du système d'exploitation
- **WHEN** le même projet est indexé sous Windows et sous Linux
- **THEN** les identifiants de module attribués aux symboles sont identiques
