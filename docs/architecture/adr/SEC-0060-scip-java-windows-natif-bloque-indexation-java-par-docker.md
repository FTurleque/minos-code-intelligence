---
id: SEC-0060
title: "scip-java sous Windows : non prêt en natif, indexation Java par le runtime Docker"
domain: security
rules: false
---

# scip-java sous Windows : non prêt en natif, indexation Java par le runtime Docker

Date : 2026-10-18. Statut : **Accepted**.

## Context

Sous Windows, un provider géré s'exécute dans un conteneur AppContainer (ADR 0036, fermeture des frontières de production). La chaîne scip-java Windows (runner PowerShell, shims compilés par `csc.exe`, patch compilé et empaqueté par la JVM du projet, scip-java lancé par `java`) n'a jamais été exécutée sous ce conteneur : le seul test de bout en bout du bac à sable (`WindowsNonElevatedIndexingTest`) écrit un fichier par `WriteAllText`, sans appel natif ni JVM, et la CI n'exécute aucun provider géré.

Le diagnostic du changement `rendre-l-echec-de-scip-java-sous-windows-actionnable` (voir son `design.md`) établit, sur un poste Windows 10 non élevé, que la chaîne échoue couche après couche pour une cause commune : le conteneur ne peut ni lister la racine du volume ni les ancêtres des racines qui lui sont accordées, et aucune ACL par défaut ne le lui accorde. Les appels natifs du runner échouent (`DriveNotFoundException`), `csc.exe` ne crée pas son fichier temporaire (CS1619), la JVM du projet ne s'initialise pas (`java.security` illisible par `toRealPath`, `File.createTempFile`).

Le principe *capability-honest* interdit de déclarer prêt un provider dont l'exécution n'est pas qualifiée.

## Decision

1. **Sous Windows natif, scip-java est `BLOCKED`.** Le diagnostic dit que l'exécution sous le bac à sable n'est pas qualifiée et nomme le runtime Docker ; l'installation et l'indexation Java sous Windows natif sont refusées avant tout lancement de provider. Linux est inchangé.
2. **L'indexation d'un projet Java depuis un poste Windows passe par le runtime Docker (Linux).** Le provider y tourne sous le confinement Linux déjà qualifié ; le confinement n'est pas modifié et aucun octroi n'est ajouté au conteneur AppContainer.
3. **Le blocage natif ne se lève que par un test de bout en bout** qui exécute, sous le conteneur AppContainer, un appel natif depuis Windows PowerShell 5.1 et une JVM (`File.createTempFile`, initialisation de `java.security`). Aucune voie d'exécution native n'est retenue.
4. **Critères de toute voie future** : le confinement n'est pas affaibli (aucun octroi de liste sur `C:\`, `C:\Users` ou le profil, qui exigerait une élévation) ; aucune élévation ; la garantie vaut sur Windows et Linux.

## Do's and Don'ts

### Do

- Déclarer scip-java `BLOCKED` sous Windows natif et refuser installation et indexation Java avant tout lancement de provider.
- Indexer un projet Java depuis un poste Windows par le runtime Docker (Linux).
- Ne lever le blocage natif que par un test de bout en bout sous AppContainer (appel natif depuis Windows PowerShell 5.1 et JVM).

### Don't

- N'ajouter aucun octroi au conteneur AppContainer.
- Aucun octroi de liste sur `C:\`, `C:\Users` ou le profil ; aucune élévation.
- Ne retenir aucune voie d'exécution native.

## Alternatives écartées

| Voie | Résout | Raison de l'écart |
|---|---|---|
| Compiler shims et patch à l'installation, hors conteneur, sous empreinte ; emplacement courant du runner posé par un `PSDrive` | Couches du runner et de `csc.exe` | Ne rend pas la JVM du projet initialisable : insuffisante seule |
| Capacité AppContainer dédiée au listage de la racine du volume | JVM du projet | Élargit la surface du conteneur, contraire aux ADR 0036 et 0038 |
| JDK géré par MINOS, installé hors profil, pour la JVM du provider | JVM du provider | Le JDK du projet reste nécessaire à la compilation analysée |

## Consequences

- Sous Windows, `minos doctor` et `minos tools verify` rapportent scip-java `BLOCKED` (il est requis par défaut) : leur verdict est `ACTION_REQUIRED`.
- `minos tools install scip-java` échoue sous Windows avec le diagnostic ; un projet TypeScript n'est pas affecté.
- L'indexation Java d'un poste Windows exige Docker.
- Limite : le chemin Docker pour un projet situé sur un hôte Windows (montage dans un conteneur Linux) n'est pas mesuré par cet ADR.

## Compliance and Enforcement

- Tests `ManagedScipProviderRuntimeManagerToolchainTest` (`minos-provider-scip`, build Maven des jobs `Verify`) — `onWindowsAnInstalledScipJavaIsBlockedUntilItsSandboxedExecutionIsQualified` (Windows seulement) et `onLinuxScipJavaIsNotBlockedByTheWindowsSandboxQualification`.
- Aucun gate `scripts/**/check-*.py` dédié.
- Revue : aucun octroi AppContainer ni élévation n'est ajouté ; la levée du blocage cite le test de bout en bout exigé au point 3.

## References

- Changement OpenSpec : `openspec/changes/rendre-l-echec-de-scip-java-sous-windows-actionnable/`.
- Frontières de production : ADR [0036](archive/0036-fail-closed-production-boundaries-and-measured-program-graph.md) ; confinement des ressources des workers : ADR [0038](archive/0038-aggregate-worker-resource-containment.md) ; outils livrés : ADR [0040](archive/0040-distribution-auto-portante-indexeurs-embarques.md).
- Constat antérieur : [`S15-REGRESSION-RUNTIME-GRANT.md`](../../project/audits/archive/2026-09/S15-REGRESSION-RUNTIME-GRANT.md) (points 1 et 2).
