# M19 — Advanced Code Intelligence — exécution

Statut : **TERMINÉ, QUALIFIÉ ET LIVRÉ — 9/9**.

Issue : #69 — **CLOSED / completed**.

PR : #70 — **MERGED**.

## Question produit

> MINOS peut-il analyser la structure d'exécution et les flux de données sans confondre faits statiques, approximations et comportements runtime ?

## Architecture retenue

```text
snapshot MINOS v2
   │
   ├── relations CALLS / READS / WRITES
   │        ↓
   │   RelationshipProgramGraphProvider
   │
   └── facts avancés explicites (provider/sidecar v1)
            ↓
       ProgramGraphProvider SPI
            ↓
       ProgramGraphComposer
            ↓
       ProgramGraph (capabilities + limitations)
            ├── Call Graph v2
            ├── CFG
            ├── Data Flow
            ├── Interprocedural Flow
            ├── CPG view
            ├── Impact v2
            └── Security paths
```

Le graphe avancé est une **vue reconstruisible** du snapshot actif et de faits provider explicites. M19 ne modifie pas le format historique des snapshots M3/M15 : les faits avancés restent optionnels, versionnés et capability-honest.

## Invariants

- `FACTUAL`, `DERIVED`, `HEURISTIC` restent distincts ;
- provenance, confiance, preuves et limitations sont conservées ;
- aucun CFG/data-flow/taint n'est inventé lorsque le provider ne le fournit pas ;
- les données `CALLS`, `READS`, `WRITES` historiques ne sont promues qu'au niveau qu'elles prouvent réellement ;
- propagation interprocédurale bornée par profondeur et nombre de résultats ;
- cycles explicitement signalés ;
- une analyse sécurité expose un **chemin observé**, jamais une affirmation de vulnérabilité exhaustive ;
- le CPG est une composition de vues cohérentes, pas un deuxième stockage autoritatif ;
- snapshots persistés historiques restent la source de vérité ;
- M16 reste la référence de scalabilité ;
- API/MCP existants restent compatibles.

## Sous-incréments

### M19-S1 — Program graph model ✅ LIVRÉ

Modèle provider-independent de nœuds/arêtes, capacités, nature, confiance, provenance, preuves et limitations. Composition déterministe et rejet des collisions incohérentes.

### M19-S2 — Call graph v2 ✅ LIVRÉ

Projection des relations `CALLS` résolues en arêtes `CALL`, sans perte de provenance/nature. Évaluation précision/rappel sur vérités terrain contrôlées.

### M19-S3 — Control Flow Graph ✅ LIVRÉ

Support de `BASIC_BLOCK` et `CONTROL_FLOW` via `ProgramGraphProvider`. Les providers sans CFG exposent explicitement `CONTROL_FLOW_UNAVAILABLE` ; aucune approximation silencieuse. La fixture contrôlée couvre branche, boucle et chemin d'exception.

### M19-S4 — Data Flow ✅ LIVRÉ

Support `DEF_USE`, `DATA_FLOW`, `ARGUMENT_FLOW`, `RETURN_FLOW`. Une vérité terrain `DEF_USE` est mesurée séparément. Les `READS`/`WRITES` historiques ne produisent qu'une dérivation locale potentielle avec `EXECUTION_ORDER_NOT_PROVEN`.

### M19-S5 — Interprocedural Flow ✅ LIVRÉ

Propagation BFS déterministe et bornée sur appels/argument/return/data-flow ; cycles, profondeur atteinte, troncature et absence de capacités sont exposés comme limitations.

### M19-S6 — CPG composition ✅ LIVRÉ

Union dédupliquée des vues symbole/call/control/data-flow avec identité stable des nœuds/arêtes. Aucun fait contradictoire n'est écrasé silencieusement ; une collision de stable-id incohérente est rejetée.

### M19-S7 — Impact v2 ✅ LIVRÉ

Impact M8 reste baseline. Impact v2 ajoute les chemins du graphe de programme quand ils existent, avec comptage séparé `baseline` / `advancedAdded` et fixture contrôlée où M19 ajoute un impact absent du graphe M8.

### M19-S8 — Security primitives ✅ LIVRÉ

Nœuds `SOURCE`, `SINK`, `SANITIZER` et recherche de chemins taint bornés. Résultat : source, sink, chemin, sanitizers observés, nature, confiance, limitations. Aucune absence de chemin n'est interprétée comme absence de vulnérabilité.

### M19-S9 — API / MCP ✅ LIVRÉ

Surface Java additive `AdvancedCodeIntelligenceApi` v1 sans modifier `MinosApi` v1. Le MCP conserve les 16 tools historiques et ajoute `minos_program_graph`, `minos_impact_v2`, `minos_security_paths`, soit 19 tools read-only, tous bornés par schéma.

## Qualification finale

Runner :

```text
scripts/m19/run-final.ps1
```

Workflow :

```text
.github/workflows/m19-advanced-code-intelligence.yml
```

Qualification Windows exact-head autoritative :

```text
Validated HEAD: 859138cbfdd4e0722a6366efd97fa62ad95c2443
M19 FINAL ADVANCED CODE INTELLIGENCE VALIDATION SUCCESS
```

Preuves principales :

- product facts : PASS ;
- reactor Maven Java 24 : **13/13 modules SUCCESS** ;
- tests `minos-application` : **112/112 PASS** ;
- tests `minos-api` : **10/10 PASS** ;
- tests `minos-mcp` : **5/5 PASS** ;
- tests agrégateur `minos-code-intelligence` : **50/50 PASS** ;
- smoke test shaded JAR : **1/1 PASS** ;
- JaCoCo : tous les gates PASS ;
- HEAD stable et worktree propre en fin de qualification.

Le HEAD qualifié n'a reçu aucun commit supplémentaire avant merge.

Merge final : `3630ebd0f229e1bc028e92444bfa34c3e7609596`.

M19 est fermé et livré ; M20 — Recherche sémantique hybride — devient le prochain jalon séquentiel.