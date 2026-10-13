# Tasks

Ordre dans chaque section : rouge, correction, preuve. Commentaires seulement : aucune ligne de code n'est modifiée. Pas de traduction en masse. Indépendant des autres changements du sprint.

## 1. Relevé et décision (AUD-QUA-11)

- [ ] 1.1 [AUD-QUA-11] Rejouer la commande de l'audit et dresser la liste nominative des fichiers mixtes par module ; relever les mots ambigus qui faussent le décompte (noms propres, termes techniques). Consigner la liste et les décomptes dans `design.md`.
- [ ] 1.2 [AUD-QUA-11] Mesurer la sensibilité aux lignes : modifier un commentaire d'un fichier présent dans `quality/spotbugs-baseline/` sur une branche jetable et rejouer le contrôle SpotBugs du module ; rechercher dans `scripts/` les chaînes exigées qui se trouvent dans un commentaire d'un fichier mixte. Consigner le résultat et la consigne qui en découle pour les lots (régénérer la baseline, ou conserver le nombre de lignes).
- [ ] 1.3 [AUD-QUA-11] **Décision à obtenir** : D1 (A : anglais pour tout le code, recommandé ; B : par module ; C : français sauf `minos-api`), et inclusion ou non de `minos-intellij`. Consigner dans `design.md`.

## 2. Règle écrite et gate (AUD-QUA-11)

- [ ] 2.1 [AUD-QUA-11] Rouge : créer `scripts/quality/check-comment-language.py` avec `--self-test` sur arbres temporaires (fichier mixte hors liste refusé, fichier listé introuvable refusé, fichier listé non mixte refusé, module non déclaré refusé, lettre accentuée dans un littéral ignorée, lecture UTF-8) et le câbler dans `invariants` (`.github/workflows/pr-ci.yml`). **Preuve du rouge : avec une liste vide, le gate échoue sur le dépôt réel en nommant les fichiers mixtes de 1.1.**
- [ ] 2.2 [AUD-QUA-11] Créer `scripts/quality/comment-language.json` (langue par module selon D1, liste initiale de 1.1) ; écrire la règle dans `CONTRIBUTING.md` et aligner la phrase de `AGENTS.md` (D5). Preuve : gate vert ; `python scripts/quality/check-ci-wiring.py` et `python scripts/quality/check-single-execution.py` verts ; `python scripts/docs/check-current-docs.py` vert.

## 3. Reprise des fichiers mixtes (AUD-QUA-11)

- [ ] 3.1 [AUD-QUA-11] Premier lot : un module, quelques fichiers, commentaires seulement (D4) ; retirer leurs entrées de la liste dans le même commit. **Preuve du rouge : retirer une entrée avant de reprendre le fichier fait échouer le gate.** Preuve : gate vert ; `./mvnw -B -ntp -pl <module> -am test` ; `python .claude/scripts/run_gates.py` (gates littéraux et `check-private-io.py`) ; baseline SpotBugs selon 1.2.
- [ ] 3.2 [AUD-QUA-11] Lots suivants, un module à la fois, même preuve, jusqu'à liste vide. Chaque lot note dans `SUIVI.md` le nombre d'entrées restantes. La tâche reste ouverte tant que la liste n'est pas vide ; le changement peut être archivé avec une liste non vide si le propriétaire l'accepte (le dire en clôture).

## 4. Clôture

- [ ] 4.1 `docs/developer/quality-gates.md` : le gate, son critère (D2) et sa liste. Rejouer `python scripts/docs/check-current-docs.py`.
- [ ] 4.2 Mettre à jour `docs/audit/2026-10-10/SUIVI.md` (AUD-QUA-11 : décomptes au HEAD, décision D1, entrées restantes, résultat de 1.2) ; noter Q18 comme traité ou partiellement traité.
- [ ] 4.3 `openspec validate --all --strict` ; docs à mettre à jour avant archivage : `CONTRIBUTING.md`, `AGENTS.md`, `docs/developer/quality-gates.md`.
