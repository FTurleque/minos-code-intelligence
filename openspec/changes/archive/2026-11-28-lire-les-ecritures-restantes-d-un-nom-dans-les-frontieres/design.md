# Design

## Contexte

Motivation : voir `proposal.md` (Why). Changement d'outillage : la lecture des sources Java d'un garde Python, ses auto-tests et leur documentation. Le design est réduit au contexte, à l'état vérifié, aux écarts, aux décisions, à la vérification et à la traçabilité ; les autres sections du modèle sont non applicables à un garde qui lit du texte.

## État vérifié au HEAD

Relu au commit `b63562c6d` (branche `fix/a11-ecritures-d-import`), le 2026-11-27.

| Fait | Preuve |
|---|---|
| Les règles de frontières lisent le texte écrit des sources Java | `module_boundary_adapters.py:25`, `:66`, `:193`, `:296` ; `module_boundary_packages.py:84`, `:128`, `:170`, `:287` ; `module_boundary_ratchets.py:293`, `:441`, `:567`, `:608`, `:701`, `:748` ; `module_boundary_documents.py:133`, `:212` ; `module_boundary_sources.py:105` |
| `check-private-io.py` décode les échappements avant de découper | `scripts/architecture/check-private-io.py:213`, `:218`, `:413` |
| La façade retire les annotations avant de recoller | `module_boundary_ratchets.py:263`, `:293` |
| `INFRASTRUCTURE_API` exige un nom après `java.net.http.` et `javax.tools.` | `module_boundary_adapters.py:270` |

### Sonde avant correction

Arbres temporaires.

| Écriture | Verdict |
|---|---|
| lettre échappée dans `ProcessBuilder` (A12), `ServiceLoader` (A16), l'index mémoire (A18) | accepté |
| `\u000a` ou `\u000d` dans un commentaire de ligne, puis un nom d'adaptateur ou un lancement de processus | accepté |
| annotation, avec ou sans arguments, deux annotations, `@` échappé devant la dernière partie d'un nom (A2) | accepté |
| A19 : `java.net.http.*`, `javax.tools.*`, `com.sun.source.*`, `HttpURLConnection` sous `java.net.*`, nom annoté | accepté |
| A19 : `com.sun.source.tree.*`, `import static javax.tools.ToolProvider.*` | refusé |

## Écarts

| Écart | Source A | Source B | Suite proposée |
|---|---|---|---|
| 1. L'écart 2 du changement précédent ne cite que les règles de nom : les échappements cachent aussi les mots de A12, A16 et A18 | `design.md` de `lire-les-noms-coupes-autour-d-un-point-dans-les-frontieres` | sonde | traité ici |
| 2. Un échappement de fin de ligne décodé ajoute une ligne : A16 et A18 numérotent le texte décodé | décodage avant lecture | `javac` | aucune : seul le numéro cité par un échec peut différer |
| 3. La mesure du code concret du moteur compte le texte écrit | `module_boundary_ratchets.py:582` | décodage des règles | aucune : mesure, pas une règle |
| 4. Le compilateur ignore certains caractères à l'intérieur d'un identificateur (écrits tels quels ou échappés, comme `\u0000`) : le garde les garde, et le nom lui échappe | revue du diff | `javac` | hors périmètre : écriture à instruire à part |
| 5. Les règles Archgate compagnes d'ARCH-0068 lisent les déclarations `package` telles qu'elles sont écrites | `ARCH-0068-…rules.ts` | `read_java` | hors périmètre : une déclaration `package` échappée n'existe pas dans le dépôt |

## Décisions

### D1 — Un décodage partagé, appliqué à la lecture [Proposition, recommandation]

- (a) **Recommandé et appliqué.** `decode_unicode_escapes` passe de `check-private-io.py` à `java_escapes.py`, sans dépendance, et lit chaque fin de ligne, retour chariot compris, comme un saut de ligne ; `read_java` dans `module_boundary_sources.py` est la lecture de tout source Java d'une règle.
- (b) Décoder dans `name_reading_code` : les règles qui cherchent des mots (A12, A16, A18) resteraient aveugles.
- (c) Copier la fonction dans le garde : deux copies à tenir alignées.

### D2 — L'annotation sort du nom, elle ne disparaît pas [Proposition, recommandation]

- (a) **Recommandé et appliqué.** `name_reading_code` déplace à la fin du texte une annotation placée entre les parties d'un nom : le nom se recolle, le type de l'annotation et ses arguments restent lus.
- (b) Retirer les annotations, comme la façade : un type nommé par la seule annotation deviendrait invisible, et A15 le déclarerait sans appelant.

La lecture d'une annotation et de ses arguments est commune à la façade et à cette lecture.

### D3 — `java.net.*` ne compte que si `HttpURLConnection` est nommé [Proposition, recommandation]

`java.net` porte aussi `URI` et `URL`, qui ne sont pas une API d'infrastructure d'A19 : refuser le joker seul refuserait un usage permis.

## Vérification

- Rouge : les nouveaux cas des auto-tests échouent avant la correction, les cas acceptés réussissent.
- Correction : auto-tests verts, dont `test_check_private_io.py` ; sortie des deux gardes sur le dépôt identique à celle de la base ; une mutation par écriture sur des copies du dépôt dans le scratchpad.
- `python .claude/scripts/run_gates.py`, `openspec validate --all --strict` et `python scripts/docs/check-capability-dossiers.py` verts.

## Documentation durable à mettre à jour

`docs/developer/quality-gates.md`, docstrings en anglais des lecteurs modifiés, `docs/architecture/diagrams/capacites/frontieres-de-modules.md` (lignes des exigences ajoutées, à l'archivage).

## Traçabilité

| Exigence (capacité › titre) | Scénario | Conception | Tâche | Test ou preuve |
|---|---|---|---|---|
| frontieres-de-modules › Un échappement Unicode ne cache rien aux règles de frontières | Nom ou mot échappé | D1 | 1.1, 1.2 | `test_a_name_written_with_a_unicode_escape_is_refused` (A2), `..._is_read` (A11), `test_a_launcher_written_with_a_unicode_escape_is_refused`, `test_a_call_written_...`, `test_a_word_written_...`, `test_a_package_declared_with_a_unicode_escape_is_the_same_package` ; mutation 1 |
| idem | Fin de commentaire échappée | D1 | 1.1, 1.2 | `test_an_escaped_line_break_ends_a_line_comment` ; mutation 2 |
| idem | Barre oblique inverse échappée | D1 | 1.1 | `test_an_escaped_backslash_opens_no_escape` |
| frontieres-de-modules › Une annotation dans un nom qualifié ne le cache pas | Nom annoté | D2 | 2.1, 2.2 | `test_a_name_interrupted_by_an_annotation_is_refused` (A2), `test_an_annotation_inside_a_qualified_name_does_not_hide_it` (A11), `test_a_name_of_another_adapter_interrupted_by_an_annotation_is_read` (A13), `test_a_cycle_closed_by_an_escaped_or_annotated_name_is_refused` (A8), `test_an_escaped_annotated_or_annotating_name_references_the_type` (A15) ; mutation 3 |
| idem | Type nommé par l'annotation | D2 | 2.1, 2.2 | `test_a_type_named_by_an_annotation_inside_a_name_is_still_read` |
| frontieres-de-modules › A19 lit le joker sur une API d'infrastructure | Joker d'infrastructure | D3 | 3.1, 3.2 | `test_a_wildcard_import_of_an_infrastructure_package_is_refused` ; mutation 4 |
| idem | Joker de java.net | D3 | 3.1, 3.2 | `test_the_http_connection_under_a_wildcard_of_java_net_is_refused`, `test_a_wildcard_of_java_net_without_the_http_connection_is_accepted` ; mutation 5 |
| idem | Dépôt au HEAD | D1, D2, D3 | 4.1 | les deux gardes, sortie identique à celle de la base |

### Preuves exécutées le 2026-11-27 (base `b63562c6d`, Windows)

- Rouge : 10 sous-cas en échec pour les cliquets (117 tests), 16 pour les adaptateurs (106 tests), 9 pour les packages (45 tests), tous sur les nouveaux cas ; les cas acceptés réussissent.
- Revue du diff : retour chariot échappé et message sans fichier rouges avant correction (adaptateurs et E/S privées) ; les cas de chaque lecture échouent avec les lecteurs de la base ; deux mutants de la lecture des annotations (récursion et saut retirés) échouent.
- Après correction : auto-tests 77, 111, 52, 47, 119, 3 et 63 (E/S privées) verts ; sorties de `check-module-boundaries.py` et de `check-private-io.py` identiques à celles de la base, aux fins de ligne près.
- Mutations d'une source Java sur des copies `git archive` de la base ; « après » : copie portant le garde corrigé.

| Mutation | rc base | rc après | Refus après |
|---|---|---|---|
| témoin | 0 | 0 | aucun |
| 1. point écrit `\u002e` dans une commande de la CLI | 0 | 1 | A11 |
| 2. lancement de processus après `// \u000a` dans le moteur | 0 | 1 | A12 |
| 3. `com.minos.registry.@Deprecated ProjectRegistry` dans la CLI | 0 | 1 | A11 |
| 4. `import java.net.http.*;` dans `minos-application` | 0 | 1 | A19 |
| 5. `import java.net.*;` et un champ `HttpURLConnection` dans `minos-application` | 0 | 1 | A19 |

- Non mesuré : exécution sous Linux.
