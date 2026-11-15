## ADDED Requirements

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
