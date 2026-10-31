# Proposal

## Why

Le 2026-10-31, la mesure de la voie WSL2 (Ubuntu 24.04, MINOS 1.3.0 sous `systemd-run --user --scope -p Delegate=yes`, `workerSandbox[managedLocalProvider]: linux-bubblewrap-cgroup2-v5 AVAILABLE`, scip-java `READY`, projet `fixtures/java/java-simple`) montre que le bac à sable Linux rend scip-java inutilisable avec deux JDK courants :

1. **JDK des paquets Debian/Ubuntu** (`apt install openjdk-21-jdk`, `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`) : scip-java échoue sur `java.lang.InternalError: Error loading java.security file`. Le JDK Debian lie chacun de ses fichiers de configuration vers `/etc/java-21-openjdk/…` (et `lib/security/cacerts` vers `/etc/ssl/certs/java/cacerts`) ; avec un egress `DENY`, le bac à sable ne monte rien de `/etc`.
2. **JDK autonome hors de `/usr`** (Temurin extrait dans le profil) : code 127, `java: error while loading shared libraries: libjli.so`. Le bac à sable ne monte que le fichier `bin/java` cité par la commande, pas le JDK.

Le bac à sable Windows accorde déjà la lecture de la racine nommée par `JAVA_HOME` ; le bac à sable Linux n'a pas d'équivalent. Aucun test n'exécute de provider réel en CI, et ni la documentation ni `minos doctor` ne disent qu'un JDK doit être autonome sous `/usr` : la capacité est annoncée sans être vraie.

## What Changes

- Le bac à sable Linux monte **en lecture seule** la racine réelle du JDK nommée par `JAVA_HOME` ou `JDK_HOME` dans l'environnement du provider (les mêmes clés que le bac à sable Windows).
- Il monte aussi, en lecture seule et **fichier par fichier**, la cible exacte de chaque lien symbolique de ce JDK qui sort de ce qui est déjà monté, à la seule condition que cette cible appartienne à une liste fermée de configuration Java de `/etc` (`/etc/java`, `/etc/java-…`, `/etc/javaN…`, `/etc/ssl/certs`, `/etc/pki/java`, `/etc/pki/ca-trust/extracted`). `/etc` n'est jamais monté en entier ; une cible hors de la liste n'est jamais montée.
- Une racine trop large (racine du système de fichiers, répertoire de premier niveau, répertoire personnel ou un de ses ancêtres, `MINOS_HOME`, `/etc`, `/proc`, `/sys`, `/dev`, `/run`) est **refusée avant le lancement** par un message actionnable sans chemin absolu.
- La qualification du backend déclare cette règle (`LINUX_TOOLCHAIN_HOME_READ_ONLY_ROOT_AND_ALLOWLISTED_ETC_LINK_TARGETS`).
- `docs/user/` décrit les prérequis Linux du JDK et de Maven pour scip-java.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `confinement-code-non-fiable` : trois exigences ajoutées (racine du JDK montée en lecture seule ; cibles de liens de configuration bornées à une liste fermée de `/etc` ; racine trop large refusée avant lancement).

## Hors périmètre

- **Maven installé par paquet** (`apt install maven`) : il lie `/usr/share/maven/conf` vers `/etc/maven` et n'est nommé par aucune variable transmise au provider ; il reste non pris en charge et la documentation le dit. Un Maven d'archive installé sous `/usr` (par exemple `/usr/local`) convient. Le signaler dans `minos doctor` est un suivi distinct.
- Monter `/etc` en entier, ou un répertoire de `/etc` entier, pour quelque raison que ce soit.
- Les autres variables d'outillage (`DOTNET_ROOT`, `CARGO_HOME`, `RUSTUP_HOME`, `COURSIER_CACHE`) : aucun provider Linux ne les exige au HEAD.
- **C3 — résolution des greffons Maven sous egress `DENY`** : une fois le JDK visible, l'indexation de `java-simple` échoue à l'étape suivante (`maven-clean-plugin … could not be resolved`), car le bac à sable ne montre aucun dépôt Maven local et `DENY` est voulu (`minos-runtime-local/src/main/java/com/minos/runtime/local/process/IndexerProcessPlanFactory.java:17-30`). Décider comment rendre un dépôt de greffons vérifié visible au build est un changement distinct ; ce changement ne rend donc pas scip-java natif Linux utilisable de bout en bout, il lève les couches C1 et C2.
- Le bac à sable Windows (inchangé).

## Impact

- **Modules du reactor** : `minos-runtime-local` (`sandbox/LinuxBubblewrapWorkerSandboxBackend`). Aucune dépendance ajoutée, aucun type exporté nouveau (règle A10).
- **Surfaces publiques** : la liste `limitations` de la qualification du backend Linux gagne une entrée (additif, `minos doctor --format json`). CLI, API Java, MCP, IntelliJ, NEXUS : aucun changement de contrat.
- **ADR** : aucun nouvel ADR. La règle précise le montage en lecture seule déjà décidé par SEC-0070 (Linux) et aligne Linux sur l'octroi de racine d'outillage du bac à sable Windows. Elle rend effective la voie WSL2 de l'ADR SEC-0074 (PR #626).
- **Gates à rejouer** : `scripts/remediation/check-mnd.py` (littéraux `addReadOnlyFile`, `managedRuntimeRoot`, `LINUX_EXACT_FILE_BIND_FOR_EXTERNAL_PROVIDER_ARGUMENTS`), `scripts/remediation/check-post-mne.py` (`relative.getNameCount() < 2`), `scripts/remediation/check-minos-01.py`, `scripts/architecture/check-private-io.py` (aucune écriture nouvelle, journal sans cause), scope JaCoCo de `LinuxBubblewrapWorkerSandboxBackend` (`scripts/quality/check-jacoco.py:192`, mesuré par la CI Linux).
- **Plateformes** : propre à Linux ; Windows a déjà l'équivalent (`WindowsAppContainerWorkerSandboxBackend`, clés d'outillage).
