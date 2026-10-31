# Design

## Context

Constat **C3** du 2026-10-31 (WSL2 Ubuntu 24.04, MINOS sous `systemd-run --user --scope -p Delegate=yes`, `linux-bubblewrap-cgroup2-v5 AVAILABLE`, scip-java `READY`, `fixtures/java/java-simple`, JAR de la branche `fix/monter-le-jdk-dans-le-bac-a-sable-linux` @ `30ff57c6`) : avec un JDK de paquet comme avec un JDK autonome, scip-java et Maven démarrent, puis Maven échoue sur `maven-clean-plugin:3.2.0 … could not be resolved`, `repo.maven.apache.org: Temporary failure in name resolution`.

Ce que le build doit résoudre, pour `java-simple` (`fixtures/java/java-simple/pom.xml`) : les greffons du cycle de vie liés par le super-POM de la version de Maven utilisée (`clean`, `resources`, `compiler`, `surefire`, `jar`…), le greffon déclaré par le projet (`maven-surefire-plugin:3.5.5`) et la dépendance du projet (`junit-jupiter:5.14.4`, portée `test`). Dans le cas général, l'ensemble dépend du POM du projet, de ses parents et de ses profils : **il n'est connu qu'après construction du modèle du projet**, qui est du contenu non fiable.

## État vérifié au HEAD (`8c3d4615`, `develop`)

- **Egress `DENY` voulu** : `minos-runtime-local/src/main/java/com/minos/runtime/local/process/IndexerProcessPlanFactory.java:17-30` — `DENY` par défaut ; `ALLOW` « is not a dependency-resolution convenience » et n'est admis que si le chemin d'exécution n'exécute aucun code du dépôt ou si ce code est séparé dans une phase de confiance distincte ; « Current managed SCIP factories intentionally keep DENY ». Aucune fabrique de `src/main` ne retourne `ALLOW` (`git grep WorkerNetworkPolicy.ALLOW` : seul le backend Linux, qui l'interprète).
- **Bac à sable Linux** : `minos-runtime-local/src/main/java/com/minos/runtime/local/sandbox/LinuxBubblewrapWorkerSandboxBackend.java:350-367` (`baseCommand`) : `--unshare-all`, `--share-net` seulement si `ALLOW` ; `:370-411` (`addRuntimeReadOnlyBinds`) : `/usr`, `/bin`, `/lib`, `/lib64`, `/sbin` (`:377`), fichiers de `/etc` seulement si `ALLOW` (`:380-386`), racine d'un outil géré ou fichier exact pour chaque argument absolu de la commande ; « Arbitrary directories are deliberately not promoted to read roots » (`:405-406`) ; `:264-302` (`sandboxPlan`) : dossier de travail, artefact et run montés en écriture. **Aucun dépôt Maven, aucun répertoire du profil.**
- **Environnement du provider** : `minos-runtime-local/src/main/java/com/minos/runtime/local/process/ProviderProcessEnvironment.java:21-42` (liste commune : `PATH`, `JAVA_HOME`, `TMPDIR`…, ni `HOME` ni `MAVEN_OPTS` ni `MAVEN_ARGS`) ; `HOME` n'est que dans la liste Windows (`:54`) ; `:196-203` : hors Windows, seule la liste commune passe. Les variables déclarées par le plan passent si leur nom n'est pas refusé (`:188-194`, `isDeniedName` `:133-140`) : `MAVEN_ARGS` n'est pas refusé. Appliqué au lancement par `minos-runtime-local/src/main/java/com/minos/runtime/local/process/ProcessIndexerExecutor.java:367`.
- **Plan scip-java** : `minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/plan/ScipJavaProcessPlanFactory.java:148-172` (`portablePlan`) : copie de travail du projet sans `mvnw` (`:47`), commande `$JAVA_HOME/bin/java -jar <lanceur> index …` ; environnement déclaré vide (`Map.of()`, `:168` ; `:141` sous Windows). scip-java lance ensuite le `mvn` trouvé dans le `PATH`.
- **État du provider** : `minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/install/ManagedScipProviderRuntimeManager.java:228-245` (`inspectJavaInstalled`) : sous Linux, `READY` dès que Coursier et le classpath sont installés et que `JAVA_HOME` contient `javac` (`:286-296`) ; **aucun contrôle de Maven ni de dépôt local**. Le Maven géré (`:440-443`, `mavenExecutable`) n'est installé et exigé que sous Windows (`:232`).
- **Bac à sable Windows** : `minos-runtime-local/src/main/java/com/minos/runtime/local/sandbox/WindowsAppContainerWorkerSandboxBackend.java:274-276` calcule l'environnement réel du provider, `:310-314` accorde la lecture des racines d'outillage (`TOOLCHAIN_HOME_KEYS`, `:721`) ; aucun octroi d'un dépôt Maven. Sous Windows natif, scip-java est `BLOCKED` (ADR SEC-0074, PR #626, non fusionnée au HEAD) : l'écart n'y est pas observable.
- **Runtime Docker** : `docker/compose-mcp.prod.yaml:87` et `docker/compose-mcp.connected.yaml:121` posent `MAVEN_OPTS=-Dmaven.repo.local=/var/lib/minos/cache/maven/repository` sur le conteneur d'administration, décrit comme confinement de Maven par `docs/user/docker-runtime.md:152-156`. Cette valeur n'atteint aucun provider : `MAVEN_OPTS` n'est pas dans la liste d'autorisation et le plan n'en déclare pas ; surtout, le plan d'administration Docker refuse tout provider avant lancement (ADR SEC-0074, « Context » ; `StrongProcessOwnershipIndexerExecutorTest`). La page décrit donc un comportement qui n'existe pas.
- **Documentation utilisateur** : `docs/user/production-installation.md:109` affirme que, sans réseau, les greffons et dépendances « doivent déjà être dans `~/.m2` » ; c'est faux sous Linux (aucun `~/.m2` visible du bac à sable) et sans objet sous Windows (scip-java `BLOCKED`).
- **Maven** (source `apache/maven` branche `maven-3.9.x`, `DefaultRepositorySystemSessionFactory`) : `maven.repo.local.tail` (depuis 3.9.0) chaîne des dépôts locaux de queue en lecture ; les écritures vont au dépôt de tête (`maven.repo.local`) ; `maven.repo.local.tail.ignoreAvailability` vaut `true` par défaut (un artefact de la queue est accepté quel que soit le dépôt distant qui l'a fourni). `MAVEN_ARGS` (depuis 3.9.0) est préfixé à la ligne de commande par le lanceur `mvn`.
- **Tests** : aucun test n'exécute un build Maven dans le bac à sable ; la CI n'exécute aucun provider réel.

Écart avec la consigne de départ : la consigne citait `HOME` comme cause. Transmettre `HOME` ne suffirait pas (le répertoire personnel n'est pas monté) et serait contraire au principe de moindre exposition : la décision retenue ne transmet pas `HOME` et désigne le dépôt explicitement.

## Options comparées

| | A. Dépôt de greffons vérifié par MINOS, monté en lecture seule | B. Phase de résolution de confiance, avec egress | C. Dépôt local de l'utilisateur en lecture seule (queue de chaîne), hors ligne | D. Refus actionnable dans `minos doctor` seul |
|---|---|---|---|---|
| Couvre `java-simple` | Non : ni `surefire:3.5.5` déclaré par le projet, ni `junit-jupiter` | Oui | Oui, si l'utilisateur a déjà construit le projet | Non (n'indexe rien) |
| Fail-closed | Oui | Partiel : construire le modèle Maven exécute des extensions du dépôt (`.mvn/extensions.xml`, `<extensions>true</extensions>`) avec réseau | Oui : hors ligne, un artefact absent fait échouer le build | Oui |
| Secrets | Aucun | `settings.xml` nécessaire pour un dépôt privé (mots de passe) | Aucun : `settings.xml` jamais lu ni monté | Aucun |
| Exfiltration | Non | Oui : code du dépôt avec egress | Non : egress `DENY` ; les artefacts lus restent dans le bac à sable | Non |
| Parité Windows/Linux | Oui | Oui | Oui (montage Linux, octroi de lecture Windows) | Oui |
| Coût | Catalogue d'empreintes par version de Maven, à maintenir | Nouveau modèle de confiance | Faible ; Maven ≥ 3.9.0 requis | Faible |
| ADR | Oui (contenu fourni) | Oui, et contraire à `IndexerProcessPlanFactory.java:20-26` | Oui (donnée de l'utilisateur lue par du code non fiable) | Non |

**Recommandation : C, complétée par D.** C est la seule option qui indexe un projet réel sans sortie réseau ni secret ; D rend l'état honnête quand C ne peut pas s'appliquer (Maven absent ou trop ancien, dépôt absent). A est écartée comme solution car l'ensemble des artefacts dépend du projet ; elle reste un complément possible pour les greffons par défaut. B est écartée : elle donnerait le réseau à du code contrôlé par le dépôt.

## Décisions

### D1 — Maven du build analysé toujours hors ligne, dépôt de tête propre au run

Le plan scip-java Linux déclare `MAVEN_ARGS=--offline -Dmaven.repo.local=<run>/maven-repository[ -Dmaven.repo.local.tail=<dépôt>]`. Le dépôt de tête est créé par les primitives privées sous le dossier du run (déjà monté en écriture) et disparaît avec le run. L'egress reste `DENY`. Maven écrit seulement dans la tête (artefacts installés, marqueurs) ; la queue n'est jamais écrite.

`MAVEN_ARGS` est découpé sur les blancs par le lanceur `mvn` : un chemin du run ou du dépôt contenant un blanc refuse le plan (D3). Si la mesure de la tâche 2.1 montre que scip-java ne transmet pas `MAVEN_ARGS` à Maven, repli : MINOS écrit ces options dans `.mvn/maven.config` de la **copie de travail** (qu'il produit lui-même), après celles du projet.

### D2 — Dépôt de l'utilisateur : le répertoire exact, en lecture seule, sans `settings.xml`

Source : la clé de configuration MINOS de l'emplacement du dépôt (lue par MINOS, jamais transmise au provider) si elle est posée, sinon `<user.home>/.m2/repository` du processus MINOS. MINOS ne lit pas `settings.xml` (il peut contenir des mots de passe) : un `<localRepository>` personnalisé se déclare par la clé. Seule la racine réelle du dépôt est montée (`--ro-bind` à son chemin réel) ; `~/.m2`, `settings.xml`, `settings-security.xml`, `toolchains.xml` et le profil ne le sont jamais. Un dépôt absent ne monte rien et ne déclare pas de queue.

Un lien du dépôt qui sort de la racine montée n'est pas suivi : sa cible n'existe pas dans le bac à sable.

Confidentialité acceptée (à consigner dans l'ADR) : le dépôt peut contenir des artefacts privés de l'utilisateur ; le provider les lit sans pouvoir les envoyer (egress `DENY`) ; ce qu'il en recopie dans l'index reste dans le stockage privé de MINOS.

### D3 — Racines en lecture seule déclarées par le plan ; refus avant lancement

`IndexerProcessPlan` gagne une liste `readOnlyRoots` (constructeur à cinq arguments conservé, liste vide). Le bac à sable Linux monte chaque racine en `--ro-bind` ; le bac à sable Windows en accorde la lecture au SID du conteneur (comme les racines d'outillage). Avant tout lancement, une racine est refusée si elle est relative, absente, n'est pas un répertoire, contient un blanc, ou si sa racine réelle ou normalisée est de profondeur < 2, égale ou ancêtre du répertoire personnel ou de `MINOS_HOME`, nommée `.m2`, ou sous `/etc`, `/proc`, `/sys`, `/dev`, `/run` (Linux) ou `C:\Windows` (Windows). Le message nomme la règle, jamais le chemin. Le contrôle vit dans le backend (toute fabrique future en hérite), la désignation du dépôt dans `ScipJavaProcessPlanFactory`.

### D4 — `minos doctor` : scip-java n'est `READY` sous Linux que s'il peut construire hors ligne

Sous Linux, `inspectJavaInstalled` ajoute deux diagnostics `ExternalPrerequisite` (donc `BLOCKED`) :

- aucun `mvn` dans le `PATH` sous une racine montée par le bac à sable (`/usr`, `/bin`…), ou version < 3.9.0 ; la version est lue sur disque (`lib/maven-core-<version>.jar` de l'installation réelle), sans exécuter Maven ;
- dépôt local absent : le diagnostic dit d'exécuter une fois le build du projet hors de MINOS (par exemple `mvn dependency:go-offline`), puis de relancer, et nomme la clé de configuration.

Un dépôt présent mais incomplet n'est pas détectable sans construire le modèle du projet : l'échec reste celui du provider (code de sortie, journal du run), décrit par la documentation. La sortie d'erreur du provider n'est pas analysée (texte non fiable).

### D5 — Documentation

`docs/user/troubleshooting.md` (prérequis Linux de scip-java), `docs/user/production-installation.md:109` (phrase sur `~/.m2` corrigée), `docs/user/docker-runtime.md:152-156` (le plan d'administration ne lance aucun provider ; `MAVEN_OPTS` y est sans effet).

## Qualification des capacités

| Capacité | Linux | Windows |
|---|---|---|
| Indexer un projet Maven dont le dépôt local est complet | **Qualifiée** après la preuve manuelle 4.2 (WSL2) ; partielle en CI (aucun provider réel exécuté) | Non supportée (scip-java `BLOCKED`, SEC-0074) |
| Indexer un projet dont des artefacts manquent au dépôt | Non supportée, refus par échec du build hors ligne | Non supportée |
| Résolution réseau à l'indexation | Non supportée par décision | Non supportée par décision |
| Montage en lecture seule des racines déclarées | Qualifiée par tests Linux du bac à sable | Qualifiée par tests de la liste d'octroi, sans exécution de scip-java |

## Direction des dépendances (ADR 0068)

`minos-provider-scip` (adaptateur) nomme déjà `IndexerProcessPlan` de `minos-runtime-local` (4 fichiers, `scripts/architecture/check-module-boundaries.py:642`) ; aucun fichier nouveau ne le nomme, aucun type exporté nouveau (`RUNTIME_LOCAL_EXPORTED_TYPES`, `:447-456`). Aucune dépendance Maven ajoutée.

## Risques

- Maven de l'hôte < 3.9 ou installé par paquet : bloqué par D4, documenté.
- `.mvn/maven.config` ou `.mvn/extensions.xml` du projet (non fiables) peuvent modifier la ligne de commande : sans effet hors du bac à sable (lecture seule de la queue, egress `DENY`).
- Concurrence : deux runs lisent la même queue sans l'écrire ; aucun verrou requis.

## Décisions en attente du propriétaire

1. **Accepter l'ADR nommé** (recommandé) : lecture du dépôt Maven de l'utilisateur par le build non fiable, hors ligne. Sans lui, l'implémentation ne démarre pas.
2. **Nom de la clé de configuration** de l'emplacement du dépôt (proposition : propriété `minos.maven.localRepository` et variable `MINOS_MAVEN_LOCAL_REPOSITORY`, lues par MINOS seul).
3. **Maven géré sous Linux** (suivi) : il supprimerait la dépendance au Maven de l'hôte et garantirait la version ≥ 3.9 ; recommandé comme changement distinct.
