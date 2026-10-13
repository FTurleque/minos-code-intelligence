# Tasks

Ordre dans chaque section : rouge (cas d'auto-test sur arbres temporaires et copie des sources réelles), règle, preuve. Aucun fichier de `src/main` n'est modifié. Les cinq lots modifient `check-module-boundaries.py` et son test : les faire en série.

## 1. Relevé et méthode (AUD-ARC-01, -05, -08, -12)

- [ ] 1.1 [AUD-ARC-01, AUD-ARC-08] Lire la reconnaissance des usages de la règle A10 ; générer les tables initiales par une exécution sur le HEAD (types du moteur par surface, types d'adaptateur par couple, lanceurs de processus, racines par module) ; mesurer les faux positifs d'une reconnaissance au grain du mot sur les noms courts. Consigner les décomptes et la méthode retenue dans `design.md`.

## 2. Règle A11 — types du moteur par surface (AUD-ARC-01)

- [ ] 2.1 [AUD-ARC-01] Rouge : cas de `scripts/architecture/test_check_module_boundaries.py` — type hors liste importé (refusé), nom qualifié hors liste (refusé), entrée morte (refusée), entrée d'un type inexistant (refusée), dépôt conforme (accepté). **Preuve du rouge : sans la règle, la copie mutée passe.**
- [ ] 2.2 [AUD-ARC-01] Règle et table `ENGINE_TYPES_NAMED_BY_SURFACE` (D1) ; message qui nomme la surface, le fichier, le type et la marche à suivre. Preuve : test vert (dont `EveryRuleHasATest`), `python scripts/architecture/check-module-boundaries.py` vert avec les décomptes par surface.

## 3. Règle A12 — lanceurs de processus (AUD-ARC-05)

- [ ] 3.1 [AUD-ARC-05] Rouge : `ProcessBuilder` dans une classe hors liste de `minos-engine` (refusé), `Runtime.getRuntime().exec` dans `minos-application` (refusé), entrée qui ne lance plus rien (refusée), mention dans un commentaire (acceptée).
- [ ] 3.2 [AUD-ARC-05] Règle et table `PROCESS_LAUNCHERS` = `PrivateLocalStorage`. Preuve : test et garde verts.

## 4. Règle A13 — types entre adaptateurs (AUD-ARC-08)

- [ ] 4.1 [AUD-ARC-08] Rouge : type de `minos-storage-local` hors liste nommé par `minos-storage-postgresql` (refusé, par import puis par nom qualifié), entrée morte (refusée).
- [ ] 4.2 [AUD-ARC-08] Règle et table `ADAPTER_TYPES_NAMED_BY_ADAPTER`, noms relus au HEAD (dont le type d'intégrité utilisé sans import). Preuve : test et garde verts.

## 5. Règle A14 — racines de packages (AUD-ARC-12)

- [ ] 5.1 [AUD-ARC-12] Rouge : nouvelle racine dans `minos-engine` (refusée), sous-package d'une racine listée (accepté), racine listée mais vide (refusée).
- [ ] 5.2 [AUD-ARC-12] Règle et table `PACKAGE_ROOTS_BY_MODULE`. Preuve : test et garde verts.

## 6. Arêtes d'exécution par service (AUD-ARC-11)

- [ ] 6.1 [AUD-ARC-11] Rouge : cas du rendu — service déclaré par un module et fourni par un autre produit une arête ; document versionné sans l'arête → `--check` échoue. **Preuve du rouge sur le document actuel.**
- [ ] 6.2 [AUD-ARC-11] `render_dependency_document` : section des arêtes d'exécution (D3) ; régénérer `docs/architecture/diagrams/module-dependencies.md` par `--write-doc` (ne pas l'éditer à la main) ; aligner `docs/architecture/arc42/05` si la règle A9 le demande. Preuve : garde vert, `python scripts/docs/check-current-docs.py` vert.

## 7. Clôture

- [ ] 7.1 Notes de mise en œuvre des ADR 0058 (§ 3 gardé par A11) et 0022 (A12) ; `docs/developer/quality-gates.md` : les quatre règles et la façon de réduire une liste. Rejouer `python .claude/scripts/run_gates.py`.
- [ ] 7.2 Mettre à jour `docs/audit/2026-10-10/SUIVI.md` (AUD-ARC-01, -05, -08, -11, -12 : dette **figée**, décomptes, chantiers restants et leurs prérequis).
- [ ] 7.3 `openspec validate --all --strict` ; docs à mettre à jour avant archivage : `docs/STATUS.md`, `docs/developer/quality-gates.md`.
