# Proposal

## Why

**AUD-ARC-09** (faible, sprint 8 de l'audit du 10 octobre 2026 ; reprise de A11). `minos-runtime-local` offre trois entrées publiques qui permettent de lancer le processus d'un provider — donc du code issu d'un projet indexé — sans le bac à sable OS :

- le constructeur public à deux arguments de `StrongProcessOwnershipIndexerExecutor` (`:40`, `localIsolation = null` en `:44`) : propriété forte des descendants seulement, ni système de fichiers confiné ni politique réseau ;
- `ProcessIndexerExecutor.execute` (`process/ProcessIndexerExecutor.java:69`), qui délègue à `executeSandboxed` avec un transformateur identité : aucune frontière du tout ;
- `WorkerSandboxBackend.nativeEphemeralWorkspace()` (`sandbox/WorkerSandboxBackend.java:79`), dont `execute` (`:111-124`) appelle directement `delegate.execute(request)` hors politique `DENY`.

Au HEAD `949e1569`, le seul site de construction en production utilise la variante sûre à trois arguments (`StrongOwnedProcessExecutors.java:28-32`), et des refus en aval tiennent (`StrongProcessOwnershipIndexerExecutor.java:117`, `LocalIsolatedIndexWorker.java:158`). Le défaut est donc **latent** : un futur appelant compilerait sans avertissement. La règle A10 de `check-module-boundaries.py` ne garde que les packages `process`, `sandbox` et `workspace`, et réserve seulement `executeSandboxed` et `ProcessPlanTransformer`.

## What Changes

- **`ProcessIndexerExecutor.execute` refuse** : lancer un provider sans transformateur de frontière lève une erreur au lieu de s'exécuter (la méthode appartient à l'interface `IndexerExecutor` et ne peut pas être masquée).
- **Le constructeur à deux arguments** de `StrongProcessOwnershipIndexerExecutor` cesse d'être public ; les tests de confinement qui l'utilisent y accèdent par le code de test du module.
- **`nativeEphemeralWorkspace()` reste en place** (une méthode statique d'interface ne peut pas être package-private, et un gate littéral lit son fichier), mais ne mène plus à une exécution sans frontière avec un exécuteur fourni par MINOS.
- **Un test de structure** échoue si une entrée publique sans frontière réapparaît.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `confinement-code-non-fiable` : deux exigences ajoutées (l'exécuteur de processus refuse de lancer un provider sans frontière ; aucune entrée publique ne construit un exécuteur sans politique réseau).

## Hors périmètre

- Étendre la règle A10 au package racine de `minos-runtime-local` ou à de nouveaux membres réservés : les règles de liste fermée sont l'objet du changement actif `figer-la-dette-de-frontieres-par-des-cliquets`.
- Déplacer `NativeEphemeralWorkspaceBackend` hors de `WorkerSandboxBackend.java` (casserait `check-p0-p2.py:264-266` et trois sites de test d'autres modules).
- Le comportement des backends qualifiés (bubblewrap, AppContainer) et la sélection du backend.
- Le suivi de propriété par sondage (AUD-PERF-08, changement `espacer-le-sondage-de-propriete-des-processus`).

## Impact

- **Modules du reactor** : `minos-runtime-local` (deux classes de production, classes de test du module) ; `minos-cli` (un test d'intégration qui exécute un `ProcessIndexerExecutor` nu). `minos-provider-scip` : inchangé en production.
- **Surfaces publiques** : CLI, MCP, IntelliJ, NEXUS inchangés. `ProcessIndexerExecutor` et `StrongProcessOwnershipIndexerExecutor` ne font pas partie de l'API Java publiée, mais sont nommables par les autres modules du reactor : le retrait d'un constructeur public et le refus de `execute` sont des ruptures internes, tranchées en D1 et D2.
- **ADR** : aucun créé. Une note de mise en œuvre est à ajouter à l'ADR qui porte la frontière de confiance de l'exécution des providers (à identifier dans `docs/adr/README.md` en tâche de clôture).
- **Gates à rejouer** : `python scripts/remediation/check-p0-p2.py` (`:264-266`), `python scripts/remediation/check-post-mne.py` (`:132`, `:244`), `python scripts/remediation/check-minos-01.py` (`:120`, `:159`), `python scripts/remediation/check-post228-hardening.py` (`:89`), `python scripts/architecture/check-module-boundaries.py` (A10), scopes JaCoCo `provider-execution-trust-boundary` et `m25-remote-distributed-indexing` (`scripts/quality/check-jacoco.py`).
- **Plateformes** : Windows et Linux ; les tests de confinement nommés par la capacité en mode requis s'exécutent sur les deux.
