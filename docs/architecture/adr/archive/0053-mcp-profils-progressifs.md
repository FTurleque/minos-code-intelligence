---
id: ADR-0053
title: "Réduire le coût de découverte MCP avec des profils explicites"
domain: architecture
rules: false
---

# 0053 — Réduire le coût de découverte MCP avec des profils explicites

> **Archivé le 2026-10-23** (tri du registre, [ADR 0063](0063-ecrire-les-adr-au-format-archgate-dans-le-registre-arc42.md)) : Rejected (proposition sans mise en œuvre ni changement OpenSpec) ; l'étude reste dans [`docs/project/research/minos-evolution-2026-10/`](../../../project/research/minos-evolution-2026-10/README.md). Ce document est conservé pour l'historique ; il n'est plus lu par Archgate.

Status: **Proposed** (2026-10-04). Aucune implémentation ni qualification acquise.
Programme : [étude MINOS](../../../project/research/minos-evolution-2026-10/README.md).
Complète les ADR 0017, 0018 ; ne les remplace pas à ce stade.

## Context

Un catalogue étendu peut ajouter du contexte ou rendre le choix d'outil difficile ; le coût exact dépend du client et doit être mesuré.

## Decision

Définir des profils au démarrage avec handlers partagés ; conserver le catalogue legacy par défaut. Réponses compactes puis détails sous références de génération. Mesurer avec Claude. Aucun REPL arbitraire, exécution de code fourni par l'agent ni mutation implicite.

## Do's and Don'ts

### Do

- Tant que l'ADR est Proposed, ces points ne sont pas normatifs.
- Définir des profils au démarrage avec handlers partagés ; conserver le catalogue legacy par défaut.
- Répondre de façon compacte, puis détailler sous références de génération.
- Faire échouer explicitement toute référence périmée.

### Don't

- Aucun REPL arbitraire, aucune exécution de code fourni par l'agent, aucune mutation implicite.
- Ne pas supposer tools/list_changed universel.

## Alternatives considérées

A : exposer tous les détails toujours — baseline. B : reproduire un REPL général — hors périmètre et surface d'exécution excessive. C : profils et lectures progressives — proposée.

## Consequences

Plusieurs profils à qualifier ; ne pas supposer tools/list_changed universel. Des descriptions plus courtes ne garantissent pas moins de tokens sur tous les clients. Toute référence périmée échoue explicitement.

## Compliance and Enforcement

- Aucun contrôle automatique : vérification en revue de code.
- Revue : un profil MCP garde le catalogue legacy par défaut et n'ajoute aucun outil d'exécution ni de mutation.
- À réévaluer : aucune mise en œuvre au 2026-10-23.

## Validation et tâches

U6-01 à U6-03 ; G5 et tâches agents. Voir [protocole](../../../project/research/minos-evolution-2026-10/EVALUATION.md) et [roadmap](../../../project/research/minos-evolution-2026-10/ROADMAP.md). Les mesures propres aux candidats appartiennent aux rapports d'évaluation, pas à cet ADR.

## Acceptation, déploiement et retour arrière

Promouvoir seulement sur gain de tâche/efficacité mesuré ; profil complet v1 reste disponible sans migration de données.

## Provenance

Analyse du code MINOS et des approches Semble/Serena décrites dans [SOURCES.md](../../../project/research/minos-evolution-2026-10/SOURCES.md). Aucune réutilisation de code tiers décidée.
