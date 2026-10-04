# 0050 — Assembler un contexte cohérent, compact et traçable

Status: **Proposed** (2026-10-04). Aucune implémentation ni qualification acquise.
Programme : [étude MINOS](../research/minos-evolution-2026-10/README.md).
Complète les ADR 0011,0029 ; ne les remplace pas à ce stade.

## Contexte

SemanticDocumentFactory produit SYMBOL/FILE/CHUNK ; HybridContextBuilder sélectionne sous budget de contenu. Les recouvrements et l'enveloppe sérialisée doivent être mesurés.

## Décision proposée

Découper d'abord avec les frontières symboliques disponibles ; isoler documents/config des faits SCIP ; dédupliquer par plage et génération ; conserver preuves et source. Définir le budget complet rendu avec compteurs estimés explicitement nommés et tokenizer de référence pour le banc. Résumé puis détail par référence versionnée.

## Alternatives considérées

A : fichiers entiers — coûteux. B : Tree-sitter imposé à tous les langages — double parsing à justifier. C : symboles existants puis fallback syntaxique/textuel explicitement limité — proposée.

## Conséquences et limites

Plus de métadonnées et règles de déduplication. Aucun chunk documentaire ne devient un symbole. Changer le découpage invalide le cache mais ne migre pas le snapshot. Les imports/config utiles restent accessibles.

## Validation et tâches

U2 et U3 ; G2/G3 et tests générations. Voir [protocole](../research/minos-evolution-2026-10/EVALUATION.md) et [roadmap](../research/minos-evolution-2026-10/ROADMAP.md). Les mesures propres aux candidats appartiennent aux rapports d'évaluation, pas à cet ADR.

## Acceptation, déploiement et retour arrière

Accepter après comparaison à classement fixe et absence de perte de preuves ; fallback ancien découpage et rendu v1 conservés.

## Provenance

Analyse du code MINOS et des approches Semble/Serena décrites dans [SOURCES.md](../research/minos-evolution-2026-10/SOURCES.md). Aucune réutilisation de code tiers décidée.
