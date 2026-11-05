## ADDED Requirements

### Requirement: Les verrous npm livrés sont analysés par le gate de vulnérabilités
Le job de vulnérabilités des pull requests SHALL analyser chaque verrou npm livré avec le produit, quel que soit le nom du fichier. Une vulnérabilité connue dans un paquet de ces verrous SHALL faire échouer le job.

#### Scenario: Paquet vulnérable dans un verrou livré
- **GIVEN** une copie du dépôt où `scip-python-package-lock.json` référence une version d'un paquet touchée par un avis connu
- **WHEN** OSV-Scanner est exécuté avec les arguments du job de pull request
- **THEN** l'analyse échoue et nomme le paquet et l'avis

#### Scenario: Verrous sains
- **GIVEN** le dépôt réel
- **WHEN** OSV-Scanner est exécuté avec les arguments du job de pull request
- **THEN** les deux verrous figurent parmi les sources analysées et l'analyse réussit

### Requirement: Aucun verrou npm livré n'échappe au scan
Un gate statique SHALL échouer quand un fichier de verrou npm versionné sous un répertoire de ressources de production n'est pas passé au job de vulnérabilités.

#### Scenario: Troisième verrou non déclaré
- **GIVEN** une copie du dépôt où un nouveau `scip-autre-package-lock.json` est ajouté aux ressources sans modifier le workflow
- **WHEN** `python scripts/quality/check-ci-wiring.py` est exécuté sur cette copie
- **THEN** le gate échoue en nommant le fichier

#### Scenario: Verrous déclarés
- **GIVEN** le dépôt réel
- **WHEN** le gate est exécuté
- **THEN** il réussit

### Requirement: Le catalogue des outils embarqués a une veille planifiée à couverture déclarée
Un contrôle planifié SHALL interroger une base d'avis pour chaque composant du catalogue qui a un identifiant interrogeable et SHALL échouer quand un composant est touché ou hors support. Sa sortie SHALL lister les composants non couverts avec leur raison. Un échec réseau, un composant inconnu de la table ou une dérogation échue SHALL être un échec.

#### Scenario: Composant touché par un avis
- **GIVEN** des réponses simulées où un composant du catalogue est touché par un avis
- **WHEN** `python scripts/quality/check-embedded-tools-advisories.py --self-test` exécute ce cas
- **THEN** le contrôle échoue et nomme le composant et l'avis

#### Scenario: Composant absent de la table
- **GIVEN** un catalogue simulé contenant un composant que la table du script ne connaît pas
- **WHEN** le contrôle est exécuté
- **THEN** il échoue en nommant le composant

#### Scenario: Service d'avis injoignable
- **GIVEN** une interrogation qui échoue
- **WHEN** le contrôle est exécuté
- **THEN** il échoue avec un message distinct d'une alerte et ne rapporte pas « aucun avis »

#### Scenario: Composants non couverts déclarés
- **GIVEN** le catalogue réel et des réponses sans avis
- **WHEN** le contrôle est exécuté
- **THEN** il réussit et sa sortie énumère les composants non couverts avec leur raison
