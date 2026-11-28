# Design

## Contexte

Changement purement documentaire : le texte de scénarios de `frontieres-de-modules` (motivation : proposal.md, Why). Aucun code, aucun gate, aucun dossier de capacité n'est modifié.

L'auto-test du garde de frontières est réparti en plusieurs fichiers `scripts/architecture/test_check_module_boundaries*.py` :

| Fichier | Cas concernés par ce changement |
|---|---|
| `test_check_module_boundaries_facade.py` | façade `MinosApplication` (`MinosApplicationAccessorRuleTest`, `MinosApplicationAccessorRatchetTest`) |
| `test_check_module_boundaries_ratchets.py` | types du moteur nommés par les surfaces, lancement de processus, mesure du code concret |
| `test_check_module_boundaries_adapters.py` | classes d'assemblage, adaptateurs embarqués, packages du stockage |
| `test_check_module_boundaries.py` | POM, politique, câblage du point d'entrée |

## État vérifié au HEAD

Relu au commit `69c7c679c`.

| Fait | Preuve |
|---|---|
| L'auto-test des cliquets ne charge aucun cas de façade | `unittest.defaultTestLoader.loadTestsFromName` : 70 cas, 0 d'une classe `MinosApplication*Test` ; 55 cas dans `test_check_module_boundaries_facade.py` |
| `test_check_module_boundaries.py` ne charge aucun cas de façade ni des cliquets | même chargement : 77 cas, 0 d'une classe `MinosApplication*Test`, `EngineTypesNamedBySurfaceRuleTest` ou `ProcessLauncherRuleTest` |
| Trois commandes de scénario échouent | `python scripts/architecture/test_check_module_boundaries.py <classe>` : `AttributeError` pour `ProcessLauncherRuleTest`, `StoragePackageIsolationRuleTest`, `EngineConcreteCodeMeasureTest` |
| `openspec/specs/frontieres-de-modules/spec.md` porte 20 puces `WHEN` qui lancent l'auto-test des cliquets, dont 17 pour un cas de façade | `git grep -c 'test_check_module_boundaries_ratchets' -- openspec/specs/frontieres-de-modules/spec.md` : 20 |
| Les 3 autres décrivent un cas des surfaces, présent dans cet auto-test | `scripts/architecture/test_check_module_boundaries_ratchets.py#test_a_wildcard_import_of_an_engine_type_is_refused_in_a_surface_and_a_contract_module`, `#test_a_cut_name_in_a_comment_or_a_literal_is_ignored_and_before_an_ellipsis_is_read`, `#test_a_type_named_by_an_annotation_inside_a_name_is_still_read` |

## Écarts

| Écart | Source A | Source B | Suite proposée |
|---|---|---|---|
| Un scénario lance `test_check_module_boundaries.py` pour une règle dont les cas sont dans `…_adapters.py`, et n'attend que le code de sortie 0 | `openspec/specs/frontieres-de-modules/spec.md:503` (« Dépôt conforme », classe d'index mémoire) | `scripts/architecture/test_check_module_boundaries_adapters.py#test_the_real_tree_keeps_the_memory_index_in_the_core` | inchangé : le résultat attendu est exact |

## Exigences couvertes

Les dix-neuf exigences du delta `specs/frontieres-de-modules/spec.md`, réalisées par les décisions D1 et D2.

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

[Décision] Chaque scénario garde sa forme de commande (`python …` ou `python -m unittest …`), seul le nom du fichier change. Trois `GIVEN` disent « l'auto-test des cliquets » pour un cas de façade : ils disent « l'auto-test de la façade ». Laisser ces `GIVEN` ferait se contredire un scénario ; le troisième porte à lui seul la désignation de « le même auto-test » dans son `WHEN`.

Les scénarios qui disent « l'auto-test du garde » ou « le même auto-test » après un `WHEN` corrigé restent tels quels.

### D2 — Désigner le fichier qui contient le cas, avec le nom de classe quand le scénario le donne

[Décision] Un scénario qui nomme une classe de test la garde en argument ; la classe est dans le fichier désigné. Un scénario qui attend le résultat d'un cas précis désigne le fichier de ce cas. Un scénario qui n'attend que le code de sortie d'un auto-test reste tel quel.

## Qualification des capacités

Non applicable : aucune capacité nouvelle ou modifiée dans son comportement.

## Risques et compromis

- [Risque] Aucun gate ne vérifie le fichier cité par un `WHEN`, la désignation peut redevenir fausse → hors périmètre ; la table de traçabilité nomme le cas de chaque scénario.

## Vérification

- `openspec validate --all --strict`
- `python scripts/docs/check-capability-dossiers.py`
- `python .claude/scripts/run_gates.py`
- Les commandes des 11 scénarios quittant `test_check_module_boundaries.py` sortent en code 0 sur les fichiers désignés.
- Essai d'archivage sur une copie hors du dépôt : la spécification fusionnée ne diffère de la courante que par les 31 puces visées, et le dossier de capacité reste cohérent avec elle.

Non mesuré : l'exécution sous Linux (texte seul, aucun comportement propre à une plateforme).

## Documentation durable à mettre à jour

Aucune. `## Documentation liée` de la spécification est conservée par l'archivage.

## Traçabilité

Une ligne par scénario dont le texte change. La ligne est celle de la puce `WHEN` au commit `69c7c679c` (puce `GIVEN` pour `:1934`). Le fichier de chaque cas est donné par la colonne Auto-test : `facade`, `ratchets` ou `adapters`, pour `scripts/architecture/test_check_module_boundaries_<nom>.py`. Les autres scénarios des dix-neuf exigences sont repris sans changement.

| Exigence (capacité `frontieres-de-modules`) | Scénario (ligne) | Auto-test | Conception | Tâche | Test ou preuve |
|---|---|---|---|---|---|
| Un accesseur de MinosApplication appelé par une surface compte comme un usage du type rendu | Accesseur appelé sans nommer le type (`:609`) | facade | D1 | 1.2 | `test_an_accessor_called_without_naming_the_type_is_refused` |
| idem | Import remplacé par var (`:614`) | facade | D1 | 1.2 | `test_replacing_the_import_by_var_keeps_the_count` |
| idem | Méthode homonyme d'arité différente (`:619`) | facade | D1 | 1.2 | `test_a_homonym_with_another_arity_is_ignored` |
| Les accesseurs de MinosApplication typés moteur sont tenus par un cliquet | Entrée ajoutée à la table vide refusée (`:995`) | facade | D1 | 1.2 | `test_an_entry_added_to_the_empty_table_is_refused` |
| La façade ne rend ni type du moteur ni racine de composition | Racine de composition réintroduite refusée (`:1541`) | facade | D1 | 1.2 | `test_a_public_composition_root_is_refused_and_a_package_private_one_accepted` |
| idem | Accesseur typé moteur réintroduit refusé (`:1546`) | facade | D1 | 1.2 | `test_an_engine_accessor_reintroduced_with_an_empty_table_is_refused` |
| idem | Paramètre typé moteur refusé (`:1551`) | facade | D1 | 1.2 | `test_a_public_signature_taking_an_engine_type_is_refused` |
| idem | Le Builder garde ses paramètres du moteur (`:1556`) | facade | D1 | 1.2 | `test_the_builder_keeps_its_engine_parameters` |
| Une méthode générique ou un import joker de la façade ne contourne pas A11 | Retour typé moteur d'une méthode statique (`:1776`) | facade | D1 | 1.2 | `test_a_public_static_method_returning_an_engine_type_is_refused` |
| idem | Type du moteur porté par une borne (`:1781`) | facade | D1 | 1.2 | `test_a_public_signature_taking_an_engine_type_is_refused` |
| L'écriture d'une déclaration de la façade ne contourne pas A11 | Annotation à l'intérieur de la déclaration (`:1821`) | facade | D1 | 1.2 | `test_an_annotation_inside_a_declaration_does_not_hide_it` |
| Un import qui passe par un type du moteur ne contourne pas A11 | Joker sur les membres d'un type du moteur (`:1853`) | facade | D1 | 1.2 | `test_a_wildcard_import_through_an_engine_type_is_refused` |
| La façade ne rend pas la racine de composition par une méthode statique | Racine de composition rendue par une méthode statique (`:1871`) | facade | D1 | 1.2 | `test_a_public_static_composition_root_is_refused` |
| La façade n'a aucun champ public typé moteur | Champ typé moteur refusé (`:1879`) | facade | D1 | 1.2 | `test_a_public_field_of_an_engine_type_is_refused` |
| Le `Builder` est le seul type imbriqué public de la façade | Type imbriqué public refusé (`:1901`) | facade | D1 | 1.2 | `test_a_public_nested_type_other_than_the_builder_is_refused` |
| Un import est lu quelle que soit sa position dans la ligne | Second import d'une ligne dans la façade (`:1914`) | facade | D1 | 1.2 | `test_an_import_is_read_wherever_it_stands_on_its_line` |
| idem | Autres positions (`:1930`) | facade | D1 | 1.2 | `test_an_import_is_read_wherever_it_stands_on_its_line` |
| idem | Import écrit dans un commentaire ou un littéral (`:1934`) | facade | D1 | 1.2 | `test_an_import_written_in_a_comment_or_a_literal_is_not_an_import` |
| Le lancement de processus hors du runtime local est tenu par liste fermée | Runtime tenu par une variable (`:411`) | ratchets | D2 | 2.2 | `test_runtime_exec_through_a_variable_a_parameter_or_a_method_reference_is_refused` |
| Les familles de l'indexation ne sont plus nommées par les surfaces | Retour d'un type retiré refusé (`:529`) | ratchets | D2 | 2.2 | `test_a_type_removed_by_the_first_lot_is_refused_again_against_the_real_cli_list` |
| Le contrôle hébergé est consommé par une façade applicative | Aucune entrée hosted dans les tables (`:714`) | ratchets | D2 | 2.2 | `test_no_surface_uses_the_hosted_control_plane_of_the_engine` |
| Les résultats de requête sont des modèles de lecture publiés | Les entrées de requête du dépôt réel (`:768`) | ratchets | D2 | 2.2 | `test_the_surfaces_read_git_through_the_application_and_keep_only_the_published_read_models` |
| Les sous-packages de com.minos.storage appartiennent à minos-storage | Sous-package du stockage dans le moteur (`:936`) | adapters | D2 | 2.2 | `test_a_package_below_the_storage_root_outside_minos_storage_is_refused` |
| La part de code concret du moteur est mesurée sans cliquet | Mesure sur un arbre de test (`:949`) | ratchets | D2 | 2.2 | `test_each_file_counts_for_its_first_top_level_declaration_with_all_its_lines` |
| Les accesseurs de MinosApplication typés moteur sont tenus par un cliquet | Une surface qui atteint la racine de composition (`:985`) | facade | D2 | 2.2 | `test_a_surface_reaching_the_composition_root_is_refused` |
| idem | Un accesseur retiré sans mettre la table à jour (`:990`) | facade | D2 | 2.2 | `test_an_entry_without_a_public_accessor_is_refused` |
| Les API d'infrastructure du JDK nommées par l'application le sont par liste fermée | Nouveau client HTTP dans l'application (`:1302`) | adapters | D2 | 2.2 | `test_a_new_http_client_of_the_application_is_refused_with_the_file_and_the_api` |
| Une classe d'adaptateur embarquée n'est nommée que par sa famille | Entrée retirée alors que la classe nomme encore une API d'infrastructure (`:1340`) | adapters | D2 | 2.2 | `test_an_api_user_missing_from_the_adapters_is_refused_with_the_class_and_the_list`, `test_an_api_user_missing_from_the_adapters_is_refused_even_when_another_source_names_it` |
| Les classes de production de minos-app sont tenues par liste fermée | Nouvelle classe dans l'assemblage (`:1367`) | adapters | D2 | 2.2 | `test_a_new_production_class_is_refused_with_its_name_and_the_advice` |

## Questions ouvertes

Aucune.
