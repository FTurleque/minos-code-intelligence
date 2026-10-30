# Design

## Context

Sprint 5 de l'audit du 10 octobre 2026 : **AUD-DEP-04**. HEAD analysé : `2d1cc214` (`develop`). Le plugin est en Gradle 9.8.1 par le wrapper, Java 21, IntelliJ Platform 2026.1, hors du reactor Maven.

## État vérifié au HEAD

### AUD-DEP-04 — confirmé

- `git ls-files` ne contient ni `gradle.lockfile` ni `verification-metadata.xml` ; `minos-intellij/gradle/` ne contient que `wrapper/`.
- `minos-intellij/build.gradle.kts:6`, `:10-11`, `:25-27` : versions directes (exact). `:30` : `intellijIdea("2026.1")` ; `:47-56` : `pluginVerification` avec `select { untilBuild = "261.*" }`.
- `.github/workflows/code-audit.yml:228` : « Dependency-Check n'y est pas encore » (l'audit citait `:286-288` ; le fichier fait 263 lignes).
- `pr-ci.yml:21-27` : OSV-Scanner en `--recursive ./`, sans `osv-scanner.toml` : un `gradle.lockfile` versionné serait lu **sans modifier le workflow** (à prouver, tâche 2.3).
- `intellij-plugin.yml:135` : le job `windows-ownership` lance `gradlew.bat test`.

**Non mesuré** : aucun Gradle exécuté pendant l'analyse. Le comportement de `lockAllConfigurations()` et de la vérification de sommes avec l'IntelliJ Platform Gradle Plugin est un risque **raisonné**, pas observé ; celui de Dependabot devant `verification-metadata.xml` non plus.

### Écarts avec l'action proposée par l'audit

1. **`lockAllConfigurations()` verrouillerait aussi des artefacts d'IDE.** `intellijIdea("2026.1")` et la sélection d'IDE du vérificateur (`untilBuild = "261.*"`) résolvent des distributions d'IntelliJ qui varient **selon le système** et **dans le temps** (nouvelles versions correctives). Un verrou global serait instable entre Linux et Windows et périmé à chaque version corrective. Le verrou vise les configurations qui produisent le zip et son build, pas la sélection d'IDE (D1).
2. **Un verrou incluant les artefacts d'IDE ajouterait du bruit au check exigé `osv-scan`** (`fail-on-vuln: true`) : les bibliothèques embarquées dans une distribution IntelliJ ne sont pas livrées par MINOS.
3. **`verification-metadata.xml` et Dependabot.** Dependabot met à jour les versions et, en général, le verrou ; rien n'indique qu'il régénère les métadonnées de vérification. Ses PR Gradle resteraient rouges jusqu'à régénération manuelle. C'est acceptable si c'est **voulu et écrit** ; sinon la vérification de sommes se limite (D2).
4. **Deux systèmes.** Les métadonnées de vérification doivent contenir les variantes résolues sous Linux **et** sous Windows, sinon le job Windows échoue.

## Goals / Non-Goals

**Goals** : les dépendances livrées dans le zip du plugin et celles de son build sont figées, visibles d'OSV et vérifiées ; une dérive échoue en CI ; la procédure de montée est connue.

**Non-Goals** : verrouiller les distributions d'IDE utilisées pour tester ; couvrir Dependency-Check ; changer de versions.

## Decisions

### D1. Verrou par configurations nommées

`dependencyLocking` activé pour les configurations de compilation et d'exécution du plugin, ses tests, et le classpath du script de build (plugins Gradle), en mode strict. Les configurations qui résolvent une distribution d'IDE sont **exclues nommément**, avec un commentaire qui en donne la raison ; la liste exacte vient de la mesure de la tâche 1.1 (`gradlew dependencies` / résolution réelle), pas d'une supposition.

Alternative écartée : `lockAllConfigurations()` (écart 1).

### D2. Vérification de sommes — **option C retenue le 2026-10-15** (A recommandée « si les artefacts d'IDE sont stables » : la mesure 1.1 montre qu'ils ne le sont pas)

- **Option A (recommandée) : vérification SHA-256 de tout ce qui est résolu**, métadonnées générées sous Linux et complétées sous Windows, artefacts d'IDE compris s'ils sont stables pour une version donnée d'IntelliJ Platform. Conséquence assumée : une PR Dependabot Gradle est rouge tant que les métadonnées ne sont pas régénérées (procédure écrite, D4).
- **Option B : verrou seul.** OSV voit les dépendances ; l'intégrité repose sur HTTPS et les dépôts. Plus simple, PR Dependabot vertes ; la limite est déclarée.
- **Option C : vérification limitée** aux composants hors distribution d'IDE (composants de confiance déclarés pour les groupes JetBrains). Intermédiaire, à retenir si la mesure montre que les artefacts d'IDE rendent A instable.

Le choix se fait **après** la mesure de la tâche 1.1.

### D3. Un gate de présence et de fraîcheur

- Statique, dans `invariants` : `minos-intellij/gradle.lockfile` existe, `build.gradle.kts` active le verrou strict, et, selon D2, `gradle/verification-metadata.xml` existe et déclare la vérification SHA-256. Porté par `scripts/ci/plugin-gate.py` (qui connaît déjà la portée du plugin) ou `check-toolchain-baseline.py` ; auto-test sur arbres temporaires.
- Dynamique, dans `intellij-plugin.yml` : le build en mode strict échoue de lui-même si une dépendance résolue diffère du verrou.

### D4. Procédure de montée écrite

`docs/developer/` et le skill `minos-dependency-update` décrivent : régénérer le verrou (`--write-locks`), régénérer les métadonnées sous Linux puis Windows (`--write-verification-metadata sha256`), relire le diff (une somme qui change **sans** changement de version est un signal d'alerte, pas une formalité), rejouer la qualification du plugin de `docs/TOOLCHAIN_POLICY.md`.

## Mesures de la tâche 1.1 (2026-10-15)

- **Configurations** : `resolvableConfigurations` liste une trentaine de configurations `intellijPlatform*` à côté des quatre classpaths Java. `compileClasspath` et `testCompileClasspath` atteignent la coordonnée locale `idea:idea:2026.1` (l'IDE extrait) ; `runtimeClasspath` ne livre que gson et error_prone_annotations. Verrou : les quatre classpaths Java, `idea:*` ignoré (`ignoredDependencies`), et le classpath du script de build ; 13 coordonnées livrées ou de test, 77 de build.
- **Dérive** : une dépendance hors verrou fait échouer le build (« Resolved 'org.apache.commons:commons-lang3:3.18.0' which is not part of the dependency lock state ») ; une version **déclarée** différente du verrou est en revanche ramenée silencieusement à la version verrouillée (`2.13.2 -> 2.14.0`, contrainte `strictly`) : d'où la règle 11 du gate.
- **Sommes** : générées sous Windows (`test buildPlugin verifyPluginProjectConfiguration verifyPluginStructure verifyPlugin spotbugsMain pitest`), complétées avec `--refresh-dependencies` (six métadonnées de greffons manquaient), puis régénérées sous Linux (WSL Ubuntu, JDK 21) : **identiques**, 209 composants. Les composants tirés de l'IDE changent de version au fil des builds d'IDE choisis à l'exécution (`idea:idea` 2026.1 et 2026.1.5, `localIde` IU-261.22158.277, `com.jetbrains.intellij.java` 261.22158.277) : option C, confiance limitée à ces groupes. Une somme altérée (gson) fait échouer le build.
- **OSV** : interrogation de l'API OSV (pas d'OSV-Scanner sur le poste) sur les 90 coordonnées verrouillées : aucun avis sur `gradle.lockfile` ; quatre composants du classpath de build en portent (jackson-databind et jackson-core 2.21.4, undertow-core 2.4.3.Final, jsoup 1.22.2) : `buildscript-gradle.lockfile` exclu nommément du scan exigé.

## Direction des dépendances

Le plugin reste hors reactor et sans dépendance `com.minos:*` ; aucune règle d'architecture n'est touchée.

## Qualification des capacités

- Analyse OSV des dépendances du plugin : **non supportée** au HEAD ; qualifiée par la tâche 2.3 (preuve sur un verrou volontairement vulnérable).
- Intégrité des artefacts résolus : **non supportée** au HEAD ; qualifiée, partielle ou déclarée absente selon D2.

## Windows et Linux

Le verrou et les métadonnées sont générés et vérifiés sur les deux systèmes ; le job `windows-ownership` en est le témoin côté Windows. Le poste de développement n'a pas toujours les dépendances en cache (`--offline` ne suffit pas) : la génération se fait avec réseau.

## Risks / Trade-offs

- Métadonnées de vérification volumineuses et verbeuses : diffs lourds à relire à chaque montée.
- PR Dependabot rouges par construction (option A) : coût récurrent, accepté ou non par le propriétaire.
- Bruit OSV si une configuration d'IDE entre par erreur dans le verrou : le gate D3 peut refuser un verrou qui contient des coordonnées de distribution d'IDE.
- Un verrou strict casse le build au moindre changement de résolution : c'est le but ; la procédure D4 doit être facile à trouver.
