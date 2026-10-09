# Audit de code outillé : SpotBugs, PIT, ArchUnit, Dependency-Check et Gitleaks

MINOS dispose de cinq outils d'audit. Quatre sont **à la demande**, hors du build habituel : SpotBugs (analyse statique du bytecode), PIT (tests de mutation), OWASP Dependency-Check (vulnérabilités connues des dépendances) et Gitleaks (secrets dans les fichiers et l'historique Git). Le cinquième, ArchUnit, est une bibliothèque de test : ses règles établies tournent dans `clean verify` avec les autres tests de `minos-app`. Aucun des quatre premiers n'est un contrôle de fusion.

- Couverture mesurée, registre des exécutions, état de référence et point de reprise : [code-audit-couverture.md](code-audit-couverture.md).
- Constats qualifiés et plan de correction : [code-audit-constats.md](code-audit-constats.md).
- Changements OpenSpec : `openspec/changes/ajouter-audit-spotbugs-pitest/` (intégration initiale) et les changements cités dans les constats.

## Rôle respectif des outils

| | SpotBugs | PIT (PITest) | ArchUnit | Dependency-Check | Gitleaks |
|---|---|---|---|---|---|
| Question posée | « Ce code contient-il un motif de bug connu ? » | « Si je casse ce code, les tests s'en aperçoivent-ils ? » | « Les classes compilées respectent-elles les frontières décidées ? » | « Une dépendance a-t-elle une vulnérabilité publiée ? » | « Un secret a-t-il été écrit dans un fichier ou un commit ? » |
| Méthode | analyse statique du bytecode | mutation du bytecode, puis exécution des tests contre chaque mutant | import du bytecode de chaque module, règles exécutées par JUnit | identification des artefacts (CPE, purl), confrontation aux flux NVD, CISA KEV, RetireJS | expressions régulières et entropie sur les fichiers et les patchs Git |
| Ne détecte pas | un défaut de logique sans motif connu | un bug dans du code non exécuté par les tests | une règle non écrite ; un usage dynamique (réflexion, `ServiceLoader`) | une vulnérabilité non publiée ; une dépendance hors Maven (plugin Gradle, image Docker, outil embarqué) | un secret au format inconnu ; ce qui n'est plus dans aucune référence Git accessible |
| Coût | ≈ 2 min pour le réacteur | de quelques secondes à plusieurs heures selon le périmètre | quelques secondes | ≈ 15 s par analyse, base NVD à jour | quelques secondes |

## Prérequis et versions retenues

| Élément | Version | Rôle | Vérifié |
|---|---|---|---|
| JDK | 24 (Maven Enforcer `[24,25)`) | compile le bytecode analysé (classes version 68) | exécuté |
| Maven | wrapper du dépôt (3.10.0, plage Enforcer `[3.9,4.0)`) | `mvnw` / `mvnw.cmd` fait foi | exécuté |
| `com.github.spotbugs:spotbugs-maven-plugin` | **4.10.4.1** | intégration Maven | exécuté |
| `com.github.spotbugs:spotbugs` | **4.10.4** | moteur, épinglé dans le plugin ; aussi lancé en ligne de commande (`edu.umd.cs.findbugs.FindBugs2`) | exécuté |
| `org.pitest:pitest-maven` | **1.30.0** | intégration Maven et moteur de mutation | exécuté |
| `org.pitest:pitest-junit5-plugin` | **1.2.3** | lance les tests JUnit Platform sous PIT | exécuté (compatibilité JUnit 6 constatée, pas garantie, voir plus bas) |
| `com.tngtech.archunit:archunit` | **1.5.1** | bibliothèque de test de `minos-app` (même version que les tests d'architecture de MORPHEUS) | exécuté |
| `org.owasp:dependency-check-maven` | **13.0.0** (analyse) et **12.2.2** (mise à jour anonyme de la base, voir « Base NVD ») | profils `audit-dependency-check` et `audit-dependency-check-tests` (même version que le profil `d2-security` de MORPHEUS) | exécuté |
| Gitleaks | **8.30.1** (binaire Windows officiel, somme SHA-256 vérifiée contre `gitleaks_8.30.1_checksums.txt`) | installé hors dépôt, pas une dépendance Maven | exécuté |

Les versions Maven sont des propriétés du `pom.xml` parent (`spotbugs.*`, `pitest.*`, `archunit.version`, `dependency-check.maven.plugin.version`) ; aucune n'est `LATEST` ni une plage.

**Compatibilité JUnit.** `pitest-junit5-plugin` 1.2.3 est construit contre JUnit 5.9 ; le dépôt utilise JUnit Jupiter 6.1.3. La compatibilité est **constatée** sur les exécutions consignées dans [code-audit-couverture.md](code-audit-couverture.md), pas garantie par l'éditeur.

## Périmètre de chaque outil

**SpotBugs** analyse le bytecode de production (`target/classes`, tests exclus) de **tous les modules du réacteur Maven**. Le plugin IntelliJ `minos-intellij` (Gradle, hors réacteur) est analysé par la ligne de commande sur ses classes recompilées (voir « Plugin IntelliJ »). Hors périmètre, faute de bytecode de production : `fixtures/` (projets d'exemple indexés par les tests), `benchmarks/`, les sources Java des scripts historiques (`scripts/history`, `scripts/m14`…).

**PIT** mute toutes les classes `com.minos.*` de chaque module avec tous les tests `com.minos.*` du même module (profil `audit-mutation`). La restriction initiale à trois classes de `minos-engine` (7 octobre 2026) n'est plus le défaut ; `-DtargetClasses` / `-DtargetTests` restreignent une exécution sans toucher au POM. `-Dpit.crossModule=true` mute aussi les classes amont du réacteur contre les tests du module courant (voir « Limites »).

**ArchUnit** importe le bytecode de production des **14 modules** depuis leurs `target/classes` (`minos-app` : `target/classes` de la racine) dans `minos-app/src/test/java/com/minos/app/architecture/ModuleArchitectureTest.java`. Le test vérifie d'abord que chaque module est importé **en entier** (nombre de `.class` sur disque = nombre de classes importées) : une sortie de compilation absente ou vide fait échouer le test au lieu de laisser les règles passer sur un périmètre vide. ArchUnit fait de plus échouer par défaut une règle dont le sujet est vide.

**Dependency-Check** analyse les dépendances de tous les modules du réacteur en un rapport agrégé : livrées (portées compile, runtime, provided, system) avec `audit-dependency-check`, et avec la portée test en plus avec `audit-dependency-check-tests`. Il ne voit **pas** : les plugins Maven et leurs dépendances (outillage de build), le plugin IntelliJ (Gradle), les images Docker, les outils SCIP embarqués, les scripts Python. Les dépendances du plugin IntelliJ sont analysées à part (voir « Plugin IntelliJ »).

**Gitleaks** analyse l'arbre de travail (fichiers suivis, non suivis et ignorés), l'historique local (toutes les références, `refs/stash` comprise) et l'historique distant complet par un **clone miroir hors dépôt** (branches, tags et `refs/pull/*`). Le clone de travail peut être superficiel (`git rev-parse --is-shallow-repository`) : son historique seul ne suffit pas.

## Profils Maven et effet exact

| Profil | Activé par défaut | Effet |
|---|---|---|
| *(aucun)* | — | `clean verify` ne charge ni SpotBugs, ni PIT, ni Dependency-Check. Les règles ArchUnit établies tournent avec les tests de `minos-app` ; la mesure stricte (`everyModuleOnlyUsesItsAllowedModules`) est sautée sauf `-Dminos.audit.archunit.strict=true` |
| `audit-spotbugs` | non | lie le but `spotbugs` (rapport, ne fait jamais échouer) à `verify`. Le contrôle bloquant `spotbugs:check` se lance explicitement |
| `audit-mutation` | non | déclare `pitest-maven`, sans liaison à une phase ; `targetClasses` et `targetTests` valent `com.minos.*` |
| `audit-dependency-check` | non | déclare `dependency-check-maven` (non hérité) pour le but `aggregate` à la racine ; dépendances livrées seulement ; rapports HTML, JSON, SARIF dans `target/dependency-check/` |
| `audit-dependency-check-tests` | non | idem avec la portée test ; rapports dans `target/dependency-check-tests/` |

Paramètres communs de Dependency-Check : `failBuildOnCVSS=11` (aucun score ne fait échouer : outil d'audit, pas contrôle de fusion), `failOnError=true` (une erreur de téléchargement ou d'analyse fait échouer le build, elle n'est jamais lue comme « aucune vulnérabilité »), analyseur OSS Index désactivé (il exige désormais des identifiants Sonatype), analyseur .NET désactivé.

## Commandes

Toutes se lancent depuis la racine du dépôt. Tout `-pl` est accompagné de `-am` (le POM parent utilise `${revision}`). Sous PowerShell, **quotez chaque argument `-D`**.

### SpotBugs : rapport et contrôle bloquant

```bash
./mvnw -B -ntp -Paudit-spotbugs -DskipTests compile spotbugs:spotbugs
python scripts/quality/audit-report-summary.py spotbugs
./mvnw -B -ntp -fae -Paudit-spotbugs -DskipTests compile spotbugs:check
```

Le contrôle bloquant échoue s'il reste une alerte `Medium` ou plus. Réglages : `-Dspotbugs.failThreshold=High`, `-Dspotbugs.threshold=Low`, `-Dspotbugs.effort=Default`, `-Dspotbugs.maxAllowedViolations=50`.

### SpotBugs : prouver le périmètre analysé

Le XML du plugin Maven 4.10.4.1 ne contient **aucune statistique de classes** (`total_classes="0"`, aucun `ClassStats` ; `num_packages` ne compte que les packages qui ont une alerte). Il ne prouve donc pas quelles classes ont été analysées : `audit-report-summary.py spotbugs` le signale `UNPROVEN` (échec avec `--strict`). Pour le prouver, rejouer SpotBugs en ligne de commande avec les mêmes réglages ; son XML porte un `ClassStats` par classe :

```bash
python scripts/quality/spotbugs-cli.py --output target/spotbugs-cli
python scripts/quality/audit-report-summary.py spotbugs --reports-dir target/spotbugs-cli --strict
```

`spotbugs-cli.py` compile le réacteur et écrit le classpath de chaque module dans la même invocation Maven (sinon les modules voisins se résolvent vers les jars du dépôt local, peut-être périmés), résout `com.github.spotbugs:spotbugs` à la version `spotbugs.version` du POM, puis lance `FindBugs2` par module (`-effort:max -medium -xml:withMessages`, `quality/spotbugs-exclude.xml`). Le job SpotBugs de `code-audit.yml` l'exécute après l'analyse Maven et publie `target/spotbugs-cli/*.xml`. La relecture du 8 octobre a produit les mêmes alertes (mêmes `instanceHash`) que le plugin Maven, module par module.

### PIT : tout le réacteur ou un module

```bash
./mvnw -B -ntp -fn -Paudit-mutation -DfailWhenNoMutations=false -Dpit.threads=10 -Djacoco.skip=true \
  test-compile org.pitest:pitest-maven:mutationCoverage
python scripts/quality/audit-report-summary.py pit --all
```

- `-fn` et non `-fae` : avec `-fae`, un module en échec fait sauter tous les modules qui en dépendent.
- Reprise après interruption : **pas de `-rf :<module>`** : il sort les modules amont du réacteur, et Maven ne trouve pas leurs artefacts (`${revision}`, rien n'est installé dans `~/.m2`), « Could not collect dependencies », constaté. Relancer tout le réacteur en limitant `-DtargetClasses` aux paquets des modules restants : les modules amont n'ont alors aucune classe à muter. L'historique incrémental de PIT (`-DwithHistory`) n'est **pas** utilisable : depuis PIT 1.30, il exige un greffon commercial (« History has been enabled but no history plugin has been installed », constaté).
- `-Djacoco.skip=true` évite que l'agent JaCoCo de `prepare-agent` soit repris dans la ligne de commande des JVM de PIT.
- Un module : `-pl <module> -am` puis `audit-report-summary.py pit --module <module>`. `-am` mute aussi les modules amont : pour ne muter que le module visé, restreindre `-DtargetClasses` à ses packages.
- `audit-report-summary.py pit` sépare les mutants **tués par une assertion** (`KILLED`) des **timeouts et erreurs** (`TIMED_OUT`, `MEMORY_ERROR`, `RUN_ERROR`) : PIT compte ces derniers comme détectés, mais ils prouvent qu'un mutant a cassé la JVM de test, pas qu'une assertion l'a vu.

### ArchUnit

```bash
./mvnw -B -ntp -pl minos-app -am test -Dtest=ModuleArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false
# mesure stricte (proposition, non établie par un ADR) :
./mvnw -B -ntp -pl minos-app -am test -Dtest=ModuleArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false -Dminos.audit.archunit.strict=true
```

Les règles établies et leur source :

| Test | Décision |
|---|---|
| `everyModuleIsImportedCompletely` | garde contre un périmètre vide ou partiel (14 modules, chaque `.class` importée) |
| `everyPackageBelongsToOneModule` | ADR 0044 § 1 sur le bytecode (aucun package compilé par deux modules) |
| `adaptersDoNotReachBackIntoUpperLayers` | ADR 0042 (A2) : un adaptateur n'atteint ni l'application, ni la racine de composition, ni une surface, ni l'assemblage |
| `applicationOnlyKnowsPorts` | ADR 0042 (A2) : `minos-application` ne connaît que des ports |
| `surfacesDoNotCompileAgainstTheCompositionRootOrAdapters` | ADR 0042 (A2) : les surfaces n'atteignent la racine de composition qu'à l'exécution |
| `coreModulesStayAtTheBottom` | ADR 0022 : le domaine ne dépend d'aucun module, le moteur du seul domaine |
| `everyModuleOnlyUsesItsAllowedModules` (opt-in) | **proposition** : lecture stricte de `ALLOWED_DEPENDENCIES` (une classe n'utilise que les modules que son POM a le droit de déclarer). Aucun ADR n'interdit l'usage transitif ; voir le constat MINOS-AUD-H08 |

Le test lit les sorties de compilation depuis le répertoire de travail du dépôt (Surefire `workingDirectory`) : lancez-le après une compilation du réacteur.

### Dependency-Check

```bash
./mvnw -B -ntp -Paudit-dependency-check -DnvdApiKeyEnvironmentVariable=NVD_API_KEY org.owasp:dependency-check-maven:aggregate
./mvnw -B -ntp -Paudit-dependency-check-tests -DautoUpdate=false org.owasp:dependency-check-maven:aggregate
python scripts/quality/audit-report-summary.py dependency-check target/dependency-check/dependency-check-report.json
python scripts/quality/audit-report-summary.py dependency-check target/dependency-check-tests/dependency-check-report.json
```

La clé NVD se passe **par le nom** de la variable d'environnement (`-DnvdApiKeyEnvironmentVariable=NVD_API_KEY`), jamais par sa valeur sur la ligne de commande. `audit-report-summary.py dependency-check` affiche la date de la base (`NVD API Last Checked`, `NVD API Last Modified`) et échoue sur toute exception d'analyse.

**Base NVD.** Dependency-Check 13.0.0 ne sait pas rafraîchir le flux NVD sans clé (anomalie amont #8715, constatée aussi dans MORPHEUS). Si la clé est absente ou refusée (`Invalid API Key` dans le journal), le build échoue : c'est une **erreur d'infrastructure, pas un résultat**. Repli constaté le 8 octobre : rafraîchir anonymement la base avec 12.2.2, qui écrit le même schéma H2, puis analyser avec 13.0.0 sans mise à jour :

```bash
./mvnw -B -ntp -N -DdataDirectory=<base> -DossindexAnalyzerEnabled=false org.owasp:dependency-check-maven:12.2.2:update-only
./mvnw -B -ntp -Paudit-dependency-check -DautoUpdate=false -DdataDirectory=<base> org.owasp:dependency-check-maven:13.0.0:aggregate
```

Placez la base hors du dépôt et hors du dépôt Maven partagé (`-DdataDirectory`), pour qu'une autre analyse concurrente (MORPHEUS) ne verrouille pas le même fichier H2.

### Gitleaks

```bash
gitleaks version                                    # 8.30.1
git rev-parse --is-shallow-repository               # true : l'historique local est tronqué
git clone --mirror https://github.com/FTurleque/minos-code-intelligence.git <hors dépôt>/mirror.git
gitleaks git <hors dépôt>/mirror.git --log-opts="--all" --redact=100 --report-format json --report-path <hors dépôt>/mirror-history.json --exit-code 0
gitleaks git . --log-opts="--all" --redact=100 --report-format json --report-path <hors dépôt>/local-history.json --exit-code 0
gitleaks dir . --redact=100 --report-format json --report-path <hors dépôt>/worktree.json --exit-code 0
```

`--redact=100` remplace chaque valeur par `REDACTED` dans le rapport ; les rapports s'écrivent **hors du périmètre analysé** pour ne pas être réanalysés. Pour qualifier une alerte sans afficher la valeur, ne publiez que sa **forme** (longueur, alphabet hexadécimal ou base 64) et la ligne masquée. La règle `sourcegraph-access-token` de la configuration par défaut correspond à **toute** chaîne hexadécimale de 40 caractères : épinglages d'actions GitHub, SHA de commit, versions d'outils.

### Plugin IntelliJ (Gradle, hors réacteur)

Gradle n'est pas installé sur le poste de développement et `minos-intellij/` n'a pas de `gradlew`. Les classes de `minos-intellij/build/` peuvent être périmées (le 8 octobre, elles dataient du 12 août alors que les sources avaient changé le 7 octobre) : ne les analysez pas telles quelles. Harnais constaté :

1. compiler `src/main/java` avec `javac --release 21` contre `<IDE>/lib/*` (IntelliJ IDEA `IU-261.22158.277`, la plateforme cible de `build.gradle.kts`) et `gson-2.14.0.jar` ; idem pour `src/test/java` avec JUnit 6.1.3 ;
2. lancer les tests par l'API `LauncherFactory` de JUnit Platform 6.1.3 ;
3. lancer SpotBugs en ligne de commande sur les classes recompilées, `<IDE>/lib/*` en classpath auxiliaire ;
4. analyser les dépendances déclarées dans `build.gradle.kts` (`implementation`, `testImplementation`, `testRuntimeOnly`) par un POM jetable qui les reproduit, avec `dependency-check-maven:check`.

Ce harnais n'exécute ni Gradle, ni le plugin Gradle `org.jetbrains.intellij.platform`, ni le plugin dans une IDE réelle ; il n'analyse pas la plateforme IntelliJ elle-même (fournie par l'IDE, non livrée).

### Déclenchement manuel dans GitHub Actions

`.github/workflows/code-audit.yml` (`workflow_dispatch` uniquement) lance SpotBugs (rapport ou `spotbugs:check`), PIT sur un module choisi (`mutation_module`, toutes ses classes) et Dependency-Check (livré puis tests, avec le secret `NVD_API_KEY`), exécute les synthèses et publie les rapports en artefacts. **Ce workflow n'a pas été exécuté sur GitHub** : il passe les contrôles statiques du dépôt (`check-workflow-pins.py`, `check-single-execution.py`).

## Chemins des rapports

| Outil | Fichiers |
|---|---|
| SpotBugs (Maven) | `<module>/target/spotbugsXml.xml`, `spotbugsSarif.json`, `reports/spotbugs.html` ; `minos-app` : dans le `target/` de la racine |
| SpotBugs (ligne de commande) | dossier choisi, un `<module>.xml` par module |
| PIT | `<module>/target/pit-reports/mutations.xml` et `index.html` ; `minos-app` : `target/pit-reports/` à la racine |
| ArchUnit | `target/surefire-reports/com.minos.app.architecture.ModuleArchitectureTest.txt` (racine, `minos-app`) |
| Dependency-Check | `target/dependency-check/` et `target/dependency-check-tests/` (`.html`, `.json`, `.sarif`) |
| Gitleaks | dossier choisi **hors du dépôt** |

`clean` supprime tous les `target/`. Les rapports volumineux ne se versionnent pas : conservez-les comme artefacts du workflow `code-audit.yml` ou dans un dossier local hors dépôt (celui du 8 octobre est cité dans [code-audit-couverture.md](code-audit-couverture.md)).

## Interpréter SpotBugs

Chaque alerte porte un **motif** (`type`), une **priorité** (1 `High`, 2 `Medium`, 3 `Low`), une classe, une méthode, une ligne et une empreinte stable (`instanceHash`). Lisez d'abord les alertes `High`, puis le motif le plus fréquent : un motif répété cent fois dit plus sur une convention du code que sur cent défauts distincts. Une alerte n'est pas un bug : **corriger**, **consigner** (constat OpenSpec) ou **exclure** si c'est un faux positif démontré.

## Interpréter PIT

| Statut | Signification | Que faire |
|---|---|---|
| `KILLED` | au moins un test a échoué sur le mutant : le comportement est contrôlé | rien |
| `SURVIVED` | le code a été exécuté par un test, mais **aucun test n'a échoué** | écrire ou renforcer une assertion, **ou** démontrer un mutant équivalent |
| `NO_COVERAGE` | aucun test du module n'exécute cette ligne | ajouter un test ; vérifier qu'un test d'un autre module ne la couvre pas (voir « Limites ») ; ou le code est mort |
| `TIMED_OUT` | le mutant a provoqué une boucle ou une attente au-delà du délai | **pas une preuve de solidité** : à examiner, surtout s'ils sont nombreux |
| `MEMORY_ERROR`, `RUN_ERROR` | le mutant a fait échouer la JVM de test | idem |
| `NON_VIABLE` | le mutant ne se charge pas | ignorer |

Indicateurs : **mutation score** (tués / générés), **force des tests** (tués / couverts). `audit-report-summary.py` calcule les deux **sans** compter timeouts et erreurs comme des tués. **Couverture et détection de mutation ne sont pas la même mesure** : JaCoCo compte les lignes exécutées, PIT les comportements vérifiés.

## Faux positifs et exclusions

1. Une exclusion SpotBugs vise **une classe**, si possible **une méthode**, pour **un motif**, avec un commentaire daté qui dit pourquoi l'alerte est fausse.
2. Une alerte *réelle* mais acceptée ne s'exclut pas : elle se consigne comme constat ou se couvre par un ADR.
3. Côté PIT, un mutant équivalent se documente dans le constat qui le concerne ; une classe peu testée ne s'exclut pas.
4. Côté Dependency-Check, une suppression vise un couple précis (purl ou fichier, CPE ou CVE), avec la démonstration de la fausse correspondance. `quality/dependency-check-suppressions.xml` n'en contient que deux, les faux rapprochements de modules MINOS de H02 (`minos-storage-postgresql` ↔ serveur PostgreSQL, `minos-nexus` ↔ project-nexus) ; `failBuildOnUnusedSuppressionRule=true` fait échouer l'analyse dès qu'une règle ne sert plus.
5. Côté Gitleaks, une liste d'autorisations se limite à une forme démontrée (par exemple un SHA d'action après `uses: …@`), jamais à un fichier entier.

## Limites connues

- **Code qui modifie l'hôte : machine jetable obligatoire.** Sous Windows, les tests de `minos-runtime-local` (et ceux de `minos-bootstrap`, `minos-cli`, `minos-app` qui démarrent le bac à sable) accordent des droits AppContainer sur de vrais répertoires de l'hôte. Sous PIT, des mutants et des timeouts tuent ces lanceurs avant leur nettoyage : les droits restent (MINOS-AUD-H22), et le PIT du 8 octobre a coïncidé avec la perte du JDK et de la lecture du profil du poste (H23). Mutez ces modules sur un runner éphémère ou une VM, jamais sur un poste de travail.
- **Tests d'un autre module.** PIT n'exécute que les tests du module analysé (`crossModule=false`). Une classe couverte seulement par des tests d'un module aval (`minos-app`, `minos-bootstrap`, `minos-cli`…) apparaît `NO_COVERAGE` ou `SURVIVED` à tort. Avant de conclure à un test manquant, cherchez la classe dans les autres modules (`grep -rl NomDeClasse */src/test`), ou mutez-la depuis le module aval avec `-Dpit.crossModule=true`.
- **Répertoire de travail.** `pitest-maven` n'a pas de paramètre `workingDirectory` ; la configuration passe `-Duser.dir=<racine>` aux JVM de PIT parce que des tests lisent des chemins relatifs à la racine (`HostedProductionBoundaryTest`).
- **Mutants équivalents.** PIT ne les distingue pas ; ils se démontrent au cas par cas.
- **Aucun seuil** PIT ni SpotBugs bloquant n'est imposé.
- **SpotBugs sans greffons** (`find-sec-bugs`, `fb-contrib` absents).
- **ArchUnit et dépendances dynamiques.** Le bytecode ne montre pas un chargement par `ServiceLoader` ou par réflexion : la règle « surfaces sans racine de composition » ne voit que les références compilées.
- **Linux, macOS et GitHub Actions.** Les mesures viennent de Windows ; les commandes bash et le workflow `code-audit.yml` n'ont pas été exécutés hors de ce poste.

## Préparer des corrections avec OpenSpec

1. **Choisir un lot cohérent** : un motif dans un module, les survivants d'une classe critique, une famille de vulnérabilités.
2. **Qualifier** : défaut confirmé, faux positif, mutant équivalent, risque ou décision. Citer motif, classe, méthode, ligne et `instanceHash` (SpotBugs), ou classe, méthode, ligne et mutateur (PIT), ou purl, CPE et CVE (Dependency-Check).
3. **Ouvrir un changement** OpenSpec : tableau constat / qualification / traitement, exigence testable, tâches qui nomment les scripts et scopes à rejouer.
4. **Écrire d'abord le test** qui tue le mutant ou échoue sur le défaut, puis corriger ; rejouer l'outil sur le même périmètre.
5. **Mesurer avant d'imposer un seuil** : un seuil PIT, un `spotbugs:check` ou un `failBuildOnCVSS` bloquant ne se décide qu'avec une base chiffrée et une décision explicite.
