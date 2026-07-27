# M21 — Production Integrity & Surface Convergence — exécution

Statut : **EN COURS — S1 VALIDÉ ; S2 EN PAUSE jusqu’en août 2026 ; S3 EN COURS ; S4→S9 planifiés.**

Issue : **#73 — M21 — Production Integrity & Surface Convergence**.

Branche : `m21-production-integrity`.

## Question produit

> MINOS peut-il devenir un produit continuellement qualifié, cohérent sur toutes ses surfaces et distribuable avec un niveau de confiance production, sans affaiblir ses invariants local-first, capability-honest et measurement-gated ?

M21 est volontairement un jalon de **consolidation post-M20**. Il ne doit pas masquer une dette de CI, de qualité, de documentation ou de distribution derrière de nouvelles fonctionnalités métier.

## Invariants non négociables

- les snapshots structurés restent la source d'autorité ;
- `FACTUAL`, `DERIVED` et `HEURISTIC` restent distincts ;
- aucune capacité provider absente n'est inventée ;
- le sémantique reste optionnel et non autoritatif ;
- NEXUS conserve le ranking global, la sélection finale et le budget multi-source ;
- le plugin IntelliJ reste un client externe du moteur ;
- les vues, caches et indexes restent reconstruisibles depuis les sources autoritatives ;
- aucune migration de backend n'est autorisée sans mesure reproductible ;
- la qualification finale reste rattachée à un HEAD exact et à un worktree propre.

## Motivations post-M20

L'audit du `main` post-M20 a identifié plusieurs écarts d'industrialisation :

1. GitHub Actions reste affecté par l'incident historique #5 (`steps=null`, logs indisponibles), mais **aucun travail CI ni déclenchement Actions n'est autorisé avant août 2026** ;
2. les quality gates JaCoCo restent centrés sur les responsabilités M15 et ne qualifient pas explicitement toutes les zones M19/M20 ;
3. les frontières Maven utilisent encore des allowlists `<includes>/<excludes>` fragiles ;
4. README, STATUS et guide utilisateur peuvent diverger malgré les facts générés ;
5. les capacités M19/M20 ne sont pas encore convergées dans l'UX IntelliJ ;
6. la composition standard ne fournit pas encore toutes les capacités avancées qu'autorise le SPI Program Graph ;
7. la recherche sémantique M20 utilise encore un scan vectoriel linéaire et n'a pas été rejouée avec une campagne d'échelle dérivée de M16 ;
8. la chaîne de release doit encore être durcie sur la supply-chain.

## Sous-incréments

| Étape | Fonction | Résultat attendu | Gate |
|---|---|---|---|
| M21-S1 | Governance & authoritative consolidation | roadmap, issue, docs courantes cohérentes, contrôle automatisé et runner local unique | **VALIDÉ** sur `b4403921bfe0e2a7fe5eef9380a122982f275e0e` |
| M21-S2 | CI recovery & branch protection readiness | jobs Actions exploitables, checks PR identifiés et incident #5 résolu ou isolé avec preuve | **PAUSE jusqu’en août 2026 — aucun run CI** |
| M21-S3 | Quality gates M19/M20 | couverture ciblée Program Graph / Impact v2 / security / semantic / hybrid + Sonar aligné | seuils documentés et verts |
| M21-S4 | Maven module-boundary hardening | suppression progressive des allowlists fragiles et frontières architecturales explicites | build reactor + tests de frontières |
| M21-S5 | Supply-chain & release hardening | dépendances contrôlées, SBOM/notices/provenance/signature selon faisabilité | release candidate reproductible |
| M21-S6 | IntelliJ parity M19/M20 | surfaces avancées utiles exposées dans l'IDE sans duplication du métier | tests protocole + Plugin Verifier |
| M21-S7 | Advanced provider productionization | au moins un provider réel enrichit Program Graph au-delà des relations historiques | fixtures contrôlées + précision/rappel/capabilities |
| M21-S8 | Semantic scale qualification | M20 mesuré à l'échelle avant toute évolution de backend/vector index | p50/p95/p99 + heap/RSS/disque |
| M21-S9 | Final production integrity gate | convergence CI/quality/docs/IDE/release/perf sur HEAD exact | verdict final M21 |

## M21-S1 — Governance & authoritative consolidation

Statut : **VALIDÉ le 27 juillet 2026.**

Livrables :

- issue #73 et branche `m21-production-integrity` ;
- présente roadmap opérationnelle ;
- `docs/ROADMAP.md` déclare M21 et les évolutions suivantes ;
- `docs/STATUS.md` distingue clairement l'état livré C0→M20 du jalon M21 en cours ;
- README racine réaligné sur l'état post-M20 ;
- guide utilisateur réaligné sur le catalogue MCP courant ;
- `scripts/docs/check-current-docs.py` empêche la réintroduction des divergences documentaires critiques ;
- `scripts/m21/run-local.ps1` devient l'entrée de validation locale M21.

Qualification autoritative Windows :

```text
Maven reactor: 13/13 SUCCESS
M20 FINAL SEMANTIC HYBRID CODE INTELLIGENCE VALIDATION SUCCESS
M15 JACOCO GATE SUCCESS
M21 CURRENT DOCUMENTATION CONSISTENCY SUCCESS (MCP tools=23)
M21 LOCAL CONSOLIDATION VALIDATION SUCCESS
Validated HEAD: b4403921bfe0e2a7fe5eef9380a122982f275e0e
```

## M21-S2 — CI recovery & branch protection readiness

Statut : **EN PAUSE jusqu’en août 2026 — aucun déclenchement GitHub Actions, aucune modification de workflow dans le cadre de S2 avant cette échéance.**

L'incident #5 reste documenté. Le HEAD S1 validé localement reproduit encore le symptôme distant historique (`steps=[]`, `logs_url=null`, logs `BlobNotFound`), sans preuve d'échec du code MINOS. Ce diagnostic est conservé uniquement comme état de référence ; il ne déclenche aucune action avant août.

À la reprise en août :

- reprendre l'issue #5 sans attribuer au code un échec pré-step ;
- vérifier politique Actions, quotas/runners, restrictions d'actions et permissions du dépôt privé ;
- obtenir au moins un workflow PR exposant steps, logs et artifacts ;
- supprimer les jobs historiques conditionnés par des noms de branches M15/M18/M19/M20 ;
- converger vers des workflows durables `ci`, `quality`, `intellij`, `release` ;
- documenter les checks destinés à devenir bloquants avant merge.

## M21-S3 — Quality gates M19/M20

Statut : **EN COURS.**

Priorité : **P0**.

Les seuils M15 existants restent une baseline mais ne suffisent plus. M21 doit ajouter des scopes explicites couvrant au minimum :

```text
ProgramGraph / composition / traversals
AdvancedImpactService
SecurityAnalysisService
SemanticVectorStore
SemanticIndexService
SemanticSearchService
HybridSearchService
HybridContextBuilder
API/MCP avancées associées
```

Le résultat attendu n'est pas un pourcentage global arbitraire : les responsabilités critiques doivent être couvertes par des seuils ciblés et des tests fonctionnels séparés.

## M21-S4 — Maven module-boundary hardening

Objectif : remplacer progressivement les allowlists de compilation par des frontières physiques et des règles d'architecture vérifiables.

Ordre attendu :

```text
1. inventorier includes/excludes actuels
2. définir la frontière cible par module
3. déplacer les sources si nécessaire
4. compiler les sources naturelles du module
5. interdire les dépendances invalides par tests/règles
6. supprimer les listes fragiles devenues inutiles
```

Aucune refactorisation de frontière ne doit modifier les contrats publics ou la sémantique métier.

## M21-S5 — Supply-chain & release hardening

À qualifier selon la faisabilité réelle du dépôt et de la distribution Windows :

- dépendances automatisées et vulnérabilités connues ;
- GitHub Actions épinglées de manière immuable ;
- SBOM CycloneDX ou SPDX ;
- notices tierces/licences de redistribution ;
- provenance de build ;
- signature Authenticode du setup lorsque la chaîne de certificat est disponible ;
- vérification install/update/rollback/uninstall conservée.

## M21-S6 — IntelliJ parity M19/M20

Cibles fonctionnelles, sous réserve de valeur UX mesurable :

```text
Program Graph
Impact v2
Security paths
Semantic index status
Semantic search
Hybrid search / context
```

Le protocole `minos-ide` doit rester versionné. Le plugin ne doit pas embarquer ni réimplémenter le moteur Java 24.

La compatibilité doit évoluer d'un seul IDE `current()` vers une politique de matrice explicitement testée.

## M21-S7 — Advanced provider productionization

M19 a livré le modèle et les SPI avancés. M21 doit distinguer clairement :

```text
surface supportée par le moteur
≠
capacité réellement fournie par le provider courant
```

Un provider ne peut annoncer `CONTROL_FLOW`, `DEF_USE`, `INTERPROCEDURAL_DATA_FLOW` ou `SECURITY_TAINT` que si des fixtures contrôlées le prouvent.

## M21-S8 — Semantic scale qualification

La référence reste la philosophie M16 : **mesurer avant d'industrialiser**.

La campagne doit mesurer au minimum :

```text
semantic index build/rebuild
semantic incremental reuse
semantic search p50/p95/p99
hybrid search p50/p95/p99
hybrid context p50/p95/p99
heap/RSS
vector store disk size
allocations/boxing dominants
```

Le scan linéaire ou le format vectoriel ne seront remplacés que si les mesures démontrent un goulot produit. ANN/HNSW/Lucene/autre backend restent des options, pas des décisions anticipées.

## M21-S9 — Final production integrity gate

Le gate final devra au minimum vérifier :

1. worktree propre et HEAD exact ;
2. facts documentaires et docs courantes cohérents ;
3. Maven Java 24 `clean verify` ;
4. quality gates M21 ;
5. tests/plugin verifier IntelliJ ;
6. replays providers et surfaces publiques ;
7. campagne performance M19/M20 retenue ;
8. packaging Windows et preuves supply-chain retenues ;
9. HEAD inchangé à la fin de la qualification.

Verdict attendu :

```text
M21 FINAL PRODUCTION INTEGRITY VALIDATION SUCCESS
Validated HEAD: <sha>
```

## Validation locale S1

Qualification exécutée sur Windows :

```powershell
.\scripts\m21\run-local.ps1 -ExpectedHead b4403921bfe0e2a7fe5eef9380a122982f275e0e
```

Toute modification postérieure à ce SHA impose une nouvelle qualification exact-head avant promotion.

## Source de vérité

- état livré : [`../STATUS.md`](../STATUS.md) ;
- roadmap produit : [`../ROADMAP.md`](../ROADMAP.md) ;
- facts calculables : [`../generated/product-facts.md`](../generated/product-facts.md) ;
- issue de pilotage : #73.