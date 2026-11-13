## ADDED Requirements

### Requirement: Les tests PostgreSQL utilisent un serveur fourni de l'extérieur
Les tests d'intégration PostgreSQL SHALL se connecter au serveur désigné par les propriétés système `minos.postgresql.test.url`, `minos.postgresql.test.user` et `minos.postgresql.test.password`, et SHALL NOT démarrer eux-mêmes de conteneur ni dialoguer avec un démon Docker.

#### Scenario: Serveur démarré par le script local
- **GIVEN** un conteneur pgvector démarré par `scripts/dev/start-test-postgres.sh` (ou `.ps1`) et les propriétés qu'il affiche
- **WHEN** `./mvnw -B -ntp -pl minos-storage -am verify "-Dminos.postgresql.tests.required=true"` est exécuté avec ces propriétés
- **THEN** les tests PostgreSQL s'exécutent contre ce serveur et passent
- **AND** `docker ps -a` ne liste aucun conteneur créé pendant le build

#### Scenario: Aucune bibliothèque de pilotage de Docker dans les tests
- **GIVEN** les sources de test du réacteur
- **WHEN** `grep -rlF "import org.testcontainers" --include=*.java .` est exécuté à la racine
- **THEN** la commande ne trouve aucun fichier

### Requirement: Un serveur absent ou injoignable fait échouer les tests en mode requis
Avec `-Dminos.postgresql.tests.required=true`, une URL de test absente, un serveur injoignable, une authentification refusée ou un droit de création de base manquant SHALL faire échouer chaque classe de test PostgreSQL avec un message qui nomme la propriété ou la cause, sans afficher le mot de passe.

#### Scenario: URL absente en mode requis
- **GIVEN** aucune propriété `minos.postgresql.test.url`
- **WHEN** `./mvnw -B -ntp -pl minos-storage -am test "-Dminos.postgresql.tests.required=true"` est exécuté
- **THEN** le build échoue et le rapport Surefire nomme `minos.postgresql.test.url`
- **AND** aucun test PostgreSQL n'est compté comme réussi ni comme sauté

#### Scenario: Serveur injoignable en mode requis
- **GIVEN** `minos.postgresql.test.url` désignant un port local sans serveur
- **WHEN** la même commande est exécutée
- **THEN** le build échoue avec un message qui dit que le serveur PostgreSQL de test est injoignable
- **AND** la sortie du build ne contient pas la valeur du mot de passe

### Requirement: Sans mode requis, l'absence de serveur saute les tests et les compte
Sans `-Dminos.postgresql.tests.required=true`, une URL de test absente ou un serveur injoignable SHALL marquer les tests PostgreSQL comme sautés, avec la raison, dans le décompte Surefire ; les autres tests du module SHALL s'exécuter normalement.

#### Scenario: Poste sans serveur
- **GIVEN** aucune propriété `minos.postgresql.test.url`
- **WHEN** `./mvnw -B -ntp -pl minos-storage -am test` est exécuté
- **THEN** le build réussit et le rapport Surefire compte les tests PostgreSQL comme `skipped` avec une raison qui nomme `minos.postgresql.test.url`

#### Scenario: Job Windows de la CI
- **GIVEN** le job `Verify (windows-2022)` de `pr-ci.yml`, qui ne démarre aucun PostgreSQL
- **WHEN** `python scripts/quality/check-jacoco.py --skip-scope m30-postgresql-pgvector` suit le `verify` Windows
- **THEN** le `verify` réussit, les tests PostgreSQL y sont comptés comme sautés et le gate JaCoCo passe

### Requirement: Chaque classe de test PostgreSQL travaille dans sa propre base
Chaque classe de test PostgreSQL SHALL disposer d'une base créée pour elle avant son premier test et supprimée après son dernier, au nom unique par exécution. Le résultat d'une classe SHALL NOT dépendre de l'ordre d'exécution des classes ni des données d'une autre classe.

#### Scenario: Ordre des classes inversé
- **GIVEN** un serveur de test démarré par le script local
- **WHEN** les tests PostgreSQL de `minos-storage` sont exécutés deux fois, avec `-Dsurefire.runOrder=alphabetical` puis `-Dsurefire.runOrder=reversealphabetical`
- **THEN** les deux exécutions donnent les mêmes décomptes de tests réussis

#### Scenario: Aucune base laissée derrière
- **GIVEN** la même exécution terminée
- **WHEN** `psql` liste les bases du serveur de test
- **THEN** aucune base créée par une classe de test ne subsiste

### Requirement: Le mot de passe du serveur de test de la CI est généré par le job
Le mot de passe du serveur PostgreSQL de test des jobs de CI SHALL être généré aléatoirement à chaque exécution du job et masqué dans ses journaux. Il SHALL NOT être écrit en clair dans un workflow, un script, un POM ni une source de test.

#### Scenario: Aucun mot de passe figé dans le dépôt
- **GIVEN** le dépôt au HEAD
- **WHEN** `grep -rn "test-secret" minos-storage/src .github/workflows scripts` est exécuté
- **THEN** la commande ne trouve rien

#### Scenario: Deux démarrages, deux mots de passe
- **GIVEN** le script `scripts/dev/start-test-postgres.sh` sous son auto-test
- **WHEN** l'auto-test démarre le conteneur deux fois avec un `docker` simulé
- **THEN** les deux mots de passe transmis diffèrent et aucun n'apparaît dans les arguments de la ligne de commande `docker`

### Requirement: Le script local démarre le conteneur de test épinglé
`scripts/dev/start-test-postgres.sh` et `scripts/dev/start-test-postgres.ps1` SHALL démarrer l'image pgvector lue dans sa source unique (défaut de `MINOS_POSTGRES_IMAGE`), liée à `127.0.0.1`, attendre son healthcheck dans un délai borné, puis afficher les propriétés à passer à `mvnw`. Un défaut d'image absent ou non épinglé SHALL faire échouer le script.

#### Scenario: Démarrage nominal
- **GIVEN** un poste avec Docker
- **WHEN** `bash scripts/dev/start-test-postgres.sh` est exécuté
- **THEN** le script se termine avec le code 0 après un healthcheck sain et affiche `-Dminos.postgresql.test.url=jdbc:postgresql://127.0.0.1:<port>/…`
- **AND** `docker port` montre le port lié à `127.0.0.1` seulement

#### Scenario: Image non épinglée
- **GIVEN** un arbre temporaire dont `docker/compose-mcp.connected.yaml` donne à `MINOS_POSTGRES_IMAGE` un défaut sans `@sha256:`
- **WHEN** l'auto-test du script l'exécute sur cet arbre
- **THEN** le script échoue avec un code non nul et un message qui nomme `MINOS_POSTGRES_IMAGE`, sans démarrer de conteneur

### Requirement: L'arbre de dépendances ne contient ni Testcontainers ni docker-java
L'arbre de dépendances Maven du réacteur, portée test comprise, SHALL NOT contenir d'artefact `org.testcontainers` ni `com.github.docker-java`.

#### Scenario: Arbre de dépendances
- **GIVEN** le réacteur au HEAD
- **WHEN** `./mvnw -B -ntp dependency:tree` est exécuté et sa sortie filtrée sur `org.testcontainers` et `com.github.docker-java`
- **THEN** le filtre ne trouve aucune ligne

#### Scenario: Rapport Dependency-Check des tests
- **GIVEN** le profil `audit-dependency-check-tests` avec une base NVD à jour
- **WHEN** `./mvnw -B -ntp -Paudit-dependency-check-tests org.owasp:dependency-check-maven:aggregate` est exécuté
- **THEN** le rapport ne cite ni CVE-2026-71290, ni CVE-2026-64607, ni CVE-2026-54399, ni CVE-2026-54428

## MODIFIED Requirements

### Requirement: Les résultats des requêtes sont inchangés
Les réponses des requêtes de lecture, leur ordre, leurs limites et leurs budgets SHALL rester identiques à ceux d'avant ce changement, sous le store fichier et sous PostgreSQL. Les goldens existants SHALL NOT être régénérés pour ce changement.

#### Scenario: Goldens de caractérisation
- **GIVEN** les goldens de `minos-app/src/test/resources/characterization/` et les rapports d'impact de référence
- **WHEN** `./mvnw -B -ntp verify` est exécuté
- **THEN** les tests de caractérisation et d'impact passent sans fichier de référence modifié

#### Scenario: Store PostgreSQL, sous Linux seulement
- **GIVEN** un poste Linux avec un serveur PostgreSQL de test démarré par `scripts/dev/start-test-postgres.sh`
- **WHEN** `./mvnw -B -ntp -pl minos-storage -am test "-Dminos.postgresql.tests.required=true"` est exécuté avec les propriétés affichées par le script
- **THEN** les tests du cache de vues PostgreSQL passent avec les mêmes décomptes que le store fichier ; sans serveur fourni les tests sont sautés, ce qui n'est pas une preuve
