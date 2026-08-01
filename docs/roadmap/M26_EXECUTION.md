# M26 — Runtime & Dynamic Intelligence — exécution

Statut : **TERMINÉ, VALIDÉ EXACT-HEAD WINDOWS + LINUX ET FUSIONNÉ DANS `develop` — 9/9.**

```text
Issue          : #87 — CLOSED / completed
PR             : #88 — MERGED
Branche        : m26-runtime-dynamic-intelligence
Base           : develop @ e37cf39fcf4f7e417c618fa0b16590100c1e0b91
Qualified HEAD : bf702990125a485646b9b31817c7787086a1dbb3
Merge develop  : 9b6395ce9bcf6a7fe942d1f6c687a8ba97cbceef
Date           : 29 juillet 2026
```

M21-S2 / GitHub Actions reste **strictement en pause jusqu’en août 2026**. M26 ne modifie, n’exécute et n’utilise aucun workflow CI comme preuve.

## Question produit

> MINOS peut-il rapprocher faits statiques et observations runtime sans transformer une trace partielle en vérité exhaustive ?

## Invariants

- format d’entrée strict, versionné, borné et `PARTIAL` uniquement ;
- provenance projet, snapshot, temps, collector/version, environnement et source SHA-256 ;
- corrélation explicite `RESOLVED`, `AMBIGUOUS` ou `UNRESOLVED` ;
- absence de trace jamais interprétée comme non-exécution ;
- snapshot statique structuré toujours autoritatif et jamais muté par le runtime ;
- aucune promotion de CFG, def-use, interprocedural data-flow ou security capability ;
- stockage local immuable, atomique, checksum-verifié, verrouillé et borné ;
- CLI d’import explicite, MCP strictement read-only ;
- qualification locale exact-head Windows x86_64 + Linux x86_64 sur le même SHA.

## Sous-incréments

### M26-S1 — Cadrage, issue et ADR ✅ IMPLÉMENTÉ

Issue #87, draft PR #88, branche dédiée et ADR-0034 distinguent facts statiques, observations partielles, qualification produit et preuve e2e.

### M26-S2 — Modèle d’observations partielles ✅ IMPLÉMENTÉ

`RuntimeObservationSession`, `RuntimeObservation`, références, résolutions et corrélations portent des identités et limites explicites. `PARTIAL` est la seule complétude M26.

### M26-S3 — Import strict versionné ✅ IMPLÉMENTÉ

`RuntimeObservationEnvelopeCodec` accepte uniquement `minos-runtime-observation-v1`, UTF-8 sans BOM, métadonnées ordonnées et observations `symbol/call/line` bornées. Chemins absolus, traversal, champs inconnus et claims non partiels échouent fermé.

### M26-S4 — Corrélation au snapshot statique ✅ IMPLÉMENTÉ

`RuntimeIntelligenceService` exige le projet UUID et le snapshot actif exact, puis résout clé, qualified name ou fichier/ligne sans muter le snapshot ni choisir arbitrairement une ambiguïté.

### M26-S5 — Persistance immuable et bornée ✅ IMPLÉMENTÉ

`RuntimeObservationStore` et `FileRuntimeObservationStore` fournissent publication atomique, verrou inter-processus, checksum SHA-256 avant lecture, idempotence, refus de mutation, confinement et capacités explicites.

### M26-S6 — Couverture observée et hot paths ✅ IMPLÉMENTÉ

Les rapports agrègent symboles/lignes/appels observés, hits, durées, corrélations et hot paths. Ils déclarent `OBSERVED_PARTIAL`, `exhaustive=false` et leurs limitations.

### M26-S7 — CLI et MCP read-only ✅ IMPLÉMENTÉ

`minos runtime import|sessions|report|symbol` couvre l’administration et la lecture. Le MCP passe à 26 tools avec `minos_runtime_sessions`, `minos_runtime_report` et `minos_runtime_symbol`; aucun import MCP n’existe.

### M26-S8 — Tests, sécurité, docs et e2e local ✅ IMPLÉMENTÉ

Tests modèle/codec/service/store/CLI/MCP couvrent corrélation résolue/ambiguë/non résolue, snapshot stale, tampering, symlink, limites, idempotence et mutation. Le JAR ombré est exercé par `run-runtime-e2e.py` et le scope JaCoCo M26 dépasse ses seuils.

### M26-S9 — Qualification et promotion exact-head ✅ VALIDÉ ET FUSIONNÉ

Le SHA `bf702990125a485646b9b31817c7787086a1dbb3` a passé `run-final.ps1` sous Windows x86_64 et `run-final.sh` sous Linux x86_64 dans un clone ext4 propre. Les deux runners ont contrôlé le SHA exact, le worktree propre et le diff `.github/workflows` vide. La PR #88 ne contenait aucun review humain ni thread actionable ; elle a été passée Ready puis fusionnée dans `develop` avec `expected_head_sha`. L’issue #87 a ensuite été fermée `completed`.

## Dispositions finales

| Surface | Disposition finale | Limite explicite |
|---|---|---|
| `minos-runtime-observation-v1` | `QUALIFIED_WITH_CONSTRAINTS` | `PARTIAL` seulement ; collector externe |
| corrélation statique↔runtime | `QUALIFIED_WITH_CONSTRAINTS` | snapshot actif exact ; ambiguïtés conservées |
| couverture et hot paths observés | `QUALIFIED_WITH_CONSTRAINTS` | ratios non exhaustifs ; absence non probante |
| store local runtime | `QUALIFIED_WITH_CONSTRAINTS` | 128 sessions / 1 GiB par projet par défaut ; aucune éviction implicite |
| CLI runtime | `QUALIFIED_WITH_CONSTRAINTS` | import opérateur explicite puis lectures bornées |
| MCP runtime | `QUALIFIED_WITH_CONSTRAINTS` | trois tools read-only ; aucun import |

## Preuves finales exact-head

Windows x86_64 et Linux x86_64 ont produit, sur le même commit :

```text
M26 FINAL RUNTIME DYNAMIC INTELLIGENCE VALIDATION SUCCESS
Validated HEAD: bf702990125a485646b9b31817c7787086a1dbb3

M26 LINUX RUNTIME DYNAMIC INTELLIGENCE VALIDATION SUCCESS
Validated HEAD: bf702990125a485646b9b31817c7787086a1dbb3
```

L’évidence JSON détaillée est `status: PASS` sur chaque plateforme : format `minos-runtime-observation-v1`, nature `OBSERVED_PARTIAL`, `exhaustive: false`, session `PARTIAL` alignée au snapshot actif, corrélations `4 RESOLVED / 1 AMBIGUOUS / 1 UNRESOLVED`, et rejets fail-closed d’un claim `COMPLETE` comme d’une mutation d’identité de session. Le scope JaCoCo `m26-runtime-dynamic-intelligence` a validé 31 classes avec `line=0.848178` et `branch=0.660245`, sans réduction de seuil.

## Critères de sortie

1. gates statique/documentaire et reactor Maven Java 24 verts ;
2. scope JaCoCo M26 vert sans réduction d’un seuil historique ;
3. e2e du JAR réel avec détail JSON `status: PASS` ;
4. Windows et Linux valident le même HEAD propre ;
5. PR #88 revue, Ready puis fusionnée dans `develop` avec protection du HEAD ;
6. issue #87 fermée completed ;
7. réconciliation post-merge avec SHA réels et M27 prochain jalon.
