# Feuille de route — MINOS

Statut au **8 août 2026** : **C0 → M30 terminés et intégrés ; MINOS 1.0.0 publié ; 1.0.1 Windows en préparation et non publiée ; hardening post-audit en cours sur PR #113.**

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
- la publication est bloquée par les vulnérabilités de dépendances connues et par l'absence de qualification exacte du candidat.

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

## M29 — Autonomous Docker Runtime & Native Parity

M29 est **TERMINÉ**. Les huit sous-étapes ont des preuves exact-head et l'issue **#107** est fermée.

| Sous-étape | Résultat |
|---|---|
| M29-S1 — Backend contract & ADR | ✅ PASS `c7a4e944...` |
| M29-S2 — Project identity / portable persistence | ✅ PASS `c7a4e944...` |
| M29-S3 — Autonomous Docker administration | ✅ PASS `3df1b40...` |
| M29-S4 — Provider-complete Docker image | ✅ PASS `3df1b40...` |
| M29-S5 — Autonomous indexing & vector lifecycle | ✅ PASS `0959fb9...` |
| M29-S6 — Backend-agnostic MCP integration | ✅ PASS `f7ef0e3...` |
| M29-S7 — Installer / switching / lifecycle | ✅ PASS `50b462f...` |
| M29-S8 — Native/Docker parity | ✅ PASS `da6a76f...` |

Contrat durable : `minos.exe mcp` reste le point d'entrée unique des clients et route vers `native|docker`; les configurations clientes ne contiennent pas de commandes Docker spécifiques.

## M30 — Advanced Installer, Ollama Docker & PostgreSQL/pgvector

M30 est **LIVRÉ** par PR #110 puis promu sur `main` par PR #111.

Les axes sont indépendants :

```text
runtime MCP       native | docker | none
storage           local | postgresql
semantic          disabled | local-hash | ollama
```

Le wizard Windows propose Standard/Avancé, les intégrations MCP détectées, la configuration PostgreSQL/Ollama/Docker applicable, puis un résumé avant installation. Le backend PostgreSQL/pgvector est réel, versionné et testé ; PostgreSQL et Ollama peuvent être gérés dans le runtime Docker sans exposer de port public au query plane.

## Hardening post-audit — PR #113

Avant le prochain candidat 1.0.1, la branche `audit/release-installer-hardening` doit converger sur :

- Jackson 2 et Jackson 3 corrigés ;
- gate OSV Scanner bloquant ;
- PostgreSQL/pgvector fail-closed en CI ;
- Testcontainers portable Windows/Linux ;
- CI sur PR **et** push `develop/main` ;
- couverture JaCoCo M29/M30 ;
- documentation current-state non figée sur d'anciens HEAD ;
- wizard Windows harmonisé avec les patterns utiles du Windows deployment wizard de NEXUS Context Engine ;
- nouveau candidat 1.0.1 construit uniquement après qualification exacte.

## Release 1.0.1

1.0.1 reste **NON PUBLIÉE**. Aucun ancien setup/ZIP n'est un candidat final après les changements de M29/M30 et le hardening post-audit.

Gate de publication attendu :

```text
HEAD candidat
→ build Maven Windows + Linux
→ PostgreSQL/pgvector réel obligatoire
→ JaCoCo M0–M30
→ scan vulnérabilités
→ IntelliJ Plugin Verifier
→ MCP handshake / tools
→ M29/M30 lifecycle et Docker
→ build + smoke setup Windows
→ validation utilisateur finale
→ seulement ensuite tag/release v1.0.1
```

## Priorité ouverte — #98

**#98 — Real OS worker sandbox** reste ouverte et indépendante de M29/M30. Aucun claim d'isolation hostile ne doit être fait tant qu'un backend sandbox Windows/Linux réel n'est pas implémenté et qualifié.

## Prochaine planification fonctionnelle

Aucun nouveau jalon M31 n'est déclaré comme engagé dans cette synthèse. La priorité immédiate est de terminer PR #113, reconstruire un candidat 1.0.1 exact-head et fermer les incohérences de gouvernance associées à M30/release.
