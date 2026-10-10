# Tasks

Ordre dans chaque section : d'abord le test ou le gate qui échoue (rouge attendu), puis la correction, puis la preuve. Chaque tâche porte l'identifiant du constat qu'elle ferme. Commande de référence de l'auto-test : `python -m unittest scripts/architecture/test_check_module_boundaries.py -v` ; du garde : `python scripts/architecture/check-module-boundaries.py`. Avant toute modification, enregistrer la sortie du garde dans le répertoire temporaire de la session (pas dans le `target/` racine) : c'est la référence d'identité de la section 1.

## 1. Rendre les règles A2 testables, puis les tester (AUD-ARC-10)

- [x] 1.1 [AUD-ARC-10] Rouge : écrire dans `test_check_module_boundaries.py` les cas refusé/accepté du tableau de la décision D1 (hexagonale, politique, cycle Maven, dépendance cachée, mise en page, sources A2, disposition Java, document généré) et `EveryRuleHasATest` (toute fonction `check_*` du module est référencée par au moins un test). Ils échouent ou ne s'exécutent pas tant que les fonctions n'acceptent pas `root`.
- [x] 1.2 [AUD-ARC-10] Ajouter le paramètre `root` (défaut `ROOT`) à `parse_pom`, `adapter_classes`, `check_source_boundaries`, `check_java_layout` et au contrôle du document généré, sans changer leur comportement. Preuve d'identité : la sortie de `check-module-boundaries.py` est identique à la référence (aux chemins près), et `python scripts/architecture/check-module-boundaries.py --write-doc` ne laisse aucun diff (`git status` propre sur `docs/architecture/diagrams/module-dependencies.md`).
- [x] 1.3 [AUD-ARC-10] Mutations témoins : tests qui copient les 14 `pom.xml` réels dans un arbre temporaire et appliquent (a) `minos-cli` → `minos-storage-local`, (b) `minos-application` → `minos-provider-scip`, (c) `minos-api` → `minos-bootstrap` en portée `compile` ; chacune doit faire échouer la règle attendue et nommer module, cible et ADR. Contre-témoin : retirer une dépendance permise ne fait échouer ni la politique ni la hexagonale. Preuve : auto-test vert (≥ 13 + les nouveaux cas, aucun saut).

## 2. E02 et mise en page dans les profils (AUD-ARC-10)

- [x] 2.1 [AUD-ARC-10] Rouge : cas `<groupId>${project.groupId}</groupId>` refusé, `groupId` voisin `com.minos.fake` avec l'artefact `minos-engine` refusé, `groupId` racine accepté ; `sourceDirectory`, `testSourceDirectory`, `includes`, `excludes` dans `profiles/profile/build` refusés, profil à `plugins` seuls accepté.
- [x] 2.2 [AUD-ARC-10] Dériver le `groupId` interne du POM racine (`minos_dependencies`, `check_no_hidden_internal_dependencies`, `SOURCE_QUALIFIED`) ; appliquer `check_pom_layout` aux profils et à `pluginManagement`. Ne **pas** ajouter la règle « tout `<build>` dans un `<profile>` » (six profils légitimes, voir `design.md`). Preuve : auto-test vert ; `check-module-boundaries.py` vert sur le dépôt.

## 3. Cycles de packages en cliquet (AUD-ARC-06)

- [x] 3.1 [AUD-ARC-06] Rouge : cas de la décision D4 sur arbres temporaires — nouveau cycle refusé (avec arête témoin de chaque sens) ; cycle connu inchangé accepté ; cycle connu étendu refusé ; cycle levé mais listé refusé ; cycle réduit refusé ; cycle dans un second répertoire de sources (modèle du plugin) refusé ; arbre acyclique et table vide accepté ; import joker et nom qualifié comptés comme arêtes.
- [x] 3.2 [AUD-ARC-06] Implémenter la règle A8 : graphe package → package (14 modules + `minos-intellij/src/main/java`), composantes fortement connexes itératives, table `KNOWN_PACKAGE_CYCLES` (entrée = packages, module, raison, changement de levée) initialisée avec les quatre composantes de `design.md`. Preuve : le garde affiche 4 cycles connus et 15 packages ; la sortie est identique sous Windows et Linux (chemins `as_posix()`, tri) ; le nombre concorde avec le recalcul indépendant de l'audit (15).
- [x] 3.3 [AUD-ARC-06] Preuve du cliquet sur le dépôt, sans le laisser en place : dans une copie temporaire des sources, (a) ajouter un import réciproque entre deux packages aujourd'hui acycliques (choisis avec le recalcul indépendant) ; (b) retirer l'entrée `discovery` de la table ; (c) réduire un cycle connu en y déplaçant une classe ; le garde doit échouer dans chaque cas.
- [x] 3.4 [AUD-ARC-06] (suggestion, non bloquante) Ajouter à la section « Mise en œuvre » de l'ADR 0057 une ligne : cycles de packages gardés par A8, table `KNOWN_PACKAGE_CYCLES`, levée par `casser-les-cycles-de-packages`. Aucun nouvel ADR.

## 4. Documentation d'architecture contrôlée contre les POM (AUD-ARC-03)

- [x] 4.1 [AUD-ARC-03] Rouge : cas de la décision D5 sur arbres temporaires (liste fausse nommant module et ligne attendue ; module sans section ; section sans ligne ; « tous les modules » pour `minos-app` accepté et refusé pour un autre module ; jetons tiers ignorés ; suffixe « (optionnel) » ignoré ; document absent = échec).
- [x] 4.2 [AUD-ARC-03] Implémenter la règle A9 dans `check-module-boundaries.py` (lecture du graphe existant, aucun second lecteur de POM). Preuve de rouge sur le dépôt : le garde échoue sur **exactement** les 7 modules du tableau de `design.md`.
- [x] 4.3 [AUD-ARC-03] Corriger `docs/architecture/arc42/05-vue-blocs.md` : les 7 lignes « Dépendances » (sorties imprimées par le garde) ; `InMemoryCodeKnowledgeStore` sous `minos-engine` (`com.minos.store`) et non `minos-storage-local` ; note de `minos-storage-postgresql` (« ce module dépend de `minos-application` ») corrigée ou retirée selon le POM ; renvoi à `diagrams/module-dependencies.md`.
- [x] 4.4 [AUD-ARC-03] `docs/architecture/SYNTHESE.md` : bandeau « document historique, état du 2026-08-06 » en tête, ligne I-3 (`:80`) et hypothèse (`:117`) corrigées ou barrées avec renvoi à `arc42/05` et à l'ADR 0058. Ajouter aux diagrammes `docs/architecture/diagrams/c4-container.md` et `arc42/05 § 5.1` les arêtes pointillées « dette déclarée (ADR 0058) » surfaces → moteur et l'arête adaptateur → adaptateur, sans toucher aux autres flèches.
- [x] 4.5 [AUD-ARC-03] Preuve : `python scripts/architecture/check-module-boundaries.py` vert ; `--write-doc` sans diff ; `python scripts/docs/check-current-docs.py` vert ; `python scripts/quality/check-milestone-artifact-references.py` vert.

## 5. Documentation des gardes

- [x] 5.1 [AUD-ARC-10, AUD-ARC-06, AUD-ARC-03] `docs/developer/quality-gates.md` : règles A8 (table des cycles, procédure de resserrement) et A9 (forme attendue d'`arc42/05`), garde-fous de l'auto-test. Rejouer `check-current-docs.py`.

## 6. Suite à ouvrir (non exécutée ici)

- [ ] 6.1 **(manuelle)** [AUD-ARC-06] Décider d'ouvrir le changement `casser-les-cycles-de-packages` (analyse en D7 de `design.md` : `ProjectResolver` + `DeterministicJson` pour la composante de `minos-application`) ; il retirera des lignes de `KNOWN_PACKAGE_CYCLES` et devra rejouer `check-jacoco.py` (scope `project-resolution`), `check-post-mne.py`, `check-mnd.py`, `check-polyglot-provider-consistency.py`, `ModuleArchitectureTest` et vérifier l'identité des goldens à l'octet.

## 7. Clôture

- [x] 7.1 Mettre à jour `docs/audit/2026-10-10/SUIVI.md` (créé par un autre changement du sprint, sinon le créer) : AUD-ARC-10 (fermé), AUD-ARC-03 (fermé), AUD-ARC-06 (**garde livrée, cycles subsistants gelés dans la table** : statut « partiel » jusqu'à `casser-les-cycles-de-packages`), avec commit et commandes de preuve. Noter la correction d'action sur le `<build>` des profils.
- [x] 7.2 `openspec validate --all --strict` ; lister les `docs/` à mettre à jour avant archivage (`docs/STATUS.md`, `docs/developer/quality-gates.md`, ADR 0057).
