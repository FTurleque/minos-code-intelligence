# program-graph-et-architecture Specification

## Purpose
Garantit que l'architecture factuelle et les requêtes de relations (appelants, appelés, dépendances)
disent quelles relations le snapshot ne contient pas, au lieu de présenter une liste vide ou un
graphe clairsemé comme une information complète, et que le filtre par module des symboles est fiable.

## Requirements

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

### Requirement: Une requête de graphe d'architecture avec module découvre le dépôt une seule fois
Une requête de graphe d'architecture en Mermaid ou DOT filtrée sur un module SHALL exécuter une seule découverte du dépôt et un seul chargement du snapshot actif, par le MCP comme par la CLI. La sortie SHALL être identique à celle d'avant et un module inconnu SHALL produire la même erreur publique.

#### Scenario: Graphe Mermaid avec module par le MCP
- **GIVEN** un service de découverte et un store de snapshots qui comptent leurs appels
- **WHEN** `minos_architecture_graph` est appelé en Mermaid avec un module existant
- **THEN** le compteur de découvertes vaut 1 et celui de chargements du snapshot vaut 1

#### Scenario: Graphe DOT avec module par la CLI
- **GIVEN** les mêmes compteurs
- **WHEN** la commande d'architecture est exécutée en DOT avec un module existant
- **THEN** le compteur de découvertes vaut 1 et celui de chargements du snapshot vaut 1

#### Scenario: Sortie inchangée
- **GIVEN** les fixtures et goldens d'architecture
- **WHEN** le graphe avec module est produit par chaque surface
- **THEN** la sortie est identique, à l'octet, à celle d'avant ce changement

#### Scenario: Module inconnu
- **GIVEN** un nom de module absent du projet
- **WHEN** le graphe avec module est demandé par le MCP puis par la CLI
- **THEN** chaque surface rend la même erreur publique qu'avant ce changement, après une seule découverte

### Requirement: Un graphe de programme servi par le cache ne relit pas les sources dans le délai
Avec une empreinte exacte du snapshot, un graphe en cache SHALL être servi sans lire aucune source. Sans empreinte exacte, la clé obtenue en hachant les sources SHALL être réutilisée pendant un délai borné de 5 secondes, mesuré sur une horloge monotone, puis recalculée une seule fois par les appels concurrents d'un même projet.

#### Scenario: Second appel avec empreinte exacte
- **GIVEN** un snapshot avec empreinte exacte et un compteur des fichiers source réellement lus
- **WHEN** le graphe de programme est demandé deux fois
- **THEN** le second appel ne lit aucune source et rend le même graphe

#### Scenario: Second appel sans empreinte exacte, dans le délai
- **GIVEN** un snapshot sans empreinte exacte, le même compteur et une horloge avancée de moins de 5 secondes
- **WHEN** le graphe de programme est demandé une seconde fois
- **THEN** le second appel ne lit aucune source et rend le même graphe

#### Scenario: Appel après le délai
- **GIVEN** un snapshot sans empreinte exacte, un graphe en cache et une horloge avancée de 5 secondes
- **WHEN** le graphe de programme est demandé deux fois de suite
- **THEN** le premier appel relit chaque source une fois et le suivant n'en relit aucune

### Requirement: Une source modifiée est prise en compte au plus tard après le délai
Sans empreinte exacte, la modification d'une source du snapshot SHALL faire reconstruire le graphe au premier appel qui suit l'expiration du délai. Avant l'expiration, le graphe en cache MAY être servi. Avec une empreinte exacte, une source qui diffère de l'état indexé SHALL toujours être refusée à la construction.

#### Scenario: Modification vue après le délai
- **GIVEN** un snapshot sans empreinte exacte, un graphe en cache et une source modifiée ensuite
- **WHEN** le graphe est demandé avant puis après l'expiration du délai
- **THEN** le premier appel rend le graphe en cache et le second un graphe reconstruit qui remplace l'ancien

#### Scenario: Source modifiée avec empreinte exacte
- **GIVEN** un snapshot avec empreinte exacte et une source modifiée avant la première construction
- **WHEN** le graphe de programme est demandé
- **THEN** l'analyse Java est refusée avec `JAVA_ADVANCED_PROVIDER_SOURCE_DIFFERS_FROM_SNAPSHOT_FINGERPRINT`

### Requirement: Un graphe construit sans empreinte exacte est déclaré
Quand le snapshot actif n'a pas d'empreinte exacte et que des sources Java sont lues, le graphe de programme, l'impact avancé et les chemins de sécurité SHALL porter la limitation `PROGRAM_GRAPH_REFLECTS_DISK_AS_OF_AT_MOST_5_SECONDS_AGO_NOT_INDEXED_STATE`. Elle SHALL être additive et SHALL NOT apparaître avec une empreinte exacte ni quand aucune source Java n'est lue.

#### Scenario: Même limitation sur les trois outils et les trois surfaces
- **GIVEN** un projet Java dont le snapshot n'a pas d'empreinte exacte
- **WHEN** les trois analyses sont appelées par le MCP, la CLI et l'API Java
- **THEN** chacune des neuf réponses contient cet identifiant une fois

#### Scenario: Aucune limitation avec empreinte exacte
- **GIVEN** le même projet avec une empreinte exacte
- **WHEN** les trois analyses sont appelées par le MCP, la CLI et l'API Java
- **THEN** aucune réponse ne contient cet identifiant et les goldens de caractérisation sont inchangés

#### Scenario: Aucune limitation sans source Java
- **GIVEN** un snapshot sans empreinte exacte qui ne référence aucun fichier Java
- **WHEN** le graphe de programme est demandé
- **THEN** la réponse ne contient pas cet identifiant

### Requirement: Les libellés Mermaid du graphe d'architecture échappent les chevrons
Le rendu Mermaid du graphe d'architecture SHALL échapper `<` et `>` dans tout texte issu du dépôt analysé (nom de module, chemin relatif). Le seul balisage HTML d'un libellé SHALL être le saut de ligne `<br/>` que MINOS insère après l'échappement. Un texte sans chevron SHALL être rendu comme avant.

#### Scenario: Nom de module hostile
- **GIVEN** une vue d'architecture dont un module se nomme `<img src=x onerror=alert(1)>`
- **WHEN** le graphe est rendu au format Mermaid
- **THEN** la sortie ne contient pas `<img` et le libellé porte le nom échappé

#### Scenario: Module nominal avec chemin
- **GIVEN** un module `core` de chemin relatif `packages/core`
- **WHEN** le graphe est rendu au format Mermaid
- **THEN** son libellé est `core<br/>packages/core`, identique au golden existant

#### Scenario: Esperluette et chevron ensemble
- **GIVEN** un module nommé `a&b<c`
- **WHEN** le graphe est rendu au format Mermaid
- **THEN** l'esperluette est échappée une seule fois et le chevron est échappé

### Requirement: Une ligne de commentaire Mermaid ne porte pas de directive issue de la vue
Dans une ligne de commentaire du rendu Mermaid, un texte issu de la vue (nom du projet, identifiant de snapshot, limitation) SHALL NOT produire la séquence `%%{`. La sortie SHALL rester un commentaire d'une seule ligne.

#### Scenario: Nom de projet contenant une directive
- **GIVEN** une vue dont le nom de projet est `demo %%{init: {"theme":"dark"}}%%`
- **WHEN** le graphe est rendu au format Mermaid
- **THEN** aucune ligne de la sortie ne contient `%%{`

#### Scenario: Nom de projet nominal
- **GIVEN** une vue dont le nom de projet ne contient ni `%` ni accolade
- **WHEN** le graphe est rendu au format Mermaid
- **THEN** la ligne de commentaire du projet est identique à celle produite au HEAD

### Requirement: Les règles de sécurité fournies par le dépôt analysé sont déclarées
Quand les règles source, puits et assainisseur de l'analyse Java sont lues dans le dépôt analysé, le graphe de programme et les réponses de `minos_program_graph`, `minos_impact_v2` et `minos_security_paths` SHALL porter la limitation `SECURITY_RULES_SUPPLIED_BY_ANALYZED_REPOSITORY`. Elle SHALL être additive et SHALL NOT apparaître quand le dépôt ne fournit aucun fichier de règles.

#### Scenario: Dépôt qui fournit ses règles
- **GIVEN** un projet Java dont la racine contient `.minos/java-advanced-provider.properties`
- **WHEN** `minos_security_paths` est appelé
- **THEN** la réponse contient la limitation `SECURITY_RULES_SUPPLIED_BY_ANALYZED_REPOSITORY`

#### Scenario: Dépôt hostile qui omet ses sources
- **GIVEN** un projet Java dont le fichier de règles ne déclare aucune source
- **WHEN** `minos_security_paths` est appelé
- **THEN** la réponse ne rapporte aucun chemin et contient cette limitation

#### Scenario: Dépôt sans fichier de règles
- **GIVEN** un projet Java sans fichier de règles
- **WHEN** le graphe de programme est demandé
- **THEN** la limitation est absente et `JAVA_SECURITY_RULES_NOT_CONFIGURED` est présente

#### Scenario: Même limitation sur les trois outils
- **GIVEN** un projet Java qui fournit ses règles
- **WHEN** les trois outils sont appelés par le MCP et par la CLI
- **THEN** chacun rend la limitation avec le même identifiant, sans autre changement de champ

### Requirement: Un chemin assaini reste rapporté et marqué
Un chemin source-puits qui traverse un assainisseur déclaré SHALL être rapporté par l'analyse de sécurité, avec la liste des assainisseurs traversés et l'indicateur d'assainissement à vrai. Un assainisseur déclaré SHALL NOT retirer un chemin de la réponse.

#### Scenario: Chemin traversant un assainisseur
- **GIVEN** un projet Java dont les règles déclarent une source, un puits et un assainisseur placé entre les deux
- **WHEN** `minos_security_paths` est appelé
- **THEN** le chemin figure dans la réponse avec l'indicateur d'assainissement à vrai

#### Scenario: Assainisseur déclaré mais non traversé
- **GIVEN** un projet Java dont les règles déclarent un assainisseur qui n'est sur aucun chemin entre la source et le puits
- **WHEN** `minos_security_paths` est appelé
- **THEN** le chemin figure dans la réponse avec l'indicateur d'assainissement à faux
