# Spec Delta

## ADDED Requirements

### Requirement: Une zone de travail PostgreSQL insuffisante donne un refus explicite
Le store PostgreSQL SHALL vérifier, avant de demander une charge utile, que sa zone de travail peut la recevoir. Une zone insuffisante, constatée avant la demande ou pendant la copie, SHALL faire échouer la lecture par une erreur d'E/S dont le message commence par « insufficient scratch space to load this snapshot », donne la taille de la charge utile et l'espace disponible en octets, sans chemin. Elle SHALL NOT se manifester par l'erreur brute du système de fichiers.

#### Scenario: Espace insuffisant avant le rapatriement
- **GIVEN** un snapshot actif dont la charge utile dépasse l'espace disponible de la zone de travail
- **WHEN** sa vue active est demandée
- **THEN** la lecture échoue par l'erreur de zone de travail insuffisante, qui donne les deux tailles en octets
- **AND** la charge utile n'a pas été demandée au serveur, aucun fichier temporaire ne subsiste et aucune réservation n'est retenue

#### Scenario: Zone épuisée pendant la copie
- **GIVEN** une zone de travail suffisante au contrôle, qu'une autre écriture remplit pendant la copie de la charge utile
- **WHEN** l'écriture du fichier temporaire échoue faute d'espace
- **THEN** la lecture échoue par la même erreur de zone de travail insuffisante
- **AND** le fichier temporaire partiel est supprimé et la réservation de mémoire est libérée

#### Scenario: Message sans chemin
- **GIVEN** un refus de zone de travail insuffisante
- **WHEN** son message est contrôlé par le détecteur de messages sensibles du dépôt
- **THEN** il ne contient aucun chemin absolu ni nom de fichier temporaire

#### Scenario: Échec d'écriture qui n'est pas un manque d'espace
- **GIVEN** une zone de travail dont l'espace disponible couvre largement la charge utile
- **WHEN** l'écriture du fichier temporaire échoue pour une autre cause
- **THEN** la lecture échoue par l'erreur d'E/S d'origine, sans être requalifiée en manque d'espace

### Requirement: Un refus de zone de travail est rendu par la CLI et le MCP avec son message
Une erreur de zone de travail insuffisante SHALL être rendue sur la CLI avec son message et le code de sortie d'erreur d'exécution existant, et par un outil MCP avec le même message au lieu de l'erreur générique. Le rendu SHALL rester additif : aucun code de sortie nouveau, aucun champ retiré, goldens inchangés.

#### Scenario: Outil MCP refusé faute de zone de travail
- **GIVEN** un backend MCP dont la lecture de vue échoue par l'erreur de zone de travail insuffisante
- **WHEN** un outil de requête est appelé
- **THEN** le résultat est une erreur d'outil dont le texte commence par « error: insufficient scratch space to load this snapshot »

#### Scenario: Commande CLI refusée faute de zone de travail
- **GIVEN** une application dont la lecture de vue échoue par l'erreur de zone de travail insuffisante
- **WHEN** une commande de requête de la CLI s'exécute
- **THEN** elle écrit le message sur la sortie d'erreur et se termine avec le code 1
- **AND** les goldens de caractérisation restent identiques à l'octet
