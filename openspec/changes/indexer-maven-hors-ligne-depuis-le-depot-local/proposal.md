# Proposal

## Why

Constat **C3**, mesuré le 2026-10-31 sous WSL2 Ubuntu 24.04 (MINOS lancé sous `systemd-run --user --scope -p Delegate=yes`, bac à sable `linux-bubblewrap-cgroup2-v5` qualifié, scip-java `READY`, projet `fixtures/java/java-simple`), avec le JDK du projet rendu visible au bac à sable par le changement `monter-le-jdk-du-projet-dans-le-bac-a-sable-linux` (branche `fix/monter-le-jdk-dans-le-bac-a-sable-linux`, constats C1 et C2) : la JVM de scip-java et Maven démarrent, puis le build échoue sur

```text
Plugin org.apache.maven.plugins:maven-clean-plugin:3.2.0 … could not be resolved
repo.maven.apache.org: Temporary failure in name resolution
```

Sous Linux natif, scip-java ne peut donc indexer **aucun** projet Maven, alors que `minos doctor` le déclare `READY`. Les causes, revérifiées au HEAD (`design.md`) :

1. l'egress du provider est `DENY`, par décision (`IndexerProcessPlanFactory.java:17-30`) : Maven ne télécharge rien, et c'est voulu ;
2. aucun dépôt Maven local n'est visible du bac à sable : `LinuxBubblewrapWorkerSandboxBackend` ne monte que les racines système, l'outil géré et les dossiers du run ;
3. `HOME` n'est pas transmis au provider sous Linux (`ProviderProcessEnvironment`, clé de la seule liste Windows) et le plan scip-java ne déclare aucune variable ;
4. le `MAVEN_OPTS=-Dmaven.repo.local=…` que la documentation du runtime Docker présente comme confinement de Maven n'atteint aucun provider : il est filtré par la liste d'autorisation, et le plan d'administration Docker ne lance aucun provider (ADR SEC-0074, PR #626).

## What Changes

- **Maven du build analysé s'exécute toujours hors ligne.** Le plan scip-java Linux déclare `MAVEN_ARGS` : `--offline`, un dépôt local inscriptible propre au run (`-Dmaven.repo.local=<run>/maven-repository`) et, s'il est configuré, le dépôt de l'utilisateur en **queue de chaîne en lecture seule** (`-Dmaven.repo.local.tail=…`, Maven ≥ 3.9.0). L'egress reste `DENY` ; aucun téléchargement n'a lieu à l'indexation.
- **Le dépôt local de l'utilisateur est monté en lecture seule, et lui seul.** Par défaut `<user.home>/.m2/repository` du processus MINOS, ou le répertoire désigné par une configuration MINOS explicite. Le répertoire exact est monté ; jamais `~/.m2`, ni `settings.xml`, ni `settings-security.xml`, ni le profil. MINOS ne lit pas `settings.xml`.
- **Le plan déclare ses racines en lecture seule** (`IndexerProcessPlan`, ajout additif) ; le bac à sable Linux les monte en `--ro-bind`, le bac à sable Windows en accorde la lecture (parité ; scip-java reste `BLOCKED` sous Windows natif, ADR SEC-0074).
- **Refus avant lancement** d'une racine de dépôt trop large (racine, premier niveau, répertoire personnel ou un de ses ancêtres, `.m2` lui-même, `MINOS_HOME`, `/etc`, `/proc`, `/sys`, `/dev`, `/run`) ou contenant un blanc, par un message actionnable sans chemin absolu.
- **`minos doctor` dit vrai.** Sous Linux, scip-java n'est `READY` que si un Maven ≥ 3.9.0 est trouvé dans le `PATH` sous une racine montée par le bac à sable (version lue sur disque, sans exécuter Maven) **et** si le dépôt local existe ; sinon un diagnostic `machine prerequisite (not shipped by MINOS)` nomme le manque et la marche à suivre (exécuter une fois le build du projet hors de MINOS, par exemple `mvn dependency:go-offline`).
- **Documentation** : prérequis Linux de scip-java (Maven, dépôt local pré-rempli, aucun téléchargement à l'indexation) ; la section « Maven et staging Java » du runtime Docker est corrigée.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `confinement-code-non-fiable` : deux exigences ajoutées (racines en lecture seule déclarées par le plan, montées ou accordées en lecture seule ; racine de dépôt trop large refusée avant lancement).
- `installation-des-providers` : trois exigences ajoutées (Maven du build analysé hors ligne avec dépôt de run et queue en lecture seule ; dépôt de l'utilisateur sans `settings.xml` ; état de scip-java sous Linux conditionné à Maven ≥ 3.9 et au dépôt local).

## Hors périmètre

- **Toute sortie réseau du build analysé**, y compris une phase de résolution séparée avec egress `ALLOW` : écartée (`design.md`, option B). La rouvrir exige un ADR distinct.
- **Un dépôt de greffons fourni et vérifié par MINOS** : écarté comme solution (il ne couvre ni les dépendances ni les greffons déclarés par le projet) ; il reste une amélioration possible des greffons par défaut.
- Le Maven géré par MINOS sous Linux (aujourd'hui installé sous Windows seulement) ; Maven de paquet (`apt install maven`, `/etc/maven`) : toujours non pris en charge (changement `monter-le-jdk-du-projet-dans-le-bac-a-sable-linux`).
- La classification de la sortie d'erreur du provider (texte non fiable) en diagnostic : un dépôt incomplet donne l'échec générique du provider, qui renvoie au journal du run ; la documentation dit comment compléter le dépôt.
- Le retrait de `MAVEN_OPTS` des fichiers Compose (`docker/compose-mcp.*.yaml`, figé par `M29DockerAdministrationContractTest`) : sans effet tant que le plan d'administration ne lance aucun provider ; suivi distinct.
- Gradle, sbt, Bazel : scip-java n'est qualifié que sur Maven.
- La levée du blocage de scip-java sous Windows natif (ADR SEC-0074, point 2).

## Impact

- **Modules du reactor** : `minos-runtime-local` (`process/IndexerProcessPlan`, `sandbox/LinuxBubblewrapWorkerSandboxBackend`, `sandbox/WindowsAppContainerWorkerSandboxBackend`) ; `minos-provider-scip` (`runtime/plan/ScipJavaProcessPlanFactory`, `runtime/install/ManagedScipProviderRuntimeManager`). Aucune dépendance nouvelle, direction des dépendances inchangée.
- **Surfaces publiques** : diagnostics additionnels de `minos doctor` et `minos tools verify` (texte et JSON, additif) ; une clé de configuration nouvelle (emplacement du dépôt Maven). API Java, MCP, IntelliJ, NEXUS : aucun changement de contrat.
- **ADR** : **nouvel ADR requis**, nommé `SEC-0075` sous réserve du prochain numéro libre (SEC-0074 est pris par la PR #626) : « le build analysé lit le dépôt Maven local de l'utilisateur en lecture seule, hors ligne ». Il précise SEC-0070 (ce qu'un bac à sable peut lire des données de l'utilisateur) et met en œuvre le critère « dépôt Maven visible du bac à sable sans sortie réseau » du point 3 de SEC-0074.
- **Gates à rejouer** : `scripts/remediation/check-mnd.py` (`addReadOnlyFile`, `managedRuntimeRoot`), `scripts/remediation/check-post-mne.py`, `scripts/remediation/check-minos-01.py`, `scripts/architecture/check-private-io.py` (dépôt de run créé sous le run), `scripts/architecture/check-module-boundaries.py` ; scopes JaCoCo de `LinuxBubblewrapWorkerSandboxBackend` et du provider SCIP (`scripts/quality/check-jacoco.py`) ; `scripts/docs/check-current-docs.py`, `scripts/docs/product-facts.py --check`.
- **Dépendance d'ordre** : l'implémentation suppose le changement `monter-le-jdk-du-projet-dans-le-bac-a-sable-linux` fusionné (sans JDK visible, C1/C2 masquent C3).
- **Plateformes** : Linux (cas mesuré) ; Windows aligné sur la règle de montage, non exercé de bout en bout tant que scip-java y est `BLOCKED`.
