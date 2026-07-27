# État courant — MINOS

Dernière mise à jour documentaire : **27 juillet 2026**

Ce fichier résume l'état produit livré. Les preuves détaillées sont conservées dans [`history/milestones/`](history/milestones/) et dans les PR/issues de qualification ; les décisions durables sont indexées dans [`adr/`](adr/README.md).

## Synthèse

```text
C0 — Cadrage                         TERMINÉ
M0 — Faisabilité technique          TERMINÉ ET LIVRÉ
M1 — Découverte et orchestration    TERMINÉ ET LIVRÉ
M2 — Intelligence des symboles      TERMINÉ ET LIVRÉ
M3 — Intelligence des relations     TERMINÉ ET LIVRÉ
M4 — Recherche et contexte compact  TERMINÉ ET LIVRÉ
M5 — Tests liés et dérivations      TERMINÉ ET LIVRÉ
M6 — Intelligence d'architecture    TERMINÉ, VALIDÉ ET LIVRÉ
M7 — Indexation incrémentale        TERMINÉ, VALIDÉ ET LIVRÉ
M8 — Analyse d'impact               TERMINÉ, VALIDÉ ET LIVRÉ
M9 — CLI stabilisée                 TERMINÉ, VALIDÉ ET LIVRÉ
M10 — Serveur MCP                   TERMINÉ, VALIDÉ ET LIVRÉ
M11 — API publique                  TERMINÉ, VALIDÉ ET LIVRÉ
M12 — Multi-dépôts + Git            TERMINÉ, VALIDÉ ET LIVRÉ
M13 — Intégration NEXUS             TERMINÉ, VALIDÉ ET LIVRÉ
M14 — Indexation autonome + PROD    TERMINÉ, VALIDÉ ET LIVRÉ
M15 — Industrialisation Core        TERMINÉ, VALIDÉ ET LIVRÉ
M16 — Scalabilité et performance    TERMINÉ, VALIDÉ ET LIVRÉ
M17 — Provider & Discovery Platform TERMINÉ, VALIDÉ ET LIVRÉ
M18 — MINOS for IntelliJ            TERMINÉ, VALIDÉ ET LIVRÉ
M19 — Advanced Code Intelligence    TERMINÉ, VALIDÉ ET LIVRÉ
```

## M19 — Advanced Code Intelligence

M19 étend MINOS vers un graphe de programme avancé provider-independent sans transformer des approximations statiques en vérités runtime.

Acquis M19 :

```text
S1   ProgramGraph provider-independent + capabilities                 ✅
S2   Call graph v2 avec provenance et mesure précision/rappel         ✅
S3   CFG explicite : branches, boucles, exceptions                    ✅
S4   Data Flow / DEF_USE + limitations READS/WRITES                   ✅
S5   propagation interprocédurale bornée + cycles visibles            ✅
S6   composition CPG déterministe, collisions incohérentes rejetées   ✅
S7   Impact v2 avec M8 conservé comme baseline                        ✅
S8   SOURCE / SINK / SANITIZER + chemins sécurité bornés              ✅
S9   AdvancedCodeIntelligenceApi v1 + 3 tools MCP                     ✅
```

### Frontière d'analyse avancée

```text
snapshot actif MINOS
      ↓
ProgramGraphProvider(s)
      ↓
ProgramGraphComposer
      ↓
ProgramGraph capability-honest
      ├── call/control/data flow
      ├── interprocedural flow
      ├── CPG
      ├── Impact v2
      └── security paths
```

Les capacités absentes ne sont jamais inventées. Les relations historiques `READS/WRITES` restent des dérivations potentielles avec `EXECUTION_ORDER_NOT_PROVEN`. L'absence de chemin sécurité n'est jamais interprétée comme preuve d'absence de vulnérabilité.

### Surfaces M19

- API Java : `AdvancedCodeIntelligenceApi` v1 additive, `MinosApi` v1 inchangée ;
- MCP : 19 tools read-only au total, dont `minos_program_graph`, `minos_impact_v2`, `minos_security_paths` ;
- Impact v2 : baseline M8 conservée, enrichissements M19 comptés séparément ;
- stockage : aucun nouveau stockage autoritatif, le graphe avancé reste une vue reconstruisible des snapshots et faits provider.

### Qualification

Porte reproductible :

```text
scripts/m19/run-final.ps1
```

Qualification Windows exact-head :

```text
Validated HEAD: 859138cbfdd4e0722a6366efd97fa62ad95c2443
M19 FINAL ADVANCED CODE INTELLIGENCE VALIDATION SUCCESS
```

Preuves : reactor Maven Java 24 **13/13 SUCCESS**, tests application **112/112**, API **10/10**, MCP **5/5**, agrégateur **50/50**, smoke shaded JAR **1/1**, JaCoCo tous gates PASS, HEAD stable et worktree propre.

PR #70 mergée via le commit `3630ebd0f229e1bc028e92444bfa34c3e7609596`. Issue #69 fermée comme `completed`.

## M18 — MINOS for IntelliJ

M18 livre une intégration IntelliJ native et optionnelle qui consomme MINOS comme client externe sans réimplémenter le moteur ni introduire de dépendance à un LLM.

Acquis M18 :

```text
S1   protocole IDE minos-ide v1 + handshake stateless             ✅
S2   plugin IntelliJ autonome Java 21                              ✅
S3   project/provider/snapshot/index status                        ✅
S4   navigation symboles + positions UTF-8/16/32                  ✅
S5   architecture graph borné et filtrable                         ✅
S6   impact + related tests explicables                             ✅
S7   index/reindex/doctor via lifecycle MINOS                       ✅
S8   Git activity factuelle, sans inférence d'importance            ✅
S9   ZIP plugin + validation/release + Plugin Verifier              ✅
```

### Frontière IDE

```text
IntelliJ IDEA / Java 21
        ↓ processus local JSON
minos-ide v1
        ↓
MINOS CLI / MinosApplication / Java 24
```

Le plugin ne dépend d'aucun artefact Maven `com.minos:*`, ne charge aucune classe interne du moteur et ne publie jamais directement de snapshot. Le lifecycle MINOS existant reste propriétaire de l'indexation et de la promotion atomique.

### Qualification

La porte reproductible M18 est :

```text
scripts/m18/run-final.ps1
```

Qualification Windows exact-head :

```text
Validated HEAD: 0186146668c12027f44b55d0511a45e89e6dee61
M18 FINAL INTELLIJ INTEGRATION VALIDATION SUCCESS
Plugin Verifier: Compatible with IntelliJ IDEA 2026.1 (IU-261.22158.277)
```

PR #68 mergée via le commit `faa51f63c5967d874a7a6685b6b513b83bb736b4`. Issue #67 fermée comme `completed`.

## M17 — Provider & Discovery Platform

M17 transforme la découverte et les providers en plateforme d'extensions explicites sans ajouter de branches d'écosystème dans les orchestrateurs centraux.

Acquis M17 :

```text
S1   Discovery SPI : project/build/source-root/language detectors      ✅
S2   Provider SPI + registry d'extensions                              ✅
S3   capability model FULL/PARTIAL/EXPERIMENTAL/UNSUPPORTED           ✅
S4   Gradle Java/Kotlin discovery, multi-module                        ✅
S5   npm/pnpm/yarn workspace discovery                                 ✅
S6   Kotlin/Maven négocié par scip-java                                ✅
S7   Python géré par scip-python 0.6.6                                 ✅
S8   provider conformance kit déterministe                             ✅
S9   installation runtime composée et provider-neutral                 ✅
```

### Architecture d'extension

```text
ProjectDiscoveryService
        ↓
ProjectDetector / BuildSystemDetector / SourceRootDetector / LanguageDetector
        ↓
ProjectDiscovery factuel

IndexerProviderRegistry
        ↓
IndexerProvider → descriptor + ProviderCapabilityProfile exhaustif
        ↓
IndexerRegistry neutre de négociation

ProviderRuntimeManager
        ↓
CompositeProviderRuntimeManager
        ├── scip-java / scip-typescript
        └── scip-python
```

Le modèle de capacités interdit toute absence implicite : chaque capacité reçoit obligatoirement `FULL`, `PARTIAL`, `EXPERIMENTAL` ou `UNSUPPORTED`.

### Écosystèmes

- Maven Java/TypeScript historiques : non-régression M14 obligatoire ;
- Gradle : découverte Java/Kotlin et modules ; aucun runtime Gradle n'est inventé ;
- npm/pnpm/yarn : marqueurs workspace et build system hérités aux packages ;
- Kotlin : discovery + négociation Maven via `scip-java` ;
- Python : discovery + runtime géré `scip-python` `0.6.6` installé sous `MINOS_HOME/tools`.

### Surfaces

- CLI : `minos providers` expose niveaux, score, limitations et état runtime ;
- API Java : `ProviderPlatformApi` v1 est additive et laisse `MinosApi` v1 inchangée ;
- MCP : `minos_project_structure` et `minos_index_status` exposent `providerProfiles` ; les 16 tools historiques restent présents dans le catalogue M19 de 19 tools ;
- doctor/tools : les runtimes optionnels sont visibles/installables sans rendre la baseline historique rouge lorsqu'ils ne sont pas installés.

### Qualification

La porte reproductible M17 est :

```text
scripts/m17/run-final.ps1
```

Elle rejoue `clean verify`, JaCoCo, product facts et l'intégralité de M14/Java/TypeScript/STALE/Windows, puis qualifie réellement Kotlin/Maven et Python avec installation provider, indexation, snapshot actif et requêtes symboles/usages. Verdict requis :

```text
M17 FINAL PROVIDER PLATFORM VALIDATION SUCCESS
```

La preuve exacte (SHA, tests, runtimes et résultats provider) est enregistrée dans la PR et l'issue M17 afin de ne pas modifier le head après qualification.

## M16 — Scalabilité et performance à grande échelle

M16 ajoute une campagne de performance reproductible sans changer les contrats publics MINOS ni présélectionner un backend plus complexe.

Acquis M16 :

```text
S1   harness benchmark exact-head + machine/JVM                    ✅
S2   datasets synthétiques déterministes et fixtures réelles       ✅
S3   query benchmark p50/p95/p99                                   ✅
S4   MCP sustained load sur serveur STDIO long-lived               ✅
S5   indexation réelle FULL/NONE + débit/RSS                        ✅
S6   profil heap/RSS/disque/indexes                                 ✅
S7   décision backend gouvernée par mesures                         ✅
S8   optimisation uniquement sous goulot prouvé                    ✅
S9   rétention/compaction snapshots + runs                          ✅
```

Le gate STANDARD utilise 10 000 fichiers logiques, 100 000 symboles, 500 000 occurrences et 250 000 relations avec seed `16000031`. Les profils `SMOKE`, `EXTENDED` et `STRESS` restent diagnostics. Le backend retenu reste snapshots fichiers versionnés + `SnapshotQueryView` + indexes mémoire reconstruisibles conformément à [ADR-0025](adr/0025-measurement-gated-storage-backend-evolution.md).

La croissance disque est bornée : snapshot actif + 2 historiques ; 20 runs réussis + 10 non réussis ; `latestRunId` protégé.

## M15 — Industrialisation du Core Engine

M15 transforme le socle M14 en plateforme modulaire, réutilisable en processus long et protégée par des gates automatiques.

```text
S1   baseline non-régression et coût initial                  ✅
S2   reactor Maven multi-module                               ✅
S3   MinosApplication / composition root partagé              ✅
S4   MCP découplé de la CLI métier                            ✅
S5   ProjectResolver commun                                   ✅
S6   persistance snapshots décomposée                         ✅
S7   cache borné du snapshot actif                            ✅
S8   indexes de requête reconstruisibles                      ✅
S9   JaCoCo + quality gates ciblées                           ✅
S10  CI automatique des pull requests Linux/Windows           ✅
S11  cohérence documentaire calculable                        ✅
```

Les snapshots persistés restent la source de vérité ; les indexes mémoire sont reconstruisibles. La CI de PR et les facts calculables restent les gates d'industrialisation.

## Contrats publics courants

- CLI : stable avec codes de sortie `0/1/2`, diagnostics provider additifs et protocole IDE `minos-ide` v1 ;
- API Java : `MinosApi` v1 stable + `ProviderPlatformApi` v1 + `AdvancedCodeIntelligenceApi` v1 additives ;
- MCP : STDIO read-only, 19 tools au total, dont les 16 historiques et les 3 tools M19 ;
- NEXUS : export JSON local versionné ;
- IntelliJ : plugin optionnel Java 21 consommant MINOS via protocole local JSON versionné ;
- installation PROD Windows : ZIP versionné, runtime Java embarqué, doctor et MCP natif ;
- Docker MCP : mode durci optionnel.

Les valeurs calculables exactes sont dans [`generated/product-facts.md`](generated/product-facts.md).

## Frontières architecturales courantes

- MINOS reste propriétaire des faits de Code Intelligence ;
- NEXUS reste propriétaire du ranking, de la sélection et du budget de contexte ;
- le plugin IntelliJ reste un client externe et ne duplique pas le métier ;
- les capacités fournisseur et graphes absents ne sont jamais inventés ;
- un nouveau langage/build system/provider se branche via SPI/catalogue d'extensions ;
- discovery et support runtime sont des faits distincts ;
- l'analyse d'impact reste potentielle, jamais une preuve runtime exhaustive ;
- Impact v2 conserve M8 comme baseline et distingue explicitement les enrichissements du program graph ;
- les chemins sécurité sont des chemins statiques observés et bornés, jamais une preuve exhaustive de sûreté ou vulnérabilité ;
- une relation cross-repository exige une identité exacte et unique ;
- CLI, API, MCP, NEXUS et IntelliJ consomment le même cœur applicatif ou ses contrats versionnés ;
- les snapshots persistés sont la source de vérité des vues/indexes mémoire ;
- toute évolution de backend est gouvernée par des mesures reproductibles M16.

## Suite

M20 — **Recherche sémantique hybride** — est le prochain jalon planifié.

## Documentation

- portail : [`README.md`](README.md) ;
- roadmap : [`ROADMAP.md`](ROADMAP.md) ;
- exécution M15 : [`roadmap/M15_EXECUTION.md`](roadmap/M15_EXECUTION.md) ;
- exécution M16 : [`roadmap/M16_EXECUTION.md`](roadmap/M16_EXECUTION.md) ;
- exécution M17 : [`roadmap/M17_EXECUTION.md`](roadmap/M17_EXECUTION.md) ;
- exécution M18 : [`roadmap/M18_EXECUTION.md`](roadmap/M18_EXECUTION.md) ;
- exécution M19 : [`roadmap/M19_EXECUTION.md`](roadmap/M19_EXECUTION.md) ;
- utilisateur : [`user/README.md`](user/README.md) ;
- développeur : [`developer/README.md`](developer/README.md) ;
- qualité : [`developer/quality-gates.md`](developer/quality-gates.md) ;
- facts générés : [`generated/product-facts.md`](generated/product-facts.md) ;
- décisions : [`adr/README.md`](adr/README.md) ;
- preuves historiques : [`history/milestones/README.md`](history/milestones/README.md).

## Source de vérité

`STATUS.md` décrit l'état livré. `ROADMAP.md` décrit la progression produit. Les ADR décrivent les décisions durables. Les rapports sous `history/milestones/` restent des archives et peuvent contenir des états intermédiaires propres à leur date de validation.