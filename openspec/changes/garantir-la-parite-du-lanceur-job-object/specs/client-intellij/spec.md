## ADDED Requirements

### Requirement: Les blocs communs du lanceur Job Object du plugin sont identiques à ceux du moteur
Le lanceur Job Object embarqué par le plugin IntelliJ SHALL contenir à l'identique chaque fragment du moteur déclaré commun (imports Win32, structures de limites, vérification de la configuration du job, citation des arguments). Un gate statique SHALL assembler le script du moteur comme le runtime et SHALL échouer en nommant le fragment quand un bloc commun diffère. Un gabarit ou un fragment introuvable SHALL faire échouer le gate.

#### Scenario: Fragment commun modifié d'un seul côté
- **GIVEN** une copie du dépôt où une ligne du fragment des imports Win32 du moteur est modifiée sans toucher au plugin
- **WHEN** `python scripts/quality/check-job-object-launcher-parity.py --root <copie>` est exécuté
- **THEN** le gate échoue en nommant le fragment

#### Scenario: Dépôt réel
- **GIVEN** le dépôt
- **WHEN** le gate est exécuté
- **THEN** il réussit et affiche le nombre de fragments communs contrôlés

#### Scenario: Fragment introuvable
- **GIVEN** une copie du dépôt où un fragment inclus par le gabarit est supprimé
- **WHEN** le gate est exécuté sur cette copie
- **THEN** il échoue en nommant le fragment manquant et ne conclut pas à la parité

### Requirement: Toute différence assumée entre les deux lanceurs est nommée et bornée
Une différence entre le lanceur du plugin et le script assemblé du moteur SHALL figurer dans une liste fermée qui porte, pour chaque entrée, un nom, une justification et un maximum de lignes. Le gate SHALL échouer sur une différence hors liste, sur un maximum dépassé et sur une entrée qui ne correspond plus à aucune différence. Il SHALL afficher la liste à chaque exécution.

#### Scenario: Différence hors liste
- **GIVEN** une copie du dépôt où une ligne est ajoutée au lanceur du plugin hors de toute zone assumée
- **WHEN** le gate est exécuté sur cette copie
- **THEN** il échoue en citant la ligne

#### Scenario: Entrée sans objet
- **GIVEN** une liste qui nomme une différence que les deux scripts ne présentent plus
- **WHEN** le gate est exécuté
- **THEN** il échoue en nommant l'entrée périmée

#### Scenario: Différences affichées
- **GIVEN** le dépôt, où la construction de la ligne de commande diffère entre plugin et moteur
- **WHEN** le gate est exécuté
- **THEN** il réussit et sa sortie nomme cette différence et sa justification

### Requirement: Un changement de fragment du lanceur déclenche la CI du plugin
La portée du gate du plugin SHALL inclure les fragments et le gabarit du lanceur Job Object du moteur ainsi que le gate de parité. Une pull request qui ne modifie que l'un de ces fichiers SHALL exécuter les jobs du plugin, dont les tests Windows du lanceur.

#### Scenario: Fragment seul modifié
- **GIVEN** une liste de fichiers modifiés réduite à un fragment du lanceur du moteur
- **WHEN** la portée du plugin est calculée par `scripts/ci/plugin-gate.py`
- **THEN** la portée est vraie

#### Scenario: Fichier sans rapport
- **GIVEN** une liste de fichiers modifiés réduite à `README.md`
- **WHEN** la portée est calculée
- **THEN** la portée est fausse
