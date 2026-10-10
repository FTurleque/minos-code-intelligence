---
paths:
  - "openspec/**"
---

# OpenSpec

`openspec/config.yaml` fait contrat (schéma `spec-driven`, artefacts **en français**). Outil : `openspec` 1.14.x ; commandes `/opsx:*` disponibles.

## Rédiger un changement
- **proposal.md** : Why, What Changes, Capabilities (nouvelles / modifiées, avec la raison si nouvelle), **Hors périmètre** (toujours), Impact (modules du reactor, surfaces publiques CLI/API/MCP/IntelliJ/NEXUS, ADR créé ou amendé, gates à rejouer, plateformes).
- **design.md** : « État vérifié au HEAD » avec `fichier:ligne` relus, **écarts avec l'action proposée par l'audit** ; direction des dépendances (ADR 0022) ; chaque capacité qualifiée (qualifiée / partielle / non supportée) ; Windows **et** Linux ; décisions en attente du propriétaire, avec une recommandation.
- **specs** : `## ADDED/MODIFIED Requirements`, chaque exigence **≤ 500 caractères** (limite de `validate --strict`) avec au moins un `#### Scenario:` GIVEN/WHEN/THEN **vérifiable par une commande locale**, y compris le cas de refus fail-closed.
- **tasks.md** : par section, d'abord le rouge, puis la correction, puis la preuve ; tests dans la même tâche que le code ; chaque tâche porte l'identifiant du constat (`AUD-…`) et nomme les `check-*.py` et scopes JaCoCo à rejouer ; tâches manuelles marquées **(manuelle)** ; section finale « Clôture » (mise à jour du suivi, `openspec validate --all --strict`, liste des `docs/` à mettre à jour avant archivage).

## Règles
- Ne jamais éditer `openspec/specs/` à la main : un changement se fusionne par `openspec archive`, qui écrit un « Purpose » provisoire à réécrire pour une capacité nouvelle.
- Un ADR n'est ni créé ni amendé pendant la **spécification** : il est nommé dans le design et planifié en tâche.
- Les changements archivés (`openspec/changes/archive/`) sont de l'historique.
- Valider avant de commiter : `openspec validate --all --strict`.
- Skills disponibles : `openspec-explore`, `openspec-propose`, `openspec-apply-change`, `openspec-update-change`, `openspec-sync-specs`, `openspec-archive-change`. Préparer un sprint d'audit : commande `/minos:sprint`.
