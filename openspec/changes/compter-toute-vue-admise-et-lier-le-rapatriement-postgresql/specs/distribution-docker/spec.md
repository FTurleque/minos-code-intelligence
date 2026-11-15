# Spec Delta

## ADDED Requirements

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
