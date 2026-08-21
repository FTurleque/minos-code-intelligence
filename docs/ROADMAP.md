# Feuille de route — MINOS

Statut au **21 août 2026** : **C0 → M30 terminés et intégrés ; MINOS 1.0.1 publiée ; hardening #113–#227 intégré.**

La remédiation du nouvel audit est actuellement en qualification.

La version historique détaillée antérieure à la réconciliation post-#226 est conservée intégralement dans [`history/reconciliations/ROADMAP-pre-post226-audit-20260821.md`](history/reconciliations/ROADMAP-pre-post226-audit-20260821.md). L'état opérationnel courant est dans [`STATUS.md`](STATUS.md).

## Principes durables

- une capacité n'est acquise qu'avec une preuve reproductible ;
- facts, dérivations, heuristiques et observations partielles restent distincts ;
- les snapshots structurés restent autoritatifs ;
- les providers absents ou non qualifiés ne sont jamais extrapolés ;
- CLI, API, MCP, NEXUS et IntelliJ restent des surfaces au-dessus du métier ;
- remote/hosted/sandbox restent fail-closed lorsqu'une garantie n'est pas prouvée ;
- le contrat managed-local-provider reste distinct d'une claim hostile/untrusted ;
- l'accès réseau d'un provider est `DENY` par défaut et ne devient jamais `ALLOW` uniquement parce qu'un écosystème résout habituellement ses dépendances en ligne ;
- les exécutables qui constituent l'autorité de sandbox ne sont pas choisis dans un PATH utilisateur ;
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
| #227 | provider egress, `CommandLocator`, reparse private storage, fallback confinement capability-honest | ✅ intégrée |
| Audit post-#227 | composition sandbox/provider + autorité des launchers + gates/docs | 🟡 remédiation courante |

## Ligne de sécurité post-#227

### Provider egress

Tout descendant d'un provider reste considéré non fiable pour la confidentialité réseau : `IndexerProcessPlanFactory.networkPolicy()` est `DENY` par défaut. Une factory ne peut demander `ALLOW` qu'après démonstration qu'elle n'exécute pas de code repository-controlled dans cette phase, ou après séparation de ce code dans une frontière explicitement approuvée.

### Deux niveaux de qualification sandbox

Le worker distant/hostile conserve le niveau fort : process/memory/CPU/descendants et filesystem bytes+entries doivent satisfaire le contrat hostile, notamment un quota filesystem `OS_ENFORCED`. Tant que le hard quota disque n'existe pas, ce chemin reste fail-closed.

Le chemin provider local géré possède désormais un contrat distinct et machine-readable. Il exige réseau OS-enforced, job boundary agrégé OS, timeout, quotas filesystem bytes+entries appliqués pendant l'exécution et reclamation scratch. Les quotas filesystem `SUPERVISED_HARD_KILL` sont acceptés uniquement dans ce niveau local ; ils ne modifient jamais `supportsUntrustedCode()`.

### Provenance des commandes de sécurité

Les commandes ordinaires peuvent continuer à provenir d'un PATH absolu canonisé. Les exécutables qui créent ou contrôlent la sandbox utilisent une règle plus forte : `bwrap`, `prlimit`, le `sh` du launcher cgroup, `systemctl` et `systemd-run` sont résolus dans des racines système Linux canoniques root-owned et non group/world-writable. PowerShell et `cmd.exe` sont ancrés à `SystemRoot\System32` ; `ComSpec` n'est pas une autorité de confiance.

### Confinement filesystem

Les frontières sensibles conservent la politique physique de #227 : symbolic links et objets `isOther()` (notamment junction/reparse Windows) sont refusés avant récursion, ACL ou lecture sensible. `SecureDirectoryStream` reste la seule stratégie annoncée comme handle-relative ; le fallback Windows est pathname-revalidated et capability-honest.

### Gates

Le `mvn verify` couvre désormais la distinction hostile/local, le sélecteur strict distant et la composition provider réelle. Les tests OS qualifient la provenance des launchers système.

`scripts/docs/product-facts.py --check` vérifie explicitement que #227 est intégrée dans STATUS, ROADMAP et le registre des risques. Il bloque les marqueurs de statut contradictoires.

## Release 1.0.1 — publiée

La **Release 1.0.1 est publiée** et reste immuable :

- tag/release : `v1.0.1` ;
- commit : `f762025d66e33c40324c811079f1527d122f90f9` ;
- URL : https://github.com/FTurleque/minos-code-intelligence/releases/tag/v1.0.1 ;
- **10 assets** publiés ;
- qualification incluant OSV, packaging et **Plugin Verifier**.

## Suite

Aucun nouveau jalon fonctionnel n'est ouvert. La priorité est de qualifier exact-head la remédiation du nouvel audit sans diminuer les seuils ni les assertions de sécurité. La dette durable reste le hard filesystem quota : si une exécution distante réellement hostile doit être activée, elle devra obtenir une primitive qui refuse l'écriture avant dépassement au lieu de reclassifier une supervision périodique.
