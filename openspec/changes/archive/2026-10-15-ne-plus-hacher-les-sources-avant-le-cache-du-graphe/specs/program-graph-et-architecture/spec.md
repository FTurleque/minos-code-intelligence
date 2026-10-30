## ADDED Requirements

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
