# Tasks

Aucun constat d'audit n'est à l'origine de ce changement : il met en œuvre ADR SEC-0074 décision 3 à la demande du propriétaire (2026-11-07). Les tâches ne portent donc pas d'identifiant `AUD-…`.

## 1. Décision d'architecture

- [x] 1.1 Écrire l'ADR 0079 (commande `/minos:adr`) : voie WSL2 de l'indexation Java depuis Windows, classement en indexation locale et exécuteur de l'orchestration unique (design D2), format de transport `minos-distributed-artifact-v3` (D3), scope délégué (D4), environnement de `wsl.exe` (D6), limite de `networkDenyEnforced` et lecture de SEC-0074 décision 3 (E5) ; statut `Proposed` ; index `docs/architecture/adr/README.md` et `docs/architecture/arc42/09-decisions.md` ; « Complété par » dans SEC-0074. Vérifier par `archgate check` et `python .claude/scripts/run_gates.py --fast`.
- [ ] 1.2 **(manuelle)** Faire relire D2 et D3 par l'agent `minos-architecture-reviewer` et le propriétaire, puis passer l'ADR `Accepted` avant le groupe 5.

## 2. Option `--via-wsl` du CLI

- [x] 2.1 Rouge : `IndexCommandViaWslTest` (`minos-cli`) — refus d'usage hors Windows, refus combiné à `--scip` ou `--dry-run`, `--wsl-distro` refusé sans `--via-wsl`, aucun run créé (indexation-java-via-wsl › L'indexation via WSL n'est offerte que sous Windows). Vérifier qu'il échoue sur l'option inconnue.
- [x] 2.2 Correction : options dans `IndexCommand` (`CliOptions.Spec`, usage), port de sélection de la voie dans `minos-application` qui fournit un autre `IndexerExecutor` à `ProjectIndexingUseCase` (aucun second coordinateur : decouverte-et-negociation › Le produit n'a qu'une orchestration d'indexation), aucun type `com.minos.remote` nommé par la CLI, câblage par `minos-bootstrap` ; vérifier `./mvnw -B -ntp -pl minos-cli -am test "-Dtest=IndexCommandViaWslTest"` ; rejouer `check-module-boundaries.py` et `check-comment-language.py`.

## 3. Coursier sous Linux

- [x] 3.1 Rouge : `ManagedScipProviderRuntimeManagerCoursierLinuxTest` (`minos-provider-scip`, Linux seulement, sauté ailleurs comme l'exige installation-des-providers › Un test du provider propre à Windows est compté comme sauté ailleurs, par symétrie) — installation depuis une source épinglée factice, empreinte non conforme, hors ligne (installation-des-providers › Sous Linux, l'installation de scip-java installe Coursier depuis le catalogue). Vérifier l'échec sur « install `cs` in PATH ».
- [x] 3.2 Correction : `coursierArtifact()` et `coursierLauncherId()` selon la plateforme, puis `ensureCoursier` sous Linux : archive `gz` par `PinnedArtifactSource`, décompression bornée, publication atomique, exécution pour le seul propriétaire (design D7), origine inscrite au registre ; `scripts/ci/qualify-linux-offline-maven-index.sh` garde son `cs` ; vérifier le test 3.1 et `ManagedScipProviderRuntimeManagerToolchainTest` ; rejouer `check-private-io.py`, `check-tools-manifest.py` et le scope JaCoCo de `minos-provider-scip`.
- [x] 3.3 Documentation : dossier `docs/architecture/diagrams/capacites/installation-des-providers.md` (Coursier Linux) et prérequis Linux de `docs/user/troubleshooting.md` (le `cs` du `PATH` n'est plus requis).

## 4. Prérequis, doctor et lancement de wsl.exe (côté Windows)

- [x] 4.1 Rouge : `WslPrerequisiteProbeTest` (`minos-runtime-local`, package `com.minos.runtime.local.wsl`) — sorties de `wsl.exe` factices : aucune distribution, WSL1, systemd inactif, contrôleurs non délégables, `bwrap` absent, JVM, Maven, JDK, dépôt ; aucun chemin absolu dans les diagnostics (indexation-java-via-wsl › Les prérequis de WSL sont vérifiés avant tout lancement d'indexation).
- [x] 4.2 Correction : `WslPrerequisiteProbe` en trois étapes (design D5 : `wsl.exe -l -v` décodé en UTF-16, commande shell constante pour systemd, bus utilisateur, contrôleurs, `bwrap` et JVM 21 avant tout lancement du jar, puis sonde du jar pour Maven, JDK et dépôt), `wsl.exe` résolu dans le répertoire système ; vérifier le test 4.1 ; rejouer `check-private-io.py`.
- [ ] 4.3 Rouge puis correction : `DoctorCommandWslJavaTest` (`minos-cli`) puis section `wslJava` de `DoctorCommand`, texte et JSON, absente sous Linux (indexation-java-via-wsl › doctor rapporte la voie WSL sous Windows) ; vérifier le test.
- [x] 4.4 Rouge puis correction : `WslCommandEnvironmentTest` — `MINOS_TEAM_TOKEN`, `GITHUB_TOKEN` et un `WSLENV` hérité n'atteignent pas la commande ; puis liste d'autorisation de la voie filtrée par la méthode publique `ProviderProcessEnvironment.sanitize(Map, Map)`, sans ouvrir `isDeniedName` (indexation-java-via-wsl › wsl.exe reçoit un environnement en liste d'autorisation ; design D6) ; vérifier le test.
- [ ] 4.5 Documentation : vues « composants » et « séquence » du nouveau dossier `docs/architecture/diagrams/capacites/indexation-java-via-wsl.md`, indexé dans `docs/architecture/diagrams/capacites/README.md` ; vérifier par `python scripts/docs/check-capability-dossiers.py`.

## 5. Worker et import

- [ ] 5.1 Rouge : test du worker Linux (`minos-runtime-local`, Linux seulement) — sans cgroup délégué le provider n'est pas lancé ; sans dépôt désigné, refus qui nomme le réglage et aucun Maven exécuté ; bundle v3 avec réseau `DENY` et `networkDenyEnforced=true`, provider exécuté par le backend du provider local géré, aucune promotion ni rétention dans la distribution (indexation-java-via-wsl › Le provider tourne dans la distribution sous le bac à sable Linux, réseau coupé ; › MINOS ne peuple pas le dépôt Maven désigné).
- [ ] 5.2 Correction : commande interne du worker (protocole `minos-wsl-worker-v1`, absente de `--help`), exécution du plan scip-java sous le backend choisi par `strongestAvailableForManagedLocalProvider` (bubblewrap et `LinuxCgroupJob`) dans le scope délégué (design D2, D4), écriture du bundle v3 ; vérifier le test 5.1 sous Linux (CI `Verify (ubuntu-24.04)` ou WSL selon `docs/developer/` pour les tests de bac à sable) ; rejouer `check-private-io.py`.
- [ ] 5.3 Rouge puis correction : `WslArtifactProvenanceTest` — empreinte altérée, projet différent, empreinte d'entrée différente, manifeste v1/v2 refusé par la voie et v3 refusé par `DistributedIndexerExecutor`, bundle supprimé, instantané inchangé ; puis format `minos-distributed-artifact-v3`, variante locale de `WorkerRequest`, `WslIndexerExecutor` et son vérificateur (design D2, D3) (indexation-java-via-wsl › L'artefact reçu de WSL est vérifié avant tout import) ; vérifier le test.
- [ ] 5.4 Rouge puis correction : `WslIndexWorkerTest` avec une fausse distribution — import par staging et promotion, échec d'import qui laisse l'instantané précédent (indexation-java-via-wsl › Un artefact conforme est importé par la promotion atomique) ; vérifier le test.
- [ ] 5.5 Rouge puis correction : `WslIndexWorkerTest` — délai dépassé : arrêt du scope, aucun artefact partiel ; diagnostic d'échec sans `C:\`, `/mnt/` ni `/home/` (indexation-java-via-wsl › Un run via WSL interrompu ne laisse rien tourner ; › Les sorties de la voie WSL ne contiennent aucun chemin absolu) ; vérifier le test ; rejouer le scope JaCoCo de `minos-runtime-local` et `minos-cli`.
- [ ] 5.6 Documentation : vue « états » et traçabilité du dossier de capacité ; page `docs/developer/` sur le fonctionnement de la voie (sans annonce produit) ; vérifier les liens.

## 6. Qualification et annonce

- [ ] 6.1 **(manuelle)** Construire une installation Windows depuis la branche (procédure de candidat local), sur un poste avec WSL2 et systemd ; mesurer le JDK des paquets Ubuntu dans le bac à sable (design Q1) et l'affinité CPU (Q2) ; consigner.
- [ ] 6.2 **(manuelle)** Prouver les cinq critères de SEC-0074 décision 3 : installation, scope systemd délégué, JDK et dépôt Maven visibles sans réseau, propriétaire des fichiers sur `/mnt/<lecteur>`, indexation réelle (`fixtures/java/java-simple` et un projet Maven réel) suivie de `minos index-status` (`READY`) et `minos find-usages` ; consigner commandes, versions et durées dans le dossier de capacité (indexation-java-via-wsl › Un artefact conforme est importé par la promotion atomique ; › doctor rapporte la voie WSL sous Windows).
- [ ] 6.3 Rouge : modifier `ManagedScipProviderRuntimeManagerToolchainTest` pour attendre `minos index --via-wsl` dans le diagnostic Windows et dans le refus d'indexation native (installation-des-providers › scip-java sous Windows n'est pas déclaré prêt sans exécution qualifiée sous le bac à sable) ; vérifier qu'il échoue.
- [ ] 6.4 Correction, seulement après 6.2 : nouveau texte de `SANDBOXED_EXECUTION_NOT_QUALIFIED` (design D8) qui garde mot pour mot le préfixe « scip-java execution under the Windows AppContainer sandbox is not qualified » reconnu par `scripts/release/qualify-offline-install.ps1:212` ; vérifier le test 6.3 sous Windows ; chercher l'ancien texte dans `scripts/` et les goldens (`minos-app/src/test/resources/characterization/`) et rejouer les gates qui le citent (skill `minos-literal-gates`).
- [ ] 6.5 Documentation, seulement après 6.2 : `docs/user/troubleshooting.md` (titre et texte de la section scip-java sous Windows, l. 9-11 au HEAD), `docs/user/production-installation.md` (prérequis WSL2, dépôt Maven à peupler par l'utilisateur), `docs/project/governance/STATUS.md` (l. 47 au HEAD), vue de déploiement arc42, « Mise en œuvre » de l'ADR 0079 ; rejouer `check-current-docs.py` et `product-facts.py --check`.

## 7. Clôture

- [ ] 7.1 Mettre à jour la matrice de traçabilité du design (exigence → scénario → conception ou diagramme → tâche → test ou preuve) en nommant les preuves manquantes ; vérifier qu'aucune ligne n'affirme une couverture sans preuve.
- [ ] 7.2 Mettre à jour les dossiers `indexation-java-via-wsl.md` et `installation-des-providers.md` ; vérifier par `python scripts/docs/check-capability-dossiers.py`.
- [ ] 7.3 Valider les spécifications ; vérifier par `openspec validate --all --strict`.
- [ ] 7.4 Rejouer `python .claude/scripts/run_gates.py` et `./mvnw -B -ntp verify` sur Windows et Linux (CI des deux plateformes).
- [ ] 7.5 Lister les documents à mettre à jour avant archivage (STATUS, ROADMAP, docs/user, docs/developer, arc42, ADR 0079 passé `Accepted`, SEC-0074) ; vérifier que chacun est fait ou nommé comme reste.

## Workflow follow-up

- Archiver le changement seulement après la réussite de la tâche 6.2 (le delta de `installation-des-providers` décrit l'état qualifié), après revue (`openspec archive indexer-java-depuis-windows-par-wsl2 -y`), réécrire le « Purpose » provisoire de `indexation-java-via-wsl` et ajouter sa section « ## Documentation liée ».
- Promotion `develop` → `main` et release : hors de ce changement.
