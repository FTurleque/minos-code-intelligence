## ADDED Requirements

### Requirement: Le mécanisme d'ACL Windows est un port découvert, fail-closed
Sur un système de fichiers à ACL, `PrivateLocalStorage` SHALL protéger un objet de l'héritage et lire sa DACL par le port `PlatformAccessControl`, découvert une fois par processus avec son propre chargeur de classes. Si aucune implémentation n'est découverte, ou si plusieurs le sont, le durcissement SHALL échouer par une erreur sans chemin absolu. Le chemin POSIX SHALL NOT consulter le port.

#### Scenario: Aucune implémentation sous Windows
- **GIVEN** un processus Windows dont le chargeur de classes ne fournit aucune implémentation du port
- **WHEN** un répertoire privé est créé
- **THEN** la création échoue, l'erreur ne contient aucun chemin absolu et le répertoire créé est supprimé

#### Scenario: Deux implémentations
- **GIVEN** un chargeur de classes qui fournit deux implémentations du port
- **WHEN** le durcissement consulte le port
- **THEN** il échoue sans écrire la DACL

#### Scenario: Système POSIX sans implémentation
- **GIVEN** un système de fichiers POSIX et aucune implémentation du port
- **WHEN** un répertoire privé est créé
- **THEN** il est créé avec les droits du propriétaire seul et le port n'est pas consulté

#### Scenario: JAR distribué
- **GIVEN** le JAR ombré de `minos-app`
- **WHEN** le port est découvert
- **THEN** exactement une implémentation est trouvée, celle de `minos-runtime-local`

### Requirement: Le lanceur d'icacls accepte les chemins longs
Le lanceur d'`icacls` de `minos-runtime-local` SHALL passer chaque chemin cible absolu avec le préfixe `\\?\` (`\\?\UNC\` pour un chemin UNC), après avoir refusé un chemin relatif ou porteur d'un caractère de contrôle ou de `*?"<>|`. Il SHALL servir à la fois au port d'ACL et aux octrois du bac à sable.

#### Scenario: Objet au chemin de plus de 260 caractères
- **GIVEN** sous Windows, un répertoire privé à créer sous un chemin de plus de 260 caractères
- **WHEN** il est créé par `PrivateLocalStorage`
- **THEN** il est créé, protégé de l'héritage, et sa DACL a été lue

#### Scenario: Chemin refusé avant le lancement
- **GIVEN** un chemin cible relatif ou porteur d'un caractère interdit
- **WHEN** le lanceur est appelé
- **THEN** aucun processus n'est lancé et l'appel échoue sans nommer le chemin

### Requirement: La doublure de test du port reste alignée sur le mécanisme réel
La doublure de test du port publiée par le `test-jar` de `minos-engine` SHALL produire, sous Windows, la même DACL que l'implémentation de `minos-runtime-local` sur des objets identiques. Seuls les modules qui ne voient pas `minos-runtime-local` SHALL l'enregistrer, et le `test-jar` SHALL NOT porter son enregistrement.

#### Scenario: Parité des deux implémentations
- **GIVEN** sous Windows, deux répertoires identiques dont le parent porte un refus conditionnel et une autorisation conditionnelle héritables
- **WHEN** l'un est durci par la doublure et l'autre par l'implémentation réelle
- **THEN** les deux SDDL relus sont égaux, au SID près des objets

#### Scenario: Module supérieur
- **GIVEN** les tests de `minos-bootstrap`, qui voient le `test-jar` du moteur et `minos-runtime-local`
- **WHEN** le port est découvert
- **THEN** une seule implémentation est trouvée, celle de `minos-runtime-local`
