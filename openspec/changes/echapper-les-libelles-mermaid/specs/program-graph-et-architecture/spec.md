## ADDED Requirements

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
