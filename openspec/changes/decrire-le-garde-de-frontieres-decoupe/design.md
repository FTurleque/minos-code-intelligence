# Design

Changement documentaire : le modèle est réduit à Contexte, État vérifié au HEAD, Écarts, Décisions, Vérification et Traçabilité.

## Contexte

Motivation : voir `proposal.md` (Why). Depuis la PR #791 (AUD-ARC-04), la politique de frontières vit dans `scripts/architecture/module-boundaries.json`, lue par le garde et par `ModuleArchitectureTest` ; depuis les PR #802 à #804, le garde est découpé. La prose n'a suivi ni l'un ni l'autre. Aucun code n'est touché.

Composition du garde au HEAD (huit fichiers exécutés, plus les fixtures d'auto-test) :

| Fichier | Rôle (docstring relue) |
|---|---|
| `scripts/architecture/check-module-boundaries.py` (414 lignes) | point d'entrée ; règles des POM (A2 politique, familles hexagonales, dépendances cachées) ; lance toutes les règles (`:1-10`, `:24-40`) |
| `module_boundary_policy.py` | lecture stricte de `module-boundaries.json`, familles, `ALLOWED_DEPENDENCIES` dérivé, `groupId` racine (`:1-6`, `:23`, `:189-224`) |
| `module_boundary_sources.py` | lecture des sources Java partagée (packages, imports, commentaires et littéraux) |
| `module_boundary_adapters.py` | A2 sources, R1/R2, A10, A19, A20, isolation des packages de stockage (`:1-3`, `:238`, `:273`, `:277`) |
| `module_boundary_ratchets.py` | A11 à A14, A16, A18, mesure du code concret du moteur ; `PACKAGE_ROOTS_BY_MODULE` (`:1-3`, `:118`) |
| `module_boundary_packages.py` | A3, A7, A8, A15, disposition Java ; `KNOWN_PACKAGE_CYCLES` (`:1-2`, `:275`) |
| `module_boundary_documents.py` | A9, A17, document généré (`:1-3`, `:195-202`) |
| `gate_runner.py` | lanceur partagé des gates d'architecture, aussi utilisé par `check-private-io.py` (`:1-9`) |
| `module_boundaries_testing.py` | fixtures des cinq auto-tests `test_check_module_boundaries*.py` (`pr-ci.yml:165-177`) ; ni gate ni test |

## État vérifié au HEAD

Commit relu : `535a50176` (`origin/main`). Recherche : `git grep` sur `docs/`, `openspec/specs/`, `.claude/` (règles, skills, agents, commandes), `AGENTS.md`, `CONTRIBUTING.md`, `CLAUDE.md`, hors documents figés, pour `check-module-boundaries.py`, « liste MODULES », « copie », « du script » et les noms de tables.

### Passages à corriger (18 passages, 6 fichiers)

| # | Passage | Dérive | Nature du document |
|---|---|---|---|
| 1 | `docs/architecture/diagrams/capacites/frontieres-de-modules.md:10` | « le garde Python `check-module-boundaries.py` et son auto-test » : un script et un auto-test, alors qu'il y a six modules de règles et cinq auto-tests | dossier de capacité |
| 2 | `docs/architecture/diagrams/capacites/frontieres-de-modules.md:71-72` | `check-module-boundaries.py:59-85` et « une copie identique vit dans `ModuleArchitectureTest` (égalité contrôlée par A17) » | dossier de capacité |
| 3 | `docs/architecture/diagrams/capacites/frontieres-de-modules.md:172` | « Le garde et son auto-test » | dossier de capacité |
| 4 | `docs/architecture/diagrams/capacites/frontieres-de-modules.md:179` | E1 : preuve `check-module-boundaries.py:1442` ; la table est `module_boundary_packages.py:275` | dossier, écart |
| 5 | `docs/architecture/diagrams/capacites/frontieres-de-modules.md:182` | E4 : preuve `check-module-boundaries.py:561-584` ; la dette A11 est dans la section `engineTypesNamedBySurface` de la politique | dossier, écart |
| 6 | `docs/architecture/diagrams/capacites/frontieres-de-modules.md:187` | E9 : preuve `check-module-boundaries.py:562-575`, et l'écart est levé : chaque entrée A11 porte une `reason` contrôlée (`module_boundary_policy.py:153`) | dossier, écart |
| 7 | `docs/developer/quality-gates.md:59-61` | le chapitre présente le garde comme un seul script, sans ses modules ni son lanceur | doc développeur |
| 8 | `docs/developer/quality-gates.md:83` | « une fonction `check_*` du script » : le test parcourt le script et les cinq modules de règles (`test_check_module_boundaries.py:406-411`) | doc développeur |
| 9 | `docs/architecture/adr/ARCH-0058-declarer-les-usages-de-modules.md:6` | `files:` nomme le script et le test, pas `module-boundaries.json` ni `ModuleBoundaryPolicy.java`, que la règle compagnon lit (`ARCH-0058-…rules.ts:8-10`) | ADR `Accepted`, en-tête |
| 10 | `ARCH-0058-…md:55` | « table `ALLOWED_DEPENDENCIES` », « table `ENGINE_TYPES_NAMED_BY_SURFACE` » attribuées au gate sans la politique | ADR `Accepted`, Compliance |
| 11 | `ARCH-0058-…md:57` | la règle Archgate décrite comme une comparaison de deux tables ; elle vérifie que le test charge la politique sans en recopier (`ARCH-0058-…rules.ts:3-6`, `:52`) | ADR `Accepted`, Compliance |
| 12 | `ARCH-0058-…md:58` | « A17 : `ModuleArchitectureTest.ALLOWED` égale `ALLOWED_DEPENDENCIES` » ; A17 refuse toute table littérale (`module_boundary_documents.py:195-202`) | ADR `Accepted`, Compliance |
| 13 | `docs/architecture/adr/ARCH-0068-modules-dependances-et-propriete-des-packages.md:64` | « La table `ALLOWED_DEPENDENCIES` de `check-module-boundaries.py` » | ADR `Accepted`, Decision |
| 14 | `ARCH-0068-…md:168` | « A7 — la liste `MODULES` du script » ; elle est la section `modules` de la politique | ADR `Accepted`, Compliance |
| 15 | `ARCH-0068-…md:170` | A9 réduit à la ligne « Dépendances » ; A9 lit aussi portées, « Sources » et « Interfaces » (`quality-gates.md:66`) | ADR `Accepted`, Compliance (connexe) |
| 16 | `ARCH-0068-…md:183`, `:185` | « en lisant la liste `MODULES` du script », « table lue dans le script » ; la règle lit `module-boundaries.json` et `module_boundary_ratchets.py` (`ARCH-0068-…rules.ts:3-10`) | ADR `Accepted`, Compliance |
| 17 | `docs/architecture/adr/ARCH-0087-adaptateurs-embarques-et-facade-reduite.md:37` | les deux listes fermées A19 situées dans le script ; elles sont les sections `infrastructureApiUsers` et `embeddedAdapterClasses` de la politique (`module_boundary_adapters.py:273`, `:277`) | ADR `Proposed`, Decision |
| 18 | `.claude/skills/minos-literal-gates/SKILL.md:30` | règles listées A2 à A10 seulement ; colonne « Lit » sans la politique JSON ni les modules, où vivent désormais la plupart des chaînes | skill |

### Passages exacts, laissés en l'état

- ARCH-0058 `:17` (Context), `:28` (Decision), `:37` (Do) parlent de « deux tables » : la note de mise en œuvre du 2026-11-19 (`:86`) les relit déjà (« les deux tables du Do sont ce fichier lu deux fois »).
- Une quarantaine de mentions désignent `check-module-boundaries.py` comme gate ou point d'entrée (ARCH-0008:89, ARCH-0027:91, ARCH-0042:297, :389, :404, ARCH-0056:60, ARCH-0057:56, :68, ARCH-0064:81, ARCH-0082:95, DATA-0081:128, DATA-0085:138, SEC-0079, SEC-0084, `AGENTS.md:39`, `.claude/rules/java-production.md:11`, scénarios de `openspec/specs/frontieres-de-modules/spec.md`) : exactes.
- `docs/developer/archgate.md:64`, `:68` et `docs/developer/quality-gates.md:78-79` décrivent déjà la source unique.
- `ARCH-0068-…rules.ts:42`, `:49` (« du script ») : commentaires de code, exacts depuis que `SCRIPT` vaut `module_boundary_ratchets.py` ; hors périmètre.

Non mesuré : aucun gate exécuté pendant la spécification ; seule la commande `git grep` de l'exigence a été jouée (rouge : 8 lignes, passages 2, 4 à 6, 12, 14 et 16).

## Écarts

| Écart | Source A | Source B | Suite proposée |
|---|---|---|---|
| L'audit parle de « huit modules » ; on compte huit fichiers exécutés (script, six `module_boundary_*.py`, `gate_runner.py`) plus les fixtures `module_boundaries_testing.py` | `findings.json` (AUD-ARC-12) | `ls scripts/architecture/` | Décrire les neuf fichiers par rôle, sans affirmer un nombre (un fait chiffré s'écrit une fois) |
| L'action vise frontieres-de-modules.md:71 seulement ; les écarts E1, E4, E9 du même dossier citent aussi des lignes mortes, et E9 est levé | `findings.json` | dossier `:179-187` | Corrigés dans la même tâche ; E9 clos |
| L'action nomme « les deux puces d'ARCH-0068 » ; quatre puces de Compliance d'ARCH-0068 et trois d'ARCH-0058 sont fausses, ainsi que `files:` d'ARCH-0058 | `findings.json` | passages 9 à 16 | Toutes corrigées (D3) |
| ARCH-0087 (`Proposed`) et le skill `minos-literal-gates` ne sont pas cités par l'audit | — | passages 17, 18 | D4 pour ARCH-0087 ; le skill est corrigé |
| `quality-gates.md` ne décrit ni A19 ni A20, que porte `module_boundary_adapters.py` | `quality-gates.md:59-83` | `module_boundary_adapters.py:1-3` | La table des fichiers nomme A19 et A20 ; leur description détaillée reste hors périmètre (ARCH-0087, ARCH-0042 § 10) |
| L'exigence de la spec qui nomme le seul `test_check_module_boundaries.py` (`spec.md:8-9`) dérive aussi | spec courante | `pr-ci.yml:165-177` | D3 du sprint : laissée à AUD-TST-13 |

## Décisions


Décision du propriétaire du 2026-11-24 : les recommandations de ce design sont retenues.
### D1 — Une seule description du découpage, dans `docs/developer/quality-gates.md`

Statut : [Décision] de conception. Le chapitre « Gardes d'architecture » reçoit une table « fichier → règles », le lanceur et les fixtures ; le dossier de capacité et les ADR y renvoient sans recopier la liste. Les ADR disent quelle règle tient la décision et où vit la politique, pas comment le code est rangé. Aucun numéro de ligne n'est cité dans la prose durable pour le garde : on cite le fichier, la section JSON ou le nom de la règle.

### D2 — Pas de gate documentaire contre la récidive dans ce changement

Statut : [Proposition], recommandation pour le propriétaire.

- **A (recommandée)** : aucune règle nouvelle ; l'exigence se vérifie par la commande `git grep` de la spec, rejouée en Clôture. Les dossiers de capacité citent des lignes par construction (« relevé au commit … ») ; un interdit propre à un seul script serait arbitraire, et un interdit général casserait une quarantaine de citations légitimes.
- **B** : étendre A9 (`module_boundary_documents.py`) pour exiger que `quality-gates.md` nomme chaque fichier `module_boundary_*.py` et `gate_runner.py`. C'est le seul contrôle qui empêche vraiment la récidive du « découpage non décrit ». Coût : code et auto-test (rouge d'abord), le changement cesse d'être documentaire, et il touche un fichier que modifie AUD-TST-13.
- **C** : un motif interdit dans `check-current-docs.py` (`check-module-boundaries\.py:\d`) : étroit, il ne verrait pas `module_boundary_ratchets.py:118` demain.

Si B est retenu, il fait l'objet d'un changement distinct, après AUD-TST-13.

### D3 — ADR acceptés : corriger les puces de contrôle, compléter la mise en œuvre, ne pas toucher la décision

Statut : [Proposition], recommandation pour le propriétaire. `Compliance and Enforcement` décrit des contrôles, pas la décision : ses puces fausses (passages 10 à 12, 14 à 16) sont réécrites au présent, sens inchangé (`.claude/rules/adr.md`, « clarifier son texte si son sens ne change pas »). Le texte de `Decision`, `Context` et `Do's and Don'ts` reste intact ; ARCH-0068:64 est relu par une note datée de `Mise en œuvre` (« la table `ALLOWED_DEPENDENCIES` est la section `allowedDependencies` de `module-boundaries.json`, lue par le garde »), et ARCH-0068 reçoit une seconde phrase sur le découpage du garde (renvoi à `quality-gates.md`). L'en-tête `files:` d'ARCH-0058 ajoute la politique et son lecteur Java, ce qui élargit la portée Archgate sans changer la règle. Alternative : ne rien changer hors `Mise en œuvre` (les puces fausses restent, contredites par la note).

### D4 — ARCH-0087 (`Proposed`) : préciser l'emplacement des listes dans le même changement

Statut : [Proposition]. L'ADR n'étant pas accepté, la phrase de `:37` se corrige en place (« sections `infrastructureApiUsers` et `embeddedAdapterClasses` de `module-boundaries.json`, lues par la règle A19 »). Si l'acceptation d'ARCH-0087 est en cours dans un autre changement, la correction y migre ; sinon, elle se fait ici.

## Vérification

- Rouge (avant correction) : la commande `git grep` de l'exigence « aucune ligne du garde » rend 8 lignes ; la boucle de l'exigence « chaque fichier nommé » rend 0 pour les six `module_boundary_*.py` et `gate_runner.py`.
- Après correction : les deux commandes passent ; `python scripts/docs/check-current-docs.py`, `python scripts/docs/product-facts.py --check`, `python scripts/docs/check-capability-dossiers.py`, `python scripts/quality/check-comment-language.py`, `archgate check`, `python .claude/scripts/run_gates.py --fast`, `openspec validate --all --strict` ; liens relatifs des fichiers modifiés vérifiés.

## Documentation durable à mettre à jour

`docs/developer/quality-gates.md` ; `docs/architecture/diagrams/capacites/frontieres-de-modules.md` (vue, écarts, traçabilité des deux exigences) ; ARCH-0058, ARCH-0068, ARCH-0087 ; `.claude/skills/minos-literal-gates/SKILL.md`.

## Traçabilité

| Exigence (capacité › titre) | Scénario | Conception | Tâche | Test ou preuve |
|---|---|---|---|---|
| frontieres-de-modules › La documentation courante ne cite aucune ligne du garde de frontières | Documentation alignée | D1, D3, D4 | 1.1, 1.2, 2.1, 2.2, 2.3, 2.4 | obtenue (2.4) : commande `git grep` de la spec, aucune ligne, code 1 |
| idem | Citation de ligne réintroduite | D2 | 1.1 | obtenue : rouge consigné en 1.1 (8 lignes, code 0) ; récidive tenue par la revue, pas de gate (D2 A) |
| frontieres-de-modules › La documentation des gates nomme chaque fichier du garde de frontières | Chaque fichier du garde est nommé | D1 | 1.1, 1.3 | obtenue (1.3) : boucle `grep -c` de la spec, chaque compte ≥ 1 |
| idem | Module de règles ajouté sans documentation | D2 | 1.1 | obtenue : rouge consigné en 1.1 (comptes à 0) ; récidive tenue par la revue (D2 A), aucun contrôle automatique |
