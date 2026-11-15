# Tasks

## 1. Décision

- [ ] 1.1 **(manuelle)** AUD-SEC-01 — Le propriétaire tranche D1 du design (option A : pilotes de l'utilisateur appliqués au clone distant ; option B recommandée : aucun pilote exécuté). Si A est retenue, remplacer l'exigence `analyse-git › La matérialisation d'un dépôt distant n'exécute aucun pilote de filtre` par `/opsx:update` avant toute tâche du groupe 2 ; vérifier que le design porte la décision datée
- [ ] 1.2 AUD-SEC-01 — Écrire l'ADR `SEC-<prochain numéro>` « Filtres Git à la matérialisation d'un dépôt distant » (commande `/minos:adr` ; numéro relu dans `docs/architecture/adr/README.md`) : contexte, options A et B, décision, conséquences pour git-lfs ; l'ajouter à l'index et à `docs/architecture/arc42/09-decisions.md` ; vérifier par `python .claude/scripts/run_gates.py --fast`

## 2. Matérialisation distante par l'ouverture de confiance

- [ ] 2.1 AUD-SEC-01 — Rouge : écrire `RemoteMaterializationFilterTest` dans `minos-integration-git` (JVM fille au répertoire personnel redirigé, sur le modèle de `GitIntelligenceTrustTest.UserConfigurationProbe`) : dépôt de fixture local avec `* filter=x`, pilote `x` déclaré par la configuration d'utilisateur de test, fichier témoin ; cas clone, cas `useJGitBuiltin = true`, cas réutilisation du cache, cas propriétaire inattendu (`analyse-git › La matérialisation d'un dépôt distant n'exécute aucun pilote de filtre`, `analyse-git › La revalidation d'une extraction distante passe par l'ouverture de confiance`). Extraire d'abord, sans changer le comportement, la méthode de portée paquet de `JGitRemoteGitClient` qui reçoit l'URI (écart E3) et le constructeur de portée paquet du matérialiseur qui reçoit le fournisseur de propriétaire attendu (écart E6). Vérifier que le test échoue parce que le témoin existe, et noter dans le design le comportement mesuré de JGit
- [ ] 2.2 AUD-SEC-01 — Correction : sortir l'ouverture de confiance de `GitIntelligenceService` vers un type de portée paquet à deux politiques de filtres, qui écrit `useJGitBuiltin = false` sur chaque pilote redirigé (D2, écart E5) ; cloner sans extraction puis extraire sur le dépôt ouvert en politique « distant » (D3). Vérifier : le cas clone de 2.1 passe ; `GitIntelligenceTrustTest` (dont `aFilterOfTheUserConfigurationIsStillApplied`), `JGitRemoteRepositoryMaterializerTest` et `JGitCloneDeadlineTest` restent verts sans changement d'attente ; rejouer `python scripts/quality/check-remote-distributed-consistency.py`, `python scripts/architecture/check-module-boundaries.py`, `python scripts/quality/check-comment-language.py`
- [ ] 2.3 AUD-SEC-01 — Correction : `validateCheckout` ouvre par l'ouverture de confiance, après le clone et à la réutilisation du cache (`analyse-git › La revalidation d'une extraction distante passe par l'ouverture de confiance`). Vérifier : les cas cache et propriétaire de 2.1 passent ; `./mvnw -B -ntp -pl minos-integration-git -am test` vert ; `GitRefusalMessagePathTest` vert (aucun chemin absolu dans un refus)
- [ ] 2.4 AUD-SEC-01 — Preuve : après `./mvnw -B -ntp verify`, rejouer `python scripts/quality/check-jacoco.py` et vérifier que le scope qui nomme `com/minos/integration/git/JGitRemoteRepositoryMaterializer` et `JGitCloneDeadline` tient ses planchers ; ajouter le nouveau type à ce scope s'il porte le contrôle du propriétaire
- [ ] 2.5 AUD-SEC-01 — Documentation : `docs/user/remote-indexing.md` (contenu brut des fichiers à filtre, git-lfs), `docs/architecture/diagrams/capacites/analyse-git.md` (vue de l'ouverture à deux politiques, traçabilité), `.claude/rules/securite.md` règle 5 (type d'ouverture, gate) ; vérifier les liens et `python scripts/docs/check-current-docs.py`

## 3. Garde contre l'ouverture directe

- [ ] 3.1 AUD-SEC-01 — Rouge : après la fusion de `doter-chaque-gate-d-un-auto-test` (AUD-TST-09, qui donne `--self-test` à `scripts/quality/check-remote-distributed-consistency.py`), ajouter à sa table de mutations les cas « `Git.open(` dans un fichier quelconque de `minos-integration-git/src/main` », « `new FileRepositoryBuilder` hors du type d'ouverture » et « type d'ouverture exempté » (`analyse-git › Aucun dépôt analysé n'est ouvert hors de l'ouverture de confiance`) ; vérifier que `python scripts/quality/check-remote-distributed-consistency.py --self-test` échoue sur les deux premiers tant que le gate ne parcourt pas le module
- [ ] 3.2 AUD-SEC-01 — Correction : ajouter au gate le parcours de `minos-integration-git/src/main` et l'exemption nominative du type d'ouverture (D4) ; vérifier que `--self-test` passe et que `python scripts/quality/check-remote-distributed-consistency.py` et `python scripts/remediation/check-post-mne.py` rendent 0 sur le dépôt ; rejouer `python scripts/quality/check-ci-wiring.py` et `python .claude/scripts/run_gates.py`

## 4. Clôture

- [ ] 4.1 Mettre à jour la matrice de traçabilité du design (exigence → scénario → conception ou diagramme → tâche → test ou preuve) en nommant les preuves manquantes ; vérifier qu'aucune ligne n'affirme une couverture sans preuve
- [ ] 4.2 Mettre à jour le dossier durable `docs/architecture/diagrams/capacites/analyse-git.md` ; vérifier par `python scripts/docs/check-capability-dossiers.py`
- [ ] 4.3 Valider les spécifications ; vérifier par `openspec validate --all --strict`
- [ ] 4.4 Lister les documents à mettre à jour avant archivage (STATUS, ROADMAP, `docs/user/remote-indexing.md`, arc42 § 9, ADR `SEC-*`, `SUIVI.md` de l'audit du 2026-11-15) ; vérifier que chacun est fait ou nommé comme reste

## Workflow follow-up

- Archiver le changement après revue (`openspec archive couvrir-le-clone-distant-par-l-ouverture-de-confiance -y`), puis vérifier que la spec de référence garde sa section `## Documentation liée`.
