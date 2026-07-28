# Advanced Program Provider — sidecar v1

M21-S7 productionise le point d'extension `ProgramGraphProvider` de M19 sans inventer de CFG, de def-use, de flux interprocédural ni de taint lorsque le provider courant ne les fournit pas.

## Principe

Le runtime MINOS compose désormais deux contributions locales :

```text
snapshot structuré actif
   ↓
RelationshipProgramGraphProvider
   +
.minos/program-graph-v1/         ← facts avancés explicites d'un analyseur statique
   ↓
FileProgramGraphProvider
   ↓
ProgramGraphComposer
```

Le sidecar est une **source provider explicite**, pas une nouvelle base de vérité MINOS. Il doit cibler exactement le `snapshotId` actif. S'il est absent, les capacités avancées restent indisponibles. S'il cible un ancien snapshot, aucune de ses capacités n'est publiée.

`.minos/` est exclu de la discovery et du fingerprint M7 : modifier un sidecar ne simule donc jamais une modification du code source. Le cache Program Graph inclut l'empreinte SHA-256 des trois fichiers du sidecar et est invalidé lorsque les facts avancés changent.

## Emplacement

À la racine du projet enregistré :

```text
<project>/.minos/program-graph-v1/
├── metadata.properties
├── nodes.tsv
└── edges.tsv
```

Les fichiers sont UTF-8. Chaque fichier est borné à 64 MiB ; le provider borne également le graphe à 100 000 nœuds et 500 000 arêtes avant les bornes de réponse `ProgramGraphService`.

## `metadata.properties`

Exemple :

```properties
formatVersion=1
snapshotId=snapshot-123
providerId=my-static-analyzer
providerType=STATIC_ANALYZER
providerVersion=2.4.1
indexRunId=run-20260727-001
capabilities=CONTROL_FLOW,LOCAL_DATA_FLOW,INTERPROCEDURAL_DATA_FLOW,SECURITY_TAINT
```

Champs obligatoires :

| Champ | Rôle |
|---|---|
| `formatVersion` | doit être exactement `1` |
| `snapshotId` | doit être exactement le snapshot structuré actif |
| `providerId` | identité stable du producteur des facts |
| `providerVersion` | version du producteur |
| `indexRunId` | identité de l'exécution ayant produit les facts |
| `capabilities` | capacités réellement prouvées par le contenu |

`providerType` vaut `PROGRAM_GRAPH_SIDECAR` s'il est omis.

`CPG` ne doit pas être déclaré : cette capacité est produite par `ProgramGraphComposer` lorsque MINOS compose des nœuds cohérents.

## `nodes.tsv`

En-tête exact :

```text
id	symbolId	kind	label	fileId	startLine	startColumn	endLine	endColumn	positionEncoding
```

`id`, `kind` et `label` sont obligatoires. `symbolId` est optionnel ; lorsqu'il est fourni, il doit exister dans le snapshot actif. Une location explicite est optionnelle. Si `fileId` est présent, toutes les coordonnées et `positionEncoding` doivent être valides selon le contrat MINOS : ligne base 1, colonne base 0, encodage explicite.

Kinds supportés :

```text
SYMBOL
BASIC_BLOCK
VARIABLE
PARAMETER
RETURN_VALUE
SOURCE
SINK
SANITIZER
```

## `edges.tsv`

En-tête exact :

```text
id	sourceNodeId	targetNodeId	kind
```

Les deux extrémités doivent être déclarées dans `nodes.tsv` du même sidecar.

Kinds supportés :

```text
CALL
CONTROL_FLOW
DEF_USE
DATA_FLOW
ARGUMENT_FLOW
RETURN_FLOW
TAINT_FLOW
```

Le contrat v1 considère les lignes du sidecar comme des **facts explicitement affirmés par le provider** et les projette avec `InformationNature.FACTUAL` et la provenance du fichier metadata. Il ne transforme jamais une absence de fact en fact négatif.

## Capability honesty

Les déclarations sont vérifiées dans les deux sens :

```text
CALL_GRAPH                 ↔ au moins une arête CALL
CONTROL_FLOW               ↔ au moins une arête CONTROL_FLOW
LOCAL_DATA_FLOW            ↔ au moins une arête DEF_USE ou DATA_FLOW
INTERPROCEDURAL_DATA_FLOW  ↔ au moins une arête ARGUMENT_FLOW ou RETURN_FLOW
SECURITY_TAINT             ↔ TAINT_FLOW + nœud SOURCE + nœud SINK
```

Une arête correspondante sans capability déclarée est également rejetée. MINOS ne complète jamais silencieusement le profil.

En particulier, M21-S7 supprime l'ancienne promotion implicite :

```text
CALL_GRAPH + LOCAL_DATA_FLOW
    ≠ INTERPROCEDURAL_DATA_FLOW
```

La capacité interprocédurale n'existe que lorsqu'un provider fournit explicitement un `ARGUMENT_FLOW` ou `RETURN_FLOW` correspondant et déclare cette capacité.

## Lifecycle

Le sidecar doit être produit **après** la connaissance du snapshot MINOS auquel il correspond :

```text
indexation structurée
→ snapshot actif = S
→ analyseur externe lit le code / artefacts correspondant à S
→ écrit sidecar avec snapshotId=S
→ requête Program Graph compose snapshot + sidecar
```

Après une nouvelle promotion structurée, un sidecar resté sur l'ancien snapshot est exposé par la limitation :

```text
ADVANCED_PROGRAM_SIDECAR_STALE_SNAPSHOT
```

et ne contribue aucun nœud, arête ou capability.

## Vérité terrain M21-S7

Fixture versionnée :

```text
fixtures/m21/advanced-program-sidecar/project/.minos/program-graph-v1/
```

Elle fournit explicitement :

```text
CONTROL_FLOW                 2 arêtes
DEF_USE                      1 arête
ARGUMENT_FLOW                1 arête
RETURN_FLOW                  1 arête
TAINT_FLOW                   2 arêtes
SOURCE / SANITIZER / SINK    présents
```

`AdvancedProgramSidecarFixtureTest` mesure précision et rappel sur chaque famille d'arêtes attendue. Le gate S7 vérifie aussi que le service ne réintroduit pas une promotion implicite de capability.

## Qualification

```powershell
.\scripts\m21\run-s7.ps1 -ExpectedHead <sha>
```

Verdicts attendus :

```text
M21 ADVANCED PROVIDER CONSISTENCY SUCCESS (capabilities=4, nodes=12, edges=7)
M21 LOCAL CONSOLIDATION VALIDATION SUCCESS
M21-S7 ADVANCED PROVIDER VALIDATION SUCCESS
Validated HEAD: <sha>
```

Cette productionisation ne prétend pas que SCIP fournit désormais CFG/def-use/taint. SCIP conserve exactement son profil réel ; le sidecar v1 permet à un analyseur avancé local de fournir ces facts sans modifier le snapshot structuré ni mentir sur ses capacités.
