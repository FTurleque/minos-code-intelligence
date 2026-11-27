# Tasks

Changement indépendant des autres du sprint 12, mais il réécrit `docs/architecture/diagrams/capacites/frontieres-de-modules.md`, où `aligner-la-documentation-courante-apres-le-sprint-11` et `lire-les-methodes-generiques-de-la-facade-dans-a11` ajoutent une ligne de traçabilité à l'archivage. Ordre conseillé : fusionner celui-ci en dernier des trois, ou rebaser le dossier.

## 1. Dossier sans décompte et auto-test (AUD-ARC-18)

- [ ] 1.1 AUD-ARC-18 — Rouge : dans `scripts/architecture/test_check_module_boundaries_documents.py`, classe `GuardDocumentationTest`, ajouter `test_the_dossier_writes_no_count_of_the_policy` (motif de D1, lignes fautives nommées) et `test_a_count_written_back_into_the_dossier_is_seen` (le texte du dossier, une ligne « minos-app dépend des 12 autres modules » ajoutée en mémoire). Vérifier : le premier échoue au HEAD en nommant les lignes 122, 220 et 221 ; le second échoue tant que la fonction de recherche n'existe pas. Exigence : frontieres-de-modules › Le dossier des frontières n'écrit aucun décompte de la politique.
- [ ] 1.2 AUD-ARC-18 — Correction du dossier `docs/architecture/diagrams/capacites/frontieres-de-modules.md` : E1 et la ligne de traçabilité des cycles connus sans nombre (« une exigence attend des cycles connus, une autre une table vide ») ; E2 « Clos », sans nombre ; E4 décrit le cliquet sans compte (« le scénario attend des décomptes figés que le cliquet a dépassés ») ; E8 cite `openspec/specs/frontieres-de-modules/spec.md` (scénarios du contrôle hébergé et de l'activité Git) et dit les accesseurs absents de `MinosApplication`, sans les écrire avec parenthèses ; en-tête : commit et date du relevé de cette PR.
- [ ] 1.3 AUD-ARC-18 — Docstring du cas (anglais) : ce qu'il refuse et sa limite (nombres écrits en lettres, renvois de ligne non lus).
- [ ] 1.4 AUD-ARC-18 — Preuve : `python scripts/architecture/test_check_module_boundaries_documents.py -v` vert ; la commande `git grep` du troisième scénario ne rend aucune ligne ; les quatre autres auto-tests du garde, `python scripts/architecture/check-module-boundaries.py`, `python scripts/docs/check-capability-dossiers.py`, `python scripts/quality/check-comment-language.py`, `python .claude/scripts/run_gates.py` verts. Aucun scope JaCoCo.

## 2. Clôture

- [ ] 2.1 AUD-ARC-18 — Mettre à jour la matrice de traçabilité du design.
- [ ] 2.2 AUD-ARC-18 — Valider : `openspec validate --all --strict` vert ; après archivage, ligne de l'exigence ajoutée dans le dossier (sans nombre, le cas de 1.1 la relit) ; `python scripts/docs/check-capability-dossiers.py` vert.
- [ ] 2.3 AUD-ARC-18 — Documents à mettre à jour avant archivage : dossier de capacité (1.2, 2.2), ligne AUD-ARC-18 du suivi de l'audit (coordinateur, après fusion). Si le propriétaire retient D2 option b, ouvrir un changement à part pour les scénarios de la spécification.
