# Design

## Context

Tâche **SH-08** du plan `docs/project/roadmap/storage-hexagonal-2026-10/` et constat **AUD-ARC-05**. Décision D2 du 2026-10-22, reprise par l'ADR 0068 § 2 : le mécanisme d'ACL Windows sort du moteur par un port d'E/S de plateforme fourni par `minos-runtime-local`, et `hosted` reste dans le moteur. Prérequis AUD-SEC-06 fusionné (PR #491). La mesure du nombre d'objets durcis est faite ; elle est consignée dans `docs/project/audits/2026-10-10/SUIVI.md`, section « Phase 2 — mesure des objets durcis ».

## État vérifié au HEAD

HEAD : `develop` `6566ddda`.

### Le mécanisme dans le moteur

`PrivateLocalStorage` (`minos-engine/src/main/java/com/minos/io/PrivateLocalStorage.java`, 1 032 lignes) :

- **`harden`** (`:560-594`). Sous POSIX, il pose les permissions et revient (`:566-570`) : le port n'y sert pas. Sur un système de fichiers à ACL, au premier contact de l'objet par le processus (`PROTECTED_LOCATIONS`, `:580`) ou si la DACL diffère, il appelle `requireNoConditionalDeny` (`:582`), puis `setAcl` (`:584`), puis `protectFromInheritance` (`:591`) et mémorise l'objet (`:592`).
- **`WindowsDacl`** (`:812-924`, classe imbriquée) porte les deux lancements :
  - `protect` (`:818-834`) lance `icacls <cible> /inheritance:d /L /q` (`:828`) ;
  - `requireNoConditionalDeny` (`:862-910`) lance `icacls <cible> /save <fichier> /L /q` (`:866`), avec une couture `Launcher` pour les tests (`:874-876`), puis cherche une entrée `(XD;` dans le SDDL ;
  - le fichier SDDL est créé par `Files.createTempFile` (`createSddlFile`, `:915-923`), dans `TEMP`, `TMP` ou le profil.
- **`CapabilityProbe`** (`:942-987`), interface publique de test, a quatre méthodes. `real()` délègue les deux méthodes Windows à `WindowsDacl` (`:977-984`), et `useForTesting` (`:1008`) l'installe par fil d'exécution.

### Qui en dépend

- **Production** : neuf modules appellent les méthodes publiques statiques de `PrivateLocalStorage`. Aucun ne nomme `WindowsDacl`, qui est privée au package. `DoctorCommand` (CLI, `:250`) appelle `privacyOf`, qui ne lit l'ACL que par Java et ne lance rien.
- **Tests Windows**, mesurés : dans un worktree jetable, les deux méthodes de `CapabilityProbe.real()` échouaient toujours. Les tests de `minos-engine`, `minos-storage-local`, `minos-storage-postgresql`, `minos-application` et `minos-integration-git` ont alors été lancés sur ce poste Windows. **95 classes** échouent sur cette erreur, avec 381 tests (moteur 11 classes, stockage local 69, PostgreSQL 8, application 3, Git 4). Trois autres classes Git échouent sans elle (`GitIntelligenceActivityBudgetTest`, `GitIntelligenceServiceTest`, `GitIntelligenceTrustTest`) : leur échec ne vient pas de l'expérience et n'est pas investigué ici.
- Ces cinq modules ne peuvent pas avoir `minos-runtime-local` sur leur classpath de test. Pour le moteur, ce serait un cycle Maven ; pour l'application et les stockages, l'ADR 0042 l'interdit (A2 : application et adaptateurs ne se voient pas).

### Le précédent du port de plateforme

`com.minos.orchestration.ExecutionPathIdentityProvider` (`minos-engine`) est implémenté par `com.minos.runtime.local.sandbox.WindowsExecutionPathIdentityProvider`. Il est enregistré dans `minos-runtime-local/src/main/resources/META-INF/services/`, et `StableFileSystemIdentity` (`:52`, `:63-64`) le découvre par `ServiceLoader.load(…, loader)` avec le chargeur du moteur. Avec zéro ou plusieurs implémentations, l'identité est refusée. Le document généré montre déjà l'arête d'exécution `minos-engine` → `minos-runtime-local` qu'il crée.

### Le lanceur d'`icacls` du runtime local

`WindowsAclGrants` (`minos-runtime-local/.../sandbox/WindowsAclGrants.java`, privé au package) lance `icacls /grant` et `/remove:g` pour le bac à sable AppContainer. Il les lance par chemin absolu sous `SystemRoot`, sans shell, avec une attente bornée et une couture `Launcher`. Il passe le chemin sans préfixe, et le défaut des chemins longs le touche donc aussi.

### Le défaut des chemins longs (mesuré)

- `icacls` échoue en code 3 sur un chemin de plus de 260 caractères, pour `/save` comme pour `/inheritance:d`.
- Avec le préfixe `\\?\`, les deux réussissent sur 335 caractères, et `/save` écrit le SDDL (102 octets).
- Sous un `MINOS_HOME` de 140 caractères, le fichier `windows-appcontainer-plan.txt` d'un scope atteint 261 caractères, et l'index refuse.

### Le reste

- **`hosted`** (`minos-engine/src/main/java/com/minos/hosted`) : aucun `ProcessBuilder`, `AclFileAttributeView`, `PrivateLocalStorage` ni `Files.write`/`create` (recherche au HEAD). Il n'a aucun mécanisme d'E/S de plateforme : la cartographie demandée par l'ADR 0068 § 3 ne justifie pas de le sortir.
- **SpotBugs** : `quality/spotbugs-baseline/minos-engine.xml` porte 8 entrées sur `com.minos.io.PrivateLocalStorage$WindowsDacl`.
- **JaCoCo** : la portée `confined-io-primitives` (`scripts/quality/check-jacoco.py:40-46`, préfixe `com/minos/io/`, planchers 0,78 / 0,68) couvre aujourd'hui `WindowsDacl`.
- **E/S privées** : l'entrée `Files.createTempFile` (max 4) de `PrivateLocalStorage` dans `scripts/architecture/private-io-allowlist.json` (`:116-121`) inclut la création du fichier SDDL. Ce fichier ne peut pas passer par `createPrivateTempFile`, qui durcirait et rappellerait le port en boucle.
- **A16** compte 4 appels `ServiceLoader.load` de production ; l'auto-test l'écrit en dur (`test_the_real_tree_passes_a_loader_at_every_site`).

## Goals / Non-Goals

**Goals** : aucun lancement de processus dans le moteur (A12 vide) ; port découvert fail-closed ; un seul lanceur d'`icacls` dans `minos-runtime-local`, sûr pour les chemins longs ; comportement Windows inchangé, prouvé par les tests existants ; tests des modules inférieurs verts sous Windows sans dépendre du runtime local.

**Non-Goals** : remplacer `icacls` ; garder la mémoire des objets protégés entre processus ; changer `privacyOf` (lot 2 d'AUD-ARC-01) ; déplacer `hosted`.

## Decisions

### D1 — Le port est découvert par `ServiceLoader`, pas installé par `minos-bootstrap`

`PrivateLocalStorage` est statique et sert avant toute composition : `MinosLauncher`, `DockerRuntimeBootstrap`, le pont NEXUS, les scripts d'installation Java. Une installation par la racine de composition laisserait ces chemins sans mécanisme. Le port est donc découvert, comme `ExecutionPathIdentityProvider`. La découverte est paresseuse, faite une fois par processus, avec le chargeur de `PrivateLocalStorage` (A16), et son résultat est gardé. Zéro implémentation n'est une erreur que si le port est consulté, donc sur un système de fichiers à ACL. Plusieurs implémentations sont toujours une erreur. L'ADR 0068 dit « fourni par `minos-runtime-local` et injecté » : la découverte par `ServiceLoader` est l'injection que l'ADR 0042 pratique pour les composants d'exécution optionnels.

Alternative écartée : un point d'installation statique appelé par `minos-bootstrap`. Il oublie les chemins hors composition, et son état global est modifiable par tout code du processus.

### D2 — Doublure de test dans le `test-jar` du moteur, enregistrée par les seuls modules inférieurs

- La doublure `com.minos.testsupport.IcaclsTestAccessControl` lance `icacls` dans du code de test. A12 ne compte que la production. Elle est publiée dans le `test-jar` de `minos-engine`, qui existe déjà (`com.minos.testsupport`, `minos-engine/pom.xml:26-37`).
- Seuls `minos-engine`, `minos-storage-local`, `minos-storage-postgresql`, `minos-application` et `minos-integration-git` l'enregistrent, chacun dans son `src/test/resources/META-INF/services/com.minos.io.PlatformAccessControl`.
- Le `test-jar` exclut ce fichier de services. Sans cette exclusion, un module supérieur qui voit à la fois le `test-jar` et `minos-runtime-local` trouverait deux implémentations, et le durcissement échouerait (D1).
- `minos-application` et `minos-integration-git` gagnent la dépendance de test au `test-jar`. Elle n'ajoute aucune arête, comme pour les modules qui l'ont déjà.
- Un **test de parité** dans `minos-runtime-local` (Windows) applique la doublure et l'implémentation réelle à deux objets identiques et compare les SDDL relus. Les deux ne peuvent pas diverger sans faire échouer la CI Windows.

Alternatives écartées :

- (b) dépendance de test des modules inférieurs à `minos-runtime-local` : c'est un cycle pour le moteur, et c'est contraire à A2 pour l'application et les stockages ;
- (c) une doublure qui ne fait rien : le contrôle de confidentialité qui suit le durcissement verrait les entrées héritées, et refuserait ;
- (d) une doublure par l'API Foreign Function : c'est un second mécanisme à maintenir, sans gain pour les tests.

Coût accepté : environ 120 lignes du mécanisme sont dupliquées dans du code de test, et le test de parité les garde alignées.

### D3 — Un seul lanceur d'`icacls`, avec le préfixe `\\?\`

`com.minos.runtime.local.acl.WindowsIcacls` lance `icacls` :

- par chemin absolu sous `SystemRoot`, sans shell, sortie et erreur jetées ;
- avec une attente bornée de 10 s, après laquelle le processus est tué ;
- avec un chemin cible absolu, contrôlé comme aujourd'hui (aucun caractère de contrôle ni `*?"<>|`), puis préfixé par `\\?\` (`\\?\UNC\` pour un chemin UNC).

`WindowsIcaclsAccessControl` implémente le port par `/save` et `/inheritance:d`, avec la logique XD actuelle. `WindowsAclGrants` délègue au même lanceur. Les refus restent sans chemin et sans sortie du processus.

### D4 — Le package `acl` du runtime local est gardé par A10

`com.minos.runtime.local.acl` rejoint `RUNTIME_LOCAL_GUARDED_PACKAGES`, sans type exporté. Aucun autre module ne le nomme : le port est atteint par `ServiceLoader`.

### D5 — `CapabilityProbe` garde sa forme

Les tests qui installent une doublure par `useForTesting` ou nomment `CapabilityProbe` (5 fichiers de test de `minos-engine`, 1 de `minos-bootstrap`) restent inchangés. `real()` délègue les deux méthodes Windows au port découvert au lieu de `WindowsDacl`.

### D6 — Les entrées SpotBugs et l'entrée d'E/S privées suivent le code

Les 8 entrées SpotBugs de `PrivateLocalStorage$WindowsDacl` passent dans `quality/spotbugs-baseline/minos-runtime-local.xml`, sous le nom de la nouvelle classe et avec la même raison écrite dans `docs/developer/quality/code-audit.md`. Le code est le même ; une alerte réellement nouvelle se corrige dans le code. L'entrée `Files.createTempFile` de `PrivateLocalStorage` passe de 4 à 3, et une entrée de 1 est créée pour `WindowsIcaclsAccessControl` (fichier SDDL) avec sa raison : le fichier ne peut pas passer par la primitive qui l'appelle.

## Direction des dépendances (ADR 0068)

Aucune arête Maven de compilation n'est ajoutée. Le port vit dans le moteur et son implémentation dans `minos-runtime-local`, qui dépend déjà du moteur. Le fichier de services ajoute un service à l'arête d'exécution `minos-engine` → `minos-runtime-local`, déjà documentée. `minos-application` et `minos-integration-git` gagnent seulement une dépendance de test au `test-jar` du moteur, qu'ils ont déjà en compilation. `com.minos.runtime.local.acl` est sous la racine `runtime` de son module (A14). Aucun cycle de packages : `acl` ne dépend que de `com.minos.io` et du JDK, et `sandbox` dépend de `acl`.

## Capacités

Aucune capacité produit n'est ajoutée ni requalifiée. La confidentialité des dossiers privés sous Windows reste celle de l'ADR 0061. Les chemins de plus de 260 caractères, refusés aujourd'hui, deviennent acceptés ; c'est une correction de défaut, pas une capacité nouvelle.

## Windows et Linux

Sous Linux et macOS, `harden` prend la branche POSIX et ne consulte jamais le port : aucun changement de comportement, et l'absence d'implémentation n'y est jamais une erreur. Sous Windows, le mécanisme est identique ; seuls son emplacement et le préfixe `\\?\` changent. Les tests Windows (`PrivateLocalStorageWindowsAclTest` 20, `PrivateLocalStorageDaclReadTest` 10, `WindowsAclGrantsTest` 9, `WindowsAppContainerWorkerSandboxBackendTest` 15) tournent dans `Verify (windows-2022)`, sans `@Disabled` ajouté ni assertion retirée.

## Coût

| Poste | Estimation au HEAD |
|---|---|
| Couverture du nouveau code (SonarCloud, 80 % par module) | `minos-runtime-local` : environ 160 lignes nouvelles (`WindowsIcacls`, `WindowsIcaclsAccessControl`), couvertes par les tests déplacés de `PrivateLocalStorageDaclReadTest` (couture `Launcher`, tous systèmes) et par les tests Windows réels. `minos-engine` : environ 30 lignes (découverte, refus), couvertes par des tests du moteur avec des chargeurs de classes à zéro, une et deux implémentations |
| Tests déplacés | `PrivateLocalStorageDaclReadTest` (10) vers `minos-runtime-local`, dans le package `com.minos.runtime.local.acl` (A3 : un test vit dans le module de son package) et réécrit sur la nouvelle classe. `PrivateLocalStorageWindowsAclTest` reste dans le moteur, avec la doublure ; un test Windows de bout en bout du port réel s'ajoute dans `minos-runtime-local` |
| Portées de `scripts/quality/check-jacoco.py` | `confined-io-primitives` à re-mesurer sur les deux systèmes après le retrait de `WindowsDacl` (aucun plancher abaissé sans mesure). Le nouveau package `acl` entre dans une portée existante du runtime local, ou en reçoit une, avec plancher mesuré |
| Gates littéraux | `check-module-boundaries.py` : `PROCESS_LAUNCHERS` vide, `RUNTIME_LOCAL_GUARDED_PACKAGES` + `acl`, auto-test (`test_the_real_private_storage_has_its_two_icacls_sites` devient « aucun lanceur », et le compte A16 passe de 4 à 5). `private-io-allowlist.json` (D6). Recherche faite : aucun `scripts/remediation/check-*.py` ne cite `WindowsDacl`, `icacls` ou `CapabilityProbe` |
| Bases SpotBugs | 8 entrées déplacées de `minos-engine.xml` vers `minos-runtime-local.xml` (D6) |
| Documentation | ADR 0068 (mise en œuvre), `docs/developer/quality-gates.md` (A12 vide, A10), `docs/developer/quality/code-audit.md`, `SUIVI.md`, plan du chantier |

## Risks / Trade-offs

- **Divergence entre la doublure et le mécanisme réel** : le test de parité (D2) la refuse.
- **Deux implémentations visibles dans un module supérieur** : l'exclusion du fichier de services du `test-jar` l'empêche (D2). Un test de `minos-bootstrap` affirme qu'une seule implémentation est découverte.
- **Une JVM qui embarque MINOS sans `minos-runtime-local`, sous Windows** : le durcissement refuse, par construction (fail-closed). Le JAR distribué embarque le runtime local.
- **Récursion** : le fichier SDDL ne doit pas passer par les primitives qui appellent le port (D6). Un test le vérifie : écrire dans un dossier privé neuf ne lance qu'un `/save` par objet.
- **Préfixe `\\?\`** : il désactive la normalisation des chemins. La cible est déjà absolue et normalisée par `PrivateLocalStorage` (`toAbsolutePath().normalize()`), et un test couvre un chemin UNC.

## Recouvrements avec les changements actifs

| Changement | Fichiers communs | Effet |
|---|---|---|
| `decouper-les-classes-de-plus-de-800-lignes` (0/19) | `PrivateLocalStorage.java`, `check-module-boundaries.py` (A12), tests de `com.minos.io` | il passe après (décision du 2026-10-25). Son extraction de `WindowsDacl` et sa décision Q4 deviennent sans objet ; `PrivateLocalStorage` perd environ 120 lignes ici |
| `lire-le-registre-sans-ecrire` et les autres | aucun fichier de ce changement | sans effet |

## Migration Plan

Une PR vers `develop`, branche `refactor/extraire-le-port-d-acl-de-plateforme`. Ordre : port et découverte (rouge : refus sans implémentation), lanceur et implémentation (rouge : chemin long), doublure et parité, retrait de `WindowsDacl`, cliquets. Retour arrière : revert de la PR, car aucun format ni aucune donnée ne change.

## Open Questions

Décisions D1 (`ServiceLoader`) et D2 (doublure dans le `test-jar`) : recommandations soumises au propriétaire avant l'implémentation.
