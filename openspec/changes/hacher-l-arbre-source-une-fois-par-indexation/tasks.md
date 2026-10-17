# Tasks

Ordre : mesure et décision, puis rouge, correction, preuve. Tout `-pl` s'accompagne de `-am`. À implémenter **après** `unifier-l-orchestration-d-indexation` ; sinon relire les lignes citées de `LocalAutonomousIndexOperations`.

## 1. Mesure et décision (AUD-PERF-04)

- [ ] 1.1 [AUD-PERF-04] Mesurer sur ce dépôt la durée d'une capture complète de l'empreinte et le nombre de captures d'un `minos index` (complet, incrémental à un fichier, reprise) ; relever les tests existants de reprise qui comparent `scopeFingerprint`. Consigner dans `design.md` ; si le gain attendu est négligeable, le dire.
- [ ] 1.2 [AUD-PERF-04] **Décision à obtenir** : D3 (empreinte « après » en hachage complet, recommandé ; ou raccourci par un changement séparé). Consigner dans `design.md`.

## 2. Empreinte « avant » réutilisée pour le scope racine (AUD-PERF-04)

- [ ] 2.1 [AUD-PERF-04] Rouge : test d'orchestration avec un `ProjectFingerprintService` compteur — une indexation complète à cible racine voit au plus 2 captures. **Preuve du rouge : 3 au HEAD.** Test d'égalité : l'empreinte transmise égale `captureScope(root, "")`. Test de reprise : un point de reprise écrit par le code du HEAD (fixture) est accepté après le changement.
- [ ] 2.2 [AUD-PERF-04] Transmettre l'empreinte « avant » au contexte du run (D1) ; l'exécuteur de run et `IndexingResumePlanner` reçoivent le service d'empreinte de l'orchestration, le champ statique de `ExecutionCheckpoints` disparaît (D2). Régénérer dans cette tâche les entrées de `quality/spotbugs-baseline/minos-engine.xml` dont les lignes bougent, sans en ajouter. Preuve : tests de 2.1 verts ; `./mvnw -B -ntp -pl minos-engine -am verify` ; `python scripts/quality/check-jacoco.py` (scopes `critical-orchestration`, `resume-orchestration`) ; `python scripts/architecture/check-private-io.py` ; `python scripts/remediation/check-post-mne.py`.

## 3. Refus de la réutilisation non prouvée et stabilité (AUD-PERF-04)

- [ ] 3.1 [AUD-PERF-04] Rouge : test « service d'empreinte de l'orchestration différent de celui de l'exécuteur » — l'empreinte du scope racine est recalculée ; test « contenu modifié pendant le run, taille et date restaurées » — la baseline n'est pas promue. **Preuve du rouge pour le premier : sans garde, la valeur transmise serait persistée.**
- [ ] 3.2 [AUD-PERF-04] Garde de D2 (tant que le service n'est pas commun partout) ; aucun raccourci sur l'empreinte « après ». Preuve : tests verts ; `./mvnw -B -ntp -pl minos-app -am verify` (goldens inchangés, tests de reprise et de crash verts).
- [ ] 3.3 [AUD-PERF-04] Refaire la mesure de 1.1 ; consigner avant / après dans `docs/project/audits/2026-10-10/SUIVI.md`.

## 4. Clôture

- [ ] 4.1 Note de « Mise en œuvre » de l'ADR 0039 (empreinte du scope racine fournie par l'orchestration ; raccourci taille / date écarté, D3) ; rejouer `python scripts/docs/check-current-docs.py`.
- [ ] 4.2 Mettre à jour `docs/project/audits/2026-10-10/SUIVI.md` (AUD-PERF-04 : statut, mesures, décision D3, manques du constat sur la reprise et les scopes non racine).
- [ ] 4.3 `python .claude/scripts/run_gates.py` ; `openspec validate --all --strict` ; docs à mettre à jour avant archivage : `docs/project/governance/STATUS.md`, ADR 0039.
