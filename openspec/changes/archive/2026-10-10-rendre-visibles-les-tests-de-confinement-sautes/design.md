# Design

## Context

Sprint 1 de l'audit du 10 octobre 2026 : AUD-TST-03 et AUD-TST-06. Les deux concernent la **preuve** du confinement des providers (bubblewrap + prlimit, cgroup v2, AppContainer + Job Object), pas son comportement. Le HEAD analysé est `816cdd0c`.

## État vérifié au HEAD

### AUD-TST-03 — confirmé, périmètre plus large que l'audit

Les sites cités par l'audit existent (`LinuxCgroupJobContainmentTest.java:183`, `LinuxCgroupJobOwnershipIsolationTest.java:324`, `LinuxBubblewrapWorkerSandboxBackendTest.java:36,71,131,160`, `LinuxStrongProcessOwnershipContainmentTest.java:76`). `grep -rn "tests.required" minos-*/src/test` : seule la propriété PostgreSQL (`PostgresTestSupport.java:32`, `pom.xml:48` et `:212`, `pr-ci.yml:248`) existe. Le décompte complet est de **26 sites de disponibilité** :

| Classe (`minos-runtime-local/src/test/java/com/minos/runtime/local/`) | Sites | Garde de plateforme |
|---|---:|---|
| `LinuxBubblewrapWorkerSandboxBackendTest` | 4 (`:36,71,131,160`) | `if (…) return;` ×4 (`:34,69,129,158`) |
| `LinuxCgroupJobContainmentTest` | 3 (`:135,160,183`) | `@EnabledOnOs(OS.LINUX)` (`:30`) |
| `LinuxCgroupJobOwnershipIsolationTest` | 1 (`:324`) | **aucune** |
| `LinuxStrongProcessOwnershipContainmentTest` | 1 (`:76`, capacité) | `@EnabledOnOs(OS.LINUX)` (`:26`) |
| `WindowsAppContainerWorkerSandboxBackendTest` | 10 (`:43,76,165,196,241,289,314,462,508,529`) | `if (…) return;` ×14 |
| `WindowsJobObjectContainmentTest` | 3 (`:44,132,166`) | `@EnabledOnOs(OS.WINDOWS)` (`:37`) |
| `WindowsStrongProcessOwnershipContainmentTest` | 2 (`:135,169`, capacité) | `@EnabledOnOs(OS.WINDOWS)` (`:64`) |
| `WindowsAppContainerRecoveryOwnershipTest` | 1 (`:267`) | `@EnabledOnOs(OS.WINDOWS)` (`:35`) |
| `WindowsNonElevatedIndexingTest` | 1 (`:79`) | `if (…) return;` ×2 (`:41,76`) |

Le chiffre « 38 tests sautés » de l'audit est exact sur le poste Windows du 10/10 (22 par `@EnabledOnOs` : « Disabled on operating system: Windows », 16 par hypothèse : message vide). Le rapport ne dit pas lesquels relèvent du confinement : c'est le défaut.

### La question bloquante : les runners GitHub permettent-ils ces tests ? **Oui, aujourd'hui.**

Journaux du run `pr-ci` 38008850294 (push `816cdd0c` sur `develop`, vert), lus en lecture seule :

| Job | Classes de confinement | Tests / sautés |
|---|---|---|
| `Verify (ubuntu-24.04)` | `LinuxBubblewrapWorkerSandboxBackendTest` 4/0, `LinuxBubblewrapWorkerSandboxIsolationTest` 3/0, `LinuxCgroupJobContainmentTest` 8/0, `LinuxCgroupJobOwnershipIsolationTest` 10/0, `LinuxStrongProcessOwnershipContainmentTest` 1/0 (0,9 s), `LinuxCgroupStaleRecoveryTest` 18/0 | tout est exécuté ; `minos-runtime-local` : 343 tests, 37 sautés (tous Windows) |
| `Verify (windows-2022)` | `WindowsAppContainerWorkerSandboxBackendTest` 15/0 (49 s), `WindowsJobObjectContainmentTest` 3/0, `WindowsStrongProcessOwnershipContainmentTest` 2/0 (94 s), `WindowsAppContainerRecoveryOwnershipTest` 9/0 (72 s) | tout est exécuté sauf `WindowsNonElevatedIndexingTest` 2/**1** (exemption `CI` voulue, `:64`) ; 343 tests, 39 sautés |

Conséquences :

1. Poser la propriété à `true` sur les deux étapes **ne rougit pas** la CI actuelle. La crainte de l'énoncé (runners Ubuntu sans bubblewrap ni délégation cgroup) est infirmée par les journaux, et par `install-linux-sandbox-toolchain.sh` + `delegate-linux-cgroup.sh`, déjà fail-closed (versions de paquets épinglées, dérive d'image = échec explicite).
2. La même lecture confirme AUD-TST-06 : sous Linux, `WindowsAppContainerWorkerSandboxBackendTest` rapporte **15 tests, 0 sauté** et `WindowsNonElevatedIndexingTest` 2/0 ; sous Windows, `LinuxBubblewrapWorkerSandboxBackendTest` rapporte 4/0. Ces tests passent à vide.
3. Piège d'ordre : sans `@EnabledOnOs`, `LinuxCgroupJobOwnershipIsolationTest` se saute sous Windows **parce que la racine cgroup n'existe pas** (10 sautés dans le journal Windows). Avec la propriété à `true`, ce saut deviendrait un échec. La séparation plateforme / capacité (TST-06) est donc un **préalable** de TST-03, pas un complément.

### AUD-TST-06 — confirmé (21 sites)

`WindowsAppContainerWorkerSandboxBackendTest` 14 (`:72,162,193,238,286,311,335,349,387,413,436,449,505,526`), `LinuxBubblewrapWorkerSandboxBackendTest` 4, `WindowsNonElevatedIndexingTest` 2, `CommandLocatorTest` 1 (`:169`). Le reste du module utilise `@EnabledOnOs`. Attention : `WindowsAppContainerWorkerSandboxBackendTest.qualificationOnlyPermitsSandboxClaimOnWindows` (`:38`) ne se termine pas par un `return` : il **branche** sur la plateforme et vérifie aussi, hors Windows, qu'aucun bac à sable n'est revendiqué. Il doit rester un test multi-plateforme.

## Goals / Non-Goals

**Goals** : qu'un confinement indisponible sur l'OS de qualification fasse échouer le job de cet OS ; qu'un test inapplicable soit **compté** sauté ; que les sauts qui restent soient nommés.

**Non-Goals** : modifier le code de production du bac à sable ; transformer en échec un saut d'environnement sans rapport avec la disponibilité du confinement ; vérifier la plateforme macOS (hors qualification, voir plus bas).

## Decisions

### D1. Propriété `minos.sandbox.tests.required`

| Propriété | Confinement applicable à l'OS ? | Disponible ? | Résultat |
|---|---|---|---|
| `true` | oui | non | **échec**, avec la raison et les diagnostics de la découverte |
| `true` | oui | oui | le test s'exécute |
| absente / `false` | oui | non | saut visible, même message qu'aujourd'hui |
| quelconque | non (autre OS) | – | saut par `@EnabledOnOs` ; la propriété n'intervient pas |

Calquée sur `PostgresTestSupport` : lecture par `Boolean.getBoolean`, déclarée dans le POM parent (`pom.xml:48`, valeur `false`) et transmise à Surefire (`pom.xml:212`) pour les forks. Passée à `true` par **les deux** étapes `Maven clean verify` de `pr-ci.yml` (Linux : à côté de `-Dminos.postgresql.tests.required=true` ; Windows : entre guillemets, `shell: powershell`). Pas de nouvelle variable d'environnement.

### D2. `SandboxTestSupport` (test de `minos-runtime-local`, paquet `com.minos.runtime.local`)

Un point unique de décision, testable sans confinement réel :

- `static void decide(boolean required, boolean available, Supplier<String> reason)` : lève `AssertionFailedError` si `required && !available`, `TestAbortedException` si `!required && !available`.
- Entrées de commodité : `requireBackend(Optional<?> discovered, String quoi)`, `requireDelegatedCgroupRoot()` (retourne le chemin), `requireStrongCapability(boolean strong, List<String> diagnostics)`.
- `SandboxTestSupportTest` couvre la table D1 sans toucher à une propriété globale (la lecture de la propriété est isolée dans un seul appel).

Le paquet `com.minos.runtime.local` appartient à `minos-runtime-local` (ADR 0044) : aucune règle A3 en jeu.

### D3. Ce qui devient « requis » et ce qui reste un saut

Convertis en `SandboxTestSupport` : les 26 sites du tableau (disponibilité du bac à sable, racine cgroup déléguée, capacité de propriété forte).

Conservés en `assumeTrue`, **nommés** (hypothèses d'environnement, non liées à la disponibilité du confinement) :

| Site | Hypothèse |
|---|---|
| `LinuxCgroupJobContainmentTest:53` | `/dev/shm` présent |
| `LinuxBubblewrapWorkerSandboxBackendTest:72` | `python3` présent |
| `WindowsAppContainerWorkerSandboxBackendTest:341` | le compte ne détient pas `WRITE_DAC` sur `%SystemRoot%` |
| `WindowsAppContainerRecoveryOwnershipTest:143` | le verrou étranger n'est pas ouvrable en écriture |
| `WindowsNonElevatedIndexingTest:64` | pas de runner `CI` élevé : exemption **voulue** (un runner GitHub est élevé par conception) ; reste le seul saut Windows de confinement en CI |
| `ProcessTableTest`, `ProviderWorkspaceFilesTest`, `ProcessIndexerExecutorTest` | liens symboliques / jonctions NTFS (hors classes de qualification) |

### D4. Gardes de plateforme

Les 21 `if (plateforme) return;` deviennent `@EnabledOnOs` : au niveau classe quand toute la classe est propre à un OS (`LinuxBubblewrapWorkerSandboxBackendTest`), au niveau méthode sinon (`WindowsAppContainerWorkerSandboxBackendTest`, sauf `qualificationOnlyPermitsSandboxClaimOnWindows`). `LinuxCgroupJobOwnershipIsolationTest` reçoit `@EnabledOnOs(OS.LINUX)`. Cet ordre précède D1 (voir constat 3).

### D5. Aucune modification de `check-*.py`

`pr-ci.yml` ne change que par des arguments de commande. `check-single-execution.py` cherche `mvnw … verify` (une ligne par OS, inchangées en nombre) ; `check-audit-remediation-v2.py` exige les noms d'étape `Maven clean verify (Unix, PostgreSQL required)` et `Maven clean verify (Windows)` (conservés) ; `check-p0-p2.py` exige `delegate-linux-cgroup.sh --attach-pid $$` (conservé). `check-current-docs.py` exige `-Dminos.postgresql.tests.required=true` : inchangé. Pour que la présence du nouvel argument soit elle aussi gardée, une assertion **de structure** (chaque ligne `mvnw … verify` de `pr-ci.yml` porte `-Dminos.sandbox.tests.required=true`) est ajoutée à `validate_ci_contracts()` de `check-current-docs.py`, avec le même motif de détection que `check-single-execution.py`.

### D6. Un second garde sur les rapports Surefire : écarté

Un script lisant `minos-runtime-local/target/surefire-reports/TEST-*.xml` pour refuser tout saut par hypothèse hors liste fermerait aussi la porte à un futur `assumeTrue` brut. Il est écarté ici : il demande une liste de sauts admis par classe et par OS, difficile à maintenir sur 16 hypothèses légitimes, pour un bénéfice que D2 (point unique) et la revue offrent déjà. **Limite assumée** : un nouvel `assumeTrue` direct dans une classe de confinement reste possible. À rouvrir si le cas se produit.

### D7. Vérification locale sans CI

- Windows (poste du mainteneur, où AppContainer est disponible : les rapports du 10/10 montrent 0 sauté) : un `test` ciblé avec `-Dminos.sandbox.tests.required=true` doit réussir ; la lecture des rapports Surefire doit montrer 0 sauté pour les classes `Windows*` (hors `WindowsNonElevatedIndexingTest` sous `CI`) et 100 % de sauts pour `LinuxBubblewrapWorkerSandboxBackendTest` (4/4).
- Preuve négative (échec quand le confinement manque) : `SandboxTestSupportTest`, sur tous les OS.
- Linux : **non vérifiable localement** (poste Windows). Tâche à autorisation : un run `pr-ci` sur une PR, lecture des journaux Linux.
- Pose de `JAVA_HOME` avant toute commande Maven (AUD-TST-18).

## Qualification des capacités nouvelles

| Capacité | Statut | Raison |
|---|---|---|
| Qualification exigible sous Windows | **Qualifiée** | Poste (0 sauté) et run CI 38008850294 (AppContainer 15/0). |
| Qualification exigible sous Linux | **Partielle** | Journaux du 10/10 : 0 sauté sur les 8 classes ; mais le mode « requis » n'a pas encore été exécuté sur un runner. |
| macOS | **Non supportée** | Aucune CI ; `@EnabledOnOs` saute les classes Linux et Windows ; la propriété n'a pas d'effet. |

## Windows et Linux

Le mode requis s'applique à chaque OS à sa propre qualification : Linux exige bubblewrap + prlimit + racine cgroup déléguée, Windows exige AppContainer + Job Object. Comportement propre à une plateforme : sous Windows hébergé par GitHub, le compte est élevé (d'où l'exemption `WindowsNonElevatedIndexingTest`) ; sous Linux, la racine cgroup est fournie par `delegate-linux-cgroup.sh --attach-pid $$` dans la même étape que Maven.

## Risks / Trade-offs

- [Dérive d'une image de runner (AppArmor, cgroup) : la CI devient rouge] → c'est le but ; `delegate-linux-cgroup.sh` rougit déjà sur les versions de paquets. Dérogation éventuelle : retirer l'argument de l'étape de l'OS concerné, avec un commentaire daté, par décision humaine.
- [`-Dminos.sandbox.tests.required=true` mal transmis par `powershell` 5.1 (points dans le nom) ou par Surefire : la propriété serait ignorée et le comportement actuel reviendrait sans échec] → `SandboxTestSupportTest.requiredModeIsActiveOnThePullRequestVerificationJob` : dans le job `verify` du workflow `PR Validation` (variables `GITHUB_WORKFLOW` et `GITHUB_JOB`), il exige que la propriété soit vraie dans le fork. Il est volontairement limité à ce job : la variable `CI` est aussi définie dans les workflows de rejeu manuel (`historical-qualification.yml`) et d'audit (`code-audit.yml`, PIT), qui ne passent pas la propriété. Hors de ce job le test est sauté ; le gate `check-current-docs.py` (tâche 3.1) garde en plus la présence de l'argument dans `pr-ci.yml`.
- [Une classe de confinement oubliée] → le décompte de 26 sites est contrôlé par `grep -rn "assumeTrue(discovered\|assumeTrue(root\|assumeTrue(executor" minos-runtime-local/src/test` : il doit retourner 0 à la clôture.

## Direction des dépendances (ADR 0022)

Aucune dépendance Maven ajoutée ; tests de `minos-runtime-local` uniquement.

## Décisions qui vous attendent

1. **Runners** : la question est tranchée par les journaux (rien ne rougit). Vous n'avez à décider que de l'**autorisation d'un run CI** pour constater le mode « requis » sous Linux.
2. **Exemption `CI` de la non-élévation** : à garder (recommandé) ou à remplacer par un test qui n'exige pas un compte non élevé.
3. **Écart de portée** : 26 sites de disponibilité répartis sur 9 classes, dont quatre classes Windows que l'audit ne citait pas (`WindowsJobObjectContainmentTest`, `WindowsStrongProcessOwnershipContainmentTest`, `WindowsAppContainerRecoveryOwnershipTest`, `WindowsNonElevatedIndexingTest`).
