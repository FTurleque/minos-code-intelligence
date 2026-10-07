# Design

## Contexte

Maven 3.10 (wrapper, plage Enforcer `[3.9,4.0)`), JDK 24, JUnit Jupiter 6.1.3, 14 modules sous un POM parent à `${revision}`. `minos-app` écrit dans le `target/` de la racine. Surefire fixe `workingDirectory` à la racine du dépôt. `pr-ci.yml` est le seul workflow à lancer `verify` (gate `check-single-execution.py`).

## Décisions

### D1. Deux profils opt-in, versions dans `pluginManagement`

La version et la configuration communes sont dans `pluginManagement` ; un profil ajoute le plugin à `<build><plugins>`. Rejeté : déclarer les plugins dans le build par défaut avec `skip=true` (une régression de configuration les activerait en silence) ; ne rien mettre dans `pluginManagement` (un appel direct `spotbugs:check` perdrait la configuration).

### D2. SpotBugs : rapport lié à `verify`, `check` jamais lié

Le but `spotbugs` (qui ne fait jamais échouer) est lié à `verify` dans le profil ; `spotbugs:check` est lancé explicitement. Raison : le contrôle est rouge aujourd'hui (197 alertes `Medium`) et ne doit pas casser un `verify -Paudit-spotbugs`. Effort `Max` et seuil `Medium` : profondeur maximale, alertes de confiance moyenne ou haute, sans le bruit de `Low`. Valeurs surchargeables par `-Dspotbugs.*`.

### D3. PIT : aucune liaison de phase, périmètre par propriétés

Le profil `audit-mutation` ne lie rien : PIT est trop coûteux pour une phase. Le périmètre est dans les propriétés `targetClasses` et `targetTests` du profil, que `-D` surcharge sans toucher au POM. Un périmètre explicite remplace le défaut de PIT (tout `com.minos.*`).

### D4. `-pl` avec `-am` impose `-DfailWhenNoMutations=false`

Le POM parent utilise `${revision}` ; sans `-am`, Maven ne résout pas les modules amont (constaté). Avec `-am`, PIT s'exécute aussi dans `minos-domain`, où il ne trouve aucune mutation et échoue par défaut. Désactiver ce garde-fou masquerait une analyse vide dans le module visé ; `audit-report-summary.py pit` le rétablit en échouant si le rapport manque, est vide ou ne contient aucun mutant couvert. Rejeté : un second profil activé par fichier (obsolète dès qu'on change `targetClasses`), un script d'enveloppe par OS, `mvn install` préalable (le POM installé garderait `${revision}`).

### D5. `-Duser.dir` pour les JVM de PIT

`pitest-maven` n'a pas de paramètre `workingDirectory`. `HostedProductionBoundaryTest` lit `minos-engine/src/main/java/…` en chemin relatif et échoue sans correctif (constaté, PIT refuse alors de muter). Le contournement `-Duser.dir=${maven.multiModuleProjectDirectory}` reproduit le répertoire de travail de Surefire. Rejeté : exclure ce test (il perdrait un test qui contrôle le code visé) ; modifier le test (hors périmètre « intégration des outils »). La solution propre est consignée comme constat.

### D6. Versions

`spotbugs-maven-plugin` 4.10.4.1 / SpotBugs 4.10.4, `pitest-maven` 1.30.0, `pitest-junit5-plugin` 1.2.3 : dernières versions stables de Maven Central au 7 octobre 2026. `pitest-junit5-plugin` est construit contre JUnit 5.9 : sa compatibilité avec JUnit 6.1.3 n'est pas déclarée, elle est constatée sur le périmètre initial (78 tests exécutés, 58 mutants tués).

### D7. Aucun seuil, aucune exclusion

Pas de `mutationThreshold`, `coverageThreshold` ni `testStrengthThreshold`, et un filtre SpotBugs vide, avant d'avoir mesuré. Les règles d'exclusion (une classe, un motif, motivée, datée) sont écrites dans le fichier et dans la documentation.

### D8. Workflow manuel

`code-audit.yml` est `workflow_dispatch` seul, hors du champ de `check-single-execution.py`. Il n'invoque pas `verify`. Les paramètres saisis passent par `env:` (gate de supply chain), jamais interpolés dans `run:`.

## Qualification des capacités

| Capacité | Qualification |
|---|---|
| SpotBugs sur les 14 modules, rapports XML/SARIF/HTML, `spotbugs:check` | **qualifiée** sur Windows (exécutée) |
| PIT sur `HostedAuditChain`, `HostedAuthorizationService`, `HostedPermission` | **qualifiée** sur Windows (exécutée, 73 mutants) |
| PIT sur un autre module ou avec des tests d'un autre module | **non qualifiée** (`crossModule=false`, non exercée) |
| Commandes bash et workflow GitHub Actions | **non qualifiées** (non exécutées) |
| Contrôle bloquant en CI | **non supportée** tant que les 197 alertes ne sont pas traitées |

## Plateformes

Mesures et commandes exécutées sous Windows. Sous PowerShell, les arguments `-D` sont quotés (virgules et points). Aucun comportement propre à une plateforme dans les profils ; `-Duser.dir` est un chemin Maven valable sur les deux.
