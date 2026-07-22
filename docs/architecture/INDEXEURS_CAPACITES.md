# Indexeurs et capacités — MINOS

Statut : **Proposition C0 — à valider**

Ce document définit comment MINOS doit raisonner sur les fournisseurs d'indexation et d'analyse sans coder en dur une liste de langages ou de technologies.

---

# 1. Objectif

MINOS doit pouvoir répondre à deux questions distinctes :

1. **Quel fournisseur sait analyser ce projet ou ce langage ?**
2. **Quelles capacités ce fournisseur peut-il réellement fournir avec un niveau de qualité acceptable ?**

La sélection d'un fournisseur ne doit jamais être réduite à :

```text
Java -> scip-java
Python -> fournisseur X
TypeScript -> fournisseur Y
```

Le choix doit être fondé sur les capacités, les contraintes du projet et la qualité mesurée.

---

# 2. Concepts principaux

## 2.1 `IndexerProvider`

Représente un fournisseur capable de produire ou exposer des faits de Code Intelligence.

Contrat conceptuel :

```text
IndexerProvider
- id
- name
- version
- providerType
- supportedLanguages
- supportedBuildSystems
- capabilities
- executionMode
- localOnly
- requiresBuild
- requiresDependencies
- priority?
```

Le fournisseur peut être :

```text
SCIP_INDEXER
GLEAN_NATIVE_INDEXER
LSP_PROVIDER
COMPILER_PROVIDER
AST_PROVIDER
CPG_PROVIDER
CUSTOM_PROVIDER
```

## 2.2 `IndexerCapabilities`

Le fournisseur annonce explicitement ce qu'il sait fournir.

Capacités candidates :

```text
PROJECT_STRUCTURE
FILE_DISCOVERY
DEFINITIONS
REFERENCES
IMPLEMENTATIONS
INHERITANCE
TYPE_RELATIONSHIPS
IMPORT_RELATIONSHIPS
CALL_RELATIONSHIPS
READ_WRITE_RELATIONSHIPS
INSTANTIATIONS
CROSS_FILE
CROSS_MODULE
CROSS_REPOSITORY
SOURCE_LOCATIONS
SIGNATURES
VISIBILITY
MODIFIERS
EXTERNAL_SYMBOLS
GENERATED_SYMBOLS
CONTROL_FLOW
DATA_FLOW
INCREMENTAL_INDEXING
```

Cette liste doit rester extensible.

---

# 3. Niveau de support d'une capacité

Une simple valeur booléenne est insuffisante.

Proposition :

```text
CapabilitySupport
- capability
- supportLevel
- confidence
- limitations
- measuredAt?
- benchmarkReference?
```

`supportLevel` :

```text
FULL
PARTIAL
EXPERIMENTAL
UNSUPPORTED
UNKNOWN
```

Exemple :

```text
CALL_RELATIONSHIPS
supportLevel = PARTIAL
limitations = "dispatch dynamique incomplet"
```

MINOS ne doit pas présenter une capacité `PARTIAL` comme complète.

---

# 4. Qualité d'un fournisseur

Un fournisseur peut techniquement supporter une capacité tout en étant insuffisamment précis pour MINOS.

MINOS doit donc distinguer :

```text
capacité déclarée
≠
capacité validée
```

Mesures possibles :

```text
ProviderQualityProfile
- indexingSuccessRate
- symbolPrecision
- referencePrecision
- implementationPrecision
- callPrecision
- unresolvedRate
- indexingLatency
- indexSize
- memoryUsage
- offlineSupport
- operationalComplexity
```

Les profils de qualité devront être établis par écosystème et type de projet lorsque les résultats diffèrent fortement.

---

# 5. Détection d'applicabilité

Un fournisseur doit pouvoir indiquer s'il est applicable à un projet donné.

Entrée conceptuelle :

```text
ProjectDescriptor
- languages
- buildSystems
- modules
- sourceRoots
- testRoots
- detectedTechnologies
- platform
```

Résultat :

```text
ProviderApplicability
- applicable
- reasons
- prerequisites
- missingPrerequisites
```

Exemples :

```text
applicable = true
prerequisite = Maven disponible
```

ou :

```text
applicable = false
reason = version du langage non supportée
```

---

# 6. Sélection des fournisseurs

La sélection doit être une décision MINOS explicable.

Entrées :

- projet ;
- capacité demandée ;
- contraintes locales ;
- fournisseurs disponibles ;
- qualité mesurée ;
- préférences utilisateur éventuelles.

Sortie conceptuelle :

```text
ProviderSelection
- selectedProvider
- requestedCapability
- alternatives
- reasons
- limitations
```

Exemple :

```text
Requête : find_callers

Provider A
- CALL_RELATIONSHIPS = PARTIAL
- précision mesurée = 91 %

Provider B
- CALL_RELATIONSHIPS = FULL
- précision mesurée = 98 %

=> Provider B sélectionné
```

---

# 7. Plusieurs fournisseurs pour un même projet

MINOS doit pouvoir combiner plusieurs fournisseurs.

Exemple conceptuel :

```text
SCIP provider
    ├── définitions
    ├── références
    └── implémentations

CPG provider
    ├── call graph avancé
    └── data flow

MINOS
    └── normalise et expose les résultats
```

Le cœur doit donc éviter l'hypothèse :

```text
un projet = un indexeur
```

La réalité cible est plutôt :

```text
un projet = un ou plusieurs fournisseurs complémentaires
```

---

# 8. Priorité de SCIP

Si l'ADR-0002 est acceptée, SCIP deviendra le protocole privilégié lorsque :

- un indexeur existe ;
- il est maintenu ;
- sa licence est compatible ;
- il fonctionne localement ;
- ses capacités requises sont suffisantes ;
- sa précision mesurée atteint les seuils MINOS.

SCIP ne doit pas être choisi uniquement parce qu'un indexeur SCIP existe.

---

# 9. Fournisseurs non-SCIP

Un fournisseur non-SCIP doit pouvoir être préféré lorsqu'il :

- fournit une capacité absente de l'indexeur SCIP ;
- apporte une meilleure précision ;
- supporte mieux un framework ou système de build ;
- fonctionne dans un contexte où SCIP échoue ;
- apporte des analyses spécialisées comme contrôle de flux ou flux de données.

Tous les fournisseurs passent par une normalisation MINOS.

---

# 10. Gestion des prérequis

MINOS devra distinguer les dépendances nécessaires au fournisseur :

```text
NONE
BUILD_TOOL
COMPILER
LANGUAGE_RUNTIME
DEPENDENCY_RESOLUTION
EXTERNAL_BINARY
SIDECAR_PROCESS
```

Un fournisseur doit pouvoir expliquer pourquoi il ne peut pas démarrer.

Exemples :

- Maven absent ;
- dépendances non résolues ;
- compilateur non installé ;
- version du runtime incompatible ;
- binaire d'indexeur absent.

---

# 11. Mode dégradé

MINOS doit pouvoir fonctionner avec une qualité partielle plutôt que d'échouer systématiquement, à condition de rendre cette dégradation explicite.

Exemple :

```text
Definitions        FULL
References         PARTIAL
Implementations    UNKNOWN
Calls              UNSUPPORTED
```

Les requêtes dépendantes d'une capacité absente doivent :

- retourner une information explicite ;
- ne pas inventer de résultat ;
- éventuellement proposer un autre fournisseur disponible.

---

# 12. Observabilité

Chaque exécution d'un fournisseur devra à terme permettre de mesurer :

- version ;
- durée ;
- statut ;
- nombre de fichiers analysés ;
- nombre de symboles ;
- nombre de relations ;
- avertissements ;
- erreurs ;
- références non résolues ;
- mémoire ;
- taille produite ;
- paramètres d'exécution.

Ces données alimenteront les benchmarks et la décision de sélection.

---

# 13. Contrats conceptuels candidats

```text
IndexerProvider
IndexerRegistry
IndexerCapabilities
CapabilitySupport
ProjectDescriptor
ProviderApplicability
IndexingRequest
IndexingResult
ProviderSelection
ProviderQualityProfile
```

Ces noms restent conceptuels pendant C0.

---

# 14. Rôle de `IndexerRegistry`

Le registre doit :

- connaître les fournisseurs disponibles ;
- exposer leurs versions ;
- filtrer selon le projet ;
- filtrer selon la capacité ;
- prendre en compte leur qualité ;
- fournir les candidats à l'orchestrateur ;
- permettre plusieurs fournisseurs pour un même projet.

Le registre ne doit pas contenir de logique métier spécifique à Java, Python, TypeScript ou tout autre langage.

---

# 15. Questions ouvertes

1. Les profils de qualité doivent-ils être statiques, mesurés automatiquement ou les deux ?
2. Comment arbitrer entre précision et coût d'indexation ?
3. Un fournisseur doit-il pouvoir être forcé par configuration utilisateur ?
4. Comment fusionner deux fournisseurs qui donnent des résultats contradictoires ?
5. Comment représenter la priorité entre un fait direct et une dérivation MINOS ?
6. Doit-on sélectionner un fournisseur par capacité ou constituer un plan d'analyse complet par projet ?
7. Comment gérer les fournisseurs qui n'offrent qu'une CLI ?
8. Comment versionner les capacités lorsque leur sémantique évolue ?

---

# 16. Critères de validation C0

Le modèle de capacités sera suffisamment défini pour M0 lorsque :

- un fournisseur SCIP et un fournisseur non-SCIP peuvent être représentés ;
- un même projet peut utiliser plusieurs fournisseurs ;
- les capacités partielles sont représentables ;
- les prérequis et modes dégradés sont représentables ;
- le choix d'un fournisseur peut être expliqué ;
- aucun langage n'est codé en dur dans le modèle ;
- les benchmarks M0 peuvent produire un `ProviderQualityProfile` exploitable.