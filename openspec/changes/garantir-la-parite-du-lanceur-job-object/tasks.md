# Tasks

Ordre : comparaison et décisions, puis par section rouge, correction, preuve. Aucun fragment ni gabarit du moteur n'est modifié (`WindowsContainmentScriptTest` les fige). Les exigences de `specs/` décrivent l'option recommandée (D1-A) ; si le propriétaire choisit la génération au build, les réviser par `/opsx:update` avant d'implémenter.

## 1. Comparaison et décisions (AUD-QUA-01)

- [ ] 1.1 [AUD-QUA-01] Assembler le script du moteur (`scripts/remediation/windows_launcher.py`, fonction `assemble`) et le comparer à la copie du plugin : attendu au HEAD, 38 lignes propres au plugin et 42 propres au moteur ; classer chaque zone divergente (fragment ou gabarit ; forme ou fond). Consigner la table dans `design.md`.
- [ ] 1.2 [AUD-QUA-01] **Décision à obtenir** : D1 (gate de parité, recommandé ; ou génération au build Gradle, avec ADR). Consigner dans `design.md`.
- [ ] 1.3 [AUD-QUA-01] **Décision à obtenir** : D3 (nommer la divergence de construction de la ligne de commande et l'instruire dans un changement de `confinement-code-non-fiable`, recommandé ; ou l'aligner ici). Consigner dans `design.md` et, si elle est renvoyée, l'inscrire comme reste dans `SUIVI.md`.

## 2. Gate de parité (AUD-QUA-01)

- [ ] 2.1 [AUD-QUA-01] Rouge : `scripts/quality/test_check_job_object_launcher_parity.py` (nouveau), sur copies temporaires des vrais fichiers — fragment commun modifié d'un seul côté, différence hors liste, entrée de liste sans objet, maximum dépassé, fragment manquant : refusés ; dépôt réel : accepté. **Preuve du rouge : le gate n'existe pas ; puis, écrit avec une liste vide, il signale sur le dépôt réel chaque zone relevée en 1.1.**
- [ ] 2.2 [AUD-QUA-01] `scripts/quality/check-job-object-launcher-parity.py` (nouveau ; `gate_cli.py`, `--root`, sortie ASCII, chemins en `/`) : liste des fragments communs, liste fermée des différences assumées (D2) avec justification et maximum. Preuve : auto-test vert ; résultat identique sous Windows et sous Linux (dire lequel n'a tourné qu'en CI).
- [ ] 2.3 [AUD-QUA-01] Reprendre dans `windows-cli-job-owner-v1.ps1` la forme du moteur pour les écarts de pure forme (D4) ; resserrer la liste et ses maximums dans la même tâche. Preuve : gate vert avec la liste réduite ; diff de la ressource relu ligne à ligne (aucune ligne de fond) ; `./gradlew --no-daemon test` dans `minos-intellij` (les tests du lanceur ne s'exécutent que sous Windows : dire où ils ont tourné).
- [ ] 2.4 [AUD-QUA-01] Câbler l'auto-test et le gate dans `invariants` (`.github/workflows/pr-ci.yml`). Preuve : `python scripts/quality/check-ci-wiring.py`, `python scripts/quality/check-single-execution.py`, `python scripts/remediation/check-audit-remediation-v2.py` et `python scripts/quality/check-workflow-pins.py` verts ; `python .claude/scripts/run_gates.py --fast` vert ; `python scripts/remediation/check-minos-01.py`, `check-mnd.py`, `check-post-mne.py` et `check-post228-hardening.py` verts (l'assembleur partagé n'a pas changé de comportement).

## 3. Portée de la CI du plugin (AUD-QUA-01)

- [ ] 3.1 [AUD-QUA-01] Rouge : cas ajoutés à `scripts/ci/test_plugin_gate.py` — un fragment de `windows-fragments/` seul, le gabarit seul, le gate de parité seul → portée vraie. **Preuve du rouge : portée fausse au HEAD.**
- [ ] 3.2 [AUD-QUA-01] Étendre `PLUGIN_PATHS` de `scripts/ci/plugin-gate.py` (D5). Preuve : `python scripts/ci/test_plugin_gate.py` vert (chaque préfixe existe) ; `python scripts/quality/check-ci-wiring.py` vert.
- [ ] 3.3 **(manuelle)** [AUD-QUA-01] À la PR : `Static invariants (single run)` et `IntelliJ plugin (gate)` verts, job Windows du plugin exécuté ; noter les run ids.

## 4. Clôture

- [ ] 4.1 `docs/developer/quality-gates.md` : le gate de parité, la liste des différences assumées et la règle « on aligne dans la copie du plugin, jamais dans un fragment ». Rejouer `python scripts/docs/check-current-docs.py`.
- [ ] 4.2 Mettre à jour `docs/audit/2026-10-10/SUIVI.md` (AUD-QUA-01 : mesure 38 / 42 au lieu de 70, sens réel de la divergence, décisions D1 et D3, reste éventuel sur le moteur, run ids).
- [ ] 4.3 `openspec validate --all --strict` ; docs à mettre à jour avant archivage : `docs/STATUS.md`, `docs/developer/quality-gates.md`, `docs/user/intellij-plugin.md` si le lanceur y est décrit.
