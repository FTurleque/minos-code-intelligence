# Plan de réalisation — pgAdmin et explorateur vectoriel (novembre 2026)

**État : spécifié, décisions prises le 2026-11-13. Aucun lot n'est implémenté ni qualifié.** Base : `develop` `63e0c4ac` (2026-11-11).

- Étude : [`docs/project/research/explorateur-vectoriel-2026-11/`](../../research/explorateur-vectoriel-2026-11/README.md)
- Exigences et tâches : trois changements OpenSpec, à appliquer et archiver dans cet ordre — `inspecter-postgresql-avec-pgadmin` (lot 1), `explorer-l-index-semantique-dans-un-navigateur` (décision, lots 2 et 2b), `projeter-l-index-semantique-en-2d` (lots 3 et 4)
- Décision acceptée : [ARCH-0082](../../../architecture/adr/ARCH-0082-surface-http-locale-optionnelle-de-consultation.md)

## Lots

| Lot | Contenu | Dépend de | Tâches | Critère de fin |
|---|---|---|---|---|
| **1 — Inspection pgAdmin** | service Compose optionnel sur la boucle locale, rôle `minos_inspect`, requêtes de diagnostic, page utilisateur | aucune décision d'architecture | groupe 1 | cas d'auto-test verts (service, droits, retrait) ; les sept requêtes exécutées sur une instance réelle ; PostgreSQL toujours non publié |
| **Frontières** | ARCH-0082 acceptée et module `minos-explorer` retenu le 2026-11-13 ; reste à créer le module | — | groupe 2 | gate de frontières vert |
| **2 — Liste, recherche, détail** | lectures paginées, voisins, détail, API Java, surface HTTP locale, interface web | décision | groupes 3, 4, 5 | scénarios des exigences verts sur les deux stockages en natif ; revue de sécurité traitée ; parcours navigateur relevé sur Windows et Linux |
| **2b — Runtime Docker** | service `minos-explorer` | lot 2 | tâche 5.7 | qualification relevée ; matrice de support mise à jour |
| **3 — Projection 2D** | lecture des vecteurs par lots, PCA, cache dérivé, statut, points, carte | lot 2 | groupe 6 | préparation reproductible ; états de projection couverts ; mesures consignées aux paliers disponibles |
| **4 — Options** | UMAP, rendu WebGL, vue 3D | mesures du lot 3 | tâche 7.1 | une décision écrite par option ; aucune implémentation dans ce changement |

Le lot 1 est indépendant des autres et peut être livré seul. Le lot 2b passe avant le lot 3 : l'index appris du poste vit dans le PostgreSQL géré.

## Premier lot proposé

**Lot 1.** Il ne crée aucune surface MINOS, ne touche à aucun module Java de production en dehors d'un test de droits, et donne tout de suite un moyen d'inspecter l'index réel du poste, qui vit dans le PostgreSQL géré.

Critères de fin :

1. Sans le profil `pgadmin`, aucun conteneur ni port supplémentaire.
2. Avec le profil, un seul port publié, lié à `127.0.0.1` ; `minos-postgres` sans port publié.
3. Le rôle `minos_inspect` ne peut ni écrire ni lire `projects.root_value`, y compris après `SET default_transaction_read_only = off`.
4. Le retrait supprime pgAdmin et le rôle, et laisse le volume PostgreSQL et les lignes de `semantic_documents` inchangés.
5. Gates `check-compose-limits.py` et `check-image-pins.py` verts ; page utilisateur publiée avec des requêtes exécutées.

## Limites connues du plan

- Aucune charge n'est estimée ; le lot 1 dépend du résultat de la tâche 1.1 (réseaux Docker).
- Les seuils de performance sont à fixer après la première mesure.
- L'accès distant n'a pas de lot : il exige une décision séparée sur l'authentification et l'autorisation par projet.
