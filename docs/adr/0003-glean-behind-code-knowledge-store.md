# ADR-0003 — Isoler le backend de connaissance et traiter Glean comme backend avancé optionnel

- Statut : **Acceptée — orientation confirmée par M0 C1/E2**
- Date de décision : **22 juillet 2026**
- Validation de Glean : **M0**

## Contexte

Glean fournit un système spécialisé pour collecter, stocker, dériver et interroger des faits typés sur le code source. Il possède un moteur de stockage fondé sur RocksDB, des schémas typés, le langage de requête Angle, des prédicats dérivés et des mécanismes permettant d'ajouter des faits spécifiques.

Ces capacités sont très proches de plusieurs besoins futurs de MINOS et justifient une expérimentation sérieuse.

Cependant, l'étude C0 met en évidence des contraintes opérationnelles importantes pour MINOS :

- la documentation officielle indique que le build Glean est actuellement testé uniquement sous Linux ;
- l'image Docker documentée est actuellement signalée comme non fonctionnelle ;
- cette image de démonstration est de l'ordre de plusieurs gigaoctets ;
- l'API cliente officiellement documentée est actuellement Haskell ;
- les autres intégrations passent par l'API Thrift ou par la CLI ;
- l'intégration Java nécessiterait donc une couche d'adaptation spécifique ;
- la documentation Java de Glean présente encore un chemin LSIF, tandis que d'autres parties de la documentation mettent en avant SCIP, ce qui devra être clarifié expérimentalement.

MINOS étant local-first et devant prendre en compte Windows, Linux et macOS, Glean ne peut pas être déclaré backend obligatoire du MVP sans validation opérationnelle.

## Décision

### 1. `CodeKnowledgeStore` devient une frontière architecturale MINOS

MINOS possède une abstraction de stockage et d'interrogation de la connaissance du code, nommée conceptuellement :

```text
CodeKnowledgeStore
```

Cette abstraction est définie à partir des cas d'usage MINOS et non à partir d'une API particulière.

```text
Domaine / Services MINOS
          │
          ▼
  CodeKnowledgeStore
          │
     ┌────┼───────────────┐
     ▼    ▼               ▼
  Léger  Glean        Backend futur
```

Aucun type Glean, prédicat Angle, identifiant de fait ou type Thrift ne doit traverser cette frontière.

### 2. Glean n'est pas obligatoire pour le MVP

L'orientation initiale :

> Glean-first, not Glean-locked

est **révisée**.

La nouvelle formulation est :

> **MINOS-first, Glean-optional.**

Glean devient un **backend avancé candidat** à évaluer pendant M0, et non le backend obligatoire ou le backend par défaut du MVP.

### 3. M0 doit comparer deux chemins réels

#### Chemin A — Baseline MINOS légère

```text
Repository
    │
    ▼
SCIP / fournisseur
    │
    ▼
Adaptateur MINOS
    │
    ▼
Modèle normalisé
    │
    ▼
Backend léger MINOS
```

Ce chemin doit démontrer que MINOS peut fournir au minimum `find_symbol` et `find_usages` sans Glean.

Le backend léger peut être en mémoire pour les tests et utiliser un stockage embarqué minimal pour le spike si nécessaire. Son objectif M0 n'est pas de remplacer toutes les capacités de Glean.

#### Chemin B — Glean

```text
Repository
    │
    ▼
SCIP / indexeur
    │
    ▼
Glean
    │
    ▼
GleanCodeKnowledgeStore
    │
    ▼
Services MINOS
```

Ce chemin mesure la valeur ajoutée réelle de Glean et son coût opérationnel.

## Pourquoi conserver Glean dans l'évaluation

Glean reste particulièrement intéressant pour :

- stockage de faits typés ;
- déduplication ;
- requêtes relationnelles complexes ;
- prédicats dérivés ;
- requêtes de graphe et parcours transitifs ;
- schémas personnalisés MINOS ;
- code navigation à grande échelle ;
- futurs besoins de multi-dépôts ou monorepos massifs.

Il peut donc devenir plus tard :

- backend avancé optionnel ;
- backend pour gros dépôts ;
- backend de serveur Linux ;
- moteur de requêtes spécialisé derrière `CodeKnowledgeStore`.

## Pourquoi ne pas l'imposer maintenant

L'imposer dès le MVP introduirait prématurément :

- une forte dépendance opérationnelle à Linux ;
- Haskell et l'écosystème de build Glean ;
- RocksDB ;
- Thrift ou orchestration CLI ;
- gestion d'un processus externe ;
- packaging et mises à jour supplémentaires ;
- une difficulté de distribution Windows non encore résolue.

Ces coûts doivent être justifiés par des mesures, pas supposés acceptables.

## Backend léger de contrôle

MINOS doit disposer au minimum :

- d'un `InMemoryCodeKnowledgeStore` pour les tests ;
- d'un chemin expérimental léger permettant de comparer Glean pendant M0.

Le choix du stockage embarqué éventuel n'est pas encore figé.

SQLite reste un candidat naturel pour un spike ou les métadonnées, mais cette ADR ne décide pas qu'il sera le backend de production.

Le convertisseur SQLite expérimental du CLI SCIP peut être utilisé pour inspection ou comparaison, mais son statut expérimental interdit d'en faire une dépendance de production sans étude supplémentaire.

## Critères d'adoption de Glean après M0

Glean ne pourra devenir backend recommandé que si l'expérience démontre :

1. une valeur fonctionnelle mesurable supérieure au chemin léger ;
2. une installation et une mise à jour automatisables ;
3. un mode d'exécution acceptable sur l'environnement développeur cible ;
4. une solution crédible pour Windows, directement ou via une abstraction transparente ;
5. une communication MINOS ↔ Glean maintenable ;
6. des temps de démarrage et d'indexation acceptables ;
7. une empreinte mémoire et disque acceptable ;
8. une reconstruction fiable des bases ;
9. des bénéfices réels pour les requêtes complexes ;
10. aucune fuite des concepts Glean dans le domaine MINOS.

## Scénarios de décision après M0

### ADOPTER

Glean apporte une valeur significative et son exploitation est suffisamment transparente.

### ADOPTER_AVEC_CONTRAINTES

Glean devient un backend avancé réservé à certains environnements, tailles de projets ou modes serveur.

### REVOIR

MINOS conserve `CodeKnowledgeStore` mais choisit un autre backend principal.

### REMPLACER

Glean est retiré de la trajectoire principale ; le domaine MINOS reste inchangé.

## Validation M0 du 22 juillet 2026

C1 a construit et exécuté Glean 0.2.0.1 sous Ubuntu WSL2. L'ingestion directe
de l'index produit par `scip-java 0.13.1` échoue : l'indexeur Glean intégré lit
les anciens tableaux de positions et ignore les plages SCIP typées modernes.
Une copie de compatibilité a permis d'ingérer et d'interroger `java-simple`.

Les résultats sont fonctionnels mais ne justifient pas Glean comme backend par
défaut :

- 13/13 symboles présents, contre 9/13 kinds exacts ;
- 5/5 cibles d'usage et l'implémentation attendue présentes ;
- aucune relation `CALLS` explicite et aucune sémantique de non-résolution
  équivalente au domaine MINOS démontrée ;
- environ 4,52 GB de store Cabal local après construction ;
- 155 376 KiB de pic RSS sur une requête instrumentée ;
- environ 0,8 s au p95 pour une invocation CLI complète sous WSL2 ;
- conversion préalable requise pour les 128 occurrences de la fixture.

E2 retient donc le scénario **ADOPTER_AVEC_CONTRAINTES** au sens suivant :

- adopter et préserver la frontière `CodeKnowledgeStore` ;
- retenir un backend MINOS léger pour le chemin par défaut du MVP ;
- conserver Glean comme backend avancé optionnel possible ;
- différer C2 Thrift et C3 sidecar jusqu'à un besoin mesuré que le chemin léger
  ne satisfait pas.

Cette décision ne choisit pas encore le stockage persistant MINOS. Elle exclut
seulement Glean du chemin par défaut actuel. Les mesures complètes sont dans
`docs/m0/RAPPORT_GLEAN_C1.md` et `docs/m0/COMPARATIF_BACKENDS.md`.

## Alternatives surveillées

- backend léger MINOS sur stockage embarqué ;
- Kythe pour certains besoins de graphes et cross-références ;
- Joern comme moteur spécialisé CPG / data-flow, sans en faire le backend général ;
- moteur spécialisé futur derrière un adaptateur ;
- combinaison de plusieurs moteurs selon les capacités.

## Sources techniques vérifiées

Sources consultées le 22 juillet 2026 :

- Introduction Glean : https://glean.software/docs/introduction/
- Construction de Glean : https://glean.software/docs/building/
- Docker Glean : https://glean.software/docs/docker/
- Requêtes et API : https://glean.software/docs/query/intro/
- CLI Glean : https://glean.software/docs/cli/
- Indexation Python/SCIP : https://glean.software/docs/indexer/scip-python/
- Indexation Java : https://glean.software/docs/indexer/lsif-java/
