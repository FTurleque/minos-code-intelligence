# Indexeurs et capacités — MINOS

- Statut : **Validé pour M0**
- Date de validation : **22 juillet 2026**

Ce document définit comment MINOS sélectionne et combine les fournisseurs d'indexation et d'analyse sans coder en dur une liste de langages ou de technologies.

---

# 1. Objectif

MINOS doit répondre à trois questions distinctes :

1. **Quel fournisseur est applicable à ce projet ?**
2. **Quelles capacités fournit-il réellement ?**
3. **Avec quelle qualité mesurée fournit-il ces capacités ?**

La sélection ne doit jamais se réduire à :

```text
Java -> scip-java
Python -> fournisseur X
TypeScript -> fournisseur Y
```

---

# 2. IndexerProvider

Représente un fournisseur capable de produire ou exposer des faits de Code Intelligence.

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
- prerequisites
- declaredPriority?
- metadata
```

Types initiaux :

```text
SCIP_INDEXER
GLEAN_NATIVE_INDEXER
LSP_PROVIDER
COMPILER_PROVIDER
AST_PROVIDER
CPG_PROVIDER
CUSTOM_PROVIDER
```

`GLEAN_NATIVE_INDEXER` désigne ici un **indexeur** produisant des faits Glean ; le backend de stockage Glean appartient à la frontière `CodeKnowledgeStore` et non au registre des indexeurs.

---

# 3. Capacités

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
TAINT_ANALYSIS
INCREMENTAL_INDEXING
```

La liste reste extensible.

---

# 4. CapabilitySupport

Une capacité n'est pas booléenne.

```text
CapabilitySupport
- capability
- supportLevel
- declaredConfidence?
- limitations
- measuredAt?
- benchmarkReference?
```

Niveaux :

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

MINOS ne présente jamais `PARTIAL` comme `FULL`.

---

# 5. Capacité déclarée ≠ capacité validée

Le fournisseur peut annoncer une capacité ; MINOS conserve séparément le résultat de sa qualification.

```text
ProviderQualityProfile
- providerId
- providerVersion
- ecosystem
- buildSystem?
- fixtureVersion
- validatedAt
- supportedEnvironment
- capabilityResults
- indexingSuccessRate
- symbolPrecision
- symbolRecall
- referencePrecision
- referenceRecall
- implementationPrecision
- implementationRecall
- callPrecision?
- callRecall?
- unresolvedRate
- indexingLatency
- queryLatency
- indexSize
- memoryUsage
- offlineSupport
- operationalComplexity
- knownLimitations
```

Les métriques suivent `docs/METRIQUES_VALIDATION.md`.

Un changement majeur du fournisseur ou de son frontend peut exiger une requalification.

---

# 6. ProjectDescriptor

Entrée normalisée utilisée pour déterminer l'applicabilité :

```text
ProjectDescriptor
- projectId
- languages
- buildSystems
- modules
- sourceRoots
- testRoots
- detectedTechnologies
- platform
- repositoryState
```

`repositoryState` peut notamment signaler :

```text
BUILDABLE
PARTIALLY_BUILDABLE
DEPENDENCIES_MISSING
UNKNOWN
```

---

# 7. ProviderApplicability

```text
ProviderApplicability
- providerId
- applicable
- reasons
- prerequisites
- missingPrerequisites
- expectedDegradedMode?
```

Exemple :

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

# 8. Prérequis

Types initiaux :

```text
NONE
BUILD_TOOL
COMPILER
LANGUAGE_RUNTIME
DEPENDENCY_RESOLUTION
EXTERNAL_BINARY
SIDECAR_PROCESS
OPERATING_SYSTEM
CONTAINER_RUNTIME
```

Un fournisseur doit expliquer les prérequis manquants.

---

# 9. ProviderSelection

La sélection est explicable et fondée sur :

- projet ;
- capacité demandée ;
- support déclaré ;
- qualité mesurée ;
- contraintes de plateforme ;
- disponibilité locale ;
- coût ;
- préférence utilisateur éventuelle.

```text
ProviderSelection
- selectedProvider
- requestedCapability
- alternatives
- reasons
- limitations
- overrideApplied
```

Exemple :

```text
find_callers

Provider A
CALL_RELATIONSHIPS = PARTIAL
precision = 91 %

Provider B
CALL_RELATIONSHIPS = FULL
precision = 98 %

=> Provider B
```

---

# 10. Priorité de décision

Ordre général :

```text
1. capacité disponible
2. seuil de qualité atteint
3. compatibilité environnement
4. précision
5. complétude
6. coût d'indexation / requête
7. préférence utilisateur
```

La précision prime sur le coût tant que le coût reste dans les limites opérationnelles acceptables.

Un fournisseur plus rapide ne doit pas être choisi s'il produit des relations fausses dépassant les seuils MINOS.

---

# 11. SCIP comme chemin privilégié

ADR-0002 étant acceptée, un fournisseur SCIP est privilégié lorsque :

- un indexeur existe ;
- il est maintenu ;
- sa licence est compatible ;
- il fonctionne localement ;
- les capacités demandées sont disponibles ;
- son profil mesuré atteint les seuils ;
- aucune alternative ne fournit une qualité significativement meilleure pour la capacité demandée.

L'existence d'un indexeur SCIP n'est jamais suffisante à elle seule.

---

# 12. Fournisseurs non-SCIP

Un fournisseur non-SCIP peut être préféré lorsqu'il :

- fournit une capacité absente ;
- améliore la précision ;
- supporte mieux un framework ou build ;
- fonctionne lorsque l'indexeur SCIP échoue ;
- apporte contrôle de flux, data-flow, taint ou sécurité ;
- possède une meilleure intégration pour un écosystème particulier.

Tous les résultats passent par la normalisation MINOS.

---

# 13. Plusieurs fournisseurs par projet

La cible est :

```text
un projet = un plan d'analyse utilisant un ou plusieurs fournisseurs
```

Exemple :

```text
SCIP Provider
    ├── DEFINITIONS
    ├── REFERENCES
    └── IMPLEMENTATIONS

Joern / CPG Provider
    ├── CONTROL_FLOW
    ├── DATA_FLOW
    └── TAINT_ANALYSIS

MINOS
    └── normalise et expose
```

---

# 14. AnalysisPlan

Pour éviter de sélectionner isolément un fournisseur à chaque appel, MINOS peut construire un plan d'analyse par projet.

```text
AnalysisPlan
- projectId
- providers
- capabilityAssignments
- prerequisites
- fallbackAssignments
- generatedAt
- reasons
```

Exemple :

```text
DEFINITIONS      -> scip-java
REFERENCES       -> scip-java
IMPLEMENTATIONS  -> scip-java
DATA_FLOW        -> joern
```

Le plan reste recalculable lorsque les fournisseurs ou leurs profils changent.

---

# 15. Conflits entre fournisseurs

Deux fournisseurs peuvent produire des informations incompatibles.

MINOS ne doit pas fusionner silencieusement des faits contradictoires.

Règles initiales :

1. conserver la provenance de chaque fait ;
2. préférer un fait `FACTUAL` validé à une dérivation ;
3. préférer une dérivation à une heuristique lorsque la sémantique est comparable ;
4. utiliser le profil de qualité pour départager deux faits du même niveau ;
5. exposer le conflit si aucune règle ne permet une décision fiable ;
6. ne jamais transformer le conflit en certitude artificielle.

Un résultat peut donc porter :

```text
CONFLICTING_EVIDENCE
```

comme diagnostic, sans nécessairement devenir un nouveau `ResolutionStatus` du domaine M0.

---

# 16. Override utilisateur

Un utilisateur peut forcer un fournisseur par configuration pour :

- diagnostic ;
- comparaison ;
- contraintes locales ;
- reproductibilité.

L'override doit être visible dans le résultat de sélection.

MINOS doit refuser un override impossible à exécuter ou annoncer explicitement le mode dégradé.

---

# 17. Fournisseurs CLI

Un fournisseur n'a pas besoin d'une bibliothèque embarquée.

```text
ProcessIndexerAdapter
```

peut encapsuler :

- ligne de commande ;
- environnement ;
- répertoire de travail ;
- stdout/stderr ;
- timeout ;
- code de retour ;
- fichiers produits ;
- nettoyage.

Cette approche convient notamment aux indexeurs SCIP externes.

Le domaine ne dépend pas du mécanisme d'exécution.

---

# 18. Mode dégradé

Exemple :

```text
Definitions        FULL
References         PARTIAL
Implementations    UNKNOWN
Calls              UNSUPPORTED
```

Une requête dépendant d'une capacité absente doit :

- signaler l'absence ;
- ne rien inventer ;
- proposer un fournisseur alternatif lorsque disponible ;
- expliquer les limitations.

---

# 19. Observabilité

Chaque exécution doit pouvoir produire :

```text
providerId
providerVersion
startTime
endTime
duration
status
filesAnalyzed
symbolsProduced
occurrencesProduced
relationshipsProduced
warnings
errors
unresolvedCount
peakMemory
outputSize
executionParameters
```

Ces informations alimentent `ProviderQualityProfile`.

---

# 20. Contrats conceptuels validés pour M0

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
AnalysisPlan
```

Les noms pourront être ajustés pendant l'implémentation sans modifier leur responsabilité.

---

# 21. Rôle de IndexerRegistry

Le registre :

- connaît les fournisseurs installés ;
- expose leurs versions ;
- filtre selon le projet ;
- filtre selon les capacités ;
- fournit les profils de qualité ;
- prépare les candidats pour l'orchestrateur ;
- permet plusieurs fournisseurs par projet.

Il ne contient aucune logique métier spécifique à Java, TypeScript, Python ou un autre langage.

---

# 22. Décisions C0 prises

1. Les profils combinent données déclarées et mesures ; **les mesures MINOS font foi** pour la qualification.
2. La précision prime sur le coût dans les limites opérationnelles définies.
3. Un utilisateur peut forcer un fournisseur avec override explicite.
4. Les conflits ne sont jamais fusionnés silencieusement.
5. Les faits directs priment sur dérivations et heuristiques lorsque leur sémantique est comparable.
6. La sélection est réalisée par capacité puis agrégée en `AnalysisPlan`.
7. Les fournisseurs CLI sont des fournisseurs de première classe derrière un adaptateur de processus.
8. Les capacités et profils sont versionnés par la version du fournisseur et la version des fixtures.

---

# 23. Validation M0

Le modèle est considéré prêt si M0 démontre :

- représentation de `scip-java` ;
- représentation de `scip-typescript` ;
- représentation d'un fournisseur spécialisé non-SCIP conceptuel ;
- capacités partielles ;
- prérequis ;
- mode dégradé ;
- production d'un `ProviderQualityProfile` ;
- sélection explicable ;
- même domaine quel que soit le fournisseur.
