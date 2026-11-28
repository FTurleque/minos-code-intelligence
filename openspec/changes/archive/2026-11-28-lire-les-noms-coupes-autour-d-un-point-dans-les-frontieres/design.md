# Design

## Contexte

Motivation : voir `proposal.md` (Why). Changement d'outillage : la lecture des noms d'un garde Python, ses auto-tests et leur documentation. Le design est réduit au contexte, à l'état vérifié, aux écarts, aux décisions, à la vérification et à la traçabilité ; les autres sections du modèle sont non applicables à un garde qui lit du texte.

## État vérifié au HEAD

Relu au commit `79e8f9fc1` (branche `fix/a11-ecritures-d-import`), le 2026-11-27.

| Fait | Preuve |
|---|---|
| `SOURCE_IMPORT` et `qualified_name_pattern` n'admettent aucun blanc dans un nom | `scripts/architecture/module_boundary_sources.py:21`, `:26` |
| Le recollage `QUALIFIER_GAP` ne sert qu'à la façade, et ne recolle pas devant `*` | `scripts/architecture/module_boundary_ratchets.py:146`, `:293` |
| Les autres lecteurs de noms effacent commentaires et littéraux sans recoller | `module_boundary_adapters.py:24`, `:66`, `:192` ; `module_boundary_packages.py:86`, `:287` ; `module_boundary_ratchets.py:442` |
| Les recherches de A12, A16, A19 et de `check-private-io.py` admettent déjà les blancs | `module_boundary_ratchets.py:58`, `:687` ; `module_boundary_adapters.py:269` ; `check-private-io.py:175` |

### Lecteurs

| Lecteur | Règles |
|---|---|
| `forbidden_names` | R1, R2, isolation des packages du stockage |
| `check_source_boundaries` | A2 sources |
| `check_runtime_local_exports` | A10 |
| `unreferenced_production_types` | A15 |
| `declared_classes` et `packages_used_by` | A8 |
| `types_named_by_module` | A11 surfaces et module de contrat, A13 |
| `minos_application_code` | A11 façade |

Les autres règles cherchent des mots, ou des expressions qui admettent les blancs. La lecture des déclarations `package` (`declared_package` et `PACKAGE`) n'admet pas de blanc : voir l'écart 5.

### Sonde avant correction

Arbres temporaires. « Coupé » : blancs, retour à la ligne après ou avant le point, commentaire de bloc.

| Écriture | Verdict |
|---|---|
| import coupé, joker coupé, nom qualifié coupé dans le code : A2, A8, A10, A11 surfaces, A13, R1, stockage | accepté |
| les trois mêmes : A15 | le type est déclaré sans appelant |
| import coupé et type coupé de la façade | refusé |
| joker coupé de la façade | accepté |
| `\u002e` à la place d'un point | accepté partout, façade comprise ; A15 déclare le type sans appelant |
| `com.minos.hosted.@Ann HostedRole` dans le code d'une surface | accepté |

## Écarts

| Écart | Source A | Source B | Suite proposée |
|---|---|---|---|
| 1. L'écart 3 du changement précédent nomme l'import d'une surface : la faille touche toutes les règles de noms, les noms qualifiés du code et le joker de la façade | `design.md` de `lire-les-ecritures-d-import-dans-les-regles-de-frontieres` | sonde | traité ici |
| 2. Un échappement Unicode cache un nom à toutes les règles de nom ; `check-private-io.py` les décode (`decode_unicode_escapes`) | sonde | Java les traduit avant de lire les noms | hors périmètre : à instruire à part |
| 3. Une annotation de type à l'intérieur d'un nom qualifié du code le cache hors de la façade | sonde | Java accepte l'écriture | hors périmètre : à instruire à part |
| 4. `FOREIGN_IMPORT` d'ARCH-0027 est ancré sur le début de ligne et n'admet pas de blanc | `ARCH-0027-…rules.ts:22` | même sonde de forme, non exécutée sur Archgate | hors périmètre : le plugin ne déclare aucune dépendance `com.minos:*` |
| 5. Une déclaration `package` coupée n'est pas lue en entier par `declared_package` | revue du diff | la disposition des sources, A3 et A14 la refusent, avec un message qui ne nomme pas la coupure | aucune : fail-closed |
| 6. A19 ne lit pas un joker sur un package d'API d'infrastructure (`import java.net.http.*;`) | revue du diff, lecture de `INFRASTRUCTURE_API` | A19 refuse de nommer ces API | hors périmètre : à instruire à part |

## Décisions

### D1 — Une lecture unique des sources pour les noms [Proposition, recommandation]

- (a) **Recommandé et appliqué.** `name_reading_code` dans `module_boundary_sources.py` efface commentaires et littéraux puis applique `QUALIFIER_GAP`, déplacé là et étendu à `*`. Chaque lecteur de noms l'appelle ; la façade garde sa chaîne (littéraux en jetons, annotations retirées) et partage l'expression.
- (b) Admettre `\s*\.\s*` dans `SOURCE_IMPORT`, `qualified_name_pattern` et les expressions locales de préfixes : chaque lecteur devrait ensuite normaliser ses captures. Trois expressions et sept consommateurs à tenir alignés.

`...` n'est jamais recollé : le recollage exige un caractère d'identificateur avant le point et un caractère d'identificateur ou `*` après lui. Les règles qui lisent des numéros de ligne (A16, A18) gardent `blank_comments_and_literals`, qui ne recolle rien.

### D2 — Les noms qualifiés du code sont dans le périmètre [Proposition, recommandation]

La demande porte sur les blancs autour des points. Le nom qualifié coupé dans le code est la même écriture, corrigée par la même lecture : le laisser accepterait ce que l'import refuse.

## Vérification

- Rouge : les nouveaux cas des auto-tests échouent avant la correction.
- Correction : auto-tests verts ; sortie du garde sur le dépôt identique à celle de la base ; une mutation par forme sur des copies du dépôt dans le scratchpad.
- `python .claude/scripts/run_gates.py`, `openspec validate --all --strict` et `python scripts/docs/check-capability-dossiers.py` verts.

## Documentation durable à mettre à jour

`docs/developer/quality-gates.md`, docstrings en anglais des lecteurs modifiés, `docs/architecture/diagrams/capacites/frontieres-de-modules.md` (ligne de l'exigence ajoutée, à l'archivage).

## Traçabilité

| Exigence (capacité › titre) | Scénario | Conception | Tâche | Test ou preuve |
|---|---|---|---|---|
| frontieres-de-modules › Un nom coupé autour d'un point est lu en entier | Import coupé | D1 | 1.1, 1.2 | `test_a_name_cut_around_its_dots_is_read_in_a_surface`, `test_a_name_of_another_adapter_cut_around_its_dots_is_read`, `test_a_name_cut_around_its_dots_is_refused` (A2, A10, R1, stockage) ; mutations 1, 5 |
| idem | Nom qualifié coupé dans le code | D1, D2 | 1.1, 1.2 | mêmes tests ; mutation 2 |
| idem | Joker coupé | D1 | 1.1, 1.2 | `test_a_wildcard_import_cut_around_its_dots_is_refused`, `test_a_wildcard_import_of_another_adapter_cut_around_its_dots_is_refused`, `test_a_wildcard_import_of_the_facade_cut_around_its_dots_is_refused` ; mutations 3, 4 |
| idem | Cycle et type référencé | D1 | 1.1, 1.2 | `test_a_cycle_closed_by_a_name_cut_around_its_dots_is_refused`, `test_a_name_cut_around_its_dots_references_the_type` |
| idem | Commentaire, littéral et ellipse | D1 | 1.1, 1.2 | `test_a_cut_name_in_a_comment_or_a_literal_is_ignored_and_before_an_ellipsis_is_read` |
| idem | Dépôt au HEAD | D1 | 1.3 | `check-module-boundaries.py`, sortie identique à celle de la base |

### Preuves exécutées le 2026-11-27 (base `79e8f9fc1`, Windows)

- Rouge : 29 sous-cas en échec pour les cliquets (112 tests), 48 pour les adaptateurs (96 tests), 24 pour les packages (42 tests : 12 cycles acceptés, 12 types déclarés sans appelant), tous sur les nouveaux cas.
- Après correction : auto-tests 77, 96, 50, 42, 112 et 3 verts ; sortie de `check-module-boundaries.py` identique à celle de la base, aux fins de ligne près.
- Mutations d'une source Java sur des copies `git archive` de la base ; « après » : copie portant le garde corrigé.

| Mutation | rc base | rc après | Refus après |
|---|---|---|---|
| témoin | 0 | 0 | aucun |
| 1. `import com.minos.registry . ProjectRegistry;` dans une commande de la CLI | 0 | 1 | A11 |
| 2. nom qualifié coupé par un retour à la ligne dans la même commande | 0 | 1 | A11 |
| 3. `import com.minos.runtime.local.process . *;` dans le provider SCIP | 0 | 1 | A10 |
| 4. `import com.minos.registry . *;` dans la façade | 0 | 1 | A11 façade |
| 5. `import org./* x */postgresql.Driver;` dans `minos-bootstrap` | 0 | 1 | R1 |

- Non mesuré : exécution sous Linux ; mutation d'une copie du dépôt pour A8 et A13, prouvées par les auto-tests seulement.
