# Tasks

Changement indépendant des quatre autres du sprint 12. Il touche les mêmes fichiers que `retirer-les-nombres-du-dossier-des-frontieres` seulement par le dossier `docs/architecture/diagrams/capacites/frontieres-de-modules.md` : ordre conseillé, celui-ci puis le dossier, qui rebase sa ligne de traçabilité.

Mutations : sur des copies du dépôt dans le scratchpad, jamais sur le dépôt.

## 1. Règle A11 sur les méthodes génériques et les imports joker (AUD-ARC-17)

- [x] 1.1 AUD-ARC-17 — Rouge (état actuel) : rejouer sur des copies les mutations du design (`leakGeneric`, `leakRet`, `leakBound`) et l'import joker `import com.minos.registry.*;` avec `public void e(ProjectRegistry r)` ; vérifier rc=0 pour chacune et rc=1 pour `leakPlain`. Relever par `grep -n "PUBLIC_METHOD" scripts/` les autres consommateurs de l'expression et dire dans la PR s'ils changent de résultat. Exigence : frontieres-de-modules › Une méthode générique ou un import joker de la façade ne contourne pas A11.
- [x] 1.2 AUD-ARC-17 — Rouge (auto-test) : dans `scripts/architecture/test_check_module_boundaries_ratchets.py`, ajouter à `test_a_public_signature_taking_an_engine_type_is_refused` les variantes `public <T> void leakGeneric(ProjectRegistry r)`, `public static <T> MinosApplication of(T t, ProjectRegistry r)`, `public <T extends ProjectRegistry> void leakBound(T r)`, `public <T extends java.util.Map<String, ProjectRegistry>> T nested()`, `public <T> MinosApplication(ProjectRegistry r)` ; un cas de retour `public <T> ProjectRegistry leakRet()` ; deux cas de retour statique, `public static ProjectRegistry leakStatic()` et `public static <T> ProjectRegistry leakStaticGeneric()` (D3) ; un cas d'import joker ; un cas accepté `public <T extends Comparable<T>> java.util.List<T> sorted(java.util.List<T> values)`. Vérifier : les cas refusés échouent tant que la règle n'est pas corrigée.
- [x] 1.3 AUD-ARC-17 — Correction (D1 a, D2 a, D3 a) : dans `scripts/architecture/module_boundary_ratchets.py`, lire une section de paramètres de type équilibrée après les modificateurs (méthodes et constructeurs publics), ajouter son texte aux textes de type du retour et des paramètres, refuser une méthode statique publique qui rend un type du moteur, et refuser un import joker d'un package de `minos-engine` dans `MinosApplication`. Docstrings en anglais, sans numéro de jalon. Vérifier : `python scripts/architecture/test_check_module_boundaries_ratchets.py -v` vert.
- [x] 1.4 AUD-ARC-17 — Preuve : sur chaque copie de 1.1, `check-module-boundaries.py` rend 1 et nomme la méthode (ou le package) et le type ; sur le dépôt, rend 0, sortie comparée à celle du HEAD. Gates : les cinq `scripts/architecture/test_check_module_boundaries*.py`, `scripts/architecture/test_gate_runner.py`, `python scripts/quality/check-ci-wiring.py`, `python scripts/quality/check-comment-language.py`, `python .claude/scripts/run_gates.py`. Aucun scope JaCoCo.

## 2. Documentation (AUD-ARC-17)

- [x] 2.1 AUD-ARC-17 — `docs/developer/quality-gates.md`, puce « A11, accesseurs de `MinosApplication` » : une phrase sur les méthodes génériques (bornes comprises) et le refus d'un import joker du moteur. Vérifier : `python scripts/docs/check-current-docs.py` vert, liens vérifiés, `python scripts/architecture/test_check_module_boundaries_documents.py` vert.
- [ ] 2.2 AUD-ARC-17 — **(manuelle)** Ajouter à la proposition de mise à jour d'ARCH-0087 (« Compliance and Enforcement », tâche manuelle du sprint 11) la ligne de cette règle. Aucun ADR modifié pendant la spécification.

## 3. Clôture

- [x] 3.1 AUD-ARC-17 — Mettre à jour la matrice de traçabilité du design (mutations rouges, auto-test, garde au HEAD).
- [ ] 3.2 AUD-ARC-17 — Valider : `openspec validate --all --strict` vert ; après archivage, ligne de l'exigence ajoutée dans `docs/architecture/diagrams/capacites/frontieres-de-modules.md` ; `python scripts/docs/check-capability-dossiers.py` vert.
- [ ] 3.3 AUD-ARC-17 — Documents à mettre à jour avant archivage : `quality-gates.md` (2.1), dossier de capacité (3.2), ligne AUD-ARC-17 du suivi de l'audit (coordinateur, après fusion). Vérifier : chacun est fait ou nommé comme reste dans la PR.
