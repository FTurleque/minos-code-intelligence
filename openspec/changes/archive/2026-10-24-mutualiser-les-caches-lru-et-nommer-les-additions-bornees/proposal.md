# Proposal

## Why

**AUD-QUA-05** (moyenne, sprint 11 de l'audit du 10 octobre 2026). Deux invariants délicats sont réécrits à la main, classe par classe (état relu au HEAD `5ce7b79b`, détail dans `design.md`).

- **Caches LRU pondérés.** Le code de production compte **9** `new LinkedHashMap<>(…, 0.75f, true)`, dont **7** sont des caches bornés en poids, chacun avec son comptage, son éviction et ses statistiques : `ProgramGraphService.java:62`, `FingerprintConstrainedJavaProgramGraphProvider.java:46`, `SemanticSearchService.java:27`, `HybridSearchService.java:42`, `FileSemanticVectorStore.java:61`, `FileSymbolSnapshotStore.java:66`, `PostgresCodeKnowledgeSnapshotStore.java:73`. Les deux autres n'ont pas de poids : `HostedDenialThrottle.java:28` (fenêtres de refus) et `SourceStateKeyMemo.java:35` (mémo à délai). Une correction faite dans un cache n'atteint pas les six autres, et ils divergent déjà (un sans borne en entrées, un sans refus de l'entrée trop lourde, trois qui ramènent un poids négatif à zéro).
- **Opérations bornées.** **21** helpers privés : `safeAdd` (8), `saturatingAdd` (5), `checkedAdd` (3), `safeMultiply` (3), `saturatingMultiply` (2). Le même nom `safeAdd` a **trois** comportements au dépassement : il sature (6 copies), il lève `OutputBudgetExceededException` (`DeterministicJson.java:241`), il lève `IllegalStateException` (`RuntimeIntelligenceService.java:311`). `checkedAdd` en a **deux** : il sature dans `SemanticSearchService.java:129` et lève `IOException` dans `RemoteCloneBudget.java:143` et `FileSemanticVectorStore.java:444`. Copier un appel d'un fichier à l'autre change donc le comportement sans que le nom le dise.

## What Changes

Deux lots, livrables séparément, dans cet ordre.

- **Lot 1 — nommer par le comportement.** Chaque `safeAdd`, `safeMultiply` et `checkedAdd` privé qui sature est renommé d'après ce qu'il fait au dépassement (`saturating…`, ou un nom qui dit l'échec). Aucun comportement ne change. Le littéral `safeMultiply` de `scripts/remediation/check-mne.py:86` est réécrit dans la même tâche. Un gate neuf refuse le retour des noms ambigus.
- **Lot 2 — un seul cache LRU pondéré.** Une classe partagée de `minos-engine` (sous réserve de D2) porte l'invariant : bornes en entrées et en poids, poids retranché à chaque retrait, refus d'un poids négatif et d'une borne invalide, accès sérialisés, statistiques. Les sept caches pondérés y migrent **un par un**, sous leurs tests existants, sans changer leurs statistiques publiques. Chaque gate littéral qui cite une chaîne supprimée par la migration (`check-mnd.py:104`, `:106`) est réécrit **dans la même tâche**. Le lot n'attend plus de changement de performance (tous archivés le 2026-10-15) ; il précède la tâche 2.2 de `exposer-un-port-de-lecture-de-snapshot`.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `audit-qualite-code` : quatre exigences ajoutées (le nom d'une opération bornée dit son comportement au dépassement ; un cache LRU pondéré n'est écrit qu'une fois ; le cache partagé tient ses bornes et son poids ; il refuse un poids ou une borne invalide).

## Hors périmètre

- Changer une borne, un plafond, une estimation de poids ou une politique d'éviction : la migration est à comportement constant (seule exception : le chemin inatteignable de l'entrée trop lourde de `SemanticSearchService`, D4).
- `HostedDenialThrottle` et `SourceStateKeyMemo` (sans poids) : exclus sauf décision contraire (D3).
- Une classe d'arithmétique bornée partagée (`BoundedArithmetic`, action de l'audit) : non créée sauf décision contraire (D2b).
- La sémantique des opérandes négatifs des helpers existants (seul `ScipIngestionLimits` les refuse) : consignée, inchangée.
- Les helpers dont le nom n'est pas ambigu (`saturatingAdd`, `saturatingMultiply`, `ScipIngestionLimits.add`, `FileProjectFingerprintSnapshotStore.addSnapshotBytes`, `NexusExportService.addWeight`) et les appels `Math.addExact` / `Math.multiplyExact` écrits en ligne.
- Les verrous de construction par bande (`buildLocks`) : ils restent dans leurs classes.

## Impact

- **Modules du reactor** : lot 1 — `minos-application`, `minos-storage-local`, `minos-storage-postgresql`, `minos-integration-git` ; lot 2 — `minos-engine` (classe partagée, sous réserve de D2), `minos-application`, `minos-storage-local`, `minos-storage-postgresql`. Aucun module créé, aucune dépendance Maven ajoutée.
- **Surfaces publiques** : aucune (helpers privés ; records de statistiques inchangés en nom et en sens). CLI, API Java, MCP, IntelliJ, NEXUS : sans effet.
- **ADR** : aucun ADR nouveau ni amendé. Le package d'accueil recommandé (`com.minos.store.cache`) est sous une racine que `minos-engine` possède déjà (ADR 0044, ADR 0062, règle A14) et respecte la direction de l'ADR 0022. Une autre racine (D2 en faveur de `minos-domain`) serait une décision au sens de l'ADR 0062.
- **Gates à rejouer** : `python scripts/remediation/check-mne.py` (`:86`, lot 1), `python scripts/remediation/check-mnd.py` (`:99-106`, lot 2), `python scripts/remediation/check-p0-p2.py` (`:221`, `:305-312`), `python scripts/quality/check-semantic-retrieval-consistency.py` (`:119-130`), `python scripts/architecture/check-module-boundaries.py` (A3, A8, A14, A15), `python scripts/quality/check-ci-wiring.py` et `python scripts/quality/check-single-execution.py` (gate neuf câblé), `python .claude/scripts/run_gates.py`. Scopes JaCoCo (`scripts/quality/check-jacoco.py`) : `program-graph-analysis`, `java-advanced-provider`, `semantic-hybrid-retrieval`, `semantic-vector-store`, `persistence-cache-indexes`, `m30-postgresql-pgvector`, `m26-runtime-dynamic-intelligence`, `global`. SonarCloud : 80 % de nouveau code par module.
- **Plateformes** : Windows et Linux, code JDK pur ; PostgreSQL réel pour le store PostgreSQL (CI Linux ; scope `m30-postgresql-pgvector` sauté sur Windows).
