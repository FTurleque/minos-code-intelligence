# État courant — MINOS

Dernière mise à jour : **21 août 2026**.

Ce fichier est la synthèse autoritative de l'état produit. Les preuves détaillées et les journaux de qualification restent dans [`roadmap/`](roadmap/), [`history/milestones/`](history/milestones/), [`adr/`](adr/README.md) et [`architecture/`](architecture/README.md).

> **Convention de référencement.** L'état courant est ancré sur des **numéros de PR**, jamais sur un SHA de `develop`. Un SHA cité ici est périmé dès le merge qui l'introduit — le commit de merge est nécessairement postérieur au contenu qu'il publie —, ce qui recréait une dérive à chaque réconciliation. Les SHA immuables (tags de release, par exemple `v1.0.1`) restent cités explicitement : eux ne bougent jamais. Les sections historiques ci-dessous conservent les SHA déjà figés à titre d'archive.

## Réconciliation post-PR #225 — 21 août 2026

`develop` intègre désormais les campagnes #224 et #225. Un nouvel audit complet du tree publié par #225 a confirmé **aucun P0 et aucun nouveau P2 dans le cœur MINOS**, mais a découvert un **P1 dans la provenance du launcher IntelliJ** ainsi que trois écarts P3 de durcissement/qualité/documentation. La remédiation est portée par la **PR #226** et reste non intégrée tant que son HEAD final n'a pas passé la qualification exact-head puis été mergé.

- **P1 — binary planting / configuration de launcher IntelliJ.** Sous Windows, le défaut `minos.cmd` était relatif alors que `MinosCliClient` plaçait le `ProcessBuilder` dans la racine du projet avant exécution ; un `minos.cmd` planté dans un workspace pouvait donc masquer l'installation MINOS. En parallèle, `executable` et `MINOS_HOME` étaient stockés dans un service `PROJECT`, donc potentiellement dans du metadata projet versionnable. #226 déplace ces réglages sensibles au niveau application/IDE, résout le launcher en chemin réel absolu **avant** d'appliquer le working directory projet, n'autorise un nom relatif que comme commande recherchée dans des entrées `PATH` absolues, ignore les éléments PATH vides/relatifs et refuse les chemins relatifs explicites. La frontière Windows d'ownership rejette également les junctions/reparse points avant toute mutation d'ACL.
- **P3 — walkers provider encore dépendants d'un confinement amont.** `BoundedProviderSourceProbe` et le staging `ScipJavaProcessPlanFactory` deviennent autonomes : rejet `isOther()`/junction des répertoires non récursables, suppression via `FileTreeOperations`, lectures de staging par `ConfinedFileOpener` afin d'éviter une réouverture de pathname, tests de symlink et vraies junctions `mklink /J` sous Windows.
- **P3 — couverture ciblée `scip-java`.** Le scope `m24-polyglot-provider-platform` inclut désormais `BoundedProviderSourceProbe` et `ScipJavaProcessPlanFactory`, avec floors par préfixe dédiés. Le gate P0-P2 exige aussi explicitement les invariants de provenance du launcher et de confinement provider.
- **P3 — documentation.** STATUS, ROADMAP, registre des risques et guide IntelliJ sont réalignés sur #224/#225 et sur la remédiation #226 sans présenter cette dernière comme intégrée avant sa qualification.

## Réconciliation post-audit — 20 août 2026

Réadit ciblé de `develop` après PR #219/#220/#221. Aucun P0/P1. Les quatre P2 et les deux P3 identifiés ont été confirmés sur le code courant puis corrigés dans **PR #222** ; la stabilisation déterministe du test Windows d'ownership a été intégrée dans **PR #223**. L'état courant est donc ancré au minimum jusqu'à PR #223, et la dette de test DT-10 est close.

- **P2 — perte du `commitStatus` dans l'API Java.** `LocalMinosApi` jetait le statut de commit et le diagnostic en construisant `IndexImportDto` : les quatre états d'import (committé, durabilité et/ou métadonnées en attente) étaient indistinguables pour un consommateur Java, alors que la CLI les restituait déjà. Correction **additive** : `importScipOutcome()` retourne `IndexImportOutcomeDto` (mêmes données + `ImportCommitStatus` + diagnostic assaini), `importScip()` est inchangé, aucun record public existant n'est modifié et `CONTRACT_VERSION` reste `1`. Le `default` répond `UNAVAILABLE` plutôt que d'annoncer `COMMITTED`.
- **P2 — perte de capacités dans `LocalMinosMultiRepositoryApi`.** La façade M12 héritait des `default` `UNAVAILABLE` de `MinosApi` pour `getArchitectureGraph(...)` et `team()`, capacités que l'application sous-jacente possède. Les deux sont redéléguées, ainsi que `contractVersion()` : sans exemption, l'invariant « toute opération `MinosApi` est redéléguée » devient vérifiable par réflexion au lieu d'une liste maintenue à la main.
- **P2 — budget incomplet dans `GitIntelligenceService`.** `maxFiles` ne bornait que le résultat ; tout le travail était accumulé avant troncature. `ActivityBudget` borne le diff d'un commit, les maps fichiers/zones suivies et les chemins retenus, et la taille du diff est mesurée par un `TreeWalk` borné **avant** matérialisation. `maxFiles` conserve sa sémantique publique et les limites atteintes sont restituées explicitement.
- **P2 — TOCTOU sur les lectures de sources.** `LocalSourceReader` validait un pathname puis le rouvrait. `ConfinedFileOpener` supprime la fenêtre : descente par handles `SecureDirectoryStream` + `NOFOLLOW_LINKS` là où la plateforme le permet, sinon ouverture atomique `NOFOLLOW_LINKS` de la feuille puis vérification de la chaîne d'ancêtres handle déjà ouvert. Même motif corrigé dans le sidecar program-graph et le fingerprint projet.
- **P3 — `maxLength` MCP ≠ octets UTF-8.** Le `maxLength` publié est désormais une borne de **caractères** dérivée explicitement du budget, chaque propriété documentant le budget en octets ; le contrôle serveur en octets réels reste l'autorité et est inchangé.
- **P3 — taxonomie des requêtes `null`.** Un `null` fourni par un appelant était présenté comme `EXECUTION_FAILURE`. Les frontières publiques valident désormais leurs paramètres via la politique commune ; `execute()` **n'attrape toujours pas** `NullPointerException`, afin qu'un défaut interne reste un défaut interne.
- **DT-10 — test Windows racy.** Observation du PID enfant rendue déterministe : publication atomique, lecture uniquement après observation de l'événement de création (`WatchService`), mort du descendant attendue sur `ProcessHandle.onExit()`, scénario de timeout exécuté sur son propre thread pour **prouver** que l'enfant était lancé et vivant avant l'expiration du budget. Budget provider porté à 90 s après qu'un runner hébergé a demandé plus de 20 s pour le seul démarrage de PowerShell. Aucun sleep d'attente, aucun retry, aucun skip, aucune assertion de sécurité modifiée.

Qualification : `clean verify` complet, gate JaCoCo (seuils **inchangés**), pins de workflow, cohérence P0-P2, MINOS-01, frontières de modules, Product Facts et provenance Inno Setup.

## Réconciliation post-PR #218 — 19 août 2026

**PR #218** (`fix/audit-linux-cgroup-and-quality-gate-20260819`) a fermé les deux constats du dernier audit ciblé :

- **P1 — délégation cgroup v2 trop large.** Les deux helpers de provisioning accordaient au compte MINOS la propriété durable de `/sys/fs/cgroup/cgroup.procs` (racine). cgroup v2 n'autorisant une migration que si le délégataire peut écrire le `cgroup.procs` de l'**ancêtre commun** des cgroups source et destination, ce droit — nécessaire uniquement parce que MINOS démarrait *hors* de son sous-arbre — lui permettait de déplacer des processus n'importe où, donc de **sortir de sa propre frontière de délégation**. La migration unique requise est désormais effectuée par le script pendant sa phase privilégiée (`--attach-pid`), qui place le shell lanceur dans `$ROOT/minos-controller` ; MINOS y démarre déjà, ne migre plus rien et n'écrit que dans le sous-arbre qu'il possède. C'est la forme que produit nativement `systemd Delegate=yes`, resté inchangé. Aucun fichier Java modifié : `LinuxCgroupJob` n'écrivait jamais le `cgroup.procs` racine.
- **P2 — trou dans le quality gate JaCoCo.** Le scope `provider-sandbox-linux` référençait `LinuxCgroupV2`, classe disparue ; comme le filtrage testait le tuple de préfixes entier, un préfixe voisin vivant suffisait à maintenir le scope `PASS` alors que la frontière de job cgroup n'était plus mesurée. Le scope pointe désormais `LinuxCgroupJob`, et **chaque préfixe déclaré doit matcher au moins une classe**, sinon le scope échoue en nommant le préfixe mort.

Barrières ajoutées : `check-p0-p2.py` refuse toute réintroduction d'un `chown`/`chmod`/`chgrp`/`setfacl` sur le `cgroup.procs` racine et exige que les workflows sandbox attachent réellement leur shell ; `check-jacoco.py --self-test` vérifie la logique de décision du gate sur 7 scénarios.

Qualification CI Linux : `Attached PID … to /sys/fs/cgroup/minos.slice/minos-controller`, 17 tests cgroup exécutés sans skip, `provider-sandbox-linux: PASS line=0.814 branch=0.665` avec des seuils **inchangés**.

## Réconciliation post-PR #215 / #216 — 19 août 2026

`develop` a été réaudité intégralement (50 commits depuis la réconciliation précédente, répartis sur deux PR mergées).

**PR #215** (`fix/post-audit-hardening-20260818-v2`) a durci les frontières de confiance du stockage local et de l'exécution provider :

- stores file-backed (registre projet, index-state, run) rejettent une racine ou une propriété symlinkée au lieu de la suivre ;
- partitions run d'index-state confinées à leur racine attendue ;
- providers locaux exigent désormais une sandbox qualifiée et une politique réseau explicite avant exécution, avec workspace provider isolée ;
- décodage SCIP borné par un budget heap explicite, empêchant l'amplification mémoire d'un payload adversarial avant le décodage complet ;
- chaque connexion HTTP JGit épinglée à l'endpoint du dépôt demandé, empêchant une redirection vers un hôte arbitraire ;
- classification des hôtes loopback PostgreSQL restreinte aux formes littérales, et mutations du registre projet PostgreSQL sérialisées pour éliminer une course d'enregistrement concurrent ;
- plans de sandbox Windows consommés de façon éphémère au lieu d'être retenus ;
- fichiers de workspace provider (local et remote worker) partagés entre les deux implémentations au lieu d'être dupliqués ;
- seuils JaCoCo critiques relevés.

**PR #216** (`fix/final-minos-hardening-20260819`) a fermé quatre findings d'un audit ciblé plus un finding découvert pendant sa remédiation :

- `ProcessIndexerExecutor` : un provider hostile remplaçant l'emplacement de l'artefact préexistant par un répertoire non vide ne peut plus faire échouer silencieusement la restauration de la sauvegarde ni faire perdre l'exception primaire ; le répertoire hostile est mis en quarantaine par un renommage non récursif plutôt que supprimé ;
- découvert pendant cette remédiation : les walkers de suppression récursive/mesure (`FileTreeOperations`, `ProviderWorkspaceFiles`, `RunDirectoryRetention`, `ProviderWriteQuotaSupervisor`) suivaient une jonction NTFS Windows plantée par un provider comme un répertoire ordinaire ; ils vérifient désormais `isDirectory() && !isSymbolicLink() && !isOther()` avant de descendre ;
- `release-windows.yml` et `intellij-plugin-release.yml` séparés en un job de build/qualification `contents: read` et un job de publication minimal `contents: write` déclenché uniquement sur `main`/à la publication d'une release ; la release IntelliJ résout désormais le tag en SHA vérifié contre `target_commitish` avant build, et n'utilise plus `--clobber` sur le chemin nominal ;
- arguments de chaîne des outils MCP bornés par des `maxLength` sémantiques centralisés (`McpArgumentBounds`), appliqués au schéma JSON **et** revérifiés côté serveur en octets UTF-8 réels.

Des tests adversariaux couvrent chaque scénario (répertoire hostile vide/non vide, échec provider, timeout, jonction Windows réelle créée via `mklink /J`, dépassement de borne MCP avec caractères multi-octets et paires de substituts).

## Réconciliation post-PR #183 — 14 août 2026

Après le merge de la PR #183, `develop@20ce803ea43fbfa579b463f79e04e9272b2b81ce` a été réaudité intégralement. La campagne de remédiation ouverte depuis ce HEAD traite les écarts découverts par ce nouvel audit sans modifier directement `develop` :

- diagnostics du provider ouverts par MINOS **avant** l'exécution de code non fiable et conservés via des descripteurs déjà ouverts ; un provider qui remplace `provider.stdout.log`, `provider.stderr.log` ou `process.txt` par un symlink ne peut plus rediriger les écritures hôte hors sandbox ;
- lifecycle de containment armé avant `transform()` : une frontière cgroup/Job créée pendant la transformation est libérée même si la validation du plan ou la préparation pré-start échoue ;
- `LocalAutonomousIndexOperations` utilise le `ProjectIndexStateReconciler` commun et échoue fermé lorsqu'un état persistant référence un snapshot autoritatif absent, au lieu de masquer la divergence en `NEVER_INDEXED` ;
- stores file-backed renforcés : projet, workspace, project index state et run vérifient l'identité UUID embarquée contre la clé/nom de fichier lors des lookups **et** des listings ;
- Maven Wrapper 3.9.16 vérifié par SHA-256 possédé par le dépôt ;
- image Docker provider-complete durcie : archive Ubuntu datée, Maven Docker vérifié par SHA-256 local, npm lockfiles + `npm ci --ignore-scripts`, `scip-dotnet` depuis un `.nupkg` exact vérifié puis source NuGet locale, Go via version exacte + `proxy.golang.org`/`sum.golang.org` sans bypass privé ;
- documentation Docker et registre des risques rendus capability-honest : le bootstrap Coursier de `scip-java` reste versionné et observable par hash du binaire produit, mais **aucune reproductibilité bit-à-bit n'est revendiquée** tant que son graphe transitif n'est pas représenté par un lockfile possédé par le dépôt.

Des tests déterministes couvrent les divergences snapshot, les identités file-backed, l'échec pré-start après création de containment et le remplacement adversarial des pathnames diagnostics par symlink.

## Réconciliation post-audit précédente — PR #182 / #183

Après le merge de la PR #182, `develop@20440b353d6a89e40b949cc1e56214d550dbdca6` avait été relu intégralement. Cette campagne avait fermé :

- composition M25 restaurée entre `StrongProcessOwnershipIndexerExecutor` et les sandboxes workers Linux/Windows via une capability explicite, sans nesting de frontières OS ;
- plugin IntelliJ lancé derrière une autorité d'ownership établie avant le CLI : Job Object Windows avec création suspendue, scope systemd/cgroup sous Linux, absence de fallback silencieux ;
- build Docker provider-complete aligné sur les lockfiles npm v3 possédés par le dépôt et `npm ci --ignore-scripts` pour `scip-typescript` et `scip-python` ;
- documentation et registre des risques réconciliés avec ces garanties et leurs limitations d'exploitation.

Les protections `ProcessHandle`/PID-start-time et les trackers Java restent des défenses en profondeur ; elles ne sont plus présentées comme autorité kernel-backed. Les plateformes sans frontière forte qualifiée restent fail-closed.

## Synthèse

```text
C0 → M30                         TERMINÉS / LIVRÉS
M29 issue #107                  CLOSED / completed
M29 PR #108                     MERGED
M30 PR #110                     MERGED vers develop
M30 promotion PR #111           MERGED vers main
hardening PR #113               MERGED
M28 Windows CI PR #117          MERGED
promotion develop → main #112   MERGED
readiness PR #118               MERGED
readiness promotion PR #119     MERGED / QUALIFIÉE
correctifs installateur #122–127 MERGED / QUALIFIÉS
v1.0.0                          PUBLIÉE / IMMUTABLE
v1.0.1                          PUBLIÉE / IMMUTABLE
post-audit #132 / PR #135       REMÉDIATION IMPLÉMENTÉE / QUALIFICATION FINALE
#98 sandbox OS réelle           IMPLÉMENTÉE + QUALIFIÉE LINUX/WINDOWS dans #135
PR #182 / #183                  HARDENING SNAPSHOT / OWNERSHIP / M25 / SUPPLY-CHAIN INTÉGRÉ
PR #215                          HARDENING STOCKAGE LOCAL / SCIP / JGit / PostgreSQL INTÉGRÉ
PR #216                          ARTEFACT PROVIDER / JONCTIONS WINDOWS / RELEASE CI / BORNES MCP INTÉGRÉ
PR #217                          RÉCONCILIATION DOCUMENTAIRE / PRÉREQUIS SANDBOX LINUX INTÉGRÉ
PR #218                          DÉLÉGATION CGROUP CONTENUE / GATE JACOCO DURCI INTÉGRÉ
PR #219–#223                     RÉCONCILIATIONS POST-AUDIT / CONTRATS / CONFINEMENT / STABILISATION WINDOWS INTÉGRÉES
PR #224                          TRAVERSÉES PROJET / NEXUS / COUVERTURE CIBLÉE INTÉGRÉES
PR #225                          WORKSPACE PROVIDER / DISCOVERY / IGNORE RULES PHYSIQUEMENT CONFINÉS INTÉGRÉS
PR #226                          PROVENANCE LAUNCHER INTELLIJ / WALKERS PROVIDER / JACOCO — EN QUALIFICATION, NON INTÉGRÉE
```

## Campagne post-audit — #132 / PR #135

La campagne post-audit a traité les findings P1/P2/P3 sans réduire les garanties existantes.

Principales corrections :

- launcher Coursier, providers directs et images de base épinglés ; les claims supply-chain distinguent désormais les entrées vérifiées des graphes transitifs non lockés ;
- images de base Docker épinglées par digest OCI ;
- GitHub Actions épinglées par SHA et gate anti-régression ;
- sandbox worker OS réelle avec confinement agrégé : Linux `bubblewrap`/namespaces + frontière de job cgroup v2, Windows AppContainer + Job Object ;
- `DENY` reste fail-closed lorsque la primitive OS n'est pas réellement disponible ;
- IDs providers validés, confinement de chemins et artefacts `NOFOLLOW_LINKS` ;
- suppression du faux contournement SAST `safeCommand` ;
- configuration isolée par `MINOS_HOME`, sans contamination via propriétés JVM globales ;
- migrations PostgreSQL sérialisées par advisory lock et enregistrement projet atomique avec unicité SQL ;
- dépendance architecturale `minos-api → minos-cli` supprimée ;
- parser Ollama migré vers Jackson ;
- sorties des processus IntelliJ bornées ;
- quoting `.cmd/.bat` Windows qualifié avec cas adversariaux ;
- seuils JaCoCo relevés progressivement et contrats packaging alignés sur la provenance effectivement démontrée.

### Preuve sandbox #98

La PR #135 contient une qualification exact-head dédiée qui :

- checkout le `pull_request.head.sha` exact sur Linux et Windows ;
- interdit les skips dans les tests sandbox ;
- vérifie les échecs réseau et filesystem attendus ;
- vérifie les limites OS ;
- exécute le chemin réel `ProcessIndexerExecutor → sandbox OS → provider → artefact`.

Linux n'annonce `OS_ENFORCED` qu'après une sonde runtime réussie des namespaces/userns/LSM. Windows vérifie `TokenIsAppContainer` avant reprise du child et l'attache à un Job Object borné : ensemble de capabilities vide pour `DENY`, seule capability `internetClient` pour `ALLOW`. En absence de mécanisme qualifié, le worker distant refuse `ALLOW` comme `DENY` avant l’exécution.

L'issue #98 est donc techniquement résolue par #135.

## Release 1.0.1 — publiée

La release **MINOS v1.0.1** a été publiée le **9 août 2026** après validation utilisateur réelle du setup Windows.

Tag autoritatif :

```text
v1.0.1 → f762025d66e33c40324c811079f1527d122f90f9
```

Release : <https://github.com/FTurleque/minos-code-intelligence/releases/tag/v1.0.1>

La publication transactionnelle finale a :

- reconnu et sauvegardé l'ancienne Release/tag 1.0.1 ;
- checkout le commit exact testé par le mainteneur ;
- construit le plugin IntelliJ en version finale `1.0.1` ;
- rejoué tests/build/structure et IntelliJ Plugin Verifier ;
- reconstruit et requalifié la distribution Windows ;
- validé ZIP, setup, MCP handshakes, installation et désinstallation ;
- remplacé l'ancien tag/release seulement après ces gates ;
- publié **10 assets** : 8 artefacts Windows/supply-chain + plugin IntelliJ ZIP/checksum ;
- re-téléchargé les 10 assets ;
- vérifié **5 paires payload/SHA-256** ;
- confirmé le tag final sur `f762025d66e33c40324c811079f1527d122f90f9`.

Workflow de publication final : run `31288322126`, conclusion **success**.

Le commit de release est immuable. Les commits postérieurs de documentation/maintenance ne doivent jamais déplacer `v1.0.1`.

## Qualification acquise

La qualification ayant mené à 1.0.1 comprend :

- Maven Linux avec PostgreSQL/pgvector Testcontainers réel et fail-closed ;
- Maven Windows ;
- JaCoCo ciblé M0–M30 ;
- OSV Scanner bloquant ;
- M19 / M20 ;
- M28 exact-head Linux et Windows ;
- SonarCloud Quality Gate ;
- IntelliJ unit tests / build / structure ;
- IntelliJ Plugin Verifier ;
- build jpackage Windows ;
- ZIP, SBOM CycloneDX, third-party notices et SHA-256 ;
- handshake MCP SDK sur runtime packagé ;
- compilation Inno Setup ;
- installation ZIP + handshake ;
- installation setup + handshake ;
- désinstallation setup ;
- contrôle des artefacts durables ;
- validation utilisateur réelle du wizard et de l'exécutable final.

La qualification post-audit ajoute notamment les courses PostgreSQL corrigées, les fitness functions d'architecture renforcées, les sandboxes OS Linux/Windows réelles et des entrées supply-chain explicitement épinglées/vérifiées.

## M29 — Autonomous Docker Runtime & Native Parity

M29 est terminé et intégré. Le contrat durable reste :

```text
clients IA
   ↓
minos.exe mcp
   ↓
backend router
  ↙      ↘
native   docker
```

Les clients ne contiennent aucune logique Docker. Le choix `native|docker` est résolu derrière le point d'entrée stable et aucun fallback silencieux Docker→native n'est autorisé.

Les anciens checkpoints S3/S4/S5 décrits dans l'historique M29 sont des preuves intermédiaires ; ils ne constituent plus l'état produit courant. Le guide Docker distingue désormais explicitement ces checkpoints historiques de la capacité intégrée actuelle.

## M30 — Advanced Installer, Ollama Docker & PostgreSQL/pgvector

M30 est livré. Capacités intégrées :

- wizard Windows **Standard / Avancé** ;
- runtime MCP : `native | docker | none` ;
- stockage : `local | postgresql` ;
- sémantique : `disabled | local-hash | ollama` ;
- PostgreSQL/pgvector réel avec migrations ;
- PostgreSQL/Ollama Docker gérés ;
- intégrations MCP Copilot JetBrains, Copilot CLI, Claude CLI/Code, Claude Desktop et Codex CLI/Desktop ;
- résumé des choix avant installation ;
- upgrade/switch/uninstall transactionnels et ownership-aware.

## Hardening et release engineering

Les PR #113/#117/#118/#119, les correctifs #122–#127 et les campagnes post-audit ont notamment livré :

- Jackson 2/3 corrigés et centralisés ;
- OSV bloquant ;
- PostgreSQL/pgvector obligatoire sur Linux ;
- Testcontainers cohérent Windows/Linux ;
- JaCoCo M29/M30 et seuils post-audit relevés ;
- handshake MCP SDK du runtime packagé ;
- Plugin Verifier IntelliJ ;
- build/smoke Windows end-to-end ;
- détection/réparation ownership-aware des clients MCP ;
- réutilisation d'une image Docker locale exactement labellisée ;
- génération locale Windows répétable malgré les locks jpackage transitoires ;
- publication fail-closed et vérification post-upload des checksums ;
- actions/images/providers directs/lockfiles et distributions outillées épinglés selon leur mécanisme de provenance ;
- worker sandbox OS qualifié Linux/Windows ;
- diagnostics provider préouverts et résistants aux races de symlink ;
- réconciliation snapshot unique et fail-closed ;
- validation d'identité durable dans les stores fichier.

## Release 1.0.0

`v1.0.0` reste immuable. Le défaut Windows historique `NoClassDefFoundError: org/w3c/dom/Node` appartient à cette release et est corrigé par 1.0.1. Le tag 1.0.0 ne doit jamais être déplacé ou recréé.

## Sources de vérité

- état courant : `docs/STATUS.md` ;
- feuille de route : `docs/ROADMAP.md` ;
- architecture : `docs/architecture/README.md` ;
- registre des risques : `docs/architecture/risks/register.md` ;
- quality gates : `docs/developer/quality-gates.md` ;
- guide production Windows : `docs/user/production-installation.md` ;
- guide Docker courant : `docs/user/docker-runtime.md` ;
- release 1.0.1 : `docs/releases/1.0.1.md` ;
- historique de livraison 1.0.1 : issue #106.
