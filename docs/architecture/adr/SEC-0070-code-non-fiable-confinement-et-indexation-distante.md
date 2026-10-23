---
id: SEC-0070
title: "Code non fiable : confinement des providers et indexation distante"
domain: security
rules: true
files: ["minos-runtime-local/src/main/java/**/WorkerSandboxQualification.java", "scripts/docs/product-facts.py"]
---

# Code non fiable : confinement des providers et indexation distante

- **Statut** : Accepted
- **Date** : 2026-10-23
- **Origine** : consolidation du registre (décision du propriétaire du 2026-10-23)
- **Remplace** : [ADR-0033](archive/0033-immutable-remote-revisions-and-verified-worker-artifacts.md), [ADR-0036](archive/0036-fail-closed-production-boundaries-and-measured-program-graph.md) § 3, [ADR-0038](archive/0038-aggregate-worker-resource-containment.md), [ADR-0041](archive/0041-indexation-distante-de-code-non-fiable.md)
- **Remplacé par** : —

## Context

- MINOS exécute des providers d'indexation sur du code qu'il n'a pas écrit : ce code n'est pas de confiance, pas plus que le réseau ni le worker qui l'indexe.
- Une URL distante, une branche mobile ou un résultat de worker ne constituent pas une preuve reproductible. Les credentials peuvent fuiter dans une URL, un journal, la configuration Git ou un manifest. Un ZIP non borné ou un `index.scip` dont la provenance est seulement déclarative peut contourner les invariants de staging et de promotion atomique (ADR 0033, Context).
- Une séparation de processus n'est pas un bac à sable : elle ne prouve ni le refus réseau ni le confinement du système de fichiers, et la présence d'un exécutable ne prouve pas que le noyau et la politique LSM autorisent le bac à sable (ADR 0036 § 3, Rejected alternatives).
- Une limite par processus (`prlimit`) se multiplie à chaque `fork` et ne borne pas l'arbre du provider ; seule une frontière de job agrégée le fait (ADR 0038, Context).
- Aucune des deux plateformes n'offre à un utilisateur non privilégié un quota disque par job appliqué par l'OS (ADR 0038 § 4). L'étude des variantes (quota de projet XFS ou ext4, tmpfs, qgroups btrfs sous Linux ; quotas NTFS, VHDX, Job Object sous Windows), leur chiffrage et les sources consultées restent dans l'[ADR 0041 archivé](archive/0041-indexation-distante-de-code-non-fiable.md) (§ 2 à § 7) : Windows n'atteint pas un quota `OS_ENFORCED` avec l'architecture AppContainer, Linux l'atteindrait au prix d'un prérequis root supplémentaire.
- Principe directeur : une revendication d'isolation qui n'est pas exactement vraie est pire qu'une absence de revendication (ADR 0038, Context ; ADR 0041, Context).

## Decision

### 1. Sources distantes épinglées (ADR 0033 § 1 à § 3)

1. Une source distante est décrite par `RemoteRepositoryRequest` : endpoint HTTPS officiel `github.com` ou `gitlab.com`, ref canonique, commit SHA-1 complet, sous-répertoire relatif et politique `FETCH_ONLY`.
2. Les credentials sont référencés par nom de variable d'environnement et résolus uniquement pendant le clone. Ni valeur ni référence ne sont écrites dans l'origin Git, les métadonnées de cache, les manifests ou les évidences CLI.
3. `JGitRemoteRepositoryMaterializer` clone sans submodule dans une entrée temporaire, vérifie origin, HEAD exact, worktree propre et confinement du sous-répertoire, puis publie atomiquement dans un cache reconstructible et borné.

### 2. Artefacts de worker vérifiés (ADR 0033 § 4 à § 7)

4. `DistributedIndexing.Worker` est provider-neutral. L'isolation déclarée est `PROCESS_EPHEMERAL_WORKSPACE` ; la politique réseau est obligatoire. Un worker ne peut accepter `DENY` que s'il prouve réellement l'enforcement réseau OS.
5. Le transport `minos-distributed-artifact-v1` est un ZIP contenant exactement `manifest.properties` et `index.scip`. Le manifest porte source, commit, run, projet, langage, provider et version, worker, isolation, réseau, temps, taille et SHA-256. Le format `minos-distributed-artifact-v2` étend le manifest avec `projectRelativeRoot` pour les monorepos multi-module.
6. Le coordinateur valide format, chemins, bornes, checksum et concordance intégrale de provenance en mode fail-closed, y compris le `projectRelativeRoot` du manifest face à la requête, ce qui rend impossible un échange de portée entre modules. Le bundle reçu est détruit après acceptation ou rejet ; seul le cache vérifié borné subsiste.
7. L'artefact accepté passe par `ScipSymbolSnapshotImporter`, le staging et la promotion atomique existants ([ADR 0006](DATA-0006-promouvoir-les-index-de-maniere-atomique.md)). Les snapshots structurés restent autoritatifs.

### 3. Revendications de bac à sable qualifiées par plateforme (ADR 0036 § 3)

- `WorkerSandboxBackend` expose une `NetworkGuarantee` ; `WorkerSandboxQualification` expose les dispositions de refus réseau, de confiance et de plateforme.
- Le backend processus seul reste explicite : `PROCESS_EPHEMERAL_WORKSPACE`, `NetworkGuarantee.NONE`, `FAIL_CLOSED_NOT_ENFORCED`, `UNTRUSTED_CODE_UNSUPPORTED`, `WORKER_SANDBOX_CLAIM_PROHIBITED`.
- `DENY` est rejeté avant l'exécution du provider dès que le backend choisi ne prouve pas l'enforcement OS ; il n'est jamais rétrogradé en `ALLOW`.
- **Linux** — `LinuxBubblewrapWorkerSandboxBackend` utilise `bubblewrap --unshare-all` ; un espace de noms réseau isolé pour `DENY`, le partage explicite du réseau de l'hôte seulement pour `ALLOW` ; la racine de l'hôte montée en lecture seule ; seules les racines de workspace, d'artefact et de run accessibles en écriture ; les capacités retirées ; des bornes `prlimit` par processus sur l'espace d'adressage, les fichiers ouverts et la CPU, le nombre de processus étant borné par le cgroup (§ 4) ; une sonde d'exécution bornée avant toute annonce `OS_ENFORCED`. La qualification Ubuntu 24.04 charge le profil AppArmor `bwrap-userns-restrict` fourni par la distribution, pour tester la frontière d'espaces de noms utilisateur non privilégiés qu'exerce la production. Si la politique du noyau ou du LSM, ou un outil requis, ne permet pas le bac à sable, la découverte échoue et MINOS reste en processus seul, fail-closed.
- **Windows** — `WindowsAppContainerWorkerSandboxBackend` utilise un jeton AppContainer à ensemble de capacités vide pour `DENY`, ou portant la seule capacité système `internetClient` pour `ALLOW` ; la vérification de `TokenIsAppContainer` avant la reprise du fils ; des droits ACL temporaires limités aux racines possédées par MINOS ou par le provider ; aucune modification des ACL système de Windows ; un Job Object (kill-on-close, processus actifs, mémoire de job, plafond CPU) durci selon le § 4.
- Une implémentation OS n'est acceptée qu'avec des tests négatifs d'évasion réseau et fichiers et un test de chemin réel `ProcessIndexerExecutor → sandbox → provider → artefact`.

### 4. Confinement des ressources agrégé et par dimension (ADR 0038 § 1 à § 3 et § 5)

- `WorkerResourceContainment` décrit chaque dimension (processus agrégés, mémoire agrégée, CPU agrégée, wall-clock, octets écrits, entrées écrites, terminaison des descendants, récupération du scratch) avec une disposition parmi `OS_ENFORCED`, `SUPERVISED_HARD_KILL`, `MEASURED_ONLY` et `UNAVAILABLE`.
- `WorkerSandboxQualification` refuse structurellement `UNTRUSTED_CODE_SUPPORTED` sans une frontière de job `OS_ENFORCED` sur les quatre dimensions agrégées et au moins une application pendant l'exécution sur les autres. Une limite par processus n'est jamais présentée comme une garantie agrégée.
- **Linux : cgroup v2.** Un cgroup v2 dédié au run, au provider et à la portée est créé avant l'exécution, configuré avec `memory.max`, `memory.swap.max`, `pids.max` et `cpu.max` relus depuis le noyau, rejoint par le processus sandbox avant l'exec du code non fiable, invisible depuis la sandbox, puis détruit par `cgroup.kill` et supprimé. Le nombre de processus de l'arbre du provider est borné par `pids.max` seul, obligatoire ; `prlimit --nproc` n'est pas passé, car `RLIMIT_NPROC` plafonne les tâches de l'utilisateur réel, threads de l'hôte compris, et ne borne ni un processus ni l'arbre du provider (ADR 0038 § 2, amendement du 2026-10-16). `prlimit` garde `RLIMIT_AS`, `RLIMIT_NOFILE` et `RLIMIT_CPU` en défense en profondeur. Sans racine cgroup déléguée réellement sondée, le backend Linux n'existe pas et l'exécution non fiable échoue avant le lancement du provider.
- **Windows : Job Object durci.** Le Job Object est créé et configuré avant le processus contenu, qui est créé suspendu, vérifié `TokenIsAppContainer`, assigné puis vérifié par `IsProcessInJob` avant `ResumeThread`. Les limites appliquées sont relues par `QueryInformationJobObject`, tout breakaway est refusé, une limite de temps CPU de job s'ajoute et `TerminateJobObject` est appelé sur tous les chemins de sortie.
- **Windows : droits accordés au SID du conteneur.** Chaque chemin est inscrit au journal de récupération du run avant `icacls /grant`. La reprise qui prouve la mort du propriétaire (verrou exclusif) retire chaque droit journalisé ; une propriété non prouvable n'est jamais récupérée. Les entrées que rien ne journalise sont recensées par `scripts/windows/Find-MinosAppContainerGrants.ps1`, en lecture seule, et retirées seulement avec `-Remove` et confirmation. Les classes de tests qui démarrent le vrai bac à sable vérifient en fin de classe qu'aucune entrée nouvelle ne reste sur le JDK (ADR 0038 § 3, amendement du 2026-10-08).
- **Résidus.** Le résidu du provider dans le run est récupéré après succès, erreur, timeout ou dépassement. La rétention traite un ancien run illisible ou hors budget comme récupérable en priorité, borne la suppression par invocation et met en quarantaine le reliquat au lieu de propager un échec ([ADR 0039](BE-0039-reprise-indexation-apres-interruption.md) complète cette rétention).
- **Aucun provider sans frontière.** Aucun exécuteur du module `minos-runtime-local` ne lance un provider sans frontière : `ProcessIndexerExecutor.execute` refuse (message fixe « provider process requires an ownership or sandbox boundary ») avant toute création de répertoire de run, toute exécution passe par `executeSandboxed` avec le transformateur qu'une frontière fournit, le backend natif ne peut pas exécuter un exécuteur de processus nu, et le constructeur de `StrongProcessOwnershipIndexerExecutor` sans politique réseau n'est pas visible hors de son package. Un test qui exerce le processus lui-même passe par `UnboundedProcessForTests`, seul contournement nommé, qui ne prouve aucune frontière (ADR 0038, mise en œuvre AUD-ARC-09).

### 5. Quota d'écriture : supervision, jamais garantie OS (ADR 0038 § 4, amendé par ADR 0041)

MINOS applique pendant l'exécution un budget d'octets **et** d'entrées sur toutes les racines accessibles en écriture au provider, avec destruction de la frontière de job au dépassement, et le déclare `SUPERVISED_HARD_KILL`, jamais `OS_ENFORCED`. Cette supervision n'est pas un état transitoire en attente d'une primitive mais une décision : `UNTRUSTED_CODE_SUPPORTED` exige un quota `OS_ENFORCED` sur les octets et les entrées, et aucun backend intégré ne l'atteint.

### 6. Indexation distante de code non fiable fermée par décision (ADR 0041, option (b))

- L'indexation distante de code non fiable reste fermée par décision, sur tous les OS, en `ALLOW` comme en `DENY` : un environnement sans bac à sable qualifié pour du code non fiable n'exécute aucun provider distant (ADR 0036, Costs ; ADR 0041, statut et § 8).
- `WorkerSandboxQualification` ajoute, dans son unique branche de rétrogradation, la limitation lisible par machine `WORKER_UNTRUSTED_CODE_CLOSED_BY_DECISION_ADR_0041` ; ce point unique couvre tout backend futur (ADR 0041, écart d'implémentation (a)). Cet identifiant est une sortie publique stable et reste inchangé : il nomme la décision par son numéro d'origine, que le présent ADR reprend.
- La sélection du backend est rapportée : `WorkerSandboxSelection` distingue `REJECTED_BY_DECISION` (seule cause qui cite la décision), `NO_OS_BACKEND_AVAILABLE` (prérequis manquant, ou plateforme sans backend intégré) et `EXECUTOR_NOT_SANDBOX_CAPABLE` (refus du worker en profondeur). La sélection émet un WARNING qui porte les identifiants de backend et les codes des dimensions manquantes, jamais un chemin ; le libellé distingue un prérequis à fournir d'une plateforme sans backend (ADR 0041 § 5, lot b1 ; écarts (b) et (c)).
- `minos doctor` comporte une section `workerSandbox` (texte et JSON) : backend local géré retenu, backend « code non fiable » retenu, `remoteIndexing: UNAVAILABLE`, codes manquants et renvoi à la décision, sans chemin ; le verdict `ready` n'en dépend pas, la fermeture étant une décision et non une panne (ADR 0041 § 5, lot b3).
- `remote index` refuse **avant** toute matérialisation, tout enregistrement de projet et tout épinglage de source, avec le même diagnostic. Le fournisseur de sélection de `LocalRemoteIndexOperations` est obligatoire : aucune valeur ne désactive ce contrôle ; seuls les tests du transport injectent une sélection qualifiée factice (ADR 0041 § 5, lot b4 ; écart (d)).
- Le transport distant n'est pas supprimé : il est fermé par décision, et ses tests prouvent le contrat qu'un futur backend devra honorer (ADR 0041 § 5, lot b2).
- Rouvrir l'indexation distante de code non fiable exige un ADR qui remplace celui-ci, fondé sur un besoin confirmé ; la variante Linux par quota de projet est chiffrée dans l'[ADR 0041 archivé](archive/0041-indexation-distante-de-code-non-fiable.md) (§ 4).

## Do's and Don'ts

### Do

- Décrire une source distante par `RemoteRepositoryRequest` (HTTPS `github.com` ou `gitlab.com`, ref canonique, SHA-1 complet, sous-répertoire relatif, `FETCH_ONLY`) et ne résoudre un credential que pendant le clone, depuis le nom de sa variable d'environnement.
- Valider en fail-closed le format, les chemins, les bornes, le checksum et la concordance intégrale de provenance de l'artefact du worker, `projectRelativeRoot` compris, puis le faire passer par `ScipSymbolSnapshotImporter`, le staging et la promotion atomique.
- Rejeter `DENY` avant l'exécution du provider quand le backend choisi ne prouve pas l'enforcement OS.
- N'accepter une implémentation OS qu'avec une sonde d'exécution, des tests négatifs d'évasion réseau et fichiers et un test de chemin réel `ProcessIndexerExecutor → sandbox → provider → artefact`.
- Décrire chaque dimension de confinement par `WorkerResourceContainment`, avec sa disposition réelle.
- Sous Linux, créer avant l'exécution un cgroup v2 dédié, configuré et relu depuis le noyau, rejoint avant l'exec du code non fiable, puis détruit par `cgroup.kill` ; borner le nombre de processus par `pids.max`.
- Sous Windows, configurer le Job Object avant le processus, vérifier `IsProcessInJob` avant `ResumeThread`, refuser tout breakaway, appeler `TerminateJobObject` sur tous les chemins de sortie, et journaliser chaque droit accordé au SID du conteneur avant `icacls /grant`.
- Récupérer le résidu du provider après succès, erreur, timeout ou dépassement.
- Garder l'indexation distante de code non fiable fermée par décision sur tous les OS, refuser `remote index` avant toute matérialisation, journaliser le refus et le rendre diagnosticable par `minos doctor`.
- Aligner les revendications (README, documents, `WorkerSandboxQualification`) sur ce que l'OS applique, et faire évoluer le test verrou avec la décision sans jamais le supprimer.

### Don't

- N'écrire ni la valeur ni la référence d'un credential dans l'origin Git, les métadonnées de cache, les manifests ou les évidences CLI.
- Ne pas indexer directement le `HEAD` d'une branche ; ne pas importer directement le ZIP du worker.
- Ne pas traiter une séparation de processus comme un bac à sable, ni annoncer un bac à sable sur la seule présence d'un outil.
- Ne pas rétrograder silencieusement `DENY` en `ALLOW`.
- Ne pas déclarer `UNTRUSTED_CODE_SUPPORTED` sans frontière de job `OS_ENFORCED` sur les quatre dimensions agrégées, ni présenter une limite par processus comme une garantie agrégée.
- Ne pas passer `prlimit --nproc` ; ne pas exécuter de code non fiable sous Linux sans racine cgroup déléguée réellement sondée.
- Ne pas déclarer le quota d'écriture `OS_ENFORCED` : il est `SUPERVISED_HARD_KILL`.
- Ne pas lancer un provider sans frontière depuis `minos-runtime-local`.
- Ne pas laisser un résidu ni un ancien run hors budget bloquer le run suivant.
- Ne pas prévoir de valeur qui désactive le refus précoce de `LocalRemoteIndexOperations`.
- Ne pas rouvrir `remote index` pour du code non fiable sans ADR qui remplace celui-ci.
- Ne pas renommer la limitation publique `WORKER_UNTRUSTED_CODE_CLOSED_BY_DECISION_ADR_0041`.

## Consequences

### Positives

- Une branche qui avance entre la demande et le clone produit un échec, jamais un autre snapshot silencieux ; le cache est une optimisation locale reconstructible, pas une autorité (ADR 0033).
- Une revendication `UNTRUSTED_CODE_SUPPORTED` correspond exactement au confinement applicable sur l'hôte ; un `fork` ne multiplie aucune limite agrégée et aucun descendant ne survit au run (ADR 0038).
- Un provider ne peut ni remplir le disque, ni épuiser les entrées, ni empoisonner `runsRoot` sans être détruit (ADR 0038).
- L'absence d'une primitive se traduit par un refus avant exécution, pas par une garantie affaiblie et silencieuse ; le refus est journalisé et diagnosticable (ADR 0038 ; ADR 0041).
- La disponibilité du bac à sable est sondée, jamais déduite du nom d'un exécutable (ADR 0036).
- Un futur backend conteneur ou VM peut implémenter le même port et prouver `DENY` sans modifier le cœur ni la CLI (ADR 0033).

### Négatives

- `remote index` reste indisponible sur tous les OS ; seule l'indexation **locale** des providers gérés utilise les bacs à sable, sous le contrat plus étroit du provider local géré (ADR 0041 § 2.3 et § 5).
- Le backend natif de référence exige `ALLOW` explicite, car il ne revendique pas une isolation réseau OS qu'il ne possède pas (ADR 0033).
- Linux exige une délégation cgroup v2 (unité systemd `Delegate=yes` ou `MINOS_SANDBOX_CGROUP_ROOT`) et des primitives `bubblewrap`/util-linux qu'une politique LSM et d'espaces de noms utilisateur autorise ; la qualification déplace le processus MINOS dans un cgroup enfant dédié pour que la racine déléguée porte `cgroup.subtree_control` (ADR 0036, Costs ; ADR 0038, Coûts).
- Windows exige un cycle de vie ACL correct et une interopérabilité PowerShell/Win32 pour l'AppContainer (ADR 0036, Costs).
- La supervision d'écriture ajoute un parcours périodique borné des racines accessibles au provider (ADR 0038, Coûts).
- GitHub ou GitLab Enterprise, SSH, submodules, LFS externe, ordonnanceur, workers non fiables et multi-tenant restent hors contrat (ADR 0033).

### Risques

- SCIP ne prouve ni CFG, ni def-use, ni flux de données, ni capacité de sécurité : [ADR 0026](BE-0026-discovery-provider-spi-and-explicit-capability-profiles.md), [ADR 0065](BE-0065-providers-scip-polyglottes-livres-dans-le-paquet.md) et [ADR 0072](ARCH-0072-graphe-de-programme-honnete-sur-ses-capacites.md) s'appliquent à tout artefact distant accepté.
- Un chemin inscriptible ajouté hors des racines supervisées échapperait au budget d'écriture : toute nouvelle racine se déclare dans la supervision.

## Compliance and Enforcement

- Gate `scripts/quality/check-remote-distributed-consistency.py`, exécuté par `scripts/remediation/check-post-mne.py` (job `invariants`) — vérifie dans les sources la validation de `RemoteRepositoryRequest` (hôtes, `https`, SHA-1 complet, `FETCH_ONLY`, ni `http://`, ni `ssh://`, ni `git@`), le clone sans submodule ni journalisation de credential, les caches bornés, le SHA-256 du manifest, le câblage de l'indexation distante par la racine de composition (`WorkerSandboxBackends.selectForUntrustedCode(home)`), les noms des tests qui portent chaque contrat (refus d'échange de portée, sortie CLI sans secret), et que la roadmap M25, les guides utilisateur et développeur et le texte de l'ADR énoncent les mêmes faits (`github.com`, `gitlab.com`, SHA, `DENY`, fail-closed, `minos-distributed-artifact-v1` et `-v2`, `projectRelativeRoot`, provenance, cache borné).
- Gate `scripts/docs/product-facts.py --check` (job `invariants`) — exige `OS_ENFORCED` sur les octets et les entrées dans `WorkerResourceContainment.unmetRequirements()`, la limitation littérale `WORKER_UNTRUSTED_CODE_CLOSED_BY_DECISION_ADR_0041` dans `WorkerSandboxQualification`, la citation « ADR 0041 » dans `STATUS.md`, le README, `docs/developer/remote-worker-sandbox-disposition.md` et `docs/user/remote-indexing.md`, et refuse dans le README l'ancien nom du test verrou.
- Gate `scripts/quality/check-vertical-decomposition-consistency.py`, exécuté par `scripts/remediation/check-post-mne.py` (job `invariants`) — exige `NetworkGuarantee.NONE` et `NetworkGuarantee.OS_ENFORCED` dans `WorkerSandboxBackend`, les dispositions `FAIL_CLOSED_NOT_ENFORCED`, `UNTRUSTED_CODE_UNSUPPORTED` et `WORKER_SANDBOX_CLAIM_PROHIBITED` dans `WorkerSandboxQualification`, et le refus fail-closed de `DENY` dans `LocalIsolatedIndexWorker`.
- Tests de `minos-runtime-local` (lancés par `mvnw verify`) : `WorkerResourceContainmentTest` (dont `currentOsBackendsStayFailClosedByDecisionAdr0041` : les backends OS intégrés restent rétrogradés pour du code non fiable), `WorkerSandboxQualificationTest` (aucune revendication de `DENY` ni de code non fiable sans preuve OS), `WorkerSandboxBackendsTest` (sélection rapportée, trois causes, WARNING sans chemin), `LinuxBubblewrapWorkerSandboxBackendTest` (réseau isolé pour `DENY`, aucun `--nproc`), `LinuxCgroupJobContainmentTest` (Linux) et `WindowsJobObjectContainmentTest` (Windows) (limites agrégées réellement appliquées, aucun descendant survivant), `ProcessIndexerExecutorBoundaryRefusalTest` (aucun provider sans frontière).
- Tests de `minos-cli` : `LocalRemoteIndexOperationsRefusalTest` (refus avant toute matérialisation, sélection obligatoire), `DoctorCommandTest` (indexation distante indisponible, cause et codes, sans chemin).
- Règle Archgate `SEC-0070-code-non-fiable-confinement-et-indexation-distante.rules.ts` / `decision-limitation-in-code` : refuse l'absence du littéral `"WORKER_UNTRUSTED_CODE_CLOSED_BY_DECISION_ADR_0041"` (commentaires exclus) dans `WorkerSandboxQualification` ; second exécutant de `scripts/docs/product-facts.py --check`.
- Règle Archgate `SEC-0070-code-non-fiable-confinement-et-indexation-distante.rules.ts` / `product-facts-requires-limitation` : refuse que `scripts/docs/product-facts.py` cesse de comparer un texte à ce littéral (lecture de son AST Python) ; contrôle propre à Archgate, il garde le gate lui-même.
- La frontière de job et la qualification d'un bac à sable n'ont pas de règle Archgate : ce sont des comportements prouvés par sonde et tests négatifs ; les autres chaînes lisibles par fichier sont tenues par les gates ci-dessus.
- Revue : une nouvelle revendication de bac à sable s'appuie sur une sonde d'exécution et des tests négatifs ; une nouvelle dimension ou un nouveau backend déclare sa disposition réelle sans promouvoir une supervision en garantie OS ; tout nouveau chemin d'import d'artefact distant passe par la validation du coordinateur et par le staging.

## Écarts constatés

Relevés le 2026-10-23 (audit des ADR) ; la décision ci-dessus reste celle en vigueur tant qu'un amendement ou une correction du code n'a pas tranché.

- La trace de jalon M25 de l'[ADR 0033 archivé](archive/0033-immutable-remote-revisions-and-verified-worker-artifacts.md) (conséquence sur le backend natif, section « Evidence ») consigne `QUALIFIED_WITH_CONSTRAINTS` pour le worker natif avec politique `ALLOW` et `BLOCKED/NOT_RUN` pour `DENY` ; au HEAD, `remote index` refuse avant toute matérialisation tant qu'aucun bac à sable n'est qualifié pour du code non fiable, en `ALLOW` comme en `DENY` (§ 6 ci-dessus) — preuve : `minos-cli/src/main/java/com/minos/cli/operations/LocalRemoteIndexOperations.java:102,111-119` ; `README.md:197`. Cette disposition M25 n'est pas l'état d'exploitation.

## References

- ADR remplacés : [0033](archive/0033-immutable-remote-revisions-and-verified-worker-artifacts.md) (preuves de qualification M25 : [`M25_EXECUTION.md`](../../project/roadmap/M25_EXECUTION.md)), [0036](archive/0036-fail-closed-production-boundaries-and-measured-program-graph.md) § 3 (preuves M28 : [`M28_EXECUTION.md`](../../project/roadmap/M28_EXECUTION.md)), [0038](archive/0038-aggregate-worker-resource-containment.md), [0041](archive/0041-indexation-distante-de-code-non-fiable.md) (étude des options et sources externes).
- ADR liés : [0006](DATA-0006-promouvoir-les-index-de-maniere-atomique.md) (promotion atomique), [0039](BE-0039-reprise-indexation-apres-interruption.md) (rétention des répertoires de run), [0042](ARCH-0042-racine-de-composition.md) (racine de composition, refus en profondeur du worker isolé), [0057](ARCH-0057-finaliser-les-frontieres-hexagonales.md) (fermeture de `remote index` tenue pour invariant), [0060](SEC-0060-scip-java-windows-natif-bloque-indexation-java-par-docker.md) (frontière AppContainer à ne pas élargir), [0061](SEC-0061-deux-niveaux-de-confidentialite-pour-l-ecriture-de-fichiers-prives.md) (E/S privées), [0065](BE-0065-providers-scip-polyglottes-livres-dans-le-paquet.md) (providers et outils vérifiés), [0071](SEC-0071-plan-de-controle-d-equipe-embarque.md) et [0072](ARCH-0072-graphe-de-programme-honnete-sur-ses-capacites.md) (autres parts de l'ADR 0036).
- Spécification courante : [`openspec/specs/confinement-code-non-fiable/spec.md`](../../../openspec/specs/confinement-code-non-fiable/spec.md).
- Documentation : [`docs/user/remote-indexing.md`](../../user/remote-indexing.md), [`docs/developer/remote-distributed-indexing.md`](../../developer/remote-distributed-indexing.md), [`docs/developer/remote-worker-sandbox-disposition.md`](../../developer/remote-worker-sandbox-disposition.md).
- arc42 : [§ 8 Concepts transverses](../arc42/08-concepts-transverses.md), [§ 9 Décisions](../arc42/09-decisions.md).
