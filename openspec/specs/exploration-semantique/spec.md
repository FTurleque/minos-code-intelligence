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

## Documentation liée

- Dossier durable (vues, traçabilité, écarts) : [exploration-semantique](../../../docs/architecture/diagrams/capacites/exploration-semantique.md)
