# Proposal

## Why

Constat **AUD-PERF-15** de l'audit `docs/project/audits/2026-11-15/` (sprint 10, sévérité faible, ouvert par la vérification du 20/11) : depuis la PR #797 (AUD-PERF-14, changement archivé `2026-11-20-liberer-la-table-de-symboles-avec-sa-vue`), le service d'exploration décrit chaque document par les lectures de la vue active. Pour un document de fichier, `SymbolLookup.fileSymbol` appelle `findFileSymbols(projectId, fileId, Integer.MAX_VALUE)`, et l'index en mémoire trie une copie de tous les symboles du fichier à chaque appel. La tâche 1.4 du changement archivé (mesure du temps d'une page avant et après) n'a pas été faite : le coût d'une page, jusqu'à 2 000 documents balayés quand elle est filtrée, a été choisi sans mesure.

La revérification au HEAD corrige l'ampleur annoncée : un document de fragment (`CHUNK`) ou de symbole porte l'identifiant de son symbole et n'atteint pas `fileSymbol` ; seul un document de fichier, un par fichier, le fait. Une page lit donc chaque fichier au plus une fois, et la mémoïsation par `fileId` que propose l'audit n'y gagnerait rien (design, écarts 1 et 2). Il reste à mesurer, puis à décider.

## What Changes

- Une mesure rejouable du temps d'une page de documents est ajoutée aux tests de `minos-application` : sautée sans propriété système, elle bâtit des fixtures synthétiques à gros fichiers (de 20 à 5 000 symboles par fichier), mesure la page filtrée la plus lente, le parcours complet d'un index et le nombre de lectures `findFileSymbols`, et écrit son relevé sous `target/` (décision D2).
- La mesure « avant » est faite au HEAD et consignée ; le cas échéant, elle est rejouée sur le dernier commit qui gardait la table de symboles (`acd79cb86`), ce qui ferme la tâche 1.4 d'AUD-PERF-14.
- Selon le seuil de la décision D3, soit aucune correction n'est faite et la mesure est consignée comme preuve, soit l'index en mémoire range une fois pour toutes les symboles de chaque fichier dans l'ordre que rend `findFileSymbols`, à la construction de la vue, sans table hors de la vue (décision D4, option B).
- Un test de caractérisation garde la règle de lecture : un document de symbole ou de fragment résolu n'appelle pas `findFileSymbols`, un document de fichier l'appelle une fois, et rien n'est gardé au-delà de la requête.
- `docs/developer/semantic-explorer.md` est corrigé (résolution des symboles et limite « tables de symboles gardées : 4 projets », périmées depuis la PR #797) et nomme la commande de mesure.

Aucune sortie de l'explorateur, de la CLI, de l'API Java, du MCP, du plugin IntelliJ ni de NEXUS ne change.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `exploration-semantique` : deux exigences ajoutées, « Un document n'est décrit par les symboles de son fichier qu'une fois par requête et sans table gardée » et « Le temps d'une page de documents est mesurable sur des fichiers à nombreux symboles ».

## Hors périmètre

- Le budget des vues, `QueryViewMemoryBudget`, les parts `HeapShare` et le cache `fingerprints` du service.
- Toute table de symboles gardée par le service hors de la vue ou au-delà d'une requête : interdite par l'exigence « La table de symboles de l'explorateur ne survit pas à sa vue », que ce changement ne modifie pas.
- Un seuil de performance contrôlé en CI : la mesure est manuelle et opt-in, aucun test du build n'affirme une durée.
- Le banc HTTP `benchmarks/scalability/` (`SemanticExplorerBenchmark`) : ses jeux ne portent qu'un symbole et n'exercent pas ce coût (design, écart 3) ; son enrichissement est une proposition, pas une tâche.
- Les autres constats du sprint 10 (AUD-ARC-12, AUD-TST-13, AUD-SEC-11) et les partiels AUD-DEP-05 et AUD-ARC-02.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| `fileSymbol` lit tous les symboles du fichier par `findFileSymbols(…, Integer.MAX_VALUE)` puis garde la plus petite `symbolKey` | [Existant vérifié] | `minos-application/src/main/java/com/minos/application/semantic/SemanticCorpusQueryService.java:741-750` |
| `findFileSymbols` trie une copie de la liste du fichier à chaque appel | [Existant vérifié] | `minos-engine/src/main/java/com/minos/store/InMemoryCodeKnowledgeStore.java:224-240` |
| Un document `CHUNK` ou `SYMBOL` porte l'identifiant de son symbole ; un document `FILE` est unique par fichier | [Existant vérifié] | `minos-application/src/main/java/com/minos/application/semantic/SemanticDocumentFactory.java:80-83`, `:101-107` |
| Seul un document `FILE`, ou un document dont le symbole est introuvable, atteint `fileSymbol` | [Existant vérifié] | `SemanticCorpusQueryService.java:722-725` |
| Une page filtrée balaie au plus 2 000 documents | [Existant vérifié] | `SemanticCorpusQueryService.java:48`, `:160-167` |
| La tâche 1.4 d'AUD-PERF-14 n'est pas faite | [Existant vérifié] | `openspec/changes/archive/2026-11-20-liberer-la-table-de-symboles-avec-sa-vue/tasks.md:14-15` |
| « Un CHUNK n'a pas de symbole » (audit) | écart | design, écart 1 |
| 1 à 3 ms par document de gros fichier | non reproduit | sonde de l'audit, `ETAT-2026-11-20.md` |
| Mesurer d'abord, corriger seulement au-delà du seuil | [Proposition] | design, D1 et D3 |

## Impact

- **Modules du reactor** : `minos-application` (tests : mesure opt-in et test de caractérisation). `minos-engine` touché seulement si la mesure franchit le seuil et que D4 retient l'option B (`InMemoryCodeKnowledgeStore`).
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS, explorateur) : aucun changement de sortie, aucune signature publique modifiée.
- **ADR** : aucun nouveau. DATA-0085 point 19 amendé d'une phrase seulement si D4 = B (planifié en tâche, pas pendant la spécification).
- **Gates à rejouer** : scope JaCoCo `explorer-surface` (`scripts/quality/check-jacoco.py:60-73`) ; si D4 = B, scope `persistence-cache-indexes` (`:124-130`) ; `scripts/architecture/check-module-boundaries.py` (A18, `module_boundary_ratchets.py:483-507`) ; `scripts/docs/check-capability-dossiers.py` ; `python .claude/scripts/run_gates.py`.
- **Plateformes** : Windows et Linux. Code Java sans chemin propre à un système ; la mesure est faite sur le poste Windows, la mesure Linux est facultative et nommée comme non faite si elle manque.
- **Documentation durable** : `docs/developer/semantic-explorer.md` (`:49-52`, `:197`, `:203-206`), `docs/architecture/diagrams/capacites/exploration-semantique.md` (traçabilité `:299`, écarts), DATA-0085 point 19 si D4 = B.

## Questions ouvertes

Trois décisions du propriétaire, chacune avec une recommandation (design, D1, D3, D4) : mesurer avant de corriger, seuil de décision, forme de la correction si le seuil est franchi.
