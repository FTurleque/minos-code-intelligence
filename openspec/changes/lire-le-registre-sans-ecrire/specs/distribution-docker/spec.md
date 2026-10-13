## ADDED Requirements

### Requirement: La qualification interroge le plan de requête sur des données en lecture seule
La qualification de l'image de release SHALL lancer le serveur MCP de l'image de requête avec le répertoire de données monté en lecture seule, comme le service `minos-mcp` des fichiers compose, et lui envoyer une requête de symboles sur un index existant. Elle SHALL d'abord prouver que ce montage refuse une écriture, et SHALL échouer si la requête rend une erreur.

#### Scenario: Requête sur le montage en lecture seule
- **GIVEN** un index préparé dans un volume par un conteneur en écriture, puis ce volume monté `readonly` dans le conteneur MCP
- **WHEN** `scripts/ci/qualify-docker-release.sh` envoie `minos_find_symbols`
- **THEN** la réponse contient le symbole attendu et la qualification continue

#### Scenario: Une lecture qui écrit fait échouer la qualification
- **GIVEN** une image dont le serveur ouvre un fichier du répertoire de données en écriture pour répondre
- **WHEN** la qualification envoie la requête de symboles
- **THEN** elle échoue en nommant l'outil

#### Scenario: Montage inscriptible réintroduit dans la qualification
- **GIVEN** une copie du script où le conteneur MCP reçoit un répertoire de données inscriptible
- **WHEN** les tests de contrat Docker de `minos-app` sont exécutés (`./mvnw -B -ntp -pl minos-app -am test "-Dtest=M29Docker*"`)
- **THEN** `M29DockerQueryImageContractTest` échoue
