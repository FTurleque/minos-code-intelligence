# M19 — Advanced Code Intelligence — exécution

Statut de branche : **9/9 implémentés ; qualification exact-head finale en attente**.

Issue : #69.

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

### M19-S1 — Program graph model ✅ IMPLÉMENTÉ

Modèle provider-independent de nœuds/arêtes, capacités, nature, confiance, provenance, preuves et limitations. Composition déterministe et rejet des collisions incohérentes.

### M19-S2 — Call graph v2 ✅ IMPLÉMENTÉ

Projection des relations `CALLS` résolues en arêtes `CALL`, sans perte de provenance/nature. Évaluation précision/rappel sur vérités terrain contrôlées.

### M19-S3 — Control Flow Graph ✅ IMPLÉMENTÉ

Support de `BASIC_BLOCK` et `CONTROL_FLOW` via `ProgramGraphProvider`. Les providers sans CFG exposent explicitement `CONTROL_FLOW_UNAVAILABLE` ; aucune approximation silencieuse. La fixture contrôlée couvre branche, boucle et chemin d'exception.

### M19-S4 — Data Flow ✅ IMPLÉMENTÉ

Support `DEF_USE`, `DATA_FLOW`, `ARGUMENT_FLOW`, `RETURN_FLOW`. Une vérité terrain `DEF_USE` est mesurée séparément. Les `READS`/`WRITES` historiques ne produisent qu'une dérivation locale potentielle avec `EXECUTION_ORDER_NOT_PROVEN`.

### M19-S5 — Interprocedural Flow ✅ IMPLÉMENTÉ

Propagation BFS déterministe et bornée sur appels/argument/return/data-flow ; cycles, profondeur atteinte, troncature et absence de capacités sont exposés comme limitations.

### M19-S6 — CPG composition ✅ IMPLÉMENTÉ

Union dédupliquée des vues symbole/call/control/data-flow avec identité stable des nœuds/arêtes. Aucun fait contradictoire n'est écrasé silencieusement ; une collision de stable-id incohérente est rejetée.

### M19-S7 — Impact v2 ✅ IMPLÉMENTÉ

Impact M8 reste baseline. Impact v2 ajoute les chemins du graphe de programme quand ils existent, avec comptage séparé `baseline` / `advancedAdded` et fixture contrôlée où M19 ajoute un impact absent du graphe M8.

### M19-S8 — Security primitives ✅ IMPLÉMENTÉ

Nœuds `SOURCE`, `SINK`, `SANITIZER` et recherche de chemins taint bornés. Résultat : source, sink, chemin, sanitizers observés, nature, confiance, limitations. Aucune absence de chemin n'est interprétée comme absence de vulnérabilité.

### M19-S9 — API / MCP ✅ IMPLÉMENTÉ

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

Le runner doit prouver sur un SHA exact :

1. worktree propre et HEAD stable ;
2. `clean verify` Java 24 vert ;
3. JaCoCo + product facts verts ;
4. modèles/capacités provider-independent ;
5. call graph précision/rappel mesurés ;
6. fixtures CFG branches/loops/exceptions ;
7. vérités terrain def-use/data-flow ;
8. propagation interprocédurale bornée et cycles explicites ;
9. CPG sans duplication incohérente ;
10. Impact v2 améliore la fixture de référence sans modifier M8 ;
11. security paths explicables et bornés ;
12. API/MCP schémas versionnés et non-régression des surfaces historiques.

Verdict unique :

```text
M19 FINAL ADVANCED CODE INTELLIGENCE VALIDATION SUCCESS
```

La branche ne sera pas marquée qualifiée, Ready ou mergée avant obtention réelle de ce verdict sur son HEAD exact.
