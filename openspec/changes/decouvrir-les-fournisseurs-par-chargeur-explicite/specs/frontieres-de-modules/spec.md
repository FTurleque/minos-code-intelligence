## ADDED Requirements

### Requirement: Les fournisseurs optionnels sont découverts par le chargeur de MINOS
La découverte du backend de stockage optionnel et celle du fournisseur d'identité de chemin SHALL utiliser le chargeur de classes qui a chargé MINOS, et SHALL NOT dépendre du chargeur de contexte du thread. Un fournisseur absent SHALL produire le refus existant, jamais un mode dégradé.

#### Scenario: Chargeur de contexte vide, backend PostgreSQL
- **GIVEN** un thread dont le chargeur de contexte ne voit aucun service, et le fournisseur PostgreSQL sur le chemin de classes de MINOS
- **WHEN** le backend `postgresql` est sélectionné
- **THEN** le fournisseur PostgreSQL est trouvé

#### Scenario: Chargeur de contexte vide, identité de chemin
- **GIVEN** un thread dont le chargeur de contexte ne voit aucun service, sous Windows
- **WHEN** l'identité physique d'une racine de projet est capturée
- **THEN** l'identité est rendue par le fournisseur enregistré

#### Scenario: Fournisseur absent
- **GIVEN** un chargeur de MINOS sans fournisseur pour le backend demandé
- **WHEN** ce backend est sélectionné
- **THEN** l'ouverture échoue en disant que le fournisseur n'est pas disponible, et aucun autre backend n'est ouvert à sa place

### Requirement: Des fournisseurs en double sont refusés
Deux fournisseurs de stockage de l'identifiant demandé, comparé sans casse avec `Locale.ROOT`, SHALL faire échouer l'ouverture avec un message qui nomme leurs classes. Plus d'un fournisseur d'identité de chemin SHALL rendre l'identité indisponible, donc le lancement refusé. Aucun fournisseur SHALL NOT être choisi par ordre de découverte.

#### Scenario: Deux fournisseurs de stockage de même identifiant
- **GIVEN** un chargeur qui expose deux fournisseurs d'identifiant `postgresql`
- **WHEN** le backend `postgresql` est sélectionné
- **THEN** l'ouverture échoue, le message nomme les deux classes et ne contient aucun chemin absolu

#### Scenario: Doublon sans effet sur le backend local
- **GIVEN** le même chargeur à deux fournisseurs `postgresql`
- **WHEN** le backend `local` est sélectionné
- **THEN** le stockage local est ouvert

#### Scenario: Deux fournisseurs d'identité de chemin
- **GIVEN** un chargeur qui expose deux fournisseurs d'identité de chemin, sur un système sans identité de fichier native
- **WHEN** une autorisation de chemin d'exécution est capturée
- **THEN** aucune autorisation n'est rendue et le lancement d'un provider local est refusé

### Requirement: Aucune découverte de service de production n'omet le chargeur
Tout appel `ServiceLoader.load` des sources de production du reactor SHALL passer un chargeur de classes explicite. Le garde de frontières SHALL échouer sur un appel à un seul argument, en nommant le fichier et la ligne.

#### Scenario: Appel sans chargeur
- **GIVEN** une copie du dépôt où une classe de production appelle `ServiceLoader.load(X.class)`
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté sur cette copie
- **THEN** le garde échoue en nommant le fichier et la ligne

#### Scenario: Dépôt réel
- **GIVEN** le dépôt après le changement
- **WHEN** le garde est exécuté
- **THEN** il réussit et les quatre découvertes de production passent un chargeur
