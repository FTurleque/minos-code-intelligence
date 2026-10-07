# Audit de code : SpotBugs et PIT

MINOS dispose de deux outils d'audit **à la demande**, hors du build habituel : SpotBugs (analyse statique du bytecode) et PIT (tests de mutation). Aucun des deux ne tourne dans `clean verify` ni dans `pr-ci.yml`, et aucun n'est un contrôle de fusion. Ils servent à repérer des défauts probables et des tests qui n'attrapent rien, puis à préparer des corrections par OpenSpec.

Mesures et constats de la première exécution : [code-audit-constats.md](code-audit-constats.md). Changement OpenSpec : `openspec/changes/ajouter-audit-spotbugs-pitest/`.

## Rôle respectif des deux outils

| | SpotBugs | PIT (PITest) |
|---|---|---|
| Question posée | « Ce code contient-il un motif de bug connu ? » | « Si je casse ce code, les tests s'en aperçoivent-ils ? » |
| Méthode | analyse statique du bytecode compilé, sans exécuter le code | mutation du bytecode (inversion d'une condition, suppression d'un appel, valeur de retour modifiée…), puis exécution des tests contre chaque mutant |
| Détecte | exposition de représentation interne, déréférencement possible de `null`, ressources non fermées, synchronisation incohérente… | assertions absentes ou trop faibles, branches jamais vérifiées, code couvert mais non contrôlé |
| Ne détecte pas | un défaut de logique métier sans motif connu | un bug dans du code que les tests n'exécutent pas : PIT ne juge que ce que les tests couvrent |
| Coût | de l'ordre de la compilation | un cycle de tests par mutant : à réserver à un périmètre restreint |

Ils sont complémentaires : SpotBugs regarde le code, PIT regarde les tests.

## Prérequis et versions retenues

| Élément | Version | Rôle | Vérifié |
|---|---|---|---|
| JDK | 24 (imposé par Maven Enforcer `[24,25)`) | compile le bytecode analysé (classes version 68) | exécuté : SpotBugs et PIT lisent les classes Java 24 du dépôt |
| Maven | wrapper du dépôt (3.10.0, plage Enforcer `[3.9,4.0)`) | `mvnw` / `mvnw.cmd` fait foi | exécuté |
| `com.github.spotbugs:spotbugs-maven-plugin` | **4.10.4.1** | intégration Maven | exécuté |
| `com.github.spotbugs:spotbugs` | **4.10.4** | moteur d'analyse, épinglé dans le plugin | exécuté (la version figure dans le rapport : `BugCollection version='4.10.4'`) |
| `org.pitest:pitest-maven` | **1.30.0** | intégration Maven et moteur de mutation | exécuté |
| `org.pitest:pitest-junit5-plugin` | **1.2.3** | lance les tests JUnit Platform sous PIT | exécuté (voir ci-dessous) |

Les versions sont des propriétés du `pom.xml` parent (`spotbugs.maven.plugin.version`, `spotbugs.version`, `pitest.maven.plugin.version`, `pitest.junit5.plugin.version`) ; aucune n'est `LATEST` ni une plage. Ce sont les dernières versions publiées sur Maven Central à la date de l'intégration (7 octobre 2026).

**Compatibilité JUnit.** Le dépôt utilise JUnit Jupiter 6.1.3. Le POM de `pitest-junit5-plugin` 1.2.3 (publié en mai 2025, c'est la dernière version) est construit contre JUnit 5.9 et la plateforme 1.9 ; je n'ai pas trouvé de déclaration de compatibilité avec JUnit 6. La compatibilité n'est donc **pas** garantie : elle est **constatée**. Sur le périmètre initial, PIT a retenu 78 tests de `com.minos.hosted` (ceux qui exercent les classes visées), les a exécutés sans échec en l'absence de mutation (condition imposée par PIT) et a tué 58 mutants. Si une autre classe visée met en échec le lanceur, le premier suspect est cette version.

## Modules, classes et exclusions

**SpotBugs** analyse le bytecode de production (`target/classes`, tests exclus : `includeTests=false`) de **tous les modules Java du reactor Maven** : `minos-domain`, `minos-engine`, `minos-runtime-local`, `minos-storage-local`, `minos-provider-scip`, `minos-integration-git`, `minos-application`, `minos-storage-postgresql`, `minos-bootstrap`, `minos-nexus`, `minos-cli`, `minos-api`, `minos-mcp`, `minos-app`. Hors périmètre : `minos-intellij` (Gradle, Java 21, hors reactor) et `fixtures/`.

**PIT** ne mute que `minos-engine`, classes `com.minos.hosted.HostedAuditChain`, `HostedAuthorizationService` et `HostedPermission` (autorisations et chaîne d'audit du plan de contrôle hébergé), avec les tests du paquet `com.minos.hosted.*` du même module (18 classes de test et une classe de support). Ce périmètre est un point de départ : il se change sans modifier de POM (voir plus bas).

**Exclusions.** Aucune. `quality/spotbugs-exclude.xml` est un filtre SpotBugs vide, branché dans la configuration, qui sert de point d'accueil et rappelle la règle ci-dessous. PIT n'exclut rien non plus, hors le comportement par défaut de l'outil.

## Profils Maven et effet exact

| Profil | Activé par défaut | Effet |
|---|---|---|
| *(aucun)* | — | `clean verify` ne charge ni SpotBugs ni PIT. Les deux plugins n'existent que dans `pluginManagement` (version et configuration partagées) ; ils ne sont dans aucun cycle de vie. Vérifié sur le POM effectif de `minos-engine` sans profil, avec chacun des deux profils |
| `audit-spotbugs` | non | déclare `spotbugs-maven-plugin` dans le build et lie le but `spotbugs` (rapport, ne fait jamais échouer) à la phase `verify`. Les réglages `effort=Max`, `threshold=Medium`, `failThreshold=Medium` sont des propriétés du POM parent (valables aussi sans profil pour un appel direct de `spotbugs:check`). Le contrôle bloquant `spotbugs:check` n'est **jamais** lié : il se lance explicitement |
| `audit-mutation` | non | déclare `pitest-maven` dans le build, **sans liaison à une phase** : PIT ne s'exécute que par `pitest-maven:mutationCoverage`. Fixe le périmètre initial (propriétés `targetClasses`, `targetTests`) ; les ressources et délais sont des propriétés `pit.*` du POM parent |

Paramètres communs (`pluginManagement`, `pom.xml`) :

- SpotBugs : rapports `spotbugsXml.xml`, `spotbugsSarif.json` (SARIF, lisible par les outils de revue) et HTML ; filtre `quality/spotbugs-exclude.xml` ; tas 1 024 Mo ; délai 30 min.
- PIT : rapports HTML et XML ; `timestampedReports=false` (chemin stable, écrasé à chaque exécution) ; mutateurs `DEFAULTS` ; 2 fils ; tas 1 024 Mo par minion ; `timeoutConstant=8000` ms et `timeoutFactor=1.5` ; `-Duser.dir` à la racine du dépôt (voir « Limites »).

## Commandes

Toutes se lancent depuis la racine du dépôt. Tout `-pl` est accompagné de `-am` : le POM parent utilise `${revision}`, et sans `-am` Maven ne résout pas les modules amont (exécuté : `Could not collect dependencies … minos-parent:pom:${revision}`).

Sous PowerShell, **quotez chaque argument `-D`** : une virgule non quotée y devient un tableau, et les points sont mal gérés par PowerShell 5.1.

### SpotBugs : rapport

Tout le reactor :

```powershell
.\mvnw.cmd -B -ntp -Paudit-spotbugs "-DskipTests" compile spotbugs:spotbugs
```

```bash
./mvnw -B -ntp -Paudit-spotbugs -DskipTests compile spotbugs:spotbugs
```

Un module et ses dépendances (ici `minos-engine`) :

```powershell
.\mvnw.cmd -B -ntp -Paudit-spotbugs -pl minos-engine -am "-DskipTests" compile spotbugs:spotbugs
```

```bash
./mvnw -B -ntp -Paudit-spotbugs -pl minos-engine -am -DskipTests compile spotbugs:spotbugs
```

Synthèse par module et par motif :

```bash
python scripts/quality/audit-report-summary.py spotbugs
```

Le profil lie aussi le rapport à `verify` : `./mvnw -B -ntp -Paudit-spotbugs verify` produit les rapports après les tests (compte tenu du coût de `verify`, la commande ci-dessus est préférable).

### SpotBugs : contrôle bloquant

```powershell
.\mvnw.cmd -B -ntp -fae -Paudit-spotbugs "-DskipTests" compile spotbugs:check
```

```bash
./mvnw -B -ntp -fae -Paudit-spotbugs -DskipTests compile spotbugs:check
```

Le build **échoue** s'il reste au moins une alerte de gravité `Medium` ou plus (`failThreshold=Medium`, `maxAllowedViolations=0` par défaut). `-fae` laisse chaque module aller au bout pour lister tous ceux qui échouent. Réglages sans toucher au POM : `"-Dspotbugs.failThreshold=High"` (seules les alertes `High` bloquent), `"-Dspotbugs.threshold=Low"` (rapporte aussi les alertes de faible confiance), `"-Dspotbugs.effort=Default"` (analyse moins profonde, plus rapide), `"-Dspotbugs.maxAllowedViolations=50"` (tolère un nombre d'alertes par module).

**Le contrôle bloquant est rouge aujourd'hui** sur 12 modules sur 14 (197 alertes `Medium`) : il ne peut devenir un contrôle de fusion qu'après une décision sur ces alertes (corriger, ou fixer une base). Avec `-Dspotbugs.failThreshold=High`, il est vert : aucune alerte `High`.

### PIT : périmètre initial

```powershell
.\mvnw.cmd -B -ntp -Paudit-mutation -pl minos-engine -am "-DfailWhenNoMutations=false" test-compile org.pitest:pitest-maven:mutationCoverage
python scripts/quality/audit-report-summary.py pit --module minos-engine
```

```bash
./mvnw -B -ntp -Paudit-mutation -pl minos-engine -am -DfailWhenNoMutations=false test-compile org.pitest:pitest-maven:mutationCoverage \
  && python scripts/quality/audit-report-summary.py pit --module minos-engine
```

Pourquoi `-DfailWhenNoMutations=false` : `-am` applique le but PIT aux modules amont (ici `minos-domain`), qui ne contiennent aucune des classes visées, et PIT échoue par défaut quand il ne trouve aucune mutation. Désactiver ce garde-fou rendrait invisible une analyse vide dans le module visé ; c'est pourquoi la commande enchaîne `audit-report-summary.py pit`, qui **échoue** (code 1) si le rapport manque, ne contient aucune mutation, ou si aucun mutant n'est couvert par un test. Une exécution PIT sans cette seconde commande n'est pas une preuve.

Changer les classes ou les tests, sans modifier de POM :

```powershell
.\mvnw.cmd -B -ntp -Paudit-mutation -pl minos-engine -am "-DfailWhenNoMutations=false" "-DtargetClasses=com.minos.hosted.HostedTenantService,com.minos.hosted.HostedWorkspaceService" "-DtargetTests=com.minos.hosted.*" test-compile org.pitest:pitest-maven:mutationCoverage
```

```bash
./mvnw -B -ntp -Paudit-mutation -pl minos-engine -am -DfailWhenNoMutations=false \
  -DtargetClasses=com.minos.hosted.HostedTenantService,com.minos.hosted.HostedWorkspaceService \
  -DtargetTests='com.minos.hosted.*' test-compile org.pitest:pitest-maven:mutationCoverage
```

`targetClasses` et `targetTests` sont des listes de noms de classes ou de motifs (`*`) séparés par des virgules. Pour muter un autre module, remplacez `-pl minos-engine` par ce module et `--module` par le même nom. Autres réglages : `"-Dpit.threads=4"`, `"-Dpit.timeoutConstant=15000"`, `"-Dpit.timeoutFactor=2"`, `"-Dpit.maxHeapMb=2048"`, `"-Dpit.mutators=STRONGER"` (jeu de mutateurs plus large, plus lent).

### Déclenchement manuel dans GitHub Actions

`.github/workflows/code-audit.yml` (`workflow_dispatch` uniquement) lance SpotBugs (rapport ou `spotbugs:check`) et/ou PIT, exécute la synthèse et publie les rapports en artefacts. Aucun déclenchement automatique. **Ce workflow n'a pas été exécuté** : son contenu a passé les contrôles statiques du dépôt (immutabilité des actions, exécution unique) mais pas un run GitHub.

## Chemins des rapports

| Outil | Module | Fichiers |
|---|---|---|
| SpotBugs | chaque module `minos-*` | `<module>/target/spotbugsXml.xml` (exploitable), `<module>/target/spotbugsSarif.json` (SARIF), `<module>/target/reports/spotbugs.html` (lecture humaine) |
| SpotBugs | `minos-app` | **dans le `target/` de la racine** (`target/spotbugsXml.xml`…), car son POM fixe `<directory>` à `${maven.multiModuleProjectDirectory}/target`. `clean` supprime ce répertoire |
| PIT | `minos-engine` | `minos-engine/target/pit-reports/index.html` (lecture humaine, un fichier par classe sous `com.minos.hosted/`), `minos-engine/target/pit-reports/mutations.xml` (exploitable) |

## Interpréter SpotBugs

Chaque alerte porte un **motif** (`type`, par exemple `EI_EXPOSE_REP`), une **priorité** (1 `High`, 2 `Medium`, 3 `Low`), une classe, une méthode et une ligne. Le rapport HTML groupe par catégorie et explique chaque motif ; le XML contient aussi une empreinte stable de l'alerte (`instanceHash`).

- Lisez d'abord les alertes `High`, puis le motif le plus fréquent : un motif répété 100 fois dit plus sur une convention du code (par exemple des enregistrements qui exposent leurs listes) que sur 100 défauts distincts.
- Une alerte n'est pas un bug : c'est un motif qui en cause souvent. Décidez au cas par cas : **corriger**, **consigner** (constat OpenSpec), ou **exclure** si c'est un faux positif.
- Le niveau `Medium` retenu rapporte les alertes de confiance moyenne et haute ; `Low` est volontairement écarté.

## Interpréter PIT

PIT génère des mutants et classe chacun :

| Statut | Signification | Que faire |
|---|---|---|
| `KILLED` | au moins un test a échoué sur le mutant : le comportement est contrôlé | rien |
| `SURVIVED` | le code a été exécuté par un test, mais **aucun test n'a échoué** : le comportement modifié n'est pas contrôlé | écrire ou renforcer une assertion, **ou** conclure à un mutant équivalent (le changement ne modifie pas le comportement observable) |
| `NO_COVERAGE` | aucun test n'exécute cette ligne | ajouter un test ; ou le code est mort |
| `TIMED_OUT` | le mutant a provoqué une boucle ou une attente infinie, détectée par le délai | compté comme tué par PIT : un test a bien « vu » une différence. Si les timeouts sont nombreux sans raison, allongez `pit.timeoutConstant` |
| `MEMORY_ERROR`, `RUN_ERROR` | le mutant a fait échouer la JVM de test | compté comme tué ; à ignorer sauf s'ils dominent |

Indicateurs du rapport :

- **Mutation score** : mutants tués / mutants générés.
- **Test strength** : mutants tués / mutants **couverts** (hors `NO_COVERAGE`). Il mesure la qualité des assertions là où les tests passent.
- **Line coverage (mutated classes only)** : couverture de lignes des seules classes mutées.

**Couverture et détection de mutation ne sont pas la même mesure.** JaCoCo (les gates `check-jacoco.py`) compte les lignes et branches *exécutées* par les tests. Un test qui appelle une méthode sans rien vérifier la couvre à 100 % et laisse survivre tous ses mutants. Une couverture élevée avec beaucoup de survivants signale des tests qui exécutent sans contrôler ; l'inverse (couverture basse, peu de survivants sur ce qui est couvert) signale un code peu testé mais bien vérifié. Ne remplacez pas l'une par l'autre.

Sur le périmètre initial, 93 % des lignes des classes mutées sont couvertes, mais 12 mutants survivent : la couverture ne dit pas quelles vérifications manquent, PIT oui.

## Faux positifs et exclusions

Une alerte ne s'exclut que si elle est démontrée fausse. Règles :

1. Une exclusion vise **une classe**, si possible **une méthode**, pour **un motif** (`<Class>`, `<Method>`, `<Bug pattern=…/>`). Pas d'exclusion par motif seul sur tout le dépôt, pas de joker sur les paquets.
2. Un commentaire au-dessus dit pourquoi l'alerte est fausse, qui l'a constaté et à quelle date.
3. Une alerte *réelle* mais acceptée (coût de correction, décision d'architecture) ne s'exclut pas : elle se consigne comme constat ou se couvre par un ADR.
4. L'exclusion se fait dans `quality/spotbugs-exclude.xml`, relue en PR. Un motif réglé globalement pour faire passer le contrôle est un refus de relecture.
5. Côté PIT, un mutant équivalent se documente dans le constat qui le concerne. `excludedClasses`, `excludedMethods` et `excludedTestClasses` ne servent qu'à des cas précis du même ordre (voir « Limites » pour la seule exclusion pressentie).

## Coût d'exécution observé

Mesures sur un poste Windows 10, JDK 24.0.1, Maven 3.10.0, cache Maven chaud, une exécution chacune (ordre de grandeur, pas une garantie) :

| Opération | Durée |
|---|---|
| SpotBugs, tout le reactor (compilation comprise), 14 modules | environ 2 min 20 s |
| SpotBugs, `minos-engine` avec `minos-domain` | environ 35 s |
| PIT, périmètre initial (73 mutants, 889 exécutions de test) | 56 s pour PIT, 57 s pour le build Maven |

Le coût de PIT croît à peu près avec le nombre de mutants multiplié par le nombre de tests qui couvrent chacun. Cinq cents mutants avec des tests plus lourds se compteront en dizaines de minutes : élargissez le périmètre par paliers, en mesurant.

## Limites connues

- **Tests d'un autre module.** PIT n'exécute que les tests du module analysé : ceux de son `target/test-classes` et de son classpath de test (`crossModule` vaut `false` par défaut). Une classe de `minos-engine` couverte seulement par des tests de `minos-storage-local`, `minos-application` ou `minos-app` apparaîtra `NO_COVERAGE` ou `SURVIVED` à tort. C'est un comportement documenté de l'outil ; il n'a pas été exercé ici sur un cas réel, le périmètre initial ayant ses tests dans le même module. Avant de conclure à un test manquant, cherchez la classe dans les autres modules (`grep -rl NomDeClasse */src/test`).
- **Répertoire de travail.** Surefire est configuré avec `workingDirectory` à la racine du dépôt, car des tests lisent des chemins `minos-engine/src/…`. `pitest-maven` n'a pas ce paramètre (`Parameter 'workingDirectory' is unknown`, constaté) ; sans correctif, `HostedProductionBoundaryTest` échoue hors mutation et PIT refuse de continuer (constaté). La configuration passe donc `-Duser.dir=<racine>` aux JVM de PIT. C'est un contournement : un test qui dépend d'un autre répertoire de travail y serait sensible. La solution propre serait un test qui localise le dépôt sans dépendre du répertoire courant.
- **Mutants équivalents.** Certains survivants ne sont pas des défauts de test (le changement ne modifie pas le comportement observable, par exemple une vérification redondante avec une autre). PIT ne les distingue pas.
- **Aucun seuil.** `mutationThreshold`, `coverageThreshold` et `testStrengthThreshold` valent 0 : aucun seuil n'est imposé avant de connaître le premier périmètre.
- **SpotBugs sans greffons.** Ni `find-sec-bugs` ni `fb-contrib` : le jeu de règles est celui de SpotBugs seul.
- **Linux/macOS et GitHub Actions.** Toutes les mesures viennent de Windows. Les commandes bash sont l'équivalent exact des commandes PowerShell exécutées mais n'ont pas été lancées sous Linux ni macOS ; le workflow `code-audit.yml` n'a pas tourné.
- **`total_classes` à 0.** Le résumé du XML SpotBugs 4.10.4 affiche `total_classes="0"` bien que les alertes soient correctement rapportées ; ne l'utilisez pas pour compter les classes analysées.

## Préparer des corrections avec OpenSpec

Les rapports alimentent le workflow `openspec/` du dépôt ; ils ne le remplacent pas.

1. **Choisir un lot cohérent**, pas une alerte isolée : un motif dans un module (par exemple `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` dans `minos-storage-local`), ou les survivants d'une classe critique (par exemple `HostedAuditChain.verify`).
2. **Qualifier** : défaut confirmé, faux positif, mutant équivalent ou décision à prendre. Pour chaque alerte, citez le motif, la classe, la méthode, la ligne et l'`instanceHash` (SpotBugs), ou la classe, la méthode, la ligne et le mutateur (PIT).
3. **Ouvrir un changement** : `openspec new change <nom-en-français>`. La proposition liste les constats (tableau constat / qualification / traitement), les modules touchés et ce qui est hors périmètre ; la spec exprime l'exigence testable (« la vérification de la chaîne d'audit refuse une séquence non contiguë ») ; les tâches nomment les scripts `check-*.py` et les scopes JaCoCo à rejouer.
4. **Écrire d'abord le test qui tue le mutant** ou qui échoue sur le défaut, puis corriger. Rejouer ensuite l'outil sur le même périmètre : un survivant doit passer à `KILLED`, une alerte doit disparaître du rapport.
5. **Mesurer avant d'imposer un seuil** : un seuil PIT ou un `spotbugs:check` bloquant ne se décide qu'avec une base chiffrée et une décision explicite (ADR si le choix est structurant).
