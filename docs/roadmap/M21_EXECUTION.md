# M21 — Production Integrity & Surface Convergence — exécution

Statut final : **TERMINÉ — #73 CLOSED / completed.**

M21 a consolidé la ligne post-M20 sans transformer les dettes de CI, qualité, documentation ou distribution en fonctionnalités métier.

## Sous-incréments

| Étape | Fonction | Disposition finale |
|---|---|---|
| M21-S1 | Governance & authoritative consolidation | ✅ VALIDÉ |
| M21-S2 | CI recovery & branch protection readiness | ✅ `PASS_WITH_CONSTRAINTS` |
| M21-S3 | Quality gates M19/M20 | ✅ VALIDÉ |
| M21-S4 | Maven module-boundary hardening | ✅ VALIDÉ |
| M21-S5 | Supply-chain & release hardening | ✅ VALIDÉ |
| M21-S6 | IntelliJ parity M19/M20 | ✅ VALIDÉ |
| M21-S7 | Advanced provider productionization | ✅ VALIDÉ |
| M21-S8 | Semantic scale qualification | ✅ VALIDÉ — `KEEP_CURRENT_M20_BACKEND` |
| M21-S9 | Final production integrity gate | ✅ VALIDÉ exact-head |

## M21-S2 — disposition finale du 1er août 2026

Le gel de juillet a été respecté puis levé. La reprise d'août a établi :

```text
CI recovery                 : PASS
required checks observés    : PASS
branch protection readiness : BLOCKED — contrainte plan/API
final disposition           : PASS_WITH_CONSTRAINTS
issue #73                   : CLOSED / completed
```

La contrainte branch protection reste une limitation de plateforme documentée, pas un faux PASS.

Runbook final : [`M21_S2_AUGUST_RECOVERY.md`](M21_S2_AUGUST_RECOVERY.md).

## Preuves structurantes

M21 a notamment consolidé :

- reactor Maven et frontières de modules ;
- supply chain/release Windows ;
- cohérence des surfaces CLI/API/MCP/IntelliJ ;
- provider avancé ;
- qualification sémantique à l'échelle ;
- JaCoCo ciblé ;
- exact-head/worktree propre.

Le replay STANDARD M21-S8 conserve la décision :

```text
status=PASS
decision=KEEP_CURRENT_M20_BACKEND
```

## Intégration ultérieure

Après M21 :

- M22→M28 ont été réalisés ;
- `develop` a été qualifié pour promotion ;
- la PR #102 a été mergée vers `main` ;
- #73 a été fermé ;
- MINOS 1.0.0 a été publié.

La maintenance Windows 1.0.1 découverte après publication ne rouvre pas M21 : elle corrige le runtime packagé et les gates de release à partir de la ligne 1.x.

## Invariants toujours valides

- snapshots structurés autoritatifs ;
- facts/dérivations/heuristiques distincts ;
- aucune capability provider inventée ;
- sémantique optionnel et non autoritatif ;
- caches/indexes reconstruisibles ;
- décisions de backend measurement-gated ;
- aucune revendication de sandbox OS réelle tant que #98 n'est pas qualifiée.
