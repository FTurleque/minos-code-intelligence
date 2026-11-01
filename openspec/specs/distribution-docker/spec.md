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
- **WHEN** le test de contrat Docker et `python scripts/quality/check-compose-limits.py` sont exécutés
- **THEN** les deux réussissent et chaque service a une image de l'une des deux variables

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

### Requirement: L'installeur Windows ne propose aucune racine de projets par défaut
Sans réglage enregistré ni paramètre `/DOCKERPROJECTSROOT`, la page Docker de l'installeur Windows SHALL présenter le champ « Racine des projets » vide, et SHALL NOT passer à la page suivante tant qu'il est vide. Elle SHALL NOT proposer le profil de l'utilisateur.

#### Scenario: Première installation
- **GIVEN** un poste sans clé `HKCU\Software\FTurleque\MINOS` et un installeur lancé sans `/DOCKERPROJECTSROOT`
- **WHEN** l'utilisateur choisit le backend Docker et arrive sur la page Docker
- **THEN** le champ « Racine des projets » est vide

#### Scenario: Champ laissé vide
- **GIVEN** la page Docker avec un champ « Racine des projets » vide
- **WHEN** l'utilisateur clique sur Suivant
- **THEN** l'assistant reste sur la page et demande de choisir un dossier

### Requirement: Une racine de projets dangereuse est refusée avant tout montage
L'installeur Windows, le commutateur de backend et l'installation ZIP SHALL refuser, avant toute écriture, une racine de projets qui est une racine de volume, le profil ou l'un de ses parents, ou qui est, contient ou se trouve dans `%LOCALAPPDATA%`, `%APPDATA%` ou un dossier de MINOS. Le message SHALL NOT contenir de chemin. Un profil introuvable SHALL entraîner le refus.

#### Scenario: Profil saisi dans l'assistant
- **GIVEN** la page Docker de l'installeur
- **WHEN** l'utilisateur saisit son profil, l'un de ses parents ou une racine de volume puis clique sur Suivant
- **THEN** l'assistant reste sur la page avec un message qui nomme la catégorie refusée

#### Scenario: Paramètre de ligne de commande ou installation silencieuse
- **GIVEN** un installeur lancé avec `/DOCKERPROJECTSROOT` désignant le profil
- **WHEN** le commutateur de backend est appelé
- **THEN** il échoue avant de préparer le runtime Docker et le backend précédent est conservé

#### Scenario: Installation ZIP
- **GIVEN** `install-windows.ps1 -McpBackend docker -ProjectsRoot` désignant `%LOCALAPPDATA%` ou l'un de ses sous-dossiers
- **WHEN** le script est exécuté
- **THEN** il échoue avant d'extraire le paquet ou de toucher l'installation existante

#### Scenario: Dossier de projets dédié
- **GIVEN** un sous-dossier du profil hors AppData, ou un dossier d'un autre volume
- **WHEN** il est choisi comme racine des projets
- **THEN** il est accepté
