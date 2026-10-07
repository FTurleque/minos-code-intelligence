# Spec Delta

## Purpose

Définit le comportement du client IntelliJ externe de MINOS : il pilote la CLI locale par le
protocole `minos-ide` v1 dans des processus de fond, sans dépendre d'aucun artefact interne de
MINOS, et lance cette CLI de façon fiable et fail-closed sous Windows comme sous Linux.

## ADDED Requirements

### Requirement: Un lanceur par lots démarre sous Windows et reçoit ses arguments intacts

Sous Windows, quand le lanceur configuré est un fichier par lots (`.cmd` ou `.bat`), le client
SHALL le démarrer réellement et lui transmettre chaque argument exactement tel que fourni, y
compris les arguments contenant des espaces ou les caractères `&`, `|`, `<`, `>` et `^`. Le code
de sortie et les sorties standard du lanceur SHALL être restitués au client.

#### Scenario: Lanceur par lots par défaut avec des arguments ordinaires
- **WHEN** le lanceur configuré est un `.cmd` valide et que le client lui transmet les arguments `simple`, `a&b` et `x y|<>^`
- **THEN** le `.cmd` démarre, reçoit exactement trois arguments identiques à ceux fournis et son code de sortie 0 est restitué

#### Scenario: Chemin du lanceur contenant des espaces
- **WHEN** le chemin absolu du `.cmd` contient des espaces (par exemple un dossier d'installation sous « Program Files »)
- **THEN** le lanceur démarre et reçoit ses arguments, sans message « n'est pas reconnu en tant que commande »

#### Scenario: Code de sortie non nul du lanceur
- **WHEN** le `.cmd` se termine avec un code de sortie non nul
- **THEN** ce code est restitué tel quel au client, qui l'interprète selon le protocole (aucun code de sortie remplacé par une erreur de lancement)

### Requirement: Les arguments qu'un lanceur par lots ne peut pas recevoir sont refusés

Pour un lanceur par lots sous Windows, le client SHALL refuser, avant tout démarrage de processus,
un argument ou un chemin de lanceur contenant un guillemet `"` ou le caractère `%`, avec un message
qui indique la cause et le contournement (configurer le lanceur natif `minos.exe`). Il SHALL aussi
continuer à refuser les caractères de contrôle (retour chariot, saut de ligne, caractère nul).

#### Scenario: Argument contenant un guillemet
- **WHEN** une action transmet l'argument `say "hi"` avec un lanceur `.cmd` sous Windows
- **THEN** aucun processus n'est démarré et l'action échoue avec un message qui nomme l'argument refusé et recommande le lanceur natif

#### Scenario: Argument contenant un pourcentage
- **WHEN** une action transmet l'argument `%PATH%` ou `50% off` avec un lanceur `.cmd` sous Windows
- **THEN** aucun processus n'est démarré, l'argument n'est jamais développé par l'interpréteur de commandes et l'action échoue avec un message recommandant le lanceur natif

#### Scenario: Caractère de contrôle dans un argument
- **WHEN** un argument contient un saut de ligne
- **THEN** il est refusé avant tout démarrage de processus, quel que soit le système d'exploitation et le type de lanceur

### Requirement: Le lancement par lots conserve la frontière de propriété du processus

Le lanceur par lots SHALL être démarré à l'intérieur de la même frontière de propriété que les
lanceurs natifs (Job Object sous Windows) : l'arrêt demandé par le client (annulation, délai
dépassé, fermeture) SHALL terminer le lanceur par lots et tous ses descendants, sans processus
orphelin.

#### Scenario: Annulation d'une commande lancée par un lanceur par lots
- **WHEN** le client arrête une commande dont le `.cmd` a démarré un processus enfant de longue durée
- **THEN** le `.cmd` et son processus enfant sont tous deux terminés et le plan de lancement temporaire n'existe plus sur disque

#### Scenario: Indisponibilité de la frontière de propriété
- **WHEN** la frontière de propriété ne peut pas être établie (hôte non qualifié ou répertoire de propriété non sûr)
- **THEN** le lanceur par lots n'est pas démarré et l'action échoue sans repli en mode dégradé

### Requirement: Les lanceurs natifs ne passent pas par un interpréteur de commandes

Pour un lanceur natif (`minos.exe` sous Windows, `minos` sous Linux), le client SHALL transmettre
les arguments un à un au système, sans les rendre à un interpréteur de commandes, et SHALL
accepter sans restriction supplémentaire les caractères `"` et `%`.

#### Scenario: Lanceur natif Windows
- **WHEN** le lanceur configuré est `minos.exe` et qu'un argument contient `"` ou `%`
- **THEN** l'argument est transmis intact et n'est pas refusé

#### Scenario: Lanceur sous Linux
- **WHEN** le client est exécuté sous Linux avec un lanceur `minos` et un argument contenant `"` ou `%`
- **THEN** la commande construite contient l'exécutable puis chaque argument tel que fourni, sans interpréteur intermédiaire

### Requirement: Le plugin reste un client externe du protocole CLI inchangé

Le plugin SHALL ne déclarer ni n'importer aucune dépendance sur un artefact `com.minos:*` et
SHALL continuer à invoquer la CLI avec les mêmes sous-commandes et options JSON du protocole
`minos-ide` v1, y compris le handshake qui précède les actions.

#### Scenario: Absence de dépendance interne
- **WHEN** la configuration de build du plugin est inspectée par la garde de CI
- **THEN** aucune déclaration d'implémentation d'un artefact `com.minos:*` n'est trouvée et la garde réussit

#### Scenario: Protocole inchangé après la correction du lancement
- **WHEN** le client construit la commande du handshake et d'une action de lecture
- **THEN** les arguments sont `ide handshake --format json` et la sous-commande attendue suivie de `--format json`, identiques à ceux d'avant la correction

#### Scenario: Version de protocole incompatible
- **WHEN** le handshake renvoie une version de protocole différente de celle attendue par le plugin
- **THEN** les actions sont bloquées avec un message qui cite la version attendue et la version reçue
