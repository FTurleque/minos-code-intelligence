# client-intellij Specification

## Purpose
Définit le comportement du client IntelliJ externe de MINOS : il pilote la CLI locale par le
protocole `minos-ide` v1 dans des processus de fond, sans dépendre d'aucun artefact interne de
MINOS, et lance cette CLI de façon fiable et fail-closed sous Windows comme sous Linux.

## Requirements

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

### Requirement: Le lanceur par défaut documenté existe dans une installation standard
Le chemin du lanceur Windows que la documentation du plugin propose par défaut SHALL désigner le fichier que l'installateur et la distribution créent effectivement. Un contrôle du dépôt SHALL échouer si la documentation cite un autre emplacement.

#### Scenario: Chemin documenté conforme
- **WHEN** la documentation du plugin cite `%LOCALAPPDATA%\Programs\MINOS\minos.cmd`
- **THEN** le contrôle documentaire réussit, ce chemin correspondant à `DefaultDirName` de l'installateur et à l'emplacement de `minos.cmd` dans la distribution

#### Scenario: Chemin documenté inexistant
- **WHEN** la documentation cite `…\Programs\MINOS\bin\minos.cmd`
- **THEN** le contrôle documentaire échoue en nommant le fichier, la ligne et l'emplacement attendu

### Requirement: Un échec de tâche d'arrière-plan est journalisé et jamais avalé
Toute tâche d'arrière-plan du plugin SHALL laisser passer `ProcessCanceledException` et les `Error`, SHALL journaliser toute autre exception dans `idea.log` avec sa pile et son message, et SHALL transmettre l'échec à l'affichage. Quand l'exception n'a pas de message ou un message vide, l'affichage SHALL nommer le type de l'exception.

#### Scenario: Exception sans message
- **GIVEN** une opération qui lève une `NullPointerException` sans message
- **WHEN** la tâche d'arrière-plan l'exécute
- **THEN** l'enregistreur reçoit l'exception avec sa pile, et le texte affiché contient `NullPointerException`, jamais `Unknown failure` seul

#### Scenario: Exception avec message
- **GIVEN** une opération qui lève une `IllegalStateException("projet absent")`
- **WHEN** la tâche d'arrière-plan l'exécute
- **THEN** l'enregistreur la reçoit avec sa pile et l'affichage contient `projet absent`

#### Scenario: Erreur de la JVM
- **GIVEN** une opération qui lève une `OutOfMemoryError`
- **WHEN** la tâche d'arrière-plan l'exécute
- **THEN** l'erreur est relancée telle quelle, sans être mémorisée comme un échec affichable

#### Scenario: Annulation
- **GIVEN** une opération qui lève une `ProcessCanceledException`
- **WHEN** la tâche d'arrière-plan l'exécute
- **THEN** l'exception est relancée sans journal ni affichage d'erreur

### Requirement: Une seule tâche d'arrière-plan sert toutes les actions du plugin
Les actions de l'éditeur, les actions M21 et le panneau d'outils SHALL exécuter leurs opérations par la même classe de tâche, sans `catch (Throwable)` dans le code des actions.

#### Scenario: Aucune capture générique dans le plugin
- **WHEN** `grep -rn "catch (Throwable" minos-intellij/src/main/java` est exécuté
- **THEN** il ne renvoie aucune ligne

#### Scenario: Pas de cycle de packages introduit
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté
- **THEN** la règle A8 rapporte 0 cycle, plugin compris

### Requirement: Le lanceur de développement ne résout pas java depuis le répertoire courant
Le lanceur `minos.cmd` de la racine du dépôt SHALL appeler `%JAVA_HOME%\bin\java.exe` quand ce fichier existe et SHALL, sinon, interdire la recherche de `java` dans le répertoire courant. Un fichier `java.bat` ou `java.exe` du projet ouvert par le plugin SHALL NOT être exécuté.

#### Scenario: java.bat piégé dans le répertoire courant
- **GIVEN** un répertoire courant contenant un `java.bat` qui écrit un marqueur, un `java` valide dans le `PATH`, et la variable `NoDefaultCurrentDirectoryInExePath` retirée de l'environnement transmis au lanceur
- **WHEN** `minos.cmd` est exécuté depuis ce répertoire
- **THEN** le `java` du `PATH` s'exécute et le marqueur du `java.bat` n'est pas écrit

#### Scenario: JAVA_HOME défini
- **GIVEN** `JAVA_HOME` désignant un JDK de test dont `bin\java.exe` écrit un marqueur, et un `java.bat` piégé dans le répertoire courant
- **WHEN** `minos.cmd` est exécuté
- **THEN** le marqueur du JDK de `JAVA_HOME` est écrit et celui du piège ne l'est pas

#### Scenario: Aucune fuite vers l'appelant
- **GIVEN** un interpréteur appelant dont l'environnement ne contient pas `NoDefaultCurrentDirectoryInExePath`
- **WHEN** `minos.cmd` est appelé puis se termine, et l'appelant affiche ensuite la variable
- **THEN** l'appelant ne la voit toujours pas (portée limitée par `setlocal`)

### Requirement: Les dépendances du plugin IntelliJ sont verrouillées et analysées
Le build du plugin SHALL verrouiller, en mode strict, les dépendances de son classpath de compilation, d'exécution, de test et de script de build dans un fichier de verrou versionné. Le gate de vulnérabilités des pull requests SHALL analyser ce verrou. Un gate statique SHALL échouer si le verrou ou son activation disparaît.

#### Scenario: Verrou absent
- **GIVEN** une copie du dépôt sans `minos-intellij/gradle.lockfile`
- **WHEN** le gate du plugin est exécuté sur cette copie
- **THEN** il échoue en nommant le fichier attendu

#### Scenario: Dépendance résolue hors verrou
- **GIVEN** une copie du dépôt où la version d'une dépendance du plugin est modifiée sans régénérer le verrou
- **WHEN** le build du plugin est exécuté
- **THEN** il échoue en nommant la dépendance

#### Scenario: Dépendance vulnérable dans le verrou
- **GIVEN** une copie du dépôt dont le verrou référence une version touchée par un avis connu
- **WHEN** OSV-Scanner est exécuté avec les arguments du job de pull request
- **THEN** l'analyse échoue en nommant la dépendance et l'avis

#### Scenario: Build identique sur les deux systèmes
- **GIVEN** le verrou versionné
- **WHEN** les tests du plugin sont exécutés sous Linux puis sous Windows
- **THEN** les deux builds réussissent sans modifier le verrou

### Requirement: L'intégrité des artefacts résolus par le plugin est vérifiée ou sa limite déclarée
Le build du plugin SHALL vérifier la somme SHA-256 des artefacts qu'il résout d'après des métadonnées versionnées, pour le périmètre retenu par la décision du design. Tout artefact hors de ce périmètre SHALL être nommé dans la documentation de la chaîne d'approvisionnement comme non vérifié.

#### Scenario: Artefact dont la somme diffère
- **GIVEN** des métadonnées de vérification où la somme d'une dépendance a été altérée
- **WHEN** le build du plugin est exécuté
- **THEN** il échoue en nommant l'artefact

#### Scenario: Métadonnées absentes alors que la vérification est retenue
- **GIVEN** une copie du dépôt sans métadonnées de vérification
- **WHEN** le gate du plugin est exécuté sur cette copie
- **THEN** il échoue

#### Scenario: Périmètre déclaré
- **GIVEN** la documentation de la chaîne d'approvisionnement
- **WHEN** elle est relue après ce changement
- **THEN** elle nomme les artefacts vérifiés par somme et ceux qui ne le sont pas

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

### Requirement: La couverture des tests du plugin est mesurée et publiée
Le build Gradle du plugin IntelliJ SHALL produire un rapport JaCoCo des tests unitaires limité aux classes `com.minos.intellij`. Le workflow du plugin SHALL produire ce rapport sur Linux et sur Windows et le publier en artefact. L'absence du rapport après les tests SHALL faire échouer le job.

#### Scenario: Rapport produit localement
- **GIVEN** le dossier `minos-intellij`
- **WHEN** `./gradlew --no-daemon test jacocoTestReport` est exécuté
- **THEN** un rapport XML de couverture existe sous `build/reports/jacoco/` et ne contient que des classes `com/minos/intellij/`

#### Scenario: Rapport absent
- **GIVEN** une copie du build où la tâche de rapport est retirée
- **WHEN** la vérification de couverture est exécutée
- **THEN** elle échoue au lieu de réussir sans mesure

### Requirement: Une couverture du plugin sous son plancher est refusée
Le build du plugin SHALL échouer quand la couverture de lignes ou de branches mesurée est sous le plancher déclaré dans `build.gradle.kts`, pour le plugin entier et pour le paquet `protocol`. Cet échec SHALL faire échouer le check exigé du plugin. Un plancher SHALL NOT être présenté comme tenu sur un système où il n'est pas évalué.

#### Scenario: Test supprimé
- **GIVEN** une copie du plugin où une classe de test de `protocol` est supprimée
- **WHEN** `./gradlew --no-daemon test jacocoTestCoverageVerification` est exécuté
- **THEN** le build échoue en nommant la règle violée, la mesure et le plancher

#### Scenario: Dépôt réel
- **GIVEN** le plugin au HEAD du changement
- **WHEN** la même commande est exécutée sous Linux
- **THEN** elle réussit

### Requirement: La logique du plugin est testable sans la plateforme IntelliJ
La logique du plugin qui ne manipule pas l'IDE (choix et score du symbole candidat, texte des notices, interprétation des réponses du CLI) SHALL vivre dans des classes sans type `com.intellij` et SHALL être testée en JUnit simple. Les tests du plugin SHALL NOT exiger une fixture de plateforme. Les classes d'interface laissées sans test unitaire SHALL être nommées dans la documentation du plugin.

#### Scenario: Score du symbole candidat
- **GIVEN** deux symboles renvoyés par le CLI, dont un seul dans le fichier et à la ligne du curseur
- **WHEN** la logique extraite choisit le candidat
- **THEN** elle retient ce symbole, sans instancier de projet ni d'éditeur

#### Scenario: Aucun candidat
- **GIVEN** une réponse du CLI sans symbole correspondant
- **WHEN** la logique extraite choisit le candidat
- **THEN** elle signale l'absence (résultat vide ou erreur de protocole explicite, selon le comportement relevé au HEAD) et ne choisit pas un symbole par défaut

#### Scenario: Dépendance à la plateforme réintroduite
- **GIVEN** une classe de logique extraite qui importe un type `com.intellij`
- **WHEN** `./gradlew --no-daemon test` est exécuté
- **THEN** le test de garde des classes sans plateforme échoue en nommant la classe

### Requirement: Le superviseur de processus du plugin sonde à cadence bornée
En régime établi, le superviseur de processus du plugin SHALL énumérer les descendants d'une commande MINOS au plus 15 fois par seconde, avec la même période que le suivi du runtime local, sans importer aucun artefact `com.minos:*`. Il SHALL continuer de terminer tout descendant observé, et un descendant encore vivant après l'arrêt forcé SHALL rester signalé comme un échec, jamais ignoré.

#### Scenario: Cadence en régime établi dans le plugin
- **GIVEN** un superviseur attaché à un processus factice vivant dont l'énumération des descendants compte ses appels
- **WHEN** deux secondes s'écoulent après la phase initiale
- **THEN** l'énumération a été appelée au moins une fois et au plus 30 fois

#### Scenario: Orphelin observé puis nettoyé
- **GIVEN** une commande dont le processus lance un enfant détaché puis sort
- **WHEN** le superviseur est fermé
- **THEN** l'enfant observé a été terminé

#### Scenario: Survivant après l'arrêt forcé
- **GIVEN** un descendant observé qui reste vivant après l'arrêt forcé
- **WHEN** le superviseur est fermé
- **THEN** la fermeture rapporte l'échec de nettoyage

#### Scenario: Aucune dépendance au noyau
- **GIVEN** le plugin après ce changement
- **WHEN** la garde de dépendances du plugin est exécutée
- **THEN** aucune dépendance ni import `com.minos:*` n'est trouvé

### Requirement: Le lanceur du plugin ne réécrit jamais la DACL d'un dossier qui n'est pas à lui
Sous Windows, le lanceur du plugin SHALL refuser, avant toute modification, un dossier MINOS qui est le profil de l'utilisateur, un ancêtre du profil ou une racine de volume. Il SHALL ne rendre privé un dossier préexistant que s'il appartient à l'utilisateur courant, et SHALL sinon échouer sans changer sa DACL.

#### Scenario: Dossier MINOS configuré sur le profil
- **GIVEN** des réglages dont le dossier MINOS est le profil de l'utilisateur
- **WHEN** le plugin lance une commande MINOS
- **THEN** le lancement échoue avec un message qui demande un dossier dédié, et la DACL du profil est inchangée

#### Scenario: Dossier préexistant d'un autre propriétaire
- **GIVEN** un dossier MINOS qui existe déjà et dont le propriétaire n'est pas l'utilisateur courant
- **WHEN** le plugin lance une commande MINOS
- **THEN** le lancement échoue en nommant le problème de propriété, et la DACL du dossier est inchangée

#### Scenario: Dossier de l'utilisateur
- **GIVEN** un dossier MINOS absent, ou présent et appartenant à l'utilisateur courant
- **WHEN** le plugin lance une commande MINOS
- **THEN** le dossier est réservé à son propriétaire et la commande démarre
