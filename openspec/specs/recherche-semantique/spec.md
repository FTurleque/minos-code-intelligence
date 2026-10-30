# recherche-semantique Specification

## Purpose
Définit le coût et la cohérence de la recherche sémantique et hybride : ce qu'une requête hybride prête relit du stockage (au plus un chargement du snapshot actif, aucun vecteur, aucune taille d'index), la détection d'un index remplacé pendant la requête, et la lecture des sources par la construction des documents sémantiques (une ouverture par fichier, aucun contenu conservé après). Elle fixe aussi le coût de la synchronisation de l'index : rien n'est écrit quand rien n'a changé, l'écriture PostgreSQL est proportionnelle aux documents modifiés et le différentiel produit le même état qu'un remplacement complet.

## Requirements

### Requirement: Une requête hybride lit le snapshot actif au plus une fois
Une recherche hybride sur un index sémantique prêt SHALL charger la vue du snapshot actif au plus une fois. Toute autre lecture de l'identité du snapshot actif pendant la requête SHALL passer par une lecture qui ne charge ni ne décode le snapshot.

#### Scenario: Décompte des chargements
- **GIVEN** un store de snapshots à compteurs et un index sémantique prêt
- **WHEN** une recherche hybride est exécutée
- **THEN** le compteur de chargements du snapshot vaut au plus 1

#### Scenario: Lecture légère conforme au chargement
- **GIVEN** chacun des deux stores réels, avec puis sans snapshot actif
- **WHEN** l'identifiant actif est lu par la lecture légère puis par le chargement complet
- **THEN** les deux lectures rendent la même réponse

### Requirement: Une requête hybride ne charge aucun vecteur et ne calcule aucune taille d'index
Sur le chemin de requête, l'identité du corpus SHALL venir des métadonnées de l'index et les documents SHALL être lus sans leurs embeddings. L'index vectoriel complet SHALL NOT être chargé et la taille de l'index SHALL NOT être calculée. L'outil de statut SHALL continuer de rendre la taille.

#### Scenario: Aucun chargement de vecteurs
- **GIVEN** un store vectoriel à compteurs et un index prêt
- **WHEN** une recherche hybride est exécutée
- **THEN** le compteur de chargements complets de l'index vaut 0 et celui des calculs de taille vaut 0

#### Scenario: Corpus déjà en cache
- **GIVEN** une première recherche hybride terminée et un index inchangé
- **WHEN** une seconde recherche hybride est exécutée
- **THEN** aucun document n'est relu

#### Scenario: Le statut rend toujours la taille
- **GIVEN** un index prêt
- **WHEN** le statut de l'index sémantique est demandé
- **THEN** la taille de l'index figure dans la réponse

### Requirement: Un index modifié pendant une requête reste détecté
La recherche sémantique SHALL comparer l'identité de l'index avant et après la recherche vectorielle. Si elle a changé, la requête SHALL être rejouée ou signaler que l'index a changé pendant la requête ; elle SHALL NOT rendre des résultats rattachés à un autre index.

#### Scenario: Index remplacé pendant la recherche
- **GIVEN** un store qui change d'index entre la recherche vectorielle et le contrôle de fin
- **WHEN** une recherche sémantique est exécutée
- **THEN** la recherche est rejouée, puis signale `SEMANTIC_INDEX_CHANGED_DURING_QUERY` si le changement persiste

#### Scenario: Index stable
- **GIVEN** un index inchangé pendant la requête
- **WHEN** une recherche hybride est exécutée
- **THEN** les résultats sont identiques, à l'octet, à ceux d'avant ce changement sur les fixtures de qualification

### Requirement: La construction des documents sémantiques ouvre chaque fichier source une fois
Pendant une construction de documents sémantiques, chaque fichier source SHALL être ouvert et décodé au plus une fois, quel que soit le nombre de ses symboles. Les documents produits SHALL être identiques, à l'octet, à ceux produits par une lecture par symbole.

#### Scenario: Fichier à plusieurs symboles
- **GIVEN** un snapshot dont un fichier porte vingt symboles et un lecteur de sources qui compte les ouvertures
- **WHEN** les documents sémantiques sont construits
- **THEN** le compteur d'ouvertures de ce fichier vaut 1

#### Scenario: Ouvertures égales au nombre de fichiers
- **GIVEN** un snapshot de fixture couvrant plusieurs fichiers
- **WHEN** les documents sémantiques sont construits
- **THEN** le nombre total d'ouvertures égale le nombre de fichiers distincts portant au moins un symbole

#### Scenario: Documents identiques
- **GIVEN** les fixtures de qualification, dont des fichiers aux fins de ligne `\n` et `\r\n`
- **WHEN** les documents sont construits
- **THEN** leurs empreintes sont celles relevées avant ce changement

#### Scenario: Fichier illisible
- **GIVEN** un fichier du snapshot supprimé du disque avant la construction
- **WHEN** les documents sont construits
- **THEN** la construction se termine et les documents de ce fichier sont ceux que produisait la lecture par symbole dans le même cas

### Requirement: Aucun contenu de source n'est conservé après la construction
Le contenu d'un fichier lu pour construire des documents SHALL NOT être conservé au-delà du traitement de ce fichier. Un extrait demandé après la construction SHALL refléter le contenu présent sur le disque à ce moment.

#### Scenario: Fichier modifié après la construction
- **GIVEN** une construction terminée, puis un fichier source modifié sur le disque
- **WHEN** un extrait de ce fichier est demandé
- **THEN** l'extrait contient le nouveau contenu

### Requirement: Une synchronisation sans changement n'écrit rien
Quand l'index sémantique en place est prêt et que son snapshot, son provider, son modèle, ses dimensions et sa version de documents sont ceux de la cible, la synchronisation SHALL se terminer sans construire de document, sans demander d'embedding et sans écrire dans le store.

#### Scenario: Indexation sans changement
- **GIVEN** un index sémantique prêt pour le snapshot actif et un store à compteurs
- **WHEN** `minos index` se termine en `NO_CHANGES` et déclenche la synchronisation
- **THEN** les compteurs de documents construits, d'embeddings demandés et d'écritures valent 0

#### Scenario: Modèle changé
- **GIVEN** un index prêt pour le même snapshot mais un autre modèle d'embeddings
- **WHEN** la synchronisation est exécutée
- **THEN** elle suit le chemin complet et l'index final porte le nouveau modèle

### Requirement: L'écriture PostgreSQL est proportionnelle aux documents modifiés
La synchronisation sous PostgreSQL SHALL n'écrire d'embedding que pour les documents nouveaux ou dont le contenu a changé, et SHALL décider de la réutilisation sans lire les vecteurs en place. Un document réutilisé SHALL recevoir son rattachement au nouveau snapshot et ses positions à jour.

#### Scenario: Un document modifié sur N
- **GIVEN** un index de N documents et une cible où un seul document a changé de contenu
- **WHEN** la synchronisation est exécutée
- **THEN** un seul embedding est demandé et un seul vecteur est écrit

#### Scenario: Document déplacé sans changement de contenu
- **GIVEN** un document dont seules les lignes de début et de fin ont changé
- **WHEN** la synchronisation est exécutée
- **THEN** aucun vecteur n'est écrit et la ligne porte les nouvelles positions et le nouveau snapshot

#### Scenario: Document sans vecteur non marqué comme réutilisé
- **GIVEN** un document transmis au store sans vecteur et sans marque de réutilisation
- **WHEN** la synchronisation est exécutée
- **THEN** elle échoue et l'index précédent est inchangé

### Requirement: Le différentiel est équivalent à un remplacement complet
Après une synchronisation différentielle, le contenu de l'index SHALL être identique, ligne à ligne, à celui qu'aurait produit un remplacement complet de la même cible. Un échec en cours d'écriture SHALL laisser l'index précédent intact.

#### Scenario: Équivalence sur les cas de mutation
- **GIVEN** les cas ajout, modification, suppression, déplacement, changement de modèle, cible vide et tout réutilisé
- **WHEN** chaque cas est synchronisé par différentiel puis, sur une base témoin, par remplacement complet
- **THEN** les deux tables sont égales ligne à ligne

#### Scenario: Échec en cours d'écriture
- **GIVEN** une écriture qui échoue après la suppression des documents retirés
- **WHEN** la transaction est annulée
- **THEN** la lecture de l'index rend l'état d'avant la synchronisation
