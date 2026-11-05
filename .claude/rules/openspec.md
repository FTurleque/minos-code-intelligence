---
paths:
  - "openspec/**"
---

# OpenSpec

`openspec/config.yaml` fait contrat (schéma projet `detailed-uml`, artefacts **en français**). Outil : `openspec` 1.14.x ; commandes `/opsx:*` disponibles. Guide complet : `docs/developer/openspec-detailed-uml.md`. Les consignes détaillées vivent dans `openspec/schemas/detailed-uml/` (schéma et modèles) ; cette règle n'en garde que l'essentiel.

## Rédiger un changement
- **proposal.md** : titres techniques anglais conservés (`## Why`, `## What Changes`, `## Capabilities`, `## Impact`), contenu en français ; **Hors périmètre** (toujours) ; statut des informations ; Impact (modules du reactor, surfaces publiques CLI/API/MCP/IntelliJ/NEXUS, ADR créé ou amendé, gates à rejouer, plateformes, documentation durable).
- **design.md** : toujours produit ; réduit à Contexte, Décisions, Vérification et Traçabilité pour un changement documentaire ou d'outillage. Sinon : « État vérifié au HEAD » avec `fichier:ligne` relus, **écarts** (code, tests, specs, audit) ; direction des dépendances (ARCH-0068) ; chaque capacité qualifiée (qualifiée / partielle / non supportée) ; Windows **et** Linux ; diagrammes Mermaid qui répondent à une question ; matrice de traçabilité ; décisions en attente du propriétaire, avec une recommandation.
- **specs** : `## ADDED/MODIFIED Requirements`, chaque exigence **≤ 500 caractères** (limite de `validate --strict`) avec au moins un `#### Scenario:` GIVEN/WHEN/THEN(/AND) **vérifiable par une commande locale**, y compris le cas de refus fail-closed. Aucune autre section dans un delta : seuls les blocs d'exigences sont fusionnés.
- **tasks.md** : par section, d'abord le rouge, puis la correction, puis la preuve ; tests et documentation (dossier de capacité, pages `docs/`) dans le groupe qui les exige ; chaque tâche porte l'identifiant du constat (`AUD-…`) et nomme les `check-*.py` et scopes JaCoCo à rejouer ; tâches manuelles marquées **(manuelle)** ; section finale « Clôture » (matrice de traçabilité, `openspec validate --all --strict`, `check-capability-dossiers.py`, liste des `docs/` à mettre à jour avant archivage).
- Étiquettes de statut : `[Exigence]`, `[Existant vérifié]`, `[Décision]`, `[Proposition]`, `[Question]`. Le code prouve l'implémentation, pas l'intention : une divergence est un écart documenté, jamais une exigence réécrite pour coller au code.

## Règles
- Les exigences de `openspec/specs/` ne s'éditent jamais à la main : un changement se fusionne par `openspec archive`, qui écrit un « Purpose » provisoire à réécrire pour une capacité nouvelle. Seules exceptions : `## Purpose` et la section finale non normative `## Documentation liée` (liens vers le dossier durable, sans titre `###`), que l'archivage conserve.
- Dossier durable d'une capacité : `docs/architecture/diagrams/capacites/<capacité>.md` (vues, traçabilité, écarts), contrôlé par `python scripts/docs/check-capability-dossiers.py`. Toute exigence ajoutée par archivage à une capacité qui a un dossier doit y entrer, sinon le gate échoue.
- Un changement garde le schéma de son `.openspec.yaml` ; les changements créés sous `spec-driven` y restent épinglés.
- Un ADR n'est ni créé ni amendé pendant la **spécification** : il est nommé dans le design et planifié en tâche.
- Les changements archivés (`openspec/changes/archive/`) sont de l'historique.
- Valider avant de commiter : `openspec validate --all --strict` et `python scripts/docs/check-capability-dossiers.py`.
- Skills disponibles : `openspec-explore`, `openspec-propose`, `openspec-new-change`, `openspec-continue-change`, `openspec-ff-change`, `openspec-apply-change`, `openspec-update-change`, `openspec-verify-change`, `openspec-sync-specs`, `openspec-archive-change`, `openspec-bulk-archive-change`, `openspec-onboard`. Vérifier un changement implémenté avant archivage : `openspec-verify-change` (`/opsx:verify`). Préparer un sprint d'audit : commande `/minos:sprint`.
