# Proposal

## Why

Constat relevé le 2026-10-15 en mesurant les restes du sprint 4 de l'audit du 10 octobre 2026 (PR #461, `docs/audit/2026-10-10/SUIVI.md`, « Clôture du sprint 4 », constat 2). Indexé par MINOS sous Linux, ce dépôt ne livre que les 51 fichiers de `minos-app`, sur 14 modules.

Le mécanisme est le suivant :
- Sans `--targetroot`, scip-java écrit ses fichiers intermédiaires (semanticdb) dans `target/scip-targetroot` **à la racine du projet** ; c'est sa valeur par défaut pour Maven (`scip-java index --help`).
- `minos-app/pom.xml:37` place le répertoire de build de `minos-app` dans ce même `target/` racine (`<directory>${maven.multiModuleProjectDirectory}/target</directory>`), pour y publier le JAR ombré.
- `minos-app` est construit en dernier. Son `clean` (le build lancé par scip-java est `clean verify -DskipTests`) vide le `target/` racine, et avec lui les semanticdb des 13 modules construits avant lui. Le journal du provider le montre : `Deleting …/workspace/target`.

Retirer cette surcharge sur une copie du corpus suffit à faire indexer les 532 fichiers de production. Mais le `target/` racine est un contrat largement consommé : `minos.cmd`, `docker/Dockerfile.mcp`, `scripts/release/build-windows-distribution.ps1`, `scripts/ci/qualify-docker-release.sh`, les IT de `minos-app` (`minos.shaded.jar`), `check-jacoco.py` et la documentation utilisateur.

Le défaut n'est d'ailleurs pas propre à ce dépôt : tout projet Maven dont un module construit dans le répertoire de build d'un autre, ou dans celui de la racine, perd silencieusement les semanticdb de ce module. Le résultat est un index incomplet, présenté comme réussi.

## What Changes

- **Le provider scip-java fixe lui-même le répertoire des fichiers intermédiaires** : `--targetroot` pointe vers un répertoire du run (`<run>/scip-java-targetroot`), hors de la copie de travail et donc hors de tout `target/` du projet. Ce répertoire est déjà lié en écriture dans le bac à sable (parent de l'artefact `index.scip`).
- Cela vaut pour les deux commandes Linux et Docker (`classpathCommand`, classpath vérifié ; `standaloneCommand`, lanceur autonome) et pour la branche Java du runner Windows, qui le fait déjà pour Kotlin (`scip-java-target`).
- **Aucun changement du build du produit** : le JAR ombré reste dans le `target/` racine.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `decouverte-et-negociation` : le provider scip-java écrit ses fichiers intermédiaires hors de l'arbre de build du projet indexé.

## Hors périmètre

- La limite de persistance de 256 Mio, que le reactor complet dépasse quand ses tests sont inclus (constat 1 du même suivi), et le budget de vecteurs de la synchronisation sémantique (constat 3).
- Le build du produit (`minos-app/pom.xml`) et ses consommateurs.
- La copie de travail créée sous Windows (le runner y indexe le projet lui-même ; seule la cible change).

## Impact

- **Module** : `minos-provider-scip` (`runtime/plan/ScipJavaProcessPlanFactory`, ressource `runtime/scip-java-windows-runner.ps1`). Aucun port, aucune dépendance, aucune sortie publique modifiés.
- **Changement actif voisin** : `executer-scip-java-sur-classpath-verifie` (tâches 1.5 et 2.3 manuelles) teste `classpathCommand`. Son test `ScipJavaClasspathTest.theClasspathCommandIsJavaJarTheLauncherThenTheIndexArguments` est mis à jour ici ; sa preuve manuelle 1.5 lira la commande avec `--targetroot`.
