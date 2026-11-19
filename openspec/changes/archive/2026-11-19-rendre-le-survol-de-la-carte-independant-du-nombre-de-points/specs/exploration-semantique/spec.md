## ADDED Requirements

### Requirement: Le point sous le pointeur est cherché parmi les points voisins seulement
La page de l'explorateur SHALL trouver le point de la carte sous le pointeur en n'examinant que les points des cellules voisines du pointeur, et non tous les points affichés. Le point trouvé SHALL être celui que rendrait l'examen de tous les points affichés, avec le même rayon de capture. Sans point dans ce rayon, la page SHALL ne désigner aucun point.

#### Scenario: Même point que le parcours complet
- **GIVEN** une carte de 100 000 points répartis sur toute sa surface, dans un canevas de 800 × 600, à plusieurs niveaux de zoom et de décalage
- **WHEN** le point sous 1 000 positions de pointeur est cherché par la page et par un parcours de référence de tous les points
- **THEN** les deux désignent le même point pour chaque position, à chaque zoom
- **AND** au zoom 1, le nombre de points examinés par la page pour une position est inférieur au centième du nombre de points affichés

#### Scenario: Aucun point dans le rayon
- **GIVEN** une carte dont aucun point n'est à moins du rayon de capture du pointeur
- **WHEN** l'utilisateur clique à cette position
- **THEN** aucun point n'est désigné
- **AND** aucune requête de détail n'est émise

### Requirement: La liste des points d'une nature n'est calculée qu'à son changement
La page de l'explorateur SHALL calculer la liste des points affichés pour le filtre de nature courant quand ce filtre ou les points de la carte changent, et SHALL la réutiliser pour le survol, le clic et le dessin tant qu'aucun des deux ne change.

#### Scenario: Survols successifs sous un filtre
- **GIVEN** une carte chargée et le filtre de nature `CHUNK` choisi
- **WHEN** deux survols se suivent sans changement de filtre ni de points
- **THEN** la même liste de points affichés sert aux deux, sans nouveau filtrage

#### Scenario: Changement de filtre
- **GIVEN** la carte précédente
- **WHEN** le filtre passe à `SYMBOL` puis un survol a lieu
- **THEN** seuls des points de nature `SYMBOL` peuvent être désignés
- **AND** aucun point de nature `CHUNK` n'est examiné

### Requirement: Le survol de la carte est traité au plus une fois par image
La page de l'explorateur SHALL traiter le survol de la carte au plus une fois par image affichée, avec la dernière position connue du pointeur. Un clic SHALL toujours désigner le point à la position du clic, sans attendre l'image suivante.

#### Scenario: Rafale de mouvements
- **GIVEN** une carte chargée et vingt mouvements de pointeur reçus avant l'image suivante
- **WHEN** l'image suivante est produite
- **THEN** une seule recherche de point a eu lieu, à la position du dernier mouvement

#### Scenario: Clic entre deux images
- **GIVEN** un mouvement de pointeur en attente de traitement
- **WHEN** l'utilisateur clique sur un point avant l'image suivante
- **THEN** le détail du point cliqué est demandé
