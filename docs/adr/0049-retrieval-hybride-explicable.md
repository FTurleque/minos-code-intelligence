# 0049 — Faire évoluer le classement hybride par profils mesurés

Status: **Proposed** (2026-10-04). Aucune implémentation ni qualification acquise.
Programme : [étude MINOS](../research/minos-evolution-2026-10/README.md).
Complète les ADR 0029,0031 ; ne les remplace pas à ce stade.

## Contexte

HybridSearchService utilise recouvrement de termes, bonus phrase et coefficients fixes. Les échelles lexicales, graphe et vectorielles ne sont pas naturellement comparables.

## Décision proposée

Conserver le profil legacy ; proposer BM25 avec segmentation d'identifiants, fusion RRF et routage déterministe borné. Traiter le graphe comme signal dérivé. Versionner profil, tokenizer et corpus. Préserver scores v1/minimumScore ; si leur sens change, exposer un contrat explicite plutôt que recycler les valeurs.

## Alternatives considérées

A : pondérations fixes retouchées sans banc — rejetée. B : remplacement global par moteur tiers — non retenue sans besoin prouvé. C : stratégies internes comparables — proposée.

## Conséquences et limites

Complexité de configuration et caches lexicaux ; top-k borné et poids mémoire comptabilisé. Aucun score de ranking ne devient une relation de programme. Les tests/legacy ne sont pas systématiquement déclassés.

## Validation et tâches

U1-01 à U1-05 ; G1/G3, ablations. Voir [protocole](../research/minos-evolution-2026-10/EVALUATION.md) et [roadmap](../research/minos-evolution-2026-10/ROADMAP.md). Les mesures propres aux candidats appartiennent aux rapports d'évaluation, pas à cet ADR.

## Acceptation, déploiement et retour arrière

Promouvoir uniquement après holdout concluant ; rollback par profil legacy et reconstruction des caches dérivés.

## Provenance

Analyse du code MINOS et des approches Semble/Serena décrites dans [SOURCES.md](../research/minos-evolution-2026-10/SOURCES.md). Aucune réutilisation de code tiers décidée.
