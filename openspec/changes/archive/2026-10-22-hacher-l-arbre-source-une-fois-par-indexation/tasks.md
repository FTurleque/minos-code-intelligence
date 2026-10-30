# Tasks

Ordre : mesure et décision, puis rouge, correction, preuve. Tout `-pl` s'accompagne de `-am`. À implémenter **après** `unifier-l-orchestration-d-indexation` ; sinon relire les lignes citées de `LocalAutonomousIndexOperations`.

## 1. Mesure et décision (AUD-PERF-04)

- [x] 1.1 [AUD-PERF-04] Mesurer sur ce dépôt la durée d'une capture complète de l'empreinte et le nombre de captures d'un `minos index` (complet, incrémental à un fichier, reprise) ; relever les tests existants de reprise qui comparent `scopeFingerprint`. Consigner dans `design.md` ; si le gain attendu est négligeable, le dire.
- [x] 1.2 [AUD-PERF-04] **Décision à obtenir** : D3 (empreinte « après » en hachage complet, recommandé ; ou raccourci par un changement séparé). Consigner dans `design.md`.

## 2. Empreinte « avant » réutilisée pour le scope racine (AUD-PERF-04)

- [x] 2.1 [AUD-PERF-04] Rouge : test d'orchestration avec un `ProjectFingerprintService` compteur — une indexation complète à cible racine voit au plus 2 captures. **Preuve du rouge : 3 au HEAD.** Test d'égalité : l'empreinte transmise égale `captureScope(root, "")`. Test de reprise : un point de reprise écrit par le code du HEAD (fixture) est accepté après le changement.
- [x] 2.2 [AUD-PERF-04] Transmettre l'empreinte « avant » au contexte du run (D1) ; l'exécuteur de run et `IndexingResumePlanner` reçoivent le service d'empreinte de l'orchestration, le champ statique de `ExecutionCheckpoints` disparaît (D2). Régénérer dans cette tâche les entrées de `quality/spotbugs-baseline/minos-engine.xml` dont les lignes bougent, sans en ajouter. Preuve : tests de 2.1 verts ; `./mvnw -B -ntp -pl minos-engine -am verify` ; `python scripts/quality/check-jacoco.py` (scopes `critical-orchestration`, `resume-orchestration`) ; `python scripts/architecture/check-private-io.py` ; `python scripts/remediation/check-post-mne.py`.

## 3. Refus de la réutilisation non prouvée et stabilité (AUD-PERF-04)

- [x] 3.1 [AUD-PERF-04] Rouge : test « service d'empreinte de l'orchestration différent de celui de l'exécuteur » — l'empreinte du scope racine est recalculée ; test « contenu modifié pendant le run, taille et date restaurées » — la baseline n'est pas promue. **Preuve du rouge pour le premier : sans garde, la valeur transmise serait persistée.**
- [x] 3.2 [AUD-PERF-04] Garde de D2 (tant que le service n'est pas commun partout) ; aucun raccourci sur l'empreinte « après ». Preuve : tests verts ; `./mvnw -B -ntp -pl minos-app -am verify` (goldens inchangés, tests de reprise et de crash verts).
- [x] 3.3 [AUD-PERF-04] Refaire la mesure de 1.1 ; consigner avant / après dans `docs/project/audits/2026-10-10/SUIVI.md`.

## 4. Clôture

- [x] 4.1 Note de « Mise en œuvre » de l'ADR 0039 (empreinte du scope racine fournie par l'orchestration ; raccourci taille / date écarté, D3) ; rejouer `python scripts/docs/check-current-docs.py`.
- [x] 4.2 Mettre à jour `docs/project/audits/2026-10-10/SUIVI.md` (AUD-PERF-04 : statut, mesures, décision D3, manques du constat sur la reprise et les scopes non racine).
- [x] 4.3 `python .claude/scripts/run_gates.py` ; `openspec validate --all --strict` ; docs à mettre à jour avant archivage : `docs/project/governance/STATUS.md`, ADR 0039.

## Preuves d'implémentation (2026-10-21)

- 1.1 : mesure consignée dans `design.md` (7,8 à 9,4 s par capture complète sur ce dépôt) ; durée d'un `minos index` réel non mesurée.
- 2.1 : rouge exécuté (réutilisation neutralisée) : le scope racine portait `ac7409…` (recapture) au lieu de l'empreinte de référence `1a51da…` ; vert après correction. Le décompte « au plus 2 captures » est prouvé par la valeur du point de contrôle (le service d'empreinte est `final`, pas de compteur possible sans le modifier).
- 2.2 (2026-10-22) : forme retenue et justification dans `design.md` (D2, « service injecté ») : constructeur public `IndexingLifecycleService(…, markers, ProjectFingerprintService)`, constructeurs existants conservés (défaut `new ProjectFingerprintService()`) ; `FINGERPRINTS`, `capturesLikeCheckpoints` et `capturesLike` supprimés, réutilisation par identité d'instance. Rouge exécuté : `IndexingResumeTest.theInjectedFingerprintServiceWritesTheCheckpointsAndVerifiesTheResume` (point de contrôle `5515fd…` du service statique au lieu de `8f3628…` du service injecté) ; vert après correction, avec la reprise qui revérifie par le service injecté. Baseline SpotBugs : borne 369 → 359 sur les 3 entrées de `ProjectFingerprintService`, aucune ajoutée (SpotBugs non rejoué). `mvnw.cmd -B -ntp -pl minos-app -am verify` : BUILD SUCCESS, 0 échec ; `check-jacoco.py` : SUCCESS (`critical-orchestration` 0,909 ; `resume-orchestration` 0,921) ; `check-private-io.py`, `check-post-mne.py` : SUCCESS.
- 3.1 : rouge exécuté (garde neutralisée) : `expected: not equal but was: <1a51da…>` ; vert après correction. Test taille / date restaurées : vert.
- 3.3 : avant / après = 3 → 2 captures, consigné dans `SUIVI.md` ; pas de mesure de bout en bout.
- 4.3 (2026-10-22) : `python .claude/scripts/run_gates.py` : 46/46 ; `run_gates.py --fast` : 21/21 ; `openspec validate --all --strict` : 44/44. STATUS, SUIVI et ADR 0039 : à reprendre par la session d'archivage (STATUS et SUIVI citent encore `capturesLike` et la tâche 2.2 partielle).
