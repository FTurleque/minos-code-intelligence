---
id: SEC-0074
title: "scip-java sous Windows : bloqué en natif, aucune voie d'indexation Java, WSL2 retenu pour la suite"
domain: security
rules: false
---

# scip-java sous Windows : bloqué en natif, aucune voie d'indexation Java, WSL2 retenu pour la suite

- **Statut** : Accepted
- **Date** : 2026-10-31
- **Origine** : changement OpenSpec `rendre-l-echec-de-scip-java-sous-windows-actionnable` (tâche 5.2) ; décision du propriétaire du 2026-10-31
- **Remplace** : ADR-0060

## Context

Sous Windows, un provider géré s'exécute dans un conteneur AppContainer (ADR 0070). La chaîne scip-java Windows (runner PowerShell, shims compilés par `csc.exe`, patch compilé et empaqueté par la JVM du projet, scip-java lancé par `java`) échoue couche après couche sous ce conteneur, pour une cause commune : le conteneur ne peut ni lister la racine du volume ni les ancêtres des racines qui lui sont accordées. Les appels natifs du runner échouent (`DriveNotFoundException`), `csc.exe` ne crée pas son fichier temporaire (CS1619), la JVM du projet ne s'initialise pas (`java.security` illisible par `toRealPath`, `File.createTempFile`). Le diagnostic complet est dans le `design.md` du changement d'origine.

Le runtime Docker ne prend pas le relais. Son plan d'administration (`minos-admin`) ne lance aucun provider : faute de bac à sable OS dans un conteneur durci (pas de `bwrap`, espaces de noms utilisateur refusés par le profil seccomp par défaut, cgroup en lecture seule), `index` y est refusé avant tout processus fils (`docs/developer/remote-worker-sandbox-disposition.md`). Mesure du 2026-10-31 sur une installation 1.3.0 avec Docker Desktop : `minos-admin index` d'un projet Maven sort en code 1 sur « provider runtime is not ready: scip-java — managed local provider sandbox tier is not provided by the Docker MCP backend ». Aucune voie n'indexe donc aujourd'hui un projet Java depuis un poste Windows.

Un index réel a été produit le 2026-10-15 dans un conteneur Linux lancé avec `--privileged` et un cgroup délégué : le bac à sable Linux qualifié (bubblewrap + cgroup v2) y tourne, au prix d'un conteneur qui n'isole plus la machine virtuelle de Docker. Mesure du 2026-10-31 dans WSL2 (Ubuntu 24.04, noyau 6.18) : systemd actif, cgroup v2, délégation `cpu memory pids` au service utilisateur, espaces de noms utilisateur autorisés, `systemd-run --user --scope` place un processus dans un cgroup délégué ; `bubblewrap` n'y est pas installé par défaut.

Le principe *capability-honest* interdit de déclarer prête une capacité absente, et d'en annoncer une qui n'existe pas.

## Decision

1. **Sous Windows natif, scip-java est `BLOCKED`.** Installation et indexation Java sont refusées avant tout lancement de provider. Le diagnostic dit que l'exécution sous le bac à sable n'est pas qualifiée et qu'**aucune voie** ne permet d'indexer un projet Java depuis un poste Windows ; il ne renvoie vers aucun runtime qui ne le fait pas. Linux est inchangé.
2. **Le blocage natif ne se lève que par un test de bout en bout** qui exécute, sous le conteneur AppContainer, un appel natif depuis Windows PowerShell 5.1 et une JVM (`File.createTempFile`, initialisation de `java.security`).
3. **La voie retenue pour indexer du Java depuis Windows est un MINOS Linux dans WSL2**, sous le bac à sable Linux qualifié (bubblewrap + cgroup v2 délégué par systemd). Elle n'est pas livrée : elle sera annoncée seulement après sa qualification (installation, propriétaire des fichiers sur `/mnt/<lecteur>`, confinement prouvé), par un ADR ou une mise en œuvre de celui-ci.
4. **Critères de toute voie** : le confinement n'est pas affaibli (aucun octroi de liste sur `C:\`, `C:\Users` ou le profil, aucune élévation, aucun conteneur privilégié) ; la garantie vaut sur Windows et Linux.

## Do's and Don'ts

### Do

- Déclarer scip-java `BLOCKED` sous Windows natif et refuser installation et indexation Java avant tout lancement de provider.
- Dire dans le diagnostic et la documentation qu'aucune voie n'indexe un projet Java depuis Windows, tant que la voie WSL2 n'est pas qualifiée.
- Ne lever le blocage natif que par un test de bout en bout sous AppContainer (appel natif depuis Windows PowerShell 5.1 et JVM).
- Construire la voie WSL2 sur le bac à sable Linux qualifié, cgroup délégué par systemd.

### Don't

- Ne pas renvoyer l'utilisateur vers le runtime Docker pour indexer un projet Java : son plan d'administration ne lance aucun provider.
- N'ajouter aucun octroi au conteneur AppContainer ; aucun octroi de liste sur `C:\`, `C:\Users` ou le profil ; aucune élévation.
- Ne pas lancer un provider dans un conteneur `--privileged`, ni assouplir le profil seccomp ou le cgroup du conteneur d'administration pour y faire tourner bubblewrap.

## Alternatives écartées

| Voie | Résout | Raison de l'écart |
|---|---|---|
| Compiler shims et patch à l'installation, hors conteneur, sous empreinte ; emplacement courant du runner posé par un `PSDrive` | Couches du runner et de `csc.exe` | Ne rend pas la JVM du projet initialisable : insuffisante seule |
| Capacité AppContainer dédiée au listage de la racine du volume | JVM du projet | Élargit la surface du conteneur, contraire à l'ADR 0070 |
| JDK géré par MINOS, installé hors profil, pour la JVM du provider | JVM du provider | Le JDK du projet reste nécessaire à la compilation analysée |
| Conteneur d'indexation `--privileged` avec cgroup délégué | Tout, mesuré le 2026-10-15 | Le build analysé (plugins Maven du projet) est du code non fiable ; un conteneur privilégié n'isole plus la machine virtuelle de Docker ni ses données |
| Conteneur d'indexation avec profil seccomp assoupli | Peut-être bubblewrap | Non mesuré ; affaiblit le durcissement du conteneur pour du code non fiable |

## Consequences

### Positives

- Le produit ne promet plus une voie absente : `doctor`, `tools verify`, la page de dépannage et l'état produit disent la même chose.
- La voie future réutilise le confinement Linux déjà qualifié, sans conteneur privilégié.

### Négatives

- Aucun projet Java ne s'indexe depuis un poste Windows tant que la voie WSL2 n'est pas livrée ; `minos doctor` et `minos tools verify` rapportent `ACTION_REQUIRED` (scip-java est requis par défaut).
- Seul recours : indexer sur un hôte Linux, ou y produire un artefact SCIP importé sous Windows par `minos import-scip` (sans compilation par MINOS).

### Risques

- La voie WSL2 dépend de la configuration de l'utilisateur (systemd, bubblewrap) et lit les projets Windows par `/mnt/<lecteur>`, dont la sémantique de propriétaire et les performances restent à qualifier.

## Compliance and Enforcement

- Tests `ManagedScipProviderRuntimeManagerToolchainTest` (`minos-provider-scip`, build Maven des jobs `Verify`) : `onWindowsAnInstalledScipJavaIsBlockedUntilItsSandboxedExecutionIsQualified` (Windows seulement : `BLOCKED`, diagnostic « no supported way to index Java projects from a Windows host », aucune mention du runtime Docker, indexation refusée avant lancement) et `onLinuxScipJavaIsNotBlockedByTheWindowsSandboxQualification`.
- `StrongProcessOwnershipIndexerExecutorTest` verrouille le refus de lancer un provider dans le plan d'administration Docker.
- Aucun gate `scripts/**/check-*.py` dédié.
- Revue : aucun octroi AppContainer, aucune élévation, aucun conteneur privilégié ; la levée du blocage natif cite le test de bout en bout du point 2 ; l'annonce de la voie WSL2 cite sa qualification.

## References

- Changement OpenSpec : `openspec/changes/rendre-l-echec-de-scip-java-sous-windows-actionnable/`.
- Confinement du code non fiable : ADR [SEC-0070](SEC-0070-code-non-fiable-confinement-et-indexation-distante.md) ; backends MCP : ADR [ARCH-0069](ARCH-0069-backends-mcp-natif-et-docker.md) ; providers livrés : ADR [BE-0065](BE-0065-providers-scip-polyglottes-livres-dans-le-paquet.md).
- Disposition des bacs à sable : [`docs/developer/remote-worker-sandbox-disposition.md`](../../developer/remote-worker-sandbox-disposition.md).
- ADR remplacé : [0060](archive/0060-scip-java-windows-natif-bloque-indexation-java-par-docker.md).
