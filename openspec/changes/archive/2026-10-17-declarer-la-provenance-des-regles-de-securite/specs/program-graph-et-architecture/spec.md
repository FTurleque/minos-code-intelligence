## ADDED Requirements

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
