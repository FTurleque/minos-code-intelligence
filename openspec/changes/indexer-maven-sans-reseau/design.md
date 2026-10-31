# Design

## Context

scip-java indexe un projet Maven en lançant Maven : `mvn --batch-mode clean verify -DskipTests` par défaut, des arguments passés après `scip-java index --` remplaçant cette ligne (documentation de scip-java, « Run `scip-java index` »). Un build Maven exécute du code que contrôle le dépôt analysé : plugins du cycle de vie et ceux que déclare le `pom.xml`, extensions de build (`<extensions>true</extensions>`) et extensions de cœur (`.mvn/extensions.xml`), options de la JVM de Maven (`.mvn/jvm.config`, qui peut charger un `-javaagent:` du projet avant le cœur de Maven), options de `.mvn/maven.config` (lues avant la ligne de commande, elles peuvent réécrire `-Dmaven.repo.local*` vers un chemin monté, sans effet hors du bac à sable). Ce code n'est pas de confiance (ADR SEC-0070) ; sous Linux il s'exécute dans le bac à sable bubblewrap + cgroup v2, réseau `DENY`.

Mesure du 2026-10-31, WSL2 Ubuntu 24.04, MINOS 1.3.0 lancé par `systemd-run --user --scope -p Delegate=yes` : `linux-bubblewrap-cgroup2-v5 AVAILABLE`, scip-java `READY`, JDK autonome `/usr/lib/jvm/temurin-25`, Maven 3.9.16 sous `/usr/local`. L'indexation de `fixtures/java/java-simple` échoue dans Maven : « Plugin org.apache.maven.plugins:maven-clean-plugin:3.2.0 … could not be resolved … repo.maven.apache.org: Temporary failure in name resolution ». Le 2026-10-15, un index réel avait été obtenu dans un conteneur `--privileged` avec un `settings.xml` hors ligne pointant vers un `~/.m2/repository` monté en lecture seule (configuration de poste, non versionnée, écartée par l'ADR SEC-0074 pour son conteneur privilégié).

## État vérifié au HEAD

HEAD `8c3d4615` (`develop`, 2026-10-31). L'ADR SEC-0074 est cité dans sa version de la PR #626 (branche `fix/declarer-java-non-indexable-sous-windows`, non fusionnée).

| Fait | Preuve | Verdict |
|---|---|---|
| Le réseau des providers est `DENY` par défaut ; `ALLOW` n'est permis que si le chemin n'exécute pas de code du dépôt, ou si ce code est isolé dans une phase distincte explicitement de confiance ; les factories SCIP gardent `DENY` | `minos-runtime-local/src/main/java/com/minos/runtime/local/process/IndexerProcessPlanFactory.java:17-30` | confirmé |
| `ScipJavaProcessPlanFactory` ne surcharge pas `networkPolicy()` | `minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/plan/ScipJavaProcessPlanFactory.java:41-368` (aucune occurrence) | confirmé |
| Sous Linux, le plan est `java -jar <lanceur> index --output … --targetroot …` (classpath installé) ou `scip-java index …` (lanceur du `PATH`, sans classpath installé), sur une copie du projet, sans argument Maven, environnement déclaré vide | `ScipJavaProcessPlanFactory.java:148-172`, `:204-222` (`Map.of()` en `:168`) | confirmé |
| `PATH` et `JAVA_HOME` sont hérités : le Maven exécuté est celui du `PATH` de l'hôte, non vérifié par MINOS | `ProviderProcessEnvironment.java:22-23` | confirmé |
| Le plan est reconstruit par le constructeur à cinq composantes dans plusieurs transformateurs | `LinuxBubblewrapWorkerSandboxBackend.java:296`, `WindowsAppContainerWorkerSandboxBackend.java:357`, `:378`, `WindowsJobObjectProcessOwnership.java:72`, `StrongProcessOwnershipIndexerExecutor.java:225` | confirmé (revue de sécurité) |
| Le réglage est lu des propriétés JVM, de l'environnement de MINOS ou de `MINOS_HOME/config/minos.properties`, jamais du dépôt analysé | `MinosRuntimeSettings.java:22` | confirmé (revue de sécurité) |
| La copie du projet exclut `mvnw`, `mvnw.cmd`, `.gradle`, `.cache`, `build` mais garde `.mvn/` (dont `extensions.xml`, `maven.config`) | `ScipJavaProcessPlanFactory.java:45-47`, `:256-284` | confirmé |
| Le bac à sable monte en lecture seule `/usr`, `/bin`, `/lib`, `/lib64`, `/sbin` ; `/etc/resolv.conf`, `/etc/hosts`, `/etc/passwd`, certificats seulement en `ALLOW` | `minos-runtime-local/src/main/java/com/minos/runtime/local/sandbox/LinuxBubblewrapWorkerSandboxBackend.java:377-386` | confirmé |
| Un argument de commande qui est un fichier est monté seul ; sous `MINOS_HOME/tools`, la racine `<outil>/<version>` ; un répertoire arbitraire n'est jamais promu en racine de lecture | `LinuxBubblewrapWorkerSandboxBackend.java:388-418` (commentaire `:405-406`) | confirmé |
| Écriture : seuls l'espace de travail, le dossier de l'artefact et le run ; `/tmp` en tmpfs | `LinuxBubblewrapWorkerSandboxBackend.java:279-291` | confirmé |
| `DENY` = espace de noms réseau isolé (`--unshare-all` sans `--share-net`) | `LinuxBubblewrapWorkerSandboxBackend.java:358-364` | confirmé |
| Aucun `~/.m2` monté ; sous Linux `HOME` n'est pas hérité (seule la liste Windows le contient), `MAVEN_ARGS` et `MAVEN_OPTS` non plus | `minos-runtime-local/src/main/java/com/minos/runtime/local/process/ProviderProcessEnvironment.java:21-42`, `:54` | confirmé |
| `IndexerProcessPlan` = commande, répertoire, environnement, artefact, délai ; aucune racine de lecture | `minos-runtime-local/src/main/java/com/minos/runtime/local/process/IndexerProcessPlan.java:12-18` | confirmé |
| Le Maven de l'hôte installé sous `/usr/local` est lisible du bac à sable, son `conf/settings.xml` (réglages globaux) aussi | conséquence de `:377-379` (`/usr` monté entier) | déduit, non mesuré |
| Sous Windows natif, scip-java est `BLOCKED` et le runtime Docker ne lance aucun provider | ADR SEC-0074, décisions 1 et Context (PR #626) | confirmé (texte de l'ADR) |

Non mesuré au HEAD : la valeur de `user.home` vue par Maven dans le bac à sable (sans `HOME` ni `/etc/passwd`), donc l'emplacement du dépôt local qu'il tente d'utiliser ; le comportement de Maven 3.9.16 avec `-Dmaven.repo.local.tail` sur un dépôt monté en lecture seule ; les besoins réseau propres à scip-java pendant le build (greffon `semanticdb-javac`).

### Écart avec l'énoncé de la tâche

- Le constat ne mentionnait pas `.mvn/extensions.xml` : il fait exécuter du code du dépôt **avant** tout plugin, pendant la construction du modèle. Toute phase « de résolution » qui lance Maven sur le projet exécute donc du code du dépôt.
- Les réglages globaux de Maven (`$MAVEN_HOME/conf/settings.xml`, parfois porteurs de mots de passe d'entreprise) sont déjà lisibles par le build analysé, puisque `/usr` est monté entier. L'absence de réseau ne suffit pas à les garder : le build peut les recopier dans une source générée, donc dans les symboles et la documentation de l'index SCIP, que MCP sert ensuite à un client (souvent un modèle distant). Ce canal vaut pour tout ce que le bac à sable rend lisible, dont le dépôt désigné.

## Options

Critères (ADR SEC-0070, SEC-0074 décision 4, `.claude/rules/securite.md`) : réseau `DENY` pour le code du dépôt ; aucun secret ni fichier de réglages de l'utilisateur dans le bac à sable ; téléchargements faits par MINOS et vérifiés, jamais par un provider (règle 8) ; aucun montage d'un ancêtre (profil, `/home`, `/mnt/c/Users`) ; garantie sur Linux et Windows, ou refus déclaré.

### A. Dépôt désigné en lecture seule, Maven `--offline`

L'utilisateur désigne un dépôt Maven local. Le plan le déclare racine de lecture ; le bac à sable le monte en lecture seule. scip-java reçoit après `--` une ligne Maven fixe :

```
--batch-mode --offline
-s <run>/maven/settings.xml -gs <run>/maven/settings.xml
-Dmaven.repo.local=<run>/maven/local
-Dmaven.repo.local.tail=<dépôt désigné>
clean verify -DskipTests
```

`settings.xml` est écrit par MINOS, vide de serveurs, miroirs, proxys et profils. Le dépôt de tête (`<run>/maven/local`) reçoit ce que Maven écrit (`_remote.repositories`, `resolver-status.properties`) et disparaît avec le run ; le dépôt désigné n'est jamais écrit.

- **Secrets** : `~/.m2/settings.xml` et `settings-security.xml` ne sont jamais montés : seul le répertoire désigné l'est, et une désignation dangereuse est refusée (D3). `-s` et `-gs` neutralisent les réglages globaux pour Maven ; le fichier reste lisible sous `/usr` et peut être recopié dans l'index (écart ci-dessus) : le masquer par un `--ro-bind` d'un fichier vide sur `$MAVEN_HOME/conf/settings.xml` et `toolchains.xml` du Maven résolu est retenu (décision 6). Le dépôt peut contenir des artefacts privés d'autres projets, que le build peut recopier dans l'index : risque résiduel à déclarer, réduit par un dépôt dédié.
- **Code du dépôt exécuté** : les plugins et extensions déjà présents dans le dépôt désigné, sous le bac à sable, sans réseau ; `.mvn/maven.config` peut ajouter des options mais ni ouvrir le réseau ni écrire le dépôt désigné. Aucune empreinte : un artefact altéré du dépôt est exécuté, avec les mêmes bornes que le reste du build.
- **Complétude** : partielle. Un artefact absent fait échouer le build hors ligne. Peupler le dépôt revient à l'utilisateur, et la façon naturelle de le faire (`mvn dependency:go-offline` dans le projet) charge `.mvn/extensions.xml` et `.mvn/jvm.config` **sur l'hôte, avec réseau et secrets** : le confinement serait vidé par son mode d'emploi. La documentation interdit donc de lancer Maven dans un projet non fiable pour peupler le dépôt et donne une procédure sûre : `mvn dependency:get -Dartifact=…` depuis un dossier vide, coordonnées lues dans le POM, ou dépôt dédié peuplé par un projet de confiance.
- **Linux** : qualifiable (bind en lecture seule de bubblewrap). **WSL2** : même mécanisme ; un dépôt Windows sous `/mnt/c/Users/<compte>/.m2/repository` est un sous-dossier du profil monté seul, jamais le profil ; propriétaire et performances DrvFs à mesurer. **Windows natif** : sans objet tant que scip-java est `BLOCKED` ; l'AppContainer refuse un plan qui déclare des racines de lecture (aucun octroi nouveau, ADR SEC-0074 décision 4). **Docker** : sans objet.
- **Coût** : une composante de plan, un montage, un réglage, un fichier écrit dans le run ; aucune dépendance nouvelle.

### B. Phase de résolution de confiance avec réseau, puis indexation `DENY`

La Javadoc de `networkPolicy()` l'admet si le code du dépôt est isolé dans une phase distincte explicitement de confiance. Deux réalisations :

- **B1. Maven lancé par MINOS, réseau `ALLOW`** (`dependency:go-offline` ou équivalent) sur consentement par projet. Maven construit le modèle du projet, donc charge `.mvn/extensions.xml` et les extensions de build : du code du dépôt s'exécute **avec** la sortie réseau, et il lit `/usr` (réglages globaux) comme la copie du projet. Les dépôts déclarés par le `pom.xml` sont contactés. Le téléchargement est fait par le provider, contraire à la règle 8 et à l'esprit de l'ADR 0065 (empreinte épinglée avant exécution). `go-offline` ne résout pas toujours les dépendances des plugins : l'indexation hors ligne qui suit peut encore échouer. Le « de confiance » repose sur une déclaration de l'utilisateur, que MINOS ne peut pas vérifier.
- **B2. Résolveur embarqué dans MINOS** (Maven Resolver et constructeur de modèle, sans exécuter de plugin). MINOS lit lui-même un `pom.xml` hostile (héritage, imports, interpolation, activation de profils), dans un processus qui a le réseau et des secrets ; il contacte les dépôts que le projet déclare (requête vers une URL choisie par l'attaquant) sauf à les restreindre à Central ou au miroir de l'utilisateur ; il ne peut pas connaître les plugins qu'apporte une extension sans l'exécuter. Les sommes de contrôle de Central ne sont pas des empreintes épinglées. Une dizaine de dépendances nouvelles (chaîne d'approvisionnement, règle 10).
- **Plateformes** : Linux ; Windows natif sans objet (`BLOCKED`) ; le bac à sable Windows en `ALLOW` n'a pas de qualification du code de dépôt avec réseau.
- **Verdict** : B1 contredit SEC-0070 et la règle 8 ; B2 est un chantier de sécurité à part entière. Non supporté dans ce changement ; un ADR serait nécessaire pour l'ouvrir.

### C. Dépôt géré par MINOS sous `MINOS_HOME`

Un dépôt sous `MINOS_HOME` (par exemple `MINOS_HOME/tools/maven-repository/<version>`), écrit par MINOS seul, monté en lecture seule comme en A.

- **C1. Peuplé depuis un catalogue épinglé** (plugins par défaut du cycle de vie du Maven retenu, sous empreinte SHA-256 comme l'ADR 0065). Couvre un projet sans dépendance ni plugin déclaré ; `java-simple` déclare déjà JUnit 5.14.4 et surefire 3.5.5, résolus même avec `-DskipTests` (compilation des tests). Utile comme socle de qualification, pas comme voie générale.
- **C2. Peuplé par une phase de résolution** : c'est B, stocké ailleurs.
- **C3. Peuplé par copie depuis le dépôt de l'utilisateur** : A, plus une copie et de l'espace disque ; isole le dépôt de l'utilisateur des lectures du build mais pas des artefacts copiés.
- **Secrets** : aucun fichier de l'utilisateur. **Code exécuté** : seulement ce que MINOS a vérifié (C1). **Plateformes** : comme A.
- **Verdict** : C1 seul ne rend aucun projet réel indexable ; il peut compléter A plus tard (dépôt de tête de secours vérifié). Différé.

### Synthèse

| | Réseau du code du dépôt | Fichiers de l'utilisateur dans le bac à sable | Projets réels | Dépendance nouvelle | ADR |
|---|---|---|---|---|---|
| A | `DENY` | le dépôt désigné, lecture seule | oui si le dépôt est complet | non | précise SEC-0074 |
| B1 | `ALLOW` | copie du projet, `/usr` | oui (incomplet parfois) | non | contredit SEC-0070 |
| B2 | `DENY` (MINOS a le réseau) | aucun | oui sauf extensions | oui, lourde | nouveau |
| C1 | `DENY` | aucun | non | non | précise SEC-0074 |

## Décisions

### D1. Voie retenue : A (décision du propriétaire, 2026-10-31)

Seule option qui garde `DENY`, n'ajoute ni téléchargement ni dépendance, et réutilise un mécanisme du bac à sable déjà qualifié (montage en lecture seule). Capacité **partielle** : elle dépend d'un dépôt peuplé par l'utilisateur, et la sortie le déclare (limite du provider, `doctor`).

### D2. Racines de lecture déclarées par le plan

`IndexerProcessPlan` reçoit une composante `readOnlyRoots` (liste de répertoires absolus, vide par défaut, constructeur existant conservé) et une méthode de copie (`withCommand(…)`) qui la conserve ; chaque transformateur relevé au HEAD passe par elle, et un test ou un gate vérifie qu'aucun site principal ne reconstruit un plan à racines sans les propager. Juste avant de construire sa commande, le backend Linux revérifie chaque racine (chemin réel égal au chemin normalisé, composants possédés par l'utilisateur ou root et non inscriptibles par d'autres, aucun chevauchement — égalité, ancêtre ou descendance — avec workspace, artefact, run ou projet source) puis la monte par `--ro-bind` après les montages système et inscriptibles. Reste un TOCTOU limité au même compte. `WindowsAppContainerWorkerSandboxBackend`, `WindowsJobObjectProcessOwnership` et un exécuteur sans bac à sable OS refusent un plan dont la liste n'est pas vide, avant tout lancement. Le mécanisme générique sert aussi plus tard à d'autres providers (Gradle, NuGet), chacun par décision propre.

Direction des dépendances (ADR 0068) : `minos-provider-scip` (adaptateur) remplit une composante d'un type exporté de `minos-runtime-local` (`process`, liste A10) ; `minos-bootstrap` lit le réglage et le passe au constructeur de la factory. Aucun sens nouveau, aucun package nouveau.

### D3. Désignation explicite, sans défaut (décision 2)

Le réglage `minos.scip.java.maven-repository` (nom à confirmer) n'a pas de valeur par défaut : `~/.m2/repository` n'est pas monté sans que l'utilisateur l'ait choisi. Refus à la lecture du réglage et au plan :

- chemin relatif, inexistant, non répertoire, lien ;
- désignation égale à, ou ancêtre de : le dossier personnel, `/mnt/<lecteur>/Users/<compte>` (profil Windows vu de WSL), `/etc`, `/proc`, `/sys`, `/dev`, `/root`, `MINOS_HOME` (donc `/`, `/home`, `/mnt`, `/mnt/c`, `/mnt/c/Users`) ;
- désignation sous `MINOS_HOME` ;
- forme qui n'est pas celle d'un dépôt Maven : à la racine, `settings.xml`, `settings-security.xml`, `toolchains.xml`, un dossier caché, ou un fichier ordinaire hors d'une liste fermée de métadonnées Maven.

Le contrôle de forme (positif) couvre ce que la liste d'ancêtres ne voit pas (`/home/<autre>`, `~/.config`). Les messages, `doctor`, `tools verify` et les journaux nomment le réglage et son état, jamais le chemin.

### D4. Ligne Maven fixe et réglages neutralisés

Arguments de D1 ajoutés après `--`, pour les deux branches du plan Linux (classpath installé et lanceur du `PATH`) ; `-Dmaven.repo.local.tail.ignoreAvailability=true` si la mesure montre qu'un artefact du dépôt désigné est jugé indisponible faute du même identifiant de dépôt distant. Le `settings.xml` du run a un contenu constant (sans `<localRepository>`, `<offline>`, `<servers>`, `<mirrors>`, `<proxies>`, `<profiles>`), écrit par une primitive privée (`check-private-io.py`) ; le dépôt de tête est créé vide pour le run, refus s'il existe déjà (un dépôt de tête partagé laisserait un build déposer un plugin piégé pour le suivant). Le mode hors ligne n'est pas une garantie de sécurité (le réseau est fermé par le bac à sable) : il rend l'échec immédiat et lisible.

Versions de Maven : `maven.repo.local.tail` existe depuis Maven 3.9 ; Maven 4 déprécie `-gs` et lit `.mvn/settings.xml` (réglages contrôlés par le dépôt). La plage qualifiée est Maven 3.9.x ; une autre version donne un refus actionnable (décision 7). Un Maven installé par `apt` (`/usr/bin/mvn` par `/etc/alternatives`, `conf/` vers `/etc/maven`) ne démarre probablement pas dans le bac à sable, `/etc` n'étant pas monté en `DENY` : échec fail-closed, à mesurer en tâche 1.1.

### D5. ADR

Le choix passe le filtre de `.claude/rules/adr.md` : options réelles, modèle de confiance du code analysé, garantie de sécurité coûteuse à inverser. SEC-0074 décision 3 n'en fixe que l'exigence (« dépôt Maven visible du bac à sable sans sortie réseau ») ; SEC-0070 fixe `DENY`, que ce choix confirme sans le changer.

Un nouvel ADR du domaine sécurité (numéro attribué à la rédaction, `SEC-0075` si SEC-0074 est fusionné) fixe la voie A et écarte B et C, en citant SEC-0074 décision 3 sans la reformuler (décision 5). SEC-0074 reste actif et inchangé.

## Décisions du propriétaire (2026-10-31)

1. **Voie** : option A.
2. **Défaut du réglage** : aucun ; `~/.m2/repository` n'est jamais monté sans désignation.
3. **Désignation sous le profil Windows depuis WSL** : `/mnt/<lecteur>/Users/<compte>/.m2/repository` est accepté comme sous-dossier exact, monté seul ; le profil et ses ancêtres restent refusés (D3).
4. **Risque résiduel** des artefacts privés d'autres projets lisibles par le build : déclaré dans la documentation, dépôt dédié recommandé.
5. **ADR** : nouvel ADR du domaine sécurité qui fixe la voie en citant SEC-0074 décision 3, sans la reformuler.
6. **Masquage des réglages globaux** du Maven de l'hôte (`--ro-bind` d'un fichier vide sur `conf/settings.xml` et `toolchains.xml`) : dans ce changement.
7. **Plage de versions Maven** : 3.9.x seule, refus actionnable sinon.
8. **Preuve d'indexation réelle** : job de CI Linux qui peuple un dépôt de qualification hors bac à sable depuis la fixture de confiance, puis indexe sous bac à sable exigé.

## Risques

- Maven peut tenter d'écrire dans le dépôt désigné malgré `tail` (verrous de fichiers, métadonnées) : l'écriture échoue en lecture seule ; à mesurer, sinon l'option A ne tient qu'avec une copie (C3).
- scip-java peut résoudre des artefacts propres pendant le build (greffon du compilateur) : à mesurer sur `java-simple` ; s'ils viennent de son classpath vérifié, rien à faire.
- Le Maven du `PATH` sous Linux n'est pas vérifié par MINOS (hors périmètre, suite possible : Maven géré, comme sous Windows).
- Canal de sortie par l'index : tout ce que le bac à sable rend lisible peut finir dans l'artefact SCIP servi par MCP ; déclaré dans l'ADR et la documentation utilisateur.
- Sur DrvFs (`/mnt/c`), Windows peut modifier les fichiers du dépôt pendant le run (même compte) ; les liens et jonctions Windows du dépôt pointent vers des chemins non montés.
- Suite possible, non retenue : un overlay bubblewrap (dépôt de tête au-dessus du dépôt désigné), `--overlay` arrivant avec bubblewrap 0.10 alors qu'Ubuntu 24.04 livre 0.9.

## Qualification

| Capacité | État visé par ce changement |
|---|---|
| Indexation scip-java Maven sous Linux, dépôt désigné complet | qualifiée par le job de CI Linux de la décision 8 (dépôt de qualification peuplé hors bac à sable, indexation sous bac à sable exigé), complétée par la mesure WSL2 manuelle |
| Idem, dépôt incomplet | partielle : échec hors ligne déclaré, aucun repli réseau |
| Racines de lecture sous Windows et backend natif | non supportée : refus avant lancement |
| Windows natif, Docker | inchangés (SEC-0074) |
