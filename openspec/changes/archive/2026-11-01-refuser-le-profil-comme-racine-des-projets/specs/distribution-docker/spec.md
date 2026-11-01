## ADDED Requirements

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
