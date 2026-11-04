## MODIFIED Requirements

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
