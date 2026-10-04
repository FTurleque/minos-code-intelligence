# 0052 — Actualiser les index hors du chemin MCP en lecture seule

Status: **Proposed** (2026-10-04). Aucune implémentation ni qualification acquise.
Programme : [étude MINOS](../research/minos-evolution-2026-10/README.md).
Complète les ADR 0014,0017,0039,0041 ; ne les remplace pas à ce stade.

## Contexte

Semble actualise son cache à la demande ; reproduire cela directement violerait l'ADR-0017 de MINOS. Une actualisation peut aussi exécuter un build via un provider.

## Décision proposée

Maintenir lecture MCP sans effet de bord ; ajouter une coordination locale opt-in hors MCP réutilisant lifecycle, leases et reprise existants. Diagnostiquer fraîcheur par génération/empreinte ; réconcilier overflow/branches ; publication atomique. Incrémental seulement sur capability qualifiée ; sinon reconstruction complète annoncée.

## Alternatives considérées

A : indexer depuis search MCP — rejetée. B : freshness invisible — rejetée. C : coordinateur indépendant + état observable — proposée.

## Conséquences et limites

Nouveau lifecycle à superviser ; arrêt, crash et quotas ; aucun assouplissement du confinement ou remote index. Détection rapide ne garantit pas indexation rapide. Les buffers IDE sont une source distincte.

## Validation et tâches

U5-01 à U5-04 ; G4/G5. Voir [protocole](../research/minos-evolution-2026-10/EVALUATION.md) et [roadmap](../research/minos-evolution-2026-10/ROADMAP.md). Les mesures propres aux candidats appartiennent aux rapports d'évaluation, pas à cet ADR.

## Acceptation, déploiement et retour arrière

Valider concurrence et crash avant activation ; mode watch désactivé par défaut ; arrêt du coordinateur et synchronisation manuelle comme rollback.

## Provenance

Analyse du code MINOS et des approches Semble/Serena décrites dans [SOURCES.md](../research/minos-evolution-2026-10/SOURCES.md). Aucune réutilisation de code tiers décidée.
