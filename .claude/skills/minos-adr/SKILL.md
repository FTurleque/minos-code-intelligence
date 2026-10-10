---
name: minos-adr
description: Rédiger, amender ou remplacer un ADR MINOS (docs/adr) — numérotation, gabarit Contexte/Décision/Conséquences, statuts, mise à jour de l'index, références croisées et vérification des gates documentaires. À utiliser quand une décision d'architecture structurante est prise ou proposée, quand un changement OpenSpec « exige un ADR », ou quand l'utilisateur demande d'écrire ou de modifier un ADR.
---

# ADR MINOS

Un ADR documente un choix **structurant et durable** ; les mesures et preuves propres à une livraison vont dans un suivi (`docs/audit/…`, `docs/history/`), pas dans l'ADR.

## Quand en écrire un
Frontière de modules, modèle de confiance, format persistant, protocole public, politique de sécurité, toolchain. Pas pour un correctif local. Pendant la **spécification** OpenSpec, un ADR est seulement nommé et planifié ; il s'écrit à l'implémentation.

## Étapes
1. Lire `docs/adr/README.md` : dernier numéro (l'index fait foi), statuts (`Proposed | Accepted | Superseded | Rejected`), ADR voisins à citer.
2. Fichier `docs/adr/NNNN-titre-en-kebab-case.md` (numéro à 4 chiffres suivant). Gabarit, en français :
   ```markdown
   # NNNN — Titre qui énonce la décision

   Status: Proposed (AAAA-MM-JJ)

   Complète/Amende/Remplace [00XX](00XX-….md).

   ## Contexte
   Faits vérifiés (fichier:ligne), contraintes, options considérées.

   ## Décision
   Ce qui est décidé, conditions prouvables (test, gate), et ce qui est exclu.

   ## Conséquences
   Positives, négatives, risques, migration, plateformes (Windows et Linux).

   ## Mise en œuvre
   (ajouté à l'implémentation : écarts à la conception, preuves.)
   ```
3. Ajouter la ligne à l'index (`| [NNNN](…) | Décision | Statut | Origine |`).
4. Passage à `Accepted` : décision de l'**utilisateur**, avec date. Une décision Accepted ne se réécrit pas : compléter « Mise en œuvre », ou écrire un ADR qui la remplace et passer l'ancien à `Superseded` (ou « Partially superseded by ADR-NNNN »).
5. Si l'ADR change une règle gardée (frontières, E/S, supply-chain), mettre à jour le gate et sa documentation dans `docs/developer/quality-gates.md` dans le même changement.
6. Vérifier : `python scripts/docs/check-current-docs.py`, `python scripts/docs/product-facts.py --check`, liens relatifs valides. Le hook demande confirmation avant de modifier un ADR existant ; un nouvel ADR se crée librement.

## Références utiles
ADR 0017 (MCP lecture seule), 0022 (reactor et frontières), 0040 (indexeurs embarqués), 0041 (indexation distante non fiable), 0042 (racine de composition), 0043 (retrait des artefacts de jalon), 0044 (un package, un module), 0057 et 0058 (frontières hexagonales, usages de modules).
