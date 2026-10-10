---
description: Rédiger un ADR (numéro, gabarit, index, références) ou en amender la mise en œuvre
argument-hint: "<titre de la décision | NNNN pour compléter un ADR existant>"
---

Applique le skill `minos-adr` pour : `$ARGUMENTS`.

1. Lire `docs/adr/README.md` (dernier numéro, statuts, ADR voisins) et les faits à l'appui (code relu, mesures, gates).
2. Nouvel ADR → créer `docs/adr/NNNN-titre.md` au gabarit (Contexte / Décision / Conséquences, statut `Proposed`), ajouter la ligne d'index. ADR existant → ne compléter que « Mise en œuvre » ou rédiger l'ADR qui le remplace.
3. Ne **pas** passer un ADR à `Accepted` sans décision explicite de l'utilisateur.
4. Rejouer `python scripts/docs/check-current-docs.py` et `python scripts/docs/product-facts.py --check` ; vérifier les liens.
