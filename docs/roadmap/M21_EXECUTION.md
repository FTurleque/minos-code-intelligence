# M21 — Production Integrity & Surface Convergence — exécution

Statut : **EN COURS — S1 VALIDÉ ; S2 EN PAUSE jusqu’en août 2026 ; S3 VALIDÉ ; S4 VALIDÉ ; S5 EN COURS ; S6→S9 planifiés.**

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

## Sous-incréments

| Étape | Fonction | État / gate |
|---|---|---|
| M21-S1 | Governance & authoritative consolidation | **VALIDÉ** sur `b4403921bfe0e2a7fe5eef9380a122982f275e0e` |
| M21-S2 | CI recovery & branch protection readiness | **PAUSE jusqu’en août 2026 — aucun run CI** |
| M21-S3 | Quality gates M19/M20 | **VALIDÉ** sur `27b4bafb35eadfdb9827b4d4cfccf7073b1e5e94` |
| M21-S4 | Maven module-boundary hardening | **VALIDÉ** sur `0699d06d6138dd77008b8ea31578a334468eec75` |
| M21-S5 | Supply-chain & release hardening | **EN COURS — candidat local à qualifier** |
| M21-S6 | IntelliJ parity M19/M20 | planifié |
| M21-S7 | Advanced provider productionization | planifié |
| M21-S8 | Semantic scale qualification | planifié |
| M21-S9 | Final production integrity gate | planifié |

## M21-S1 — Governance & authoritative consolidation

Statut : **VALIDÉ le 27 juillet 2026.**

Livrables :

- issue #73 et branche `m21-production-integrity` ;
- roadmap M21 et trajectoire M22→M27 ;
- README/STATUS/guide utilisateur réalignés ;
- `scripts/docs/check-current-docs.py` ;
- `scripts/m21/run-local.ps1`.

Qualification :

```text
Maven reactor: 13/13 SUCCESS
M20 FINAL SEMANTIC HYBRID CODE INTELLIGENCE VALIDATION SUCCESS
M21 CURRENT DOCUMENTATION CONSISTENCY SUCCESS (MCP tools=23)
M21 LOCAL CONSOLIDATION VALIDATION SUCCESS
Validated HEAD: b4403921bfe0e2a7fe5eef9380a122982f275e0e
```

## M21-S2 — CI recovery & branch protection readiness

Statut : **EN PAUSE jusqu’en août 2026 — aucun déclenchement GitHub Actions, aucune modification de workflow avant reprise explicite.**

L'incident #5 reste documenté. Le symptôme distant historique `steps=[]`, `logs_url=null`, `BlobNotFound` n'est pas traité en juillet et ne doit pas être imputé au code MINOS sans preuve exploitable.

À la reprise en août :

- vérifier politique Actions, quotas/runners, restrictions et permissions ;
- obtenir au moins un workflow exposant steps/logs/artifacts ;
- supprimer les jobs historiques milestone-specific ;
- converger vers des workflows durables `ci`, `quality`, `intellij`, `release` ;
- définir les checks bloquants avant merge.

## M21-S3 — Quality gates M19/M20

Statut : **VALIDÉ le 27 juillet 2026** sur `27b4bafb35eadfdb9827b4d4cfccf7073b1e5e94`.

Les cinq scopes historiques M15 sont conservés et six scopes M19/M20 ont été ajoutés :

```text
program-graph-analysis
advanced-impact-security
semantic-vector-store
semantic-hybrid-retrieval
advanced-public-api
m19-m20-mcp-catalogue
```

Résultats :

```text
program-graph-analysis      90.46 % lignes / 57.86 % branches
advanced-impact-security    93.18 % lignes / 57.69 % branches
semantic-vector-store       94.23 % lignes / 61.54 % branches
semantic-hybrid-retrieval   89.42 % lignes / 64.69 % branches
advanced-public-api         70.59 % lignes / 55.26 % branches
m19-m20-mcp-catalogue       93.49 % lignes / 60.00 % branches
M21 JACOCO GATE SUCCESS
```

Les seuils restent des planchers ciblés anti-régression ; toute baisse future nécessite une justification documentée.

## M21-S4 — Maven module-boundary hardening

Statut : **VALIDÉ le 27 juillet 2026** sur `0699d06d6138dd77008b8ea31578a334468eec75`.

L'ADR-0022 indiquait que M15 avait déjà relocalisé physiquement les sources/tests dans leurs modules propriétaires. Les filtres `<includes>/<excludes>` encore présents dans les configurations `maven-compiler-plugin` étaient une dette transitoire résiduelle.

Livrables :

1. suppression des allowlists/denylists de compilation dans les 12 modules enfants ;
2. compilation naturelle de chaque `src/main/java` ;
3. conservation des exclusions du `maven-shade-plugin`, qui relèvent du packaging ;
4. `scripts/architecture/check-module-boundaries.py` ;
5. adaptation des runners M19/M20 pour qu'ils vérifient les invariants métier sans dépendre de l'ancien mécanisme d'ownership.

Qualification :

```text
M21 MODULE BOUNDARY CONSISTENCY SUCCESS (modules=12, sources=263)
Maven reactor: 13/13 SUCCESS
M21 JACOCO GATE SUCCESS
M20 FINAL SEMANTIC HYBRID CODE INTELLIGENCE VALIDATION SUCCESS
M21 LOCAL CONSOLIDATION VALIDATION SUCCESS
Validated HEAD: 0699d06d6138dd77008b8ea31578a334468eec75
```

## M21-S5 — Supply-chain & release hardening

Statut : **EN COURS — candidat Windows local à qualifier.**

S5 reste volontairement séparé de la CI en juillet. L'épinglage immuable des GitHub Actions reste affecté à S2 lors de sa reprise en août.

Livrables S5 :

- CycloneDX Maven Plugin `2.9.2`, SBOM JSON agrégé CycloneDX 1.6 généré explicitement depuis la racine d'exécution Maven après le build du reactor ;
- scope test exclu du SBOM de distribution ;
- `scripts/release/generate-third-party-notices.py` avec politique stricte : aucune licence inventée ;
- `scripts/release/create-release-manifest.py` : version, commit, taille et SHA-256 de chaque fichier de distribution ;
- `scripts/release/check-supply-chain.py` : cohérence SBOM/notices/manifest/VERSION ;
- ZIP et setup embarquent `supply-chain/minos.cdx.json`, `supply-chain/THIRD-PARTY-NOTICES.txt` et `RELEASE-MANIFEST.json` ;
- sidecars de release SBOM/notices + `.sha256` publiables avec le setup/ZIP ;
- `scripts/release/sign-windows-artifact.ps1` pour signature Authenticode explicite lorsqu'un certificat est disponible ;
- `scripts/m21/run-s5.ps1` rejoue core, packaging, checksums, ZIP install, setup install/uninstall et politique de signature.

Premier candidat `1b4f8cdc7d0821a6af5504a4873b4b4647bdb5c1` : le gate core a entièrement passé (`13/13`, JaCoCo 11 scopes, M20/M21 exact-head), puis la distribution a échoué avant packaging supply-chain. CycloneDX affichait `Skipping CycloneDX on non-execution root` parce que `makeAggregateBom` était attaché à `minos-app`. Le correctif retire cette exécution du module enfant et invoque l'aggregator depuis la racine Maven après le build ; `-SkipVerify` évite aussi de rejouer Surefire après le gate core déjà validé.

La signature n'est pas simulée. Sans certificat, le candidat peut être qualifié non signé ; `MINOS_REQUIRE_SIGNED_RELEASE=1` transforme une signature Authenticode valide en exigence bloquante.

Gate local S5 :

```powershell
.\scripts\m21\run-s5.ps1 -ExpectedHead <sha>
```

Verdict attendu :

```text
M21 SUPPLY-CHAIN EVIDENCE SUCCESS
MINOS Windows release VALIDATION SUCCESS
M21-S5 SUPPLY-CHAIN RELEASE VALIDATION SUCCESS
Validated HEAD: <sha>
```

## M21-S6 — IntelliJ parity M19/M20

Cibles fonctionnelles :

```text
Program Graph
Impact v2
Security paths
Semantic index status
Semantic search
Hybrid search / context
```

Le protocole `minos-ide` reste versionné. Le plugin ne doit pas embarquer ni réimplémenter le moteur Java 24. La compatibilité doit évoluer vers une matrice IDE explicitement testée.

## M21-S7 — Advanced provider productionization

La surface supportée par le moteur doit rester distincte de la capacité réellement fournie par le provider courant. Un provider ne peut annoncer `CONTROL_FLOW`, `DEF_USE`, `INTERPROCEDURAL_DATA_FLOW` ou `SECURITY_TAINT` que si des fixtures contrôlées le prouvent.

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

Le scan linéaire ou le format vectoriel ne seront remplacés que si les mesures démontrent un goulot produit.

## M21-S9 — Final production integrity gate

Le gate final devra vérifier au minimum :

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

## Validation locale

Gate général :

```powershell
.\scripts\m21\run-local.ps1 -ExpectedHead <sha>
```

Gate S5 :

```powershell
.\scripts\m21\run-s5.ps1 -ExpectedHead <sha>
```

## Source de vérité

- état livré : [`../STATUS.md`](../STATUS.md) ;
- roadmap produit : [`../ROADMAP.md`](../ROADMAP.md) ;
- facts calculables : [`../generated/product-facts.md`](../generated/product-facts.md) ;
- supply-chain : [`../developer/supply-chain.md`](../developer/supply-chain.md) ;
- issue de pilotage : #73.