# ADR-0026 — Discovery/provider SPI et profils de capacités explicites

- Statut : **Accepted**
- Origine : **M17 — Provider & Discovery Platform**

## Contexte

Après M16, le cœur MINOS reste provider-independent, mais deux catalogues d'écosystème sont encore codés dans des classes centrales : la découverte connaît les marqueurs Maven/npm et les racines Java/TypeScript, tandis que le catalogue SCIP construit directement les providers historiques. Ajouter Gradle, Kotlin, Python ou un nouveau workspace risquerait donc d'accumuler des branches spécifiques.

En parallèle, le simple ensemble `IndexerCapability` ne distingue pas une capacité pleinement qualifiée d'une capacité partielle, expérimentale ou explicitement absente.

## Décision

### 1. Discovery par extensions composables

`ProjectDiscoveryService` orchestre quatre SPI :

- `ProjectDetector` ;
- `BuildSystemDetector` ;
- `SourceRootDetector` ;
- `LanguageDetector`.

Les conventions intégrées vivent dans `DefaultDiscoveryPlugins`. L'orchestrateur central ne contient aucun nom de fichier, langage ou build system spécifique.

### 2. Provider SPI

Un provider est un `IndexerProvider` qui expose :

- son `IndexerDescriptor` négociable ;
- son `ProviderCapabilityProfile` exhaustif.

`IndexerProviderRegistry` est le catalogue d'extensions. Le `IndexerRegistry` historique reste le moteur neutre de négociation dérivé des descriptors.

### 3. Capability model v2

Chaque valeur de `IndexerCapability` reçoit obligatoirement un niveau :

```text
FULL
PARTIAL
EXPERIMENTAL
UNSUPPORTED
```

Une entrée absente est invalide. Une capacité ne peut donc jamais être déduite implicitement de la présence d'un provider ou de sa capacité à démarrer.

### 4. Runtime d'installation composé

Les installations restent derrière `ProviderRuntimeManager`. `CompositeProviderRuntimeManager` route les opérations vers des extensions autonomes et refuse les identifiants dupliqués. Les commandes CLI/doctor/index n'ajoutent pas de `switch` par provider.

### 5. Limites exposées

Les profils, niveaux, limitations et diagnostics runtime sont disponibles par un service applicatif commun puis exposés :

- CLI : `minos providers` ;
- Java : `ProviderPlatformApi`, séparée de `MinosApi` v1 ;
- MCP : enrichissement read-only des diagnostics projet/index existants.

Le catalogue MCP historique n'est pas élargi uniquement pour transporter ces diagnostics.

## Conséquences

- un nouveau langage/build system se branche par SPI ;
- un nouveau provider n'impose aucune modification du domaine ;
- les capacités non prouvées restent visibles comme `PARTIAL`, `EXPERIMENTAL` ou `UNSUPPORTED` ;
- Gradle peut être correctement découvert avant qu'un runtime Gradle soit qualifié ;
- les providers Java/TypeScript historiques restent négociés par le même moteur ;
- les runtimes supplémentaires peuvent être installés sous `MINOS_HOME/tools` sans installation globale.

## Limites

- un SPI ne garantit pas qu'un provider tiers est sûr ou correct : le conformance kit et une qualification exact-head restent obligatoires ;
- la découverte de workspace repose sur des conventions observables, pas sur l'exécution arbitraire des builds ;
- une capacité fournisseur upstream n'est pas automatiquement une capacité MINOS qualifiée ;
- les profils doivent être réévalués lorsqu'une version provider change.

## Preuve

Les preuves de M17 sont enregistrées dans la PR et l'issue M17. La gate de livraison est `scripts/m17/run-final.ps1` et exige le verdict :

```text
M17 FINAL PROVIDER PLATFORM VALIDATION SUCCESS
```
