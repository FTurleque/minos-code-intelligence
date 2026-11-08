---
id: SEC-0079
title: "Indexation Java depuis Windows par WSL2 : indexation locale, providers routés de l'orchestration unique, artefact v3 vérifié"
domain: security
rules: false
---

# Indexation Java depuis Windows par WSL2 : indexation locale, providers routés de l'orchestration unique, artefact v3 vérifié

- **Statut** : Proposed
- **Date** : 2026-11-08
- **Origine** : changement OpenSpec `indexer-java-depuis-windows-par-wsl2` (tâche 1.1, design D2 à D6, écart E5) ; option CLI choisie par le propriétaire le 2026-11-07 (design D1) ; revue d'architecture de D2 et D3 du 2026-11-08 (tâche 1.2)
- **Complète** : [SEC-0074](SEC-0074-java-non-indexable-depuis-windows-voie-wsl2.md) (décision 3), qui reste actif ; met en œuvre cette décision sans changer les décisions 1, 2 et 4

## Context

Sous Windows natif, scip-java est `BLOCKED` : la chaîne Java ne tourne pas sous le conteneur AppContainer, et aucune voie n'indexe un projet Java depuis un poste Windows ([SEC-0074](SEC-0074-java-non-indexable-depuis-windows-voie-wsl2.md), décision 1 ; `minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/install/ManagedScipProviderRuntimeManager.java:286`). SEC-0074, décision 3, retient WSL2 et le bac à sable Linux qualifié (bubblewrap et cgroup v2 délégué par systemd), et n'annonce la voie qu'après sa qualification. [SEC-0076](SEC-0076-depot-maven-designe-en-lecture-seule-et-maven-hors-ligne.md) fixe le dépôt Maven désigné, Maven hors ligne et le réseau `DENY` du build analysé.

La voie se présente comme une option du CLI Windows, `minos index <projet> --via-wsl` (décision du propriétaire du 2026-11-07). Elle exécute dans une distribution WSL2 le jar de l'installation Windows (`lib/minos.jar`, indépendant de la plateforme), puis importe le SCIP produit dans l'installation Windows, qui reste la seule source de vérité de l'index.

Faits relus au HEAD qui contraignent la décision :

- Le produit n'a qu'une orchestration d'indexation (`openspec/specs/decouverte-et-negociation/spec.md:101`), portée par `ProjectIndexingUseCase` (`minos-application/src/main/java/com/minos/application/indexing/ProjectIndexingUseCase.java:59`), qui délègue l'exécution à un `IndexerExecutor` obtenu d'un `ProviderRuntimeManager`.
- `ProjectIndexingUseCase` lit la disponibilité des providers par `ProviderRuntimeManager.inspect` (`ProjectIndexingUseCase.java:519`) et refuse le run si l'un n'est pas prêt (`requireReadyRuntimes`, `:306`) ; il obtient ensuite chaque exécuteur par `ProviderRuntimeManager.executor` (`:350`), que `ManagedScipProviderRuntimeManager` refuse pour un provider non prêt (`ManagedScipProviderRuntimeManager.java:167-172`). Un provider `BLOCKED` en natif n'atteint donc jamais un exécuteur, quel que soit le traitement appliqué après sa résolution.
- `IndexerExecutor` n'est pas fermable (`minos-engine/src/main/java/com/minos/orchestration/IndexingRuntimePorts.java:23`). Un artefact retenu par `DistributedArtifactBundleStore` hors du répertoire du run reste en bail jusqu'à la fermeture de son exécuteur (`DistributedIndexerExecutor.java:67`, `:129`), que seul `LocalRemoteIndexingRuntime` assure.
- La sélection du backend d'un provider local géré (`WorkerSandboxBackends.strongestAvailableForManagedLocalProvider`, `minos-runtime-local/src/main/java/com/minos/runtime/local/sandbox/WorkerSandboxBackends.java:150`) est distincte de celle du code non fiable distant (`selectForUntrustedCode`, même fichier, `:41`), fermée par décision ([SEC-0070](SEC-0070-code-non-fiable-confinement-et-indexation-distante.md), décision 6), et que `LocalRemoteIndexingRuntime` (`minos-bootstrap`) câble avec `DistributedIndexerExecutor`.
- Le contrat de worker `DistributedIndexing.Worker` est indépendant du provider ; le magasin `DistributedArtifactBundleStore` vérifie format, chemins, bornes et SHA-256 d'un bundle avant import (SEC-0070, décision 2). `DistributedIndexerExecutor` est lié à une matérialisation distante (dépôt canonique et commit) et n'accepte que `minos-distributed-artifact-v2`.
- `DistributedArtifactManifest` porte `sourceRepository` et `sourceCommit` comme composants obligatoires, et les formats `minos-distributed-artifact-v1` et `-v2` exigent un `sourceCommit` SHA-1 Git de 40 caractères (`minos-engine/src/main/java/com/minos/remote/DistributedArtifactManifest.java:50`, `DistributedIndexing.java:40`) ; l'empreinte d'entrée d'un projet local est un SHA-256 de 64 caractères.
- Une surface ne nomme aucun type `com.minos.remote` ni aucune classe d'adaptateur (`scripts/architecture/check-module-boundaries.py`, règle A11 ; [ARCH-0042](ARCH-0042-racine-de-composition.md)).
- Le motif de secret unique `ProviderProcessEnvironment.isDeniedName` est privé au package ; la méthode publique qui l'applique est `ProviderProcessEnvironment.sanitize(Map, Map)` (`minos-runtime-local/src/main/java/com/minos/runtime/local/process/ProviderProcessEnvironment.java:133`, `:171`).

Options considérées pour l'exécution et l'import :

| Option | Raison de l'écart |
|---|---|
| Classer la voie comme indexation distante et passer par `remote index` / `LocalRemoteIndexingRuntime` | La source est un projet local enregistré, pas une révision distante ; l'indexation distante de code non fiable est fermée par décision (SEC-0070, décision 6) et la rouvrir exige un ADR qui remplace SEC-0070 |
| Un coordinateur propre à la voie, à côté de `ProjectIndexingUseCase` | Contraire à l'orchestration unique ; préparation, verrou, promotion et rétention seraient dupliqués |
| Décorer l'exécuteur natif résolu par `ProviderRuntimeManager.executor` | L'exécuteur natif de scip-java n'existe pas sous Windows : la disponibilité et la résolution refusent avant toute décoration |
| Lever le `BLOCKED` natif de scip-java quand la voie est demandée | Contraire à SEC-0074, décision 1 : l'exécution native resterait annoncée sans qualification |
| Import direct du SCIP par `import-scip` | Ne vérifie ni provenance ni politique réseau |
| Tronquer l'empreinte SHA-256 du projet pour la loger dans `sourceCommit` (manifeste v2) | Perd l'intégrité de la provenance et détourne la sémantique d'un champ Git |
| Script livré au lieu d'une commande | Écarté par le propriétaire (design D1) |

## Decision

1. **Indexation locale.** Le provider tourne dans la distribution sous `strongestAvailableForManagedLocalProvider`, jamais par `selectForUntrustedCode`, `LocalRemoteIndexingRuntime` ni `remote index` (SEC-0070, décision 6, reste fermée).
2. **Providers routés de l'orchestration unique.** `ProjectIndexingUseCase` reste la seule orchestration. Le port `WslIndexingRoute`, câblé dans `minos-bootstrap` sous Windows, lui fournit un `ProviderRuntimeManager` qui rend disponibilité et exécuteur (`WslIndexerExecutor`) de scip-java seul, tirés de la sonde WSL, et délègue les autres providers au natif. Le `BLOCKED` natif de scip-java reste intact (SEC-0074, décision 1).
3. **Transport `minos-distributed-artifact-v3`.** Source locale identifiée par l'empreinte SHA-256 du projet ; v1 et v2 inchangés sur le fil ; source scellée dans les types du moteur ; chaque format n'est accepté que par son exécuteur.
4. **Scope systemd délégué** (`systemd-run --user --scope -p Delegate=yes`), refus sans repli.
5. **Environnement de `wsl.exe` en liste d'autorisation** (`ProviderProcessEnvironment.sanitize(Map, Map)`), `WSLENV` vide.
6. **Limite déclarée** : `networkDenyEnforced` est déclaré par la distribution.
7. **Lecture de SEC-0074, décision 3** : le jar de l'installation Windows exécuté dans la distribution est le « MINOS Linux dans WSL2 » ; annonce seulement après qualification.
8. **Artefact recopié sous le run**, bail du magasin libéré avant le retour de `execute`, bundle supprimé.
9. **Worker Linux composé hors de la surface** : port de `minos-application` implémenté dans `minos-bootstrap` ; réglages lus dans la configuration MINOS de la distribution, refus nominatif si absents.

Exclus : octroi AppContainer ou élévation, levée du `BLOCKED` natif, réseau du build ouvert ou dépôt Maven peuplé par MINOS (SEC-0076), tout autre provider que scip-java dans la distribution.

## Contrats de la voie

- **Routage (décision 2).** La disponibilité routée de scip-java vient de la sonde des prérequis WSL ; les autres providers d'un projet polyglotte restent natifs. `WslIndexerExecutor` délègue à un `DistributedIndexing.Worker`. La distribution n'exécute que le provider : ni promotion ni rétention.
- **Orchestration (décision 2).** `ProjectIndexingUseCase` garde préparation, verrou de mutation, exécution, import par le port d'import, staging, promotion atomique ([DATA-0006](DATA-0006-promouvoir-les-index-de-maniere-atomique.md)) et rétention ; la voie ne remplace que le `ProviderRuntimeManager` qu'il reçoit. `DistributedArtifactBundleStore` est réutilisé ; `DistributedIndexerExecutor`, lié à une matérialisation distante, ne l'est pas.
- **Manifeste v3 (décision 3).** Champs de source : `sourceKind=LOCAL_WSL`, `projectFingerprint` (empreinte d'entrée de 64 caractères hexadécimaux, calculée par l'installation Windows avant le lancement), `workerId=wsl:<distribution>` ; ni `sourceRepository` ni `sourceCommit`. Le bundle contient exactement `manifest.properties` et `index.scip` ; taille et SHA-256 du manifeste égalent ceux du fichier ; réseau `DENY` avec `networkDenyEnforced=true`. Le décodeur du magasin a une branche v3 au jeu de champs exact. Le vérificateur de la voie compare projet, portée, empreinte, langage, provider et worker à la requête et refuse v1 et v2 ; `DistributedIndexerExecutor` refuse v3. `WorkerRequest` reçoit la même source scellée que le manifeste.
- **Cycle de vie de l'artefact (décision 8).** Le format est éphémère : le bundle n'existe que le temps de la vérification. L'artefact accepté vit sous `runs/<runId>/` et relève du confinement du répertoire du run ; aucun bail du magasin ne survit au retour de `execute`.
- **Cgroup (décision 4).** La découverte existante (`LinuxCgroupJob`) place le provider dans le scope délégué et active `memory`, `pids` et `cpu`.
- **Environnement (décision 5).** `wsl.exe` est lancé depuis le répertoire système de Windows, jamais résolu par le `PATH`. Liste d'autorisation : `SystemRoot`, `WINDIR`, `TEMP`, `TMP`, `USERPROFILE`, `LOCALAPPDATA`, `PATH` réduit au répertoire système. La requête du worker ne contient ni secret ni chemin Windows absolu.
- **Réglages du worker (décision 9).** Dépôt Maven désigné, JDK du projet et installation de scip-java sont lus dans le fichier de configuration MINOS de la distribution, avec les clés de l'installation Linux native : `minos.scip.java.maven-repository` (`ScipJavaMavenOffline.REPOSITORY_SETTING`) et `minos.scip.java.project-jdk`, résolue comme elle (propriété JVM, fichier de configuration, puis `JAVA_HOME`) ; `wsl.exe --exec` ne transmet pas les variables d'environnement de la session de l'utilisateur, et le worker ne les cherche pas ; aucun shell de connexion ni profil utilisateur n'est exécuté pour les découvrir. Un réglage absent produit un refus qui nomme la clé et l'action.
- **Limite réseau (décision 6).** La confiance repose sur le même jar exécuté des deux côtés, sur la sonde du backend dans la distribution et sur la vérification du manifeste ; la documentation de la voie l'écrit.
- **Qualification (décision 7).** Critères de SEC-0074 : installation, scope délégué, JDK et dépôt Maven visibles sans réseau, propriétaire des fichiers sur `/mnt/<lecteur>`, indexation réelle.

## Do's and Don'ts

### Do

- Fournir la voie comme un `ProviderRuntimeManager` qui route scip-java seul et délègue les autres providers au natif ; importer par le port d'import, le staging et la promotion atomique existants.
- Exécuter le provider sous `strongestAvailableForManagedLocalProvider`, dans un scope systemd délégué, réseau `DENY`.
- Vérifier tout bundle par `DistributedArtifactBundleStore` puis par le vérificateur de la voie avant import ; recopier l'artefact sous le run, libérer le bail, supprimer le bundle.
- Composer le worker Linux par un port de `minos-application` implémenté dans `minos-bootstrap`.
- Lancer `wsl.exe` depuis le répertoire système, environnement filtré par `ProviderProcessEnvironment.sanitize(Map, Map)`, `WSLENV` vide.

### Don't

- Ne pas résoudre l'exécuteur natif d'un provider routé, ni lever le `BLOCKED` natif de scip-java.
- Ne pas passer par `selectForUntrustedCode`, `LocalRemoteIndexingRuntime`, `DistributedIndexerExecutor` ni `remote index`, ni créer de second coordinateur.
- Ne pas loger une empreinte SHA-256 dans `sourceCommit`, ni accepter v1/v2 par la voie ou v3 par `DistributedIndexerExecutor`.
- Ne pas laisser un bail du magasin ouvert après `execute`.
- Ne pas nommer depuis `minos-cli` un type `com.minos.remote` ni un adaptateur.
- Ne pas lancer le provider hors d'un cgroup délégué ni retomber sur un backend plus faible.
- Ne pas transmettre l'environnement hérité, ouvrir `isDeniedName`, ni passer un réglage par `WSLENV`.
- Ne pas découvrir un réglage par un shell de connexion, ni installer scip-java à la volée.

## Consequences

### Positives

- Un projet Java s'indexe depuis Windows sous le même confinement que sous Linux natif, sans conteneur privilégié ni octroi AppContainer.
- La voie réutilise l'orchestration, le verrou, la promotion atomique, le contrat de worker et le magasin vérifié : aucun second transport ni coordinateur.
- Le refus natif de scip-java reste intact : seule la voie demandée explicitement fournit une disponibilité, et seulement pour scip-java.
- Le même jar s'exécute côté Windows et côté distribution : aucun décalage de version entre coordinateur et worker.

### Négatives

- Un format de transport de plus (v3), avec ses validations et ses tests de refus croisés ; la source scellée restructure des types internes du moteur (`DistributedArtifactManifest`, `WorkerRequest`) et leurs tests.
- Une copie de l'artefact par run sous le répertoire du run.
- La voie dépend de la configuration WSL de l'utilisateur (WSL2, systemd, délégation des contrôleurs, bubblewrap, JVM 21, Maven 3.9.x, JDK du projet, dépôt désigné, scip-java installé dans la distribution) ; MINOS la vérifie, il ne l'installe pas.
- La qualification de bout en bout ne tourne pas en CI (pas de WSL2 avec systemd) : elle est manuelle, sur poste Windows.

### Risques

- `networkDenyEnforced` repose sur une déclaration de la distribution (décision 6).
- Les droits POSIX sur `/mnt/<lecteur>` (drvfs) n'ont pas de sens garanti ; le dossier d'échange est créé privé côté Windows et le propriétaire des fichiers est un critère de qualification de SEC-0074.
- `systemd-run --user` lancé par `wsl.exe` sans bus utilisateur ni `XDG_RUNTIME_DIR` échoue ; la sonde des prérequis le contrôle avant tout lancement.

## Compliance and Enforcement

- Tests de la voie : `IndexCommandViaWslTest` et `IndexViaWslSideEffectsTest` (`minos-cli`) pour l'option et ses refus ; `WslIndexingRouteWiringTest` (port et câblage par le composeur) ; `WslPrerequisiteProbeTest` et `WslCommandEnvironmentTest` (`minos-runtime-local`) pour la sonde et l'environnement de `wsl.exe`. Tests prévus par le changement OpenSpec : `WslArtifactProvenanceTest` (refus croisés v1/v2/v3, empreinte, projet, bundle supprimé, bail libéré) ; cas v3 de `DistributedIndexerExecutorProvenanceTest` ; `WslIndexWorkerTest` (import par promotion, échec sans changement d'instantané, délai, scip-java seul routé) ; test du worker Linux (refus sans cgroup délégué, réglage absent nommé, réseau `DENY`).
- Gates existants : `scripts/architecture/check-module-boundaries.py` (aucun type `com.minos.remote` nommé par `minos-cli`, `minos-application` sans dépendance d'adaptateur), `scripts/architecture/check-private-io.py` (dossier d'échange, bundle et copie sous le run par les primitives privées).
- Aucune règle Archgate compagnon : les contraintes portent sur des appels et des comportements, tenus par les tests et la revue.
- Revue : aucune référence à `selectForUntrustedCode`, `LocalRemoteIndexingRuntime`, `DistributedIndexerExecutor` ou `remote index` dans le code de la voie ; aucun second coordinateur ; aucun appel à l'exécuteur natif d'un provider routé ; aucune annonce de la voie (diagnostic de scip-java, documentation utilisateur) avant la preuve des critères de SEC-0074, décision 3.

## Mise en œuvre

- Option `--via-wsl` et `--wsl-distro` de `IndexCommand`, refusées hors Windows et sur une installation qui n'offre pas la voie (`AutonomousIndexOperations.VIA_WSL_UNAVAILABLE`).
- Port `WslIndexingRoute` (`minos-application`, package `com.minos.application`) et `MinosApplicationComposer.wslIndexingRoute(Path)`, vide par défaut ; aucune implémentation câblée dans `minos-bootstrap`, la voie est donc refusée.
- Sonde des prérequis `WslPrerequisiteProbe` et environnement `WslCommandEnvironment` (`minos-runtime-local`, package `com.minos.runtime.local.wsl`).
- Restent : `ProviderRuntimeManager` routé, worker Linux, manifeste v3, `WslIndexerExecutor` et import (tâches 5) ; qualification (tâche 6.2), qui consigne ici ses preuves.

## References

- Changement OpenSpec : `openspec/changes/indexer-java-depuis-windows-par-wsl2/` (proposal, design D1 à D8, E5).
- ADR [SEC-0074](SEC-0074-java-non-indexable-depuis-windows-voie-wsl2.md) (décision 3, complétée) ; ADR [SEC-0076](SEC-0076-depot-maven-designe-en-lecture-seule-et-maven-hors-ligne.md) (dépôt Maven désigné, réseau `DENY`) ; ADR [SEC-0070](SEC-0070-code-non-fiable-confinement-et-indexation-distante.md) (artefacts de worker vérifiés, indexation distante fermée) ; ADR [DATA-0006](DATA-0006-promouvoir-les-index-de-maniere-atomique.md) (promotion atomique) ; ADR [ARCH-0042](ARCH-0042-racine-de-composition.md) (racine de composition) ; ADR [ARCH-0068](ARCH-0068-modules-dependances-et-propriete-des-packages.md) (direction des dépendances).
- Règle de sécurité : `.claude/rules/securite.md`, points 1, 3, 7 et 9.
- arc42 : [`09-decisions.md`](../arc42/09-decisions.md).
