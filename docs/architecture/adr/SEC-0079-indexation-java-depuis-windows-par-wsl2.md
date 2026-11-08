---
id: SEC-0079
title: "Indexation Java depuis Windows par WSL2 : indexation locale, exécuteur de l'orchestration unique, artefact v3 vérifié"
domain: security
rules: false
---

# Indexation Java depuis Windows par WSL2 : indexation locale, exécuteur de l'orchestration unique, artefact v3 vérifié

- **Statut** : Proposed
- **Date** : 2026-11-07
- **Origine** : changement OpenSpec `indexer-java-depuis-windows-par-wsl2` (tâche 1.1, design D2 à D6, écart E5) ; option CLI choisie par le propriétaire le 2026-11-07 (design D1)
- **Complète** : [SEC-0074](SEC-0074-java-non-indexable-depuis-windows-voie-wsl2.md) (décision 3), qui reste actif ; met en œuvre cette décision sans changer les décisions 1, 2 et 4

## Context

Sous Windows natif, scip-java est `BLOCKED` : la chaîne Java ne tourne pas sous le conteneur AppContainer, et aucune voie n'indexe un projet Java depuis un poste Windows ([SEC-0074](SEC-0074-java-non-indexable-depuis-windows-voie-wsl2.md), décision 1 ; `minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/install/ManagedScipProviderRuntimeManager.java:100-102`). SEC-0074, décision 3, retient WSL2 et le bac à sable Linux qualifié (bubblewrap et cgroup v2 délégué par systemd), et n'annonce la voie qu'après sa qualification. [SEC-0076](SEC-0076-depot-maven-designe-en-lecture-seule-et-maven-hors-ligne.md) fixe le dépôt Maven désigné, Maven hors ligne et le réseau `DENY` du build analysé.

La voie se présente comme une option du CLI Windows, `minos index <projet> --via-wsl` (décision du propriétaire du 2026-11-07). Elle exécute dans une distribution WSL2 le jar de l'installation Windows (`lib/minos.jar`, indépendant de la plateforme), puis importe le SCIP produit dans l'installation Windows, qui reste la seule source de vérité de l'index.

Faits relus au HEAD qui contraignent la décision :

- Le produit n'a qu'une orchestration d'indexation (`openspec/specs/decouverte-et-negociation/spec.md:101`), portée par `ProjectIndexingUseCase` (`minos-application/src/main/java/com/minos/application/indexing/ProjectIndexingUseCase.java:59`), qui délègue l'exécution à un `IndexerExecutor`.
- La sélection du backend d'un provider local géré (`WorkerSandboxBackends.strongestAvailableForManagedLocalProvider`, `minos-runtime-local/src/main/java/com/minos/runtime/local/sandbox/WorkerSandboxBackends.java:150`) est distincte de celle du code non fiable distant (`selectForUntrustedCode`, même fichier, `:41`), fermée par décision ([SEC-0070](SEC-0070-code-non-fiable-confinement-et-indexation-distante.md), décision 6), et que `LocalRemoteIndexingRuntime` (`minos-bootstrap`) câble avec `DistributedIndexerExecutor`.
- Le contrat de worker `DistributedIndexing.Worker` est indépendant du provider ; le magasin `DistributedArtifactBundleStore` vérifie format, chemins, bornes et SHA-256 d'un bundle avant import (SEC-0070, décision 2). `DistributedIndexerExecutor` est lié à une matérialisation distante (dépôt canonique et commit).
- Les formats `minos-distributed-artifact-v1` et `-v2` exigent un `sourceCommit` SHA-1 Git de 40 caractères (`minos-engine/src/main/java/com/minos/remote/DistributedArtifactManifest.java:50`, `DistributedIndexing.java:40`) ; l'empreinte d'entrée d'un projet local est un SHA-256 de 64 caractères.
- Le motif de secret unique `ProviderProcessEnvironment.isDeniedName` est privé au package ; la méthode publique qui l'applique est `ProviderProcessEnvironment.sanitize(Map, Map)` (`minos-runtime-local/src/main/java/com/minos/runtime/local/process/ProviderProcessEnvironment.java:133`, `:171`).

Options considérées pour l'exécution et l'import :

| Option | Raison de l'écart |
|---|---|
| Classer la voie comme indexation distante et passer par `remote index` / `LocalRemoteIndexingRuntime` | La source est un projet local enregistré, pas une révision distante ; l'indexation distante de code non fiable est fermée par décision (SEC-0070, décision 6) et la rouvrir exige un ADR qui remplace SEC-0070 |
| Un coordinateur propre à la voie, à côté de `ProjectIndexingUseCase` | Contraire à l'orchestration unique ; préparation, verrou, promotion et rétention seraient dupliqués |
| Import direct du SCIP par `import-scip` | Ne vérifie ni provenance ni politique réseau |
| Tronquer l'empreinte SHA-256 du projet pour la loger dans `sourceCommit` (manifeste v2) | Perd l'intégrité de la provenance et détourne la sémantique d'un champ Git |
| Script livré au lieu d'une commande | Écarté par le propriétaire (design D1) |

## Decision

1. **Indexation locale.** La voie indexe un projet local enregistré ; le provider tourne dans la distribution sous le backend du provider local géré (`strongestAvailableForManagedLocalProvider`), jamais par `selectForUntrustedCode`, `LocalRemoteIndexingRuntime` ni `remote index`. Elle ne rouvre pas l'indexation distante de code non fiable (SEC-0070, décision 6).
2. **Exécuteur de l'orchestration unique.** `ProjectIndexingUseCase` reste la seule orchestration ; la voie lui fournit un `IndexerExecutor` (`WslIndexerExecutor`) qui délègue à un `DistributedIndexing.Worker`. La distribution n'exécute que le provider : ni promotion ni rétention.
3. **Transport additif `minos-distributed-artifact-v3`.** Source locale identifiée par l'empreinte SHA-256 du projet ; v1 et v2 inchangés ; chaque format n'est accepté que par son exécuteur.
4. **Scope systemd délégué.** Le worker démarre par `systemd-run --user --scope -p Delegate=yes` ; sans délégation, refus sans repli.
5. **Environnement de `wsl.exe` en liste d'autorisation**, filtré par `ProviderProcessEnvironment.sanitize(Map, Map)`, `WSLENV` vide.
6. **Limite déclarée** : `networkDenyEnforced` est déclaré par la distribution, non prouvé par l'installation Windows.
7. **Lecture de SEC-0074, décision 3** : le jar de l'installation Windows, exécuté par une JVM de la distribution et piloté depuis Windows, est le « MINOS Linux dans WSL2 » ; la voie n'est annoncée qu'après la preuve des critères de qualification de SEC-0074.

Exclus : tout octroi AppContainer ou élévation (SEC-0074, décision 4), toute ouverture du réseau du build ou tout peuplement du dépôt Maven par MINOS (SEC-0076), tout autre provider que scip-java.

## Contrats de la voie

- **Orchestration (décision 2).** `ProjectIndexingUseCase` garde préparation, verrou de mutation, exécution, import par le port d'import, staging, promotion atomique ([DATA-0006](DATA-0006-promouvoir-les-index-de-maniere-atomique.md)) et rétention. `DistributedArtifactBundleStore` est réutilisé ; `DistributedIndexerExecutor`, lié à une matérialisation distante, ne l'est pas.
- **Manifeste v3 (décision 3).** Champs de source : `sourceKind=LOCAL_WSL`, `projectFingerprint` (empreinte d'entrée de 64 caractères hexadécimaux, calculée par l'installation Windows avant le lancement), `workerId=wsl:<distribution>` ; ni `sourceRepository` ni `sourceCommit`. Le bundle contient exactement `manifest.properties` et `index.scip` ; taille et SHA-256 du manifeste égalent ceux du fichier ; réseau `DENY` avec `networkDenyEnforced=true`. Le vérificateur de la voie compare projet, portée, empreinte, langage, provider et worker à la requête et refuse v1 et v2 ; `DistributedIndexerExecutor` refuse v3. Le format est éphémère : le bundle est supprimé après acceptation ou rejet. `WorkerRequest` a une variante locale sans `sourceCommit`.
- **Cgroup (décision 4).** La découverte existante (`LinuxCgroupJob`) place le provider dans le scope délégué et active `memory`, `pids` et `cpu`.
- **Environnement (décision 5).** `wsl.exe` est lancé depuis le répertoire système de Windows, jamais résolu par le `PATH`. Liste d'autorisation : `SystemRoot`, `WINDIR`, `TEMP`, `TMP`, `USERPROFILE`, `LOCALAPPDATA`, `PATH` réduit au répertoire système. Les réglages de la voie (dépôt Maven désigné dans la distribution, JDK du projet) passent dans la requête du worker, qui ne contient ni secret ni chemin Windows absolu.
- **Limite réseau (décision 6).** La confiance repose sur le même jar exécuté des deux côtés, sur la sonde du backend dans la distribution et sur la vérification du manifeste ; la documentation de la voie l'écrit.
- **Qualification (décision 7).** Critères de SEC-0074 : installation, scope délégué, JDK et dépôt Maven visibles sans réseau, propriétaire des fichiers sur `/mnt/<lecteur>`, indexation réelle. Aucune installation Linux persistante de MINOS n'est requise.

## Do's and Don'ts

### Do

- Fournir la voie à `ProjectIndexingUseCase` comme un `IndexerExecutor`, et importer l'artefact par le port d'import, le staging et la promotion atomique existants.
- Exécuter le provider dans la distribution sous le backend choisi par `strongestAvailableForManagedLocalProvider`, dans un scope `systemd-run --user --scope -p Delegate=yes`, réseau `DENY`.
- Vérifier tout bundle reçu par `DistributedArtifactBundleStore` puis par le vérificateur de provenance de la voie (format v3, projet, portée, empreinte, langage, provider, worker) avant tout import, et supprimer le bundle après acceptation ou rejet.
- Lancer `wsl.exe` depuis le répertoire système, avec un environnement en liste d'autorisation filtré par `ProviderProcessEnvironment.sanitize(Map, Map)` et `WSLENV` vide.
- Déclarer dans la documentation de la voie que `networkDenyEnforced` est déclaré par la distribution.

### Don't

- Ne pas faire passer la voie par `selectForUntrustedCode`, `LocalRemoteIndexingRuntime`, `DistributedIndexerExecutor` ni `remote index`.
- Ne pas créer de second coordinateur d'indexation, ni promouvoir ou retenir un index dans la distribution.
- Ne pas loger une empreinte SHA-256 dans `sourceCommit`, ni accepter un manifeste v1 ou v2 par la voie, ni un manifeste v3 par `DistributedIndexerExecutor`.
- Ne pas lancer le provider hors d'un cgroup délégué, ni retomber sur un backend plus faible.
- Ne pas transmettre à `wsl.exe` l'environnement hérité de MINOS, ni ouvrir `ProviderProcessEnvironment.isDeniedName` hors de son package, ni passer un réglage ou un secret par `WSLENV`.

## Consequences

### Positives

- Un projet Java s'indexe depuis Windows sous le même confinement que sous Linux natif, sans conteneur privilégié ni octroi AppContainer.
- La voie réutilise l'orchestration, le verrou, la promotion atomique, le contrat de worker et le magasin vérifié : aucun second transport ni coordinateur.
- Le même jar s'exécute côté Windows et côté distribution : aucun décalage de version entre coordinateur et worker.

### Négatives

- Un format de transport de plus (v3), avec ses validations et ses tests de refus croisés.
- La voie dépend de la configuration WSL de l'utilisateur (WSL2, systemd, délégation des contrôleurs, bubblewrap, JVM 21, Maven 3.9.x, JDK du projet, dépôt désigné) ; MINOS la vérifie, il ne l'installe pas.
- La qualification de bout en bout ne tourne pas en CI (pas de WSL2 avec systemd) : elle est manuelle, sur poste Windows.

### Risques

- `networkDenyEnforced` repose sur une déclaration de la distribution (décision 6).
- Les droits POSIX sur `/mnt/<lecteur>` (drvfs) n'ont pas de sens garanti ; le dossier d'échange est créé privé côté Windows et le propriétaire des fichiers est un critère de qualification de SEC-0074.
- `systemd-run --user` lancé par `wsl.exe` sans bus utilisateur ni `XDG_RUNTIME_DIR` échoue ; la sonde des prérequis le contrôle avant tout lancement.

## Compliance and Enforcement

- Tests prévus par le changement OpenSpec : `IndexCommandViaWslTest` (`minos-cli`) pour l'option et ses refus ; `WslCommandEnvironmentTest` (environnement de `wsl.exe`, `WSLENV` vide, jetons exclus) ; `WslArtifactProvenanceTest` (refus croisés v1/v2/v3, empreinte, projet, bundle supprimé) ; `WslIndexWorkerTest` (import par promotion, échec sans changement d'instantané, délai) ; test du worker Linux (refus sans cgroup délégué, réseau `DENY`).
- Gates existants : `scripts/architecture/check-module-boundaries.py` (aucun type `com.minos.remote` nommé par `minos-cli`, `minos-application` sans dépendance d'adaptateur), `scripts/architecture/check-private-io.py` (dossier d'échange et bundle par les primitives privées).
- Aucune règle Archgate compagnon : les contraintes portent sur des appels et des comportements, tenus par les tests et la revue.
- Revue : aucune référence à `selectForUntrustedCode`, `LocalRemoteIndexingRuntime`, `DistributedIndexerExecutor` ou `remote index` dans le code de la voie ; aucun second coordinateur ; aucune annonce de la voie (diagnostic de scip-java, documentation utilisateur) avant la preuve des critères de SEC-0074, décision 3.

## Mise en œuvre

Aucune au HEAD. Tâches 2 à 6 du changement OpenSpec `indexer-java-depuis-windows-par-wsl2` ; la qualification (tâche 6.2) consigne ici ses preuves.

## References

- Changement OpenSpec : `openspec/changes/indexer-java-depuis-windows-par-wsl2/` (proposal, design D1 à D8, E5).
- ADR [SEC-0074](SEC-0074-java-non-indexable-depuis-windows-voie-wsl2.md) (décision 3, complétée) ; ADR [SEC-0076](SEC-0076-depot-maven-designe-en-lecture-seule-et-maven-hors-ligne.md) (dépôt Maven désigné, réseau `DENY`) ; ADR [SEC-0070](SEC-0070-code-non-fiable-confinement-et-indexation-distante.md) (artefacts de worker vérifiés, indexation distante fermée) ; ADR [DATA-0006](DATA-0006-promouvoir-les-index-de-maniere-atomique.md) (promotion atomique) ; ADR [ARCH-0068](ARCH-0068-modules-dependances-et-propriete-des-packages.md) (direction des dépendances).
- Règle de sécurité : `.claude/rules/securite.md`, points 1, 3, 7 et 9.
- arc42 : [`09-decisions.md`](../arc42/09-decisions.md).
