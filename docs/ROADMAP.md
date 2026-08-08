# Feuille de route — MINOS

Statut au **8 août 2026** : **C0 → M30 terminés et intégrés ; hardening #113/#117 intégré et promu par #112 ; MINOS 1.0.0 publié ; 1.0.1 en pré-publication et non publiée.**

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
- la publication est bloquée par les vulnérabilités connues, l'absence de qualification exacte du candidat ou un conflit de tag/release.

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
| Hardening post-audit | sécurité, CI, PostgreSQL, JaCoCo, MCP packagé, Windows setup | ✅ #113 + #117 + #112 |

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

## Hardening post-audit — terminé

Le hardening porté par **#113**, corrigé par **#117** puis promu sur `main` par **#112**, est terminé.

Gates acquis :

- Jackson 2/3 corrigés ;
- OSV Scanner bloquant ;
- PostgreSQL/pgvector fail-closed sur Linux ;
- Testcontainers portable Windows/Linux ;
- CI sur PR et push `develop/main` ;
- JaCoCo M29/M30 ;
- M28 exact-head Linux/Windows ;
- M19/M20 ;
- SonarCloud Quality Gate ;
- build jpackage + ZIP/SBOM/notices/checksums ;
- handshake MCP SDK sur runtime packagé ;
- compilation Inno Setup ;
- installation ZIP + handshake ;
- installation setup + handshake ;
- désinstallation isolée ;
- vérification des artefacts durables.

Le Plugin Verifier IntelliJ est désormais qualifié sur PR/push pertinents et fait également partie du workflow manuel de publication.

## Release 1.0.1 — phase de décision

1.0.1 reste **NON PUBLIÉE**. Aucun ancien setup/ZIP n'est un candidat final.

Le candidat final doit provenir du `main` post-hardening et satisfaire :

```text
HEAD candidat
→ Maven Windows + Linux
→ PostgreSQL/pgvector réel obligatoire sur Linux
→ JaCoCo M0–M30
→ OSV
→ SonarCloud
→ IntelliJ build/tests/Plugin Verifier
→ MCP initialize/tools-list
→ lifecycle M29/M30
→ build + install + handshake + uninstall setup Windows
→ validation utilisateur finale du wizard et des clients réels
→ résolution explicite de tout conflit tag/release
→ autorisation explicite de publication
→ seulement ensuite GitHub Release
```

### Blocage de gouvernance actuel

Un tag Git historique `v1.0.1` existe sur `2de847bdc6bc39e63715f20987a30f07731cc717`, alors que le `main` post-hardening est plus récent. Ce tag ne doit pas être déplacé ou supprimé implicitement. Le workflow de release le détecte avant le build et échoue volontairement tant que la situation n'a pas été résolue explicitement.

## Priorité ouverte — #98

**#98 — Real OS worker sandbox** reste ouverte et indépendante de 1.0.1. Aucun claim d'isolation hostile ne doit être fait tant qu'un backend sandbox Windows/Linux réel n'est pas implémenté et qualifié.

## Prochaine planification fonctionnelle

Aucun nouveau jalon M31 n'est engagé avant la décision de publication 1.0.1. Après la release, la prochaine priorité structurante connue reste #98, sauf décision contraire de roadmap.
