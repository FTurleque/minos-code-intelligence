---
id: ADR-0064
title: "Cœur agnostique du langage et de l'indexeur, SCIP protocole privilégié"
domain: architecture
rules: false
---

# ADR-0064 — Cœur agnostique du langage et de l'indexeur, SCIP protocole privilégié

- **Statut** : Accepted
- **Date** : 2026-10-23
- **Origine** : consolidation du registre (décision du propriétaire du 2026-10-23)
- **Remplace** : [ADR-0001](archive/0001-language-and-indexer-agnostic-core.md), [ADR-0002](archive/0002-scip-preferred-interoperability-protocol.md)
- **Remplacé par** : —

## Context

Le cahier des charges validé pose deux exigences structurantes : MINOS est **agnostique du langage** et **agnostique de l'indexeur** (ADR 0001). Aucun parser, compilateur, indexeur SCIP, serveur de langage ou moteur d'analyse unique ne couvre correctement tous les langages et toutes les capacités attendues (ADR 0001).

MINOS a besoin d'informations précises au niveau des symboles (définitions, références, implémentations, relations). Réimplémenter pour chaque langage un frontend, un résolveur de symboles et la navigation sémantique consommerait une part très importante du projet avant de produire la valeur propre à MINOS (ADR 0002). SCIP est un protocole d'indexation agnostique du langage fondé sur Protobuf, dont l'écosystème fournit des indexeurs pour plusieurs familles de langages ; le protocole et les indexeurs restent deux sujets distincts : accepter SCIP ne rend pas tous les indexeurs SCIP équivalents ni suffisamment précis (ADR 0002).

Deux ADR postérieurs modifient en partie la décision initiale :

- l'[ADR 0026](0026-discovery-provider-spi-and-explicit-capability-profiles.md) § 1 fixe le mécanisme de découverte (SPI composables) et son § 3 le modèle de capacités (niveau obligatoire par capacité, sans niveau `UNKNOWN`) ;
- l'ADR 0032 § 2 admet que le moteur porte et étende un vocabulaire de langages et de build systems, tant que l'orchestrateur de découverte ne branche sur aucun langage.

Options écartées (ADR 0001, « Alternatives rejetées ») : un framework de parsing unique pour tous les langages ; SCIP comme modèle de domaine interne obligatoire ; les schémas Glean comme modèle de domaine ; les langages supportés comme constante structurelle du domaine.

## Decision

1. **Cœur agnostique.** Le domaine MINOS, son modèle de connaissance et son modèle de requêtes sont agnostiques du langage et de l'indexeur (ADR 0001).
2. **Fournisseurs enregistrés, choisis par capacités.** L'indexation propre à un langage est fournie par des fournisseurs enregistrés, qui déclarent leurs capacités au lieu d'être sélectionnés par le seul nom du langage. `IndexerRegistry` négocie les fournisseurs adaptés au projet détecté et à l'analyse demandée (ADR 0001 ; règles de négociation : [ADR 0008](0008-negocier-indexeurs-par-capacites-explicites.md)). Un fournisseur peut fournir un sous-ensemble de capacités ; plusieurs fournisseurs peuvent contribuer à un même projet ou langage (ADR 0001, règles 3 et 4).
3. **Aucun pipeline par langage dans le cœur.** Le cœur ne contient aucune logique figée du type « si Java -> pipeline Java » (ADR 0001). La découverte passe par quatre SPI, `ProjectDetector`, `BuildSystemDetector`, `SourceRootDetector` et `LanguageDetector`, orchestrées par `ProjectDiscoveryService` ; les conventions intégrées vivent dans `DefaultDiscoveryPlugins` ; l'orchestrateur central ne contient aucun nom de fichier, langage ou build system spécifique (ADR 0026 § 1). Les vocabulaires `Language` et `BuildSystem` peuvent être étendus pour un nouvel écosystème, mais `ProjectDiscoveryService` ne branche sur aucun langage (ADR 0032 § 2). Les langages supportés restent une propriété des fournisseurs disponibles et de leurs capacités (ADR 0001).
4. **Capacités explicites et qualifiées.** Chaque valeur de `IndexerCapability` reçoit obligatoirement un niveau `FULL`, `PARTIAL`, `EXPERIMENTAL` ou `UNSUPPORTED` ; une entrée absente est invalide ; une capacité n'est jamais déduite de la présence d'un fournisseur ni de sa capacité à démarrer (ADR 0026 § 3). L'absence d'une capacité est représentable sans inventer de résultat (ADR 0001, règle 5).
5. **Normalisation à la frontière.** Les modèles propres aux fournisseurs sont normalisés avant de franchir la frontière du domaine ; aucun type propre à un fournisseur n'apparaît dans les contrats publics du domaine (ADR 0001, règle 1). Le support d'un nouveau langage n'impose pas de modifier les services métier existants (ADR 0001, règle 6).
6. **SCIP privilégié, jamais obligatoire.** SCIP est le protocole d'interopérabilité sémantique privilégié lorsqu'un indexeur suffisamment fiable, maintenu et compatible avec les besoins du projet existe ; SCIP n'est jamais obligatoire (ADR 0002). La chaîne est : indexeur propre au langage → SCIP → adaptateur SCIP possédé par MINOS → modèle normalisé MINOS. Les services du cœur n'exposent pas les types Protobuf SCIP, et `IndexerRegistry` permet à des fournisseurs non-SCIP de fournir les mêmes capacités métier lorsqu'ils sont plus adaptés (ADR 0002).
7. **Qualification avant activation.** Un indexeur SCIP n'est activable comme fournisseur qu'après qualification de ses capacités, au niveau défini au point 4 ; le profil conserve les limitations connues (ADR 0002, « Qualification d'un fournisseur SCIP », échelle de niveaux modifiée par ADR 0026 § 3). Le protocole et ses bindings, la CLI d'inspection et chaque indexeur producteur sont versionnés séparément : la compatibilité d'un indexeur ne se déduit pas du numéro de version de la CLI SCIP (ADR 0002).
8. **Limites assumées.** SCIP n'est pas un Code Property Graph et ne garantit à lui seul ni graphe d'appels complet, ni contrôle de flux, ni flux de données, ni analyse de sécurité, ni couverture sémantique uniforme entre langages ; MINOS garde son modèle de capacités et peut compléter SCIP par d'autres moteurs (ADR 0002). L'intégration de providers SCIP polyglottes relève de l'[ADR 0065](0065-providers-scip-polyglottes-livres-dans-le-paquet.md).

## Do's and Don'ts

### Do

- Fournir l'indexation propre à un langage par des fournisseurs enregistrés qui déclarent leurs capacités.
- Brancher un langage ou un build system par les SPI de découverte et les conventions de `DefaultDiscoveryPlugins`.
- Attribuer un niveau `FULL`, `PARTIAL`, `EXPERIMENTAL` ou `UNSUPPORTED` à chaque valeur de `IndexerCapability`.
- Normaliser les modèles propres aux fournisseurs avant la frontière du domaine MINOS.
- Faire passer SCIP par un adaptateur possédé par MINOS et qualifier les capacités d'un indexeur SCIP avant de l'activer.
- Versionner séparément le protocole SCIP et ses bindings, la CLI d'inspection et chaque indexeur.

### Don't

- Ne pas coder dans le cœur une logique figée du type « si Java -> pipeline Java ».
- Ne pas mettre de nom de fichier, de langage ou de build system dans `ProjectDiscoveryService`.
- Ne pas déduire une capacité de la présence d'un fournisseur ou de sa capacité à démarrer.
- Ne pas exposer de type propre à un fournisseur, ni de type Protobuf SCIP, dans les contrats publics ou les services du cœur.
- Ne pas rendre SCIP obligatoire ni empêcher un fournisseur non-SCIP de fournir les mêmes capacités métier.

## Consequences

### Positives

- Un langage, un build system ou un fournisseur nouveau se branche sans réécrire les services principaux (ADR 0001, ADR 0026).
- Plusieurs moteurs peuvent se combiner pour un même langage ; le meilleur fournisseur est choisi selon la capacité demandée (ADR 0001).
- CLI, MCP, API, NEXUS et agents restent indépendants des technologies d'indexation (ADR 0001).
- SCIP évite de reconstruire un parser et un résolveur complets par langage et sépare l'indexation du stockage et des requêtes (ADR 0002).

### Négatives

- Le modèle normalisé et la négociation des capacités demandent une conception soignée ; une part de la richesse propre à un fournisseur peut se perdre à la normalisation (ADR 0001).
- Les capacités absentes ou partielles doivent être déclarées explicitement, et l'intégration entre fournisseurs exige des tests (ADR 0001).

### Risques

- Un transport SCIP commun ne garantit pas une complétude sémantique commune : chaque indexeur se qualifie séparément (ADR 0002).
- Ajouter une valeur au vocabulaire `Language` modifie le moteur ; seul l'orchestrateur de découverte est protégé par un contrôle automatique (voir « Compliance and Enforcement »).

## Compliance and Enforcement

- Gate `scripts/quality/check-polyglot-provider-consistency.py`, lancé par `scripts/remediation/check-post-mne.py` (job `invariants`) — refuse dans `ProjectDiscoveryService` toute branche `Language.CSHARP`, `Language.GO`, `Language.RUST`, `Language.CPP` ou `Language.C` ; les autres langages ne sont pas couverts par ce gate.
- Règle A2 de `scripts/architecture/check-module-boundaries.py` (job `invariants`) — rôles des modules : le moteur, l'application et les surfaces ne dépendent d'aucun adaptateur ; les bindings `scip-java-bindings`, déclarés par le seul `minos-provider-scip/pom.xml`, n'atteignent donc pas le cœur. Aucune règle ne nomme `scip-java-bindings` ni `org.scip_code`.
- Test `ProviderBoundaryTest` (`minos-application`) — aucun import `org.scip_code.scip`, `com.google.protobuf`, `glean.` ou `com.facebook.glean` dans les packages du cœur présents dans `minos-application`.
- Tests `ProviderConformanceKitTest` (`rejectsAnyImplicitCapability`, `producesDeterministicExhaustiveProfile`) et `ProjectDiscoveryServiceTest` (`acceptsNewDiscoveryPluginWithoutChangingCentralOrchestrator`), `minos-engine`, lancés par `mvnw verify`.
- Aucun contrôle automatique de l'absence d'aiguillage par langage ailleurs dans le cœur (voir « Écarts constatés »). Aucune règle Archgate compagnon (`rules: false`) : la contrainte se lit dans les sources, la règle reste à écrire et à voir en échec.
- Revue : aucun aiguillage par langage vers un pipeline, aucun type de fournisseur dans un contrat public, chaque capacité d'un nouveau fournisseur qualifiée par un niveau.

## Écarts constatés

Relevés le 2026-10-23 (audit des ADR), preuves relues au HEAD `2f773e57` ; la décision ci-dessus reste celle en vigueur tant qu'un amendement ou une correction du code n'a pas tranché.

- `ProjectInvalidationService.matchesLanguageExtension` aiguille par langage (`switch`) ; il associe des extensions de fichiers et ne choisit pas de pipeline : la lecture de la règle « si Java -> pipeline Java » (point 3) reste à qualifier — preuve : `minos-engine/src/main/java/com/minos/orchestration/ProjectInvalidationService.java:194-206`.

Écarts des ADR sources levés par la consolidation (aucune décision nouvelle : les amendements existaient déjà) :

- L'énumération fermée `ProjectDiscovery.Language`, relevée contre le rejet d'« une liste fermée de langages supportés dans le cœur » (ADR 0001), est le vocabulaire extensible qu'admet l'ADR 0032 § 2 (point 3) — `minos-engine/src/main/java/com/minos/discovery/ProjectDiscovery.java:44-54`.
- Le modèle `IndexerCapabilities` et le niveau `UNKNOWN` exigés par l'ADR 0002 sont remplacés par `IndexerCapability` et `CapabilitySupportLevel` à quatre niveaux (ADR 0026 § 3, point 4) — `minos-engine/src/main/java/com/minos/orchestration/IndexerCapability.java:10`, `minos-engine/src/main/java/com/minos/orchestration/CapabilitySupportLevel.java:4-9`.

## References

- ADR actifs : [ADR 0008](0008-negocier-indexeurs-par-capacites-explicites.md) (négociation par capacités explicites), [ADR 0026](0026-discovery-provider-spi-and-explicit-capability-profiles.md) (SPI de découverte et profils de capacités), [ADR 0042](0042-racine-de-composition.md) (racine de composition), [ADR 0065](0065-providers-scip-polyglottes-livres-dans-le-paquet.md) (providers SCIP polyglottes).
- ADR archivés : [ADR 0001](archive/0001-language-and-indexer-agnostic-core.md), [ADR 0002](archive/0002-scip-preferred-interoperability-protocol.md) (versions vérifiées pour M0, sources, plan de validation M0, alternatives conservées), [ADR 0032](archive/0032-evidence-gated-polyglot-scip-providers.md) (§ 2).
- Historique : [`VALIDATION_C0.md`](../../project/history/c0/VALIDATION_C0.md), [`INDEXEURS_CAPACITES.md`](../../project/history/milestones/m0/INDEXEURS_CAPACITES.md) ; étude : [`COMPARATIF_FONDATIONS_CODE_INTELLIGENCE.md`](../../project/research/COMPARATIF_FONDATIONS_CODE_INTELLIGENCE.md).
