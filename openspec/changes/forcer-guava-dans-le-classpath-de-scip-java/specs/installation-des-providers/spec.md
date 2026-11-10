# Spec Delta

## ADDED Requirements

### Requirement: Une version forcée du classpath de scip-java est nominative et justifiée
Le verrou du classpath de scip-java SHALL déclarer chaque version forcée par un module `organisation:nom`, une version, l'identifiant de l'avis corrigé et une raison non vide, et SHALL épingler le jar `<nom>-<version>.jar` correspondant. `scripts/quality/check-tools-manifest.py` SHALL échouer pour une entrée sans avis ni raison, en double, ou dont le jar forcé est absent du verrou.

#### Scenario: Verrou livré avec guava forcé
- **GIVEN** le verrou livré du dépôt
- **WHEN** `python scripts/quality/check-tools-manifest.py` est exécuté
- **THEN** il réussit
- **AND** le verrou épingle `guava-33.7.2-jre.jar`, n'épingle plus `guava-33.6.0-jre.jar`, et déclare `com.google.guava:guava` forcé en `33.7.2-jre` pour `GHSA-xxph-c9ww-hj94`

#### Scenario: Entrée forcée sans raison
- **GIVEN** une copie du verrou dont l'entrée forcée a une raison vide
- **WHEN** l'auto-test `python scripts/quality/test_check_tools_manifest.py` exécute ce cas
- **THEN** le gate échoue en nommant le module forcé et le verrou
- **AND** aucun fichier n'est modifié

#### Scenario: Jar forcé absent du verrou
- **GIVEN** une copie du verrou qui force `com.google.guava:guava:33.7.2-jre` mais épingle `guava-33.6.0-jre.jar`
- **WHEN** l'auto-test exécute ce cas
- **THEN** le gate échoue en nommant le jar forcé manquant

### Requirement: L'installation de scip-java applique les versions forcées du verrou
La résolution du classpath de scip-java à l'installation SHALL passer à `coursier fetch --classpath` une option `--force-version <module>:<version>` par entrée forcée du verrou, dans l'ordre du verrou, et aucune autre. Elle SHALL NOT résoudre sans ces options quand le verrou en déclare. Une entrée forcée malformée SHALL faire échouer l'installation avant tout lancement de `coursier`.

#### Scenario: Commande de résolution avec guava forcé
- **GIVEN** un faux `coursier` qui enregistre ses arguments et un verrou de test qui force `com.google.guava:guava:33.7.2-jre`
- **WHEN** l'installation de scip-java est exécutée (test `ClasspathLockTest` de `minos-provider-scip`)
- **THEN** les arguments enregistrés sont `fetch --classpath --force-version com.google.guava:guava:33.7.2-jre org.scip-code:scip-java:0.13.2`
- **AND** aucun argument ne contient `launch` ni `--main`

#### Scenario: Verrou sans version forcée
- **GIVEN** un verrou de test sans entrée forcée
- **WHEN** la commande de résolution est construite
- **THEN** elle vaut `fetch --classpath org.scip-code:scip-java:0.13.2`, sans option de forçage

#### Scenario: Entrée forcée malformée
- **GIVEN** un verrou de test dont l'entrée forcée n'a pas la forme `organisation:nom` ou a une version vide
- **WHEN** l'installation de scip-java est exécutée
- **THEN** elle échoue (fail-closed) avec un message qui ne contient aucun chemin absolu
- **AND** le faux `coursier` n'a enregistré aucun appel et aucun `classpath.txt` n'existe

### Requirement: Le verrou de scip-java se régénère par le script de construction avec ses versions forcées
`scripts/release/build-embedded-tools.py --update-classpath-lock` SHALL résoudre le classpath avec le Coursier épinglé du catalogue et les versions forcées du verrou, puis SHALL réécrire le nom, la taille et le SHA-256 de chaque jar en conservant les entrées forcées. Il SHALL refuser d'écrire le verrou si un module forcé n'est pas résolu à sa version forcée. Sans l'option, une résolution différente du verrou SHALL échouer.

#### Scenario: Régénération avec un faux Coursier
- **GIVEN** un faux Coursier qui produit des jars dont `guava-33.7.2-jre.jar`, et un verrou de test qui force cette version
- **WHEN** `python scripts/release/test_build_embedded_tools.py` exécute la régénération
- **THEN** les arguments passés au faux Coursier contiennent `--force-version com.google.guava:guava:33.7.2-jre`
- **AND** le verrou écrit liste chaque jar avec sa taille et son SHA-256 recalculés, et garde l'entrée forcée inchangée

#### Scenario: Module forcé non résolu à sa version
- **GIVEN** un faux Coursier qui produit `guava-33.6.0-jre.jar` alors que le verrou force `33.7.2-jre`
- **WHEN** la régénération est exécutée
- **THEN** elle échoue en nommant le module forcé
- **AND** le verrou existant est inchangé, octet pour octet

#### Scenario: Construction sans régénération sur une résolution divergente
- **GIVEN** un faux Coursier dont un jar diffère du verrou
- **WHEN** la construction est exécutée sans `--update-classpath-lock`
- **THEN** elle échoue en nommant le jar et en indiquant l'option de régénération
