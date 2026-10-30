# Proposal

## Why

Les six constats du sprint 10 de l'audit du 10 octobre 2026 (AUD-ARC-01, -05, -07, -08, -11, -12) sont confirmés au HEAD `949e1569`, mais **aucun n'est spécifiable tel que l'audit le formule** :

- trois dépendent de prérequis **non implémentés** : AUD-ARC-01 attend AUD-ARC-02 (sprint 9), AUD-ARC-05 attend AUD-SEC-06 (sprint 8), AUD-ARC-07 attend AUD-PERF-03 (sprint 4, spécifié) ;
- quatre actions proposées **contredisent un ADR accepté** (0044 pour `hosted`, `minos-app` et le nommage des packages ; 0022 pour une arête `minos-mcp → minos-cli`) ou supposent une décision qui n'est pas prise (ADR 0047, encore « Proposed ») ;
- les chantiers qui les résorbent (SH-01 à SH-12 de `docs/project/roadmap/storage-hexagonal-2026-10/TASKS.md`, ADR 0055 à 0057) sont tous « À faire », et chacun déplace des classes.

Spécifier ces chantiers maintenant reviendrait à écrire des tâches sur du code que les sprints 4, 8 et 9 vont modifier, et à trancher en passant des décisions qui reviennent au propriétaire. Le changement `figer-la-dette-de-frontieres-par-des-cliquets` empêche déjà la dette de croître. Celui-ci fait le reste de ce qui est possible aujourd'hui : **poser les décisions, les faire trancher, et les consigner**, pour que chaque chantier puisse être spécifié dès que son prérequis est livré.

## What Changes

Aucun code, aucun gate. Quatre décisions du propriétaire sont instruites (état vérifié, options, recommandation) puis consignées dans un ADR ou une note de mise en œuvre :

1. **ADR 0047** (AUD-ARC-07) : l'accepter, le reporter avec un critère, ou le rejeter.
2. **Emplacement du mécanisme d'ACL Windows et de `hosted`** (AUD-ARC-05) : maintenir l'ADR 0044 ou l'amender.
3. **`minos-app`** (AUD-ARC-11) : maintenir l'ADR 0044 (transport MCP Docker et pont NEXUS dans l'assemblage) ou déplacer le service de route MCP.
4. **Nommage des packages** (AUD-ARC-12) : convention pour les **nouveaux** packages seulement, ou renommage.

Et un **ordre de spécification** des chantiers restants, avec leurs prérequis, est écrit dans le suivi de l'audit.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

(aucune : ce changement ne modifie aucun comportement ; `skip_specs` est posé dans `.openspec.yaml`. Les exigences viendront avec les chantiers.)

## Hors périmètre

- Les chantiers eux-mêmes (cas d'usage d'indexation hors de la CLI, extraction du mécanisme d'ACL, port de lecture des snapshots, codec commun, staging injecté) : à spécifier après leurs prérequis.
- Les cliquets : changement `figer-la-dette-de-frontieres-par-des-cliquets`.
- AUD-ARC-02, AUD-SEC-06, AUD-PERF-03 : leurs propres changements.

## Impact

- **Modules du reactor** : aucun.
- **Surfaces publiques** : aucune.
- **ADR** : selon les décisions, l'ADR 0047 change de statut, et un ADR (prochain numéro libre de `docs/architecture/adr/README.md`) amende ou confirme l'ADR 0044 sur les points 2 à 4. Rédigés en tâche, pas pendant cette spécification.
- **Gates à rejouer** : `python scripts/docs/check-current-docs.py` (index des ADR), `python .claude/scripts/run_gates.py --fast`.
- **Plateformes** : sans objet.
- **Constats concernés** : AUD-ARC-01, -05, -07, -08, -11, -12 restent **ouverts** ; ce changement ne les ferme pas.
