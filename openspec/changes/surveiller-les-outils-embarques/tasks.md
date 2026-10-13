# Tasks

Ordre dans chaque section : rouge, correction, preuve. Aucun fichier livré n'est renommé (D1).

## 1. Verrous npm dans le gate OSV (AUD-DEP-03)

- [ ] 1.1 [AUD-DEP-03] **Décision à obtenir** : D1 (`--lockfile` explicite, recommandé, ou renommage + Dependabot `npm`). Puis rouge mesuré : sur une copie du dépôt, remplacer dans `scip-python-package-lock.json` une version par une version touchée par un avis connu et lancer OSV-Scanner (version épinglée par le workflow) avec les `scan-args` **actuels**. **Preuve du rouge : l'analyse ne voit pas le verrou et réussit.** Consigner la commande et la sortie dans `design.md` (lève le « non mesuré »).
- [ ] 1.2 [AUD-DEP-03] Ajouter les deux `--lockfile=package-lock.json:<chemin>` aux `scan-args` de `.github/workflows/pr-ci.yml`. Preuve : la même copie échoue en nommant le paquet ; le dépôt réel réussit avec les deux verrous dans les sources analysées. Valider le YAML par `yaml.safe_load` ; rejouer `python scripts/quality/check-workflow-pins.py`.

## 2. Gate de présence (AUD-DEP-03)

- [ ] 2.1 [AUD-DEP-03] Rouge : cas ajoutés au test de `scripts/quality/check-ci-wiring.py` sur arbres temporaires (verrou de ressources absent des `scan-args` refusé ; présent accepté ; verrou sous `fixtures/` ou `src/test` ignoré). **Preuve du rouge avant la règle.**
- [ ] 2.2 [AUD-DEP-03] Règle D2 dans `check-ci-wiring.py`. Preuve : test vert, gate vert sur le dépôt réel, `python scripts/quality/check-single-execution.py` vert.

## 3. Veille planifiée du catalogue (AUD-DEP-03)

- [ ] 3.1 [AUD-DEP-03] Rouge : `scripts/quality/check-embedded-tools-advisories.py --self-test` écrit d'abord avec ses cas (composant touché, composant hors support, composant absent de la table, échec réseau, dérogation valide, dérogation échue, composants non couverts listés) sur réponses simulées. Puis le script (D3) : table identifiant → (écosystème, nom) ou raison de non-couverture pour les 11 composants, requêtes par lot, sortie en trois sections, fichier de dérogations datées.
- [ ] 3.2 [AUD-DEP-03] Mesurer si les 74 coordonnées de `scip-java-classpath.lock.json` peuvent être interrogées dans le même passage (durée, quotas) ; les inclure, ou les déclarer non couvertes avec la raison.
- [ ] 3.3 [AUD-DEP-03] Câbler l'auto-test dans `invariants` (`pr-ci.yml`) ; créer le workflow planifié (hebdomadaire + `workflow_dispatch`, actions épinglées par SHA, `permissions: contents: read`, `timeout-minutes`, `persist-credentials: false`). Rejouer `check-ci-wiring.py`, `check-workflow-pins.py`, `check-single-execution.py`. Ne **pas** l'ajouter à `.github/required-checks.json`.
- [ ] 3.4 **(manuelle)** [AUD-DEP-03] Lancer le workflow une fois par `workflow_dispatch` ; noter le run id et la liste des composants non couverts dans `SUIVI.md`. Une alerte réelle se traite selon D4, pas dans ce changement.

## 4. Clôture

- [ ] 4.1 `docs/developer/supply-chain.md` : tableau « composant livré → veille » (Maven, Gradle, actions, images, verrous npm, catalogue, non couverts) et procédure de traitement d'une alerte ; note de mise en œuvre de l'ADR 0040. Rejouer `python scripts/docs/check-current-docs.py`.
- [ ] 4.2 Mettre à jour `docs/audit/2026-10-10/SUIVI.md` (AUD-DEP-03 : statut, décision D1, mesure 1.1, couverture obtenue).
- [ ] 4.3 `openspec validate --all --strict` ; docs à mettre à jour avant archivage : `docs/STATUS.md`, `docs/developer/supply-chain.md`, `docs/developer/quality-gates.md`.
