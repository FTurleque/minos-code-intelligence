# Design

## Context

Constat du superviseur de code (fin de tour) sur deux fichiers de production de plus de 800 lignes ; voir `proposal.md` (Why). Le dépôt n'a pas de limite de taille : le seuil est celui du superviseur, et l'audit du 10 octobre 2026 écarte tout seuil bloquant (AUD-QUA-07). Ce design tranche **quelles responsabilités secondaires se détachent proprement**, à quel prix pour les gates et pour la couverture, et ce qui reste volontairement en place.

`PRIVATE` désigne `minos-engine/src/main/java/com/minos/io/PrivateLocalStorage.java` ; `MANAGER` désigne `minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/install/ManagedScipProviderRuntimeManager.java`.

## État vérifié au HEAD

### Base de lecture

HEAD `f7c6cb13` (`develop` du 2026-10-28, PR #586). `wc -l` : `PRIVATE` **960** lignes, `MANAGER` **1 005**. Tous les numéros de ligne ci-dessous sont relus à ce HEAD.

`PRIVATE` ne lance plus aucun processus : le port `PlatformAccessControl` (`minos-engine/src/main/java/com/minos/io/PlatformAccessControl.java`) est fourni par `minos-runtime-local` (`com.minos.runtime.local.acl`, `WindowsIcacls.java`, `WindowsIcaclsAccessControl.java`), et `PRIVATE` ne fait que le découvrir (`PRIVATE:803-853`). La table A12 est vide (`PROCESS_LAUNCHERS: dict[str, int] = {}`, `scripts/architecture/check-module-boundaries.py:580`). Aucune extraction de ce changement ne touche au mécanisme d'ACL ni à A12.

### Classes de production de plus de 800 lignes

Décompte au HEAD (`wc -l` de tous les `*/src/main/**/*.java`, plugin compris) : **six** fichiers, pas deux.

| Fichier | Lignes | Traité ici |
|---|---|---|
| `minos-storage/src/main/java/com/minos/storage/codec/SnapshotBinaryCodecSupport.java` | 1 081 | non (Q5) |
| `minos-engine/src/main/java/com/minos/orchestration/IndexingRunExecutor.java` | 1 025 | non (Q5) |
| `MANAGER` | 1 005 | oui |
| `PRIVATE` | 960 | oui |
| `minos-runtime-local/src/main/java/com/minos/runtime/local/sandbox/WindowsAppContainerWorkerSandboxBackend.java` | 817 | non (Q5) |
| `minos-runtime-local/src/main/java/com/minos/runtime/local/sandbox/LinuxCgroupJob.java` | 813 | non (Q5) |

Le superviseur ne relève que les fichiers touchés pendant la session qui l'a déclenché ; ce changement garde ce périmètre (décision Q5).

### `PRIVATE` — ce qui se détache et ce qui reste

Couverture des blocs : **non mesurée au HEAD** (la tâche 1.2 la relève). Les commits qui touchent `PRIVATE` depuis la dernière mesure locale (`6804b40d`, `c08cb379`, `cff3d33a`) ont changé la sonde du propriétaire et retiré le lancement d'`icacls`.

| Bloc | Lignes | Taille | Appelants | Tests qui l'atteignent |
|---|---|---|---|---|
| Diagnostic d'un refus d'écriture (DENY explicite) : `WRITE_PROTECTED_MESSAGE`, `WRITE_RIGHTS`, les deux `explainDenied`, `writeProtected`, `carriesWriteDenyForOwner`, `namesTheProcessUser` | `PRIVATE:621-698` | 78 | six appels internes (`:200`, `:210`, `:224`, `:242`, `:519`, `:587`) | `WriteDenyClassificationTest:34-58` (`carriesWriteDenyForOwner`) |
| Propriétaire du processus : cache `volatile currentOwner`, `currentOwner`, `ownerUnverifiable`, `probeOwner` | `PRIVATE:436-481` | 46 | `ensurePrivateDirectory` (`:148`) ; `verifyOwnedByCurrentUser` (`:431`), qui reste avec sa Javadoc (`:417-434`) et que `SandboxLauncherScript` (`minos-runtime-local`, `:108`, `:117`, `:141`, `:171`, `:242`) appelle | `PrivateLocalStorageSharedLocationTest:71,81,97`, `PrivateLocalStorageWindowsAclTest:473,490` (`probeOwner`) |
| Découverte du port de plateforme : `DiscoveredPlatformAccessControl`, `platformAccessControl`, `discoverPlatformAccessControl`, `refusing` | `PRIVATE:803-853` | 51 | `CapabilityProbe.real()` (`:906`, `:911`) | `PlatformAccessControlDiscoveryTest:40,50,62,73,201` |
| Crochet d'essai : interface `CapabilityProbe` et son `real()` (le `ThreadLocal`, `useForTesting` et `resetCapabilityProbeForTesting` restent) | `PRIVATE:855-915` | 61 | `CAPABILITY_PROBE` (`:917-918`), lu par `aclView` (`:795-797`), `supportsPosix` (`:799-801`) et le durcissement (`:583`, `:592`) | à retyper : `FakeCapabilityProbe:11,23`, `PrivateLocalStorageStreamsTest:146-147`, `PrivateLocalStorageWindowsAclTest:203-205,382,394,399`, `PlatformAccessControlDiscoveryTest:163-164,187` (`minos-engine`) ; `MinosApplicationTest:150` (`minos-bootstrap`) ; `DurableAtomicFileTest` et `PrivateLocalStorageTest` n'utilisent que `FakeCapabilityProbe` |
| Garde des emplacements partagés (H23 : `refuseSharedLocation`, `userHome`) | `PRIVATE:160-179` | 20 | `ensurePrivateDirectory` (`:147`) | — |
| Cœur : création, durcissement POSIX et ACL, vérification, flux privés, `PROTECTED_LOCATIONS` | le reste | ~704 | sept modules de production (`minos-app`, `minos-application`, `minos-engine`, `minos-integration-git`, `minos-provider-scip`, `minos-runtime-local`, `minos-storage`), plus les tests de `minos-bootstrap` | — |

- **Le diagnostic de refus** est fait de fonctions pures sur des entrées ACL, sauf la surcharge à chemins, qui lit la vue ACL par `aclView(location)` (`:689`), donc par le `ThreadLocal` du crochet d'essai (`:795-797`) : l'extraction lui passe la vue en paramètre.
- **La sonde du propriétaire** n'utilise **pas** le crochet d'essai (`probeOwner` appelle `Files.createTempFile` directement, `:465`, `:471`) : aucune dépendance cachée.
- **La découverte du port** n'a aucune dépendance vers le reste de la classe ; elle charge le service par `PrivateLocalStorage.class.getClassLoader()` (`:808`), jamais par le chargeur de contexte (règle A16) : c'est le « propre chargeur » de la classe qui découvre, qu'exigent `openspec/specs/stockage-prive-et-secrets/spec.md:447` et ARCH-0068 (`:189`). Son test porte déjà le nom de la classe d'accueil proposée (`PlatformAccessControlDiscoveryTest`).
- **`CapabilityProbe.real()`** appelle `platformAccessControl()` (`:906`, `:911`) : extraire `CapabilityProbe` sans la découverte ferait dépendre le type extrait de `PrivateLocalStorage`. Les deux partent ensemble.
- **La garde H23** est trop petite (20 lignes) et liée à `ensurePrivateDirectory` : **elle reste**.
- **Le cœur** reste : y séparer POSIX et ACL demanderait de défaire `harden` et `PROTECTED_LOCATIONS` (`:707`), qui partagent l'état par JVM.

Estimation : 960 − 78 − 46 − 51 − 61 + ~6 de délégation − ~4 d'imports ≈ **726 lignes**. Cible de la tâche 2.5 : ≤ 740. Sans la découverte du port : ~777, trop près de 800 et avec une dépendance de `CapabilityProbe` vers `PrivateLocalStorage`. Sans la sonde du propriétaire (Q6) : ~772.

### `MANAGER` — ce qui se détache et ce qui reste

Couverture : **non mesurée au HEAD** par bloc. Mesure de CI de la classe entière (commentaire de `scripts/quality/check-jacoco.py:119-120`, runs du 2026-10-15 sur `develop` `83dd1fbb`) : Linux 0,711 / 0,572, Windows 0,668 / 0,548. Depuis cette mesure, cinq commits touchent `MANAGER` (`b9de8402`, `19fd0515`, `aef4fa4e`, `ded956fe`, `ffb243e3`, dont la migration vers les primitives d'E/S) : le plancher de classe 0,61 / 0,49 porte sur un code qui a changé depuis. Le dernier (`ffb243e3`) fait passer trois messages par `leafName` et ajoute une ligne d'import.

| Bloc | Lignes | Taille | Appelants | Tests qui l'atteignent |
|---|---|---|---|---|
| Classpath de scip-java : `scipJavaFetchCommand`, `fetchVerifiedClasspath`, `resolvedJars`, `jarList`, `installedClasspathVerifies`, `installClasspath`, `swapIn`, `DirectoryMover`, `restore`, `copyJar` | `MANAGER:367-517` | 151 | `installJava` (`:356-360`) | `ClasspathLockTest`, `ScipJavaClasspathInstallationTest` (`installClasspath`, `installedClasspathVerifies`), `ManagedToolInstallationFilesTest:179,194,206,224` (`swapIn`), `ManagedScipProviderRuntimeManagerTest` et `…RuntimeTest` (`scipJavaFetchCommand`, `resolvedJars`) |
| Extraction bornée d'archives (`extractZipBounded`, `extractDistribution`, `openExtractedFile`) | `MANAGER:855-914` | 60 | `ensureMaven`, `ensureNode` (`extractDistribution`), amorçage (`:774`) | `ManagedToolInstallationFilesTest`, `ManagedScipProviderRuntimeManagerRuntimeTest` |
| Gestes d'installation : `writeNewFile` (`:519-524`), `run` (`:970-989`), `deleteRecursively` (`:991-993`), `moveFile` (`:996-999`), `moveDirectory` (`:1001-1004`) ; tous `private static` sauf `run` | `MANAGER:519-524`, `:970-1004` | 41 | tout le fichier | `InstallationEnvironmentIsolationTest:61`, `ManagedRuntimeCommandRunnerTest:39` (`run`) |
| Acquisition épinglée de Coursier, Maven, Node (`ensureCoursier`, `ensureMaven`, `ensureNode`, racines et exécutables, `coursierExecutable`) | `MANAGER:557-661`, `:916-920` | ~110 | `installJava`, `installTypeScript`, `seedComponents` | couverte à 11 % des lignes sur la dernière mesure locale (2026-10-18) |
| Amorçage depuis la charge utile embarquée (`SeedOutcome` … `originDiagnostic`) | `MANAGER:663-853` | 191 | `inspect*`, `install*` | `EmbeddedToolsSeedingTest` |
| Inspection, installation, exécuteur, diagnostics, chemins | le reste | ~450 | `DefaultMinosApplicationComposer.java:78` (`new ManagedScipProviderRuntimeManager(home)`) | — |

- **Extraction bornée** : sans état ; deux appels bruts `Files.createDirectories` (`:876`, `:879`), qui sont **exactement** le `max` 2 de l'entrée de l'allowlist. Ne nomme aucun type de `minos-runtime-local`. Coupe très propre.
- **Classpath** : presque entièrement statique ; les deux méthodes d'instance lisent chacune un chemin du manager : `installedClasspathVerifies` le listing (`scipJavaClasspathFile()`, `:428`), `installClasspath` la racine (`scipJavaClasspathRoot()`, `:449`) ; les deux deviennent des paramètres. Le bloc utilise trois constantes privées : `CLASSPATH_FILE` (déclarée `:75`, lue `:468` et par `scipJavaClasspathFile()`), `MAX_CLASSPATH_JAR_BYTES` (`:84`, lue par `copyJar`) et `MAX_INSTALL_LOG_LINE_CHARS` (`:48`, lue par le lecteur du journal, `:393-394`). Les deux dernières partent avec le bloc ; `CLASSPATH_FILE` y est déplacée en visibilité de package et le manager la référence. Le bloc nomme **deux types de `minos-runtime-local`** : `BoundedProcessOutput.DEFAULT_MAX_BYTES_PER_STREAM` (`:391`) et `CommandLocator.isWindows()` (`:431`) ; voir D8.
- **Gestes d'installation** : partagés par tous les blocs ci-dessus ; c'est ce qui rend les autres extractions possibles sans dépendance en retour vers le manager. `run` nomme `InstallationProcessEnvironment` (`:974`) et `BoundedProcessOutput` (`:978`).
- **Acquisition** : dépend de `toolsRoot`, de `source()` et de `origins` ; Coursier refusé hors Windows (`:560-562`). Couverte à 11 % : la déplacer en ferait du code neuf très sous le seuil de 80 % de SonarCloud.
- **Amorçage** : 191 lignes, le plus couplé (acquisition, `installJavaWindowsRuntime`, six accesseurs de chemins).
- **`ManagedPolyglotScipRuntimeManager` et `ManagedScipPythonRuntimeManager`** dupliquent `run` et `moveDirectory` : constaté, non traité.

### Ce que les gates et les cliquets exigent de ces fichiers

| Garde | Ce qu'il lit | Effet de l'extraction |
|---|---|---|
| `scripts/architecture/check-private-io.py` + `private-io-allowlist.json` (**24 entrées, somme des `max` = 56**) | un maximum **par fichier et par méthode brute** ; un appel hors liste (`:448-450`) ou un maximum supérieur au décompte (`:456-460`) échoue, la liste ne fait que se resserrer (`check-private-io.py:48`, capacité `stockage-prive-et-secrets`) ; l'édition de la liste demande une confirmation (hook `.claude/hooks/guard_paths.py:43`) | `PRIVATE` `Files.createTempFile` `max` 3 = `:222` + `:465` + `:471` : 1 reste, 2 vont à `ProcessOwner`. `MANAGER` `Files.createDirectories` `max` 2 = `:876` + `:879` : l'entrée change de clé de fichier. Après : **25 entrées**, somme des `max` inchangée à **56**. |
| Règle Archgate `private-local-storage-keeps-its-entries` (`docs/architecture/adr/SEC-0061-deux-niveaux-de-confidentialite-pour-l-ecriture-de-fichiers-prives.rules.ts:13`, `ENTREES_DECLAREES = 5`) | exactement cinq entrées de la liste pour `PrivateLocalStorage.java` | inchangé : l'entrée `Files.createTempFile` de `PRIVATE` reste (`max` 1, `:222`) ; les cinq méthodes restent listées. |
| Texte de SEC-0061 (Accepted) | « Un appel brut de plus dans ces classes échoue au garde » (`:78`) ; « les maxima de `PrivateLocalStorage` ne bougent pas » (`:92`, `:103`) | le maximum `Files.createTempFile` de `PRIVATE` passe de 3 à 1 et une entrée `ProcessOwner.java` s'ouvre, sans appel brut de plus. La lettre de `:92` et `:103` ne le prévoit pas ; SH-08 (`c08cb379`) l'a déjà fait passer de 4 à 3 avec l'entrée `WindowsIcaclsAccessControl.java` sans note dans l'ADR. Décision Q6 : note datée dans SEC-0061 (tâche 4.3). |
| `check-module-boundaries.py` A13 (`:586-603`, `ADAPTER_TYPES_NAMED_BY_ADAPTER` ; vérification `:843-860`, `named_type_ratchet_problems` `:750-780`, `types_named_by_module` `:714-747`) ; auto-test `test_check_module_boundaries.py:1417`, qui fige `CommandLocator` à 10 dans la table réelle | pour le couple (`minos-provider-scip`, `minos-runtime-local`), le **nombre de fichiers de `src/main/java`** du provider qui nomment chaque type par import simple ou statique ou par nom qualifié (commentaires et littéraux exclus, nom simple sans import non compté) ; un compte qui monte échoue (« a new use goes through a port, not into the table ») | `MANAGER` est compté pour `BoundedProcessOutput` (3), `CommandLocator` (10) et `InstallationProcessEnvironment` (3). Une extraction naïve ferait monter `BoundedProcessOutput` à 4 (gestes **et** classpath) et `CommandLocator` à 11 (classpath) : le gate échouerait. D8 garde les trois comptes. |
| `check-module-boundaries.py` A12 (`:580`, `:804-840`) | `ProcessBuilder` dans le domaine, le moteur et l'application | sans effet : table vide, aucune classe extraite de `PRIVATE` ne lance de processus ; `MANAGER` est un adaptateur, hors du champ d'A12. |
| `check-module-boundaries.py` A15 (`:1062-1192`) | tout type **public de premier niveau** de `src/main` est nommé par la production ou listé | `CapabilityProbe` devient public de premier niveau ; `PrivateLocalStorage` (même package) le nomme : aucune entrée à ajouter. Les autres classes nouvelles sont de visibilité de package. |
| `check-module-boundaries.py` A16 (`:974-998`) | tout `ServiceLoader.load` de production passe un chargeur explicite | la découverte garde la forme à deux arguments (D2). |
| `check-module-boundaries.py` A3, A8, A10, A14 | package par module, cycles, exports de `minos-runtime-local`, racines de packages | inchangés : mêmes packages et modules ; les types de `minos-runtime-local` nommés sont déjà exportés (A10). |
| `scripts/quality/check-jacoco.py` | préfixes de **noms de classes** (`startswith`, `:583-601`) ; `confined-io-primitives` = `com/minos/io/` (`:40-46`) ; `global` = `com/minos/` (`:19-24`) ; `m24-polyglot-provider-platform` (`:111-131`) nomme `.../install/ManagedScipProviderRuntimeManager` (`:121`) avec un plancher de classe 0,61 / 0,49 (`:126`) et un plancher agrégé 0,50 / 0,28 | `com/minos/io/` couvre les classes nouvelles de `PRIVATE` sans rien changer (le commentaire « 34 classes » de `:36` devient faux). Une classe nouvelle de `.../install/` **sort du scope m24** tant qu'elle n'y est pas déclarée, et le plancher du manager porte sur un code différent (D6). |
| `scripts/remediation/check-post-mne.py` (`:113-114`, `:225-226`, `:233-239`) | `MANAGER` contient `BoundedInputStream`, `BoundedLineReader`, `FileTreeOperations.deleteRecursively` ; n'a ni `.sorted(Comparator.reverseOrder()).toList()` ni `Files.readAllLines(log)` | les trois chaînes quittent `MANAGER` (classpath : `:390-394`, `:513` ; gestes : `:992`) ; le gate est repointé |
| `scripts/remediation/check-mnd.py` (`:60`, `:120-124`) | `MANAGER` contient `MAX_COURSIER_ARCHIVE_BYTES`, `source().acquire(`, `coursierArtifact(), archivePartial, MAX_COURSIER_ARCHIVE_BYTES` ; n'a pas `BodyHandlers.ofFile(archivePartial)` | inchangé : l'acquisition reste dans `MANAGER` (D7) |
| `scripts/quality/check-tools-manifest.py` (`:39`, `:223`) | `SCIP_JAVA_VERSION`, `SCIP_TYPESCRIPT_VERSION` de `MANAGER` | inchangé |
| `scripts/remediation/check-p0-p2.py` (`:130`, `:139`) | `PRIVATE` contient `attributes.isOther()` (`:377`, `:499`, `:564`, `:763`) | inchangé : `openNewTreeFile`, `privacyOf`, `hardenFile` et `requireType` restent |
| `quality/spotbugs-baseline/minos-engine.xml` ; `minos-provider-scip.xml` ; comparaison par `instanceHash`, qui dépend du nom de classe ; `code-audit.yml`, **hors checks exigés** | aucune alerte de `minos-engine.xml` ne nomme `PrivateLocalStorage` ; `minos-provider-scip.xml` : `MANAGER.extractZipBounded` (NP, `:50-73`), `gitBashForGit` (NP, `:74-97`), `addProjectJdkDiagnostics` (ENV, `:309-331`) | `minos-engine` : rien à rafraîchir, la comparaison stricte reste verte. `minos-provider-scip` : la base est **rafraîchie** après l'extraction (`.claude/rules/tests-java.md`) en vérifiant que seule l'alerte d'`extractZipBounded` change de classe, jamais masquée (`audit-qualite-code` : « Aucune alerte n'est masquée ») |
| SonarCloud (`FTurleque_atlas`, 80 % de couverture du nouveau code, par module ; rapports Linux et Windows, job `sonar` de `pr-ci.yml`) | lignes nouvelles du module | chaque classe nouvelle est entièrement « code neuf » : voir D5 |

`scripts/quality/check-runtime-dynamic-consistency.py:94` cite `PrivateLocalStorage` comme fait attendu dans `BoundedFileLease.java`, qui ne change pas. La règle Archgate d'ARCH-0017 (`ARCH-0017-mcp-stdio-read-only.rules.ts:37`) lit les appels à `PrivateLocalStorage` dans `minos-mcp`, qui ne changent pas. `scripts/history/` cite d'anciens chemins du manager et n'est pas exécuté. Aucun test n'atteint ces membres par réflexion. Aucun nom de classe nouvelle (`WriteDenyDiagnosis`, `ProcessOwner`, `PlatformAccessControlDiscovery`, `CapabilityProbe`, `BoundedZipExtraction`, `ManagedToolInstallation`, `ScipJavaClasspathInstallation`) n'existe comme fichier de production du dépôt.

Documentation qui nomme ces classes : `docs/architecture/arc42/06-vue-execution.md:122,151` et `docs/architecture/diagrams/seq-erreur-provider.md:38,67` (`inspect`, `install`, qui restent) ; `docs/architecture/diagrams/deployment-native.md:8` (nom de classe, qui reste) ; `docs/developer/quality/code-audit-constats.md:39` (`ENV_USE_PROPERTY_INSTEAD_OF_ENV` sur le manager, « ligne 238 », périmée : la lecture de `JAVA_HOME` est `MANAGER:296`) ; `docs/developer/quality/code-audit.md:94` (`addProjectJdkDiagnostics`, qui reste) ; `docs/developer/quality-gates.md:130` (« seuls les appels de `LinuxCgroupJob` (cgroupfs) et de `PrivateLocalStorage` sont définitifs » : déjà inexact au HEAD, l'entrée durable `WindowsIcaclsAccessControl.java` n'y figure pas, et `ProcessOwner.java` s'y ajoute) ; `docs/project/audits/2026-10-10/SUIVI.md:861` (ligne de ce changement dans l'audit en cours : AUD-ARC-05, 1 004 lignes, 23 entrées, `WindowsDacl`, à réécrire sur l'état de ce design). Hors périmètre mais périmés, à signaler : `docs/project/governance/STATUS.md:126` et ARCH-0068 (`:66`, `:137`) nomment `PrivateLocalStorage` comme seul lanceur de processus du moteur, alors que la table A12 est vide.

### Recouvrements avec les capacités et les changements actifs

Capacités dont les exigences doivent rester satisfaites, sans modification : `frontieres-de-modules` (« Les gates et la couverture suivent les classes déplacées », le cliquet A13 des types d'un adaptateur nommés par un autre, « Une classe publique de production est référencée par la production ou déclarée ») ; `stockage-prive-et-secrets` (« La liste de tolérance des écritures brutes ne fait que se resserrer », « Le message d'une primitive d'E/S privée ne contient pas de chemin absolu », « Un refus conditionnel n'est jamais retiré par le durcissement ») ; `installation-des-providers` ; `audit-qualite-code` (« Aucune alerte n'est masquée pour obtenir un résultat vert »).

Changements actifs (`openspec list`, 16 changements dans `develop`) et changements en cours hors de `develop` :

- **`injecter-le-staging-du-cycle-scip`** (SH-06, spécifié sur la branche `docs/specifier-le-staging-injecte-du-scip`, `8568cf94`, absent de `develop`) : retire l'entrée stockage d'A13 (`check-module-boundaries.py:600-602`), ajoute une table `ADAPTER_COUPLING_REASONS` qui justifie le couple `minos-provider-scip` → `minos-runtime-local`, et modifie l'exigence A13 de `frontieres-de-modules`. Le lot `MANAGER` (section 3) dépend du même décompte A13 et touche le même provider : **voir D9**.
- `executer-scip-java-sur-classpath-verifie` (1.5, 2.3), `surveiller-les-outils-embarques` (3.4, 4.3), `retirer-les-jalons-des-identifiants` (4.2), `rendre-l-echec-de-scip-java-sous-windows-actionnable` (5.2), `migrer-l-installation-des-outils-scip-vers-les-primitives` (4.1), `verifier-le-proprietaire-des-dossiers-prives` (2.3), `proteger-la-dacl-des-dossiers-du-plugin` (3.3), `migrer-les-ecritures-d-artefacts-du-runtime-local` (5.1), `lire-le-registre-sans-ecrire` (4.3 à 4-B.2) : leurs tâches restantes sont des mesures manuelles, de la qualification, du Docker ou une clôture ; **aucune ne modifie `PRIVATE`, `MANAGER` ni `private-io-allowlist.json`**. Ceux qui citent des lignes du manager ou de `PRIVATE` sont à relire à leur archivage, sans conflit d'écriture.
- `retirer-les-jalons-des-identifiants` garde le nom de scope `m24-polyglot-provider-platform` ; si le scope est renommé plus tard, les préfixes ajoutés ici suivent.

### Non mesuré

Couverture des blocs au HEAD, sous Linux comme sous Windows ; couverture Sonar réelle (l'API ne donne rien pour une branche non principale de cette organisation) ; durée des suites de tests. Pour cette révision : `wc -l`, `git log 5ce7b79b..f7c6cb13` sur les deux fichiers, relecture des deux fichiers, des gates, de la liste de tolérance et de la règle Archgate de SEC-0061 ; **aucun build Maven**, aucun gate Java exécuté, aucun code modifié.

### Relevé à l'implémentation (2026-10-29, `develop` `617b73a3`)

- **1.1** : `wc -l` inchangé, `PRIVATE` 960 et `MANAGER` 1 005. `git log f7c6cb13..617b73a3` sur les deux fichiers et `check-module-boundaries.py` : cinq commits (`1e577b0f`, `065bc258`, `c073bb87`, `72cf8c5d`, `9b857f11`) ; seul `9b857f11` touche `MANAGER`, sur une ligne (`SCIP_JAVA_VERSION`), sans décaler aucun numéro de ligne de ce design. Comptes A13 du couple (`minos-provider-scip`, `minos-runtime-local`) : `BoundedProcessOutput` 3, `CommandLocator` 10, `InstallationProcessEnvironment` 3. `injecter-le-staging-du-cycle-scip` est archivé (`2026-10-28`) : la condition d'ordre de D9 est remplie.
- **1.2** (rapport `jacoco.xml` du module, poste Windows, JDK 25 ; Linux non mesuré localement, sa preuve est le job `Verify (ubuntu-24.04)` de chaque PR) :

| Classe d'origine, bloc | Lignes couvertes / total | Branches couvertes / total |
|---|---|---|
| `PRIVATE` entier | 306 / 346 | 136 / 170 |
| diagnostic de refus (`:621-698`) | 27 / 29 | 26 / 34 |
| propriétaire du processus (`:436-481`) | 20 / 20 | 8 / 10 |
| découverte du port (`:803-853`) | 14 / 16 | 4 / 4 |
| `CapabilityProbe` (`:855-915`) | 9 / 9 | 0 / 0 |
| reste | 236 / 272 | 98 / 122 |

Rapports `jacoco-modules-Linux` et `jacoco-modules-Windows` du run `PR Validation` 40872361738 (`develop` `617b73a3`, avant découpage), mêmes sommes :

| Classe d'origine, bloc | Linux lignes | Linux branches | Windows lignes | Windows branches |
|---|---|---|---|---|
| `PRIVATE` entier | 290 / 346 | 134 / 170 | 306 / 346 | 138 / 170 |
| diagnostic de refus (`:621-698`) | 19 / 29 | 19 / 34 | 27 / 29 | 28 / 34 |
| propriétaire du processus (`:436-481`) | 18 / 20 | 8 / 10 | 20 / 20 | 8 / 10 |
| découverte du port (`:803-853`) | 14 / 16 | 4 / 4 | 14 / 16 | 4 / 4 |
| `CapabilityProbe` (`:855-915`) | 4 / 9 | 0 / 0 | 9 / 9 | 0 / 0 |
| reste | 235 / 272 | 103 / 122 | 236 / 272 | 98 / 122 |
| `MANAGER`, somme des méthodes | 376 / 520 | 170 / 292 | 362 / 520 | 175 / 292 |
| gestes d'installation | 28 / 28 | 5 / 6 | 28 / 28 | 5 / 6 |
| extraction bornée | 30 / 30 | 13 / 14 | 30 / 30 | 13 / 14 |
| classpath de scip-java (avec `DirectoryMover`) | 77 / 83 | 25 / 30 | 79 / 83 | 25 / 30 |
| reste du `MANAGER` | 241 / 379 | 127 / 242 | 225 / 379 | 132 / 242 |

Pour `MANAGER`, les blocs sont sommés par compteurs de méthodes (le total diffère de quelques lignes de la somme par `<line>`). Le diagnostic de refus et `CapabilityProbe` sont moins couverts sous Linux, leurs chemins ACL n'y tournant pas ; `com/minos/io/` est jugé par son préfixe de package. Planchers de D6 contre la plus basse des deux mesures : reste du `MANAGER` 0,594 (Windows) / 0,525 (Linux), plancher 0,52 / 0,44 ; `ScipJavaClasspathInstallation` 0,928 / 0,833 (Linux), plancher 0,85 / 0,75 ; `BoundedZipExtraction` 1,0 / 0,929, plancher 0,90 / 0,80 ; `ManagedToolInstallation` mesuré à 5/8 branches sous Linux après découpage, plancher 0,57. Les deux jobs `Verify` de #605 et #606 ont passé ces planchers.

- **1.3** : `git grep` des membres déplacés (`carriesWriteDenyForOwner`, `namesTheProcessUser`, `explainDenied`, `WRITE_PROTECTED_MESSAGE`, `probeOwner`, `currentOwner`, `ownerUnverifiable`, `discoverPlatformAccessControl`, `platformAccessControl()`, `CapabilityProbe` ; `run`, `moveFile`, `moveDirectory`, `writeNewFile`, `deleteRecursively`, `extractZipBounded`, `extractDistribution`, `scipJavaFetchCommand`, `fetchVerifiedClasspath`, `resolvedJars`, `swapIn`, `DirectoryMover`) dans `scripts/`, `.github/`, `docs/`, `quality/`, `.claude/`, `.archgate/`, `minos-intellij/` et les sources Java : aucun gate de plus que ceux de la table ci-dessus ; seules les lignes 616, 624 et 627 de `docs/project/audits/2026-10-10/SUIVI.md` citent `PrivateLocalStorage.currentOwner` et `probeOwner`, relevé daté d'une correction SpotBugs, laissé tel quel.

### Mesures après découpage (2026-10-29, poste Windows, JDK 25)

| Fichier | Lignes avant | Lignes après | Lignes couvertes avant → après | Branches couvertes avant → après |
|---|---|---|---|---|
| `PRIVATE` et ses quatre classes extraites | 960 | 724 (`PRIVATE`) | 306/346 → 307/347 | 136/170 → 136/170 |
| `MANAGER` et ses trois classes extraites | 1 005 | 751 (`MANAGER`) | 358/514 → 360/516 | 174/292 → 174/292 |

Aucune ligne ni branche couverte n'est perdue ; l'écart de total vient des lignes de délégation, toutes couvertes. Par classe après : `WriteDenyDiagnosis` 27/29, 26/34 ; `ProcessOwner` 20/20, 8/10 ; `PlatformAccessControlDiscovery` 14/16, 4/4 ; `CapabilityProbe` 9/9 ; `MANAGER` 225/377, 131/242 ; `ManagedToolInstallation` 28/28, 6/8 (Linux : 5/8 branches) ; `BoundedZipExtraction` 30/30, 13/14 ; `ScipJavaClasspathInstallation` 77/81, 24/28. Le manager garde ses parties les moins couvertes, d'où le plancher recalculé de D6. Linux : jobs `Verify (ubuntu-24.04)` des deux PR.

Reste hors lot : dans `MANAGER`, l'acquisition épinglée de Coursier, Maven et Node (couverte à 11 %) et l'amorçage depuis la charge utile (décision Q2) ; les quatre autres fichiers de plus de 800 lignes (décision Q5).

## Goals / Non-Goals

**Goals** : `PRIVATE` et `MANAGER` sous 800 lignes (cibles : ≤ 740 et ≤ 770, estimations ~726 et ~751) ; chaque classe issue du découpage a une seule raison de changer, ses tests dans son module et son entrée dans les gates ; aucun compte de cliquet qui monte ; zéro différence observable.

**Non-Goals** : un seuil de taille dans un gate ; un nouveau module ou package ; le mécanisme d'ACL de `minos-runtime-local` ou le port `PlatformAccessControl` ; la migration des appels bruts vers les primitives ; l'unification des managers frères ; la réduction ou la justification de la table A13 ; la réorganisation interne du cœur de `PRIVATE` ; les quatre autres fichiers de plus de 800 lignes (Q5).

## Decisions

### D1. La preuve d'un remaniement est une caractérisation

Aucun comportement ne change : les tests existants, verts avant et après sans modification d'attente, sont la preuve. Seuls changent, dans les tests, le nom de la classe qui porte le membre appelé et le nom de type de `CapabilityProbe`. Les gates littéraux et les listes de tolérance ont un vrai rouge : chaque tâche qui déplace un appel brut ou une chaîne lue par un gate conserve la sortie du gate en échec avant d'éditer sa liste.

### D2. `PRIVATE` : quatre extractions dans `com.minos.io`

Toutes dans le package et le module actuels (ADR 0068 : aucune racine nouvelle) :

- **`WriteDenyDiagnosis`** : `carriesWriteDenyForOwner` (visibilité de package, utilisée par `WriteDenyClassificationTest`), `namesTheProcessUser`, `writeProtected`, `WRITE_RIGHTS`, `WRITE_PROTECTED_MESSAGE` et les deux formes d'`explainDenied` ; celle à chemins reçoit un `Function<Path, AclFileAttributeView>`. Le contrat « le message ne nomme jamais le chemin » est inchangé.
- **`ProcessOwner`** : `current(Path near)` avec son cache `volatile`, `probe(Path near)`, `unverifiable(IOException)`. `PrivateLocalStorage.verifyOwnedByCurrentUser` reste publique, garde sa Javadoc et délègue.
- **`PlatformAccessControlDiscovery`** : le porteur paresseux du port du processus, `current()` (aujourd'hui `platformAccessControl()`), `discover(ClassLoader)` (aujourd'hui `discoverPlatformAccessControl`) et le port qui refuse tout. Le chargeur est celui de la classe qui découvre, `PlatformAccessControlDiscovery.class.getClassLoader()`, jamais celui de l'interface `PlatformAccessControl` (un hôte qui chargerait une autre copie de l'interface par son chargeur parent ne verrait pas le `META-INF/services` de MINOS). Il est identique à celui de `PrivateLocalStorage` par construction : `CapabilityProbe.real()` appelle `current()` en accès de package, ce qui impose le même paquet d'exécution, donc le même chargeur. L'appel garde la forme à deux arguments (A16) ; les trois messages de refus sont conservés au caractère près. `PlatformAccessControlDiscoveryTest` appelle la classe nouvelle.
- **`CapabilityProbe`** : interface publique de premier niveau (voir D4) ; son `real()` appelle `PlatformAccessControlDiscovery.current()`. Sa Javadoc vise `PrivateLocalStorage#useForTesting(CapabilityProbe)`.

`PrivateLocalStorage` garde le cœur, la garde H23, les flux privés, `ownerOnlyAcl`, `PROTECTED_LOCATIONS`, le `ThreadLocal` du crochet et les méthodes publiques `useForTesting`, `resetCapabilityProbeForTesting`.

### D3. `MANAGER` : trois extractions dans `…/runtime/install`

Toutes dans le package et le module actuels :

- **`BoundedZipExtraction`** : `extractZipBounded`, `extractDistribution`, `openExtractedFile`.
- **`ManagedToolInstallation`** : `run`, `moveFile`, `moveDirectory`, `writeNewFile`, `deleteRecursively`, et la borne du journal d'installation (D8). `copyPackagedResource` **reste** sur le manager : elle charge une ressource par sa classe et trois tests l'appellent à cet endroit.
- **`ScipJavaClasspathInstallation`** : tout le bloc classpath, dont `swapIn` et `DirectoryMover` ; ses méthodes prennent la racine du classpath ou le listing en paramètre ; `CLASSPATH_FILE` y est déplacée. Le manager garde deux méthodes d'instance de visibilité de package, `installClasspath(List<Path>, ClasspathLock)` et `installedClasspathVerifies(ClasspathLock)`, qui délèguent en passant `scipJavaClasspathRoot()` ou `scipJavaClasspathFile()` (privés) : `ScipJavaClasspathInstallationTest` les appelle et n'est pas modifié ; l'estimation de D7 compte ces délégations.

`list`, `inspect`, `install`, `executor`, les constantes publiques (`SCIP_*`), `SANDBOXED_EXECUTION_NOT_QUALIFIED` (`:105`) et les diagnostics de prérequis restent sur le manager. Les trois classes sont `final`, de visibilité de package, à constructeur privé : des regroupements de fonctions, pas des surfaces.

### D4. `CapabilityProbe` devient un type de premier niveau (décidé : oui, Q3)

C'est le seul déplacement qui touche un autre module (`MinosApplicationTest` dans `minos-bootstrap`) : un changement de nom de type dans des tests, pas dans la surface de production. Il ôte du code **de test** d'une classe **de production**. A15 reste vert : le type est nommé par `PrivateLocalStorage`.

### D5. La couverture : un déplacement est du code neuf

SonarCloud juge le nouveau code module par module (80 %) : chaque ligne déplacée compte. Les tests restent dans le même module, donc la couverture suit. Aucune classe extraite n'a de branche qui ne s'exécute que sous un système : `ProcessOwner` s'exécute sous POSIX comme sous ACL, `WriteDenyDiagnosis` est fait de fonctions pures testées partout, `PlatformAccessControlDiscovery` est testée par des chargeurs construits par le test. Sonar importe les rapports Linux **et** Windows (job `sonar` de `pr-ci.yml`), mais la fusion fichier par fichier n'est pas prouvée (réserve consignée à la PR #421). La tâche 3.6 la vérifie sur la première PR par l'API, fichier par fichier ; un module sous le seuil reçoit des tests, jamais une exclusion.

### D6. Planchers JaCoCo : la couverture agrégée ne baisse pas

Le gate raisonne par préfixe de nom de classe. Découper un fichier change le dénominateur de chaque classe : le manager, une fois ses blocs bien couverts partis, perd des points de lignes sans qu'une seule ligne perde sa couverture, et peut franchir son plancher de 0,61. Règle : on **additionne** les compteurs ligne et branche des classes issues du découpage avant et après ; le **nombre de lignes et de branches couvertes** après n'est pas inférieur à celui d'avant, et le taux agrégé (couverts / total) ne baisse pas de plus que l'effet des lignes de délégation ajoutées, chiffré (tâches 1.2, 2.5, 3.5). Chaque classe nouvelle de `…/install/` entre dans `m24-polyglot-provider-platform` avec un plancher égal à la plus basse de ses mesures Linux et Windows moins environ cinq points (convention AUD-TST-02, celle du commentaire `check-jacoco.py:119-120`) ; le plancher du manager est **recalculé sur ce qui lui reste** par la même convention, et la baisse apparente est consignée avec le calcul. Le plancher agrégé du scope (0,50 / 0,28) n'est pas abaissé. Un plancher n'est jamais abaissé sans ce calcul. `com/minos/io/` est un préfixe de package : rien à déclarer pour `PRIVATE`.

### D7. Acquisition et amorçage de `MANAGER` : une exclusion et une option tranchée

Sans eux, `MANAGER` descend à **~751 lignes** (1 005 − 151 − 60 − 41 + ~8 de délégation − ~10 d'imports) : sous 800, marge ~50 lignes.

- **L'acquisition épinglée (~110 lignes) n'entre pas** : couverte à 11 %, elle deviendrait du code neuf à 11 % dans un module jugé à 80 %. Elle fait l'objet d'un changement ultérieur qui commence par les tests (un `PinnedArtifactSource` factice servant une archive, sans réseau), puis déplace.
- **L'amorçage (191 lignes)** rejoint ce changement ultérieur (Q2).

### D8. A13 : aucun compte ne monte

Après le découpage, chaque type de `minos-runtime-local` est nommé par **le même nombre** de fichiers de production de `minos-provider-scip` qu'au HEAD :

- `InstallationProcessEnvironment` et `BoundedProcessOutput` passent de `MANAGER` à `ManagedToolInstallation`, avec `run` : comptes 3 et 3 inchangés, à condition que `MANAGER` ne les nomme plus (son import disparaît).
- `ScipJavaClasspathInstallation` ne nomme ni `BoundedProcessOutput` ni `CommandLocator` : la borne d'octets du journal lu par `fetchVerifiedClasspath` lui vient de `ManagedToolInstallation` (une constante de visibilité de package égale à `BoundedProcessOutput.DEFAULT_MAX_BYTES_PER_STREAM`, lue par `ManagedToolInstallation.<constante>`, sans import), et le choix POSIX de `ScipJavaClasspath.verify` lui est passé en paramètre par le manager, qui garde `CommandLocator.isWindows()` (compte 10 inchangé, celui que fige `test_check_module_boundaries.py:1417`). Elle n'importe aucun type de `minos-runtime-local`, pas même pour un `{@link}` de Javadoc, qu'A13 compterait.
- `BoundedZipExtraction` ne nomme aucun type de `minos-runtime-local`.

La preuve est `python scripts/architecture/check-module-boundaries.py` vert **sans modifier** `ADAPTER_TYPES_NAMED_BY_ADAPTER`. Ajouter une unité à un compte pour faire passer le découpage est exclu : la table ne s'allonge que par une décision relue (ARCH-0068).

### D9. Ordre de fusion avec `injecter-le-staging-du-cycle-scip`

`MANAGER` est compté par A13, et `injecter-le-staging-du-cycle-scip` réécrit la table A13, ses messages et l'exigence correspondante de `frontieres-de-modules`. Les deux PR qui touchent ce décompte — celle de la section 3 de ce changement et celle de SH-06 — **se fusionnent l'une après l'autre**, jamais en parallèle : la seconde est mise à jour sur `develop` après la fusion de la première (`gh pr update-branch`), rejoue `python scripts/architecture/check-module-boundaries.py` et `test_check_module_boundaries.py` sur l'arbre fusionné, et ne fusionne qu'avec un CI neuf. La mise en série tient au **décompte A13 sur l'arbre fusionné**, pas à un conflit de texte : ce changement ne modifie ni `check-module-boundaries.py` ni `test_check_module_boundaries.py` (D8), et ses seuls fichiers communs possibles avec SH-06 sont `SUIVI.md` et `docs/developer/quality-gates.md`. L'ordre lui-même est libre ; par défaut, SH-06 d'abord, puisqu'il est déjà en cours d'implémentation. La section 2 (`PRIVATE`) n'est pas concernée.

### D10. Ordre et isolement des lots

Deux PR, une par module : `PRIVATE` (section 2) puis `MANAGER` (section 3). Dans chaque PR, un commit par extraction, chacun vert pour tous les gates : un gate littéral ou une liste qui lit une chaîne déplacée est repointé dans la tâche qui la déplace. Une régression se bissecte à la classe près. La section 2 n'a aucune condition préalable ; la section 3 suit D9.

## Direction des dépendances

Aucun import entre packages ajouté ni retiré ; toutes les classes nouvelles sont dans le package de leur classe d'origine, donc dans le même module. Pas de cycle de packages (A8), pas de racine nouvelle (A14), pas de dépendance Maven nouvelle ; ADR 0068 respecté. Entre classes d'un même package, `ScipJavaClasspathInstallation`, `BoundedZipExtraction` et le manager dépendent de `ManagedToolInstallation`, jamais l'inverse ; `PrivateLocalStorage` dépend de `WriteDenyDiagnosis`, `ProcessOwner` et `CapabilityProbe`, et `CapabilityProbe.real()` de `PlatformAccessControlDiscovery` ; aucune de ces classes ne dépend de `PrivateLocalStorage`.

## Qualification des capacités

Sans objet : aucune capacité ajoutée, modifiée ni requalifiée. `PrivateLocalStorage` garde ses niveaux de confidentialité (SEC-0061 ; `openNewTreeFile` reste partiel par construction) et le manager ses états `READY`, `BLOCKED`, `NOT_INSTALLED` ; la mention « exécution sous AppContainer non qualifiée » (`SANDBOXED_EXECUTION_NOT_QUALIFIED`) reste sur le manager.

## Windows et Linux

`ProcessOwner` s'exécute sur les deux systèmes (sonde sous POSIX comme sous ACL) ; `WriteDenyDiagnosis` n'est atteint en production que sur une vue ACL, mais ses fonctions pures sont testées partout par `WriteDenyClassificationTest` ; `PlatformAccessControlDiscovery` découvre le port sur les deux systèmes (`WindowsIcaclsAccessControl` ne fait rien hors de Windows, `:55`, `:61`, et lance `icacls` sous Windows). L'acquisition de Coursier et Node, propre à Windows, reste en place. Le scope `m24-polyglot-provider-platform` est rouge hors régression sur poste Windows : comparer à `develop` avant d'attribuer un écart au découpage. Aucune branche `os.name` n'est ajoutée ni retirée.

## Risks / Trade-offs

- **Fail-closed** → le refus quand le propriétaire ne peut pas être vérifié, le port qui refuse tout quand aucun (ou plusieurs) n'est enregistré, et le diagnostic d'un DENY explicite restent identiques ; l'agent `minos-security-reviewer` relit `ProcessOwner` et `PlatformAccessControlDiscovery` avant la PR (tâche 2.5).
- **Régression silencieuse d'un message** → les messages sans chemin de `PRIVATE` sont affirmés par `IoRefusalMessageTest`, `WriteDenyClassificationTest`, `PrivateLocalStorageSharedLocationTest` et `PlatformAccessControlDiscoveryTest` ; ils restent verts sans modification d'attente.
- **Entrées de l'allowlist** → oublier une entrée fait échouer le gate deux fois ; l'ordre « classe d'accueil, entrée, gate » dans une même tâche l'évite.
- **Compte A13** → une extraction qui nomme un type de `minos-runtime-local` de plus fait échouer A13 ; D8 et la preuve de chaque tâche de la section 3 l'évitent.
- **Couverture Sonar** → voir D5 ; risque principal, visible seulement une fois la PR ouverte.
- **Tests d'un autre module** → le retypage de `CapabilityProbe` touche `minos-bootstrap` ; rejouer `-pl minos-engine,minos-bootstrap -am`.
- **Gain** → si, à la relecture (1.1), une extraction ne retire pas les lignes annoncées, le dire et ne pas la faire.

## Décisions du propriétaire

Tranchées le 2026-10-20 :

- **Q1 — cliquet de taille de fichier : non.** Aucun gate de taille n'est ajouté ; le seuil de 800 lignes reste celui du superviseur.
- **Q2 — amorçage depuis la charge utile : dans un changement suivant**, avec l'acquisition de Coursier, Maven et Node (qui commence par des tests).
- **Q3 — `CapabilityProbe` en type public de premier niveau : oui** (D4).

Tranchée le 2026-10-28 :

- **Q6 — la sonde du propriétaire quitte `PrivateLocalStorage` : oui** (D2, tâche 2.2). L'extraction de `ProcessOwner` baisse le maximum `Files.createTempFile` de `PrivateLocalStorage.java` de 3 à 1 et ouvre l'entrée `ProcessOwner.java` (`max` 2) : aucun appel brut de plus, somme 56 inchangée, cinq entrées pour `PrivateLocalStorage` (la règle Archgate reste verte). SEC-0061, Accepted, écrit que « les maxima de `PrivateLocalStorage` ne bougent pas » (`:92`, `:103`) et qu'« un appel brut de plus dans ces classes échoue au garde » (`:78`) : la tâche 4.3 lui ajoute une note datée de « Mise en œuvre » qui dit que les appels bruts de la primitive peuvent se répartir entre classes de `com.minos.io` sans que leur somme monte, et qui consigne les deux répartitions (`WindowsIcaclsAccessControl.java` par SH-08, `ProcessOwner.java` ici) ; la décision de l'ADR ne change pas.

Sans objet : **Q4** (nom du lanceur d'`icacls` après extraction) — le lancement d'`icacls` vit dans `minos-runtime-local` depuis SH-08 (`c08cb379`) et la table A12 est vide.

En attente :

- **Q5 — les quatre autres fichiers de plus de 800 lignes.** **Recommandation** : hors de ce changement. `WindowsAppContainerWorkerSandboxBackend` et `LinuxCgroupJob` sont du confinement (relecture de sécurité dédiée, scopes `provider-sandbox-windows` et `provider-sandbox-linux`, tests de confinement exigés en CI) ; `SnapshotBinaryCodecSupport` porte le codec binaire du snapshot (scope `storage-codec-and-state`) ; `IndexingRunExecutor` est l'exécuteur de l'indexation, que `decouper-les-methodes-d-analyse-et-d-ingestion` (archivé le 2026-10-24) a déjà retravaillé méthode par méthode. Chacun relève d'un changement mesuré à part, s'il est demandé.
