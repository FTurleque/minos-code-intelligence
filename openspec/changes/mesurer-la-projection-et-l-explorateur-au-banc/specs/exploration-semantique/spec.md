## ADDED Requirements

### Requirement: Le banc de scalabilité produit une référence pour la projection et les routes de l'explorateur
Le banc `benchmarks/scalability/` SHALL mesurer, sur des index sémantiques de tailles et de dimension déclarées dans sa configuration, la préparation d'une projection et chaque route de lecture de l'explorateur servie en HTTP sur la boucle locale, pour le stockage local et pour PostgreSQL. Il SHALL écrire ses résultats hors du dépôt et SHALL NOT s'exécuter pendant `mvn verify`.

#### Scenario: Mesure sur le stockage local
- **GIVEN** la configuration sémantique du banc et un dossier de sortie hors du dépôt
- **WHEN** le script de lancement du banc sémantique est exécuté
- **THEN** `result.tsv` contient, pour chaque taille d'index, la durée, le nombre de parcours de l'index, les vecteurs lus et la mémoire de travail de pointe de la préparation, puis la latence p50 et p95, les octets alloués par requête et le tas retenu des routes liste, détail, voisins, état de projection, points et recherche
- **AND** `git status --porcelain` du dépôt est inchangé

#### Scenario: Deux exécutions comparables
- **GIVEN** deux exécutions du banc avec la même configuration et la même graine
- **WHEN** leurs résultats sont comparés
- **THEN** le nombre de documents, le nombre de passes et le nombre de vecteurs lus sont identiques

#### Scenario: Sortie dans le dépôt refusée
- **GIVEN** un dossier de sortie situé dans le dépôt
- **WHEN** le script de lancement est exécuté
- **THEN** il se termine avec un code non nul avant toute mesure et n'écrit aucun fichier

#### Scenario: Banc absent du build
- **GIVEN** `./mvnw -B -ntp -pl minos-explorer -am test`
- **WHEN** le rapport Surefire est lu
- **THEN** la classe principale du banc n'y figure pas comme test

### Requirement: Une mesure impossible est nommée dans le résultat
Quand une section du banc sémantique ne peut pas être mesurée, le résultat SHALL contenir pour cette section et ce jeu une ligne qui en donne la raison, sans chemin absolu ni secret, et aucune valeur. Le banc SHALL poursuivre les autres sections. Il SHALL se terminer par un code non nul si un refus n'était pas déclaré attendu, ou si un refus attendu ne s'est pas produit.

#### Scenario: PostgreSQL non configuré
- **GIVEN** une configuration qui demande la section PostgreSQL et aucune URL de serveur passée au banc
- **WHEN** le banc est exécuté
- **THEN** `result.tsv` contient une ligne de refus pour la section PostgreSQL qui nomme le paramètre manquant, les sections locales sont mesurées et le code de sortie est non nul

#### Scenario: Refus attendu au-delà du budget de décodage local
- **GIVEN** un jeu du stockage local plus grand que ce que le magasin décode en entier, déclaré à refus attendu pour la liste, le détail, les voisins et la recherche sémantique
- **WHEN** le banc interroge ces routes
- **THEN** `result.tsv` contient une ligne de refus par route avec le motif rendu par le produit, aucune latence pour ces routes, et le code de sortie est nul

#### Scenario: Refus attendu qui ne se produit plus
- **GIVEN** le même jeu et un magasin qui sert désormais ces routes
- **WHEN** le banc est exécuté
- **THEN** les latences sont écrites et le code de sortie est non nul, avec un message qui demande de retirer la déclaration de refus attendu

#### Scenario: Préparation refusée par le budget
- **GIVEN** un index dont la préparation dépasse le budget de mémoire de travail demandé
- **WHEN** le banc mesure la préparation
- **THEN** la ligne de refus reprend le motif rendu par le service de projection et aucune durée de préparation n'est écrite pour ce jeu

### Requirement: Le harnais du banc sémantique est gardé par un test du build
Un test exécuté par `mvn verify` SHALL lancer le harnais du banc sémantique sur un index minuscule du stockage local et SHALL vérifier que chaque mesure attendue de la préparation et de chaque route est émise avec une valeur. Une route de l'explorateur ajoutée sans mesure correspondante SHALL faire échouer ce test.

#### Scenario: Toutes les mesures sont émises
- **GIVEN** un index local de quelques centaines de documents construit par le provider déterministe
- **WHEN** `./mvnw -B -ntp -pl minos-explorer -am test` exécute le test de fumée du banc
- **THEN** le test réussit et la liste des mesures émises égale la liste attendue

#### Scenario: Route non mesurée
- **GIVEN** une route de lecture de l'explorateur absente de la table des routes mesurées du harnais
- **WHEN** le test de fumée s'exécute
- **THEN** il échoue en nommant la route
