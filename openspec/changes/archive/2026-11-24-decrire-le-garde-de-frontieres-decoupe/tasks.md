# Tasks

Constat **AUD-ARC-12** (sprint 10). Changement documentaire : aucun code, test, workflow ni gate modifié. Les passages numérotés renvoient à la table « Passages à corriger » du design. Si l'option B de D2 est retenue, elle fait l'objet d'un changement distinct.

## 1. Documentation du garde et dossier de capacité

- [x] 1.1 [AUD-ARC-12] Rouge : sur le `main` du moment, relire les 18 passages du design et corriger dans `design.md` tout numéro de ligne qui a bougé ; rejouer la commande `git grep` de l'exigence « La documentation courante ne cite aucune ligne du garde de frontières » et la boucle `grep -c` de l'exigence « La documentation des gates nomme chaque fichier du garde de frontières » (frontieres-de-modules). Vérifier : sorties consignées ici (8 lignes attendues ; comptes à 0 pour les six `module_boundary_*.py` et `gate_runner.py`).
  - Relu au commit `535a50176` (base `origin/docs/specifier-le-sprint-10-de-l-audit-2026-11-15`) : les 18 passages sont aux lignes du design, aucun numéro à corriger ; `wc -l scripts/architecture/check-module-boundaries.py` : 414.
  - `git grep` de l'exigence : 8 lignes, code 0 (ARCH-0058:58, ARCH-0068:168, :183, :185, frontieres-de-modules.md:71, :179, :182, :187).
  - Boucle `grep -c` : `module_boundary_adapters.py 0`, `module_boundary_documents.py 0`, `module_boundary_packages.py 0`, `module_boundary_policy.py 0`, `module_boundary_ratchets.py 0`, `module_boundary_sources.py 0`, `gate_runner.py 0`, `module-boundaries.json 4`.
- [x] 1.2 [AUD-ARC-12] Correction du dossier `docs/architecture/diagrams/capacites/frontieres-de-modules.md` (passages 1 à 6) : le garde est « un script d'entrée et ses modules de règles » avec renvoi à `docs/developer/quality-gates.md` ; la vue simplifiée renvoie à la section `allowedDependencies` de `scripts/architecture/module-boundaries.json`, lue sans copie par `ModuleArchitectureTest` (règle A17) ; E1 et E4 citent le fichier ou la section qui porte la table, sans numéro de ligne ; E9 est clos (raisons A11 contrôlées par la lecture stricte de la politique). Vérifier : `python scripts/docs/check-capability-dossiers.py` ; liens relatifs.
  - `python scripts/docs/check-capability-dossiers.py` : « Capability dossiers consistent: 22 dossier(s) for 22 capability spec(s). » ; liens relatifs des fichiers modifiés : 47 liens, 0 cassé.
- [x] 1.3 [AUD-ARC-12] Correction de `docs/developer/quality-gates.md` (passages 7 et 8, design D1) : au chapitre « Gardes d'architecture », une table « fichier → règles portées » pour `check-module-boundaries.py` et chaque `module_boundary_*.py` (A19 et A20 nommés), `gate_runner.py` (lanceur partagé avec `check-private-io.py`), `module_boundaries_testing.py` et les cinq auto-tests ; `module-boundaries.json` nommée comme source unique ; « une fonction `check_*` du script » devient « du script ou d'un de ses modules de règles ». Vérifier : la boucle `grep -c` de l'exigence passe ; `python scripts/docs/check-current-docs.py` ; `python scripts/docs/product-facts.py --check`.
  - Boucle `grep -c` : chaque `module_boundary_*.py` et `gate_runner.py` à 1, `module-boundaries.json` à 6 ; `check-current-docs.py` : « MINOS CURRENT DOCUMENTATION CONSISTENCY SUCCESS » ; `product-facts.py --check` : « M15 PRODUCT FACTS CONSISTENCY SUCCESS » ; `check-minos-01.py` (cite ce fichier) : SUCCESS.
- [x] 1.4 [AUD-ARC-12] Correction de `.claude/skills/minos-literal-gates/SKILL.md:30` (passage 18) : ligne du garde avec les modules `module_boundary_*.py` et `module-boundaries.json` dans la colonne « Lit », renvoi à `quality-gates.md` pour la liste des règles. Vérifier : `python .claude/scripts/run_gates.py --fast`.
  - `python .claude/scripts/run_gates.py --fast` : « 30/30 gates passent. » ; complet : « 87/87 gates passent. »

## 2. ADR (planifiés ici, aucun ADR créé)

- [x] 2.1 [AUD-ARC-12] ARCH-0058 (`Accepted`, design D3, passages 9 à 12) : en-tête `files:` complété par `scripts/architecture/module-boundaries.json` et `minos-app/src/test/java/**/ModuleBoundaryPolicy.java` ; puces `:55`, `:57`, `:58` de `Compliance and Enforcement` réécrites au présent (A2 et A11 lisent la politique ; la règle Archgate et A17 vérifient que le test charge la politique sans recopier de table) ; `Context`, `Decision`, `Do's and Don'ts` inchangés. Vérifier : `archgate check` (règle `allowed-dependency-tables-identical` et registre GEN-0073).
  - `archgate check` : `"pass":true,"total":15,"passed":15,"failed":0`.
- [x] 2.2 [AUD-ARC-12] ARCH-0068 (`Accepted`, design D3, passages 13 à 16) : puces `:168`, `:170`, `:183`, `:185` de `Compliance and Enforcement` réécrites (liste des modules = section `modules` de la politique ; A9 avec portées, « Sources » et « Interfaces » ; `PACKAGE_ROOTS_BY_MODULE` lue dans `module_boundary_ratchets.py`) ; note datée de `Mise en œuvre` (AUD-ARC-12) : la table `ALLOWED_DEPENDENCIES` du § Decision (`:64`) est la section `allowedDependencies` de `module-boundaries.json`, et le garde est découpé en modules de règles décrits dans `docs/developer/quality-gates.md` ; texte de `Decision` inchangé. Vérifier : `archgate check` (règles `governed-modules-match-reactor`, `one-package-one-module`, `package-roots-owned-by-module`).
  - `archgate check` : `"pass":true,"total":15,"passed":15,"failed":0`.
- [x] 2.3 [AUD-ARC-12] ARCH-0087 (`Proposed`, design D4, passage 17) : `:37` situe les deux listes A19 dans les sections `infrastructureApiUsers` et `embeddedAdapterClasses` de `module-boundaries.json`, sauf si un autre changement en cours accepte ou réécrit cet ADR (la correction y migre alors, noté ici). Vérifier : `archgate check`.
  - Aucun autre changement actif n'accepte ni ne réécrit ARCH-0087 avant celui-ci (`retirer-la-racine-de-composition-de-la-facade` fusionne après lui, ses `tasks.md:3` et `design.md:226`) : corrigé ici ; `archgate check` vert.
- [x] 2.4 [AUD-ARC-12] Preuve : la commande `git grep` de l'exigence « La documentation courante ne cite aucune ligne du garde de frontières » ne rend aucune ligne (code 1) ; sortie consignée. Vérifier aussi `python scripts/quality/check-comment-language.py` et les liens relatifs des trois ADR.
  - `git grep` de l'exigence : aucune ligne, code 1 ; `check-comment-language.py` : « COMMENT LANGUAGE GATE SUCCESS » ; liens relatifs des trois ADR vérifiés (0 cassé).

## 3. Clôture

- [x] 3.1 [AUD-ARC-12] Mettre à jour la matrice de traçabilité du design en nommant les preuves obtenues et manquantes (les scénarios de récidive restent tenus par la revue si D2 A est retenu).
  - Matrice du design complétée (colonne « Test ou preuve » : preuves obtenues ; scénarios de récidive tenus par la revue, D2 A).
- [x] 3.2 [AUD-ARC-12] Préparer l'entrée des deux exigences ajoutées dans `docs/architecture/diagrams/capacites/frontieres-de-modules.md` (traçabilité), à écrire à l'archivage ; vérifier par `python scripts/docs/check-capability-dossiers.py`.
  - Fait à l'archivage (deux lignes, statut `partielle` : commandes de la spécification, aucun gate) ; `check-capability-dossiers.py` : 22 dossiers cohérents.
- [x] 3.3 [AUD-ARC-12] `openspec validate --all --strict`, `python .claude/scripts/run_gates.py --fast`, `python scripts/docs/check-current-docs.py`, `python scripts/docs/product-facts.py --check`, `archgate check`.
  - `openspec validate --all --strict` : « Totals: 28 passed, 0 failed (28 items) » ; `run_gates.py --fast` : 30/30 ; `run_gates.py` complet : 87/87 ; `check-current-docs.py`, `product-facts.py --check` : SUCCESS ; `archgate check` : 15/15, `"failed":0`.
- [x] 3.4 [AUD-ARC-12] Lister les documents à mettre à jour avant archivage (`docs/project/audits/2026-11-15/SUIVI.md` ou le relevé d'état en cours, `docs/project/governance/STATUS.md` s'il décrit le garde) ; vérifier que chacun est fait ou nommé comme reste.
  - Reste nommé : `docs/project/audits/2026-11-15/SUIVI.md` (statut d'AUD-ARC-12, mis à jour à la fusion). `STATUS.md` et `ROADMAP.md` décrivent les règles du garde, pas ses fichiers (`STATUS.md:19`, `ROADMAP.md:23`) : rien à changer.

## Workflow follow-up

- Archiver après revue (`openspec archive decrire-le-garde-de-frontieres-decoupe -y`), puis vérifier que `openspec/specs/frontieres-de-modules/spec.md` garde sa section `## Documentation liée`.
