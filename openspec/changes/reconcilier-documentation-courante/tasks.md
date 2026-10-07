# Tasks

Changement de documentation seule : aucune exigence (`skip_specs: true`), donc aucune tâche ne
cite d'exigence. Chaque tâche cite ses constats `MINOS-AUD-GNN` et sa validation observable.

Règles communes à toutes les tâches :

- Relire chaque affirmation corrigée contre sa source **au moment de l'édition** (fichier ou
  commande indiqués) ; ne pas recopier un chiffre de ce fichier.
- Après chaque tâche, rejouer les trois gates documentaires, qui affirment des chaînes
  littérales : `python scripts/docs/check-current-docs.py` (succès attendu :
  « MINOS CURRENT DOCUMENTATION CONSISTENCY SUCCESS »), `python scripts/docs/product-facts.py --check`
  (« M15 PRODUCT FACTS CONSISTENCY SUCCESS ») et
  `python scripts/quality/check-milestone-artifact-references.py` (« MILESTONE ARTIFACT REFERENCE
  GATE SUCCESS »). Ne pas lancer `product-facts.py` sans `--check` : il réécrit
  `docs/generated/product-facts.md`.
- Ne jamais supprimer ni reformuler les lignes qui portent le SHA, l'URL, « 10 assets »,
  « 5 paires », « #98 » ou « C0 → M30 » (affirmés par `check-current-docs.py`) ; ne pas modifier
  la ligne `Version : …` de `docs/architecture/README.md` (affirmée par `product-facts.py`).
- Aucun fichier `docs/adr/00NN-*.md` n'est modifié (voir Questions ouvertes du design).
- Aucune tâche ne renomme ou ne déplace de code : aucun scope JaCoCo à rejouer.

## 1. Toolchain Maven : faits d'état (MINOS-AUD-G11)

- [ ] 1.1 [MINOS-AUD-G11] Dans `docs/STATUS.md` (section « Supply-chain et toolchains ») et `docs/ROADMAP.md` (même section), remplacer « Maven Wrapper : Maven 3.9.16 » / « wrapper Maven 3.9.16 » par la version du wrapper ; dans `docs/user/docker-runtime.md`, corriger l'affirmation que le SHA-256 Maven de l'image est « identique au checksum du Maven Wrapper » (l'image reste en Maven 3.9.16). Ne pas toucher à `docs/TOOLCHAIN_POLICY.md`, aux ADR ni à « Maven 3.9.x » comme plage de politique (Q5). Sources à relire : `.mvn/wrapper/maven-wrapper.properties` (`distributionUrl`, `distributionSha256Sum`), `pom.xml` (`requireMavenVersion`), `docker/Dockerfile.mcp.release` (`MAVEN_VERSION`, `MAVEN_ZIP_SHA256`), `embedded-tools.json`. Validation : `Get-Content .mvn/wrapper/maven-wrapper.properties` et `Select-String -Path docker/Dockerfile.mcp.release -Pattern 'MAVEN_'` confirment chaque valeur citée ; `Select-String -Path docs/STATUS.md,docs/ROADMAP.md,docs/user/docker-runtime.md -Pattern '3\.9\.16|3\.10'` ne renvoie plus de wrapper en 3.9.16 ni de SHA « identique » ; les trois gates sont rejoués.

## 2. docs/STATUS.md (MINOS-AUD-G01, G15, G19)

- [ ] 2.1 [MINOS-AUD-G01] Remplacer l'unique « Dernière mise à jour : 31 août 2026 » par deux dates explicites (état produit revérifié, planification du 5 octobre) selon design D3. Source : `git log -1 --format=%cs -- docs/STATUS.md` et les sections datées du fichier. [MINOS-AUD-G15] Corriger « deux jobs » : relire `.github/workflows/pr-ci.yml` (jobs `vulnerability-scan`, `invariants`, `verify`) et décrire le nombre réel sans retirer les deux puces existantes. [MINOS-AUD-G19] « snapshots structurés v1/v2 » devient « v1/v2/v3 » (source : `minos-storage-local/.../KnowledgeSnapshotCodecs.java`, `SnapshotBinaryCodecSupport.MAX_PERSISTED_SNAPSHOT_BYTES` commun aux formats). Validation : `Select-String -Path .github/workflows/pr-ci.yml -Pattern '^  [a-z-]+:$'` énumère les jobs cités ; les trois gates sont rejoués.

## 3. docs/ROADMAP.md (MINOS-AUD-G01, G12, G13, G14, G19)

- [ ] 3.1 [MINOS-AUD-G12] Supprimer ou corriger le paragraphe « La ligne de développement courante est 1.1.0-SNAPSHOT. Aucune release 1.1.0 n'est publiée » (section « Release 1.0.1 — publiée ») : ligne courante = `<revision>` du POM (relire `pom.xml`) ; releases publiées relues par `git ls-remote --tags origin`. [MINOS-AUD-G13] Sortir l'ADR 0039 de « Travaux ouverts en conception » (« conception proposée ») : statut du fichier « Accepted — implémenté au sprint 2 » ; preuve de code `IndexingResumePlanner` (`minos-engine`) et `IndexingRun.Status.INTERRUPTED`. [MINOS-AUD-G14] Réécrire la sous-section « Post-228 — invariants statiques ciblés » comme `docs/STATUS.md` : workflow retiré, invariants dans le job `invariants` de `pr-ci.yml` (relire `ls .github/workflows`) ; conserver les SHA historiques Post-#228. [MINOS-AUD-G01] Même séparation de dates que la tâche 2.1 pour l'en-tête « Statut au 31 août 2026 ». [MINOS-AUD-G19] « snapshots v1/v2 » devient « v1/v2/v3 ». Validation : `Get-ChildItem .github/workflows` ne contient aucun `post-228-hardening.yml` ; `Select-String -Path docs/ROADMAP.md -Pattern '1\.1\.0-SNAPSHOT|conception proposée'` ne renvoie plus que les lignes légitimes relues ; les trois gates sont rejoués.

## 4. README.md (MINOS-AUD-G16)

- [ ] 4.1 [MINOS-AUD-G16] Compléter le bloc « État courant » (aujourd'hui arrêté à 1.0.1) avec les releases 1.1.0 et 1.2.0 et la ligne de développement, ou le faire renvoyer à `docs/STATUS.md`, sans retirer les lignes affirmées par `check-current-docs.py` et `product-facts.py` ; remplacer « avec `minos-app` comme composition root » par la formulation de l'ADR-0042 (racine de composition dans `minos-bootstrap`, `minos-app` = assemblage final distribué : lanceur, JAR ombré, backends optionnels). Sources à relire : `docs/adr/0042-racine-de-composition.md` (décision et option (c)), `pom.xml` (`<module>`), `minos-bootstrap/.../DefaultMinosApplicationComposer.java`, `git ls-remote --tags origin v1.1.0 v1.2.0` (SHA à comparer à `docs/STATUS.md`). Validation : `python scripts/docs/check-current-docs.py` et `python scripts/docs/product-facts.py --check` réussissent (README affirmé par les deux) ; `Select-String README.md -Pattern 'composition root'` ne renvoie plus `minos-app` comme racine.

## 5. Documentation d'architecture (MINOS-AUD-G01, G09, G17)

- [ ] 5.1 [MINOS-AUD-G17] Mettre à jour `docs/architecture/arc42/02-contraintes.md` (CT-3 « 12 modules enfants + parent »), `arc42/04-strategie-solution.md` (§ 4.2 : liste de modules sans `minos-bootstrap` ni `minos-storage-postgresql`, et « minos-app ← composition root »), `arc42/05-vue-blocs.md` (aucune occurrence de `minos-bootstrap` : l'ajouter au diagramme de conteneurs) et `docs/architecture/SYNTHESE.md` lignes « 13 projets (12 enfants + parent) » et « `minos-app` est le seul composition root ». Sources : `pom.xml` (compter les `<module>` au moment de l'édition), ADR-0042, `docs/architecture/diagrams/module-dependencies.md` (déjà à jour pour `minos-bootstrap`), `python scripts/architecture/check-module-boundaries.py` (nombre de modules affiché). [MINOS-AUD-G09] Dans `SYNTHESE.md`, corriger « 37 ADR … jusqu'à M29 » et « index de 37 ADR » une fois la tâche 6.1 faite. [MINOS-AUD-G01] `docs/architecture/README.md` : mettre à jour uniquement la ligne « Dernière mise à jour » (pas la ligne `Version :`). Validation : `python scripts/architecture/check-module-boundaries.py` affiche le nombre de modules écrit dans les documents ; les trois gates sont rejoués.

## 6. arc42/09 : index des ADR (MINOS-AUD-G09)

- [ ] 6.1 [MINOS-AUD-G09] Ajouter à `docs/architecture/arc42/09-decisions.md` les 20 lignes manquantes (ADR-0038 à 0057) à partir de `docs/adr/README.md` ; corriger les dates divergentes en relisant l'en-tête de chaque fichier ADR (0008, 0009, 0012 si besoin, 0020, 0021, 0025, 0036 : comparer ligne à ligne, ne pas se fier à cette liste) ; copier les statuts des lignes nouvelles depuis l'en-tête de chaque fichier ADR sans les interpréter ; laisser la ligne ADR-0036 en l'état (Q1). Validation : `(Get-ChildItem docs/adr/0*.md).Count` égale le nombre de lignes `[ADR-` du tableau ; pour chaque ADR, la date du tableau égale celle de l'en-tête du fichier (relecture ligne à ligne, commande `Select-String -Path docs/adr/0*.md -Pattern 'Date'`) ; les trois gates sont rejoués.

## 7. Notes datées et citations (MINOS-AUD-G10, G03)

- [ ] 7.1 [MINOS-AUD-G10] Reformuler la note « PR #333, non fusionnée lors de cette rédaction » de `docs/adr/README.md` (prose de l'index, pas un statut) et la phrase équivalente de `docs/roadmap/storage-hexagonal-2026-10/README.md` à l'état actuel (fusionnée) ; source : `git log --oneline --grep "pull request #333"` (merge `bc1d3421`). [MINOS-AUD-G03] Dans `docs/architecture/arc42/11-risques-dette.md:4`, remplacer la citation « ADR-0021 (Docker autonomy) » par l'ADR-0037 (relire le § « Relation avec ADR-0021 » de 0037) ; ne pas toucher à la mention « ADR-0036 (Proposed) » de la ligne 3 (Q1). Validation : `Select-String docs/adr/README.md,docs/roadmap/storage-hexagonal-2026-10/README.md -Pattern 'non fusionn'` ne renvoie plus rien ; les trois gates sont rejoués.

## 8. Liens morts (MINOS-AUD-G18)

- [ ] 8.1 [MINOS-AUD-G18] Corriger les liens morts hors `docs/history/` et hors diagrammes (Q7) : `docs/audit/AUDIT-2026-09.md` (`../adr/0045-surfaces-d-objet.md` vers `0045-constructeur-unique-et-point-d-entree-nomme.md`), `docs/audit/PROMPT-FIABILITE.md` et `docs/audit/PROMPT-SPRINT-2-3-RESTANT.md` (liens `docs/adr/…` relatifs invalides depuis leur répertoire) et les six liens de `docs/audit/CI-HYGIENE-SUIVI.md` vers des workflows supprimés (les passer en texte de code sans changer la phrase). Ne modifier aucun fichier de `docs/audit/annexes/`. Validation : pour chaque cible corrigée, `Test-Path <répertoire du fichier>/<lien>` renvoie `True` (ou le lien a été retiré) ; un contrôle des liens `.md` de `docs/` hors `history/` ne signale plus que les sept liens de `docs/architecture/diagrams/README.md` en attente de Q7 ; les trois gates sont rejoués.

## 9. Vérification d'ensemble

- [ ] 9.1 [MINOS-AUD-G01..G19 retenus] Rejouer les trois gates sur l'arbre final ; vérifier `git status --short` : seuls des fichiers `docs/**` et `README.md` sont modifiés, aucun `docs/adr/00NN-*.md`, aucun fichier de `docs/audit/annexes/`, `docs/generated/product-facts.md` inchangé ; relire la liste des constats de `proposal.md` et confirmer que chaque ligne « retenue » a sa tâche cochée et chaque question ouverte reste dans `design.md`. Validation : sorties « SUCCESS » des trois gates et `git diff --stat -- docs README.md` revu fichier par fichier.

## Workflow follow-up

- Ouvrir une PR `docs/…` vers `develop` ; l'archivage du changement ne crée aucune spec.
- Résumer à l'archivage les docs/ mis à jour (STATUS.md, ROADMAP.md) ; aucun ADR à mettre à jour sans réponse aux questions ouvertes Q1 à Q9.
