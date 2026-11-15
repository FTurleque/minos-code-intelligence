# Tasks

## 1. Prérequis et mesure de référence (AUD-PERF-10)

- [ ] 1.1 AUD-PERF-10 — Vérifier que AUD-TST-02 (changement `executer-le-front-de-l-explorateur-en-test`) est fusionné : `scripts/quality/check-explorer-front.py` existe, exécute `app.js` par `node:test` et caractérise la sélection de la carte ; sinon s'arrêter. Vérifier : `python scripts/quality/check-explorer-front.py` vert sur `main`.
- [ ] 1.2 AUD-PERF-10 — Mesure « avant » sous Node, dans le harnais de AUD-TST-02, sans modifier `app.js` : pour 100 000 et 250 000 points (tailles atteignables par une projection), puis 1 000 000 de points comme jeu synthétique hors de portée du produit (250 000 documents au plus par index), dans un canevas de 800 × 600 : nombre de points examinés par `pointAt` et durée médiane d'une recherche, avec et sans filtre de nature. Vérifier : relevé joint à la PR avec machine, version de Node et révision ; écrit comme mesure sous Node, pas dans un navigateur.

## 2. Grille spatiale et liste filtrée (AUD-PERF-10)

- [ ] 2.1 AUD-PERF-10 — Rouge : tests `node:test` — (a) équivalence avec un parcours de référence sur 100 000 points, 1 000 positions, plusieurs zooms (0,5 ; 1 ; 20 ; 200) et décalages, égalité de distance départagée par l'indice ; (b) faux DOM au canevas fixé à 800 × 600, zoom 1 : nombre de points examinés inférieur au centième des points affichés pour des points répartis sur toute la surface (le seuil dépend de `half`, `app.js:576` : il n'est affirmé ni à un autre canevas ni au zoom 0,5) ; (c) aucun point dans le rayon → aucun point, aucune requête de détail au clic ; (d) deux survols sous le même filtre → une seule liste filtrée ; (e) changement de filtre → aucun point de l'ancienne nature examiné ; (f) carte rechargée → grille reconstruite. Vérifier que (b) et (d) échouent au HEAD pour la bonne raison. Exigences : exploration-semantique › Le point sous le pointeur est cherché parmi les points voisins seulement ; › La liste des points d'une nature n'est calculée qu'à son changement. Vérifier : `python scripts/quality/check-explorer-front.py` rouge.
- [ ] 2.2 AUD-PERF-10 — Correction : construire la grille à la réception des points et chercher le point sous le pointeur dans les cellules qui couvrent le rayon de capture ; `pointAt` garde sa signature et son résultat. Vérifier : (a), (b), (c), (f) verts ; tests de caractérisation de AUD-TST-02 verts sans modification ; `./mvnw -B -ntp -pl minos-explorer -am test "-Dtest=WebResourcesContentTest" "-Dsurefire.failIfNoSpecifiedTests=false"` ; `scripts/quality/check-comment-language.py`.
- [ ] 2.3 AUD-PERF-10 — Correction : mettre en cache la liste des points du filtre courant, invalidée au changement de filtre et au remplacement des points ; `drawMap`, `pointAt` et `markRowsOnMap` la réutilisent. Vérifier : (d), (e) verts ; `WebResourcesContentTest` vert.

## 3. Survol traité une fois par image (AUD-PERF-10)

- [ ] 3.1 AUD-PERF-10 — Rouge : tests `node:test` avec un faux `requestAnimationFrame` — vingt `pointermove` avant l'image → une seule recherche, à la dernière position ; `pointerup` avant l'image → détail du point cliqué demandé. Vérifier que le premier échoue au HEAD (vingt recherches). Exigence : exploration-semantique › Le survol de la carte est traité au plus une fois par image.
- [ ] 3.2 AUD-PERF-10 — Correction : `hoverMap` garde la dernière position et traite le survol à l'image ; le clic cherche immédiatement. Vérifier : 3.1 vert ; contrôle par exécution des puits DOM de AUD-TST-02 vert ; `python scripts/quality/check-explorer-front.py` ; `python .claude/scripts/run_gates.py --fast`.

## 4. Mesure après correction (AUD-PERF-10)

- [ ] 4.1 AUD-PERF-10 — Mesure « après » sous Node : mêmes jeux et même machine que 1.2. Vérifier : tableau avant/après joint à la PR, gain écrit tel que mesuré, y compris nul à 100 000 points.
- [ ] 4.2 AUD-PERF-10 — **(manuelle)** Parcours dans un navigateur sur la plus grande projection réellement préparée (32 768 points à 768 dimensions ; jusqu'à 250 000 à petites dimensions ; jamais 1 000 000, que le produit ne construit pas) : survol, clic, zoom, filtre de nature, recherche avec surbrillance ; relevé de l'outil de performance du navigateur avant et après. Vérifier : relevé et navigateur nommés dans la PR ; ce qui n'a pas été parcouru est dit.
- [ ] 4.3 AUD-PERF-10 — **(manuelle, propriétaire)** Reporter la mesure dans `docs/project/audits/2026-11-15/SUIVI.md` ; confirmer D3 (contrat des points inchangé). Vérifier : relecture du propriétaire.

## 5. Clôture

- [ ] 5.1 AUD-PERF-10 — Mettre à jour la matrice de traçabilité du design en nommant les preuves manquantes ; vérifier qu'aucune ligne n'affirme une couverture sans preuve.
- [ ] 5.2 AUD-PERF-10 — Mettre à jour `docs/architecture/diagrams/capacites/exploration-semantique.md` ; vérifier par `python scripts/docs/check-capability-dossiers.py`.
- [ ] 5.3 AUD-PERF-10 — Valider les spécifications ; vérifier par `openspec validate --all --strict`.
- [ ] 5.4 AUD-PERF-10 — Lister les documents à mettre à jour avant archivage (STATUS, ROADMAP, `docs/user`, `docs/developer`, arc42, SUIVI de l'audit) ; vérifier que chacun est fait ou nommé comme reste.

## Workflow follow-up

- Archiver le changement après revue (`openspec archive rendre-le-survol-de-la-carte-independant-du-nombre-de-points -y`), puis vérifier que la spec de référence garde sa section `## Documentation liée`.
