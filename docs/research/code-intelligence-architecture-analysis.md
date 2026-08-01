# C0 — Analyse consolidée de l’architecture Code Intelligence

Statut : **RÉCONCILIÉ RÉTROSPECTIVEMENT — décisions matérialisées par C0→M27 ; reliquats de hardening suivis par #95 et #93.**

Ce document ferme le livrable historique demandé par l’issue #2. Il ne réécrit pas l’histoire du projet : il relie les questions de cadrage initiales aux décisions effectivement prises, aux ADR et aux preuves livrées.

## Invariants conservés

- contrats et vocabulaire possédés par MINOS ;
- domaine indépendant des indexeurs et des backends ;
- `CodeKnowledgeStore` et snapshots structurés sous autorité MINOS ;
- local-first ;
- aucun LLM obligatoire ;
- nature, provenance, preuve, confiance et limitations explicites ;
- aucune capability extrapolée depuis un signal plus faible ;
- choix de backend guidés par mesures.

## Matrice de décision

| Axe | Décision | Disposition | Matérialisation |
|---|---|---|---|
| SCIP comme source sémantique structurée | Conserver SCIP comme famille de providers prioritaire lorsqu’un indexeur qualifié existe | **ADOPTER AVEC CONTRAINTES** | M0, M14, M17, M24 ; catalogue provider et conformance explicites |
| Tree-sitter | Conserver comme provider structurel potentiel ou fallback, jamais comme dépendance centrale obligatoire | **DIFFÉRER** | SPI M17 permet l’ajout ultérieur sans modifier le domaine ; aucun claim non qualifié |
| API compilateur Java | Employer l’AST public du JDK pour les facts avancés Java bornés | **ADAPTER** | M22 / ADR-0030 ; parsing sans attribution de type devinée |
| Glean | Maintenir une frontière fournisseur optionnelle, sans dépendance de production obligatoire | **DIFFÉRER** | décisions M0 ; backend local conservé tant qu’aucune mesure n’impose Glean |
| FalkorDB / graph store spécialisé | Ne pas faire du graphe externe la frontière du domaine ; exiger une preuve de bottleneck avant adoption | **REJETER COMME BASELINE / DIFFÉRER COMME ADAPTER** | `ProgramGraph` reconstructible M19 ; ADR-0025 et `KEEP_CURRENT_M20_BACKEND` |
| Architecture hybride de providers | Composer plusieurs providers derrière les contrats MINOS avec capabilities et provenance explicites | **ADOPTER** | M17, M21-S7, M22, M24 |
| Modèle normalisé symboles/relations | Identités stables, résolutions explicites, relations typées, preuves et provenance | **ADOPTER** | M2, M3, M5 ; domaine provider-independent |
| Graphe de programme | Vue reconstructible et capability-honest au-dessus du snapshot, non nouvelle base autoritative | **ADOPTER** | M19, M22 ; `ProgramGraphService` et providers composables |
| Requêtes déterministes | Fournir symboles, usages, appels, dépendances, architecture, impact et chemins explicatifs bornés | **ADOPTER** | M2→M11, API/CLI/MCP/IDE partagés |
| Indexation incrémentale | Fingerprints SHA-256, plans `NONE/INCREMENTAL/FULL`, invalidation conservatrice et fallback complet | **ADOPTER** | M7, M14, M16 ; snapshots d’empreintes immuables |
| Surveillance temps réel implicite | Ne pas muter les connaissances lors d’une simple lecture ; garder promotion explicite | **REJETER POUR LE CŒUR** | indexation et synchronisation administratives explicites |
| Recherche sémantique | Couche optionnelle reconstructible, signal `HEURISTIC`, fallback structuré | **ADOPTER AVEC CONTRAINTES** | M20 / ADR-0029 ; M23 / ADR-0031 |
| ANN / vector DB | Ne pas adopter sans bottleneck mesuré | **DIFFÉRER** | M21-S8 : `KEEP_CURRENT_M20_BACKEND` |
| Questions en langage naturel | Laisser le ranking global, l’orchestration multi-source et le budget de contexte à NEXUS/agents | **REJETER DU CŒUR MINOS** | M13 et signaux NEXUS v2 M20 |
| Intelligence architecturale | Topologie, dépendances, centralité relative, concentration et explications | **ADOPTER** | M6, M16 ; graphes et formats déterministes |
| Détection de communautés avancée | Introduire uniquement avec ground truth et gain mesuré | **DIFFÉRER** | aucune communauté « intelligente » inventée sans qualification |
| CLI locale et formats agents | Administration explicite, sorties texte/JSON et codes de sortie stables | **ADOPTER** | M9, M14, M18 et évolutions M19→M27 |
| Backend mémoire de test | Conserver des implémentations contrôlables pour les contrats et fixtures | **ADOPTER** | stores et fixtures de tests des modules engine/application |

## Comparaison des voies d’indexation

### SCIP

Forces retenues : identités et relations structurées, format interopérable, providers par écosystème. Limites assumées : couverture hétérogène, capabilities dépendantes du provider, besoin de pins et de qualification plateforme.

Disposition : **baseline provider multi-langages**, jamais contrat du domaine public.

### Tree-sitter

Forces : parsing structurel homogène, disponibilité large. Limites : résolution sémantique et identité cross-file insuffisantes sans enrichisseurs spécifiques.

Disposition : **provider potentiel**, utile lorsqu’aucun SCIP/compilateur fiable n’existe, mais non promu sans conformance et ground truth dédiés.

### Indexeurs et API natifs

Forces : meilleure fidélité sémantique potentielle. Limites : coupling outil/langage, installation et compatibilité plateforme.

Disposition : **adapter lorsque la preuve est supérieure**, comme l’API compilateur Java M22 ; capabilities strictement bornées.

## Comparaison des backends

### Backend local MINOS

Adopté comme baseline : reconstructible, local-first, testable, déployable sans service externe. Les benchmarks M16 et M21-S8 n’ont pas démontré de besoin de remplacement.

### Glean

Conservé comme frontière possible pour un fact store externe, sans dépendance obligatoire ni transfert des contrats du domaine.

### FalkorDB / graph store

Non retenu comme source de vérité. Un adapter futur pourrait matérialiser une projection si des mesures montrent un bénéfice, sans modifier l’autorité des snapshots.

### Architecture hybride

Retenue au niveau des **providers et projections**, pas sous forme de backends concurrents devenant chacun autoritatifs. MINOS compose et vérifie les contributions.

## Identité et provenance

Le modèle final privilégie :

- identité provider stable lorsqu’elle est prouvée ;
- fallback structurel explicitement qualifié ;
- `RESOLVED`, `AMBIGUOUS`, `UNRESOLVED` au lieu d’une résolution inventée ;
- localisation et encodage de position explicites ;
- `FACTUAL`, `DERIVED`, `HEURISTIC`, `OBSERVED_PARTIAL` distincts ;
- origine, version provider, snapshot/run et preuves transportées jusqu’aux surfaces publiques.

## Pipeline incrémental retenu

```text
workspace visible
  → fingerprints SHA-256 bornés
  → ChangeSet
  → plan NONE / INCREMENTAL / FULL
  → exécution provider
  → staging vérifié
  → promotion atomique
  → snapshots structurés + empreintes exactes
```

Une incohérence, une corruption, une suppression ambiguë ou une capability provider insuffisante provoque un fallback conservateur ou un refus explicite.

## Intelligence architecturale retenue

MINOS produit des facts et dérivations explicables : modules, namespaces, dépendances, concentration, centralité relative et chemins. Les algorithmes de communautés plus avancés restent différés jusqu’à disponibilité d’un corpus de vérité terrain et de métriques démontrant un gain utile.

## Fonctionnalités identifiées puis intégrées à la roadmap

Les fonctions absentes du cadrage initial ont été traitées progressivement :

- industrialisation multi-module et quality gates — M15/M21 ;
- scalabilité — M16 ;
- plateforme provider — M17 ;
- IntelliJ — M18 ;
- ProgramGraph/CFG/data-flow/sécurité — M19/M22 ;
- recherche sémantique/hybride learned locale — M20/M23 ;
- expansion polyglotte — M24 ;
- indexation distante/distribuée — M25 ;
- observations runtime partielles — M26 ;
- contrôle multi-tenant embarqué — M27 ;
- convergence et hardening — #95 puis M28/#93.

## Documents et décisions mis à jour

- cahier des charges historique : [`../history/c0/CAHIER_DES_CHARGES.md`](../history/c0/CAHIER_DES_CHARGES.md) ;
- roadmap courante : [`../ROADMAP.md`](../ROADMAP.md) ;
- état courant : [`../STATUS.md`](../STATUS.md) ;
- architecture et surfaces : [`../developer/public-surfaces.md`](../developer/public-surfaces.md) ;
- ADR : [`../adr/README.md`](../adr/README.md), notamment ADR-0025, ADR-0028, ADR-0029 et ADR-0030→0035 ;
- preuves d’exécution : [`../roadmap/`](../roadmap/).

## Spikes M0 et critère de sortie

Les spikes M0 ont qualifié SCIP Java/TypeScript, la normalisation SCIP→MINOS, le backend local léger, la frontière Glean et les contraintes d’adoption. Les jalons suivants ont ensuite rendu les hypothèses testables et reproductibles.

Le critère de sortie de l’issue #2 est donc satisfait : MINOS possède une architecture explicitement justifiée, indépendante technologiquement, matérialisée par des contrats et vérifiée par des jalons. Les écarts découverts après M27 ne rouvrent pas C0 ; ils sont suivis par le lot de remédiation #95 et M28/#93.
