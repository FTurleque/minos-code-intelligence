# Tasks

Changement indépendant des quatre autres du sprint 12. Il partage seulement `docs/architecture/diagrams/capacites/frontieres-de-modules.md` (ligne de traçabilité, à l'archivage) avec `lire-les-methodes-generiques-de-la-facade-dans-a11` et `retirer-les-nombres-du-dossier-des-frontieres` : le dernier archivé rebase le dossier.

## 1. Tables de rupture de l'API Java (AUD-QUA-09)

- [ ] 1.1 AUD-QUA-09 — Rouge : exécuter `git grep -n 'projectIndexing(executorDecorator)' -- docs/user/java-api.md` ; vérifier qu'il rend la ligne 372 (code 0). Consigner dans la PR. Exigence : frontieres-de-modules › La table de rupture de la façade ne promet aucun remplaçant absent.
- [ ] 1.2 AUD-QUA-09 — Correction (D1, option a) : à `docs/user/java-api.md:372`, remplacer le remplaçant par « aucun remplaçant public : la décoration des exécuteurs est réservée aux tests (`MinosApplicationTestAccess` du `test-jar`) » ; titres des sections `:365`, `:380` et `:390` : « prochaine version » au lieu de « version 1.3.0 » (écart 1). Relire les autres lignes de la table contre `MinosApplication.java` et `RemoteIndexingUseCase` au HEAD.
- [ ] 1.3 AUD-QUA-09 — `docs/user/release-notes/unreleased.md` : un paragraphe « API Java : fabriques statiques des cas d'usage retirées » qui renvoie à la table de `java-api.md` et dit que la décoration des exécuteurs n'a pas de remplaçant public, et une phrase sur les méthodes abstraites ajoutées au port interne `MinosApplicationComposer` (sections `:380` et `:390`) (écart 2).
- [ ] 1.4 AUD-QUA-09 — Preuve : la commande de 1.1 ne rend aucune ligne (code 1) ; `git grep -nE 'retentionService\(\)` \| `new LocalProjectOperations' -- docs/user/java-api.md` toujours vide ; liens des deux pages vérifiés ; `python scripts/docs/check-current-docs.py` et `python scripts/quality/check-toolchain-baseline.py` verts.

## 2. Décompte des documents d'état (AUD-QUA-10)

- [ ] 2.1 AUD-QUA-10 — Rouge : `git grep -nE "[0-9]+ cas\b" -- docs/project/governance/STATUS.md docs/project/governance/ROADMAP.md` rend `STATUS.md:19` et `ROADMAP.md:23` (code 0). Exigence : controles-de-fusion › Les documents d'état ne citent aucun décompte d'auto-test non relu.
- [ ] 2.2 AUD-QUA-10 — Correction (D2, option a) : retirer « (154 cas) » aux deux lignes, qui gardent « testée règle par règle ».
- [ ] 2.3 AUD-QUA-10 — Preuve : la commande de 2.1 ne rend aucune ligne (code 1) ; `python scripts/docs/check-current-docs.py`, `python scripts/docs/product-facts.py --check`, `python scripts/quality/check-audit-remediation-v2.py`, `python scripts/quality/check-post228-hardening.py` (ils lisent aussi les deux documents), `python .claude/scripts/run_gates.py --fast` verts. Aucun scope JaCoCo.

## 3. Clôture

- [ ] 3.1 AUD-QUA-09, AUD-QUA-10 — Mettre à jour la matrice de traçabilité du design (preuves exécutées).
- [ ] 3.2 AUD-QUA-09, AUD-QUA-10 — Valider : `openspec validate --all --strict` vert ; après archivage, ligne de l'exigence ajoutée dans `docs/architecture/diagrams/capacites/controles-de-fusion.md` et ligne de l'exigence modifiée relue dans `frontieres-de-modules.md` ; `python scripts/docs/check-capability-dossiers.py` vert. Compter les caractères de l'exigence `MODIFIED` (≤ 500) avant l'archivage.
- [ ] 3.3 AUD-QUA-09, AUD-QUA-10 — **(manuelle)** Documents à mettre à jour avant archivage : `java-api.md` (1.2), `unreleased.md` (1.3), `STATUS.md` et `ROADMAP.md` (2.2), lignes AUD-QUA-09 et AUD-QUA-10 du suivi de l'audit, et correction de la phrase « `ProjectInspectionService.view` s'appelle `statusView` » de `SUIVI.md:169` (écart 3), par le coordinateur après fusion.
