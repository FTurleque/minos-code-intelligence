# Design

## Contexte

Motivation : voir `proposal.md` (Why). Changement d'outillage : seul le garde `scripts/architecture/check-module-boundaries.py`, son auto-test et ARCH-0087 changent. Le design est réduit au contexte, à l'état vérifié, aux décisions, à la vérification et à la traçabilité.

## État vérifié au HEAD

Relu au commit `acd79cb86` (PR #795).

| Fait | Preuve |
|---|---|
| `check_embedded_adapters` lit les sources de `minos-application`, retire commentaires et littéraux, puis refuse : un fichier qui nomme une API surveillée hors de `INFRASTRUCTURE_API_USERS`, une entrée de cette liste qui n'en nomme plus, une entrée d'adaptateur sans justification ou sans classe, une source hors liste qui nomme un adaptateur | `scripts/architecture/check-module-boundaries.py:1174-1210` |
| `INFRASTRUCTURE_API_USERS` porte des noms sous `com.minos` (`program.analysis.JavaAstParser`) ; `EMBEDDED_ADAPTER_CLASSES` des noms simples (`JavaAstParser`) | `scripts/architecture/module-boundaries.json:222-252` |
| Aucune ligne ne compare les deux listes | `check-module-boundaries.py:1195-1209` |
| 10 utilisateurs d'API, 17 adaptateurs ; les 10 utilisateurs sont tous adaptateurs ; 7 adaptateurs ne nomment aucune API (`FileProgramGraphProvider`, `FingerprintConstrainedJavaProgramGraphProvider`, `JavaInterproceduralFlowResolver`, `JavaProgramGraphEngine`, `JavaSourceProgramGraphProvider`, `JavaSourceWorkspace`, `SourceStateKeyMemo`) | lecture de `module-boundaries.json` |
| Mutation en mémoire (appel de `check_embedded_adapters(adapters=…)` sur le dépôt réel, une entrée retirée à la fois) : 13 retraits refusés, parce qu'une autre source non listée nomme alors la classe ; 4 silencieux : `OllamaEmbeddingProvider`, `JavaAstSupport`, `JavaProgramModel`, `FileProgramGraphProvider` | script exécuté dans le scratchpad de la session le 2026-11-19 |
| L'audit reproduit le cas `OllamaEmbeddingProvider` sur une copie, puis le même retrait plus une référence ajoutée dans `MinosApplicationRuntimeConfiguration` : garde vert | `findings.json`, AUD-ARC-11, preuve |
| `EmbeddedAdapterRuleTest` n'a aucun cas d'entrée retirée alors que la classe nomme encore une API | `scripts/architecture/test_check_module_boundaries.py:1519-1587` |
| ARCH-0087 point 3 décrit les deux listes sans lien entre elles ; statut `Proposed` | `docs/architecture/adr/ARCH-0087-adaptateurs-embarques-et-facade-reduite.md:37` |

Non mesuré : aucune exécution de l'auto-test complet dans le cadre de cette spécification.

## Écarts

| Écart | Source A | Source B | Suite proposée |
|---|---|---|---|
| L'audit présente l'inclusion des utilisateurs d'API dans la liste des adaptateurs comme le correctif complet | `findings.json`, AUD-ARC-11, action | la mutation montre un quatrième retrait silencieux, `FileProgramGraphProvider`, qui ne nomme aucune API du JDK | D2 : résidu accepté et écrit dans ARCH-0087 |
| ARCH-0087 dit « les deux listes ne font que rétrécir » | ARCH-0087:37 | réduire la seconde sans réduire la première lève la protection | le point 3 dit que la première est incluse dans la seconde |

## Exigences couvertes

- frontieres-de-modules › Une classe d'adaptateur embarquée n'est nommée que par sa famille — D1.

## Décisions

### D1 — Inclusion des noms simples des utilisateurs d'API dans la liste des adaptateurs [Proposition]

A19 calcule le nom simple de chaque entrée de `INFRASTRUCTURE_API_USERS` (dernier segment) et refuse celles qui manquent à `EMBEDDED_ADAPTER_CLASSES` : « `<nom>` names `<API>` and is missing from EMBEDDED_ADAPTER_CLASSES; an infrastructure client is an embedded adapter ». Le contrôle se fait sur l'état courant, sans mémoire de l'état précédent. Les deux listes rétrécissent toujours : une classe ne quitte la liste des adaptateurs qu'après avoir cessé de nommer l'API, ou en quittant `minos-application`.

Option écartée : refuser le retrait de toute entrée dont la classe existe encore. Le garde ne voit que l'état courant ; il faudrait une liste figée en double dans le script, et l'ajouter rendrait le fichier de politique (ARCH-0087, source unique des tables) redondant.

Collision de noms simples : deux classes de même nom dans deux packages de `minos-application` rendraient l'inclusion ambiguë. Il n'en existe aucune au HEAD parmi les 17 ; le garde refuse déjà les doublons dans `infrastructureApiUsers` (`check-module-boundaries.py:170`). Le cas de test D1 couvre une entrée qualifiée dont le nom simple est absent.

### D2 — `FileProgramGraphProvider` reste protégé par la revue [Proposition, recommandation]

Ce lecteur de sidecar ne nomme aucune API surveillée ; ses lectures passent par les primitives confinées, gardées par `check-private-io.py`. Options :

- (a) **recommandée** : accepter le résidu et l'écrire dans ARCH-0087 (« une classe d'adaptateur qui ne nomme aucune API du JDK n'est protégée que par la revue de la liste ») ;
- (b) dériver la liste des adaptateurs des classes que `DefaultMinosApplicationComposer` instancie : il instancie aussi `RelationshipProgramGraphProvider`, calcul pur, volontairement absent de la liste ; il faudrait une troisième table d'exceptions.

## Vérification

- Rouge : nouveaux cas d'`EmbeddedAdapterRuleTest` en échec avant la correction.
- Correction : `python scripts/architecture/check-module-boundaries.py` vert sur le dépôt (les 10 classes sont déjà listées) ; `python scripts/architecture/test_check_module_boundaries.py` vert.
- Preuve : la mutation de l'état vérifié rejouée, `OllamaEmbeddingProvider`, `JavaAstSupport` et `JavaProgramModel` refusés ; `FileProgramGraphProvider` silencieux, comme D2 l'écrit.
- `check-ci-wiring.py` et `check-single-execution.py` : l'auto-test est déjà câblé dans le job `invariants`.

## Documentation durable à mettre à jour

- `docs/architecture/adr/ARCH-0087-adaptateurs-embarques-et-facade-reduite.md`, point 3 (inclusion, résidu D2).
- `docs/architecture/diagrams/capacites/frontieres-de-modules.md` : ligne de traçabilité de l'exigence modifiée, nouveau scénario et test, à l'archivage.

## Traçabilité

| Constat | Exigence | Décision | Tâches | Preuve attendue |
|---|---|---|---|---|
| AUD-ARC-11 | frontieres-de-modules › Une classe d'adaptateur embarquée n'est nommée que par sa famille | D1, D2 | 1.1-1.4, 2.1-2.4 | cas rouge puis vert d'`EmbeddedAdapterRuleTest` ; garde vert sur le dépôt ; mutation rejouée |
