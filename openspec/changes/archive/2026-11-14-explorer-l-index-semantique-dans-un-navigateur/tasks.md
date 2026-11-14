# Tasks

Aucun constat d'audit (`AUD-…`) n'est à l'origine de ce changement. Les exigences sont citées « capacité › titre ». Plan commun : `docs/project/roadmap/explorateur-vectoriel-2026-11/README.md`. Toute commande Maven passe par le wrapper, `-pl` avec `-am`.

La numérotation des groupes est celle du plan commun : le groupe 1 (pgAdmin) est porté par `inspecter-postgresql-avec-pgadmin`, les groupes 6 et 7 (projection) par `projeter-l-index-semantique-en-2d`.

## 2. Décision et frontières (préalable aux lots 2 et 3)

- [x] 2.1 Décisions prises par le propriétaire le 2026-11-13 : ARCH-0082 acceptée, module `minos-explorer` retenu. Relire `ALLOWED_DEPENDENCIES` (`scripts/architecture/check-module-boundaries.py:59`) et fixer les dépendances permises du module et l'arête `minos-cli → minos-explorer` avant d'écrire le rouge 2.2.
- [x] 2.2 Rouge : déclarer `minos-explorer` dans `ALLOWED_DEPENDENCIES` avec ses seules dépendances permises et un test ArchUnit « la surface ne dépend d'aucun adaptateur de stockage » ; vérifier que `check-module-boundaries.py` échoue tant que le module n'existe pas.
- [x] 2.3 Correction : module `minos-explorer` vide de logique (POM, package racine, déclaration dans le reactor et dans `minos-app`) ; régénérer `docs/architecture/diagrams/module-dependencies.md` par `check-module-boundaries.py --write-doc` ; rejouer `python .claude/scripts/run_gates.py --fast`.

## 3. Lot 2 — Lectures de l'index (domaine, stockage, application, API Java)

- [x] 3.1 Rouge : tests de contrat communs aux deux adaptateurs pour la page de documents (parcours complet sans doublon, borne de taille, curseur refusé après remplacement de l'index) (`exploration-semantique` › La liste des documents est paginée et lue sur un seul index) ; test de décompte « aucune lecture de la colonne `embedding` ».
- [x] 3.2 Correction : lecture paginée additive sur `SemanticVectorStore` (défaut dérivé), implémentations locale et PostgreSQL, `SemanticCorpusQueryService.documents` avec filtres et identité d'index ; vérifier 3.1 ; scopes JaCoCo `minos-domain`, `minos-storage`, `minos-application`.
- [x] 3.3 Rouge : tests « voisins sans appel au provider », « document exclu de ses voisins », « clé inconnue → introuvable », égalité avec `search` sur le même vecteur (`exploration-semantique` › Les voisins d'un document sont classés depuis son vecteur stocké).
- [x] 3.4 Correction : lecture du vecteur d'un document sur le port et les deux adaptateurs ; `SemanticCorpusQueryService.neighbors` ; vérifier 3.3.
- [x] 3.5 Rouge : tests du détail — source modifiée (`DIFFERENTE`), supprimée (`SOURCE_INTROUVABLE`), nature `FILE` (`NON_COMPARABLE`), tentative de sortie de la racine refusée, aucune racine absolue dans le résultat (`exploration-semantique` › Le détail distingue le contenu indexé de la source actuelle).
- [x] 3.6 Correction : `SemanticCorpusQueryService.detail` (contenu indexé, extrait par `LocalSourceReader`, divergence, symbole résolu par le port de lecture des snapshots, « non résolu » si l'index n'est pas aligné) ; vérifier 3.5.
- [x] 3.7 Rouge puis correction : test de contrat `SemanticExplorationApi` (version `1`, DTO sans type interne, erreurs classées) sur le modèle de `minos-api/src/test/java/com/minos/api/SemanticCodeIntelligenceApiContractTest.java`, puis l'interface et son implémentation locale ; `SemanticCodeIntelligenceApi` inchangée (son test de contrat reste vert).
- [x] 3.8 Documentation : `docs/developer/` (contrats de lecture), `docs/user/java-api.md` ; créer `docs/architecture/diagrams/capacites/exploration-semantique.md` avec les vues du design ; vérifier par `python scripts/docs/check-capability-dossiers.py`.

## 4. Lot 2 — Surface HTTP locale

- [x] 4.1 Rouge : tests « aucune écoute hors `explorer serve` », « socket lié à la boucle locale seulement », « refus de démarrage avec `MINOS_HOSTED_MODE=enabled` » (`exploration-semantique` › La surface d'exploration est optionnelle et n'écoute que sur la boucle locale).
- [x] 4.2 Correction : serveur sur `jdk.httpserver`, commande `minos explorer serve [--port N]`, arrêt propre, URL sur `stderr` ; vérifier 4.1 ; golden de l'aide CLI (`minos-app/src/test/resources/characterization/`) mis à jour sans dépendre de l'hôte.
- [x] 4.3 Rouge : tests de refus — sans jeton (`401`), `Host` hors liste (`403`), `Origin` étranger (`403`), aucun en-tête CORS, aucun cookie, service non appelé en cas de refus (`exploration-semantique` › Toute requête d'API exige le jeton de session et une origine locale).
- [x] 4.4 Correction : filtre unique devant toutes les routes `/api/` (jeton de 256 bits comparé en temps constant, liste d'hôtes, contrôle d'origine) ; vérifier 4.3.
- [x] 4.5 Rouge : test « arborescence de `MINOS_HOME` et index identiques après consultation de toutes les routes » et test d'inventaire « aucune route n'accepte `PUT`, `PATCH`, `DELETE` ; `POST` réservé à la recherche » (`exploration-semantique` › La surface d'exploration ne modifie aucun état).
- [x] 4.6 Correction : routes projets, documents, détail, voisins ; bornes, délai par requête, plafond de requêtes simultanées ; vérifier 4.5.
- [x] 4.7 Rouge : tests « aucune racine absolue ni adresse de provider dans une réponse, une erreur ou un journal », « réponses JSON avec `nosniff` », « page servie avec une CSP sans `unsafe-inline` » (`exploration-semantique` › Aucune réponse de l'explorateur n'expose de chemin absolu ni de secret).
- [x] 4.8 Correction : en-têtes de sécurité, erreurs opaques, ressources web servies depuis le jar ; vérifier 4.7 ; rejouer `scripts/quality/check-comment-language.py` et déclarer la langue du nouveau module dans `scripts/quality/comment-language.json`.
- [x] 4.9 Windows : construire la distribution et vérifier que le runtime embarqué contient `jdk.httpserver` (`scripts/release/build-windows-distribution.ps1`) ; **(manuelle)** si le build de distribution n'est pas disponible localement.
- [x] 4.10 Revue de sécurité par le skill `minos-security-checklist` et l'agent `minos-security-reviewer` ; consigner les constats et leur traitement dans le design.

## 5. Lot 2 — Recherche et interface (liste, recherche, détail)

- [x] 5.1 Rouge : test « classement et scores identiques à `semantic-search` pour la même requête et les mêmes bornes », « index `STALE` → réponse vide et limite `SEMANTIC_SEARCH_REQUIRES_READY_INDEX` », « un filtre retire des lignes sans changer score ni rang » (`exploration-semantique` › La recherche de l'explorateur rend le classement des services existants et nomme chaque score).
- [x] 5.2 Correction : route de recherche déléguant à `SemanticSearchService` et `HybridSearchService`, nature de chaque score dans la réponse ; vérifier 5.1 ; rejouer `scripts/quality/check-semantic-retrieval-consistency.py`.
- [x] 5.3 Rouge : test « index `local-hash` → limite `LOCAL_HASH_EMBEDDING_NOT_LANGUAGE_MODEL` dans le statut, la recherche et les voisins » (`exploration-semantique` › Le provider local-hash est signalé comme référence déterministe).
- [x] 5.4 Correction : propagation de la limite dans les trois réponses ; vérifier 5.3.
- [x] 5.5 Interface web (table, filtres, recherche, détail, états du document de conception) à partir de la maquette, sans chaîne de build ni ressource externe, tout contenu inséré comme texte ; test automatisé « aucun `innerHTML`, aucune URL externe dans les ressources web ».
- [ ] 5.6 **(manuelle)** Parcours dans un navigateur sur Windows et Linux : chaque état du tableau de `CONCEPTION-FONCTIONNELLE.md` § 7 hors projection ; relevé joint.
- [x] 5.7 Lot 2b — runtime Docker : service `minos-explorer` (profil `explorer`, image de requête, données en lecture seule, port publié sur la boucle locale), mêmes gates que le service pgAdmin (`check-compose-limits.py`, `check-image-pins.py`) ; rouge d'abord par les cas d'auto-test des scripts Docker ; qualification relevée avant toute mention de support.
- [x] 5.8 Documentation : page `docs/user/` « Explorer l'index sémantique », matrice de support exacte ; `STATUS.md` et `ROADMAP.md` par le skill `minos-doc-sync` ; rejouer `scripts/docs/check-current-docs.py` et `scripts/docs/product-facts.py --check`.

## 8. Clôture

- [x] 8.1 Mettre à jour la matrice de traçabilité du design (exigence → scénario → conception ou diagramme → tâche → test ou preuve) en nommant les preuves manquantes ; vérifier qu'aucune ligne n'affirme une couverture sans preuve
- [x] 8.2 Mettre à jour le dossier durable de la capacité `exploration-semantique` ; vérifier par `python scripts/docs/check-capability-dossiers.py`
- [x] 8.3 Valider les spécifications ; vérifier par `openspec validate --all --strict`
- [x] 8.4 Lister les documents à mettre à jour avant archivage (STATUS, ROADMAP, docs/user, docs/developer, arc42, ADR ARCH-0082 « Mise en œuvre ») ; vérifier que chacun est fait ou nommé comme reste

## Workflow follow-up

- Archiver le changement après revue (`openspec archive explorer-l-index-semantique-dans-un-navigateur -y`), réécrire le « Purpose » provisoire de `exploration-semantique`, puis vérifier que chaque spec de référence garde sa section `## Documentation liée`.
