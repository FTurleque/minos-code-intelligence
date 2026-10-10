# Spec Delta

## ADDED Requirements

### Requirement: Un confinement indisponible fait échouer sa qualification quand elle est exigée
Quand la propriété `minos.sandbox.tests.required` vaut `true`, un test de qualification dont le mécanisme de confinement de la plateforme courante (bubblewrap, cgroup v2 délégué, propriété de processus forte, AppContainer) est indisponible SHALL échouer en citant les diagnostics de la découverte ; sinon il SHALL être sauté avec la même raison. Un mécanisme disponible SHALL être exercé quelle que soit la propriété.

#### Scenario: Confinement absent et qualification exigée
- **WHEN** `SandboxTestSupportTest` appelle la décision avec « exigé = vrai » et « disponible = faux »
- **THEN** elle lève un échec d'assertion dont le message contient la raison fournie

#### Scenario: Confinement absent et qualification facultative
- **WHEN** la décision est appelée avec « exigé = faux » et « disponible = faux »
- **THEN** elle abandonne le test (saut visible dans le rapport Surefire) avec la raison fournie

#### Scenario: Confinement présent
- **WHEN** la décision est appelée avec « disponible = vrai », exigé ou non
- **THEN** elle ne lève rien et le test s'exécute

#### Scenario: Racine cgroup absente
- **WHEN** la propriété vaut `true` sur un hôte Linux sans racine cgroup v2 déléguée et que `LinuxCgroupJobContainmentTest` ou `LinuxCgroupJobOwnershipIsolationTest` s'exécute
- **THEN** le test échoue en citant la variable d'environnement de la racine, et ne se saute pas

### Requirement: La CI exige la qualification du confinement sur chacun de ses deux systèmes
Chaque commande Maven `verify` de `pr-ci.yml` (Ubuntu 24.04 et Windows Server 2022) SHALL passer `-Dminos.sandbox.tests.required=true`. Dans un job de CI, la propriété SHALL être effectivement vraie dans le processus des tests.

#### Scenario: Argument absent d'une commande
- **WHEN** une des deux commandes `mvnw … verify` de `pr-ci.yml` ne porte pas `-Dminos.sandbox.tests.required=true`
- **THEN** `python scripts/docs/check-current-docs.py` se termine avec le code 1 et nomme la ligne

#### Scenario: Propriété perdue en route
- **WHEN** le job `verify` du workflow `PR Validation` s'exécute et que la propriété n'est pas transmise au processus des tests
- **THEN** `SandboxTestSupportTest.requiredModeIsActiveOnThePullRequestVerificationJob` échoue

#### Scenario: Qualification effectivement exécutée sur Windows
- **WHEN** le job `Verify (windows-2022)` termine
- **THEN** les rapports Surefire de `WindowsAppContainerWorkerSandboxBackendTest`, `WindowsJobObjectContainmentTest`, `WindowsStrongProcessOwnershipContainmentTest` et `WindowsAppContainerRecoveryOwnershipTest` ne comptent aucun test sauté, et le seul saut de confinement restant est l'exemption nommée de `WindowsNonElevatedIndexingTest`

#### Scenario: Qualification effectivement exécutée sur Linux
- **WHEN** le job `Verify (ubuntu-24.04)` termine
- **THEN** les rapports Surefire de `LinuxBubblewrapWorkerSandboxBackendTest`, `LinuxBubblewrapWorkerSandboxIsolationTest`, `LinuxCgroupJobContainmentTest`, `LinuxCgroupJobOwnershipIsolationTest` et `LinuxStrongProcessOwnershipContainmentTest` ne comptent aucun test sauté

### Requirement: Un test propre à une plateforme est compté comme sauté sur l'autre
Un test de `minos-runtime-local` qui ne s'applique qu'à une plateforme SHALL être restreint par `@EnabledOnOs` et SHALL NOT se terminer par un `return` anticipé qui le fait passer à vide. Un test qui vérifie le comportement sur les deux plateformes (par exemple l'absence de revendication de bac à sable hors Windows) SHALL rester exécuté sur chacune.

#### Scenario: Test Linux sur un poste Windows
- **WHEN** `LinuxBubblewrapWorkerSandboxBackendTest` s'exécute sous Windows
- **THEN** son rapport Surefire indique 4 tests et 4 sautés, avec la raison « Disabled on operating system »

#### Scenario: Test Windows sur Linux
- **WHEN** `WindowsAppContainerWorkerSandboxBackendTest` s'exécute sous Linux
- **THEN** 14 de ses 15 tests sont sautés et `qualificationOnlyPermitsSandboxClaimOnWindows` s'exécute et réussit

#### Scenario: Plus de passage à vide
- **WHEN** on cherche dans les tests de `minos-runtime-local` un `if` sur `currentPlatform()` ou `isWindows()` dont le corps est `return;`
- **THEN** `grep -rnE 'if \(.*(currentPlatform\(\)|isWindows).*\) *return;' minos-runtime-local/src/test` ne retourne aucune ligne

#### Scenario: Saut de plateforme distinct d'un saut de capacité
- **WHEN** `LinuxCgroupJobOwnershipIsolationTest` s'exécute sous Windows avec la propriété à `true`
- **THEN** la classe est sautée par `@EnabledOnOs(OS.LINUX)` et le job n'échoue pas

### Requirement: Les sauts d'hypothèse d'environnement des classes de confinement sont nommés
Les hypothèses d'environnement sans rapport avec la disponibilité du confinement (présence de `/dev/shm`, de `python3`, absence de `WRITE_DAC` du compte sur `%SystemRoot%`, verrou étranger non ouvrable, exemption volontaire des runners de CI élevés pour le test de non-élévation) SHALL rester des sauts par hypothèse, chacun avec un message qui énonce l'hypothèse. Aucune disponibilité de confinement SHALL être exprimée par un `assumeTrue` direct dans ces classes.

#### Scenario: Aucun saut de disponibilité direct
- **WHEN** on cherche `assumeTrue(discovered`, `assumeTrue(root` et `assumeTrue(executor` dans `minos-runtime-local/src/test`
- **THEN** la recherche ne retourne aucune ligne

#### Scenario: Exemption de non-élévation sur un runner élevé
- **WHEN** `WindowsNonElevatedIndexingTest` s'exécute sous un compte élevé avec la variable `CI` définie
- **THEN** le test est sauté avec un message qui explique l'exemption, et il échoue sous un compte élevé sans la variable `CI`
