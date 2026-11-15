# Tasks

## 1. Tableau d'état exact et gardé (AUD-TST-10)

- [ ] 1.1 AUD-TST-10 — Rouge, après décision D2 du propriétaire (recommandation : garde automatique) : ajouter à `scripts/quality/audit-report-summary.py` la sous-commande `mutation-status --check` (option `--root`, bibliothèque standard, tableau repéré par son en-tête de colonnes) et son `--self-test` sur copies mutées : module ajouté sans ligne, module disparu encore nommé, tableau introuvable, cas accepté. Exigence : audit-qualite-code › Le tableau d'état de la mutation suit la liste des modules du reactor. Vérifier : auto-test vert ; `python scripts/quality/audit-report-summary.py mutation-status --check` **rouge** sur le dépôt (`unknown: minos-storage-local`, `unknown: minos-storage-postgresql`, `missing: minos-storage`, `missing: minos-explorer`).
- [ ] 1.2 AUD-TST-10 — Correction : dans `docs/developer/quality/code-audit-couverture.md`, réaligner le tableau « Statut PIT par module — source unique » sur les quatorze modules et le plugin (`minos-storage` et `minos-explorer` à « non exécuté » avec leur raison ; lignes des deux modules disparus retirées), corriger sa phrase d'introduction, ajouter la note « Mesures antérieures à la fusion » (E27, E30) et les notes de date de l'inventaire § 2.1 et du point de reprise § 7.3. Exigence : audit-qualite-code › Une mesure de mutation antérieure à une fusion de modules n'est pas reportée sur le module fusionné. Vérifier : contrôle de 1.1 vert ; `grep -c "modules sur 14"` du document au plus égal à 1 (0 au HEAD : la phrase d'introduction écrit « 11 sur 14 » et « 3 sur 14 ») ; `python scripts/docs/check-current-docs.py` ; liens relatifs valides.
- [ ] 1.3 AUD-TST-10 — Câblage : faire exécuter le contrôle et son auto-test par le job `invariants` de `.github/workflows/pr-ci.yml` ; documenter la commande dans `docs/developer/quality/code-audit.md`. Vérifier : `python scripts/quality/check-ci-wiring.py`, `python scripts/quality/check-single-execution.py`, `python .claude/scripts/run_gates.py` verts.

## 2. Mesures rejouées (AUD-TST-10)

- [ ] 2.1 AUD-TST-10 — **(manuelle, CI à la demande du propriétaire)** lancer `code-audit.yml` sur `main` avec `run_mutation` et `mutation_module=minos-storage`, puis `mutation_module=minos-explorer` ; télécharger les artefacts `pit-reports` ; exécuter `python scripts/quality/audit-report-summary.py pit --module <module>` ; reporter décomptes, exécution, système et commit dans le document. Si le délai de 120 min est dépassé pour `minos-storage`, borner par `target_classes` et écrire « partiel » avec le périmètre. Vérifier : sorties jointes à la PR ; statut de chaque ligne conforme au rapport.
- [ ] 2.2 AUD-TST-10 — **(manuelle, CI à la demande du propriétaire)** rejouer la mutation du plugin par `code-audit.yml` (`run_intellij_plugin`), après fusion de `tester-la-logique-d-interface-du-plugin` s'il est prêt, et reporter le résultat. **Ne jamais lancer `pitest` du plugin sur le poste du mainteneur** (incident H23). Vérifier : artefact `intellij-plugin-audit-reports` cité dans le document.

## 3. Documentation durable (AUD-TST-10)

- [ ] 3.1 AUD-TST-10 — Reporter les deux exigences et leur qualification (état exact ; mutation de `minos-storage` et `minos-explorer` non exécutée tant que 2.1 ne l'est pas) dans `docs/architecture/diagrams/capacites/audit-qualite-code.md`. Vérifier : `python scripts/docs/check-capability-dossiers.py`.

## 4. Clôture

- [ ] 4.1 AUD-TST-10 — Mettre à jour la matrice de traçabilité du design, preuves manquantes nommées (mesures de 2.1 et 2.2 si elles ne sont pas faites) ; vérifier qu'aucune ligne n'affirme une couverture sans preuve.
- [ ] 4.2 AUD-TST-10 — Contrôler le dossier de capacité ; vérifier par `python scripts/docs/check-capability-dossiers.py`.
- [ ] 4.3 AUD-TST-10 — Valider ; vérifier par `openspec validate --all --strict` et `python .claude/scripts/run_gates.py --fast`.
- [ ] 4.4 AUD-TST-10 — Lister les documents à mettre à jour avant archivage (`code-audit-couverture.md`, `code-audit.md`, dossier de capacité, `docs/project/audits/2026-11-15/SUIVI.md`) ; vérifier que chacun est fait ou nommé comme reste.

## Workflow follow-up

- Archiver après revue (`openspec archive actualiser-le-document-de-mutation -y`), puis vérifier que la spec de référence garde sa section `## Documentation liée`.
- La décision D3 de l'audit du 2026-10-10 (pas de plancher PIT) reste en vigueur ; la rouvrir est une décision du propriétaire.
