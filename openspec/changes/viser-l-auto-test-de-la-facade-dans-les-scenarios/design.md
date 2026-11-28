# Design

## Contexte

Changement purement documentaire : le texte de scénarios de `frontieres-de-modules` (motivation : proposal.md, Why). Aucun code, aucun gate, aucun dossier de capacité n'est modifié.

L'auto-test du garde de frontières est réparti en plusieurs fichiers `scripts/architecture/test_check_module_boundaries*.py`. Les cas de la façade `MinosApplication` sont les classes `MinosApplicationAccessorRuleTest` et `MinosApplicationAccessorRatchetTest` de `test_check_module_boundaries_facade.py` ; les cas des surfaces, d'A12 et d'A13 sont dans `test_check_module_boundaries_ratchets.py`.

## État vérifié au HEAD

Relu au commit `69c7c679c`.

| Fait | Preuve |
|---|---|
| L'auto-test des cliquets ne charge aucun cas de façade | `unittest.defaultTestLoader.loadTestsFromName` : 70 cas, 0 d'une classe `MinosApplication*Test` ; 55 cas de façade dans `test_check_module_boundaries_facade.py` |
| `openspec/specs/frontieres-de-modules/spec.md` porte 20 puces `WHEN` qui lancent l'auto-test des cliquets | `git grep -c 'test_check_module_boundaries_ratchets' -- openspec/specs/frontieres-de-modules/spec.md` : 20 |
| 17 d'entre elles décrivent un cas de façade | table de traçabilité |
| 3 décrivent un cas des surfaces, présent dans l'auto-test des cliquets | `scripts/architecture/test_check_module_boundaries_ratchets.py#test_a_wildcard_import_of_an_engine_type_is_refused_in_a_surface_and_a_contract_module`, `#test_a_cut_name_in_a_comment_or_a_literal_is_ignored_and_before_an_ellipsis_is_read`, `#test_a_type_named_by_an_annotation_inside_a_name_is_still_read` |
| Les preuves du dossier de capacité citent l'auto-test de la façade | `docs/architecture/diagrams/capacites/frontieres-de-modules.md:154`, `:176`, `:202`, `:213-220` |

## Écarts

Hors périmètre de ce changement : des scénarios lancent `test_check_module_boundaries.py` pour un cas que ce fichier ne contient pas. Les lignes citées sont celles de `openspec/specs/frontieres-de-modules/spec.md` au commit `69c7c679c`.

| Écart | Source A | Source B | Suite proposée |
|---|---|---|---|
| Classe nommée absente du fichier lancé : la commande échoue (`AttributeError`, exécutée) | `:411` (`ProcessLauncherRuleTest`), `:949` (`EngineConcreteCodeMeasureTest`), `:936` (`StoragePackageIsolationRuleTest`) | les deux premières classes sont dans `…_ratchets.py`, la troisième dans `…_adapters.py` | un autre changement |
| Cas de façade : la commande réussit sans l'exécuter | `:985`, `:990` | `…_facade.py#test_a_surface_reaching_the_composition_root_is_refused`, `#test_an_entry_without_a_public_accessor_is_refused` | un autre changement ; l'exigence est reprise ici, ces deux puces restent telles quelles |
| Cas porté par un autre auto-test (lecture des identifiants, non exécuté) | `:529`, `:768`, `:1302`, `:1340`, `:1367` | `…_ratchets.py` (`:529`, `:768`), `…_adapters.py` (`:1302`, `:1340`, `:1367`) | un autre changement |

## Exigences couvertes

Les dix exigences du delta `specs/frontieres-de-modules/spec.md`, réalisées par la décision D1.

## Objectifs et non-objectifs

Non applicable : changement documentaire.

## Responsabilités et frontières des composants

Non applicable : changement documentaire.

## Modèle de domaine et invariants

Non applicable : changement documentaire.

## Traitements et interactions

Non applicable : changement documentaire.

## États et transitions

Non applicable : changement documentaire.

## Contrats d'interface

Non applicable : changement documentaire.

## Persistance et intégrité

Non applicable : changement documentaire.

## Transactions, concurrence et idempotence

Non applicable : changement documentaire.

## Sécurité et isolation

Non applicable : changement documentaire.

## Erreurs et reprise

Non applicable : changement documentaire.

## Observabilité

Non applicable : changement documentaire.

## Performance et mesure

Non applicable : changement documentaire.

## Compatibilité, migration et retour arrière

Non applicable : changement documentaire.

## Diagrammes

Non applicable : changement documentaire.

## Décisions

### D1 — Désigner l'auto-test de la façade dans le `WHEN` et dans le `GIVEN` qui le nomme

[Décision] Chaque scénario de façade garde sa forme `python …test_check_module_boundaries_facade.py` ou `python -m unittest …`, seul le nom du fichier change. Trois `GIVEN` disent « l'auto-test des cliquets » pour un cas de façade : ils disent « l'auto-test de la façade ». Laisser ces `GIVEN` ferait se contredire un scénario ; le troisième porte à lui seul la désignation de « le même auto-test » dans son `WHEN`.

Les scénarios qui disent « l'auto-test du garde » ou « le même auto-test » après un `WHEN` corrigé restent tels quels.

## Qualification des capacités

Non applicable : aucune capacité nouvelle ou modifiée dans son comportement.

## Risques et compromis

- [Risque] Aucun gate ne vérifie le fichier cité par un `WHEN`, la désignation peut redevenir fausse → hors périmètre ; la table de traçabilité nomme le cas de chaque scénario.

## Vérification

- `openspec validate --all --strict`
- `python scripts/docs/check-capability-dossiers.py`
- `python .claude/scripts/run_gates.py`
- `python scripts/architecture/test_check_module_boundaries_facade.py` : les cas désignés passent.
- Essai d'archivage sur une copie hors du dépôt : la spécification fusionnée ne diffère de la courante que par les 20 puces visées, et le dossier de capacité reste cohérent avec elle.

Non mesuré : l'exécution sous Linux (texte seul, aucun comportement propre à une plateforme).

## Documentation durable à mettre à jour

Aucune. `## Documentation liée` de la spécification est conservée par l'archivage.

## Traçabilité

Une ligne par scénario dont le texte change. La ligne est celle de la puce `WHEN` au commit `69c7c679c` (puce `GIVEN` pour le dernier). Les cas sont dans `scripts/architecture/test_check_module_boundaries_facade.py`. Les autres scénarios des dix exigences sont repris sans changement.

| Exigence (capacité `frontieres-de-modules`) | Scénario (ligne) | Conception | Tâche | Test ou preuve |
|---|---|---|---|---|
| Un accesseur de MinosApplication appelé par une surface compte comme un usage du type rendu | Accesseur appelé sans nommer le type (`:609`) | D1 | 1.2 | `test_an_accessor_called_without_naming_the_type_is_refused` |
| idem | Import remplacé par var (`:614`) | D1 | 1.2 | `test_replacing_the_import_by_var_keeps_the_count` |
| idem | Méthode homonyme d'arité différente (`:619`) | D1 | 1.2 | `test_a_homonym_with_another_arity_is_ignored` |
| Les accesseurs de MinosApplication typés moteur sont tenus par un cliquet | Entrée ajoutée à la table vide refusée (`:995`) | D1 | 1.2 | `test_an_entry_added_to_the_empty_table_is_refused` |
| La façade ne rend ni type du moteur ni racine de composition | Racine de composition réintroduite refusée (`:1541`) | D1 | 1.2 | `test_a_public_composition_root_is_refused_and_a_package_private_one_accepted` |
| idem | Accesseur typé moteur réintroduit refusé (`:1546`) | D1 | 1.2 | `test_an_engine_accessor_reintroduced_with_an_empty_table_is_refused` |
| idem | Paramètre typé moteur refusé (`:1551`) | D1 | 1.2 | `test_a_public_signature_taking_an_engine_type_is_refused` |
| idem | Le Builder garde ses paramètres du moteur (`:1556`) | D1 | 1.2 | `test_the_builder_keeps_its_engine_parameters` |
| Une méthode générique ou un import joker de la façade ne contourne pas A11 | Retour typé moteur d'une méthode statique (`:1776`) | D1 | 1.2 | `test_a_public_static_method_returning_an_engine_type_is_refused` |
| idem | Type du moteur porté par une borne (`:1781`) | D1 | 1.2 | `test_a_public_signature_taking_an_engine_type_is_refused` |
| L'écriture d'une déclaration de la façade ne contourne pas A11 | Annotation à l'intérieur de la déclaration (`:1821`) | D1 | 1.2 | `test_an_annotation_inside_a_declaration_does_not_hide_it` |
| Un import qui passe par un type du moteur ne contourne pas A11 | Joker sur les membres d'un type du moteur (`:1853`) | D1 | 1.2 | `test_a_wildcard_import_through_an_engine_type_is_refused` |
| La façade ne rend pas la racine de composition par une méthode statique | Racine de composition rendue par une méthode statique (`:1871`) | D1 | 1.2 | `test_a_public_static_composition_root_is_refused` |
| La façade n'a aucun champ public typé moteur | Champ typé moteur refusé (`:1879`) | D1 | 1.2 | `test_a_public_field_of_an_engine_type_is_refused` |
| Le `Builder` est le seul type imbriqué public de la façade | Type imbriqué public refusé (`:1901`) | D1 | 1.2 | `test_a_public_nested_type_other_than_the_builder_is_refused` |
| Un import est lu quelle que soit sa position dans la ligne | Second import d'une ligne dans la façade (`:1914`) | D1 | 1.2 | `test_an_import_is_read_wherever_it_stands_on_its_line` |
| idem | Autres positions (`:1930`) | D1 | 1.2 | `test_an_import_is_read_wherever_it_stands_on_its_line` |
| idem | Import écrit dans un commentaire ou un littéral (`:1934`) | D1 | 1.2 | `test_an_import_written_in_a_comment_or_a_literal_is_not_an_import` |

## Questions ouvertes

Aucune.
