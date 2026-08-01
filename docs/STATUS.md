# État courant — MINOS

Dernière mise à jour documentaire : **1er août 2026 — M28 S1→S8 implémentés et mergés dans `develop` via PR #96 ; qualification locale exact-head en cours ; S9/CI/main explicitement différés.**

Ce fichier est la synthèse autoritative de l’état courant. Les contrats détaillés et les preuves historiques restent dans [`roadmap/`](roadmap/), [`history/milestones/`](history/milestones/) et [`adr/`](adr/README.md).

## Synthèse

```text
C0→M20                                TERMINÉS, VALIDÉS ET LIVRÉS sur main
M21 — Production Integrity           S2 EN PAUSE — S1/S3→S9 localement validés
M22 — Advanced Provider Intelligence TERMINÉ, VALIDÉ, MERGÉ develop
M23 — Semantic Retrieval 2.0         TERMINÉ, VALIDÉ, MERGÉ develop
M24 — Polyglot Expansion             TERMINÉ, VALIDÉ, MERGÉ develop
M25 — Remote & Distributed Indexing  TERMINÉ, VALIDÉ, MERGÉ develop
M26 — Runtime & Dynamic Intelligence TERMINÉ, VALIDÉ, MERGÉ develop
M27 — Team / Hosted Mode             TERMINÉ, VALIDÉ, MERGÉ develop
M28 — Production Convergence         S1→S8 MERGÉS develop / S9 PARTIEL — PR #96 MERGED
```

**État livré sur `main` : C0→M20.**

`develop` contient le tree M21 localement qualifié et M22→M28 S1→S8 fusionnés. M28 est défini par l'issue #93 et [`roadmap/M28_EXECUTION.md`](roadmap/M28_EXECUTION.md). Le gel CI de juillet 2026 a pris fin le 1er août 2026 ; M21-S2/CI et la promotion vers `main` restent explicitement différés dans cette session.

## M21 — Production Integrity & Surface Convergence

Issue : **#73 OPEN**. Roadmap : [`roadmap/M21_EXECUTION.md`](roadmap/M21_EXECUTION.md).

```text
S1   governance + docs + runner local                 VALIDÉ
S2   CI recovery + readiness branch protection        EN PAUSE jusqu’en août 2026
S3   quality gates M19/M20                            VALIDÉ
S4   Maven module-boundary hardening                  VALIDÉ
S5   supply-chain + release hardening                 VALIDÉ
S6   IntelliJ parity M19/M20                          VALIDÉ
S7   advanced provider productionization              VALIDÉ
S8   semantic scale qualification                     VALIDÉ
S9   final production integrity gate                  VALIDÉ exact-head
```

```text
M21 qualified tree : 60c1aba43e2d005991152cc4f3fe0b0dadef1c2d
develop merge      : 4222706502c54e10f0bf0400a18360fb99e6208c
M21 S8 STANDARD MEASUREMENT status=PASS decision=KEEP_CURRENT_M20_BACKEND
```

Scopes conservés : `semantic-learned-provider`, provider avancé, persistance, API/MCP, remote, runtime et hosted.

## M22 — Advanced Provider Intelligence

**TERMINÉ, VALIDÉ exact-head, MERGÉ dans `develop`.**

```text
Issue          : #76 CLOSED / completed
PR             : #77 MERGED
Qualified HEAD : 75d6169be6d46d4e60ca19e781ff61704ca1613c
Merge develop  : 37a3c904fd92c25b343344a26991531c75ebc4b6
```

Contrat : `minos-java-source-v1`, CFG, def-use local, argument/return flow borné, sécurité explicite, provenance et confiance sans confusion entre capability moteur et fait provider.

## M23 — Semantic Retrieval 2.0

**TERMINÉ, VALIDÉ exact-head, MERGÉ dans `develop`.**

```text
Issue          : #78 CLOSED / completed
PR             : #79 MERGED
Qualified HEAD : 7a5fe2b96480a21e063b8ffa537009e5bdf99bc0
Merge develop  : ffe12d95ac46c25026661dca51949fb0d39626b4
Decision       : KEEP_CURRENT_M20_BACKEND
```

Le provider learned local reste opt-in et loopback-only. L’ANN demeure measurement-gated.

## M24 — Polyglot Expansion

**TERMINÉ, VALIDÉ exact-head Windows + Linux, MERGÉ dans `develop`.**

```text
Base           : 8dbe34cb9e524acb62becda4faa263d74b90b9a9
Issue          : #81 CLOSED / completed
PR             : #82 MERGED
Qualified HEAD : 927f57768a79af162e2cdc765d0f54d274cbe02e
Merge develop  : 2a499a7aedd71b7cf4c5fb8339c5b914e3dd46fa
```

| Provider | Version / preuve | Disposition | Plateformes |
|---|---|---|---|
| scip-clang | 0.4.0 | QUALIFIED_WITH_CONSTRAINTS | Linux x86_64 |
| scip-dotnet | 0.2.14 | QUALIFIED_WITH_CONSTRAINTS | Linux x86_64 |
| scip-go | 0.2.7 | QUALIFIED_WITH_CONSTRAINTS | Windows x86_64, Linux x86_64 |
| rust-analyzer | scip 2026-07-27 / v0.3.2989 / commit 12c3381 | QUALIFIED_WITH_CONSTRAINTS | Windows x86_64, Linux x86_64 |

## M25 — Remote & Distributed Indexing

**TERMINÉ, VALIDÉ exact-head Windows + Linux, MERGÉ dans `develop`.**

```text
Issue          : #84 CLOSED / completed
PR             : #85 MERGED
Qualified tree : b17631de59871848351a4139b12be6e0354989bc
Qualified HEAD : fc395d189cf7fc5a0e06130210a3dc763fc48637
Merge develop  : 1a82f18115184606cbc13a9070b7cc78643ebb35
Decision       : ADR-0033
```

Le worker natif est qualifié avec contraintes pour workspace/process et `ALLOW`. M28 formalise que `DENY` reste fail-closed et qu’aucun claim sandbox pour code non fiable n’est permis.

## M26 — Runtime & Dynamic Intelligence

**TERMINÉ, VALIDÉ exact-head Windows + Linux, MERGÉ dans `develop`.**

```text
Issue          : #87 CLOSED / completed
PR             : #88 MERGED
Qualified HEAD : bf702990125a485646b9b31817c7787086a1dbb3
Merge develop  : 9b6395ce9bcf6a7fe942d1f6c687a8ba97cbceef
```

Les observations restent `PARTIAL` / `OBSERVED_PARTIAL`; l’absence d’observation n’est jamais une preuve d’absence runtime.

## M27 — Team / Hosted Mode

**TERMINÉ, VALIDÉ exact-head Windows + Linux, MERGÉ dans `develop`.**

```text
Issue          : #90 CLOSED / completed
PR             : #91 MERGED
Qualified HEAD : d4bd51ef52cb329ab75b70b32bc22e2b236bd65d
Merge develop  : ee22c3b39b9cd891c18cb61188eb8e973fc7e822
```

Le contrôle tenant embarqué reste opt-in, local-first, RBAC fail-closed, chiffré AES-256-GCM et audité par HMAC chaîné. Il ne vaut pas SaaS opéré.

## M28 — Production Convergence & Architectural Hardening

**S1→S8 IMPLÉMENTÉS ET MERGÉS DANS `develop` — S9 PARTIEL / PENDING — qualification locale exact-head en cours.**

```text
Issue          : #93 OPEN
PR             : #96 MERGED
Branch         : pre-m28-audit-remediation
Base develop   : cfbb495fbca8ddaf2b4bd529985e702e02106505
Merge develop  : 53d6faa41579d3d01e7900c5c4b65fdcc42c5868
Qualified HEAD : PENDING — qualification locale Windows/Linux en cours
Promotion main : DIFFÉRÉE — M21-S2/CI explicitement hors périmètre de cette session
```

État :

- S1/S2 : wiring M22 réel et tests verticaux application/API/CLI-IDE/MCP — IMPLÉMENTÉS, MERGÉS ;
- S3 : catalogue Product Facts des sept providers et cohérence sémantique — IMPLÉMENTÉ, MERGÉ ;
- S4 : graphe de dépendances Maven et fitness functions — IMPLÉMENTÉ, MERGÉ ;
- S5 : provider Java décomposé et profil performance ProgramGraph — IMPLÉMENTÉ, MERGÉ ;
- S6 : disposition remote explicite — `DENY` non OS-enforced, Windows/Linux `BLOCKED`, code non fiable non supporté — IMPLÉMENTÉ, MERGÉ ;
- S7 : façade hosted décomposée, ports opérateur et frontière no-SaaS — IMPLÉMENTÉ, MERGÉ ;
- S8 : gates structurels, négatifs, JaCoCo et runners exact-head — IMPLÉMENTÉS, MERGÉS ;
- S9 : PARTIEL / PENDING — M21-S2, CI, branch protection, promotion main explicitement différés (CI DEFERRED).

Le gel CI de juillet 2026 a pris fin le **1er août 2026**. La présente session reste néanmoins volontairement limitée à l’intégration et à la qualification locale de `develop` : M21-S2, GitHub Actions, required checks, branch protection et promotion vers `main` restent explicitement différés.

Les commandes autoritatives sont dans [`roadmap/M28_EXECUTION.md`](roadmap/M28_EXECUTION.md).

## Règle de promotion

- aucun workflow GitHub Actions exécuté ou modifié dans cette session ;
- aucun claim sandbox OS ou SaaS sans preuve dédiée ;
- aucune promotion vers `main` avant fermeture réelle de M21-S2 ;
- tout nouveau commit invalide les preuves exact-head antérieures.
