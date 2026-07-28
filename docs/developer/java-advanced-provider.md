# Java Advanced Provider — M22

M22 fournit un provider Java local de référence qui alimente le `ProgramGraph` M19 à partir de l’AST du compilateur JDK, sans confondre parsing, résolution de types et comportement runtime.

## Architecture

```text
active structured snapshot
      │
      ├── Java fileIds observed by snapshot symbols
      │       ↓
      │  source confinement + size bounds
      │       ↓
      │  JDK compiler parse (`-proc:none`)
      │       ↓
      │  JavaSourceProgramGraphProvider
      │       ├── CONTROL_FLOW
      │       ├── DEF_USE
      │       ├── ARGUMENT_FLOW / RETURN_FLOW
      │       └── configured TAINT_FLOW
      │
      ├── RelationshipProgramGraphProvider
      └── FileProgramGraphProvider (external sidecar v1)
              ↓
       ProgramGraphComposer
```

Le provider intégré a l’identifiant stable :

```text
minos-java-source-v1
```

Il est activé dans le constructeur par défaut de `ProgramGraphService`. Le sidecar M21 reste disponible comme contribution complémentaire.

## Ce que le provider prouve

### Source units

Un fichier n’est analysé que si :

- un symbole du snapshot actif le référence comme fichier Java ;
- le `fileId` est relatif au projet ;
- le fichier existe réellement ;
- le chemin réel reste sous la racine réelle enregistrée ;
- les bornes de taille sont respectées ;
- tous les fichiers Java requis se parsèrent sans diagnostic de syntaxe `ERROR`.

Le provider ne publie pas un graphe partiel lorsque cette précondition projet échoue.

### CFG

Le provider construit des nœuds `BASIC_BLOCK` et des arêtes `CONTROL_FLOW` pour les séquences et structures explicitement modélisées :

- `if/else` ;
- `while` ;
- `for` ;
- enhanced-for ;
- do/while ;
- `try/catch/finally` avec modèle d’exception conservateur ;
- `synchronized` ;
- labeled statements.

Les transferts non modélisés ajoutent une limitation au lieu d’être masqués.

### Def-use local

Les définitions sont suivies pour :

- paramètres ;
- variables locales ;
- assignations ;
- compound assignments ;
- incréments/décréments.

Les usages sont les `IdentifierTree` reliés aux définitions nominales actuellement possibles. Les jointures `if/else` fusionnent les définitions possibles. Les boucles sont conservatrices et explicitement limitées ; les champs ne sont pas promus en def-use local prouvé.

### Interprocédural

M22 v1 n’invente pas de résolution de surcharge par type.

Un appel est relié à une méthode projet uniquement si :

```text
(simpleName, arity) -> exactement une méthode projet
```

Dans ce cas seulement :

- argument appelant → paramètre cible : `ARGUMENT_FLOW` ;
- retour cible → résultat de l’appel : `RETURN_FLOW`.

Un appel externe ou ambigu ne produit aucune arête interprocédurale et ajoute :

```text
JAVA_INTERPROCEDURAL_EXTERNAL_OR_AMBIGUOUS_CALLS_SKIPPED
```

## Security taint

La taxonomy sécurité est opt-in et locale au projet :

```text
<project>/.minos/java-advanced-provider.properties
```

Exemple :

```properties
sources=request.getParameter,source
sinks=executeQuery,sink
sanitizers=escapeSql,sanitize
```

Le nom configuré correspond soit au select complet (`request.getParameter`), soit au nom simple (`getParameter`).

Sans fichier :

```text
JAVA_SECURITY_RULES_NOT_CONFIGURED
```

et `SECURITY_TAINT` n’est jamais publié.

La propagation M22 v1 est intraprocédurale. Un appel inconnu stoppe le taint plutôt que de supposer qu’il préserve la valeur.

## Nature, confiance et provenance

Nœuds directement observés dans l’AST :

```text
nature      FACTUAL
originType  AST
providerId  minos-java-source-v1
```

Arêtes calculées :

```text
CFG                  DERIVED confidence=1.00
DEF_USE              DERIVED confidence=0.90
ARGUMENT/RETURN      DERIVED confidence=0.85
TAINT_FLOW           DERIVED confidence=0.90
originType           DERIVED_BY_MINOS
Evidence             obligatoire
```

Ces valeurs expriment la confiance dans la dérivation **dans son périmètre déclaré**, pas une certitude runtime.

## Bornes

```text
MAX_SOURCE_FILES        2 000
MAX_SOURCE_BYTES        4 MiB / fichier
MAX_TOTAL_SOURCE_BYTES 64 MiB
```

Les bornes de réponse de `ProgramGraphService` restent en plus applicables.

## Cache

La clé de cache du provider inclut :

- `snapshotId` ;
- `fileId` de chaque source ;
- contenu exact de chaque source Java ;
- contenu du fichier de règles sécurité lorsqu’il existe.

Modifier source ou taxonomy invalide donc le fragment Java sans demander de redémarrage du service.

## Limitations v1 importantes

Les limitations suivantes sont contractuelles :

```text
JAVA_AST_PARSE_ONLY_TYPE_ATTRIBUTION_NOT_PROVEN
JAVA_LOCAL_DATA_FLOW_NAME_BASED_WITHIN_METHOD
JAVA_LOCAL_DATA_FLOW_FIELDS_NOT_MODELED
JAVA_LOCAL_DATA_FLOW_LOOP_FIXPOINT_CONSERVATIVE
JAVA_INTERPROCEDURAL_UNIQUE_NAME_ARITY_ONLY
JAVA_INTERPROCEDURAL_EXTERNAL_OR_AMBIGUOUS_CALLS_SKIPPED
JAVA_SECURITY_FLOW_INTRAPROCEDURAL_CONFIGURED_RULES_ONLY
JAVA_SECURITY_UNKNOWN_CALL_STOPS_FLOW
```

Elles peuvent apparaître uniquement lorsque le cas correspondant est rencontré.

## Ground truth

Fixtures :

```text
fixtures/m22/java-advanced-provider/project/src/main/java/demo/
├── CfgFixture.java
├── DefUseFixture.java
├── InterproceduralFixture.java
└── SecurityFixture.java
```

La taxonomy fixture est dans :

```text
fixtures/m22/java-advanced-provider/project/.minos/java-advanced-provider.properties
```

`JavaSourceProgramGraphProviderTest` mesure l’ensemble exact des arêtes attendues avec `ProgramGraphEvaluator`.

Critère bloquant M22-S8 :

```text
CONTROL_FLOW   precision=1.0 recall=1.0
DEF_USE        precision=1.0 recall=1.0
ARGUMENT_FLOW  precision=1.0 recall=1.0
RETURN_FLOW    precision=1.0 recall=1.0
TAINT_FLOW     precision=1.0 recall=1.0
```

## Surfaces publiques

Aucun nouveau modèle métier public n’est créé. Les surfaces existantes lisent le même `ProgramGraphService` :

- Java `AdvancedCodeIntelligenceApi` v1 ;
- MCP `minos_program_graph`, `minos_impact_v2`, `minos_security_paths` ;
- IntelliJ `program-graph`, `impact-v2`, `security-paths` via `minos-ide` v1.

Une capability visible dans ces surfaces signifie qu’au moins un provider du graphe composé a fourni les faits correspondants ; elle ne signifie jamais une preuve exhaustive du comportement runtime.

## Qualification

```powershell
.\scripts\m22\run-final.ps1 -ExpectedHead <sha>
```

Verdict attendu :

```text
M22 ADVANCED PROVIDER CONSISTENCY SUCCESS
M22 FINAL ADVANCED PROVIDER INTELLIGENCE VALIDATION SUCCESS
Validated HEAD: <sha>
```
