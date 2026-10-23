---
id: ADR-0026
title: "Discovery/provider SPI et profils de capacités explicites"
domain: backend
rules: false
---

# ADR-0026 — Discovery/provider SPI et profils de capacités explicites

- Statut : **Accepted**
- Origine : **M17 — Provider & Discovery Platform**

## Context

Après M16, le cœur MINOS reste provider-independent, mais deux catalogues d'écosystème sont encore codés dans des classes centrales : la découverte connaît les marqueurs Maven/npm et les racines Java/TypeScript, tandis que le catalogue SCIP construit directement les providers historiques. Ajouter Gradle, Kotlin, Python ou un nouveau workspace risquerait donc d'accumuler des branches spécifiques.

En parallèle, le simple ensemble `IndexerCapability` ne distingue pas une capacité pleinement qualifiée d'une capacité partielle, expérimentale ou explicitement absente.

## Decision

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

## Do's and Don'ts

### Do

- Brancher la découverte par les SPI `ProjectDetector`, `BuildSystemDetector`, `SourceRootDetector` et `LanguageDetector`, orchestrés par `ProjectDiscoveryService`.
- Placer les conventions intégrées dans `DefaultDiscoveryPlugins`.
- Attribuer obligatoirement un niveau `FULL`, `PARTIAL`, `EXPERIMENTAL` ou `UNSUPPORTED` à chaque valeur de `IndexerCapability`.
- Router les installations par `CompositeProviderRuntimeManager`, qui refuse les identifiants dupliqués.

### Don't

- Ne pas mettre de nom de fichier, de langage ou de build system dans l’orchestrateur central.
- Ne pas déduire une capacité de la présence d’un provider ou de sa capacité à démarrer.
- Ne pas ajouter de `switch` par provider dans les commandes CLI/doctor/index.
- Ne pas élargir le catalogue MCP uniquement pour transporter les diagnostics.

## Consequences

- un nouveau langage/build system se branche par SPI ;
- un nouveau provider n'impose aucune modification du domaine ;
- les capacités non prouvées restent visibles comme `PARTIAL`, `EXPERIMENTAL` ou `UNSUPPORTED` ;
- Gradle peut être correctement découvert avant qu'un runtime Gradle soit qualifié ;
- les providers Java/TypeScript historiques restent négociés par le même moteur ;
- les runtimes supplémentaires peuvent être installés sous `MINOS_HOME/tools` sans installation globale.

## Compliance and Enforcement

- Test `ProviderConformanceKitTest` (`minos-engine`, lancé par `mvnw verify`) — refus de toute capacité implicite, profil exhaustif et déterministe, refus des descriptors dupliqués.
- Tests `CompositeProviderRuntimeManagerTest` (`minos-runtime-local`) — routage sans branche centrale par provider, refus des extensions dupliquées — et `ProjectDiscoveryServiceTest` (`minos-engine`) — un plugin de découverte nouveau ne modifie pas l’orchestrateur central.
- Le harnais de jalon `scripts/history/m17/run-final.ps1` est une archive : aucun workflow ne le lance.
- Revue : un langage, un build system ou un provider nouveau se branche par SPI et déclare un profil de capacités exhaustif.

## Limites

- un SPI ne garantit pas qu'un provider tiers est sûr ou correct : le conformance kit et une qualification exact-head restent obligatoires ;
- la découverte de workspace repose sur des conventions observables, pas sur l'exécution arbitraire des builds ;
- une capacité fournisseur upstream n'est pas automatiquement une capacité MINOS qualifiée ;
- les profils doivent être réévalués lorsqu'une version provider change.

## Preuve

Les preuves de M17 sont enregistrées dans la PR et l'issue M17. La gate de livraison est `scripts/history/m17/run-final.ps1` et exige le verdict :

```text
M17 FINAL PROVIDER PLATFORM VALIDATION SUCCESS
```

## Suivi (2026-10-10, audit AUD-ARC-06)

Les quatre interfaces SPI de découverte (`ProjectDetector`, `BuildSystemDetector`, `SourceRootDetector`, `LanguageDetector`) résidaient dans le package `com.minos.discovery.spi`, qui importait le modèle de `com.minos.discovery` tandis que `ProjectDiscoveryService` importait l'SPI : un cycle de packages. Elles vivent désormais dans `com.minos.discovery`, avec le modèle et le service ; le package `spi` n'existe plus. La décision de cet ADR (découverte par extensions composables, conventions intégrées dans `DefaultDiscoveryPlugins`) est inchangée ; aucun implémenteur ne vit hors de `minos-engine`.

## Mise en œuvre (2026-10-21, audit AUD-QUA-02)

`IndexerProviderRegistry`, présenté au § 2 comme catalogue d'extensions, n'avait aucun appelant de production : aucune extension tierce de provider n'est supportée, et le produit négocie par `IndexerRegistry` à partir des descriptors. La classe est supprimée (changement `retirer-les-services-sans-appelant-de-production`) ; ses tests sont réécrits sur `IndexerRegistry`. Le contrôle propre au catalogue (identifiant du descriptor égal à celui du profil) disparaît avec lui ; `ProviderCapabilityProfile` reste exhaustif et `ProviderConformanceKit` inchangé. Le reste de la décision (SPI de découverte, `IndexerProvider`, modèle de capacités v2) est inchangé ; un catalogue d'extensions reviendrait par une nouvelle décision.

## Écarts constatés

Relevés le 2026-10-23 (audit des ADR) ; la décision ci-dessus reste celle en vigueur tant qu'un amendement ou une correction du code n'a pas tranché.

- `IndexerProviderRegistry`, présenté au § 2 comme catalogue d’extensions, n’existe pas dans le code ; la négociation passe par `IndexerRegistry` (voir « Mise en œuvre (2026-10-21, audit AUD-QUA-02) ») — preuve : `git grep "class IndexerProviderRegistry"` sans résultat ; `minos-engine/src/main/java/com/minos/orchestration/IndexerRegistry.java:23`.
