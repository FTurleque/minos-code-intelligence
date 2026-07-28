# M22 — Advanced Provider Intelligence — exécution

Statut : **TERMINÉ — VALIDÉ exact-head — 9/9 — FUSIONNÉ dans `develop` via PR #77.**

Issue : **#76 — M22 — Advanced Provider Intelligence — CLOSED / completed**.

Branche : `m22-advanced-provider-intelligence`.

Base : `develop @ 4222706502c54e10f0bf0400a18360fb99e6208c`.

Qualified HEAD final : `75d6169be6d46d4e60ca19e781ff61704ca1613c`.

Merge `develop` : `37a3c904fd92c25b343344a26991531c75ebc4b6`.

M21-S2/CI reste en pause jusqu’en août 2026. M22 n’a exécuté, modifié ni contourné aucun workflow CI.

## Question produit

> MINOS peut-il alimenter réellement CFG, def-use, flux interprocéduraux et primitives de sécurité avec des providers qualifiés, sans confondre capacité du moteur et fait effectivement prouvé par un provider ?

## Décision M22

M22 introduit un **provider Java de référence local** fondé sur l’API AST publique du compilateur JDK (`jdk.compiler`) :

```text
snapshot structuré actif
        │
        ├── relations CALLS / READS / WRITES
        │       ↓
        │  RelationshipProgramGraphProvider
        │
        ├── fichiers Java réellement présents dans le snapshot
        │       ↓
        │  JavaSourceProgramGraphProvider
        │       ├── CFG dérivé de l’AST
        │       ├── def-use local conservateur
        │       ├── argument/return flow si nom+arité unique
        │       └── taint local si règles explicites configurées
        │
        └── sidecar externe optionnel M21
                ↓
           FileProgramGraphProvider

                 ↓
          ProgramGraphComposer
                 ↓
       ProgramGraph capability-honest
```

Le provider Java ne remplace ni le snapshot structuré ni le sidecar. Il constitue une vue reconstruisible additionnelle.

## Invariants non négociables

- `FACTUAL`, `DERIVED` et `HEURISTIC` restent distincts ;
- la présence d’un moteur capable de calculer un CFG n’implique jamais qu’un projet possède un CFG qualifié ;
- chaque fichier Java analysé doit être représenté par le snapshot actif ;
- tous les chemins sont confinés à la racine réelle du projet ;
- aucun résultat partiel n’est publié si un fichier attendu manque, s’échappe de la racine ou ne se parse pas ;
- aucune attribution de types n’est inventée : M22 v1 parse l’AST, mais ne prétend pas avoir résolu le classpath complet ;
- le def-use est local à une méthode et explicite sa résolution nominale ;
- l’interprocédural n’est publié que lorsqu’un nom simple + arité possède une cible projet unique ;
- les appels externes ou ambigus restent non prouvés ;
- `SECURITY_TAINT` exige des règles locales explicites et au moins un chemin `SOURCE → ... → SINK` observé ;
- une absence de chemin taint ne prouve jamais une absence de vulnérabilité ;
- TypeScript/Python ne sont pas promus par M22 tant qu’une qualification équivalente n’existe pas ;
- toutes les analyses restent bornées et reconstruisibles ;
- qualification finale = exact HEAD + worktree propre.

## Bornes Java provider v1

```text
fichiers Java maximum         2 000
source maximum / fichier      4 MiB
total sources maximum        64 MiB
```

Le provider ne parcourt pas arbitrairement le disque : la liste des `.java` vient des symboles du snapshot structuré actif.

## Nature et provenance

Les nœuds AST observés (`BASIC_BLOCK`, occurrences de variables, paramètres, retours, arguments d’appel) sont `FACTUAL` avec :

```text
providerId      minos-java-source-v1
providerType    JAVA_COMPILER_AST
providerVersion 1
originType      AST
```

Les arêtes CFG, def-use, argument/return et taint sont `DERIVED`, portent une confiance explicite, une `Evidence` structurée et une origine `DERIVED_BY_MINOS`.

## Règles sécurité

La sécurité est désactivée tant que le projet ne possède pas :

```text
.minos/java-advanced-provider.properties
```

Format v1 :

```properties
sources=request.getParameter,source
sinks=executeQuery,sink
sanitizers=escapeSql,sanitize
```

Les valeurs correspondent exactement soit au select complet de l’invocation, soit à son nom simple. Modifier ce fichier change la clé de cache du provider.

## Sous-incréments

### M22-S1 — Roadmap + provider contract

- roadmap opérationnelle ;
- ADR-0030 ;
- limites de preuve, bornes et fallback documentés ;
- M21-S2 explicitement hors scope.

### M22-S2 — Java discovery + confinement

- fichiers Java issus uniquement du snapshot actif ;
- racine réelle, refus absolute/`..`/symlink escape ;
- fail-closed sur source manquante ;
- limites fichiers/octets ;
- clé de cache SHA-256 incluant sources + règles sécurité.

### M22-S3 — Control Flow Graph

- nœuds `BASIC_BLOCK` à emplacement UTF-16 explicite ;
- séquences ;
- `if/else` ;
- `while`, `for`, enhanced-for, do-while ;
- try/catch/finally conservateur ;
- synchronisation/labeled statements ;
- contrôle non modélisé signalé comme limitation.

Capability : `CONTROL_FLOW` uniquement si une arête correspondante existe réellement.

### M22-S4 — Local def-use

- paramètres comme définitions initiales ;
- déclarations ;
- assignations ;
- compound assignment / increment / decrement ;
- uses d’identifiants ;
- jointure conservative `if/else` ;
- boucle explicitement signalée comme fixpoint conservateur ;
- champs non modélisés signalés.

Capability : `LOCAL_DATA_FLOW` uniquement si `DEF_USE`/`DATA_FLOW` existe.

### M22-S5 — Interprocedural argument/return flow

- index des méthodes projet par `(simpleName, arity)` ;
- `ARGUMENT_FLOW` uniquement si cible unique ;
- `RETURN_FLOW` uniquement vers les retours observés de cette cible ;
- appels externes/ambigus ignorés avec limitation explicite ;
- aucune prétention de résolution de surcharge par type.

Capability : `INTERPROCEDURAL_DATA_FLOW` uniquement si au moins une arête argument/return existe.

### M22-S6 — Security primitives

- taxonomy source/sink/sanitizer locale et explicite ;
- propagation taint intraprocédurale ;
- sanitizer observé conservé dans le chemin ;
- appel inconnu stoppe le taint et produit une limitation ;
- résultats `DERIVED`, jamais assertion runtime.

Capability : `SECURITY_TAINT` uniquement si `TAINT_FLOW + SOURCE + SINK` sont observés.

### M22-S7 — Capability/provenance/fallback hardening

- aucune capability sur snapshot non Java ;
- aucune capability sur parse failure ;
- provenance de chaque arête dérivée ;
- règles sécurité absentes => `JAVA_SECURITY_RULES_NOT_CONFIGURED` ;
- cache invalidé par source/config ;
- sidecar M21 conservé comme provider externe complémentaire.

### M22-S8 — Ground-truth precision/recall

Fixture versionnée :

```text
fixtures/m22/java-advanced-provider/project/
```

Scénarios indépendants :

```text
CfgFixture.java
DefUseFixture.java
InterproceduralFixture.java
SecurityFixture.java
```

Chaque famille est évaluée par `ProgramGraphEvaluator` sur l’ensemble exact des arêtes attendues. Gate bloquant :

```text
CONTROL_FLOW   precision=1.0 recall=1.0
DEF_USE        precision=1.0 recall=1.0
ARGUMENT_FLOW  precision=1.0 recall=1.0
RETURN_FLOW    precision=1.0 recall=1.0
TAINT_FLOW     precision=1.0 recall=1.0
```

### M22-S9 — Public surfaces + final qualification

Aucun nouveau contrat métier n’est dupliqué : le provider alimente `ProgramGraphService`, donc les surfaces M19 déjà additives bénéficient du graphe enrichi :

```text
Java API AdvancedCodeIntelligenceApi v1
MCP minos_program_graph / minos_impact_v2 / minos_security_paths
IntelliJ minos-ide v1 program-graph / impact-v2 / security-paths
```

Le protocole public reste compatible ; seules les capabilities réellement disponibles changent selon le projet/provider.

## Qualification locale finale

Runner :

```powershell
.\scripts\m22\run-final.ps1 -ExpectedHead <sha>
```

Le runner vérifie :

1. HEAD exact + worktree tracked propre ;
2. gate local M21/M20, modules et JaCoCo ;
3. tests Java provider / vérité terrain ;
4. contrat M22 statique ;
5. distribution Windows et runtime réellement livré ;
6. parité IntelliJ + Plugin Verifier ;
7. documentation, HEAD et worktree finaux.

Le premier replay complet a validé l’implementation tree `af760cfd61f023113b0e2051e237f73522c8aca6`. Après la réconciliation documentaire, le **replay de promotion** a validé le HEAD final :

```text
M22 ADVANCED PROVIDER CONSISTENCY SUCCESS
M21 JACOCO GATE SUCCESS
M20 FINAL SEMANTIC HYBRID CODE INTELLIGENCE VALIDATION SUCCESS
M21 LOCAL CONSOLIDATION VALIDATION SUCCESS
M21-S5 SUPPLY-CHAIN RELEASE VALIDATION SUCCESS
M22 PACKAGED JDK.COMPILER RUNTIME SUCCESS
M21-S6 INTELLIJ PARITY VALIDATION SUCCESS
M22 FINAL ADVANCED PROVIDER INTELLIGENCE VALIDATION SUCCESS
Validated HEAD: 75d6169be6d46d4e60ca19e781ff61704ca1613c
```

Le runtime Windows qualifié est vérifié depuis le ZIP livré : `app\runtime\lib\modules` doit exister et `app\runtime\release` doit déclarer `jdk.compiler` dans `MODULES`. L’absence de `runtime\bin\java.exe` n’est pas utilisée comme oracle, les commandes natives pouvant être retirées du runtime `jlink` produit par `jpackage`.

## Promotion

M22 a été promu après ce replay exact-head :

```text
PR #77           MERGED
Issue #76        CLOSED / completed
Merge develop    37a3c904fd92c25b343344a26991531c75ebc4b6
```

M21-S2 reste indépendant et en pause jusqu’en août 2026 ; aucun travail CI n’a été inclus dans la promotion M22.
