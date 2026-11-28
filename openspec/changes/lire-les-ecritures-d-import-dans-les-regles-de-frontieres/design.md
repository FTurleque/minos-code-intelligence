# Design

## Contexte

Motivation : voir `proposal.md` (Why). Changement d'outillage : le lecteur d'imports d'un garde Python, deux règles qui traitent le joker, leurs auto-tests et leur documentation. Le design est réduit au contexte, à l'état vérifié, aux écarts, aux décisions, à la vérification et à la traçabilité ; les autres sections du modèle sont non applicables à un garde qui lit du texte.

## État vérifié au HEAD

Relu au commit `a2988f693` (branche `fix/a11-formes-de-declaration`), le 2026-11-27.

| Fait | Preuve |
|---|---|
| `SOURCE_IMPORT` n'accepte un `import` qu'en début de ligne | `scripts/architecture/module_boundary_sources.py:19` |
| `types_named_by_module` passe tout joker non statique qui ne cible pas un package | `scripts/architecture/module_boundary_ratchets.py:444-448` |
| `check_source_boundaries` (A2) fait de même | `scripts/architecture/module_boundary_adapters.py:69-73` |
| La façade refuse déjà le joker sur un type du moteur | `module_boundary_ratchets.py:333-344` |
| `types_named_by_module` n'a qu'un appelant, `named_type_ratchet_problems`, qui sert A11 (surfaces, `minos-nexus`) et A13 | recherche dans `scripts/` |
| Base : `check-module-boundaries.py` code 0 ; auto-tests 77, 86, 50, 38 et 97 verts | exécutés avant toute modification |

### Lecteurs de `SOURCE_IMPORT`

« En second » : l'import suit un autre import sur la même ligne. La colonne « simple » vaut pour un import qui nomme un type.

| Lecteur | Règles | Simple en second | Joker en second |
|---|---|---|---|
| `engine_type_reader` (`ratchets:296`) | A11, signatures de la façade | **non lu** (statique compris) | non lu |
| `minos_application_engine_wildcard_imports` (`ratchets:333`) | A11, joker de la façade | sans objet | **non lu** |
| `types_named_by_module` (`ratchets:424`) | A11 surfaces et module de contrat, A13 | lu par le nom qualifié resté dans le corps | **non lu** |
| `check_source_boundaries` (`adapters:49`) | A2 sources | lu par le nom qualifié | **non lu** |
| `check_runtime_local_exports` (`adapters:163`) | A10 | lu par le nom qualifié | **non lu** |
| `forbidden_names` (`adapters:22`) | R1, R2, packages du stockage | lu par le nom qualifié | **non lu** quand `.*` suit le préfixe interdit |
| `packages_used_by` (`packages:308`) | A8 | lu par le nom qualifié | **non lu** : arête perdue |
| `unreferenced_production_types` (`packages:106`) | A15 | lu par le nom qualifié | non lu : faux « sans appelant », fail-closed |

Les règles A12, A16, A18, A19 et le refus de la racine de composition cherchent des mots dans le code : la position d'un import ne les concerne pas. `check-private-io.py` a son propre lecteur (`_STATIC_IMPORT` sans ancre, `_IMPORT` qui ne sert qu'à effacer) : un import non effacé ne contient aucun appel qu'il compte.

### Sonde avant correction

Arbres temporaires.

| Source sondée | Verdict |
|---|---|
| `import com.minos.orchestration.IndexingRuntimePorts.*;` dans une surface | accepté |
| le même joker en `import static` dans une surface | refusé (type utilisé, hors liste) |
| le même joker dans la façade | refusé |
| joker sur les membres d'une classe d'adaptateur : A13, A2 | accepté |
| le même, hors du runtime local : A10 | refusé |
| second import simple sur la ligne : surface, A13, A2, A10, A8 | refusé |
| second import simple, joker ou statique sur la ligne : façade | accepté |
| second import joker de package : surface, A13, A2, A10, R1, A8 | accepté |
| second import joker de package : A15 | le type est déclaré sans appelant |
| import après `;` seul, ou après un commentaire de bloc fermé sur la ligne d'un import : façade | accepté |

## Écarts

| Écart | Source A | Source B | Suite proposée |
|---|---|---|---|
| 1. La demande dit que le second import d'une ligne n'est pas lu : un import simple l'est, par son nom qualifié, partout sauf dans la façade | demande | sonde | la correction du lecteur ferme les deux cas |
| 2. A2 laisse passer le même joker sur un type, hors des appelants de `types_named_by_module` ; le scénario « Classe d'adaptateur nommée par une source » cite le joker | sonde | spec `frontieres-de-modules` | traité (D3) |
| 3. Des blancs ou un retour à la ligne autour d'un point (`com.minos.registry . ProjectRegistry`) cachent un import à une surface | sonde | Java accepte ces écritures | hors périmètre : troisième écriture, à instruire à part |
| 4. Un guillemet échappé suivi de deux guillemets ferme à tort un bloc de texte pour `COMMENTS_AND_LITERALS` : la suite du bloc est lue comme du code | revue du diff, auto-test | « Un `import` écrit dans un commentaire ou dans un littéral SHALL rester ignoré » | traité (D1) |
| 5. A10 : un `import static` d'un membre réservé, sans usage, est accepté où qu'il soit dans sa ligne, l'import n'étant pas un usage | lecture de `adapters:207`, sonde | A10 refuse « l'usage des membres réservés » | aucune : un usage reste refusé |

## Décisions

### D1 — Le mot-clé `import` suffit à ouvrir une déclaration [Proposition, recommandation]

- (a) **Recommandé et appliqué.** `SOURCE_IMPORT` perd son ancre de début de ligne : `(?<![\w$])import\s+…;`. `import` est un mot réservé de Java et chaque lecteur efface commentaires et littéraux avant d'appliquer l'expression : le mot n'apparaît plus que dans une déclaration d'import. La garde `(?<![\w$])` écarte un identifiant qui finit par ces lettres.
- (b) Ancrer sur le début de ligne ou un `;` précédent : énumère les prédécesseurs légaux, pour le même résultat.

Aucune liste de lecteurs à corriger un par un : l'expression est unique. La condition « littéraux effacés » vaut aussi pour les blocs de texte : `COMMENTS_AND_LITERALS` y lit les échappements.

### D2 — Un joker sur un type du module visé est refusé et compté [Proposition, recommandation]

- (a) **Recommandé et appliqué.** Dans `types_named_by_module`, le joker non statique sur un type ou un type imbriqué rejoint les jokers refusés, comme le joker de package et comme dans la façade. Il compte aussi comme un usage du type : le message d'un type listé ne demande pas de retirer son entrée.
- (b) Le compter seulement, comme le joker statique : un type listé admet alors l'écriture sans échec, et un type membre hérité d'un autre type entre sous le nom du type importé.

Le joker statique reste compté et non refusé dans une surface : il est lu.

### D3 — A2 traite le joker sur une classe comme un import qui la nomme [Proposition, recommandation]

Seul le joker d'un package d'adaptateur garde son traitement propre ; tout autre import se résout en classe.

## Vérification

- Rouge : les nouveaux cas des trois auto-tests échouent avant la correction, les cas acceptés réussissent.
- Correction : auto-tests verts ; sortie du garde sur le dépôt identique à celle de la base ; une mutation par forme sur des copies du dépôt dans le scratchpad.
- `python .claude/scripts/run_gates.py`, `openspec validate --all --strict` et `python scripts/docs/check-capability-dossiers.py` verts.

## Documentation durable à mettre à jour

`docs/developer/quality-gates.md`, docstrings en anglais des lecteurs modifiés, `docs/architecture/diagrams/capacites/frontieres-de-modules.md` (lignes des exigences ajoutées, à l'archivage).

## Traçabilité

| Exigence (capacité › titre) | Scénario | Conception | Tâche | Test ou preuve |
|---|---|---|---|---|
| frontieres-de-modules › Un import est lu quelle que soit sa position dans la ligne | Second import de la façade, autres positions | D1 | 1.1, 1.2 | `test_an_import_is_read_wherever_it_stands_on_its_line` ; mutation 5 |
| idem | Joker de package en second | D1 | 1.1, 1.2 | `test_a_wildcard_import_following_another_on_its_line_is_refused` (A11, A2, A10, R1, stockage), `test_a_wildcard_import_of_another_adapter_following_another_on_its_line_is_refused` ; mutations 4, 6 |
| idem | Cycle et type référencé | D1 | 1.1, 1.2 | `test_a_cycle_closed_by_a_wildcard_following_another_import_on_its_line_is_refused`, `test_a_package_wildcard_following_another_import_on_its_line_references_the_type` |
| idem | Commentaire et littéral | D1 | 1.1, 1.2 | `test_an_import_written_in_a_comment_or_a_literal_is_not_an_import`, `test_an_escaped_quote_does_not_close_a_text_block` |
| idem | Dépôt au HEAD | D1 | 1.3 | `check-module-boundaries.py`, sortie identique à celle de la base |
| frontieres-de-modules › Un joker sur un type du module visé échoue en A11 et en A13 | Type non listé | D2 | 2.1, 2.2 | `test_a_wildcard_import_of_an_engine_type_is_refused_in_a_surface_and_a_contract_module` ; mutation 1 |
| idem | Type listé | D2 | 2.1, 2.2 | `test_a_wildcard_import_of_a_listed_engine_type_is_refused_without_asking_to_remove_the_entry` |
| idem | Autre adaptateur | D2 | 2.1, 2.2 | `test_a_wildcard_import_of_a_type_of_another_adapter_is_refused` ; mutation 2 |
| idem | Hors cible et statique | D2 | 2.1 | `test_wildcard_imports_outside_the_engine_and_static_ones_are_not_refused` |
| frontieres-de-modules › Un joker sur une classe d'adaptateur échoue en A2 | Membres d'une classe d'adaptateur | D3 | 2.1, 2.2 | `test_a_wildcard_import_on_the_members_of_an_adapter_class_is_refused` ; mutation 3 |
| idem | Hors des adaptateurs | D3 | 2.1 | `test_a_wildcard_import_on_the_members_of_a_domain_type_is_accepted` |

### Preuves exécutées le 2026-11-27 (base `a2988f693`, Windows)

- Rouge : 24 échecs, tous sur les nouveaux cas (15 sur 105 pour les cliquets, 7 sur 92 pour les adaptateurs, 2 sur 40 pour les packages) ; les cas acceptés réussissent.
- Guillemet échappé dans un bloc de texte : un cas rouge à part, vert après correction.
- Après correction : auto-tests 77, 92, 50, 40, 106 et 3 verts ; sortie de `check-module-boundaries.py` identique à celle de la base.
- Mutations sur deux copies `git archive` de la base ; « après » : copie portant le garde corrigé.

| Mutation d'une source Java | rc base | rc après | Refus après |
|---|---|---|---|
| témoin | 0 | 0 | aucun |
| 1. `import ….IndexingRuntimePorts.*;` dans une commande de la CLI | 0 | 1 | A11 : joker, type hors liste |
| 2. `import ….CommandLocator.*;` dans le provider SCIP | 0 | 1 | A13 : joker, compte 10 dépassé |
| 3. `import ….FileSymbolSnapshotStore.*;` dans la CLI | 0 | 1 | A2 |
| 4. `import com.minos.registry.*;` à la suite d'un import de la CLI | 0 | 1 | A11 : joker |
| 5. `import ….ProjectRegistry;` à la suite d'un import de la façade, et un accesseur public qui le rend | 0 | 1 | A11 : accesseur `leak` |
| 6. `import org.postgresql.*;` à la suite d'un import de `minos-bootstrap` | 0 | 1 | R1 |

- Non mesuré : exécution sous Linux.
