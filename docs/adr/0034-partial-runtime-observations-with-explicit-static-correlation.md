# ADR-0034 — Partial runtime observations with explicit static correlation

Status: **Accepted for the M26 implementation; final exact-head qualification disposition pending.**

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

## Consequences

- Les observations d’un ancien snapshot restent auditables mais ne sont pas fusionnées avec le snapshot actif.
- Un même identifiant de session ne peut pas être réutilisé avec un contenu différent.
- Les ratios et hot paths sont utiles pour les sessions sélectionnées, jamais universels.
- Le collecteur reste externe/opéré séparément ; MINOS M26 importe un contrat et ne déploie pas d’agent universel.
- Un futur collector ou backend de stockage peut implémenter les mêmes contrats sans modifier la découverte M17 ni l’autorité des snapshots.

## Rejected alternatives

- Marquer une trace comme complète : preuve insuffisante et non portable entre collectors.
- Ajouter des relations d’appel runtime dans le snapshot statique : mélange d’autorités et de temporalités.
- Résoudre une ambiguïté par le premier symbole : résultat non fiable et dépendant de l’ordre.
- Exposer l’import via MCP : mutation implicite depuis une surface conçue read-only.
- Évincer silencieusement des sessions : perte de preuve sans décision opérateur.

## Qualification disposition

La disposition finale doit être enregistrée après les gates locales exact-head Windows x86_64 et Linux x86_64 sur le même commit, avec preuves JSON `status: PASS`. Jusqu’alors le format, le store, les corrélations et les surfaces sont implémentés et testés, mais ne sont pas déclarés finalisés.

Voir [`../roadmap/M26_EXECUTION.md`](../roadmap/M26_EXECUTION.md).
