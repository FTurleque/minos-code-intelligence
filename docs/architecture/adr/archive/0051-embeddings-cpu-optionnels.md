---
id: ADR-0051
title: "Évaluer un provider d'embeddings CPU léger et local"
domain: backend
rules: false
---

# 0051 — Évaluer un provider d'embeddings CPU léger et local

> **Archivé le 2026-10-23** (tri du registre, [ADR 0063](0063-ecrire-les-adr-au-format-archgate-dans-le-registre-arc42.md)) : Rejected (proposition sans mise en œuvre ni changement OpenSpec) ; l'étude reste dans [`docs/project/research/minos-evolution-2026-10/`](../../../project/research/minos-evolution-2026-10/README.md). Ce document est conservé pour l'historique ; il n'est plus lu par Archgate.

Status: **Proposed** (2026-10-04). Aucune implémentation ni qualification acquise.
Programme : [étude MINOS](../../../project/research/minos-evolution-2026-10/README.md).
Complète les ADR 0031, 0042 ; ne les remplace pas à ce stade.

## Context

EmbeddingProvider, Ollama et local-hash existent. Un modèle statique peut réduire le coût, mais qualité FR/code, runtime et distribution sont inconnus sur MINOS.

## Decision

Spiker implémentation native Java et sidecar isolé ; décider selon parité numérique, qualité, RSS total, licence et packaging. Aucun choix de modèle ni runtime n'est arrêté ici. Intégrer uniquement via SPI/configuration, sous option ; provisioning explicite et digest modèle. Si port externe, respecter l'ADR-0042 en plaçant les contrats neutres hors application au besoin.

## Do's and Don'ts

### Do

- Tant que l'ADR est Proposed, ces points ne sont pas normatifs.
- Intégrer un provider uniquement via SPI/configuration, sous option, avec provisioning explicite et digest du modèle.
- Décider selon parité numérique, qualité, RSS total, licence et packaging.
- Si un port externe est nécessaire, respecter l'ADR 0042 en plaçant les contrats neutres hors application.

### Don't

- Ne pas arrêter de choix de modèle ni de runtime par cet ADR.
- Ne pas embarquer Python automatiquement sans qualification.
- Aucun téléchargement par MCP, aucun ANN ajouté.

## Alternatives considérées

A : Ollama seul — baseline conservée. B : embarquer Python automatiquement — rejetée sans qualification. C : provider léger qualifié — proposée conditionnellement. D : no-go — résultat valide.

## Consequences

Dépendances et supply-chain supplémentaires possibles ; réseau désactivé en utilisation ; dimensions/digest inclus dans identité ; pas de téléchargement MCP. Aucun ANN ajouté.

## Compliance and Enforcement

- Aucun contrôle automatique : vérification en revue de code.
- Revue : un provider d'embeddings ajouté passe par la SPI, reste sous option et déclare le digest de son modèle.
- À réévaluer : aucune mise en œuvre au 2026-10-23.

## Validation et tâches

U4-01 à U4-04 ; G1/G3 ; licence modèle/tokenizer et dépendances inventoriées. Voir [protocole](../../../project/research/minos-evolution-2026-10/EVALUATION.md) et [roadmap](../../../project/research/minos-evolution-2026-10/ROADMAP.md). Les mesures propres aux candidats appartiennent aux rapports d'évaluation, pas à cet ADR.

## Acceptation, déploiement et retour arrière

Décider le mode de réalisation après U4-01 ; promouvoir après U4-04. Revenir à disabled/Ollama et reconstruire vecteurs sans toucher aux snapshots.

## Provenance

Analyse du code MINOS et des approches Semble/Serena décrites dans [SOURCES.md](../../../project/research/minos-evolution-2026-10/SOURCES.md). Aucune réutilisation de code tiers décidée.
