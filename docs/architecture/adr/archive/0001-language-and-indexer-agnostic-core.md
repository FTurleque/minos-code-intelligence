---
id: ADR-0001
title: "Conserver un cœur MINOS agnostique du langage et de l'indexeur"
domain: architecture
rules: false
---

# ADR-0001 — Conserver un cœur MINOS agnostique du langage et de l'indexeur

> **Archivé le 2026-10-23** (tri du registre, [ADR 0063](0063-ecrire-les-adr-au-format-archgate-dans-le-registre-arc42.md)) : Superseded by [ADR-0064](../ARCH-0064-coeur-agnostique-du-langage-et-de-l-indexeur.md). Ce document est conservé pour l'historique ; il n'est plus lu par Archgate.

- Statut : **Acceptée**
- Date de décision : **22 juillet 2026**

## Context

MINOS doit devenir un moteur de Code Intelligence capable de comprendre des dépôts utilisant plusieurs langages et différents systèmes de build.

Les premiers exemples ont souvent cité Java, TypeScript et Python, mais ces langages ne doivent jamais devenir des frontières architecturales.

De la même manière, aucun parser, compilateur, indexeur SCIP, serveur de langage ou moteur d'analyse unique ne peut être supposé couvrir correctement tous les langages et toutes les capacités attendues.

Le cahier des charges validé établit explicitement deux exigences structurantes :

- MINOS est **agnostique du langage** ;
- MINOS est **agnostique de l'indexeur**.

Cette décision ne préjuge pas de l'adoption future de SCIP, Glean ou d'un autre fournisseur.

## Decision

Le domaine MINOS, son modèle de connaissance et son modèle de requêtes sont conçus pour être :

- **agnostiques du langage** ;
- **agnostiques de l'indexeur**.

L'indexation spécifique à un langage est fournie par des fournisseurs enregistrés.

Les fournisseurs déclarent leurs capacités plutôt que d'être sélectionnés uniquement à partir du nom du langage.

Exemples de capacités :

```text
DEFINITIONS
REFERENCES
IMPLEMENTATIONS
TYPE_RELATIONSHIPS
CALL_RELATIONSHIPS
CROSS_FILE
CROSS_MODULE
CROSS_REPOSITORY
CONTROL_FLOW
DATA_FLOW
```

Le cœur ne doit pas contenir de logique figée telle que :

```text
si Java -> pipeline Java
si Python -> pipeline Python
si TypeScript -> pipeline TypeScript
```

Un `IndexerRegistry` et un mécanisme de négociation des capacités permettront de sélectionner les fournisseurs adaptés au projet détecté et à l'analyse demandée.

Les modèles propres aux fournisseurs doivent être normalisés avant de franchir la frontière du domaine MINOS.

## Do's and Don'ts

### Do

- Fournir l'indexation propre à un langage par des fournisseurs enregistrés.
- Faire déclarer leurs capacités aux fournisseurs plutôt que de les sélectionner uniquement par le nom du langage.
- Normaliser les modèles propres aux fournisseurs avant la frontière du domaine MINOS.
- Représenter explicitement les capacités, y compris leur absence, sans inventer de résultat.

### Don't

- Ne pas coder dans le cœur une logique figée du type « si Java -> pipeline Java ».
- Ne pas exposer de type propre à un fournisseur dans les contrats publics du domaine MINOS.
- Ne pas imposer la modification des services métier existants pour supporter un nouveau langage.

## Consequences

### Conséquences positives

- ajout de nouveaux langages sans réécriture des services principaux ;
- combinaison possible de plusieurs moteurs pour un même langage ;
- choix du meilleur fournisseur selon la capacité demandée ;
- coexistence future d'indexeurs sémantiques, CPG, analyseurs de flux ou outils de sécurité ;
- consommateurs CLI, MCP, API, NEXUS et agents indépendants des technologies d'indexation ;
- possibilité de remplacer SCIP ou Glean sans redéfinir le domaine MINOS.

### Conséquences négatives

- nécessité de concevoir soigneusement un modèle normalisé ;
- complexité supplémentaire de négociation des capacités ;
- risque de perdre certaines richesses propres à un fournisseur lors de la normalisation ;
- besoin de tests d'intégration entre fournisseurs ;
- nécessité de définir explicitement les capacités absentes ou partielles.

## Compliance and Enforcement

- Règle A2 de `scripts/architecture/check-module-boundaries.py` (job `invariants`) — dépendances autorisées entre modules et frontière hexagonale ; contrôle indirect de l'isolation des fournisseurs.
- Test `ModuleArchitectureTest` (`adaptersDoNotReachBackIntoUpperLayers`, `everyModuleOnlyUsesItsAllowedModules`) — mêmes frontières de modules, vues depuis les classes.
- Aucun contrôle automatique de l'absence de logique par langage dans le cœur.
- Revue : aucun aiguillage par langage vers un pipeline dans le cœur, aucun type de fournisseur dans un contrat public, capacités absentes représentées explicitement.

## Alternatives rejetées

### Un framework de parsing unique pour tous les langages

Rejeté comme principe architectural : la qualité du parsing, de la résolution sémantique et du support écosystème varie fortement selon les langages.

### SCIP comme modèle de domaine interne obligatoire

Rejeté : SCIP est envisagé comme protocole d'interopérabilité possible, pas comme définition du domaine MINOS.

### Les schémas Glean comme modèle de domaine MINOS

Rejeté : cela couplerait directement le domaine et les contrats publics à un backend particulier.

### Une liste fermée de langages supportés dans le cœur

Rejetée : les langages supportés sont une propriété des fournisseurs disponibles et de leurs capacités, pas une constante structurelle du domaine.

## Règles d'architecture résultantes

1. Aucun type propre à un fournisseur ne doit apparaître dans les contrats publics du domaine MINOS.
2. Les capacités doivent être représentées explicitement.
3. Un fournisseur peut fournir un sous-ensemble de capacités.
4. Plusieurs fournisseurs peuvent contribuer à un même projet ou langage.
5. L'absence d'une capacité doit être représentable sans inventer de résultat.
6. Le support d'un nouveau langage ne doit pas imposer une modification des services métier existants.

## Validation

Cette ADR est acceptée à la suite de la validation du cahier des charges MINOS, qui confirme explicitement les principes d'agnosticisme du langage et de l'indexeur.

Les expérimentations M0 devront vérifier que cette abstraction reste praticable avec au moins deux écosystèmes de langage et plusieurs types de fournisseurs.

## Écarts constatés

Relevés le 2026-10-23 (audit des ADR) ; la décision ci-dessus reste celle en vigueur tant qu'un amendement ou une correction du code n'a pas tranché.

- Le moteur porte une énumération fermée de langages (`ProjectDiscovery.Language` : `JAVA` … `RUST`), alors que l'ADR rejette « une liste fermée de langages supportés dans le cœur » — preuve : `minos-engine/src/main/java/com/minos/discovery/ProjectDiscovery.java:44-54`.
- `ProjectInvalidationService.matchesLanguageExtension` aiguille par langage (`switch`) ; il associe des extensions de fichiers et ne choisit pas de pipeline : la lecture de la règle « si Java -> pipeline Java » reste à qualifier — preuve : `minos-engine/src/main/java/com/minos/orchestration/ProjectInvalidationService.java:194-206`.
