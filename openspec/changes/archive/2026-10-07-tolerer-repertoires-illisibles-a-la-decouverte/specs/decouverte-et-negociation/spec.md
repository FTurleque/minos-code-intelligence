# Spec Delta

## Purpose

Définit comment MINOS découvre un projet, calcule son empreinte de référence et applique la sémantique d'ignore (`.gitignore`, `.minosignore`, répertoires durcis), y compris face à un répertoire illisible, à un fichier d'ignore produit sous Windows et à un projet qui change pendant l'observation.

## ADDED Requirements

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
Dans l'indexation autonome comme dans le rafraîchissement incrémental, l'empreinte de référence SHALL être capturée avant la découverte, de sorte qu'une structure apparue entre la capture et la découverte, ou pendant le run, empêche la promotion de la baseline au lieu d'être déclarée couverte sans avoir été indexée.

#### Scenario: Un module apparaît juste après la découverte
- **WHEN** un module est créé après que la découverte a rendu son résultat et avant la fin du run, puis que le run se termine avec succès
- **THEN** l'empreinte de référence n'est pas promue, un diagnostic signale que l'espace de travail a changé, et l'exécution suivante est une indexation complète

#### Scenario: Arbre stable
- **WHEN** aucun fichier ne change entre la capture de l'empreinte et la fin du run
- **THEN** l'empreinte de référence est promue comme avant
