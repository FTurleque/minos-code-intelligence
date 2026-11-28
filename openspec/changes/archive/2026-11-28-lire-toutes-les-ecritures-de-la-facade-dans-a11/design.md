# Design

## Contexte

Motivation : voir `proposal.md` (Why). Changement d'outillage : une règle d'un garde Python, son auto-test, sa documentation. Le design est réduit au contexte, à l'état vérifié, aux écarts, aux décisions, à la vérification et à la traçabilité ; les autres sections du modèle sont non applicables à un garde qui lit du texte.

## État vérifié au HEAD

Relu au commit `54f23fa37` (branche `fix/a11-methodes-generiques-de-la-facade`), le 2026-11-27.

| Fait | Preuve |
|---|---|
| `METHOD_HEAD` : le type de retour commence par une lettre, ne contient qu'une espace comme blanc, et le nom commence par une minuscule | `scripts/architecture/module_boundary_ratchets.py:134-136` |
| La table des noms importés exclut les imports `static` et joker | `module_boundary_ratchets.py:258-261` |
| Le joker n'est refusé que si sa cible est un package du moteur et que l'import n'est pas `static` | `module_boundary_ratchets.py:293-302` |
| La méthode statique qui rend `MinosApplicationComposer` est refusée et couverte par un test | `public_composition_root_methods` ; `test_a_public_static_composition_root_is_refused` |
| Base : auto-test des cliquets 89 sur 89, `run_gates.py --fast` 30 sur 30 | exécutés avant toute modification |

### Sonde avant correction

Arbres temporaires, `check_minos_application_accessors` avec une table vide.

| Déclaration ou import | Verdict |
|---|---|
| `public @Deprecated ProjectRegistry x()`, annotation entre modificateurs, après `<T>`, à arguments imbriqués, dans un nom qualifié | accepté |
| `public ProjectRegistry X()`, `public void Leak(ProjectRegistry r)`, `public ProjectRegistry $x()` | accepté |
| `public Map<String,` / `ProjectRegistry> x()` ; nom qualifié coupé sur deux lignes | accepté |
| `public` / `ProjectRegistry` / `x()` sur trois lignes | refusé |
| `import com.minos.orchestration.IndexingRuntimePorts.*;`, avec ou sans `static` | accepté |
| `import static com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;` | accepté |
| `public static MinosApplicationComposer x()` | refusé |

## Écarts

| Écart | Source A | Source B | Suite proposée |
|---|---|---|---|
| 1. La demande nomme cinq formes ; la sonde en trouve des variantes (annotation dans un nom qualifié, nom qualifié coupé, nom en `$`, joker statique) | demande | sonde | traitées avec leur famille |
| 2. Un champ public typé moteur (`public ProjectRegistry registry;`) est accepté | sonde | exigence « La façade ne rend ni type du moteur ni racine de composition », qui nomme méthodes et constructeurs | hors périmètre : étendre l'exigence est une décision |
| 3. Les membres d'un type imbriqué public autre que `Builder` (un `record` public, une classe) sont acceptés | sonde | l'exigence n'exclut nommément que le `Builder` | hors périmètre, même décision |
| 4. Une méthode héritée d'une interface, ou un type membre hérité nommé sans import, n'est pas visible à la lecture de la source | lecture du garde | bytecode | règle complémentaire (D1) |
| 5. Deux `import` sur une même ligne : le second n'est pas lu | sonde | `SOURCE_IMPORT` ancré en début de ligne, partagé par plusieurs règles (`module_boundary_sources.py`) | hors périmètre |
| 6. Côté surface, `import <type du moteur>.*;` n'est ni compté ni refusé (`wildcard` suivi de `continue`) | `module_boundary_ratchets.py:403-406` | exigence « Les types du moteur nommés par une surface le sont par liste fermée » | hors périmètre : autre règle, à instruire à part |

## Décisions

### D1 — Lire l'en-tête par sa structure dans le garde Python [Proposition, recommandation]

- (a) **Recommandé et appliqué.** Les annotations sont effacées (parenthèses équilibrées) et les noms qualifiés recollés dans le texte que lisent toutes les règles de la façade ; l'en-tête d'un membre va du dernier modificateur à la première ponctuation `(`, `;`, `=`, `{` ou `}`, et le nom est le dernier identifiant avant `(`. Aucune classe de caractères n'énumère ce qu'un type peut contenir : la casse, les blancs et les formes de type cessent d'être des angles morts. Un en-tête illisible fait échouer le garde.
- (b) Règle sur le bytecode : ArchUnit 1.5.1, déjà en portée test de `minos-app` (`JavaCodeUnit.getReturnType()`, `JavaType.getAllInvolvedRawTypes()`), ou réflexion dans un test de `minos-application`. Le compilateur ayant résolu les noms, les cinq formes, les imports sur une ligne et les héritages (écarts 4 et 5) se voient sans analyse lexicale ; les champs (écart 2) aussi si la règle les lit. Limites : elle ne tourne que dans les jobs `Verify` (environ 40 min), elle demande une compilation, et elle **ne remplace pas** la lecture de source, qui fournit à A11 la table des accesseurs dont se sert le compte des usages des surfaces, et le refus rapide dans le job `invariants`. Les deux lectures coexisteraient. Aucun prototype écrit : la couverture réelle n'est pas mesurée.
- (c) Élargir encore `METHOD_HEAD` (annotations, majuscules, `\s`) : chaque forme ajoute une alternative, les parenthèses imbriquées des annotations restent hors de portée d'une expression régulière.

(b) reste un complément possible, à décider à part, si les écarts 2 à 5 doivent être fermés.

### D2 — Imports passant par un type du moteur [Proposition, recommandation]

- (a) **Recommandé et appliqué.** Joker (statique ou non) sur un type du moteur : refusé, comme le joker de package, car un nom simple ainsi importé ne se résout pas. `import static` d'un membre précis : le nom entre dans la table des noms importés ; un import simple non statique de même nom l'emporte.
- (b) Résoudre le joker en lisant les types membres de la cible : demande d'analyser les sources du moteur, pour une forme que la façade n'emploie pas.

### D3 — La racine de composition rendue par une méthode statique entre dans les exigences [Proposition, recommandation]

Une exigence ajoutée la nomme. Elle ne change pas le code. L'exigence « La façade ne rend ni type du moteur ni racine de composition » n'est pas modifiée : son énoncé est proche de la limite de 500 caractères.

## Vérification

- Rouge : les nouveaux cas de l'auto-test échouent avant la correction. Pour D3, sans rouge possible, le test est éprouvé par mutation d'une copie du garde.
- Correction : auto-test vert, garde vert sur le dépôt, sortie comparée à celle de la base ; mutations de `MinosApplication` sur des copies du dépôt, une par forme.
- `python .claude/scripts/run_gates.py` et `openspec validate --all --strict` verts.

## Documentation durable à mettre à jour

`docs/developer/quality-gates.md` (puce A11 des accesseurs), docstring de `check_minos_application_accessors` (anglais), `docs/architecture/diagrams/capacites/frontieres-de-modules.md` (lignes des exigences ajoutées, à l'archivage).

## Traçabilité

| Exigence (capacité › titre) | Scénario | Conception | Tâche | Test ou preuve |
|---|---|---|---|---|
| frontieres-de-modules › L'écriture d'une déclaration de la façade ne contourne pas A11 | Annotation, nom, lignes, sans type du moteur, illisible, HEAD | D1 | 1.1 à 1.3 | `test_an_annotation_inside_a_declaration_does_not_hide_it`, `test_a_member_is_read_whatever_the_case_of_its_name`, `test_a_header_written_over_several_lines_is_read`, `test_declarations_without_an_engine_type_are_accepted`, `test_an_unreadable_member_header_fails_closed` ; mutations 1 à 3 |
| frontieres-de-modules › Un import qui passe par un type du moteur ne contourne pas A11 | Joker, import statique, hors moteur | D2 | 1.1 à 1.3 | `test_a_wildcard_import_through_an_engine_type_is_refused`, `test_a_static_import_of_a_nested_engine_type_is_read`, `test_a_static_import_outside_the_engine_or_shadowed_is_accepted` ; mutations 4, 4b, 5 |
| frontieres-de-modules › La façade ne rend pas la racine de composition par une méthode statique | Méthode statique | D3 | 1.4 | `test_a_public_static_composition_root_is_refused` ; mutation du garde |

### Preuves exécutées le 2026-11-27 (base `54f23fa37`)

- Rouge : auto-test des cliquets, 20 échecs sur 97, tous sur les nouveaux cas ; les cas acceptés réussissent. Après correction, 97 sur 97.
- Mutations de `MinosApplication` sur une copie `git archive` de la base ; « après » : même copie portant le garde corrigé.

| Mutation | rc base | rc après | Message après |
|---|---|---|---|
| témoin | 0 | 0 | sortie identique à celle de la base |
| 1. `public @Deprecated ProjectRegistry leak()` | 0 | 1 | `leak`, `registry.ProjectRegistry` |
| 2. `public ProjectRegistry Leak()` | 0 | 1 | `Leak`, `registry.ProjectRegistry` |
| 3. `Map<String,` puis `ProjectRegistry>` à la ligne | 0 | 1 | `leak`, `registry.ProjectRegistry` |
| 4. `import ….IndexingRuntimePorts.*;` (import nommé de `IndexerExecutor` retiré) | 0 | 1 | cible du joker |
| 4b. même joker en `import static` | 0 | 1 | cible du joker |
| 5. `import static ….IndexingRuntimePorts.IndexerExecutor;` | 0 | 1 | `leak`, `orchestration.IndexingRuntimePorts` |
| `public static MinosApplicationComposer leak()` | 1 | 1 | racine de composition |

- Les membres lus sur la façade réelle sont les mêmes avant et après (26).
- D3 : sur une copie du garde qui ignore les méthodes statiques, `test_a_public_static_composition_root_is_refused` échoue (3 variantes).
- `run_gates.py` 88 sur 88, `openspec validate --all --strict` 28 sur 28, `archgate check` 11 sur 11, `check-capability-dossiers.py` vert.
- Non mesuré : exécution sous Linux ; prototype de la règle sur le bytecode (D1 b).
