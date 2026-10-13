# Tasks

Ordre : correction documentaire d'abord (faisable seule), puis exécutions manuelles, puis décision. Aucune exécution de `code-audit.yml` n'est lancée sans le propriétaire : ce sont des actions sortantes. Tant que la section 2 n'est pas faite, AUD-TST-11 reste « implémenté pour le document, preuves d'exécution en attente ».

## 1. Un seul décompte dans le document (AUD-TST-11)

- [ ] 1.1 [AUD-TST-11] Rouge : relever dans `docs/quality/code-audit-couverture.md` tous les décomptes de modules mutés. **Preuve du rouge : trois valeurs incompatibles (`:122`, `:229`, `:288`) pour un tableau qui en donne une quatrième lecture (10 exécutés, 1 partiel, 3 sans rapport).** Relever aussi les documents courants hors archives qui reprennent « 3 sur 14 ».
- [ ] 1.2 [AUD-TST-11] Faire du tableau des statuts du § 5.2 la source unique (D1) : une ligne par module et une pour le plugin, colonnes statut, système, exécution, raison ; § 3.3 et § 7 ramenés à un renvoi ; correction datée. Preuve : une seule occurrence d'un décompte « modules sur 14 » relatif à PIT ; `python scripts/docs/check-current-docs.py` et `python scripts/docs/product-facts.py --check` verts ; liens du document vérifiés.

## 2. Exécutions manquantes (AUD-TST-11)

- [ ] 2.1 **(manuelle)** [AUD-TST-11] Le propriétaire lance `code-audit.yml` (`workflow_dispatch`, PIT seul) pour `minos-cli`, puis `minos-bootstrap`, puis `minos-runtime-local` ; relever pour chacun l'identifiant d'exécution, la durée et l'artefact de rapport.
- [ ] 2.2 **(manuelle)** [AUD-TST-11] Même lancement pour `minos-app` (avec `-am`, presque tout le reactor : le plus long) ; si le job n'aboutit pas dans son délai, le consigner comme tel plutôt que de retenir un rapport partiel.
- [ ] 2.3 **(manuelle)** [AUD-TST-11] Lancer le job du plugin IntelliJ de `code-audit.yml` ; relever si PIT aboutit (le statut « bloqué » date du 8 octobre).
- [ ] 2.4 [AUD-TST-11] Sur les rapports téléchargés : `python scripts/quality/audit-report-summary.py pit --all` (et `--module` par module) ; reporter dans le tableau du § 5.2 les lignes produites, le système (Ubuntu 24.04) et les identifiants d'exécution ; déclarer non analysées les classes propres à Windows de `minos-runtime-local` (D2). Preuve : sortie de la commande jointe au suivi ; aucun module « exécuté » sans rapport complet.

## 3. Plancher PIT (AUD-TST-11)

- [ ] 3.1 [AUD-TST-11] **Décision à obtenir** : D3 (aucun plancher avant des rapports complets pour les 14 modules et le plugin, puis plancher non bloquant instruit avec `etendre-audit-outille-a-tout-le-perimetre` — recommandé ; ou plancher immédiat sur les modules complets ; ou jamais). Consigner dans `design.md`.

## 4. Clôture

- [ ] 4.1 `docs/quality/code-audit.md` : procédure de reprise alignée sur le tableau (ordre des modules, runner éphémère, mise en garde sur le code qui modifie l'hôte). Rejouer `python scripts/docs/check-current-docs.py`.
- [ ] 4.2 Mettre à jour `docs/audit/2026-10-10/SUIVI.md` (AUD-TST-11 : troisième contradiction relevée au § 3.3, état par module, identifiants d'exécution ou « en attente », décision D3) ; y noter que `docs/audit/2026-10-10/findings.md` et `docs/audit/2026-10-10/sprints.md` citent « 3 sur 14 » comme objet du constat (rien à y corriger).
- [ ] 4.3 `openspec validate --all --strict` ; docs à mettre à jour avant archivage : `docs/STATUS.md`, `docs/quality/code-audit-couverture.md`, `docs/quality/code-audit.md`.
