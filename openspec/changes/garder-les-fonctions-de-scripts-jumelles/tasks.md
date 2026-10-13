# Tasks

Ordre dans chaque section : rouge, correction, preuve. Les `.ps1` restent en CRLF ; aucun script de jalon gelé (ADR 0043) ni de `scripts/history/` n'est modifié. Indépendant des autres changements du sprint.

## 1. Relevé et décision (AUD-QUA-09)

- [ ] 1.1 [AUD-QUA-09] Comparer ligne à ligne les deux `Require-Identifier` et les deux `Invoke-NativeChecked` de `scripts/release/` ; dire pour chacun si la différence est voulue. Rechercher dans les scripts vivants (hors `scripts/history/` et hors scripts gelés) toute autre fonction définie sous le même nom dans deux fichiers. Relever les contrôles du contenu du paquet Windows. Établir le statut (gelé ou vivant) de `scripts/m14/validate-local.ps1` et de `scripts/m15/run-final.ps1`. Consigner dans `design.md`.
- [ ] 1.2 [AUD-QUA-09] **Décision à obtenir** : D1 (garde d'identité, recommandée, ou bibliothèque PowerShell livrée). Consigner dans `design.md`. Si B est retenue, réviser les exigences et les tâches par `/opsx:update` avant d'implémenter.

## 2. Garde d'identité (AUD-QUA-09)

- [ ] 2.1 [AUD-QUA-09] Rouge : créer `scripts/quality/test_twin_script_functions.py` (D5) avec la liste `Read-BoundedUtf8`, `Require-Identifier`, `Test-CeilingOverride`, `Get-PreservedCeilingOverrides`, et ses propres cas sur arbres temporaires (corps différents, fonction absente, fichier introuvable, CRLF contre LF) ; le câbler dans `invariants` (`.github/workflows/pr-ci.yml`). **Preuve du rouge : le test échoue sur le dépôt réel en nommant `Require-Identifier` et ses deux fichiers.**
- [ ] 2.2 [AUD-QUA-09] `Require-Identifier` : aligner les deux corps sur le plus strict, ou renommer l'un des deux et retirer la paire de la liste (D2), selon 1.1. Preuve : test 2.1 vert ; `python scripts/quality/check-ci-wiring.py` et `python scripts/quality/check-single-execution.py` verts ; `python scripts/remediation/check-post-mne.py` vert ; `python scripts/install/test_secret_file_acl.py` (ne s'exécute que sous Windows : dire où il a tourné).
- [ ] 2.3 [AUD-QUA-09] Paire `Invoke-NativeChecked` de `scripts/release/` : alignée et ajoutée à la liste si 1.1 montre une différence accidentelle (D3) ; sinon raison écrite dans `design.md` et aucune modification. Preuve : test 2.1 vert.
- [ ] 2.4 **(manuelle)** [AUD-QUA-09] Si 2.2 ou 2.3 modifie un script livré ou un script de release : sur un poste Windows, exécuter `scripts/install/configure-runtime-settings.ps1` sur une installation de test et relever le résultat ; pour `scripts/release/`, la preuve vient du prochain candidat de release (noter le run id dans `SUIVI.md`).

## 3. Module Python partagé (AUD-QUA-09)

- [ ] 3.1 [AUD-QUA-09] Rouge : dans les auto-tests des deux gates, un cas qui affirme que `find_ci_wiring_violations` vient du module partagé. **Preuve du rouge : les deux auto-tests échouent tant que la fonction est définie localement.**
- [ ] 3.2 [AUD-QUA-09] Créer `scripts/quality/_ci_wiring.py` et y déplacer la fonction ; `check-compose-limits.py` et `check-image-pins.py` l'importent, sans repli (D4). Preuve : `python scripts/quality/check-compose-limits.py` et `python scripts/quality/check-image-pins.py` verts, avec et sans `--self-test` ; `python scripts/quality/check-ci-wiring.py` vert (le module partagé n'est ni un `test_*.py` ni un script à `--self-test`) ; `python .claude/scripts/run_gates.py`.

## 4. Clôture

- [ ] 4.1 `docs/developer/quality-gates.md` : la garde d'identité, sa liste, le module partagé. Rejouer `python scripts/docs/check-current-docs.py`.
- [ ] 4.2 Mettre à jour `docs/audit/2026-10-10/SUIVI.md` (AUD-QUA-09 : décomptes corrigés, `Require-Identifier` divergent, `Resolve-Python` sans objet, décision D1, sort de `Invoke-NativeChecked`).
- [ ] 4.3 `openspec validate --all --strict` ; docs à mettre à jour avant archivage : `docs/developer/quality-gates.md` ; `docs/user/production-installation.md` seulement si D1 retient la bibliothèque livrée.
