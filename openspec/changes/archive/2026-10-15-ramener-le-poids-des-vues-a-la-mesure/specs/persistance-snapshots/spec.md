## ADDED Requirements

### Requirement: Un snapshot de la taille de ce dépôt est admis au cache de vues
Les deux stores de snapshots SHALL estimer le poids d'une vue de requête d'après une amplification mesurée de la taille persistée. Un snapshot au format courant dont la vue tient sous le plafond du cache SHALL être mis en cache : deux lectures consécutives de la vue active SHALL ne provoquer qu'un seul chargement complet.

#### Scenario: Snapshot de plus de 64 Mio persistés
- **GIVEN** un snapshot actif au format courant de plus de 64 Mio persistés, dont la vue pèse moins que le plafond du cache
- **WHEN** la vue active est lue deux fois de suite
- **THEN** le compteur de chargements complets vaut 1

#### Scenario: Vue plus lourde que le plafond
- **GIVEN** un snapshot dont la vue estimée dépasse le plafond du cache
- **WHEN** la vue active est lue deux fois de suite
- **THEN** elle n'est pas mise en cache et le compteur de chargements complets vaut 2

### Requirement: Le poids estimé d'une vue ne sous-évalue pas le tas qu'elle retient
Pour chaque format de snapshot lisible, le poids estimé d'une vue SHALL être supérieur ou égal au tas qu'elle retient, mesuré par un test. Un format dont l'amplification n'a pas été mesurée SHALL conserver l'amplification prudente d'origine.

#### Scenario: Estimation comparée au tas retenu
- **GIVEN** une vue construite depuis un snapshot réel au format courant
- **WHEN** le test de poids mesure le tas retenu et le compare à l'estimation
- **THEN** l'estimation est supérieure ou égale à la mesure

#### Scenario: Format non mesuré
- **GIVEN** un snapshot d'un format antérieur sans mesure d'amplification
- **WHEN** son poids est estimé
- **THEN** l'estimation utilise l'amplification prudente d'origine

### Requirement: Un snapshot altéré n'est ni publié ni mis en cache
Quel que soit le nombre de parcours du fichier, une vue SHALL NOT être rendue à l'appelant, mise en cache ni comptée comme chargée avant que l'empreinte du contenu lu ait été comparée à l'empreinte attendue. Un contenu altéré, tronqué ou prolongé SHALL produire l'erreur d'intégrité.

#### Scenario: Contenu altéré
- **GIVEN** un snapshot actif dont un octet a été modifié après publication
- **WHEN** la vue active est lue
- **THEN** la lecture échoue avec l'erreur d'intégrité, le cache est vide et le compteur de chargements complets n'a pas augmenté

#### Scenario: Contenu tronqué
- **GIVEN** un snapshot actif tronqué
- **WHEN** la vue active est lue
- **THEN** la lecture échoue avec l'erreur d'intégrité et non avec une erreur de décodage

#### Scenario: Seconde lecture après un échec
- **GIVEN** une lecture qui vient d'échouer sur un contenu altéré
- **WHEN** la vue active est lue de nouveau
- **THEN** elle échoue de la même façon

### Requirement: Un snapshot décodé partage une instance par chaîne distincte
Le décodage d'un snapshot SHALL rendre une seule instance par chaîne distincte lue dans une même passe, pour chaque format lisible. Le modèle décodé SHALL rester égal au modèle écrit. La table de partage SHALL NOT survivre à la passe de décodage.

#### Scenario: Chaînes répétées sur plusieurs entités
- **GIVEN** un snapshot écrit dont deux symboles, une occurrence et une relation portent le même identifiant de projet
- **WHEN** il est décodé au format courant puis au format précédent
- **THEN** ces champs désignent la même instance et le modèle décodé est égal au modèle écrit

#### Scenario: Deux décodages successifs
- **GIVEN** un même fichier décodé deux fois
- **WHEN** les deux modèles sont comparés
- **THEN** ils sont égaux et ne partagent pas leurs instances de chaînes
