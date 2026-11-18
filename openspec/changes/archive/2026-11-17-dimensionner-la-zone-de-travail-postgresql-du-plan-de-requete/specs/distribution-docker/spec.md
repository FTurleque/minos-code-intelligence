# Spec Delta

## ADDED Requirements

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
