# Proposal

## Why

**AUD-SEC-02** (moyenne, sprint 2 de l'audit du 10 octobre 2026) reste **partiel** dans l'état revérifié du 13 octobre (`docs/project/audits/2026-10-10/ETAT-2026-10-13.md`) : le changement archivé `epingler-et-isoler-l-installation-des-providers` vérifie le classpath de scip-java **à l'installation**, mais laissait hors périmètre le plan d'**indexation** (sa décision D2, « changement séparé de confinement à ouvrir »). Au HEAD `90c24fd0` :

- `ScipJavaProcessPlanFactory.java:150-165` : sous Linux, sans lanceur autonome `scip-java` dans le `PATH`, le plan relance `coursier launch org.scip-code:scip-java:0.13.1 --jvm system --main …` : résolution **par coordonnée**, sans empreinte, au moment de l'indexation. C'est le défaut d'origine du constat.
- `ScipJavaProcessPlanFactory.java:119-122` et `scip-java-windows-runner.ps1:379-384` : sous Windows, quand `classpath.txt` est absent, le runner appelle `coursier fetch --classpath <coordonnée>` puis exécute le résultat, sans vérification.
- `ManagedScipProviderRuntimeManager.java:369-379` : le classpath vérifié est une liste de chemins **absolus vers le cache Coursier** de l'utilisateur ; un jar du cache remplacé après l'installation est exécuté tel quel (limite (1) écrite dans l'ADR 0040).
- Constat annexe : sous Windows, une installation hors paquet écrit ces chemins absolus, que `Read-ShippedClasspath` (`scip-java-windows-runner.ps1:223-233`) joint au dossier de la liste (`Join-Path`) : le runner ne trouve pas les jars.

## What Changes

- **L'installation range une copie vérifiée** du classpath dans `MINOS_HOME/tools/scip-java/0.13.1/classpath/` : jars copiés depuis le cache, copies comparées au verrou, puis un lanceur (`scip-java-launcher.jar`, manifeste seul : `Main-Class` et `Class-Path` relatif) et en dernier la liste `classpath.txt` (noms de fichiers relatifs, la disposition du paquet Windows). Une installation existante qui ne se vérifie pas (format d'une version antérieure, jar remplacé) est refaite.
- **Le plan d'indexation revérifie avant d'exécuter**, sous Windows comme sous Linux : chaque jar de la liste est comparé au verrou ; sous Linux, le lanceur est comparé aux octets dérivés de la liste. Toute différence, ou un classpath absent, refuse le plan avec la commande de réparation, avant toute copie du projet.
- **Sous Linux**, `coursier launch` disparaît : le classpath installé passe en premier ; un lanceur autonome du `PATH` n'est utilisé qu'en son absence (image Docker, ou outil choisi par l'opérateur). Sinon le plan est `$JAVA_HOME/bin/java -jar <lanceur> index --output <sortie>`. Le lanceur vit sous `MINOS_HOME/tools`, que le bac à sable bubblewrap monte déjà en lecture seule pour un argument de commande qui s'y trouve (`managedRuntimeRoot`).
- **Sous Windows**, `-ClasspathFile` est toujours transmis au runner ; le repli du runner sur Coursier n'est plus atteignable depuis MINOS (il reste pour `benchmarks/scalability/prepare-corpus.ps1`).
- L'état du provider dit « non installé » quand le classpath manque (hors lanceur autonome dans le `PATH`, cas de l'image Docker).
- Les fichiers copiés héritent des droits du dossier privé (comme l'arbre extrait du paquet Windows) au lieu de porter chacun une liste de droits protégée : le bac à sable Windows accorde sa lecture sur le dossier, que des fichiers protégés n'héritaient pas (défaut déjà présent avant ce changement pour `classpath.txt`, relevé par la revue de sécurité). L'ancien arbre est mis de côté jusqu'à la substitution, puis supprimé.
- `ScipJavaProcessPlanFactory` passe de trois constructeurs à un seul, qui reçoit le vérificateur (sens de l'ADR 0045).

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `installation-des-providers` : deux exigences ajoutées (copie vérifiée rangée dans `MINOS_HOME/tools` ; plan d'indexation qui n'exécute que ce classpath revérifié).

## Hors périmètre

- L'image Docker : elle construit son lanceur autonome (`cs bootstrap --standalone`) sans ce verrou (limite (2) de l'ADR 0040, inchangée) ; le plan l'utilise toujours en premier.
- Le montage du JDK du projet dans le bac à sable Linux (déjà vrai de l'ancien plan : un JDK hors de `/usr` n'y est pas visible).
- La fenêtre entre la vérification et l'ouverture des jars par la JVM : un remplacement par le **même compte** pendant cette fenêtre reste possible ; il suppose la compromission du compte.
- Le repli Coursier du runner Windows utilisé par le script de benchmark.
- Restreindre le lanceur autonome du `PATH` à un exécutable système possédé par root ou à un marqueur Docker : décision du propriétaire (un `scip-java` installé à la main hors de MINOS, sans classpath installé, reste exécuté comme `node` ou `dotnet` pour les autres providers).
- Les installations concurrentes de scip-java (aucun verrou de fichier) : la seconde peut échouer ; le plan revérifie de toute façon.
- Les décisions D1 et D2 de `rendre-le-test-scip-java-hermetique` (AUD-TST-18) : ce changement réduit les constructeurs à un seul mais ne touche pas au `if (CommandLocator.isWindows())` du test.

## Impact

- **Modules** : `minos-provider-scip` seul (paquets `runtime.plan` et `runtime.install`). Aucune dépendance nouvelle ; sens `install → plan` déjà existant.
- **Surfaces publiques** : aucune signature de `com.minos.api`, de la CLI ni du MCP ne change. Message nouveau à l'indexation (« managed scip-java classpath is not installed; run `minos tools install scip-java` ») et diagnostic nouveau de `minos tools` (« managed scip-java 0.13.1 classpath is not installed in MINOS_HOME/tools »).
- **ADR** : amende l'ADR 0040 (limite (1) levée) ; pas de nouvel ADR.
- **Utilisateurs existants** : une installation antérieure est « prête » mais son plan est refusé avec la commande de réparation ; `minos tools install scip-java` la refait (≈ 86 Mo copiés).
