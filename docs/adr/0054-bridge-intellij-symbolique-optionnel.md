# 0054 — Étudier un bridge IntelliJ de lecture symbolique

Status: **Proposed** (2026-10-04). Aucune implémentation ni qualification acquise.
Programme : [étude MINOS](../research/minos-evolution-2026-10/README.md).
Complète les ADR 0027,0042 ; ne les remplace pas à ce stade.

## Contexte

Le plugin MINOS est un client externe et utilise déjà PSI pour le contexte éditeur. Serena montre l'intérêt possible de l'IDE comme backend, mais les garanties et coûts sont différents.

## Décision proposée

Autoriser un spike avant toute implémentation produit. Si concluant, créer un bridge optionnel versionné pour symboles/usages/implémentations, avec provenance IDE, version du document, identité projet et limites. Transport local authentifié, opérations allowlistées et bornées. Garder les observations IDE séparées des snapshots. Aucun refactoring/debug/shell.

## Alternatives considérées

A : faire de l'IDE le moteur obligatoire — rejetée. B : importer toutes ses relations comme faits snapshot — rejetée sans corrélation exacte. C : bridge opt-in séparé — proposée. D : utiliser Serena à côté sans intégrer — alternative à mesurer.

## Conséquences et limites

Étend le rôle du plugin au-delà de l'ADR-0027 ; son statut ne peut changer sans décision explicite. Ne pas dépendre des classes internes com.minos dans le plugin. PSI APIs publiques et compatibilité CE/langage à vérifier. Gestion dirty/dumb mode et fermeture essentielle.

## Validation et tâches

U7-01 à U7-05 ; G6/G7, Plugin Verifier et threat model. Voir [protocole](../research/minos-evolution-2026-10/EVALUATION.md) et [roadmap](../research/minos-evolution-2026-10/ROADMAP.md). Les mesures propres aux candidats appartiennent aux rapports d'évaluation, pas à cet ADR.

## Acceptation, déploiement et retour arrière

Go/no-go après U7-01 ; acceptation de l'extension ADR-0027 avant U7-03. Désactivation du bridge doit restaurer le socle sans IDE.

## Provenance

Analyse du code MINOS et des approches Semble/Serena décrites dans [SOURCES.md](../research/minos-evolution-2026-10/SOURCES.md). Aucune réutilisation de code tiers décidée.
