# Tasks

Ordre conseillé : ce changement avant `tenir-le-contrat-de-la-facade-par-le-garde` (lignes voisines de `MinosApplication.java` et de `docs/user/java-api.md`). Indépendant de `injecter-les-ports-des-operations-locales`. `docs/project/audits/2026-11-15/SUIVI.md:7` n'est pas touché (coordinateur).

Chaque texte durable dit l'état en vigueur, au présent, sans récit du chantier (design, Contexte).

## 1. Prose de la façade et d'ARCH-0087 (AUD-ARC-15)

- [x] 1.1 AUD-ARC-15 — Rouge : exécuter la commande `git grep` du scénario « Prose en vigueur » ; vérifier qu'elle rend quatre lignes (`MinosApplication.java:66`, `ARCH-0042-racine-de-composition.md:333` et `:401`, `public-surfaces.md:12`) ; vérifier que `git grep -n 'retirer-la-racine-de-composition-de-la-facade' -- docs/architecture/adr/README.md` ne rend rien. Consigner les sorties dans la PR. Exigence : frontieres-de-modules › La documentation courante de la façade décrit son état en vigueur.
- [x] 1.2 AUD-ARC-15 — Correction (code) : remplacer le Javadoc de classe de `minos-application/src/main/java/com/minos/application/MinosApplication.java:66` par le texte de D4 (anglais, sans jalon). Vérifier : `python scripts/quality/check-comment-language.py` vert ; `python scripts/remediation/check-p0-p2.py` vert. Aucun build Maven requis (commentaire seul) ; la compilation est couverte par le CI.
- [x] 1.3 AUD-ARC-15 — Correction (ADR et index) : dans `docs/architecture/adr/ARCH-0042-racine-de-composition.md`, réécrire les deux parenthèses de D2 (§ 8.1 `:333`, § 10 `:401`), sans toucher aux sections de décision ; dans `docs/architecture/adr/README.md:92`, ajouter `retirer-la-racine-de-composition-de-la-facade` à la colonne Origine, dans l'ordre de l'en-tête d'ARCH-0087. Vérifier : liens relatifs valides ; `archgate check` vert s'il est installé, sinon le dire dans la PR ; `python scripts/docs/check-current-docs.py` vert.
- [x] 1.4 AUD-ARC-15 — Correction (développeur et mémoire) : `docs/developer/public-surfaces.md:12`, stéréotype `<<application facade>>` (D4) ; `.claude/agent-memory/archgate-developer/project_audit_2026_11_15_clos.md:19-21`, exemple sans statut (D3), la règle inchangée. Vérifier : la ligne 41 du même fichier et les lignes 19-21 ne se contredisent plus ; `python scripts/remediation/check-p0-p2.py` vert (lit `public-surfaces.md`).
- [x] 1.5 AUD-ARC-15 — Preuve : la commande du scénario « Prose en vigueur » ne rend aucune ligne (code 1) ; celle du scénario « Index des ADR complet pour ARCH-0087 » rend la ligne 92 (code 0) ; `python scripts/docs/product-facts.py --check` et `python .claude/scripts/run_gates.py --fast` verts. Aucun scope JaCoCo.

## 2. Remplaçant de `retentionService()` (AUD-QUA-08)

- [x] 2.1 AUD-QUA-08 — **(manuelle)** Faire trancher D1 par le propriétaire (recommandation : documenter, sans opération nouvelle). Si l'option (b) est retenue, arrêter ce groupe et ouvrir un changement dédié à l'opération de rétention. Recommandation retenue le 2026-11-25.
- [x] 2.2 AUD-QUA-08 — Rouge : exécuter la commande du scénario « Rétention sans remplaçant promis » ; vérifier qu'elle rend `docs/user/java-api.md:359`. Exigence : frontieres-de-modules › La table de rupture de la façade ne promet aucun remplaçant absent.
- [x] 2.3 AUD-QUA-08 — Correction et preuve : réécrire la cellule « Remplaçant » de la ligne `retentionService()` selon D1 (a), avec un lien relatif vers `autonomous-indexing.md` (rétention après import) ; vérifier que la commande de 2.2 ne rend plus rien (code 1), que les autres remplaçants de la table sont des méthodes publiques au HEAD (relecture ligne par ligne, résultat consigné dans la PR), puis `python scripts/docs/check-current-docs.py` et `python .claude/scripts/run_gates.py --fast`.

## 3. Clôture

- [x] 3.1 AUD-ARC-15, AUD-QUA-08 — Mettre à jour la matrice de traçabilité du design (preuves exécutées, preuves manquantes) ; vérifier qu'aucune ligne n'affirme une couverture sans preuve.
- [x] 3.2 AUD-ARC-15, AUD-QUA-08 — Après archivage, ajouter les deux exigences au dossier `docs/architecture/diagrams/capacites/frontieres-de-modules.md` (coordonner avec `actualiser-le-dossier-des-frontieres-de-modules`, AUD-ARC-16, s'il est archivé dans la même période) ; vérifier par `python scripts/docs/check-capability-dossiers.py`.
- [x] 3.3 AUD-ARC-15, AUD-QUA-08 — Valider : `openspec validate --all --strict`.
- [x] 3.4 AUD-ARC-15, AUD-QUA-08 — Lister les documents à mettre à jour avant archivage : ceux des tâches 1.2 à 1.4 et 2.3, le dossier de capacité (3.2), les lignes AUD-ARC-15 et AUD-QUA-08 du suivi de l'audit `docs/project/audits/2026-11-15/` après fusion ; STATUS, ROADMAP et notes de version inchangés (aucun comportement modifié). Vérifier que chacun est fait ou nommé comme reste dans la PR. Liste : tâches 1.2-1.4 et 2.3 faites ; restent le dossier de capacité (3.2, à l'archivage) et les lignes AUD-ARC-15, AUD-QUA-08 du SUIVI (coordinateur, après fusion).
