---
description: Rédiger un ADR (numéro, gabarit, index, références) ou en compléter la mise en œuvre
argument-hint: "<titre de la décision | NNNN pour compléter un ADR existant>"
---

Rédige ou complète un ADR pour : `$ARGUMENTS`. Un ADR documente un choix **structurant et durable** (frontière de modules, modèle de confiance, format persistant, protocole public, politique de sécurité, toolchain), pas un correctif local. Pendant la **spécification** OpenSpec, un ADR est seulement nommé et planifié en tâche : il s'écrit à l'implémentation.

1. Lire `docs/adr/README.md` : dernier numéro (l'index fait foi), statuts (`Proposed | Accepted | Superseded | Rejected`), ADR voisins à citer ; réunir les faits à l'appui (code relu `fichier:ligne`, mesures, gates).
2. **Nouvel ADR** → `docs/adr/NNNN-titre-en-kebab-case.md`, en français, statut `Proposed` :
   ```markdown
   # NNNN — Titre qui énonce la décision

   Status: Proposed (AAAA-MM-JJ)

   Complète/Amende/Remplace [00XX](00XX-….md).

   ## Contexte
   Faits vérifiés, contraintes, options considérées.

   ## Décision
   Ce qui est décidé, conditions prouvables (test, gate), ce qui est exclu.

   ## Conséquences
   Positives, négatives, risques, migration, Windows et Linux.

   ## Mise en œuvre
   (ajouté à l'implémentation : écarts à la conception, preuves.)
   ```
   puis ajouter la ligne à l'index (`| [NNNN](…) | Décision | Statut | Origine |`).
3. **ADR existant** → ne compléter que « Mise en œuvre » ; pour changer la décision, écrire l'ADR qui la remplace et passer l'ancien à `Superseded` (ou « Partially superseded by ADR-NNNN »). Le hook demande confirmation avant de modifier un ADR existant ; un nouvel ADR se crée librement.
4. Ne **pas** passer un ADR à `Accepted` sans décision explicite de l'utilisateur, avec date.
5. Si la décision change une règle gardée (frontières, E/S, supply-chain), mettre à jour le gate et `docs/developer/quality-gates.md` dans le même changement.
6. Rejouer `python scripts/docs/check-current-docs.py` et `python scripts/docs/product-facts.py --check` ; vérifier les liens.

ADR de référence : 0017 (MCP lecture seule), 0022 (reactor), 0040 (indexeurs embarqués), 0041 (indexation distante non fiable), 0042 (racine de composition), 0043 (retrait des artefacts de jalon), 0044 (un package, un module), 0057 et 0058 (frontières hexagonales).
