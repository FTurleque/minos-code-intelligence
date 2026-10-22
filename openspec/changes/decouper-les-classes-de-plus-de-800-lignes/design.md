# Design

## Context

Constat du superviseur de code (fin de tour) sur deux fichiers de production de plus de 800 lignes ; voir `proposal.md` (Why). Le dépôt n'a pas de limite de taille : le seuil est celui du superviseur, et l'audit du 10 octobre 2026 écarte tout seuil bloquant (AUD-QUA-07). Ce design tranche **quelles responsabilités secondaires se détachent proprement**, à quel prix pour les gates et pour la couverture, et ce qui reste volontairement en place.

`PRIVATE` désigne `minos-engine/src/main/java/com/minos/io/PrivateLocalStorage.java` ; `MANAGER` désigne `minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/install/ManagedScipProviderRuntimeManager.java`.

## État vérifié au HEAD

### Base de lecture

HEAD `5ce7b79b` (`develop` du 2026-10-22, PR #538). `wc -l` : `PRIVATE` **1 031** lignes, `MANAGER` **1 004**. Tous les numéros de ligne ci-dessous sont relus à ce HEAD.

Les deux branches du superviseur qui modifiaient ces fichiers ne sont pas une condition : `refactor/complexite-points-superviseur` (`c4531427`) est un ancêtre du HEAD (`git merge-base --is-ancestor` : oui) ; `refactor/duplications-points-superviseur` (`ae8ff500`) ne l'est pas, mais son contenu retenu est dans `develop` par `3c1c53ef`, et `902e1132` retire du manager la factorisation `acquireAndExtractZip` (helper à huit paramètres, S107, et nouveau code non couvert refusé par SonarCloud). Aucune des deux branches n'existe localement. L'attente bornée des processus `icacls` est factorisée au HEAD sous le nom `WindowsDacl.awaitSuccess` (`PRIVATE:839-853`).

### Classes de production de plus de 800 lignes

Décompte au HEAD (`wc -l` de tous les `*/src/main/**/*.java`, plugin compris) : **six** fichiers, pas deux.

| Fichier | Lignes | Traité ici |
|---|---|---|
| `minos-storage-local/.../store/SnapshotBinaryCodecSupport.java` | 1 119 | non (Q5) |
| `PRIVATE` | 1 031 | oui |
| `MANAGER` | 1 004 | oui |
| `minos-engine/.../orchestration/IndexingRunExecutor.java` | 984 | non (Q5 ; `decouper-les-methodes-d-analyse-et-d-ingestion` réduit `execute`) |
| `minos-runtime-local/.../sandbox/WindowsAppContainerWorkerSandboxBackend.java` | 816 | non (Q5) |
| `minos-runtime-local/.../sandbox/LinuxCgroupJob.java` | 813 | non (Q5) |

Le superviseur ne relève que les fichiers touchés pendant la session qui l'a déclenché ; ce changement garde ce périmètre (décision Q5).

### `PRIVATE` — ce qui se détache et ce qui reste

Couverture : mesure locale Windows (`verify`) du 2026-10-18, **non rejouée** au HEAD ; le dernier commit qui touche `PRIVATE` (`a35b530f`) est antérieur à cette mesure, elle reste indicative jusqu'à la tâche 1.2.

| Bloc | Lignes | Taille | Appelants | Couverture |
|---|---|---|---|---|
| `WindowsDacl` (lancement d'`icacls`, attente bornée, lecture SDDL) | `PRIVATE:801-924` | 124 | `CapabilityProbe.real()` (`:977`, `:982`) ; test `PrivateLocalStorageDaclReadTest` (`PrivateLocalStorage.WindowsDacl.requireNoConditionalDeny`, `.Launcher`) | 55/57 lignes, 35/46 branches |
| Diagnostic d'un refus d'écriture (DENY explicite) | `PRIVATE:619-696` | 78 | six appels internes (`:199`, `:209`, `:223`, `:241`, `:517`, `:585`) ; test `WriteDenyClassificationTest:34-58` (`carriesWriteDenyForOwner`) | 27/29, 26/34 |
| Propriétaire du processus (sonde, cache) | `PRIVATE:416-479` | 64, dont 14 de Javadoc et de signature de `verifyOwnedByCurrentUser` qui restent | `ensurePrivateDirectory` (`:147`) ; `SandboxLauncherScript` (`minos-runtime-local`, `:108`, `:117`, `:141`, `:171`, `:242`) appelle `verifyOwnedByCurrentUser` ; tests `PrivateLocalStorageSharedLocationTest:71,81,97` et `PrivateLocalStorageWindowsAclTest:473,490` (`probeOwner`) | 24/24, 10/12 |
| Crochet d'essai : interface `CapabilityProbe` (le `ThreadLocal` et `useForTesting` restent) | `PRIVATE:928-986` | 59 | quatre fichiers de test à retyper : `MinosApplicationTest` (`minos-bootstrap`, `:150`), `FakeCapabilityProbe:23`, `PrivateLocalStorageStreamsTest:146-147`, `PrivateLocalStorageWindowsAclTest:203-205,382,394,399` ; `DurableAtomicFileTest` et `PrivateLocalStorageTest` n'utilisent que `FakeCapabilityProbe` | 9/9 lignes, pas de branche |
| Garde des emplacements partagés (H23 : `refuseSharedLocation`, `userHome`) | `PRIVATE:159-178` | 20 | `ensurePrivateDirectory` (`:146`) | 10/10, 16/16 |
| Cœur : création, durcissement POSIX et ACL, vérification, flux privés | le reste | ~686 | neuf modules de production (dont `minos-engine`), plus les tests de `minos-bootstrap` | — |

Total local du fichier (même mesure) : 346/386 lignes (90 %), 167/212 branches (79 %).

- **`WindowsDacl`** n'a **aucune dépendance** vers le reste de la classe : elle lance un processus, lit un fichier SDDL et décide. Elle est `static final`, de visibilité de package, et porte une couture `Launcher` (`:871-875`) pour `requireNoConditionalDeny` (`:877`) ; `protect` (`:817-833`) lance `ProcessBuilder` directement (`:827`). C'est la coupe la plus propre.
- **Le diagnostic de refus** est fait de fonctions pures sur des entrées ACL, sauf la surcharge à chemins qui lit la vue ACL par `aclView(location)` (`:687`), donc par le `ThreadLocal` du crochet d'essai (`:793-795`) : l'extraction lui passe la vue en paramètre.
- **La sonde du propriétaire** n'utilise **pas** le crochet d'essai (`probeOwner` appelle `Files.createTempFile` directement, `:463`, `:469`) : aucune dépendance cachée.
- **La garde H23** est trop petite (20 lignes) et liée à `ensurePrivateDirectory` : **elle reste**.
- **Le cœur** reste : y séparer POSIX et ACL demanderait de défaire `harden` et `PROTECTED_LOCATIONS` (`:705`), qui partagent l'état par JVM.

Estimation : 1 031 − 124 − 78 − 50 (propriétaire, sans la Javadoc qui reste) − 59 + ~5 de délégation − ~3 d'imports ≈ **722 lignes**. Cible de la tâche 2.6 : ≤ 730. Sans `CapabilityProbe` : ~781, trop près de 800.

### `MANAGER` — ce qui se détache et ce qui reste

Couverture : mesure locale Windows du 2026-10-18 sur une pile qui portait `acquireAndExtractZip` (15 lignes du bloc d'acquisition, absentes au HEAD depuis `902e1132`) ; **non rejouée** au HEAD. Mesure de CI de la classe entière (commentaire de `scripts/quality/check-jacoco.py:110-111`, runs du 2026-10-15) : Linux 0,711 / 0,572, Windows 0,668 / 0,548.

| Bloc | Lignes | Taille | Appelants | Couverture |
|---|---|---|---|---|
| Classpath de scip-java : `scipJavaFetchCommand`, `fetchVerifiedClasspath`, `resolvedJars`, `jarList`, `installedClasspathVerifies`, `installClasspath`, `swapIn`, `DirectoryMover`, `restore`, `copyJar` | `MANAGER:366-516` | 151 | `installJava` (`:355-359`) ; tests `ClasspathLockTest`, `ScipJavaClasspathInstallationTest` (`installClasspath` ×9, `installedClasspathVerifies` ×6), `ManagedToolInstallationFilesTest:179,194,206,224` (`swapIn`), `ManagedScipProviderRuntimeManagerTest` et `…RuntimeTest` (`scipJavaFetchCommand`, `resolvedJars`) | 79/83 lignes (95 %), 25/30 branches |
| Extraction bornée d'archives (`extractZipBounded`, `extractDistribution`, `openExtractedFile`) | `MANAGER:854-913` | 60 | `ensureMaven` (`:615`), `ensureNode` (`:653`), `seedAssembledTree` ; tests `ManagedToolInstallationFilesTest`, `ManagedScipProviderRuntimeManagerRuntimeTest` | 30/30 lignes, 13/14 branches |
| Gestes d'installation : `writeNewFile` (`:518-523`), `run` (`:969-988`), `deleteRecursively` (`:990-992`), `moveFile` (`:994-998`), `moveDirectory` (`:1000-1003`) ; tous `private static` sauf `run` | `MANAGER:518-523`, `:969-1003` | 41 | tout le fichier ; tests `InstallationEnvironmentIsolationTest:61`, `ManagedRuntimeCommandRunnerTest:39` (`run`) | 28/28 lignes, 6/8 branches |
| Acquisition épinglée de Coursier, Maven, Node (`ensureCoursier`, `ensureMaven`, `ensureNode`, racines et exécutables, `coursierExecutable`) | `MANAGER:556-660`, `:915-919` | ~110 | `installJava`, `installTypeScript`, `seedComponents` | 8/71 lignes (11 %), 3/34 branches sur la pile |
| Amorçage depuis la charge utile embarquée (`SeedOutcome` … `originDiagnostic`) | `MANAGER:662-852` | 191 | `inspect*`, `install*` ; test `EmbeddedToolsSeedingTest` | 89/110 lignes (81 %), 60/79 branches |
| Inspection, installation, exécuteur, diagnostics, chemins | le reste | ~450 | `DefaultMinosApplicationComposer.java:78` (`new ManagedScipProviderRuntimeManager(home)`) | 124/189 lignes (66 %), 67/127 branches |

Total local de la classe sur la pile (nom de classe et classes internes) : 358/511 lignes (70 %), 174/292 branches (60 %).

**Conséquence sur le plancher** (`check-jacoco.py:117`, classe 0,61 / 0,49) : sur la pile, retirer les trois blocs bien couverts de D3 (137 lignes couvertes sur 141) laisse au manager 221/370 lignes (**59,7 %**) et 130/240 branches (**54,2 %**). Au HEAD, sans `acquireAndExtractZip` (peu couvert), le taux restant est un peu plus haut, sans marge sûre sur 0,61 ; la mesure exacte est celle de la tâche 1.2. La règle de D6 vaut quel que soit le chiffre.

- **Extraction bornée** : sans état ; deux appels bruts `Files.createDirectories` (`:875`, `:878`), qui sont **exactement** le `max` 2 de l'entrée de l'allowlist. Coupe très propre.
- **Classpath** : presque entièrement statique ; les deux méthodes d'instance lisent chacune un chemin du manager : `installedClasspathVerifies` le listing (`scipJavaClasspathFile()`, `:427`), `installClasspath` la racine (`scipJavaClasspathRoot()`, `:448`) ; les deux deviennent des paramètres. Le bloc utilise trois constantes privées : `CLASSPATH_FILE` (déclarée `:74`, lue `:467` et par `scipJavaClasspathFile()` `:937`), `MAX_CLASSPATH_JAR_BYTES` (`:83`, lue `:513`) et `MAX_INSTALL_LOG_LINE_CHARS` (`:47`, lue `:393`). Les deux dernières partent avec le bloc ; `CLASSPATH_FILE` y est déplacée en visibilité de package et le manager la référence.
- **Gestes d'installation** : partagés par tous les blocs ci-dessus ; c'est ce qui rend les autres extractions possibles sans dépendance en retour vers le manager.
- **Acquisition** : dépend de `toolsRoot`, de `source()` et de `origins` ; Coursier refusé hors Windows (`:559-561`). Couverte à 11 % : la déplacer en ferait du code neuf très sous le seuil de 80 % de SonarCloud — c'est la raison du retrait de `acquireAndExtractZip` par `902e1132`.
- **Amorçage** : 191 lignes à 81 %, exécutable sous Linux par le crochet `minos.tools.platform` (`EmbeddedToolsCatalog.currentPlatform`, `:210-211`), mais le plus couplé (acquisition, `installJavaWindowsRuntime`, six accesseurs de chemins).
- **`ManagedPolyglotScipRuntimeManager`** (564 lignes) **et `ManagedScipPythonRuntimeManager`** (286 lignes, `run` `:253`, `moveDirectory` `:282`) dupliquent ces gestes : constaté, non traité.

### Ce que les gates et les cliquets exigent de ces fichiers

| Garde | Ce qu'il lit | Effet de l'extraction |
|---|---|---|
| `scripts/architecture/check-private-io.py` + `private-io-allowlist.json` (**23 entrées, somme des `max` = 56** ; gate vert au HEAD) | un maximum **par fichier et par méthode brute** ; liste qui ne fait que se resserrer (`stockage-prive-et-secrets`) | `PRIVATE` `Files.createTempFile` `max` 4 = `:221` + `:463` + `:469` + `:919` : 1 reste, 2 vont à `ProcessOwner`, 1 à `WindowsDacl`. `MANAGER` `Files.createDirectories` `max` 2 = `:875` + `:878` : l'entrée change de clé de fichier. Sans cela, le gate échoue deux fois (appel hors liste, puis maximum supérieur au décompte). Après : **25 entrées**, somme des `max` inchangée à **56**. |
| `check-module-boundaries.py` A12 (`:580-585`, `PROCESS_LAUNCHERS` = `{minos-engine/io.PrivateLocalStorage}` ; capacité `frontieres-de-modules`) | le nom de **fichier** de chaque source de `minos-domain`, `minos-engine`, `minos-application` qui contient `ProcessBuilder` (`:725-729`) | après l'extraction, `ProcessBuilder` est dans `WindowsDacl.java` (`:827`, `:865` au HEAD) : l'entrée devient `minos-engine/io.WindowsDacl`, sinon le gate échoue deux fois (classe hors liste, entrée qui ne lance plus rien). L'ADR 0062 (Décision § 1) et `docs/developer/quality-gates.md:68` nomment `PrivateLocalStorage` comme seul lanceur : décision Q4. L'auto-test `ProcessLauncherRuleTest` (`test_check_module_boundaries.py:1030-1062`) construit son propre arbre et ne change pas. |
| `check-module-boundaries.py` A15 (`:842-972`) | tout type **public de premier niveau** de `src/main` est nommé par la production ou listé | `CapabilityProbe` devient public de premier niveau ; `PrivateLocalStorage` (même package) le nomme : aucune entrée à ajouter. Les autres classes nouvelles sont de visibilité de package. |
| `check-module-boundaries.py` A3, A8, A10, A11, A13, A14 | package par module, cycles, exports de `minos-runtime-local`, types du moteur nommés par les surfaces, types d'un adaptateur nommés par un autre, racines de packages | inchangés : mêmes packages et modules ; `ManagedToolInstallation` nomme les mêmes types de `minos-runtime-local` (`InstallationProcessEnvironment`, `BoundedProcessOutput`, `CommandLocator`) que le manager, déjà listés pour le couple (`minos-provider-scip`, `minos-runtime-local`) ; les surfaces ne nomment pas `CapabilityProbe` en production. |
| `scripts/quality/check-jacoco.py` | préfixes de **noms de classes** (`startswith`, `:571-578`) ; `confined-io-primitives` = `com/minos/io/` (`:40-46`) ; `global` = `com/minos/` (`:19-24`) ; `m24-polyglot-provider-platform` (`:102-121`) nomme `.../install/ManagedScipProviderRuntimeManager` avec un plancher de classe 0,61 / 0,49 (`:117`) et un plancher agrégé 0,50 / 0,28 | `com/minos/io/` couvre les classes nouvelles de `PRIVATE` sans rien changer (le commentaire « 34 classes » de `:36` devient faux). Une classe nouvelle de `.../install/` **sort du scope m24** tant qu'elle n'y est pas déclarée, et le plancher du manager porte sur un code différent (D6). |
| `scripts/remediation/check-post-mne.py` (`:113-114`, `:225-226`, `:233-237`) | `MANAGER` contient `BoundedInputStream`, `BoundedLineReader`, `FileTreeOperations.deleteRecursively` ; n'a ni `.sorted(Comparator.reverseOrder()).toList()` ni `Files.readAllLines(log)` | les trois chaînes quittent `MANAGER` (classpath : `:389-393`, `:512` ; gestes : `:991`) ; le gate est repointé |
| `scripts/remediation/check-mnd.py` (`:60`, `:118-119`, `:122`) | `MANAGER` contient `MAX_COURSIER_ARCHIVE_BYTES`, `source().acquire(`, `coursierArtifact(), archivePartial, MAX_COURSIER_ARCHIVE_BYTES` ; n'a pas `BodyHandlers.ofFile(archivePartial)` | inchangé : l'acquisition reste dans `MANAGER` (D7) |
| `scripts/quality/check-tools-manifest.py` (`:39`, `:223`) | `SCIP_JAVA_VERSION`, `SCIP_TYPESCRIPT_VERSION` de `MANAGER` | inchangé |
| `scripts/remediation/check-p0-p2.py` (`:130`, `:139`) | `PRIVATE` contient `attributes.isOther()` (`:497`, `:761`) | inchangé : `privacyOf` et `requireType` restent |
| `quality/spotbugs-baseline/minos-engine.xml` (`:517-536`), `minos-provider-scip.xml` ; comparaison par `instanceHash`, qui dépend du nom de classe ; `code-audit.yml`, **hors checks exigés** | `PRIVATE.currentOwner` (NP) ; `MANAGER.extractZipBounded` (NP), `inspectJavaInstalled` (ENV, entrée périmée : le `System.getenv("JAVA_HOME")` est dans `addProjectJdkDiagnostics`, `:294-295`), `gitBashForGit` (NP) | la base est à **rafraîchir** après chaque déplacement (`.claude/rules/tests-java.md`) en vérifiant que seules ces alertes changent de classe, jamais à masquer (`audit-qualite-code` : « Aucune alerte n'est masquée ») |
| SonarCloud (`FTurleque_atlas`, 80 % de couverture du nouveau code, par module ; rapports Linux et Windows, `pr-ci.yml:393-463`) | lignes nouvelles du module | chaque classe nouvelle est entièrement « code neuf » : voir D5 |

`scripts/quality/check-runtime-dynamic-consistency.py:94` cite `PrivateLocalStorage` comme fait attendu dans `BoundedFileLease.java`, qui ne change pas. `scripts/history/` cite d'anciens chemins du manager et n'est pas exécuté. Les tests de garde de `minos-app` (`DuplicationGuardTest`, `JsonEscapeGuardTest`, `JsonSourceScanner`) ne citent aucun des deux fichiers ; aucun test n'atteint ces membres par réflexion ; aucun `ServiceLoader` n'est concerné. Aucun nom de classe nouvelle (`WindowsDacl`, `WriteDenyDiagnosis`, `ProcessOwner`, `CapabilityProbe`, `BoundedZipExtraction`, `ManagedToolInstallation`, `ScipJavaClasspathInstallation`) n'existe comme fichier du dépôt.

Documentation qui nomme ces classes : `docs/architecture/arc42/06-vue-execution.md:122,151` et `docs/architecture/diagrams/seq-erreur-provider.md:38,67` (`inspect`, `install`, qui restent) ; `docs/architecture/diagrams/deployment-native.md:8` (nom de classe, qui reste) ; `docs/developer/quality/code-audit-constats.md:39` (`ENV_USE_PROPERTY_INSTEAD_OF_ENV` sur le manager, « ligne 238 », périmée) ; `docs/developer/quality-gates.md:68` (A12, à mettre à jour) ; ADR 0062 (Décision § 1 et Conséquences négatives, `PrivateLocalStorage` lanceur d'`icacls`). `docs/developer/quality-gates.md` ne cite pas le scope `m24-polyglot-provider-platform`.

### Recouvrements avec les capacités et les changements actifs

Capacités dont les exigences doivent rester satisfaites, sans modification : `frontieres-de-modules` (« Les gates et la couverture suivent les classes déplacées », « Le lancement de processus hors du runtime local est tenu par liste fermée », « Une classe publique de production est référencée par la production ou déclarée ») ; `stockage-prive-et-secrets` (« La liste de tolérance des écritures brutes ne fait que se resserrer », « Le message d'une primitive d'E/S privée ne contient pas de chemin absolu », « Un refus conditionnel n'est jamais retiré par le durcissement ») ; `installation-des-providers` ; `audit-qualite-code` (« Aucune alerte n'est masquée pour obtenir un résultat vert »).

Changements actifs (`openspec list`, 23 changements) :

- **`exposer-un-port-de-lecture-de-snapshot`** (1/22) : ajoute une règle **A17** à `check-module-boundaries.py` et des entrées à `quality/spotbugs-baseline/minos-engine.xml`. Mêmes fichiers, entrées différentes : conflit textuel possible, à résoudre en gardant les deux effets ; aucun ordre imposé.
- **`faire-consommer-l-indexation-aux-surfaces`** (0/18) : réduit les listes A11 de `check-module-boundaries.py` ; `io.PrivateLocalStorage` reste dans la liste de la CLI (`DoctorCommand`). Ce changement-ci n'ajoute aucun type nommé par une surface : pas de conflit de fond.
- **`decouper-les-methodes-d-analyse-et-d-ingestion`** (0/10) : `ScipIngestionAdapter` est aussi dans `minos-provider-scip`, et `IndexingRunExecutor` (984 lignes) dans `minos-engine`. Aucun fichier commun ; les deux rejouent `m24-polyglot-provider-platform` sans en modifier les mêmes lignes. Ne pas les réunir dans une même PR : SonarCloud juge le nouveau code par PR et par module.
- **`mutualiser-les-caches-lru-et-nommer-les-additions-bornees`** (0/15) : aucun fichier commun. Si sa décision D2 place la classe partagée dans `com.minos.io`, elle entre dans `confined-io-primitives` : sans effet ici.
- **`retirer-les-jalons-des-identifiants`** (0/13) : garde le nom de scope `m24-polyglot-provider-platform` (hors de son périmètre) et ne touche pas le manager. Si le scope est renommé plus tard, les préfixes ajoutés ici suivent.
- **`fixer-la-langue-des-commentaires`** (0/10) : `PRIVATE` et `MANAGER` n'ont aucune ligne de commentaire accentuée (relevé au HEAD) ; les classes extraites gardent une Javadoc en anglais seulement et ne créent aucun fichier mixte. Sa décision D1 ne change pas ce changement-ci.
- `migrer-l-installation-des-outils-scip-vers-les-primitives` (reste 4.1), `rendre-l-echec-de-scip-java-sous-windows-actionnable` (5.2), `executer-scip-java-sur-classpath-verifie` (1.5, 2.3), `surveiller-les-outils-embarques` (3.4, 4.3), `verifier-le-proprietaire-des-dossiers-prives` (2.3), `proteger-la-dacl-des-dossiers-du-plugin` (3.3), `migrer-les-ecritures-d-artefacts-du-runtime-local` (5.1), `lire-le-registre-sans-ecrire` (4.3 à 4-B.2) : leurs tâches restantes sont des mesures manuelles, de la qualification ou du Docker ; **aucune ne modifie ces fichiers ni `private-io-allowlist.json`**. `executer-scip-java-sur-classpath-verifie` et `surveiller-les-outils-embarques` citent des lignes du manager que ce changement déplace : à relire à leur archivage, sans conflit d'écriture.
- Changements archivés le 2026-10-22 : `figer-la-dette-de-frontieres-par-des-cliquets` (A11 à A14, dont A12) et `trancher-les-decisions-d-architecture-du-sprint-10` (ADR 0062 : le mécanisme d'ACL sortira du moteur par un **port d'E/S de plateforme** fourni par `minos-runtime-local`, conçu avec AUD-SEC-06). L'extraction faite ici reste **dans `com.minos.io`** : ce n'est pas ce port et elle ne le préjuge pas ; elle isole le code qu'il remplacera.

### Non mesuré

Couverture des blocs au HEAD (chiffres de la pile du 2026-10-18) et couverture **Linux** des blocs ; couverture Sonar réelle (l'API ne donne rien pour une branche non principale de cette organisation) ; durée des suites de tests. Pour cette révision : `wc -l`, `git merge-base`, relecture des deux fichiers et des gates, `python scripts/architecture/check-private-io.py` et `python scripts/architecture/check-module-boundaries.py` (verts au HEAD) ; **aucun build Maven**, aucun code modifié.

## Goals / Non-Goals

**Goals** : `PRIVATE` et `MANAGER` sous 800 lignes (cibles : ≤ 730 et ≤ 770, estimations ~722 et ~750) ; chaque classe issue du découpage a une seule raison de changer, ses tests dans son module et son entrée dans les gates ; zéro différence observable.

**Non-Goals** : un seuil de taille dans un gate ; un nouveau module ou package ; le port d'ACL de plateforme de l'ADR 0062 ; la migration des appels bruts vers les primitives ; l'unification des managers frères ; la réorganisation interne du cœur de `PRIVATE` ; les quatre autres fichiers de plus de 800 lignes (Q5).

## Decisions

### D1. La preuve d'un remaniement est une caractérisation ; les coutures ont un rouge

Aucun comportement ne change : les tests existants, verts avant et après sans modification d'attente, sont la preuve. Là où une couture est ajoutée pour couvrir sous Linux du code qui ne s'exécute que sous Windows (D5), le test qui l'utilise est écrit d'abord et échoue (il ne compile pas) tant que la couture n'existe pas.

### D2. `PRIVATE` : quatre extractions dans `com.minos.io`

Toutes dans le package et le module actuels (ADR 0044, ADR 0062 : aucune racine nouvelle) :

- **`WindowsDacl`** (visibilité de package, `final`, constructeur privé). `protect(Path)` délègue à une surcharge `protect(Path, String osName, String systemRoot, Launcher)` qui réutilise l'interface `Launcher` existante ; `awaitSuccess` et `createSddlFile` suivent. Les messages d'erreur sont conservés au caractère près ; l'ordre « contrôle du chemin → contrôle de `SystemRoot` → lancement » est inchangé.
- **`WriteDenyDiagnosis`** : `carriesWriteDenyForOwner` (visibilité de package, utilisée par `WriteDenyClassificationTest`), `namesTheProcessUser`, `writeProtected`, `WRITE_RIGHTS`, `WRITE_PROTECTED_MESSAGE` et les deux formes d'`explainDenied` ; celle à chemins reçoit un `Function<Path, AclFileAttributeView>`. Le contrat « le message ne nomme jamais le chemin » est inchangé.
- **`ProcessOwner`** : `current(Path near)` avec son cache `volatile`, `probe(Path near)`, `unverifiable(IOException)`. `PrivateLocalStorage.verifyOwnedByCurrentUser` reste publique, garde sa Javadoc et délègue.
- **`CapabilityProbe`** : interface publique de premier niveau (voir D4).

`PrivateLocalStorage` garde le cœur, la garde H23, les flux privés, `ownerOnlyAcl`, `PROTECTED_LOCATIONS`, le `ThreadLocal` du crochet et les méthodes publiques `useForTesting`, `resetCapabilityProbeForTesting`.

### D3. `MANAGER` : trois extractions dans `…/runtime/install`

Toutes dans le package et le module actuels :

- **`BoundedZipExtraction`** : `extractZipBounded`, `extractDistribution`, `openExtractedFile`.
- **`ManagedToolInstallation`** : `run`, `moveFile`, `moveDirectory`, `writeNewFile`, `deleteRecursively`. `copyPackagedResource` **reste** sur le manager : elle charge une ressource par sa classe et trois tests l'appellent à cet endroit.
- **`ScipJavaClasspathInstallation`** : tout le bloc classpath, dont `swapIn` et `DirectoryMover` ; ses méthodes prennent la racine du classpath ou le listing en paramètre ; `CLASSPATH_FILE` y est déplacée.

`list`, `inspect`, `install`, `executor`, les constantes publiques (`SCIP_*`), `SANDBOXED_EXECUTION_NOT_QUALIFIED` (`:104`) et les diagnostics de prérequis restent sur le manager. Les trois classes sont `final`, de visibilité de package, à constructeur privé : des regroupements de fonctions, pas des surfaces au sens de l'ADR 0045.

### D4. `CapabilityProbe` devient un type de premier niveau (décidé : oui, Q3)

C'est le seul déplacement qui touche un autre module (`MinosApplicationTest` dans `minos-bootstrap`) : un changement de nom de type dans des tests, pas dans la surface de production. Il ôte du code **de test** d'une classe **de production** et fait passer `PRIVATE` de ~781 à ~722 lignes. A15 reste vert : le type est nommé par `PrivateLocalStorage`.

### D5. La couverture : un déplacement est du code neuf, les coutures le couvrent sous Linux

SonarCloud juge le nouveau code module par module (80 %) : chaque ligne déplacée compte. Les tests restent dans le même module, donc la couverture suit. Deux précautions :

- `WindowsDacl.protect` n'est couverte que sous Windows (premier `return` sur le nom du système, `:818`). La couture de la tâche 2.1 la fait exécuter aussi sous Linux.
- Sonar importe les rapports Linux **et** Windows (job `sonar` de `pr-ci.yml`), mais la fusion fichier par fichier n'est pas prouvée (réserve consignée à la PR #421). La tâche 3.6 la vérifie sur la première PR par l'API, fichier par fichier ; un module sous le seuil reçoit des tests, jamais une exclusion.

### D6. Planchers JaCoCo : la couverture agrégée ne baisse pas

Le gate raisonne par préfixe de nom de classe. Découper un fichier change le dénominateur de chaque classe : le manager, une fois ses blocs bien couverts partis, perd une dizaine de points de lignes sans qu'une seule ligne perde sa couverture, et peut franchir son plancher de 0,61. Règle : on **additionne** les compteurs ligne et branche (couverts / total) des classes issues du découpage avant et après ; la somme après n'est pas inférieure à la somme avant (tâches 1.2, 2.6, 3.5). Chaque classe nouvelle de `…/install/` entre dans `m24-polyglot-provider-platform` avec un plancher égal à la plus basse de ses mesures Linux et Windows moins environ cinq points (convention AUD-TST-02, celle du commentaire `:110-111`) ; le plancher du manager est **recalculé sur ce qui lui reste** par la même convention, et la baisse apparente est consignée avec le calcul. Le plancher agrégé du scope (0,50 / 0,28) n'est pas abaissé. Un plancher n'est jamais abaissé sans ce calcul. `com/minos/io/` est un préfixe de package : rien à déclarer pour `PRIVATE`.

### D7. Acquisition et amorçage de `MANAGER` : une exclusion et une option tranchée

Sans eux, `MANAGER` descend à **~750 lignes** (1 004 − 151 − 60 − 41 + ~8 de délégation − ~10 d'imports) : sous 800, marge ~50 lignes.

- **L'acquisition épinglée (~110 lignes) n'entre pas** : couverte à 11 %, elle deviendrait du code neuf à 11 % dans un module jugé à 80 % ; `902e1132` montre que SonarCloud refuse une factorisation de ce bloc. Elle fait l'objet d'un changement ultérieur qui commence par les tests (un `PinnedArtifactSource` factice servant une archive, sans réseau), puis déplace.
- **L'amorçage (191 lignes)** rejoint ce changement ultérieur (Q2).

### D8. Ordre et isolement des lots

Deux PR, une par module, indépendantes : `PRIVATE` (section 2) puis `MANAGER` (section 3). Dans chaque PR, un commit par extraction : une régression se bissecte à la classe près. Aucune condition préalable de fusion.

## Direction des dépendances

Aucun import entre packages ajouté ni retiré ; toutes les classes nouvelles sont dans le package de leur classe d'origine, donc dans le même module. Pas de cycle de packages (A8), pas de racine nouvelle (A14, ADR 0062), pas de dépendance Maven nouvelle ; ADR 0022 et ADR 0044 respectés. Entre classes d'un même package, `ScipJavaClasspathInstallation`, `BoundedZipExtraction` et le manager dépendent de `ManagedToolInstallation`, jamais l'inverse ; `PrivateLocalStorage` dépend de `WriteDenyDiagnosis`, `ProcessOwner` et `CapabilityProbe`, et `CapabilityProbe.real()` de `WindowsDacl` ; aucune de ces classes ne dépend de `PrivateLocalStorage`.

## Qualification des capacités

Sans objet : aucune capacité ajoutée, modifiée ni requalifiée. `PrivateLocalStorage` garde ses niveaux de confidentialité (ADR 0061 ; `openNewTreeFile` reste partiel par construction) et le manager ses états `READY`, `BLOCKED`, `NOT_INSTALLED` ; la mention « exécution sous AppContainer non qualifiée » (`SANDBOXED_EXECUTION_NOT_QUALIFIED`) reste sur le manager.

## Windows et Linux

`WindowsDacl` ne s'exécute qu'avec Windows ; sa couture (D5) la teste sous les deux systèmes, par un `Launcher` factice et un chemin absolu natif de l'hôte. `ProcessOwner` s'exécute sur les deux systèmes (sonde sous POSIX comme sous ACL) ; `WriteDenyDiagnosis` n'est atteint en production que sur une vue ACL, mais ses fonctions pures sont testées partout par `WriteDenyClassificationTest`. L'acquisition de Coursier et Node, propre à Windows, reste en place. Le scope `m24-polyglot-provider-platform` est rouge hors régression sur poste Windows : comparer à `develop` avant d'attribuer un écart au découpage. Aucune branche `os.name` n'est ajoutée ni retirée.

## Risks / Trade-offs

- **Fail-closed** → l'ordre vérification → exécution du lancement d'`icacls`, le refus quand le SDDL est illisible, sans DACL ou porte une entrée `(XD;`, et le refus quand le propriétaire ne peut pas être vérifié restent identiques ; l'agent `minos-security-reviewer` relit `WindowsDacl` et `ProcessOwner` avant la PR (tâche 2.6).
- **Régression silencieuse d'un message** → les messages sans chemin de `PRIVATE` sont affirmés par `IoRefusalMessageTest`, `WriteDenyClassificationTest`, `PrivateLocalStorageDaclReadTest` et `PrivateLocalStorageSharedLocationTest` ; ils restent verts sans modification d'attente.
- **Entrées de l'allowlist et de A12** → oublier une entrée fait échouer le gate deux fois ; l'ordre « classe d'accueil, entrée, gate » dans une même tâche l'évite.
- **Couverture Sonar** → voir D5 ; risque principal, visible seulement une fois la PR ouverte.
- **Tests d'un autre module** → le retypage de `CapabilityProbe` touche `minos-bootstrap` ; rejouer `-pl minos-engine,minos-bootstrap -am`.
- **Lettre de l'ADR 0062** → elle nomme `PrivateLocalStorage` comme lanceur ; voir Q4.
- **Gain** → si, à la relecture (1.1), une extraction ne retire pas les lignes annoncées, le dire et ne pas la faire.

## Décisions du propriétaire

Tranchées le 2026-10-20 :

- **Q1 — cliquet de taille de fichier : non.** Aucun gate de taille n'est ajouté ; le seuil de 800 lignes reste celui du superviseur.
- **Q2 — amorçage depuis la charge utile : dans un changement suivant**, avec l'acquisition de Coursier, Maven et Node (qui commence par des tests).
- **Q3 — `CapabilityProbe` en type public de premier niveau : oui** (D4).

En attente :

- **Q4 — lanceur `icacls` nommé `WindowsDacl` au lieu de `PrivateLocalStorage`.** L'ADR 0062 (Décision § 1) écrit que `PrivateLocalStorage` est le seul lanceur admis du moteur ; après l'extraction, c'est `WindowsDacl`, même package, même mécanisme. **Recommandation** : accepter, remplacer l'entrée A12 et ajouter à l'ADR 0062 une note de mise en œuvre datée (« le lanceur est `com.minos.io.WindowsDacl`, extrait de `PrivateLocalStorage` sans changement de package ») sans changer la décision ; aucun nouvel ADR. Alternative : garder `WindowsDacl` imbriquée, ce qui laisse `PRIVATE` à ~846 lignes et manque l'objectif.
- **Q5 — les quatre autres fichiers de plus de 800 lignes.** **Recommandation** : hors de ce changement. `WindowsAppContainerWorkerSandboxBackend` et `LinuxCgroupJob` sont du confinement (relecture de sécurité dédiée, scopes `provider-sandbox-windows` et `provider-sandbox-linux`, tests de confinement exigés en CI) ; `SnapshotBinaryCodecSupport` porte le codec binaire du snapshot (scope `storage-codec-and-state`) ; `IndexingRunExecutor` est touché par `decouper-les-methodes-d-analyse-et-d-ingestion`. Chacun relève d'un changement mesuré à part, s'il est demandé.
