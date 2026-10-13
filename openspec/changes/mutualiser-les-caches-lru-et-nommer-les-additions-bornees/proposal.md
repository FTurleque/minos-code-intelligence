# Proposal

## Why

**AUD-QUA-05** (moyenne, sprint 11 de l'audit du 10 octobre 2026). Deux invariants délicats sont réécrits à la main, classe par classe.

- **Caches LRU pondérés.** Le code de production compte **8** `new LinkedHashMap<>(…, 0.75f, true)`, dont **7** sont des caches bornés en poids, chacun avec son comptage, son éviction et ses statistiques : `ProgramGraphService.java:44`, `FingerprintConstrainedJavaProgramGraphProvider.java:44`, `SemanticSearchService.java:27`, `HybridSearchService.java:42`, `FileSemanticVectorStore.java:61`, `FileSymbolSnapshotStore.java:59`, `PostgresCodeKnowledgeSnapshotStore.java:59`. Le huitième (`HostedDenialThrottle.java:28`) n'est pas pondéré. Une correction faite dans l'un n'atteint pas les six autres.
- **Additions bornées.** **13** helpers privés `safeAdd` ou `saturatingAdd`, **21** en comptant `checkedAdd` (3), `safeMultiply` (3) et `saturatingMultiply` (2). Le même nom `safeAdd` a **trois** comportements au dépassement : il sature (6 copies), il lève `OutputBudgetExceededException` (`DeterministicJson.java:241`), il lève `IllegalStateException` (`RuntimeIntelligenceService.java:303`). `checkedAdd` en a **deux**, ce que l'audit n'avait pas relevé : il sature dans `SemanticSearchService.java:129` et lève `IOException` dans `RemoteCloneBudget.java:143` et `FileSemanticVectorStore.java:441`. Copier un appel d'un fichier à l'autre change donc le comportement sans que le nom le dise.

## What Changes

Deux lots, livrables séparément.

- **Lot 1 — nommer par le comportement.** Chaque `safeAdd` et chaque `checkedAdd` privé est renommé d'après ce qu'il fait au dépassement (sature, ou lève). Aucun comportement ne change. Un gate neuf refuse le retour des noms ambigus.
- **Lot 2 — un seul cache LRU pondéré.** Une classe partagée porte l'invariant (bornes en entrées et en poids, poids décrémenté à chaque retrait, accès sûr, statistiques) ; les sept caches pondérés y migrent **un par un**, sous leurs tests existants. Chaque gate littéral qui cite une chaîne supprimée par la migration est réécrit **dans la même tâche**. Ce lot passe **après** les changements actifs de performance qui touchent les mêmes classes.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `audit-qualite-code` : trois exigences ajoutées (le nom d'une addition bornée dit son comportement au dépassement ; un cache LRU pondéré n'est écrit qu'une fois ; le cache partagé tient ses bornes et son poids).

## Hors périmètre

- Changer une borne, un plafond de poids ou une politique d'éviction : la migration est à comportement constant.
- `HostedDenialThrottle` (fenêtres glissantes, non pondéré) : exclu sauf décision contraire (D3).
- `safeMultiply` de `FileSymbolSnapshotStore`, exigé littéralement par `scripts/remediation/check-mne.py:86`, et le facteur d'amplification que traite `ramener-le-poids-des-vues-a-la-mesure`.
- Les lectures répétées et les clés de cache que traitent les changements actifs de performance.

## Impact

- **Modules du reactor** : lot 1 — `minos-application`, `minos-storage-local`, `minos-storage-postgresql`, `minos-integration-git` ; lot 2 — les mêmes moins `minos-integration-git`, plus `minos-engine` (classe partagée, sous réserve de D2).
- **Surfaces publiques** : aucune (helpers privés ; statistiques de cache inchangées en nom et en sens). CLI, API Java, MCP, IntelliJ, NEXUS : sans effet.
- **ADR** : aucun ADR nouveau attendu ; la classe partagée respecte l'ADR 0044 (package à propriétaire unique) et l'ADR 0022. Si D2 retenait `minos-domain`, le dire dans la « Mise en œuvre » de l'ADR 0044.
- **Gates à rejouer** : `python scripts/remediation/check-mne.py` (`:86`), `python scripts/remediation/check-mnd.py` (`:99-106`), `python scripts/remediation/check-p0-p2.py` (`:221`, `:305-312`), `python scripts/architecture/check-module-boundaries.py` (A8, propriété des packages), `python scripts/quality/check-ci-wiring.py` et `python scripts/quality/check-single-execution.py` (nouveau gate câblé), scopes JaCoCo `program-graph-analysis`, `java-advanced-provider`, `semantic-hybrid-retrieval`, `semantic-vector-store`, `persistence-cache-indexes`, `m30-postgresql-pgvector`, `m25-remote-distributed-indexing` (`scripts/quality/check-jacoco.py`).
- **Plateformes** : Windows et Linux ; PostgreSQL réel pour le store PostgreSQL (CI Linux).
