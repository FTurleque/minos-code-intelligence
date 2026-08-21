# Feuille de route — MINOS

Statut au **21 août 2026** : **C0 → M30 terminés et intégrés ; MINOS 1.0.1 publiée ; hardening post-audit #113–#226 intégré ; remédiation post-#226 en qualification dans PR #227.**

La version historique détaillée antérieure à cette réconciliation est conservée intégralement dans [`history/reconciliations/ROADMAP-pre-post226-audit-20260821.md`](history/reconciliations/ROADMAP-pre-post226-audit-20260821.md). L'état opérationnel courant est dans [`STATUS.md`](STATUS.md).

## Principes durables

- une capacité n'est acquise qu'avec une preuve reproductible ;
- facts, dérivations, heuristiques et observations partielles restent distincts ;
- les snapshots structurés restent autoritatifs ;
- les providers absents ou non qualifiés ne sont jamais extrapolés ;
- CLI, API, MCP, NEXUS et IntelliJ restent des surfaces au-dessus du métier ;
- remote/hosted/sandbox restent fail-closed lorsqu'une garantie n'est pas prouvée ;
- l'accès réseau d'un provider est `DENY` par défaut et ne devient jamais `ALLOW` uniquement parce qu'un écosystème résout habituellement ses dépendances en ligne ;
- une release publiée est immuable ;
- le runtime packagé doit être testé, pas seulement le JAR ;
- une publication est bloquée par les vulnérabilités connues ou l'absence de qualification exacte du candidat.

## Trajectoire livrée

| Jalon | Résultat principal | État |
|---|---|---|
| C0 | cadrage fonctionnel et architectural | ✅ terminé |
| M0–M8 | discovery, indexation, symboles, relations, recherche, architecture, incrémental, impact | ✅ terminé |
| M9–M14 | CLI, MCP, API, multi-repo/Git, export NEXUS, installation PROD Windows | ✅ terminé |
| M15–M20 | reactor, scalabilité, provider platform, IntelliJ, advanced intelligence, semantic hybrid | ✅ terminé |
| M21 | Production Integrity | ✅ terminé |
| M22 | Advanced Provider Intelligence | ✅ terminé |
| M23 | Semantic Retrieval 2.0 | ✅ terminé |
| M24 | Polyglot Expansion | ✅ terminé |
| M25 | Remote & Distributed Indexing | ✅ terminé ; sandbox OS complétée par #98 |
| M26 | Runtime & Dynamic Intelligence | ✅ terminé |
| M27 | Team / Hosted Mode | ✅ terminé |
| M28 | Production Convergence | ✅ terminé |
| M29 | Autonomous Docker Runtime & Native Parity | ✅ M29 issue #107 CLOSED / M29 PR #108 intégrée |
| M30 | Advanced Installer, Ollama Docker & PostgreSQL/pgvector | ✅ M30 PR #110 + M30 promotion PR #111 |
| Hardening release/installer | supply-chain, Windows CI, sécurité release | ✅ #113 ; M28 Windows CI PR #117 |
| #98 Real OS worker sandbox | bubblewrap/cgroup + AppContainer/Job Object | ✅ implémenté et qualifié |
| #215–#223 | stockage, process, cgroup, API/Git/MCP, PostgreSQL, tests Windows | ✅ intégrés |
| #224 | traversées projet/NEXUS + couverture ciblée | ✅ intégrée |
| #225 | confinement workspace provider/discovery/ignore rules | ✅ intégrée |
| #226 | provenance launcher IntelliJ + walkers provider | ✅ intégrée |
| #227 | provider egress, `CommandLocator`, reparse private storage, fallback confinement capability-honest | 🟡 en qualification — non intégrée |

## Ligne de sécurité post-#226

### Provider egress

La règle de production est désormais simple : tout descendant d'un provider est considéré non fiable, y compris les scripts et hooks provenant du repository. `IndexerProcessPlanFactory.networkPolicy()` reste `DENY` par défaut. Une factory ne peut demander `ALLOW` qu'après démonstration qu'elle n'exécute pas de code repository-controlled dans cette phase, ou après séparation de ce code dans une frontière explicitement approuvée.

### Provenance des commandes

`CommandLocator` ne considère que des entrées `PATH` absolues, les canonise et retourne un fichier réel absolu. Les éléments vides ou relatifs ne peuvent plus transformer le CWD en autorité de lancement. Les batch Windows utilisent un `cmd.exe` résolu vers un fichier absolu existant.

### Confinement filesystem

Les frontières sensibles convergent sur la même politique physique : symbolic links et objets `isOther()` (notamment junction/reparse Windows) sont refusés avant récursion, ACL ou lecture sensible. `SecureDirectoryStream` reste la seule stratégie annoncée comme handle-relative ; le fallback Windows est explicitement décrit comme pathname-revalidated plutôt que sur-vendu comme équivalent `openat`.

## Release 1.0.1 — publiée

La **Release 1.0.1 est publiée** et reste immuable :

- tag/release : `v1.0.1` ;
- commit : `f762025d66e33c40324c811079f1527d122f90f9` ;
- URL : https://github.com/FTurleque/minos-code-intelligence/releases/tag/v1.0.1 ;
- **10 assets** publiés ;
- qualification incluant OSV, packaging et **Plugin Verifier**.

## Suite

Aucun nouveau jalon fonctionnel n'est ouvert par #227. La priorité est de terminer sa qualification exact-head sans diminuer les seuils ni les assertions de sécurité. Toute évolution future de provider nécessitant réellement le réseau devra introduire une phase de confiance explicite plutôt qu'un retour à un `ALLOW` implicite.
