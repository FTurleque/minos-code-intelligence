# Design

## Context

Constats du 2026-10-31, mesurés dans WSL2 Ubuntu 24.04 avec MINOS 1.3.0 lancé sous `systemd-run --user --scope -p Delegate=yes` : `workerSandbox[managedLocalProvider]: linux-bubblewrap-cgroup2-v5 AVAILABLE`, scip-java `READY`, indexation de `fixtures/java/java-simple`.

| # | `JAVA_HOME` | Résultat |
|---|---|---|
| C1 | `/usr/lib/jvm/java-21-openjdk-amd64` (paquet `openjdk-21-jdk`) | `java.lang.InternalError: Error loading java.security file` (`TempFileHelper` → `Security.initialize`) |
| C2 | Temurin extrait dans le profil (`~/minos-wsl/jdk`) | code 127, `java: error while loading shared libraries: libjli.so` |

Disposition du JDK Debian relevée sur la même machine (`ls -la`) : `conf/` et `conf/security/` sont de vrais répertoires sous `/usr/lib/jvm/java-21-openjdk-amd64`, mais chacun de leurs fichiers est un lien absolu vers `/etc/java-21-openjdk/…` (`java.security`, `java.policy`, `net.properties`, `logging.properties`…) ; `lib/security/cacerts` lie vers `/etc/ssl/certs/java/cacerts`, `lib/security/default.policy` et `blocked.certs` vers `/etc/java-21-openjdk/security/…`. `docs` est un lien relatif vers `/usr/share/doc/…`, déjà sous `/usr`. Fedora/RHEL suivent le même schéma vers `/etc/java/…` et `/etc/pki/java/cacerts`.

## État vérifié au HEAD (`8c3d4615`, `develop`)

- `minos-runtime-local/src/main/java/com/minos/runtime/local/sandbox/LinuxBubblewrapWorkerSandboxBackend.java:370-410` (`addRuntimeReadOnlyBinds`) : monte `/usr`, `/bin`, `/lib`, `/lib64`, `/sbin` (`:377`) ; une liste de fichiers de `/etc` **seulement si l'egress est `ALLOW`** (`:380-386`) ; puis, pour chaque argument absolu de la commande, la racine `<provider>/<version>` d'un outil géré, sinon le **seul fichier** désigné (`:403`) ; « Arbitrary directories are deliberately not promoted to read roots » (`:405`). Aucune lecture de l'environnement du provider.
- `:286` : `sandboxPlan` appelle `addRuntimeReadOnlyBinds(sandbox, plan.command(), networkPolicy, minosHome)` ; `plan.environment()` n'est consulté que pour être recopié dans le plan rendu.
- `minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/plan/ScipJavaProcessPlanFactory.java:96` (`requireProjectJdk()`) et `:345-347` (`projectJava()`) : sous Linux, la commande est `classpathCommand(projectJava(), …)` (`:164`, `:204-211`), soit `$JAVA_HOME/bin/java -jar <launcher> index …` ; le plan déclare un environnement vide (`Map.of()`). `JAVA_HOME` atteint le provider par l'environnement hérité : `minos-runtime-local/src/main/java/com/minos/runtime/local/process/ProviderProcessEnvironment.java:23` l'inscrit dans la liste d'autorisation commune.
- Cause de C2 : `$JAVA_HOME/bin/java` est monté seul (`:403`) ; le lanceur cherche `../lib/libjli.so`, absent du bac à sable.
- Cause de C1 : le JDK est sous `/usr`, donc visible, mais ses liens pointent vers `/etc/java-21-openjdk/…`, jamais monté en `DENY`.
- Windows : `minos-runtime-local/src/main/java/com/minos/runtime/local/sandbox/WindowsAppContainerWorkerSandboxBackend.java:274-276` calcule l'environnement réel du provider par `ProviderProcessEnvironment.sanitize(new ProcessBuilder().environment(), plan.environment())`, puis `:310-314` accorde la lecture de la racine réelle de chaque clé d'outillage (`TOOLCHAIN_HOME_KEYS`, `:721`) sauf une racine trop large (`isOverBroadGrant`, `:778`). Linux n'a pas d'équivalent : c'est l'écart.
- Tests : `LinuxBubblewrapWorkerSandboxIsolationTest` (Linux) vérifie la forme de la commande (`sandboxPlanNeverBindsWholeHostRoot`, `arbitraryExecutableBindsExactFileNotItsSensitiveParent`) ; aucun ne lance une JVM dans le bac à sable.

## Décisions

### D1 — Monter la racine réelle du JDK, en lecture seule

L'environnement du provider est calculé comme sous Windows (`ProviderProcessEnvironment.sanitize`, même source). Pour `JAVA_HOME` et `JDK_HOME` : valeur absolue, existante, répertoire → racine réelle (`toRealPath`) montée `--ro-bind` à son chemin réel ; si l'écriture normalisée diffère, un `--symlink` (lien) ou un second `--ro-bind` (ancêtre lié) la rend visible à la même adresse. Une racine déjà couverte (`/usr/lib/jvm/…`) n'est pas montée deux fois. Une valeur vide, relative, absente ou qui n'est pas un répertoire ne monte rien (le plan scip-java l'a déjà refusée, `ScipJavaProcessPlanFactory.java:96`).

Option écartée : refuser tout JDK hors de `/usr` par un diagnostic. Elle laisse C1 non résolu (le JDK Debian est sous `/usr`) et interdit le JDK autonome que la documentation Windows recommande déjà.

### D2 — Cibles de liens : fichier exact, liste fermée de `/etc`

La racine réelle du JDK est parcourue sans suivre les liens, bornée (profondeur 16, 50 000 entrées). Pour chaque lien dont la cible réelle n'est ni dans la racine du JDK ni sous une racine déjà montée, la cible exacte (fichier ou répertoire désigné, jamais son parent) est montée en lecture seule à son chemin réel **si et seulement si** elle est sous une entrée de la liste fermée : un répertoire de premier niveau de `/etc` dont le nom commence par `java` (`/etc/java-21-openjdk`, `/etc/java`), `/etc/ssl/certs`, `/etc/pki/java`, `/etc/pki/ca-trust/extracted`. Si l'écriture littérale du lien (avant résolution des liens intermédiaires) diffère du chemin réel et appartient aussi à la liste, elle est montée sur la même source. Toute autre cible (profil, `/etc/shadow`, `/etc/ssh`, `/etc/ssl/private`, autre racine) n'est jamais montée ; leur nombre est journalisé en WARNING, sans chemin.

Pourquoi une liste fermée plutôt qu'une liste d'exclusion : la racine du JDK est désignée par une variable d'environnement et peut être inscriptible par l'utilisateur ; un lien planté y viserait n'importe quel fichier lisible. La liste fermée ne laisse passer que de la configuration Java publique et des certificats publics. Le parcours ne lit aucun contenu de fichier.

### D3 — Racine trop large ou qui n'est pas un JDK : refus avant lancement

`sandboxPlan` échoue par `IllegalStateException` dans deux cas. Le message nomme la variable et ne contient aucun chemin.

- **Racine trop large** : une racine réelle ou normalisée de profondeur inférieure à 2 (`/`, `/home`, `/opt`), ancêtre ou égale du répertoire personnel (`user.home`) ou de `MINOS_HOME`, ou située sous `/etc`, `/proc`, `/sys`, `/dev` ou `/run`. Message : « JAVA_HOME names a directory too broad to mount in the Linux sandbox ».
- **Racine qui n'est pas un JDK** : la racine réelle n'a pas sa propre `bin/java` (fichier régulier, pas un lien) avec un fichier `release` ou `lib/libjli.so`. Le cas couvre aussi une racine sous `MINOS_HOME` hors de `MINOS_HOME/tools`. Message : « JAVA_HOME does not name a JDK directory the Linux sandbox may mount ». Sans ce contrôle, un `JAVA_HOME` lié vers `~/.ssh` serait monté pour tout provider : seul scip-java vérifie `javac` (`ScipJavaProcessPlanFactory.java:357`).

Le refus a lieu avant tout lancement. Le cgroup du run est déjà créé (`LinuxCgroupJob.create` précède `sandboxPlan` dans `ContainedSandboxSession.transform`) ; il est récupéré par la fermeture de la session. Windows ignore une racine trop large et ne contrôle pas la forme du JDK. Linux refuse plus tôt, et la garantie de confinement est au moins la même. Étendre le contrôle de forme au bac à sable Windows est un suivi.

Remarque : un JDK situé sous le dossier de travail, l'artefact ou le run (dossiers inscriptibles) reste inscriptible, car le `--bind` du dossier parent, posé après, recouvre le `--ro-bind`. Le provider n'y gagne aucun droit : il pouvait déjà écrire dans ce dossier.

### D4 — Qualification et sonde

La sonde de capacité (`probeOsIsolation`) n'a pas d'environnement de provider : elle n'est pas modifiée. La qualification ajoute `LINUX_TOOLCHAIN_HOME_READ_ONLY_ROOT_AND_ALLOWLISTED_ETC_LINK_TARGETS` (ajout en fin de liste, additif).

## Direction des dépendances

Tout reste dans `minos-runtime-local` (package `sandbox`, qui dépend déjà de `process`). Aucune dépendance Maven, aucun type exporté nouveau (A10), aucun nouveau package.

## Capacités

- JDK autonome hors de `/usr` (Temurin, archive) : **qualifiée** par un test qui exécute `$JAVA_HOME/bin/java` dans le vrai bac à sable (Linux, bubblewrap requis).
- JDK des paquets Debian/Ubuntu : **qualifiée** par le même test sur un JDK de paquet quand la machine en a un (sinon saut visible, échec si `minos.sandbox.tests.required=true` et absence de JDK de paquet sur l'image CI : décidé en tâche, voir 2.3), et par la reproduction WSL2 (manuelle).
- Maven installé par paquet (`/etc/maven`) : **non supportée**, documentée.
- scip-java natif Linux de bout en bout sur un projet Maven : **non supportée**. Mesure du 2026-10-31 avec le JAR de la branche sous WSL2 : avec le JDK de paquet comme avec le JDK autonome, la JVM de scip-java et Maven démarrent, puis le build échoue (`maven-clean-plugin … could not be resolved`). L'egress est `DENY` et aucun dépôt Maven local n'est visible (constat C3, hors périmètre). Avec le JAR 1.3.0, les mêmes essais échouent sur C1 et sur C2.

## Plateformes

Propre à Linux. Windows : inchangé, il accorde déjà la racine d'outillage (`WindowsAppContainerWorkerSandboxBackend.java:310-314`).

## Décisions en attente du propriétaire

Aucune bloquante pour ce changement. Suivis recommandés, hors de ce changement :

- C3 : décider comment le build Maven obtient ses greffons sous `DENY`. Exemple : un dépôt de greffons vérifié monté en lecture seule, ou une phase de résolution de confiance séparée (`IndexerProcessPlanFactory.java:17-30`).
- Signaler dans `minos doctor` un Maven du `PATH` dont la configuration sort de `/usr`.
- Appliquer le contrôle de forme de JDK au bac à sable Windows.
