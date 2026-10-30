# Design

## Context

Sprint 8 de l'audit du 10 octobre 2026 : **AUD-ARC-09**. HEAD analysé : `949e1569` (`develop`). Changement indépendant des autres du sprint. Abréviation : `RL` = `minos-runtime-local/src/main/java/com/minos/runtime/local`.

## État vérifié au HEAD

### AUD-ARC-09 — confirmé, action inapplicable telle quelle

- `minos-runtime-local/src/main/java/com/minos/runtime/local/StrongProcessOwnershipIndexerExecutor.java` : `:40` constructeur public `(ProcessIndexerExecutor delegate, Path minosHome)`, `:44` `this.localIsolation = null` ; `:51` constructeur public à trois arguments (politique réseau) ; `:64` et `:79` constructeurs package-private. `:102-108` : `execute` passe par `executeLocallyIsolated` si une isolation existe, sinon par `executeOwnershipOnly` (`:138-146`), qui **exige** une capacité de propriété forte et appelle `delegate.executeSandboxed` avec le transformateur de la frontière. Le constructeur à deux arguments n'est donc pas « sans rien » : il pose la frontière de propriété (cgroup, Job Object), sans bac à sable ni politique réseau.
- `minos-runtime-local/src/main/java/com/minos/runtime/local/process/ProcessIndexerExecutor.java` : `:43` `public final class … implements ProcessSandboxCapableIndexerExecutor` ; `:69` `execute` rend `executeSandboxed(request, (plan, runDirectory) -> plan)` : transformateur identité, aucune frontière.
- `minos-runtime-local/src/main/java/com/minos/runtime/local/sandbox/WorkerSandboxBackend.java` : `:79` `static WorkerSandboxBackend nativeEphemeralWorkspace()` ; classe imbriquée `NativeEphemeralWorkspaceBackend` dont `execute` (`:111-124`) refuse la politique `DENY` puis rend `delegate.execute(request)`.
- Seul site de construction en production : `minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/install/StrongOwnedProcessExecutors.java:28-32` (trois arguments).
- Refus en aval : `StrongProcessOwnershipIndexerExecutor.java:117` (backend non qualifié pour un provider local géré), `minos-runtime-local/src/main/java/com/minos/runtime/local/LocalIsolatedIndexWorker.java:158` (backend non qualifié pour du code non fiable).
- Usages de `nativeEphemeralWorkspace()` : trois en production, tous dans le package `sandbox` (`WorkerSandboxBackends.java:98,161`, `WorkerSandboxSelection.java:78`) ; en test hors de ce package, six sites dans quatre classes, dont trois sites dans deux autres modules (`minos-cli/src/test/java/com/minos/cli/operations/LocalRemoteIndexOperationsRefusalTest.java:62`, `minos-provider-scip/src/test/java/com/minos/adapter/scip/runtime/install/StrongOwnedProcessExecutorsQualificationTest.java:58,73`).
- Usages du constructeur à deux arguments : uniquement en test — `minos-runtime-local/src/test/java/com/minos/runtime/local/sandbox/LinuxStrongProcessOwnershipContainmentTest.java:78` (package `sandbox`) et `minos-runtime-local/src/test/java/com/minos/runtime/local/WindowsStrongProcessOwnershipContainmentTest.java:137,171` (package racine). Ces deux classes sont **nommées** par la capacité `confinement-code-non-fiable` (exigence « La CI exige la qualification du confinement sur chacun de ses deux systèmes »).
- `new ProcessIndexerExecutor(` : un site de production (ci-dessus) et 17 classes de test, dont 16 dans `minos-runtime-local` et `minos-cli/src/test/java/com/minos/cli/operations/LocalRemoteIndexOperationsIntegrationTest.java:216`, qui le remet à un « worker de confiance » de test (`:164`).
- Règle A10, `scripts/architecture/check-module-boundaries.py:396` : packages gardés `process`, `sandbox`, `workspace` ; `:415` membres réservés au module `ProcessPlanTransformer` et `executeSandboxed`, **y compris pour les sources de test** des autres modules (`:433-441`). `StrongProcessOwnershipIndexerExecutor` vit dans le package racine, hors garde.
- Gates littéraux : `scripts/remediation/check-p0-p2.py:264-266` exige `NetworkGuarantee.OS_ENFORCED` et `native worker cannot prove OS-level network denial` **dans `WorkerSandboxBackend.java`** ; `scripts/remediation/check-post-mne.py:132` et `scripts/remediation/check-minos-01.py:120` exigent d'autres chaînes du même fichier ; `check-post-mne.py:244` et `check-minos-01.py:159` lisent `ProcessIndexerExecutor.java` ; `scripts/remediation/check-post228-hardening.py:89` interdit `StrongProcessOwnershipIndexerExecutor.detectCapability(minosHome)` chez un appelant.
- Scopes JaCoCo (`scripts/quality/check-jacoco.py`) : `m25-remote-distributed-indexing` (`:80-85`) et `provider-execution-trust-boundary` (`:101-106`) suivent le **préfixe** `com/minos/runtime/local/sandbox/WorkerSandboxBackend` ; le second porte un seuil propre à `StrongProcessOwnershipIndexerExecutor` (`:113`).

**Non vérifié** : aucun build ni test exécuté. Le corps du worker de test de `minos-cli` au-delà de `:164-175` et la façon dont chacune des 16 classes de test appelle l'exécuteur n'ont pas été relus (tâche 1.1).

### Écarts avec l'action proposée par l'audit

1. **`ProcessIndexerExecutor.execute` ne peut pas devenir package-private** : c'est la méthode de l'interface `IndexerExecutor`. Elle peut en revanche refuser (D2).
2. **Une méthode statique d'interface est publique ou privée**, jamais package-private. Déplacer `nativeEphemeralWorkspace()` dans une classe non publique casse trois sites de test d'autres modules (qui construisent des fixtures de **refus**) et, si la classe imbriquée suit, le gate `check-p0-p2.py:264-266`.
3. **Le constructeur à deux arguments sert deux tests de confinement nommés par la spécification.** L'un est dans le package `sandbox` : un constructeur package-private du package racine lui devient inaccessible.
4. **Les tests des autres modules ne peuvent pas « passer par `executeSandboxed` »** : A10 réserve ce membre au module, tests compris. Le test d'intégration de `minos-cli` doit changer d'approche.
5. **A10 ne voit pas le package racine.** Le garde de ce changement est donc un test de structure, pas la règle A10.
6. **Décompte des sites de test hors package** : six sites dans quatre classes (et non trois), dont trois dans d'autres modules.

## Goals / Non-Goals

**Goals** : aucun appelant d'un autre module ne peut lancer un provider sans bac à sable avec les types de `minos-runtime-local` ; un exécuteur de processus sans frontière refuse au lieu d'exécuter ; la régression est détectée par un test.

**Non-Goals** : supprimer le backend natif (il sert de repli **refusé** et de fixture de refus) ; changer la sélection de backend ; toucher à l'API Java publiée.

## Decisions

### D1. Le constructeur à deux arguments devient package-private

`StrongProcessOwnershipIndexerExecutor(ProcessIndexerExecutor, Path)` perd `public`. `WindowsStrongProcessOwnershipContainmentTest` (package racine) n'est pas touché. `LinuxStrongProcessOwnershipContainmentTest` (package `sandbox`) l'obtient par une fabrique de **test** placée dans le package racine des sources de test du module ; la classe n'est ni déplacée ni renommée, pour que son rapport Surefire garde le nom que cite la capacité.

Écartée : supprimer le constructeur — la frontière de propriété seule est précisément ce que ces deux tests qualifient.

### D2. `ProcessIndexerExecutor.execute` refuse — **tranchée par le propriétaire le 2026-10-17 : option A**

Option A (recommandée) : `execute` lève une `IllegalStateException` au message fixe, sans chemin (« provider process requires an ownership or sandbox boundary »), avant toute création de répertoire de run. Toute exécution passe par `executeSandboxed` avec un transformateur fourni par une frontière. Conséquences : les classes de test de `minos-runtime-local` qui appelaient `execute` sur un exécuteur nu appellent `executeSandboxed` avec un transformateur identité **nommé** dans une aide de test (le contournement devient visible et unique) ; le test de `minos-cli` remplace l'exécuteur de processus par un `IndexerExecutor` de test, puisqu'il éprouve le transport et non le processus (à confirmer en 1.1).

Option B : laisser `execute` exécuter, et se contenter du test de structure qui fige la liste des entrées sans frontière. Aucun test à migrer, mais le défaut reste : un appelant d'un autre module peut toujours exécuter un provider nu, A10 ne réservant que `executeSandboxed`.

Avec l'option A, le backend natif ne peut plus exécuter un `ProcessIndexerExecutor` nu : `delegate.execute(request)` (`WorkerSandboxBackend.java:123`) refuse. Avec un `StrongProcessOwnershipIndexerExecutor`, il atteint le chemin isolé ou la frontière de propriété, qui refusent eux-mêmes quand ils ne sont pas qualifiés.

### D3. `nativeEphemeralWorkspace()` reste où il est

Ni déplacement ni renommage : le fichier `WorkerSandboxBackend.java` garde les chaînes exigées par trois gates, et le préfixe suivi par deux scopes JaCoCo ne change pas. La sûreté vient de D1 et D2 (plus aucun exécuteur du module ne s'exécute sans frontière derrière lui) et des refus existants (`supportsUntrustedCode`, `supportsManagedLocalProvider`, politique `DENY`). Sa Javadoc dit qu'il ne confine rien et n'est qualifié pour aucun code non fiable.

Écartée : ajouter `nativeEphemeralWorkspace` aux membres réservés de A10 — casse les trois fixtures de refus des autres modules, pour un gain nul une fois D2 faite.

### D4. Un test de structure garde l'invariant

Dans `minos-runtime-local`, par réflexion : (a) tout constructeur **public** de `StrongProcessOwnershipIndexerExecutor` prend un `WorkerNetworkPolicy` ; (b) `ProcessIndexerExecutor.execute` lève l'erreur de D2 sans créer de répertoire de run. Il est rouge au HEAD sur (a) (`:40`) et sur (b) (`:69`).

## Direction des dépendances

Changements internes à `minos-runtime-local` (couche runtime) et à des sources de test de `minos-cli` (surface). Aucun import nouveau ; `minos-provider-scip` (adaptateur) continue d'utiliser le constructeur à trois arguments. ADR 0022 respecté ; A10 inchangée et rejouée.

## Qualification des capacités

- Refus d'exécuter un provider sans frontière par l'exécuteur de processus : **non qualifiée** au HEAD ; qualifiée par D2-A (reste non qualifiée en option B).
- Exécution sous frontière de propriété seule : **partielle** par nature (ni système de fichiers confiné ni politique réseau) ; réservée au module après D1.
- Backend natif : **non supporté** pour du code non fiable, inchangé.

## Windows et Linux

D1, D2 et D4 valent sur les deux systèmes. Les tests de confinement nommés par la capacité doivent rester exécutés, non sautés, en mode requis (`-Dminos.sandbox.tests.required=true`) : `LinuxStrongProcessOwnershipContainmentTest` sous Ubuntu, `WindowsStrongProcessOwnershipContainmentTest` sous Windows Server. Sur un poste Windows, les classes Linux comptent comme sautées, et inversement.

## Risks / Trade-offs

- **Migration de tests large** (option A) : jusqu'à 17 classes. Le risque est de transformer un test de processus en test sans processus ; l'aide de test conserve l'exécution réelle du processus.
- **Chaînes de gates** : `check-post-mne.py:244` et `check-minos-01.py:159` exigent des expressions de `ProcessIndexerExecutor.java` ; D2 n'y touche pas, à prouver en rejouant les deux gates.
- **Seuil JaCoCo par classe** : `StrongProcessOwnershipIndexerExecutor` a un seuil propre (`check-jacoco.py:113`) ; retirer `public` ne change pas la couverture, mais le scope est rejoué.
- **Rupture pour un consommateur du reactor** : aucun module n'utilise les deux entrées retirées en production (relevé ci-dessus) ; un consommateur externe du JAR ombré n'est pas un contrat.
