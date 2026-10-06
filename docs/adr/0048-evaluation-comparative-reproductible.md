# 0048 — Évaluer les changements sur un protocole commun

Status: **Proposed** (2026-10-04). Aucune implémentation ni qualification acquise.
Programme : [étude MINOS](../research/minos-evolution-2026-10/README.md).
Complète les ADR 0025,0031 ; ne les remplace pas à ce stade.

## Contexte

Les gains publiés par un concurrent ne prouvent ni la qualité sur les projets MINOS ni une économie de crédits. Les bancs snapshot existants ne couvrent pas toutes les tâches agents.

## Décision proposée

Geler corpus, partitions, versions et machine avant réglage ; comparer des capacités communes ; mesurer qualité, ressources, tokens sérialisés et réussite des tâches. Distinguer FAILED, NOT_RUN et NOT_SUPPORTED. Publier les ablations et les résultats négatifs.

## Alternatives considérées

A : adopter les chiffres amont — rejetée, protocole différent. B : mesurer uniquement latence — rejetée, permet une réponse rapide mais fausse. C : banc commun contrôlé — proposée.

## Conséquences et limites

Coût d'annotation et d'exécution ; budget LLM à prévoir. Données privées locales uniquement. Aucune collecte distante automatique.

## Validation et tâches

U0-01 à U0-04 ; G0 puis G1–G7 dans EVALUATION. Voir [protocole](../research/minos-evolution-2026-10/EVALUATION.md) et [roadmap](../research/minos-evolution-2026-10/ROADMAP.md). Les mesures propres aux candidats appartiennent aux rapports d'évaluation, pas à cet ADR.

## Acceptation, déploiement et retour arrière

Valider corpus, budget et seuils avec le propriétaire avant le premier changement de ranking. En cas de mesure inconclusive, conserver le profil courant.

## Provenance

Analyse du code MINOS et des approches Semble/Serena décrites dans [SOURCES.md](../research/minos-evolution-2026-10/SOURCES.md). Aucune réutilisation de code tiers décidée.
