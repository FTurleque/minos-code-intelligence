# distribution-docker Specification

## Purpose
Définit la façon dont MINOS se distribue par Docker : une image de requête, réduite au JRE et au jar, pour le plan de requête MCP et les amorçages, et une image d'administration qui porte seule la chaîne de compilation et les outils de provider ; chaque service désigne l'une des deux, le cycle de vie refuse un état incohérent avant tout appel à Docker, et une installation à une seule image est mise à niveau sans intervention.

## Requirements

### Requirement: L'image du plan de requête ne contient ni compilateur ni outil de provider
Le conteneur du plan de requête MCP SHALL utiliser une image limitée au runtime Java et au jar de MINOS. Cette image SHALL NOT contenir de JDK, de compilateur C, de toolchain Rust, Go ou .NET, de Node ni de gestionnaire de paquets de langage. L'image d'administration SHALL conserver ce qu'exige l'indexation.

#### Scenario: Compilateurs absents de l'image de requête
- **GIVEN** l'image de requête construite par la qualification de release
- **WHEN** `gcc`, `cargo`, `go`, `dotnet`, `node`, `pip3` et `javac` sont recherchés dans le conteneur
- **THEN** aucune de ces commandes n'existe

#### Scenario: Le plan de requête répond sur l'image de requête
- **GIVEN** un index existant et le serveur MCP lancé dans l'image de requête
- **WHEN** la poignée de main MCP puis une requête de symboles sont envoyées
- **THEN** les deux réponses sont valides

#### Scenario: Toolchain réintroduite dans la cible de requête
- **GIVEN** une copie du Dockerfile où la cible de requête copie un stage de toolchain
- **WHEN** le test de contrat Docker de `minos-app` est exécuté sur cette copie
- **THEN** le test échoue en nommant la ligne

### Requirement: Chaque service Docker utilise l'image qui correspond à son rôle
Les fichiers compose SHALL désigner l'image de requête pour tout service qui n'exécute ni provider ni outil embarqué, et l'image d'administration pour les autres. Le conteneur persistant du plan de requête SHALL NOT utiliser l'image d'administration.

#### Scenario: Service de requête sur l'image d'administration
- **GIVEN** une copie d'un fichier compose où `minos-mcp` désigne l'image d'administration
- **WHEN** le test de contrat Docker est exécuté sur cette copie
- **THEN** le test échoue en nommant le service

#### Scenario: Classement des services réels
- **GIVEN** les deux fichiers compose du dépôt
- **WHEN** le test de contrat Docker de `minos-app` est exécuté
- **THEN** il réussit
- **AND** chaque service MINOS désigne l'image de requête ou l'image d'administration, les services tiers de la pile connectée (PostgreSQL, Ollama) gardant leur propre image épinglée

### Requirement: Une installation sans image de requête échoue de façon actionnable
Quand le fichier compose du runtime demande l'image de requête et que sa variable est absente ou vide, le lancement SHALL échouer avant tout appel à Docker, avec un message qui nomme la commande de mise à jour. Il SHALL NOT utiliser l'image d'administration à la place. Le runtime laissé par une version à une seule image SHALL rester utilisable jusqu'à sa mise à jour, qui SHALL produire les deux images.

#### Scenario: Variable absente
- **GIVEN** une installation dont le fichier compose demande l'image de requête et dont la configuration ne la définit pas
- **WHEN** le script de cycle de vie démarre le plan de requête
- **THEN** il échoue sans appeler Docker et son message nomme la commande de mise à jour

#### Scenario: Image de requête qui n'en est pas une
- **GIVEN** une configuration dont la variable de l'image de requête désigne l'image d'administration
- **WHEN** le script de cycle de vie démarre le plan de requête
- **THEN** il échoue sans démarrer de conteneur

#### Scenario: Runtime d'une version à une seule image, pas encore mis à jour
- **GIVEN** un runtime dont le fichier compose ne demande qu'une image
- **WHEN** le script de cycle de vie démarre le plan de requête
- **THEN** le plan démarre et un avertissement nomme la commande de mise à jour

#### Scenario: Mise à niveau d'une installation à une seule image
- **GIVEN** une installation de la version précédente, avec une seule image
- **WHEN** la qualification de mise à niveau Docker est exécutée
- **THEN** l'installation mise à jour définit les deux images et le plan de requête répond

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

### Requirement: Le runtime Docker ne monte aucune racine des projets
Les fichiers Compose livrés SHALL NOT monter de dossier de projets dans `minos-mcp` ni dans `minos-admin` en dehors des montages de projets générés. Avec une table de montages vide, `minos-mcp` SHALL démarrer et `/workspace/projects` SHALL être vide dans les deux services.

#### Scenario: Fichiers Compose livrés
- **GIVEN** `docker/compose-mcp.prod.yaml` et `docker/compose-mcp.connected.yaml`
- **WHEN** `M29DockerAdministrationContractTest` les lit
- **THEN** aucun ne contient `MINOS_PROJECTS_DIR` ni un montage de cible `/workspace/projects`

#### Scenario: Table vide
- **GIVEN** une installation Docker sans projet exposé
- **WHEN** `minos-mcp` est démarré puis `ls /workspace/projects` est exécuté dans le conteneur
- **THEN** le démarrage réussit et la liste est vide

### Requirement: Exposer un projet Docker ajoute son montage puis l'inscrit
`-Action AddProject -ProjectPath <dossier>` SHALL ajouter un montage en lecture seule de ce seul dossier sur `/workspace/projects/<nom>` dans `minos-mcp` et `minos-admin`, recréer `minos-mcp`, puis inscrire le projet par ce chemin. Un dossier absent, point de reparse, ou refusé par la règle des racines dangereuses SHALL être refusé avant toute écriture, par un message sans chemin.

#### Scenario: Projet exposé
- **GIVEN** une installation Docker et un dossier de projet dédié `app`
- **WHEN** `AddProject -ProjectPath <app>` est exécuté
- **THEN** `project list` dans `minos-admin` contient le projet sous `/workspace/projects/app`, `rootAvailable` vaut `true`, et un dossier voisin de `app` est absent de `/workspace/projects`

#### Scenario: Dossier dangereux
- **GIVEN** le profil de l'utilisateur, une racine de volume ou `%LOCALAPPDATA%`
- **WHEN** il est passé à `AddProject`
- **THEN** l'action échoue, la table et l'override sont inchangés et `minos-mcp` n'est pas recréé

#### Scenario: Point de reparse
- **GIVEN** une jonction ou un lien symbolique vers un dossier de projet
- **WHEN** il est passé à `AddProject`
- **THEN** l'action échoue avant toute écriture et le message demande le dossier réel

### Requirement: La table des montages est bornée et sans chevauchement
Le bootstrap Docker SHALL refuser, sans écrire, un nom de montage hors de `[a-z0-9][a-z0-9._-]{0,62}`, un nom déjà pris sans tenir compte de la casse, un chemin de montage qui en préfixe un autre, un dossier hôte égal à un autre ou qui le contient, un dossier hôte relatif, et un ajout au-delà du plafond de montages. Une version antérieure SHALL refuser le format 2 sans le modifier.

#### Scenario: Nom en double
- **GIVEN** une table qui contient le montage `app`
- **WHEN** `add-project-mount App D:/autre` est exécuté
- **THEN** le code de sortie est non nul et la table est inchangée

#### Scenario: Dossier contenu dans un autre
- **GIVEN** une table qui contient `D:/src/app`
- **WHEN** un montage de `D:/src/app/sub` ou de `D:/src` est ajouté
- **THEN** l'ajout est refusé et la table est inchangée

#### Scenario: Plafond atteint
- **GIVEN** une table qui contient le nombre maximal de montages
- **WHEN** un montage de plus est ajouté
- **THEN** l'ajout est refusé et le message nomme le plafond

### Requirement: L'override des montages est régénéré depuis la table
Avant chaque démarrage de `minos-mcp`, chaque `minos-admin` et chaque validation, le cycle de vie SHALL régénérer `compose-mcp.projects.yaml` à partir de la table et le passer à Compose. Chaque chemin SHALL y être une chaîne échappée. Un montage dont le dossier hôte n'existe plus SHALL être omis avec un avertissement, sans empêcher le démarrage.

#### Scenario: Override modifié à la main
- **GIVEN** un `compose-mcp.projects.yaml` auquel un montage a été ajouté à la main
- **WHEN** `-Action Start` est exécuté
- **THEN** le fichier est réécrit et le montage ajouté à la main n'est pas monté

#### Scenario: Caractères spéciaux
- **GIVEN** un dossier de projet dont le nom contient une apostrophe, un dièse et deux-points
- **WHEN** l'override est généré
- **THEN** `docker compose config` l'accepte et la source du montage est exactement ce dossier

#### Scenario: Dossier supprimé
- **GIVEN** un projet exposé dont le dossier a été supprimé
- **WHEN** `-Action Start` est exécuté
- **THEN** `minos-mcp` démarre, un avertissement nomme le montage et le projet répond `rootAvailable: false`

### Requirement: Retirer un projet Docker retire son montage sans perdre son index
`-Action RemoveProject -Name <nom>` SHALL retirer le montage de la table, régénérer l'override et recréer `minos-mcp`. Les projets inscrits sous ce montage SHALL rester inscrits avec leur index et répondre `rootAvailable: false`. Un nom inconnu SHALL être refusé sans écriture.

#### Scenario: Retrait
- **GIVEN** un projet exposé sous le montage `app`
- **WHEN** `RemoveProject -Name app` est exécuté
- **THEN** `/workspace/projects/app` est absent de `minos-mcp`, `project list` contient le projet avec `rootAvailable: false`

#### Scenario: Nom inconnu
- **GIVEN** une table sans montage `absent`
- **WHEN** `RemoveProject -Name absent` est exécuté
- **THEN** l'action échoue et `minos-mcp` n'est pas recréé

### Requirement: Une racine unique migre vers un montage par projet inscrit
À l'installation sur des données Docker au format 1, chaque projet inscrit SHALL recevoir un montage sur son chemin conteneur actuel, sans réécrire le registre local ou PostgreSQL. Un projet inscrit à la racine elle-même, isolé ou refusé par la règle des racines dangereuses SHALL NOT être monté et SHALL être signalé. Une inscription illisible SHALL arrêter la migration avant toute écriture.

#### Scenario: Projets sous l'ancienne racine
- **GIVEN** des données Docker au format 1 de racine `R` et deux projets inscrits sous `a` et `team/b`
- **WHEN** l'installation de cette version est exécutée
- **THEN** la table contient `a` et `team/b`, les inscriptions sont inchangées octet pour octet, et un dossier non inscrit sous `R` est absent de `/workspace/projects`

#### Scenario: Projet inscrit à la racine
- **GIVEN** un projet inscrit avec `rootRelativePath=.`
- **WHEN** la migration est exécutée
- **THEN** aucun montage de `R` n'est créé et un avertissement nomme le projet

#### Scenario: Inscription illisible
- **GIVEN** une inscription illisible dans le registre
- **WHEN** l'installation de cette version est exécutée
- **THEN** elle échoue, le fichier de correspondance reste au format 1 et l'ancien runtime est conservé

### Requirement: La migration d'une racine unique stockée dans PostgreSQL est refusée
Une installation Docker à racine unique dont les projets sont stockés dans PostgreSQL SHALL NOT être migrée : l'installation SHALL échouer avant toute modification, conserver l'ancien runtime, et afficher un message sans chemin qui donne le remède (désinstaller le runtime Docker avec ses données, réinstaller, exposer chaque projet par `AddProject`).

#### Scenario: Runtime PostgreSQL à racine unique
- **GIVEN** des données Docker au format 1 dont `minos.storage.backend` vaut `postgresql`
- **WHEN** l'installation de cette version configure le runtime Docker
- **THEN** elle échoue avec le remède, la configuration et la correspondance sont inchangées et aucune action du cycle de vie n'est lancée

### Requirement: L'installeur Windows ne demande aucune racine des projets Docker
La page Docker de l'installeur Windows SHALL NOT présenter de champ de racine des projets. `/DOCKERPROJECTSROOT`, `-ProjectsRoot` et la valeur `ProjectsRoot` enregistrée SHALL NOT entraîner de montage ; leur présence SHALL être signalée par un avertissement dans le journal d'installation.

#### Scenario: Gabarit de l'installeur
- **GIVEN** `packaging/windows/minos-installer.iss.template`
- **WHEN** `scripts/install/verify-installer-template.ps1` est exécuté
- **THEN** il échoue si le gabarit contient `Racine des projets` ou transmet `-ProjectsRoot`

#### Scenario: Paramètre hérité en mode silencieux
- **GIVEN** une installation silencieuse lancée avec `/DOCKERPROJECTSROOT=<dossier>`
- **WHEN** le commutateur de backend configure le runtime Docker
- **THEN** la table des montages ne contient pas ce dossier et le journal contient l'avertissement

### Requirement: Un dossier de projet dangereux est refusé avant tout montage
Le cycle de vie Docker SHALL refuser, avant toute écriture, un dossier de projet à exposer qui est une racine de volume, le profil ou l'un de ses parents, ou qui est, contient ou se trouve dans `%LOCALAPPDATA%`, `%APPDATA%` ou un dossier de MINOS. La migration d'une racine unique SHALL NOT monter un tel dossier. Le message SHALL NOT contenir de chemin. Un profil introuvable SHALL entraîner le refus.

#### Scenario: Profil passé à AddProject
- **GIVEN** une installation Docker
- **WHEN** `AddProject -ProjectPath` désigne le profil, l'un de ses parents ou une racine de volume
- **THEN** l'action échoue avec un message qui nomme la catégorie refusée, et la table des montages est inchangée

#### Scenario: Dossier de MINOS à migrer
- **GIVEN** des données Docker au format 1 et un projet inscrit dont le dossier se trouve dans `%LOCALAPPDATA%`
- **WHEN** la migration est exécutée
- **THEN** ce projet n'est pas monté et un avertissement le nomme

#### Scenario: Dossier de projets dédié
- **GIVEN** un sous-dossier du profil hors AppData, ou un dossier d'un autre volume
- **WHEN** il est passé à `AddProject`
- **THEN** il est accepté

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

### Requirement: pgAdmin est un service optionnel publié sur la seule boucle locale
Le runtime Docker SHALL fournir pgAdmin comme un service hors profil par défaut, publié uniquement sur la boucle locale de l'hôte. PostgreSQL SHALL rester non publié. Les mots de passe SHALL provenir de fichiers privés hors du dépôt.

#### Scenario: Installation sans pgAdmin
- **GIVEN** une installation Docker démarrée sans le profil `pgadmin`
- **WHEN** les conteneurs et les ports publiés sont listés
- **THEN** aucun conteneur pgAdmin n'existe et aucun port n'est publié

#### Scenario: pgAdmin activé
- **GIVEN** le profil `pgadmin` activé
- **WHEN** les ports publiés sont listés
- **THEN** le seul port publié est celui de pgAdmin, lié à `127.0.0.1`
- **AND** `minos-postgres` ne publie aucun port

#### Scenario: Secret absent
- **GIVEN** le profil `pgadmin` activé sans fichier de mot de passe
- **WHEN** le service est démarré
- **THEN** le démarrage échoue en nommant la variable manquante

### Requirement: pgAdmin se connecte par un rôle PostgreSQL limité à la lecture
Le serveur préenregistré dans pgAdmin SHALL utiliser un rôle PostgreSQL dédié qui ne détient que des droits `SELECT` sur une liste fermée de tables et de colonnes, sans la racine des projets. La lecture seule SHALL tenir aux droits, pas à un réglage de session.

#### Scenario: Écriture refusée
- **GIVEN** une session ouverte avec le rôle d'inspection, `default_transaction_read_only` remis à `off`
- **WHEN** un `DELETE` est tenté sur `semantic_documents`
- **THEN** PostgreSQL refuse avec « permission denied »

#### Scenario: Racine des projets non lisible
- **GIVEN** une session ouverte avec le rôle d'inspection
- **WHEN** `SELECT root_value FROM projects` est exécuté
- **THEN** PostgreSQL refuse avec « permission denied »

### Requirement: Retirer pgAdmin ne supprime aucune donnée MINOS
L'action de retrait de pgAdmin SHALL supprimer son conteneur, son volume de configuration, ses fichiers privés et le rôle d'inspection, et SHALL NOT modifier le volume PostgreSQL ni les données MINOS.

#### Scenario: Retrait
- **GIVEN** une installation avec pgAdmin activé et un projet indexé
- **WHEN** l'action de retrait est exécutée
- **THEN** le conteneur, le volume de pgAdmin et le rôle d'inspection n'existent plus
- **AND** le nombre de lignes de `semantic_documents` et le volume PostgreSQL sont inchangés

### Requirement: Le contrat de l'image de requête publie les seuils de relations calculés
Le test de contrat de l'image de requête SHALL calculer, index des arêtes entrantes compris, pour la limite mémoire par défaut et pour une vue sans symbole ni occurrence, le plus grand nombre de relations sous lequel une vue au format courant de la taille du plafond de persistance tient sous le plafond du cache, puis sous le budget de mémoire des vues. Il SHALL échouer si la documentation utilisateur des limites de ressources n'annonce pas ces deux nombres et leur hypothèse.

#### Scenario: Seuils annoncés égaux aux seuils calculés
- **GIVEN** les deux fichiers compose, les limites de snapshot de production et `docs/user/docker-runtime.md`
- **WHEN** le test de contrat calcule les deux seuils et lit la documentation
- **THEN** la documentation annonce les deux nombres calculés, dit qu'ils valent pour une vue sans symbole ni occurrence, et le test passe

#### Scenario: Réglage modifié sans mise à jour de la documentation
- **GIVEN** une valeur de `MaxRAMPercentage`, de limite mémoire par défaut ou de plafond qui change l'un des seuils, la documentation restant inchangée
- **WHEN** le test de contrat s'exécute
- **THEN** il échoue en nommant le seuil calculé et la valeur annoncée

### Requirement: La zone de travail PostgreSQL du plan de requête est dimensionnée pour le nombre déclaré de JVM
Chaque service du plan de requête qui monte la zone de travail PostgreSQL SHALL lui donner une taille au moins égale au nombre déclaré de JVM simultanées multiplié par la plus grande charge utile PostgreSQL admissible sous le budget de mémoire des vues par défaut. La somme des tas de ces JVM et de cette taille SHALL rester sous la limite mémoire par défaut du service. La documentation utilisateur SHALL annoncer ce nombre de JVM.

#### Scenario: Taille suffisante pour le nombre déclaré de JVM
- **GIVEN** `docker/compose-mcp.connected.yaml`, les limites de snapshot de production et le nombre de JVM annoncé par `docs/user/docker-runtime.md`
- **WHEN** le test de contrat calcule la plus grande charge utile admissible par JVM et lit la taille de la zone de `minos-mcp` et de `minos-explorer`
- **THEN** chaque taille est supérieure ou égale au produit calculé
- **AND** la somme des tas et de la taille de la zone ne dépasse pas la limite mémoire par défaut du rôle requêtes

#### Scenario: Zone réduite sous la borne
- **GIVEN** une taille de zone inférieure au produit calculé dans l'un des deux services
- **WHEN** le test de contrat s'exécute
- **THEN** il échoue en nommant le service, la taille déclarée et la taille minimale calculée

#### Scenario: Nombre de JVM non annoncé
- **GIVEN** une documentation utilisateur qui n'annonce plus le nombre de JVM simultanées retenu
- **WHEN** le test de contrat s'exécute
- **THEN** il échoue en nommant la page et la valeur attendue

### Requirement: Le réglage de taille de la zone de travail survit à la régénération du fichier d'environnement
Une taille de zone de travail PostgreSQL réglée dans le fichier d'environnement du runtime Docker SHALL être reportée dans le fichier régénéré par une mise à jour ou une réinstallation, au même titre que les plafonds de ressources. Une valeur qui n'est pas une taille strictement positive SHALL être refusée en nommant la variable, sans rien écrire.

#### Scenario: Valeur reprise
- **GIVEN** un fichier d'environnement existant qui règle la taille de la zone de travail
- **WHEN** la fonction de reprise des réglages des scripts d'installation le lit
- **THEN** la ligne de ce réglage figure parmi les lignes reportées, dans les deux scripts qui portent la fonction

#### Scenario: Valeur invalide refusée
- **GIVEN** un fichier d'environnement dont la taille de zone vaut `0`, est vide ou n'est pas une taille
- **WHEN** la fonction de reprise le lit
- **THEN** elle échoue par une erreur qui nomme la variable
- **AND** aucun fichier d'environnement n'est écrit

### Requirement: Le budget des vues et les caches applicatifs tiennent sous le tas par défaut du plan de requête
Sous la limite mémoire et le pourcentage de tas par défaut du plan de requête, la somme du budget de mémoire des vues, de la marge de décodage d'une vue déclarée et des plafonds effectifs de tous les caches applicatifs pondérés SHALL être inférieure ou égale au tas maximal de la JVM. Le test de contrat de l'image de requête SHALL calculer cette somme depuis les fichiers compose et les valeurs du code, et SHALL échouer en nommant chaque terme quand elle dépasse le tas.

#### Scenario: Somme sous le tas par défaut
- **GIVEN** les deux fichiers compose, le pourcentage de tas déclaré, la marge de décodage et les parts du tas déclarées dans le code
- **WHEN** le test de contrat calcule le tas par défaut, le budget des vues, la marge de décodage et le plafond effectif de chaque cache, en stockage local puis PostgreSQL
- **THEN** la somme est inférieure ou égale au tas

#### Scenario: Part relevée au-delà du tas
- **GIVEN** une part du tas, une marge ou un plafond relevé de sorte que la somme dépasse le tas par défaut
- **WHEN** le test de contrat s'exécute
- **THEN** il échoue en nommant le tas, le budget des vues, la marge de décodage et le plafond effectif de chaque cache

#### Scenario: Cache pondéré ajouté sans part déclarée
- **GIVEN** un cache pondéré construit en production sans passer par la part du tas, qu'il soit un cache LRU pondéré ou un cache nommé dans la liste fermée du recensement
- **WHEN** le test qui recense les caches pondérés s'exécute
- **THEN** il échoue en nommant la classe qui construit ce cache

### Requirement: Les fichiers de distribution Docker ne présentent pas le plan d'administration comme lançant des providers
Les fichiers compose et le Dockerfile de release SHALL décrire le service `minos-admin` par ce qu'il exécute : administration, inventaire et vérification des outils, indexation refusée faute de bac à sable OS. Ils SHALL NOT affirmer que ce service lance un provider pour indexer. Le test de contrat Docker SHALL échouer sur une telle affirmation dans le bloc du service `minos-admin`. Le service de sonde des outils n'est pas concerné.

#### Scenario: Commentaire qui annonce le lancement des providers
- **GIVEN** une copie d'un fichier compose où le commentaire de `minos-admin` dit que l'indexation lance les providers
- **WHEN** `./mvnw -B -ntp -pl minos-app -am test "-Dtest=M29DockerAdministrationContractTest" "-Dsurefire.failIfNoSpecifiedTests=false"` est exécuté sur cette copie
- **THEN** le test échoue en nommant le fichier et la ligne

#### Scenario: Fichiers réels
- **GIVEN** les deux fichiers compose et le Dockerfile de release du dépôt après le changement
- **WHEN** le même test est exécuté
- **THEN** il réussit
- **AND** le commentaire de `minos-admin` dit que l'indexation y est refusée

#### Scenario: Service de sonde des outils
- **GIVEN** les fichiers compose du dépôt, où le service `minos-provider-probe` est décrit comme sondant `javac`, `mvn`, les indexeurs et les toolchains
- **WHEN** le même test est exécuté
- **THEN** ce commentaire n'est pas signalé

#### Scenario: Le runtime confirme ce que disent les fichiers
- **GIVEN** une installation Docker de qualification
- **WHEN** `scripts/ci/qualify-docker-upgrade.ps1` lance `index` dans le plan d'administration et lit l'état des providers
- **THEN** l'indexation est refusée avant tout processus fils et scip-java est déclaré `UNSUPPORTED_BY_BACKEND`

### Requirement: Le journal des services Docker publiés sur la boucle locale est borné
Les services `minos-explorer` et `minos-pgadmin` du fichier Compose connecté SHALL déclarer le pilote de journal `json-file` avec une taille maximale par fichier et un nombre maximal de fichiers. Le gate des plafonds Compose SHALL échouer si l'un de ces services ne borne pas son journal.

#### Scenario: Services bornés
- **GIVEN** le fichier `docker/compose-mcp.connected.yaml` du dépôt
- **WHEN** `python scripts/quality/check-compose-limits.py` est exécuté
- **THEN** il réussit, et les deux services portent `max-size` et `max-file`

#### Scenario: Borne retirée
- **GIVEN** un arbre où le service `minos-explorer` n'a plus de clé `logging`, ou a `max-size` sans `max-file`
- **WHEN** le gate est exécuté sur cet arbre (`python scripts/quality/test_check_compose_limits.py`)
- **THEN** il échoue en nommant le service et la clé manquante

#### Scenario: Pilote sans borne
- **GIVEN** un arbre où le service déclare un pilote de journal autre que `json-file` sans option de taille
- **WHEN** le gate est exécuté
- **THEN** il échoue

## Documentation liée

- Dossier durable (vues, traçabilité, écarts) : [distribution-docker](../../../docs/architecture/diagrams/capacites/distribution-docker.md)
