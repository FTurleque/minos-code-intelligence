# Tasks

Ordre : rouge, correction, preuve. Les workflows de release ne tournent pas sur une PR : la preuve avant fusion est statique (gate, `yaml.safe_load`) ; la preuve réelle est une tâche manuelle. À fusionner avant `attester-et-signer-la-release-windows` (même fichier `release-windows.yml`).

## 1. Décision et gate (AUD-DEP-07)

- [ ] 1.1 [AUD-DEP-07] **Décision à obtenir** : D3 (contrôle après publication, recommandé, ou plugin attaché avant publication). Consigner dans `design.md`.
- [ ] 1.2 [AUD-DEP-07] Rouge : cas d'auto-test de `scripts/quality/check-ci-wiring.py` sur arbres temporaires — workflow de release sans appel du plugin (refusé), workflow du plugin au seul déclencheur `release:` (refusé), configuration cible (acceptée). **Preuve du rouge : avec la règle, le dépôt réel échoue sur les deux points.**

## 2. Workflows (AUD-DEP-07)

- [ ] 2.1 [AUD-DEP-07] `intellij-plugin-release.yml` : déclencheurs `workflow_call` et `workflow_dispatch` avec l'entrée `tag` (et `commit` pour l'appel) ; les huit lectures de `github.event.release.*` remplacées par des valeurs calculées une fois ; contrôles conservés (tag → commit cible, tag non déplacé, envoi sans écrasement). Preuve : `yaml.safe_load` ; `python scripts/quality/check-workflow-pins.py`, `check-toolchain-baseline.py` verts.
- [ ] 2.2 [AUD-DEP-07] `release-windows.yml` : job `plugin` (`needs: publish`, `uses: ./.github/workflows/intellij-plugin-release.yml`, `permissions: contents: write`) et contrôle final de présence de l'asset (D3). Preuve : gate de 1.2 vert ; `python scripts/docs/check-current-docs.py` vert (`workflow_dispatch` présent, pas de `pull_request:`) ; `python scripts/quality/check-single-execution.py` vert.

## 3. Preuve réelle

- [ ] 3.1 **(manuelle)** [AUD-DEP-07] Lancer `intellij-plugin-release.yml` par `workflow_dispatch` sur un tag de test créé pour l'occasion sur une branche jetable (ou selon le choix du propriétaire) : contrôle du tag, construction, attache ; relancer pour vérifier le refus d'écrasement. Noter les run ids dans `SUIVI.md`, puis supprimer la release et le tag de test.
- [ ] 3.2 **(manuelle)** [AUD-DEP-07] À la prochaine release : relever que le job `plugin` s'exécute, que l'asset et sa somme sont présents, que le workflow est vert. Tant que ce n'est pas fait, AUD-DEP-07 reste « implémenté, preuve de release en attente ».
- [ ] 3.3 **(manuelle, décision du propriétaire)** [AUD-DEP-07] Rattraper ou non v1.1.0 et v1.2.0 par le rejeu (ajoute un asset à une release publiée) ; consigner la décision dans `SUIVI.md`.

## 4. Clôture

- [ ] 4.1 Documentation de release (`docs/developer/`, skill `minos-release-promotion`) : le plugin est publié par le workflow de release ; une release créée à la main demande un rejeu ; note sur les releases immuables. Rejouer `check-current-docs.py`.
- [ ] 4.2 Mettre à jour `docs/audit/2026-10-10/SUIVI.md` (AUD-DEP-07 : statut, preuve empirique des deux releases sans plugin, décision D3, run ids).
- [ ] 4.3 `openspec validate --all --strict` ; docs à mettre à jour avant archivage : `docs/STATUS.md`, documentation de release.
