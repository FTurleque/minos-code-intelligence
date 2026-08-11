# Feuille de route — MINOS

Statut au **9 août 2026** : **C0 → M30 terminés et intégrés ; hardening/readiness terminés ; MINOS 1.0.0 et 1.0.1 publiés ; remédiation post-audit #132 / #98 implémentée et qualifiée dans PR #135.**

L'état opérationnel courant est dans [`STATUS.md`](STATUS.md). Les preuves détaillées restent sous [`roadmap/`](roadmap/), les décisions durables sous [`adr/`](adr/README.md), l'architecture sous [`architecture/`](architecture/README.md) et les preuves historiques sous [`history/milestones/`](history/milestones/README.md).

## Principes de roadmap

- une capacité n'est acquise qu'avec une preuve reproductible ;
- facts, dérivations, heuristiques et observations partielles restent distincts ;
- les snapshots structurés restent autoritatifs ;
- les capacités provider absentes ne sont jamais extrapolées ;
- CLI, API, MCP, NEXUS et IntelliJ restent des surfaces au-dessus du métier ;
- les claims remote/hosted/sandbox restent fail-closed lorsqu'ils ne sont pas prouvés ;
- une release publiée est immuable ;
- le runtime packagé doit être testé, pas seulement le JAR ;
- un backend Docker n'est équivalent au natif qu'après qualification de parité métier, données, providers, MCP et lifecycle ;
- la publication est bloquée par les vulnérabilités connues, l'absence de qualification exacte du candidat ou un conflit de provenance/tag/release ;
- un sandbox OS ne peut annoncer `OS_ENFORCED` qu'après une sonde de capacité réelle de la plateforme courante.

## Trajectoire livrée

| Jalon | Résultat principal | État |
|---|---|---|
| C0 | cadrage fonctionnel et architectural | ✅ livré |
| M0–M8 | discovery, indexation, symboles, relations, recherche, architecture, incrémental, impact | ✅ livré |
| M9–M14 | CLI, MCP, API, multi-repo/Git, export NEXUS, installation PROD Windows | ✅ livré |
| M15–M20 | reactor, scalabilité, provider platform, IntelliJ, advanced intelligence, semantic hybrid | ✅ livré |
| M21 | Production Integrity | ✅ terminé — issue #73 closed |
| M22 | Advanced Provider Intelligence | ✅ livré |
| M23 | Semantic Retrieval 2.0 | ✅ livré |
| M24 | Polyglot Expansion | ✅ livré |
| M25 | Remote & Distributed Indexing | ✅ livré ; sandbox OS complétée post-audit par #98/#135 |
| M26 | Runtime & Dynamic Intelligence | ✅ livré |
| M27 | Team / Hosted Mode | ✅ livré |
| M28 | Production Convergence | ✅ terminé — issue #93 closed / PR #102 merged |
| M29 | Autonomous Docker Runtime & Native Parity | ✅ terminé — issue #107 closed / PR #108 merged |
| M30 | Advanced Installer, Ollama Docker & PostgreSQL/pgvector | ✅ livré — PR #110 + promotion #111 |
| Hardening post-audit initial | sécurité, CI, PostgreSQL, JaCoCo, MCP packagé, Windows setup | ✅ #113/#117/#112 |
| Readiness 1.0.1 | Plugin Verifier, preflight release, docs et smoke MCP stabilisé | ✅ #118/#119 |
| Correctifs validation réelle | Docker local, Codex, PowerShell 5.1, staging jpackage | ✅ #122–#127 |
| Release 1.0.1 | setup Windows + supply-chain + plugin IntelliJ | ✅ publiée le 9 août 2026 |
| Remédiation audit complet | supply-chain immuable, frontières process/path/config, concurrence PostgreSQL, architecture, qualité | ✅ PR #135 |
| #98 Real OS worker sandbox | Linux bubblewrap/namespaces/prlimit + Windows AppContainer/Job Object, exact-head | ✅ implémenté et qualifié dans #135 |

## M29 — Autonomous Docker Runtime & Native Parity

M29 est **TERMINÉ**. Le point d'entrée client durable reste `minos.exe mcp`, qui route vers `native|docker` sans logique Docker dans les configurations clientes et sans fallback silencieux.

## M30 — Advanced Installer, Ollama Docker & PostgreSQL/pgvector

M30 est **LIVRÉ**. Les axes restent indépendants :

```text
runtime MCP       native | docker | none
storage           local | postgresql
semantic          disabled | local-hash | ollama
```

Le wizard Windows propose Standard/Avancé, les intégrations MCP détectées, la configuration PostgreSQL/Ollama/Docker applicable, puis un résumé avant installation. PostgreSQL/pgvector est un backend réel et versionné ; PostgreSQL et Ollama peuvent être gérés dans le runtime Docker.

## Remédiation post-audit — #132 / PR #135

La campagne post-audit ferme les findings structurants identifiés après 1.0.1 :

- Coursier et providers téléchargés depuis des références immuables avec checksums attendus ;
- images Docker de base par digest OCI et workflows Actions par commit SHA ;
- `ProviderId`, confinement des répertoires et artefacts sans suivi de symlink ;
- suppression du faux `safeCommand` qui cassait le signal SAST ;
- configuration `MINOS_HOME` sans état JVM global ;
- migrations PostgreSQL sérialisées et unicité de racine garantie par la base ;
- suppression de la dépendance `minos-api → minos-cli` ;
- sorties processus IntelliJ bornées ;
- parser Ollama robuste ;
- quoting batch Windows qualifié sur cas adversariaux ;
- contrats packaging et seuils JaCoCo alignés sur les garanties réellement vérifiées.

## #98 — Real OS worker sandbox

#98 n'est plus une capacité seulement planifiée : l'implémentation est livrée dans la PR #135 et sa qualification exige une preuve exact-head sur les deux OS.

### Linux

- `bubblewrap --unshare-all` ;
- network namespace isolé pour `DENY`, partage explicite uniquement pour `ALLOW` ;
- racine hôte en lecture seule et racines d'écriture bornées ;
- `--cap-drop ALL` ;
- frontière de job cgroup v2 par run : `memory.max`, `memory.swap.max`, `pids.max`, `cpu.max`, `cgroup.kill` ;
- limites `prlimit` mémoire virtuelle/processus/fichiers/CPU conservées comme défense en profondeur **par processus** uniquement ;
- sonde runtime des primitives, du contexte LSM/userns et de la délégation cgroup ;
- fallback process-only + rejet de `ALLOW` comme de `DENY` si une sonde échoue.

### Windows

- AppContainer sans capability réseau pour `DENY`, ou avec la seule capability `internetClient` pour `ALLOW` ;
- validation `TokenIsAppContainer` avant reprise du processus ;
- Job Object configuré avant la création du processus, avec kill-on-close, mémoire, process count, CPU hard cap et job time ;
- vérification `IsProcessInJob` avant `ResumeThread`, relecture des limites appliquées, breakaway interdit et `TerminateJobObject` sur tous les chemins de sortie ;
- ACL temporaires limitées aux racines gérées par MINOS ;
- aucune modification des ACL système Windows.

### Quota d'écriture et résidu

- budget d'écriture (octets et nombre d'entrées) appliqué pendant l'exécution sur toutes les racines accessibles au provider, avec destruction de la frontière de job au dépassement ;
- récupération du résidu du run après succès, erreur, timeout ou dépassement ;
- rétention des runs bornée, avec quarantaine d'un ancien run pathologique au lieu d'un échec propagé.

Les tests exact-head couvrent les tentatives réseau, les écritures hors racine, les limites de ressources agrégées, la survie des descendants, un provider hostile en écriture et le chemin réel `ProcessIndexerExecutor → sandbox → provider → artefact`.

## Release 1.0.1 — livrée

La release **v1.0.1** est publiée et immuable sur :

```text
f762025d66e33c40324c811079f1527d122f90f9
```

URL : <https://github.com/FTurleque/minos-code-intelligence/releases/tag/v1.0.1>

La livraison a convergé après validation utilisateur réelle et correction des derniers défauts Windows observés sur le poste mainteneur. La publication finale a rejoué le Plugin Verifier, la qualification Windows complète, publié 10 assets et vérifié 5 paires SHA-256 après re-téléchargement.

## Gates durables

Les gates actuellement acquis comprennent :

- OSV Scanner bloquant ;
- supply-chain Actions / Docker / providers immuable et vérifiée ;
- PostgreSQL/pgvector fail-closed sur Linux ;
- Testcontainers portable Windows/Linux ;
- CI sur PR et push `develop/main` ;
- JaCoCo ciblé M0–M30 ;
- M19/M20 ;
- IntelliJ unit/build/structure + Plugin Verifier ;
- build jpackage + ZIP/SBOM/notices/checksums ;
- handshake MCP SDK sur runtime packagé ;
- compilation Inno Setup ;
- installation ZIP + handshake ;
- installation setup + handshake ;
- désinstallation isolée ;
- contrôle strict de provenance avant publication ;
- sandbox OS exact-head Linux/Windows sans skip dans la campagne #135.

Le workflow manuel standard de publication reste disponible pour les releases futures ; les workflows one-shot créés uniquement pour une migration ou une qualification ponctuelle sont retirés après usage.

## Prochaine planification fonctionnelle

Aucun jalon **M31** n'est encore engagé. Après l'intégration de #135 et la fermeture de #98/#132, aucune priorité structurante ouverte n'est imposée par la roadmap ; la prochaine évolution doit être décidée à partir des besoins produit et des mesures disponibles.
