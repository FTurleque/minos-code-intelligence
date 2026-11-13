# Tasks

Aucun constat d'audit (`AUD-…`) n'est à l'origine de ce changement. Les exigences sont citées « capacité › titre ». Plan commun : `docs/project/roadmap/explorateur-vectoriel-2026-11/README.md`. Toute commande Maven passe par le wrapper, `-pl` avec `-am`.

La numérotation des groupes est celle du plan commun : les groupes 2 à 5 sont portés par le changement `explorer-l-index-semantique-dans-un-navigateur`, qui doit être archivé avant celui-ci.

## 6. Lot 3 — Projection 2D

- [ ] 6.1 Rouge : tests « deux préparations identiques → coordonnées identiques », « budget dépassé → échec, projection précédente intacte », « aucun appel au provider ni à la recherche pendant la préparation », « lecture des vecteurs par lots, jamais `load()` » (`exploration-semantique` › La préparation d'une projection est une commande explicite et bornée).
- [ ] 6.2 Correction : lecture des vecteurs par lots sur le port et les deux adaptateurs ; sélection reproductible ; PCA par itération de puissance ; commande `minos semantic projection prepare` ; vérifier 6.1.
- [ ] 6.3 Rouge : tests « document modifié → `PERIMEE` », « préparation interrompue → aucune projection partielle lisible », « fichier altéré → refusé à la lecture » (`exploration-semantique` › Une projection est publiée atomiquement et liée à l'identité de son index).
- [ ] 6.4 Correction : port `SemanticProjectionStore`, adaptateur fichier par les primitives d'E/S privées, identité complète, publication atomique ; vérifier 6.3 ; rejouer `check-private-io.py`.
- [ ] 6.5 Rouge : tests « projection absente → état `ABSENTE`, aucun vecteur lu », « plafond de 200 sur 1 000 → échantillonnage annoncé avec sa graine » , « `POST` sur la projection → `405`, rien n'est créé » (`exploration-semantique` › Les points d'une projection annoncent méthode, sélection et échantillonnage ; › La lecture d'une projection ne calcule rien).
- [ ] 6.6 Correction : routes de statut et de points, commande `semantic projection status` ; vérifier 6.5.
- [ ] 6.7 Carte dans l'interface (Canvas 2D, zoom, déplacement, survol, sélection, couleurs, légende, compteurs, mise en évidence des résultats, états de projection) ; **(manuelle)** parcours navigateur des états de projection.
- [ ] 6.8 Mesures selon le protocole du design (« Performance et mesure ») sur Windows et Linux ; consigner les relevés ; proposer les seuils au propriétaire. Limite : le palier de 100 000 documents exige PostgreSQL.
- [ ] 6.9 Documentation : dossier de capacité, pages `docs/user/` et `docs/developer/`, `STATUS.md`, `ROADMAP.md`.

## 7. Lot 4 — Options sous décision mesurée (hors engagement)

- [ ] 7.1 **(manuelle)** Décider, sur les relevés de 6.8, d'ouvrir ou non un changement distinct pour UMAP (critère : préservation des k plus proches voisins, licence et maintenance de la bibliothèque), le rendu WebGL (critère : fluidité mesurée insuffisante) et la vue 3D (critère : variance portée par la troisième composante). Aucune de ces options n'est implémentée par ce changement.

## 8. Clôture

- [ ] 8.1 Mettre à jour la matrice de traçabilité du design (exigence → scénario → conception ou diagramme → tâche → test ou preuve) en nommant les preuves manquantes ; vérifier qu'aucune ligne n'affirme une couverture sans preuve
- [ ] 8.2 Mettre à jour le dossier durable de la capacité `exploration-semantique` ; vérifier par `python scripts/docs/check-capability-dossiers.py`
- [ ] 8.3 Valider les spécifications ; vérifier par `openspec validate --all --strict`
- [ ] 8.4 Lister les documents à mettre à jour avant archivage (STATUS, ROADMAP, docs/user, docs/developer, arc42, ADR ARCH-0082 « Mise en œuvre ») ; vérifier que chacun est fait ou nommé comme reste

## Workflow follow-up

- Archiver le changement après revue (`openspec archive projeter-l-index-semantique-en-2d -y`), puis vérifier que chaque spec de référence garde sa section `## Documentation liée`.
