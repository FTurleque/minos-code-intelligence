## ADDED Requirements

### Requirement: La page de l'explorateur est exécutée par des tests et contrôlée avant toute fusion
Le script de la page de l'explorateur SHALL être analysé syntaxiquement et exécuté par des tests automatisés, sans dépendance téléchargée, par un gate du job des invariants. Le gate SHALL échouer quand le script ne s'analyse pas, quand un test échoue, quand aucun test n'est exécuté ou quand l'interpréteur JavaScript est absent ou plus ancien que la version minimale déclarée ; il SHALL NOT compter ces cas comme sautés.

#### Scenario: État du dépôt
- **GIVEN** le dépôt au commit candidat et Node installé
- **WHEN** `python scripts/quality/check-explorer-front.py` est exécuté
- **THEN** il se termine avec le code 0 et affiche le nombre de tests exécutés, supérieur à zéro

#### Scenario: Erreur de syntaxe
- **GIVEN** une copie de `app.js` où une accolade fermante est retirée
- **WHEN** `python scripts/quality/check-explorer-front.py --self-test` l'évalue
- **THEN** le cas constate le code 1 et un message qui nomme le fichier

#### Scenario: Interpréteur absent
- **GIVEN** un `PATH` sans Node
- **WHEN** le gate est exécuté
- **THEN** il se termine avec le code 1 et un message qui nomme Node et la version minimale, sans afficher de succès

#### Scenario: Aucun test trouvé
- **GIVEN** un dossier de tests du front vide
- **WHEN** l'auto-test du gate l'évalue
- **THEN** le cas constate le code 1

#### Scenario: Gate non câblé
- **GIVEN** un `pr-ci.yml` dont le job `invariants` ne lance pas `check-explorer-front.py --self-test`
- **WHEN** `python scripts/quality/check-ci-wiring.py` est exécuté
- **THEN** il se termine avec le code 1 et nomme le script

### Requirement: Aucun contenu indexé n'atteint un puits de balisage, prouvé par exécution
Les tests du front SHALL rejouer chaque rendu de la page (liste, détail, voisins, recherche, textes et survol de la carte, bandeaux) avec des valeurs indexées contenant du balisage, dans un environnement qui refuse toute écriture de balisage dans le document et toute génération de code depuis une chaîne. Un rendu qui emprunte un tel puits SHALL faire échouer le test, quel que soit le nom du puits.

#### Scenario: Contenu hostile rendu en texte
- **GIVEN** des réponses d'API simulées dont le nom de symbole, le chemin et le contenu valent `<script>alert(1)</script>`
- **WHEN** les tests du front rendent la liste, le détail et les voisins
- **THEN** chaque valeur est retrouvée telle quelle comme texte d'un nœud, et aucune écriture de balisage n'a été tentée

#### Scenario: Puits introduit sous un nom non listé
- **GIVEN** une copie de `app.js` où un rendu écrit par `insertAdjacentHTML` ou par un nom de propriété calculé
- **WHEN** l'auto-test du gate exécute les tests sur cette copie
- **THEN** le test de puits échoue

#### Scenario: Génération de code depuis une chaîne
- **GIVEN** une copie de `app.js` qui appelle `eval` ou `new Function` sur une valeur
- **WHEN** les tests s'exécutent sur cette copie
- **THEN** l'appel est refusé par l'environnement et le test échoue

### Requirement: La carte et la traduction des états ont un comportement caractérisé par des tests
Les tests du front SHALL fixer, sur des jeux de points déterministes, le point désigné par le pointeur, les points visibles sous un filtre de nature, les bornes et le point fixe du zoom, la suite des requêtes de lecture des points, et le nombre de recherches de point et de tracés par rafale de déplacements. Ils SHALL vérifier que chaque état, divergence et cause de péremption du contrat est affiché par un libellé de la page et jamais par sa constante brute.

#### Scenario: Point désigné
- **GIVEN** un jeu de points engendré par une graine fixe et une position du pointeur
- **WHEN** le test interroge la sélection du point
- **THEN** le point rendu est le plus proche dans le rayon de capture, et aucun point n'est rendu au-delà de ce rayon

#### Scenario: Filtre de nature
- **GIVEN** des points de deux natures et un filtre sur l'une
- **WHEN** le test lit les points visibles
- **THEN** seuls les points de cette nature sont rendus, dans l'ordre de réception

#### Scenario: Lecture paginée des points
- **GIVEN** une projection simulée de trois pages
- **WHEN** la carte est chargée
- **THEN** les requêtes portent les décalages successifs attendus et s'arrêtent quand le total est atteint ou quand l'état change

#### Scenario: État sans libellé
- **GIVEN** la table des constantes du contrat tenue par les tests, augmentée d'un état inconnu de la page
- **WHEN** le test de traduction s'exécute
- **THEN** il échoue en nommant la constante sans libellé

### Requirement: Un échec de copie de la référence est signalé dans la page
Quand la copie de la référence d'un document dans le presse-papiers est refusée ou échoue, la page SHALL l'indiquer par un message et SHALL NOT laisser de promesse rejetée sans traitement. Une copie réussie SHALL rester sans message d'erreur.

#### Scenario: Autorisation refusée
- **GIVEN** un presse-papiers simulé dont l'écriture est rejetée
- **WHEN** le test active « Copier la référence »
- **THEN** le bandeau de la page affiche un message d'échec de copie et aucun rejet non géré n'est signalé par l'environnement de test

#### Scenario: Copie réussie
- **GIVEN** un presse-papiers simulé qui accepte l'écriture
- **WHEN** le test active « Copier la référence »
- **THEN** le texte copié est l'identifiant du fichier suivi de ses lignes, et le bandeau reste inchangé
