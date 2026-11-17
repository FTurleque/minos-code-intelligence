# exploration-semantique Specification

## Purpose
Permettre à l'utilisateur d'un poste d'explorer, depuis un navigateur local et en lecture seule, les documents sémantiques d'un projet MINOS : liste paginée, détail, recherche, documents voisins et carte 2D de leurs vecteurs. La projection de la carte est préparée par une commande explicite, jamais par la consultation.

## Requirements

### Requirement: La surface d'exploration est optionnelle et n'écoute que sur la boucle locale
Le système SHALL ne démarrer la surface HTTP d'exploration que par la commande `minos explorer serve`, et SHALL n'écouter que sur la boucle locale. Aucune autre surface ne MUST en dépendre. La commande SHALL refuser de démarrer quand le mode équipe est activé.

#### Scenario: Aucune écoute sans la commande
- **GIVEN** un `MINOS_HOME` valide
- **WHEN** une commande CLI autre que `explorer serve`, ou le serveur MCP, s'exécute
- **THEN** le processus n'ouvre aucun port en écoute

#### Scenario: Écoute limitée à la boucle locale
- **GIVEN** `minos explorer serve` démarré
- **WHEN** une connexion est tentée sur le port depuis une adresse non locale de la machine
- **THEN** la connexion est refusée
- **AND** l'URL imprimée sur `stderr` désigne `127.0.0.1`

#### Scenario: Refus en mode équipe
- **GIVEN** `MINOS_HOSTED_MODE=enabled`
- **WHEN** `minos explorer serve` est lancé
- **THEN** la commande échoue avec une erreur d'usage actionnable et un code de sortie non nul
- **AND** aucun port n'est ouvert

### Requirement: Toute requête d'API exige le jeton de session et une origine locale
Le système SHALL refuser toute requête `/api/` qui ne porte pas le jeton de session généré au démarrage, dont l'en-tête `Host` n'est pas une adresse de boucle locale servie, ou dont l'en-tête `Origin` désigne une autre origine. Le système SHALL NOT émettre d'en-tête CORS ni de cookie.

#### Scenario: Requête sans jeton
- **GIVEN** la surface démarrée
- **WHEN** une requête `GET /api/v1/projects` arrive sans jeton ou avec un jeton erroné
- **THEN** la réponse est `401` sans détail
- **AND** aucun service MINOS n'est appelé

#### Scenario: Hôte détourné
- **GIVEN** la surface démarrée et un jeton valide
- **WHEN** une requête porte `Host: attaquant.example:8765`
- **THEN** la réponse est `403`

#### Scenario: Page tierce
- **GIVEN** la surface démarrée et un jeton valide
- **WHEN** une requête porte `Origin: https://autre.example`
- **THEN** la réponse est `403`
- **AND** elle ne contient aucun en-tête `Access-Control-Allow-Origin`

### Requirement: La surface d'exploration ne modifie aucun état
Le système SHALL NOT exposer de route qui écrit, synchronise un index sémantique, prépare une projection ou change une configuration. Une consultation SHALL NOT déclencher de vectorisation du corpus ni de calcul de projection.

#### Scenario: Données inchangées après consultation
- **GIVEN** un projet dont l'index est `READY` et un `MINOS_HOME` dont l'arborescence est relevée
- **WHEN** la liste, le détail, les voisins, le statut et les points de projection sont consultés
- **THEN** l'arborescence de `MINOS_HOME` et l'index sont identiques au relevé

### Requirement: La liste des documents est paginée et lue sur un seul index
Le système SHALL rendre les documents sémantiques d'un projet par pages bornées, ordonnées par clé stable, avec un curseur. Chaque page SHALL porter l'identité de l'index lu (snapshot, provider, modèle, dimensions). Le système SHALL refuser un curseur émis pour une autre identité d'index.

#### Scenario: Parcours complet
- **GIVEN** un index de 120 documents et une taille de page de 50
- **WHEN** les pages sont lues jusqu'à épuisement du curseur
- **THEN** les 120 documents sont rendus une fois chacun, en ordre de clé stable

#### Scenario: Taille de page hors bornes
- **GIVEN** un index `READY`
- **WHEN** une page de 5 000 documents est demandée
- **THEN** la réponse est une erreur d'usage qui nomme la borne

#### Scenario: Index remplacé entre deux pages
- **GIVEN** un curseur émis pour un snapshot
- **WHEN** l'index est remplacé puis le curseur réutilisé
- **THEN** la réponse est un conflit qui demande de relancer le parcours
- **AND** aucun document du nouvel index n'est mêlé aux pages déjà lues

### Requirement: Le détail distingue le contenu indexé de la source actuelle
Le système SHALL rendre séparément le contenu indexé d'un document et l'extrait relu du fichier, et SHALL signaler quand ils diffèrent. La lecture de la source MUST passer par le lecteur de sources confiné à la racine du projet ; aucun chemin n'est accepté du client.

#### Scenario: Source modifiée depuis l'indexation
- **GIVEN** un document `CHUNK` dont le fichier a été modifié après l'indexation
- **WHEN** son détail est demandé
- **THEN** la réponse contient le contenu indexé, l'extrait actuel et l'indicateur de divergence

#### Scenario: Source introuvable
- **GIVEN** un document dont le fichier a été supprimé
- **WHEN** son détail est demandé
- **THEN** la réponse contient le contenu indexé et l'état « source introuvable »
- **AND** elle ne contient aucun chemin absolu

### Requirement: La recherche de l'explorateur rend le classement des services existants et nomme chaque score
Le système SHALL déléguer la recherche aux services de recherche sémantique et hybride sans en changer le classement, et SHALL nommer la nature de chaque score rendu (similarité cosinus, score hybride et ses signaux). Un filtre SHALL retirer des résultats sans modifier un score ni un rang.

#### Scenario: Même classement que la CLI
- **GIVEN** un index `READY` et une requête
- **WHEN** la recherche est faite par l'explorateur et par `semantic-search` avec les mêmes bornes
- **THEN** les deux classements sont identiques, scores compris

#### Scenario: Index non prêt
- **GIVEN** un index `STALE`
- **WHEN** une recherche est demandée
- **THEN** la réponse est vide et porte la limite `SEMANTIC_SEARCH_REQUIRES_READY_INDEX`

### Requirement: Les voisins d'un document sont classés depuis son vecteur stocké
Le système SHALL classer les documents voisins d'un document par similarité cosinus exacte avec son vecteur stocké, dans le même index, sans appeler le provider d'embeddings, et SHALL exclure le document lui-même.

#### Scenario: Provider indisponible
- **GIVEN** un index `READY` et un provider d'embeddings injoignable
- **WHEN** les voisins d'un document sont demandés
- **THEN** la réponse rend les voisins classés
- **AND** aucun appel au provider n'est émis

#### Scenario: Document inconnu
- **GIVEN** une clé stable absente de l'index
- **WHEN** ses voisins sont demandés
- **THEN** la réponse est « introuvable »

### Requirement: Aucune réponse de l'explorateur n'expose de chemin absolu ni de secret
Le système SHALL NOT inclure dans une réponse HTTP, un journal de niveau WARNING ou un message d'erreur la racine absolue d'un projet, une adresse de provider ou un identifiant de connexion. Les ressources web SHALL être servies avec une politique de contenu qui interdit les scripts en ligne et les origines tierces.

#### Scenario: Fiche de projet
- **GIVEN** un projet inscrit
- **WHEN** la liste des projets est lue
- **THEN** aucun champ ne contient la racine absolue du projet

#### Scenario: Contenu indexé contenant du HTML
- **GIVEN** un document dont le contenu contient `<script>alert(1)</script>`
- **WHEN** son détail est lu
- **THEN** la réponse est de type `application/json` avec l'en-tête `X-Content-Type-Options: nosniff`
- **AND** la page est servie avec un en-tête `Content-Security-Policy` sans `unsafe-inline`

### Requirement: Le provider local-hash est signalé comme référence déterministe
Le système SHALL signaler, dans le statut de l'index et dans chaque réponse de recherche ou de voisinage, qu'un index construit par le provider `local-hash` n'est pas issu d'un modèle de langage.

#### Scenario: Index local-hash
- **GIVEN** un index construit avec `minos.semantic.provider=local-hash`
- **WHEN** le statut du projet et une recherche sont lus
- **THEN** chaque réponse porte la limite `LOCAL_HASH_EMBEDDING_NOT_LANGUAGE_MODEL`

### Requirement: La préparation d'une projection est une commande explicite et bornée
Le système SHALL calculer une projection uniquement par la commande `minos semantic projection prepare`, en lisant les vecteurs par lots bornés, avec une graine et un plafond de points explicites. Un dépassement de budget SHALL échouer sans publier.

#### Scenario: Préparation reproductible
- **GIVEN** un index inchangé, une méthode, des paramètres et une graine
- **WHEN** la préparation est lancée deux fois
- **THEN** les deux projections ont les mêmes coordonnées

#### Scenario: Budget dépassé
- **GIVEN** un corpus dont la lecture dépasse le budget de mémoire configuré
- **WHEN** la préparation est lancée
- **THEN** la commande échoue avec une erreur actionnable et un code de sortie non nul
- **AND** la projection publiée auparavant est intacte

### Requirement: Une projection est publiée atomiquement et liée à l'identité de son index
Le système SHALL publier une projection en une opération atomique et SHALL la lier au projet, au snapshot indexé, au provider, au modèle, aux dimensions, à l'empreinte des documents, à la méthode, à ses paramètres, à la graine et à la sélection. Une projection dont l'identité diffère de l'index courant SHALL être déclarée `PERIMEE`.

#### Scenario: Index resynchronisé
- **GIVEN** une projection `PRETE`
- **WHEN** un document de l'index change puis le statut de projection est lu
- **THEN** l'état rendu est `PERIMEE`

#### Scenario: Préparation interrompue
- **GIVEN** une préparation interrompue avant sa fin
- **WHEN** le statut de projection est lu
- **THEN** aucune projection partielle n'est lisible
- **AND** la projection précédente, si elle existe, reste rendue avec son propre état

### Requirement: Les points d'une projection annoncent méthode, sélection et échantillonnage
Le système SHALL rendre avec les points la méthode, ses paramètres, la graine, le nombre de points et le nombre de documents de l'index, et SHALL indiquer tout échantillonnage. Les points SHALL provenir d'un seul index.

#### Scenario: Corpus échantillonné
- **GIVEN** un index de 1 000 documents et une projection préparée avec un plafond de 200 points
- **WHEN** les points sont lus
- **THEN** la réponse annonce 200 points sur 1 000 documents et l'échantillonnage avec sa graine

### Requirement: La lecture d'une projection ne calcule rien
Le système SHALL rendre le statut et les points d'une projection sans lire de vecteur ni calculer, et SHALL NOT exposer de route HTTP qui prépare une projection. Sans projection préparée, la réponse SHALL indiquer l'état `ABSENTE` et nommer la commande de préparation.

#### Scenario: Projection absente
- **GIVEN** un projet sans projection préparée
- **WHEN** les points de projection sont demandés
- **THEN** la réponse indique l'état `ABSENTE` et nomme la commande de préparation
- **AND** aucun vecteur n'est lu

#### Scenario: Préparation demandée par HTTP
- **GIVEN** la surface d'exploration démarrée et un jeton valide
- **WHEN** une requête `POST` vise la projection d'un projet
- **THEN** la réponse est `405`
- **AND** aucune projection n'est créée ni modifiée

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
Les tests du front SHALL fixer, sur des jeux de points déterministes, le point désigné par le pointeur, les points visibles sous un filtre de nature, les bornes et le point fixe du zoom, la suite des lectures de points et le nombre de recherches et de tracés par rafale de déplacements. Ils SHALL vérifier que le badge, le bandeau d'état, la carte et le détail affichent chaque état, divergence et cause de péremption du contrat par un libellé, jamais par sa constante brute.

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

#### Scenario: Badge d'en-tête traduit
- **GIVEN** un projet dans chacun des états d'index du contrat, `READY` compris
- **WHEN** le test rend l'en-tête de la page
- **THEN** le badge affiche le libellé de l'état (`index : prêt` pour `READY`) et jamais la constante brute ; un état hors du contrat est affiché tel que reçu

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

### Requirement: En stockage local, une page de documents est lue sans charger l'index
En stockage local, le système SHALL rendre une page de documents en parcourant le fichier d'index dans l'ordre des clés stables, en ne décodant que les documents de la page, sans décoder de vecteur et sans charger l'index entier. Un index à l'ancien format, dont l'ordre n'est pas garanti, SHALL rester lu par le chemin chargé.

#### Scenario: Index plus lourd que le plafond du cache
- **GIVEN** un store local dont le plafond de cache est inférieur au poids d'un index de 500 documents
- **WHEN** les pages de 200 documents sont lues jusqu'à épuisement
- **THEN** les 500 documents sont rendus une fois chacun, en ordre de clé stable, identiques à ceux de l'index chargé
- **AND** le compteur de chargements complets du store n'a pas augmenté

#### Scenario: Index remplacé entre deux pages
- **GIVEN** une première page de 20 documents lue sur un index de 60 documents
- **WHEN** l'index est remplacé par un index de 30 documents d'un autre snapshot, puis la page qui suit la 20e clé est demandée
- **THEN** la page porte les métadonnées du nouvel index et ses 10 documents de clé supérieure
- **AND** elle ne contient aucun document de l'ancien index

#### Scenario: Fichier hors ordre
- **GIVEN** un fichier d'index au format courant dont deux clés stables ne se suivent pas en ordre croissant
- **WHEN** une page est lue
- **THEN** la lecture est refusée par une erreur d'index invalide, sans chemin absolu

### Requirement: En stockage local, le détail lit le seul document demandé
En stockage local, le système SHALL rendre un document et son vecteur stocké en ne décodant que ce document, sans charger l'index entier. Une clé absente SHALL rendre les métadonnées de l'index sans document.

#### Scenario: Document présent
- **GIVEN** un index local de 500 documents plus lourd que le plafond du cache
- **WHEN** le document d'une clé stable existante est lu
- **THEN** le document et son vecteur sont égaux à ceux de l'index chargé
- **AND** le compteur de chargements complets du store n'a pas augmenté

#### Scenario: Clé absente
- **GIVEN** le même index
- **WHEN** une clé stable absente est lue
- **THEN** la réponse porte les métadonnées de l'index et aucun document

### Requirement: En stockage local, les voisins sont classés sans retenir l'index
Quand l'index local ne peut pas être tenu par le cache, le système SHALL classer les voisins d'un vecteur par un parcours du fichier qui ne retient ni l'index ni les contenus des documents hors résultat. Le classement et les scores SHALL être ceux du classement sur l'index chargé.

#### Scenario: Même classement que l'index chargé
- **GIVEN** un index local de 500 documents, lu une fois par un store au cache large et une fois par un store au cache trop étroit
- **WHEN** les 20 voisins du même vecteur sont demandés aux deux
- **THEN** les deux réponses ont les mêmes documents, dans le même ordre, avec les mêmes scores
- **AND** le store au cache étroit n'a retenu aucun index en cache

#### Scenario: Dimensions différentes
- **GIVEN** un index local à 8 dimensions
- **WHEN** les voisins d'un vecteur à 4 dimensions sont demandés
- **THEN** la demande est refusée par une erreur de dimensions
- **AND** aucun résultat partiel n'est rendu

### Requirement: Des lectures simultanées d'un index local que le cache tient ne le décodent qu'une fois
Quand plusieurs requêtes demandent en même temps le chargement complet du même index local, absent du cache et que le cache peut tenir, le système SHALL le décoder une seule fois et servir les autres requêtes du résultat. Un échec du décodage SHALL être rendu à chaque requête en attente, sans entrée de cache.

#### Scenario: Quatre lectures froides simultanées
- **GIVEN** un index local absent du cache, plus léger que son plafond, et quatre fils qui demandent son chargement au même instant
- **WHEN** les quatre chargements se terminent
- **THEN** le store rapporte un seul décodage complet
- **AND** les quatre fils reçoivent le même index

#### Scenario: Décodage en échec
- **GIVEN** un fichier d'index tronqué et deux fils qui le chargent au même instant
- **WHEN** le décodage échoue
- **THEN** les deux fils reçoivent une erreur d'index tronqué
- **AND** le cache ne contient aucune entrée pour ce projet

### Requirement: Une page PostgreSQL de l'index sémantique ne recompte pas les documents
Sous PostgreSQL, le système SHALL rendre une page de documents, une page de vecteurs, un document stocké et les métadonnées d'un index sémantique sans compter les lignes de l'index dans la requête. Le nombre de documents rendu avec la réponse SHALL être celui de l'index lu, tenu à jour par chaque écriture de l'index dans la même transaction que cette écriture.

#### Scenario: Plan d'une requête de page
- **GIVEN** un index sémantique PostgreSQL de 3 000 documents
- **WHEN** la requête d'une page de vecteurs, puis d'une page de documents, est préparée sur le serveur et son plan lu par `EXPLAIN EXECUTE`
- **THEN** aucun des deux plans ne contient de nœud d'agrégat
- **AND** chaque page rendue annonce 3 000 documents

#### Scenario: Compte après une synchronisation différentielle
- **GIVEN** un index de 120 documents
- **WHEN** une synchronisation retire 20 documents, en ajoute 5, puis une page de documents est lue
- **THEN** la page annonce 105 documents
- **AND** ce nombre est égal au nombre de lignes de l'index

#### Scenario: Index remplacé entre deux pages
- **GIVEN** une première page de vecteurs lue sur un index de 120 documents
- **WHEN** l'index est remplacé par un index de 80 documents, puis la page suivante est lue
- **THEN** les métadonnées de la seconde page diffèrent de celles de la première par le snapshot et par le nombre de documents
- **AND** aucune des deux pages ne porte le nombre de documents de l'autre index

### Requirement: Une page suivante PostgreSQL est bornée par la clé stable dans l'index
Sous PostgreSQL, le système SHALL lire une page qui suit une clé stable par une condition d'index sur cette clé, y compris quand le serveur exécute la requête en plan générique. Le système SHALL NOT parcourir les lignes qui précèdent la clé pour les écarter une à une.

#### Scenario: Plan générique forcé
- **GIVEN** un index de 3 000 documents et une session dont `plan_cache_mode` vaut `force_generic_plan`
- **WHEN** la requête de page suivante est préparée par `PREPARE`, puis son plan lu par `EXPLAIN (ANALYZE) EXECUTE` avec la 2 000e clé stable
- **THEN** le plan porte une condition d'index sur `stable_key`
- **AND** il n'écarte par filtre aucune ligne dont la clé précède la borne

#### Scenario: Borne absente sur une page suivante
- **GIVEN** un store PostgreSQL ouvert
- **WHEN** une page est demandée avec une clé de reprise vide
- **THEN** la demande est refusée par une erreur d'argument
- **AND** aucune requête n'est envoyée au serveur

### Requirement: La migration du schéma PostgreSQL reprend le nombre de documents de chaque index sémantique
La migration d'un schéma PostgreSQL antérieur SHALL inscrire, pour chaque index sémantique existant, son nombre réel de documents. Après migration, une lecture SHALL annoncer ce nombre sans resynchronisation de l'index ni recalcul de vecteur.

#### Scenario: Migration d'un index existant
- **GIVEN** une base au schéma précédent qui porte un index sémantique de 250 documents
- **WHEN** le runtime migre le schéma puis lit la première page de documents
- **THEN** la colonne du nombre de documents de cet index vaut 250 et la page annonce 250 documents
- **AND** la page rend les mêmes clés stables qu'avant la migration

#### Scenario: Migration rejouée
- **GIVEN** une base déjà migrée dont un index sémantique porte 250 documents
- **WHEN** la migration est relancée
- **THEN** elle n'échoue pas et le nombre de documents inscrit reste 250

### Requirement: La préparation ne relit pas l'index à chaque itération quand l'échantillon tient en mémoire
Quand les vecteurs de l'échantillon tiennent dans le budget de mémoire de la préparation, le système SHALL lire l'index au plus deux fois (sélection, puis copie de l'échantillon) et SHALL calculer centrage, ajustement et projection sans autre lecture du store. La mémoire de l'échantillon SHALL être comptée sur ce budget.

#### Scenario: Échantillon dans le budget
- **GIVEN** un index de 2 000 documents, un plafond de 500 points et un budget qui contient 500 vecteurs
- **WHEN** la préparation s'exécute sur un store qui compte ses parcours complets
- **THEN** le store a été parcouru deux fois, quel que soit le nombre d'itérations
- **AND** le rapport annonce la stratégie en mémoire et un pic de mémoire au plus égal au budget

#### Scenario: Index remplacé pendant la copie
- **GIVEN** une préparation dont la sélection est faite
- **WHEN** l'index est remplacé avant la fin de la copie de l'échantillon
- **THEN** la préparation est refusée avec le motif « index modifié pendant la préparation »
- **AND** aucune projection n'est publiée et la projection précédente est intacte

### Requirement: Un échantillon plus lourd que le budget garde le parcours en flux et l'annonce
Quand les vecteurs de l'échantillon dépassent le budget de mémoire, le système SHALL préparer la projection par le parcours en flux, sans retenir l'échantillon, et SHALL annoncer cette stratégie dans le rapport avec l'option qui relève le budget. Le système SHALL NOT dépasser le budget pour éviter des lectures.

#### Scenario: Repli annoncé
- **GIVEN** un index de 2 000 documents, un plafond de 500 points et un budget qui ne contient pas 500 vecteurs mais suffit au parcours en flux
- **WHEN** la préparation s'exécute
- **THEN** la projection est publiée et le rapport annonce la stratégie en flux et nomme `--max-memory-mb`
- **AND** le pic de mémoire rapporté est au plus égal au budget

#### Scenario: Budget insuffisant même en flux
- **GIVEN** un budget qui ne contient ni l'échantillon ni la sélection
- **WHEN** la préparation s'exécute
- **THEN** la commande échoue avec le refus de budget, un message actionnable et un code de sortie non nul
- **AND** aucune projection n'est publiée

### Requirement: Les deux stratégies de préparation rendent les mêmes coordonnées
Pour un même index, une même sélection et une même graine, le système SHALL publier les mêmes points, coordonnées et parts de variance, que l'échantillon ait été itéré en mémoire ou relu en flux.

#### Scenario: Égalité des projections
- **GIVEN** un index inchangé de 2 000 documents et une graine
- **WHEN** la préparation est lancée une fois avec un budget qui contient l'échantillon, une fois avec un budget qui force le flux
- **THEN** les deux projections ont les mêmes clés stables, les mêmes coordonnées et les mêmes parts de variance
- **AND** seuls la stratégie, le nombre de passes et le pic de mémoire diffèrent dans les rapports

## Documentation liée

- Dossier durable (vues, traçabilité, écarts) : [exploration-semantique](../../../docs/architecture/diagrams/capacites/exploration-semantique.md)
