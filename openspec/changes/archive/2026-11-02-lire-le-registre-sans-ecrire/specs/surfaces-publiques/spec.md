## ADDED Requirements

### Requirement: Tout outil MCP répond sur un répertoire de données qu'il ne peut pas écrire
Sur une application ouverte dont le répertoire de données ne peut plus être écrit, un appel d'outil MCP SHALL rendre la même réponse que sur un répertoire inscriptible. Il SHALL NOT créer, supprimer, modifier ni rouvrir aux écritures un fichier de ce répertoire, fichiers de verrou compris. Un refus fonctionnel (symbole inconnu, mode désactivé) reste une réponse ; l'échec interne opaque n'en est pas une.

#### Scenario: Requête de symboles sur un répertoire protégé en écriture
- **GIVEN** un projet enregistré, un snapshot publié, puis le répertoire de données protégé en écriture
- **WHEN** `minos_find_symbols` est appelé (`./mvnw -B -ntp -pl minos-mcp -am test "-Dtest=MinosMcpWriteProtectedHomeTest"`)
- **THEN** la réponse contient le symbole et n'est pas une erreur

#### Scenario: Aucun outil n'échoue en interne
- **GIVEN** le même répertoire protégé en écriture
- **WHEN** chaque outil de la liste publiée est appelé une fois
- **THEN** aucune réponse ne vaut « MINOS tool execution failed »

#### Scenario: Le répertoire est identique après les appels
- **GIVEN** l'état du répertoire relevé avant les appels (entrées, tailles, dates, permissions ou refus d'écriture)
- **WHEN** tous les outils ont été appelés
- **THEN** l'état relevé après est identique
