# 0051 — Évaluer un provider d'embeddings CPU léger et local

Status: **Proposed** (2026-10-04). Aucune implémentation ni qualification acquise.
Programme : [étude MINOS](../research/minos-evolution-2026-10/README.md).
Complète les ADR 0031,0042 ; ne les remplace pas à ce stade.

## Contexte

EmbeddingProvider, Ollama et local-hash existent. Un modèle statique peut réduire le coût, mais qualité FR/code, runtime et distribution sont inconnus sur MINOS.

## Décision proposée

Spiker implémentation native Java et sidecar isolé ; décider selon parité numérique, qualité, RSS total, licence et packaging. Aucun choix de modèle ni runtime n'est arrêté ici. Intégrer uniquement via SPI/configuration, sous option ; provisioning explicite et digest modèle. Si port externe, respecter l'ADR-0042 en plaçant les contrats neutres hors application au besoin.

## Alternatives considérées

A : Ollama seul — baseline conservée. B : embarquer Python automatiquement — rejetée sans qualification. C : provider léger qualifié — proposée conditionnellement. D : no-go — résultat valide.

## Conséquences et limites

Dépendances et supply-chain supplémentaires possibles ; réseau désactivé en utilisation ; dimensions/digest inclus dans identité ; pas de téléchargement MCP. Aucun ANN ajouté.

## Validation et tâches

U4-01 à U4-04 ; G1/G3 ; licence modèle/tokenizer et dépendances inventoriées. Voir [protocole](../research/minos-evolution-2026-10/EVALUATION.md) et [roadmap](../research/minos-evolution-2026-10/ROADMAP.md). Les mesures propres aux candidats appartiennent aux rapports d'évaluation, pas à cet ADR.

## Acceptation, déploiement et retour arrière

Décider le mode de réalisation après U4-01 ; promouvoir après U4-04. Revenir à disabled/Ollama et reconstruire vecteurs sans toucher aux snapshots.

## Provenance

Analyse du code MINOS et des approches Semble/Serena décrites dans [SOURCES.md](../research/minos-evolution-2026-10/SOURCES.md). Aucune réutilisation de code tiers décidée.
