# Indexation Java depuis Windows par WSL2 : fonctionnement

Cette page décrit comment la voie `minos index <projet> --via-wsl` est construite, pour qui la modifie ou la
teste. Mise en place côté utilisateur : [installation, §2 ter](../user/production-installation.md#2-ter-indexer-un-projet-java-depuis-windows-par-wsl2).
Décisions : [SEC-0074](../architecture/adr/SEC-0074-java-non-indexable-depuis-windows-voie-wsl2.md) (décision 3) et
[SEC-0079](../architecture/adr/SEC-0079-indexation-java-depuis-windows-par-wsl2.md).

## Principe

Sous Windows natif, scip-java est `BLOCKED` : son exécution sous AppContainer n'est pas qualifiée. La voie WSL2 ne
lève pas ce blocage. Elle fait tourner scip-java dans une distribution WSL2, sous le bac à sable Linux (bubblewrap
et cgroup v2 délégué par systemd, réseau `DENY`), avec le jar de l'installation Windows (`lib/minos.jar`,
indépendant de la plateforme). L'installation Windows reste la seule source de vérité : elle vérifie l'artefact
reçu puis l'importe par la promotion atomique habituelle. La distribution n'exécute que le provider, sans
promotion ni rétention.

## Une seule orchestration, des runtimes routés

`ProjectIndexingUseCase.viaWsl` construit le cas d'usage d'indexation habituel avec un autre
`ProviderRuntimeManager`, fourni par le port `WslIndexingRoute` de `minos-application`. `minos-bootstrap`
l'implémente sous Windows (`LocalWslIndexingRoute`), et seulement depuis une installation empaquetée : la
distribution exécute le jar de l'installation, qu'un lancement depuis des répertoires de classes n'a pas.

Le gestionnaire rendu, `WslRoutedRuntimes` (`minos-runtime-local`, package `com.minos.runtime.local.wsl`), route
scip-java seul :

| Appel | scip-java | Autres providers |
|---|---|---|
| `inspect`, `list` | `READY` ou `BLOCKED` selon la sonde des prérequis, faite une fois par run | natif |
| `executor` | `WslIndexerExecutor` de la distribution résolue ; refus si la sonde a refusé | natif |
| `install` | refusé : scip-java s'installe dans la distribution par son utilisateur | natif |

Un projet polyglotte garde donc ses autres providers natifs.

## Prérequis et doctor

`WslPrerequisiteProbe` procède en trois étapes : `wsl.exe -l -v` (sortie UTF-16), puis un script constant dans la
distribution (systemd, bus utilisateur, contrôleurs `memory`, `pids` et `cpu` délégables, `bwrap`, JVM 21), puis
les contrôles du jar (Maven, JDK du projet, dépôt Maven désigné). `wsl.exe` n'est résolu que dans le répertoire
système de Windows.

La troisième étape est le mode sonde de la commande interne. `WslCommandJarProbe` lance
`wsl.exe -d <distribution> --exec java -cp <jar> <classe principale> internal wsl-worker --probe`, sans shell ni
scope systemd : la sonde ne fait que lire des fichiers. Dans la distribution, `LocalWslJarChecks` applique
`ScipJavaMavenOffline.mavenProblem`, `ScipJavaProjectJdk.problem` et `ScipJavaMavenOffline.repositoryProblem` sur
les réglages MINOS que lit aussi le worker, sans lancer Maven, et écrit une ligne `minos-wsl-probe-v1 <id> <état>
<action>` par contrôle (`WslJarProbeProtocol`). Une action qui cite un chemin est remplacée par l'action fixe du
contrôle, des deux côtés. Un jar qui ne répond pas, échoue ou dépasse 90 secondes laisse ses contrôles `UNKNOWN`.
Sans installation empaquetée, ou sans `wsl.exe`, la sonde du jar est `WslJarProbe.NOT_AVAILABLE`.

La route bloque sur tout contrôle `MISSING` et sur un contrôle de l'hôte `UNKNOWN`. Un contrôle du jar `UNKNOWN` ne
bloque pas : le worker applique les mêmes contrôles avant de lancer le provider. `minos doctor` affiche le même
rapport dans sa section `wslJava` (Windows seulement), par `HostDiagnosticsService.wslJava` et
`LocalWslPrerequisiteDiagnostics`.

## Lancement de la distribution

`WslIndexWorker` lance, par `ProcessWslCommandRunner` :

```text
wsl.exe -d <distribution> --exec systemd-run --user --scope -p Delegate=yes --unit=minos-wsl-<runId> -- java -cp <jar> <classe principale> internal wsl-worker <requête>
```

- L'environnement de `wsl.exe` est remplacé par la liste d'autorisation de `WslCommandEnvironment`, filtrée par
  `ProviderProcessEnvironment.sanitize(Map, Map)`, avec `WSLENV` vide : aucun réglage ni secret ne traverse.
- Aucun shell de connexion n'est utilisé : les réglages du worker se lisent dans la configuration MINOS de la
  distribution, comme pour une installation Linux native (`minos.scip.java.maven-repository`,
  `minos.scip.java.project-jdk`, avec repli sur `JAVA_HOME` pour le JDK).
- La requête et le bundle passent par un dossier d'échange privé sous `MINOS_HOME`, vu de la distribution par
  `/mnt/<lecteur>`.
- Le délai du worker est de 75 minutes (délai du provider plus démarrage et écriture du bundle). Au-delà, ou sur
  interruption, `systemctl --user stop minos-wsl-<runId>.scope` termine tous les processus du run et l'échange
  est supprimé.

## Côté Linux : le worker

La commande interne `internal wsl-worker` n'apparaît pas dans l'aide. `WslWorkerLaunch` (`minos-cli`) la relie au
port `WslIndexingWorker`, que `minos-bootstrap` n'offre que sous Linux : `run` pour une requête (`WslLinuxWorker`),
`probe` pour l'option `--probe` (`LocalWslJarChecks`). Le worker :

1. refuse tout autre provider que scip-java, une autre version que celle du jar, un projet inaccessible ;
2. refuse si le backend choisi par `strongestAvailableForManagedLocalProvider` ne prend pas en charge le provider
   local géré ou n'applique pas `DENY` (pas de cgroup délégué) ;
3. refuse si scip-java n'est pas prêt dans la distribution, en nommant le réglage manquant (dépôt Maven, JDK) ;
   aucun Maven n'est lancé avec réseau, ni dans ni hors du bac à sable ;
4. exécute le provider sous bubblewrap et `LinuxCgroupJob`, réseau `DENY`, puis écrit un bundle
   `minos-distributed-artifact-v3` ;
5. écrit une ligne d'état unique (`OK`, `REFUSED …` ou `FAILED …`) sans chemin absolu, et supprime son répertoire
   de run.

## Vérification et import côté Windows

`WslIndexerExecutor` :

1. calcule l'empreinte SHA-256 du projet et construit une `WorkerRequest` locale (source scellée
   `DistributedArtifactSource.LocalProject`, politique `DENY`) ;
2. fait accepter le bundle par `DistributedArtifactBundleStore` (format, chemins, bornes, SHA-256) ;
3. exige un manifeste identique à celui transporté, puis la provenance attendue par `WslArtifactProvenance` :
   format v3, même run, projet, scope, provider, version, worker et empreinte, `DENY` appliqué ;
4. recopie l'artefact sous `runs/<runId>/<provider>/<scope>/index.scip` et revérifie son empreinte ;
5. libère le bail du magasin et supprime le bundle avant de rendre l'artefact, en cas de succès comme d'échec.

Les formats v1 et v2 sont refusés par la voie, et le format v3 par `DistributedIndexerExecutor` (indexation
distante).

## Tests

| Sujet | Tests |
|---|---|
| Option du CLI | `IndexCommandViaWslTest`, `IndexViaWslSideEffectsTest` |
| Route et runtimes routés | `LocalWslIndexingRouteTest`, `WslRoutedRuntimesTest`, `WslIndexingRouteWiringTest` |
| Prérequis et doctor | `WslPrerequisiteProbeTest`, `LocalWslPrerequisiteDiagnosticsTest`, `DoctorCommandWslJavaTest` |
| Sonde du jar | `WslJarProbeProtocolTest`, `WslCommandJarProbeTest`, `LocalWslJarChecksTest`, `ScipJavaMavenOfflineTest` (Linux seulement) |
| Lancement de `wsl.exe` | `WslCommandEnvironmentTest`, `WslProcessCommandRunnerTest`, `WslWorkerProtocolTest` |
| Worker Linux et mode sonde | `WslLinuxWorkerTest` (exécution réelle sous le bac à sable : Linux seulement), `WslWorkerLaunchTest` |
| Transport et provenance | `DistributedArtifactManifestTest`, `DistributedIndexingWorkerRequestTest`, `WslArtifactProvenanceTest`, `DistributedIndexerExecutorProvenanceTest` |
| Import, délai, sorties | `WslIndexWorkerTest` |

Ces tests simulent la distribution (sorties de `wsl.exe` factices). Aucun test de CI n'exécute une vraie
distribution WSL2 : la qualification sur un poste réel, et ses mesures, sont consignées dans le dossier de capacité.

## Le build dans le bac à sable Linux

Deux règles du plan et du bac à sable tiennent compte d'une distribution réelle :

- le JDK désigné par `minos.scip.java.project-jdk` est le `JAVA_HOME` et la première entrée du `PATH` du build
  (`ScipJavaProcessPlanFactory`) : le wrapper `javac` de scip-java lance `java` depuis le `PATH`, et le `java` par
  défaut d'une distribution peut vivre hors des racines montées (`/usr`, `/bin`, `/lib`, `/lib64`, `/sbin`) ;
- chaque processus du bac à sable reçoit `JAVA_TOOL_OPTIONS` avec `-XX:MaxRAM` égal à la mémoire du job et
  `-XX:ActiveProcessorCount` égal à ses processeurs, au plus ceux de l'hôte
  (`LinuxBubblewrapWorkerSandboxBackend.boundedJavaToolOptions`), après les options du plan : sans cela une JVM se
  dimensionne sur l'hôte et ses réservations atteignent la limite d'espace d'adressage par processus.

Tests : `ScipJavaProcessPlanFactoryProjectJdkTest`, `LinuxSandboxJvmBoundsTest`,
`LinuxBubblewrapWorkerSandboxBackendTest` (Linux).

La traçabilité exigence → preuve, les écarts et la qualification sont tenus dans le dossier de capacité
[indexation-java-via-wsl](../architecture/diagrams/capacites/indexation-java-via-wsl.md).
