## ADDED Requirements

### Requirement: Un snapshot de plus de 256 Mio est persisté jusqu'au plafond de persistance
Les deux stores de snapshots SHALL publier un snapshot de connaissance dont l'encodage dépasse 256 Mio sans dépasser le plafond de persistance, et SHALL le relire avec la même garantie d'intégrité qu'un petit snapshot.

#### Scenario: Snapshot de plus de 256 Mio sous le plafond
- **GIVEN** un snapshot de connaissance dont l'encodage dépasse 256 Mio et ne dépasse pas le plafond de persistance
- **WHEN** il est publié par le store fichier, puis par le store PostgreSQL
- **THEN** le snapshot est publié et le pointeur actif le désigne
- **AND** sa relecture est authentifiée par l'empreinte et rend un modèle égal à l'original

#### Scenario: Relecture d'un snapshot de plus de 256 Mio
- **GIVEN** un snapshot de plus de 256 Mio publié sous le plafond de persistance
- **WHEN** la vue active est lue
- **THEN** la lecture réussit sans erreur de taille

### Requirement: Un snapshot au-delà du plafond de persistance est refusé sans rien écrire
Les deux stores de snapshots SHALL refuser, avant toute écriture, un snapshot dont l'encodage dépasse le plafond de persistance, avec un message qui ne contient que des tailles en octets, sans chemin.

#### Scenario: Snapshot au-delà du plafond
- **GIVEN** un snapshot de connaissance dont l'encodage dépasse le plafond de persistance
- **WHEN** sa publication est demandée
- **THEN** elle échoue par une erreur d'E/S dont le message commence par « knowledge snapshot is too large to persist: » et ne donne que la taille encodée et le plafond
- **AND** aucun fichier ni aucune ligne n'est écrit et le snapshot actif précédent reste actif

### Requirement: Le plafond du cache de vues couvre tout snapshot persistable
Le plafond du cache de vues de requête SHALL être au moins égal au plafond de persistance multiplié par l'amplification estimée, de sorte qu'un snapshot persistable ne soit jamais refusé par le cache pour son seul poids estimé.

#### Scenario: Invariant respecté
- **GIVEN** le plafond de persistance, l'amplification estimée et le plafond du cache de chacun des deux stores
- **WHEN** le test d'invariant les compare
- **THEN** le plafond du cache est supérieur ou égal au produit du plafond de persistance par l'amplification

#### Scenario: Plafond de cache réduit sous l'invariant
- **GIVEN** un plafond de cache inférieur au produit du plafond de persistance par l'amplification
- **WHEN** le test d'invariant s'exécute
- **THEN** il échoue en nommant les trois valeurs

## MODIFIED Requirements

### Requirement: Un snapshot de la taille de ce dépôt est admis au cache de vues
Les deux stores de snapshots SHALL estimer le poids d'une vue de requête d'après une amplification mesurée de la taille persistée. Un snapshot au format courant dont la vue tient sous le plafond du cache SHALL être mis en cache : deux lectures consécutives de la vue active SHALL ne provoquer qu'un seul chargement complet.

#### Scenario: Snapshot de plus de 64 Mio persistés
- **GIVEN** un snapshot actif au format courant de plus de 64 Mio persistés, dont la vue pèse moins que le plafond du cache
- **WHEN** la vue active est lue deux fois de suite
- **THEN** le compteur de chargements complets vaut 1

#### Scenario: Snapshot de plus de 300 Mio persistés
- **GIVEN** un snapshot actif au format courant de plus de 300 Mio persistés, dont le poids estimé est inférieur au plafond du cache
- **WHEN** la vue active est lue deux fois de suite
- **THEN** le compteur de chargements complets vaut 1
- **AND** la seconde lecture est servie sans relire le fichier

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

#### Scenario: Corpus de plus de 300 Mio persistés
- **GIVEN** une vue construite depuis un snapshot réel de plus de 300 Mio persistés au format courant
- **WHEN** le test de poids mesure le tas retenu et le compare à l'estimation
- **THEN** l'estimation est supérieure ou égale à la mesure
- **AND** le rapport mesuré du tas à la taille persistée est consigné dans le rapport du banc

#### Scenario: Format non mesuré
- **GIVEN** un snapshot d'un format antérieur sans mesure d'amplification
- **WHEN** son poids est estimé
- **THEN** l'estimation utilise l'amplification prudente d'origine
