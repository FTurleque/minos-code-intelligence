# Tasks

Ordre : rouge, correction, preuve. Un seul module touché : `minos-integration-git`.

## 1. Relevé et décision (AUD-SEC-10-suite)

- [x] 1.1 Relever au HEAD les surfaces qui rendent `workTree` et `originRemote`, la rédaction existante et ses trous (`design.md`, « État vérifié au HEAD »).
- [x] 1.2 **(manuelle)** Décision du propriétaire sur `workTree` : option « remote seul » (2026-10-28), consignée en D1.

## 2. Remote rendu (AUD-SEC-10-suite)

- [x] 2.1 Rouge : tests de `GitIntelligenceService` sur dépôts temporaires — URL HTTPS à identifiants (inchangée), URL non analysable à identifiants, chemin POSIX, chemin Windows, `file://`, chemin UNC, forme SCP, `ssh://` ; pour chaque remote tu : `originRemote == null`, limitation `ORIGIN_REMOTE_NOT_DISCLOSED`, aucune trace de l'identifiant ni du chemin dans la vue ; identité inchangée pour un remote local. Preuve du rouge sur le code actuel. — fait : `GitRemoteDisclosureTest` (13 cas) ; rouge : 7 échecs, dont `https://user:p ss@example.com/org/repo.git` rendu `https://user:p ss@example.com/org/repo` (mot de passe exposé) et les six remotes locaux rendus tels quels.
- [x] 2.2 Correction : `disclosedRemote` (D2) pour la valeur rendue, `sanitizeRemote` inchangée pour l'identité (D3), limitation additive (D4). Preuve : tests verts ; `./mvnw -B -ntp -pl minos-integration-git -am test` ; `cli-git.golden` identique (`-pl minos-app -am`, `A2SurfaceCharacterizationTest`) ; `python .claude/scripts/run_gates.py --fast`. — fait : `GitIntelligenceService.disclosedRemote` et le motif `SCP_REMOTE` ; 11 classes de test du module vertes ; `A2SurfaceCharacterizationTest` vert, `cli-git.golden` inchangé ; gates 25/25.

## 3. Documentation

- [x] 3.1 `docs/user/cli.md` et `docs/user/java-api.md` : forme rendue d'`originRemote`, limitation `ORIGIN_REMOTE_NOT_DISCLOSED`, `workTree` rendu en chemin absolu par décision ; `docs/developer/multi-repo-git.md`. Rejouer `python scripts/docs/check-current-docs.py`.

## 4. Clôture

- [x] 4.1 `docs/project/audits/2026-10-10/SUIVI.md` : ligne AUD-SEC-10-suite (statut, PR, preuve) ; `openspec validate --all --strict`. — fait : PR #594 ; validation 36/36.
