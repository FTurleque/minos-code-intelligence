---
id: DATA-0034
title: "Partial runtime observations with explicit static correlation"
domain: data
rules: false
---

# Partial runtime observations with explicit static correlation

Status: **Accepted — final M26 dispositions recorded from exact-head Windows + Linux evidence.**

Date: 2026-07-29

## Context

Une trace est limitée par son collector, son instrumentation, sa fenêtre temporelle, son environnement et ses chemins effectivement exercés. La présenter comme une vérité exhaustive ferait de l’absence d’événement une fausse preuve de non-exécution. Injecter ces événements dans le snapshot statique brouillerait en outre l’identité, la provenance et les capabilities qualifiées des providers.

M26 doit rendre les observations runtime utiles pour la couverture observée, les hot paths et les appels, tout en conservant le snapshot structuré comme autorité statique.

## Decision

1. Le seul format d’entrée M26 est `minos-runtime-observation-v1`, UTF-8 TSV strict, versionné et borné.
2. Toute session déclare projet UUID, snapshot exact, fenêtre, collector/version, environnement et `completeness=PARTIAL`. Aucun claim `COMPLETE` n’est accepté.
3. Les observations `SYMBOL_EXECUTION`, `CALL` et `LINE_COVERAGE` portent des références provider-neutral, hits et durées bornés.
4. La corrélation consulte le snapshot actif sans le modifier : clé exacte, puis qualified name, puis fichier/ligne. Les états `RESOLVED`, `AMBIGUOUS` et `UNRESOLVED` restent visibles ; aucun choix arbitraire n’est effectué.
5. Les sessions acceptées sont immuables, alignées à un snapshot, vérifiées par SHA-256, publiées atomiquement et conservées dans un store local borné.
6. Les rapports déclarent `OBSERVED_PARTIAL` et `exhaustive=false`. L’absence signifie `NOT_OBSERVED_IN_SELECTED_PARTIAL_SESSIONS`.
7. La CLI porte l’import explicite. Les trois tools MCP M26 sont strictement read-only.
8. Les traces ne promeuvent jamais CFG, def-use, interprocedural data-flow, security ou toute autre capability statique.

## Do's and Don'ts

### Do

- N'accepter en entrée que `minos-runtime-observation-v1`, TSV UTF-8 strict, versionné et borné.
- Déclarer `completeness=PARTIAL` pour toute session, et `OBSERVED_PARTIAL` et `exhaustive=false` dans les rapports.
- Laisser visibles les états `RESOLVED`, `AMBIGUOUS` et `UNRESOLVED` de la corrélation.
- Rendre les sessions acceptées immuables, alignées sur un snapshot, vérifiées par SHA-256 et publiées atomiquement dans un store local borné.
- Porter l'import par la CLI et garder strictement read-only les trois tools MCP M26.

### Don't

- Ne pas accepter de claim `COMPLETE`.
- Ne pas modifier le snapshot actif lors de la corrélation.
- Ne pas résoudre une ambiguïté par un choix arbitraire.
- Ne pas exposer l'import par MCP.
- Ne pas promouvoir de capacité statique (CFG, def-use, data-flow interprocédural, sécurité) à partir de traces.

## Consequences

- Les observations d’un ancien snapshot restent auditables mais ne sont pas fusionnées avec le snapshot actif.
- Un même identifiant de session ne peut pas être réutilisé avec un contenu différent.
- Les ratios et hot paths sont utiles pour les sessions sélectionnées, jamais universels.
- Le collecteur reste externe/opéré séparément ; MINOS M26 importe un contrat et ne déploie pas d’agent universel.
- Un futur collector ou backend de stockage peut implémenter les mêmes contrats sans modifier la découverte M17 ni l’autorité des snapshots.

## Compliance and Enforcement

- Gate `scripts/quality/check-runtime-dynamic-consistency.py`, exécuté par `scripts/remediation/check-post-mne.py` (job `invariants`) — interdit à `RuntimeIntelligenceService` toute publication (`.publish(`) et tout usage de `ProviderCapability` ou de `ProjectDiscoveryService`, interdit `minos_runtime_import` dans `MinosMcpTools`, exige que cet ADR, la roadmap M26 et les guides citent `minos-runtime-observation-v1` et `OBSERVED_PARTIAL`, et que cet ADR cite `NOT_OBSERVED_IN_SELECTED_PARTIAL_SESSIONS`.
- Revue : un nouveau rapport runtime déclare son caractère partiel, et aucune donnée runtime n'entre dans le snapshot structuré.

## Rejected alternatives

- Marquer une trace comme complète : preuve insuffisante et non portable entre collectors.
- Ajouter des relations d’appel runtime dans le snapshot statique : mélange d’autorités et de temporalités.
- Résoudre une ambiguïté par le premier symbole : résultat non fiable et dépendant de l’ordre.
- Exposer l’import via MCP : mutation implicite depuis une surface conçue read-only.
- Évincer silencieusement des sessions : perte de preuve sans décision opérateur.

## Qualification disposition

Le format, la corrélation statique↔runtime, la couverture/hot paths observés, le store local, la CLI runtime et les trois tools MCP read-only sont `QUALIFIED_WITH_CONSTRAINTS`. Les limites de cette décision restent constitutives du produit : `PARTIAL` uniquement, collector externe, snapshot actif exact, ambiguïtés visibles, ratios non exhaustifs, aucune éviction implicite et aucun import MCP.

Windows x86_64 et Linux x86_64 ont validé le même HEAD `bf702990125a485646b9b31817c7787086a1dbb3` avec preuves JSON `status: PASS`. La PR #88 a été fusionnée dans `develop` par le commit `9b6395ce9bcf6a7fe942d1f6c687a8ba97cbceef` et l’issue #87 est `CLOSED / completed`.

Voir [`../../project/roadmap/M26_EXECUTION.md`](../../project/roadmap/M26_EXECUTION.md).
