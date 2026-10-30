# decouverte-et-negociation Specification

## Purpose
Définit comment MINOS découvre un projet, calcule son empreinte de référence et applique la sémantique d'ignore (`.gitignore`, `.minosignore`, répertoires durcis), y compris face à un répertoire illisible, à un fichier d'ignore produit sous Windows et à un projet qui change pendant l'observation. Elle fixe aussi ce que la copie de travail d'un provider garde du projet, et où scip-java écrit ses fichiers intermédiaires : dans le run, hors de l'arbre de build du projet.

## Requirements

### Requirement: Un répertoire illisible ignoré ou durci est écarté sans faire échouer le projet
Lorsqu'un répertoire du projet ne peut pas être listé et qu'il est ignoré par `.gitignore` ou `.minosignore`, ou qu'il est un répertoire durci, la découverte, l'empreinte de référence et l'inventaire des fichiers visibles SHALL l'écarter, comme s'il était absent, et poursuivre ; une trace d'avertissement bornée le nomme par son chemin relatif.

#### Scenario: Répertoire de volume ignoré par `.gitignore`
- **WHEN** le projet contient un `pom.xml`, `src/main/java/A.java`, un répertoire `pgdata/` illisible pour le compte courant et un `.gitignore` contenant `pgdata/`
- **THEN** la découverte réussit, découvre le module et la source Java, et la capture d'empreinte réussit sans compter aucun fichier de `pgdata/`

#### Scenario: Répertoire durci illisible
- **WHEN** le projet contient un répertoire `node_modules` illisible pour le compte courant
- **THEN** la découverte et la capture d'empreinte réussissent, comme pour un `node_modules` lisible

#### Scenario: La trace est bornée et ne révèle pas de chemin absolu
- **WHEN** plus de dix répertoires illisibles ignorés sont rencontrés par une même opération
- **THEN** au plus dix traces nominatives puis une trace de synthèse sont émises, chacune portant le chemin relatif et le type de l'erreur, jamais le chemin absolu du projet ni le message de l'exception

#### Scenario: L'inspection du projet en hérite
- **WHEN** le projet contient un répertoire illisible ignoré et qu'on demande l'inspection du projet ou le statut d'index
- **THEN** la découverte ne fait pas échouer la réponse du fait de ce répertoire

### Requirement: Un répertoire illisible non ignoré échoue de façon actionnable
Lorsqu'un répertoire du projet ne peut pas être listé et qu'il n'est ni ignoré ni durci, la découverte et l'empreinte de référence SHALL échouer (fail-closed), et le message d'échec SHALL nommer le répertoire par son chemin relatif et suggérer de l'exclure par `.minosignore`, sans chemin absolu.

#### Scenario: Répertoire de données non ignoré et illisible
- **WHEN** le projet contient un répertoire `data/db` illisible pour le compte courant, qu'aucune règle d'ignore ne couvre
- **THEN** la découverte échoue, et le message contient `data/db` et `.minosignore` mais pas le chemin absolu de la racine du projet

#### Scenario: La classification publique de l'échec est conservée
- **WHEN** l'échec d'un répertoire illisible non ignoré traverse l'API Java ou le MCP
- **THEN** il reste classé comme refus d'accès, avec le même code d'erreur public qu'avant, et son message public ne contient aucun chemin absolu

#### Scenario: L'ajout de la règle d'ignore débloque le projet
- **WHEN** l'utilisateur ajoute `data/db/` à `.minosignore` après cet échec
- **THEN** la découverte et la capture d'empreinte réussissent

### Requirement: La racine du projet ou du scope illisible échoue toujours
Lorsque la racine du projet, ou la racine du scope d'une empreinte par scope, ne peut pas être listée, la découverte et l'empreinte SHALL échouer, quelles que soient les règles d'ignore : une racine illisible n'est jamais écartée.

#### Scenario: Racine illisible
- **WHEN** la racine du projet est illisible pour le compte courant
- **THEN** la découverte échoue et aucun résultat partiel n'est produit

### Requirement: La copie de travail d'un provider écarte les répertoires illisibles ignorés
La copie du projet vers l'espace de travail éphémère d'un provider SHALL écarter un répertoire illisible ignoré ou durci, sans le recréer ni le copier, et SHALL échouer, avec le même message actionnable, pour un répertoire illisible non ignoré.

#### Scenario: Copie d'un projet avec un volume ignoré illisible
- **WHEN** un projet dont `pgdata/` est illisible et ignoré est copié pour un provider
- **THEN** la copie réussit, ne contient aucun fichier de `pgdata/`, et l'indexation n'échoue pas du fait de ce répertoire

#### Scenario: Copie d'un projet avec un répertoire non ignoré illisible
- **WHEN** un projet dont `data/db` est illisible et non ignoré est copié pour un provider
- **THEN** la copie échoue avec un message qui nomme `data/db` et suggère `.minosignore`, et aucun résidu partiel n'est conservé

### Requirement: Le BOM UTF-8 d'un fichier d'ignore n'altère pas sa première règle
La lecture de `.gitignore` et de `.minosignore` SHALL retirer un éventuel BOM UTF-8 en tête de fichier, sur toutes les plateformes, de sorte que la première règle s'applique comme dans Git.

#### Scenario: `.gitignore` enregistré avec BOM
- **WHEN** `.gitignore` est encodé en UTF-8 avec BOM et contient `generated/` puis `out2/`
- **THEN** `generated/X.java` est ignoré, comme `out2/Y.java`

#### Scenario: `.minosignore` enregistré avec BOM
- **WHEN** `.minosignore` est encodé en UTF-8 avec BOM et sa première règle est `build-cache/`
- **THEN** un fichier de `build-cache/` est ignoré

#### Scenario: Fichier sans BOM inchangé
- **WHEN** un fichier d'ignore ne porte pas de BOM
- **THEN** ses règles s'appliquent exactement comme avant

### Requirement: L'empreinte de référence est capturée avant la découverte
Dans l'unique orchestration d'indexation du produit, l'empreinte de référence SHALL être capturée avant la découverte, de sorte qu'une structure apparue entre la capture et la découverte, ou pendant le run, empêche la promotion de la baseline au lieu d'être déclarée couverte sans avoir été indexée.

#### Scenario: Un module apparaît juste après la découverte
- **GIVEN** un projet indexé par le cas d'usage d'indexation
- **WHEN** un module est créé après que la découverte a rendu son résultat et avant la fin du run, puis que le run se termine avec succès
- **THEN** l'empreinte de référence n'est pas promue, un diagnostic signale que l'espace de travail a changé, et l'exécution suivante est une indexation complète

#### Scenario: Arbre stable
- **GIVEN** un projet indexé par le cas d'usage d'indexation
- **WHEN** aucun fichier ne change entre la capture de l'empreinte et la fin du run
- **THEN** l'empreinte de référence est promue comme avant

### Requirement: scip-java écrit ses fichiers intermédiaires hors de l'arbre de build du projet
Le plan d'exécution de scip-java SHALL passer un `--targetroot` situé dans le répertoire du run, hors de la copie de travail du projet, pour la commande sur classpath vérifié, pour le lanceur autonome et pour le runner Windows. Un module dont le `clean` vide le répertoire de build d'un autre module SHALL NOT retirer de l'index les fichiers des modules construits avant lui.

#### Scenario: Commande Linux sur classpath vérifié
- **GIVEN** un run dont l'artefact est `<run>/index.scip` et la copie de travail `<run>/workspace`
- **WHEN** la commande scip-java du plan est construite
- **THEN** elle contient `--targetroot <run>/scip-java-targetroot`, chemin absolu hors de `<run>/workspace`

#### Scenario: Reactor dont le dernier module construit dans le target racine
- **GIVEN** un reactor Maven dont le dernier module déclare `<directory>${maven.multiModuleProjectDirectory}/target</directory>`
- **WHEN** MINOS indexe ce reactor avec scip-java
- **THEN** l'index contient les fichiers de tous les modules, pas seulement ceux du dernier

### Requirement: Le produit n'a qu'une orchestration d'indexation
La préparation, l'exécution, la reprise, la promotion de l'empreinte, la synchronisation sémantique et la rétention d'une indexation SHALL être coordonnées par un seul cas d'usage de `minos-application`, invocable sans la CLI. La commande `minos index` SHALL lui déléguer. Aucune autre classe de production SHALL NOT coordonner ces étapes.

#### Scenario: Indexation invoquée sans CLI
- **GIVEN** une application MINOS ouverte dans un test, sans classe de `minos-cli`
- **WHEN** le cas d'usage d'indexation est appelé sur un projet enregistré
- **THEN** le run réussit, le snapshot est promu et l'empreinte de référence est publiée

#### Scenario: Sorties de la commande inchangées
- **GIVEN** les goldens de caractérisation de `minos-app`
- **WHEN** `./mvnw -B -ntp -pl minos-app -am verify` est exécuté après la délégation
- **THEN** les sorties de `minos index` sont identiques à l'octet, hors diagnostic additif décidé

#### Scenario: Coordinateur sans appelant retiré
- **GIVEN** le dépôt après le changement
- **WHEN** on recherche `IncrementalIndexingCoordinator` dans les sources de production et de test
- **THEN** la recherche ne retourne aucune ligne

### Requirement: Une baseline d'empreinte illisible est déclarée
Quand la baseline d'empreinte active ne peut pas être lue, l'orchestration SHALL exiger une indexation complète avec la raison `FINGERPRINT_BASELINE_UNREADABLE` et SHALL rendre un diagnostic qui le dit, sans chemin absolu ni cause interne. Elle SHALL NOT traiter la baseline comme absente sans le signaler.

#### Scenario: Baseline illisible
- **GIVEN** un projet dont le magasin d'empreintes lève une erreur d'E/S à la lecture de la baseline active
- **WHEN** l'indexation est exécutée
- **THEN** le plan est complet, sa raison est `FINGERPRINT_BASELINE_UNREADABLE` et le résultat porte un diagnostic de baseline illisible

#### Scenario: Diagnostic sans chemin
- **GIVEN** une erreur de lecture dont le message contient un chemin absolu
- **WHEN** le diagnostic est rendu
- **THEN** il ne contient ni ce chemin ni la racine du projet

### Requirement: Une indexation à cible racine hache l'arbre source au plus deux fois
Une indexation réussie dont une cible est à la racine du projet SHALL capturer au plus deux empreintes complètes de l'arbre source : celle de référence et celle de fin de run. L'empreinte du scope racine enregistrée au point de reprise SHALL être celle de référence, réutilisée. Si l'égalité des politiques d'empreinte n'est pas établie, l'empreinte de scope SHALL être recalculée.

#### Scenario: Cible racine
- **GIVEN** un projet à cible racine et un service d'empreinte qui compte ses captures
- **WHEN** une indexation complète réussit
- **THEN** le compteur vaut au plus 2

#### Scenario: Politique d'empreinte non prouvée égale
- **GIVEN** une orchestration dont le service d'empreinte n'est pas celui de l'exécuteur de run
- **WHEN** une indexation à cible racine s'exécute
- **THEN** l'empreinte du scope racine est recalculée et n'est pas reprise de l'empreinte de référence

#### Scenario: Scope non racine
- **GIVEN** un projet dont une cible est dans un sous-répertoire
- **WHEN** l'indexation s'exécute
- **THEN** l'empreinte de ce scope est capturée une fois pour ce scope, comme avant

### Requirement: La réutilisation ne change ni l'empreinte persistée ni la preuve de stabilité
L'empreinte de scope persistée au point de reprise SHALL être identique, à l'octet, à celle que produit la capture du scope. L'empreinte de fin de run SHALL rester un hachage complet du contenu des fichiers et SHALL NOT être déduite de la taille ou de la date de modification.

#### Scenario: Égalité avec la capture du scope
- **GIVEN** un arbre source figé
- **WHEN** l'empreinte réutilisée pour le scope racine est comparée à la capture directe de ce scope
- **THEN** les deux valeurs sont égales

#### Scenario: Reprise d'un run antérieur au changement
- **GIVEN** un run interrompu dont le point de reprise a été écrit avant le changement, sur un arbre inchangé
- **WHEN** l'indexation est relancée avec reprise
- **THEN** la cible est réutilisée et la reprise n'est pas refusée pour empreinte différente

#### Scenario: Fichier modifié sans changement de taille ni de date
- **GIVEN** un fichier source dont le contenu change pendant le run, taille et date de modification restaurées
- **WHEN** le run se termine
- **THEN** l'empreinte de référence n'est pas promue et un diagnostic signale que l'espace de travail a changé
