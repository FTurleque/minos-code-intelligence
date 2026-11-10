# Spec Delta

## ADDED Requirements

### Requirement: Le plan de requête Docker fixe le tas de sa JVM en proportion de sa limite mémoire
L'image du plan de requête et le service `minos-mcp` des deux fichiers compose SHALL fixer explicitement le tas maximal de la JVM MCP en pourcentage de la mémoire du conteneur, avec la même valeur dans `docker/Dockerfile.mcp`, l'étape `query` de `docker/Dockerfile.mcp.release` et les deux compose. Avec la limite mémoire par défaut, le budget de mémoire des vues SHALL contenir la vue V3 la plus lourde persistable.

#### Scenario: Valeur déclarée identique partout
- **GIVEN** `docker/Dockerfile.mcp`, `docker/Dockerfile.mcp.release`, `docker/compose-mcp.prod.yaml` et `docker/compose-mcp.connected.yaml`
- **WHEN** le test de contrat de l'image de requête les lit
- **THEN** chacun déclare `-XX:MaxRAMPercentage=` avec la même valeur pour la JVM du plan de requête
- **AND** les services d'administration et de tâches gardent leurs options Java inchangées

#### Scenario: Budget suffisant sous la limite par défaut
- **GIVEN** la limite mémoire par défaut du rôle requêtes et la valeur déclarée de `MaxRAMPercentage`
- **WHEN** le test de contrat calcule le tas maximal puis le budget de mémoire des vues par défaut
- **THEN** le budget est supérieur ou égal au poids estimé de la vue V3 la plus lourde persistable

#### Scenario: Tas effectif du conteneur
- **GIVEN** l'image de requête construite et démarrée avec la limite mémoire par défaut
- **WHEN** `java -XX:+PrintFlagsFinal -version` s'exécute dans le conteneur
- **THEN** `MaxHeapSize` vaut le pourcentage déclaré de la limite mémoire, à l'arrondi de la JVM près
