# 0053 — Réduire le coût de découverte MCP avec des profils explicites

Status: **Proposed** (2026-10-04). Aucune implémentation ni qualification acquise.
Programme : [étude MINOS](../research/minos-evolution-2026-10/README.md).
Complète les ADR 0017,0018 ; ne les remplace pas à ce stade.

## Contexte

Un catalogue étendu peut ajouter du contexte ou rendre le choix d'outil difficile ; le coût exact dépend du client et doit être mesuré.

## Décision proposée

Définir des profils au démarrage avec handlers partagés ; conserver le catalogue legacy par défaut. Réponses compactes puis détails sous références de génération. Mesurer avec Claude. Aucun REPL arbitraire, exécution de code fourni par l'agent ni mutation implicite.

## Alternatives considérées

A : exposer tous les détails toujours — baseline. B : reproduire un REPL général — hors périmètre et surface d'exécution excessive. C : profils et lectures progressives — proposée.

## Conséquences et limites

Plusieurs profils à qualifier ; ne pas supposer tools/list_changed universel. Des descriptions plus courtes ne garantissent pas moins de tokens sur tous les clients. Toute référence périmée échoue explicitement.

## Validation et tâches

U6-01 à U6-03 ; G5 et tâches agents. Voir [protocole](../research/minos-evolution-2026-10/EVALUATION.md) et [roadmap](../research/minos-evolution-2026-10/ROADMAP.md). Les mesures propres aux candidats appartiennent aux rapports d'évaluation, pas à cet ADR.

## Acceptation, déploiement et retour arrière

Promouvoir seulement sur gain de tâche/efficacité mesuré ; profil complet v1 reste disponible sans migration de données.

## Provenance

Analyse du code MINOS et des approches Semble/Serena décrites dans [SOURCES.md](../research/minos-evolution-2026-10/SOURCES.md). Aucune réutilisation de code tiers décidée.
