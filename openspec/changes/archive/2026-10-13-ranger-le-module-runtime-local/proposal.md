# Proposal

## Why

`minos-runtime-local` (39 classes, 9 049 lignes) tient dans un seul package plat, `com.minos.runtime.local` : bacs à sable Windows et Linux, lancement et propriété des processus, espaces de travail des providers, quotas d'écriture, rétention et cache d'artefacts distribués y sont mêlés. Le propriétaire a demandé de ranger ce module, dernier de la série après `minos-provider-scip` (PR #411), `minos-storage-postgresql` (PR #413) et `minos-cli` (PR #415), en acceptant le coût mesuré : des classes aujourd'hui package-private deviennent publiques.

## What Changes

- Quatre sous-packages : `process` (localisation des commandes, environnement des processus, plans, sortie bornée, exécuteur de processus, arrêt d'arbre), `sandbox` (contrats du bac à sable, backend bubblewrap, cgroup, assemblage des scripts de confinement), `workspace` (espace de travail du provider, quota d'écriture, rétention, récupération des résidus, marqueurs de reprise), `distributed` (cache et exécuteur d'artefacts distribués).
- La racine garde l'orchestration (`LocalIsolatedIndexWorker`, `StrongProcessOwnershipIndexerExecutor`, `CompositeProviderRuntimeManager`, `WorkerSandboxBackends`) **et les quatre classes propres à Windows** (`WindowsAppContainerWorkerSandboxBackend`, `SandboxLauncherScript`, `WindowsJobObjectProcessOwnership`, `WindowsExecutionPathIdentityProvider`) : rangement partiel, décidé par le propriétaire le 11 octobre 2026 (voir design, « Rangement partiel »).
- Les dépendances vont de la racine vers tous, de `sandbox` vers `process` et `workspace`, de `process` vers `workspace` : aucun cycle, **sans modifier de code** (le découpage a été choisi pour cela).
- **13 types deviennent publics**, avec les 22 membres que d'autres sous-packages appellent.
- Les ressources (`windows-*.ps1*`, `windows-fragments/`) ne bougent pas : elles sont chargées par chemin absolu.
- Aucun comportement, aucune garantie de confinement, aucun script ne change.

## Capabilities

### Modified Capabilities
- `frontieres-de-modules` : une exigence ajoutée sur l'organisation interne de `minos-runtime-local`.

## Hors périmètre

- Ranger les quatre classes propres à Windows : SonarCloud ne reçoit que la couverture du job Linux, qui ne peut pas les exécuter ; les déplacer en ferait du code neuf non couvrable (voir design).
- Changer le confinement, l'environnement des processus, les scripts Windows ou les quotas.
- Déplacer les ressources empaquetées.
- Introduire JPMS (`module-info`) pour rétablir une encapsulation entre sous-packages.
- Réduire la taille des classes (`WindowsAppContainerWorkerSandboxBackend`, `LinuxCgroupJob`).

## Impact

- Modules : `minos-runtime-local` ; imports réécrits dans `minos-bootstrap`, `minos-cli`, `minos-provider-scip` et les tests de `minos-app`.
- Surfaces publiques (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune.
- Sécurité : aucune garantie ne change, mais des classes de confinement et d'environnement (`ProviderProcessEnvironment`, `ProviderWorkspaceFiles`, `LinuxCgroupJob`, `WindowsJobObjectProcessOwnership`…) deviennent publiques. Revue par l'agent `minos-security-reviewer` avant la PR.
- ADR : aucun créé ; ADR 0044 respecté. `META-INF/services/com.minos.orchestration.ExecutionPathIdentityProvider` ne change pas (`WindowsExecutionPathIdentityProvider` reste à la racine).
- Gates à rejouer : `check-module-boundaries.py` (A3, A8), `check-private-io.py` et sa liste de tolérance, `check-jacoco.py` (scopes `provider-sandbox-*`, `provider-write-quota-supervisor`, `provider-execution-trust-boundary`), gates `remediation`, gates de cohérence qui citent le module, baseline SpotBugs.
- Couverture : SonarCloud compte un fichier déplacé comme code neuf (80 %) ; une partie du code n'est exécutée que sur une plateforme (bubblewrap et cgroup sous Linux, AppContainer sous Windows), la mesure Linux fait foi.
- Plateformes : Ubuntu et Windows ; les tests de confinement doivent rester exigés sur les deux (`-Dminos.sandbox.tests.required=true`).
