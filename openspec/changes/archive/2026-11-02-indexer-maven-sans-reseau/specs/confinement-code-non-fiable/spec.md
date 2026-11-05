## ADDED Requirements

### Requirement: Un plan déclare ses racines de lecture et le bac à sable Linux les monte exactement
Un plan d'indexation SHALL pouvoir déclarer des racines en lecture seule. Le bac à sable Linux SHALL monter chacune en lecture seule par son chemin réel, elle seule, jamais un ancêtre, après les montages système et les montages inscriptibles. Le réseau reste `DENY`.

#### Scenario: Racine montée en lecture seule
- **GIVEN** sous Linux, un plan qui déclare un répertoire comme racine de lecture
- **WHEN** le bac à sable construit sa commande
- **THEN** elle contient `--ro-bind` de ce répertoire vers lui-même, placé après tout `--bind`, aucun montage de son parent, et pas `--share-net`

#### Scenario: Écriture refusée dans la racine
- **GIVEN** sous Linux avec bac à sable qualifié, un provider qui tente d'écrire dans une racine de lecture déclarée
- **WHEN** il s'exécute par la chaîne `ProcessIndexerExecutor → sandbox → provider`
- **THEN** l'écriture échoue et la racine est inchangée

### Requirement: Le bac à sable Linux revérifie une racine de lecture juste avant de la monter
Juste avant de construire la commande, le bac à sable Linux MUST refuser une racine relative, absente, non répertoire, dont le chemin réel diffère du chemin normalisé (lien sur un composant), dont un composant n'appartient ni à l'utilisateur ni à root ou est inscriptible par d'autres, ou qui est égale, ancêtre ou descendante de l'espace de travail, de l'artefact, du run ou du projet source.

#### Scenario: Composant intermédiaire lien symbolique
- **GIVEN** sous Linux, une racine de lecture `/data/u` où `/data` est un lien symbolique
- **WHEN** le bac à sable prépare le plan
- **THEN** le plan est refusé avant tout lancement, sans chemin absolu dans le message

#### Scenario: Racine dans le run
- **GIVEN** sous Linux, une racine de lecture située sous le répertoire du run
- **WHEN** le bac à sable prépare le plan
- **THEN** le plan est refusé avant tout lancement

### Requirement: Les racines de lecture survivent à chaque transformation du plan ou font refuser
Toute transformation d'un plan (transformateurs de `StrongProcessOwnershipIndexerExecutor`, `WindowsJobObjectProcessOwnership`, backends) SHALL conserver ses racines de lecture. `WindowsAppContainerWorkerSandboxBackend`, `WindowsJobObjectProcessOwnership` et un exécuteur sans bac à sable OS SHALL refuser un plan qui en déclare, avant toute création de processus et sans octroi nouveau.

#### Scenario: AppContainer et racine de lecture
- **GIVEN** sous Windows, un plan qui déclare une racine de lecture, transmis par la chaîne d'exécution réelle
- **WHEN** le plan atteint le bac à sable
- **THEN** il est refusé, aucun processus n'est lancé et aucune ACL n'est modifiée

#### Scenario: Plan sans racine
- **GIVEN** un plan sans racine de lecture
- **WHEN** n'importe quel backend le prépare
- **THEN** sa commande est identique à celle produite sans cette capacité
