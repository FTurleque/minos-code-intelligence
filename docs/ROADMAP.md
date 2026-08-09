# Feuille de route — MINOS

Statut au **9 août 2026** : **C0 → M30 terminés et intégrés ; hardening/readiness terminés ; MINOS 1.0.0 et 1.0.1 publiés.**

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
- la publication est bloquée par les vulnérabilités connues, l'absence de qualification exacte du candidat ou un conflit de provenance/tag/release.

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
| M25 | Remote & Distributed Indexing | ✅ livré avec limitation sandbox explicite |
| M26 | Runtime & Dynamic Intelligence | ✅ livré |
| M27 | Team / Hosted Mode | ✅ livré |
| M28 | Production Convergence | ✅ terminé — issue #93 closed / PR #102 merged |
| M29 | Autonomous Docker Runtime & Native Parity | ✅ terminé — issue #107 closed / PR #108 merged |
| M30 | Advanced Installer, Ollama Docker & PostgreSQL/pgvector | ✅ livré — PR #110 + promotion #111 |
| Hardening post-audit | sécurité, CI, PostgreSQL, JaCoCo, MCP packagé, Windows setup | ✅ #113/#117/#112 |
| Readiness 1.0.1 | Plugin Verifier, preflight release, docs et smoke MCP stabilisé | ✅ #118/#119 |
| Correctifs validation réelle | Docker local, Codex, PowerShell 5.1, staging jpackage | ✅ #122–#127 |
| Release 1.0.1 | setup Windows + supply-chain + plugin IntelliJ | ✅ publiée le 9 août 2026 |

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

## Release 1.0.1 — livrée

La release **v1.0.1** est publiée et immuable sur :

```text
f762025d66e33c40324c811079f1527d122f90f9
```

URL : <https://github.com/FTurleque/minos-code-intelligence/releases/tag/v1.0.1>

La livraison a convergé après validation utilisateur réelle et correction des derniers défauts Windows observés sur le poste mainteneur :

- installation Docker accélérée par réutilisation d'une image exacte préconstruite ;
- réparation ownership-aware d'un ancien bloc Codex MINOS ;
- parsing `docker image inspect` compatible Windows PowerShell 5.1 ;
- génération locale répétable malgré les handles conservés par un ancien runtime jpackage.

La publication finale a rejoué le Plugin Verifier, la qualification Windows complète, remplacé l'ancien état `v1.0.1` uniquement après qualification, publié 10 assets et vérifié 5 paires SHA-256 après re-téléchargement.

## Gates de release durables

Les gates actuellement acquis comprennent :

- Jackson 2/3 corrigés ;
- OSV Scanner bloquant ;
- PostgreSQL/pgvector fail-closed sur Linux ;
- Testcontainers portable Windows/Linux ;
- CI sur PR et push `develop/main` ;
- JaCoCo M29/M30 ;
- M28 exact-head Linux/Windows ;
- M19/M20 ;
- SonarCloud Quality Gate ;
- IntelliJ unit/build/structure + Plugin Verifier ;
- build jpackage + ZIP/SBOM/notices/checksums ;
- handshake MCP SDK sur runtime packagé ;
- compilation Inno Setup ;
- installation ZIP + handshake ;
- installation setup + handshake ;
- désinstallation isolée ;
- vérification des artefacts durables ;
- contrôle strict de provenance avant publication.

Le workflow manuel standard de publication reste disponible pour les releases futures ; les workflows one-shot créés uniquement pour la migration de l'ancien `v1.0.1` sont retirés après livraison.

## Priorité ouverte — #98

**#98 — Real OS worker sandbox** reste ouverte et indépendante de 1.0.1. Aucun claim d'isolation hostile ne doit être fait tant qu'un backend sandbox Windows/Linux réel n'est pas implémenté et qualifié.

## Prochaine planification fonctionnelle

Aucun jalon **M31** n'est encore engagé. La prochaine priorité structurante connue reste #98, sauf décision contraire de roadmap.
