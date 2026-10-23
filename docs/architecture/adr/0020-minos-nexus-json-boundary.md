---
id: ADR-0020
title: "Intégrer NEXUS par un contrat JSON local versionné"
domain: architecture
rules: false
---

# ADR-0020 — Intégrer NEXUS par un contrat JSON local versionné

- **Statut** : Accepted
- **Date** : 24 juillet 2026
- **Origine** : M13 — Intégration NEXUS

## Context

MINOS utilise Java 24 et possède les faits normalisés de Code Intelligence. NEXUS reste en Java 21 et possède le ranking, la sélection et le budget de contexte. Un couplage Maven imposerait le runtime MINOS à NEXUS et une réimplémentation créerait deux sources de vérité.

## Decision

MINOS et NEXUS communiquent par un contrat JSON local, versionné et explicitement orchestré.

```text
MINOS Java 24
  nexus-export --root <project>
        |
        | JSON stdout — contract v1
        v
NEXUS Java 21
  minos-import <project> < stdin
```

- MINOS reste propriétaire des faits de Code Intelligence, provenance, preuves et limitations ;
- NEXUS reste propriétaire du ranking, de la sélection et du budget de contexte ;
- aucun couplage Maven ou modèle interne n’est introduit ;
- aucun réseau n’est requis pour cette frontière ;
- NEXUS ne lance pas MINOS depuis son cœur ; l’orchestration appartient au shell, à l’IDE, à JARVIS ou à un script ;
- les faits importés conservent `sourceProvider=minos`.

## Do's and Don'ts

### Do

- Faire communiquer MINOS et NEXUS par un contrat JSON local, versionné et explicitement orchestré.
- Laisser MINOS propriétaire des faits de Code Intelligence, de la provenance, des preuves et des limitations.
- Faire évoluer la frontière par version de contrat explicite.

### Don't

- Ne pas introduire de couplage Maven ni de modèle interne partagé avec NEXUS.
- Ne pas exiger de réseau pour cette frontière.
- Ne pas faire lancer MINOS par le cœur de NEXUS.

## Consequences

Les deux moteurs restent indépendants et peuvent évoluer sur des runtimes Java différents. La frontière doit évoluer par version de contrat explicite.

## Compliance and Enforcement

- Test `NexusExportContractTest` (`minos-nexus`, lancé par `mvnw verify`) — version de contrat et producteur épinglés ; les composants exportés ne dépendent d’aucun type NEXUS.
- Revue : toute modification du format exporté par `nexus-export` passe par une version de contrat explicite.

## Preuves

Voir [`../../project/history/milestones/m13/DECISION_M13.md`](../../project/history/milestones/m13/DECISION_M13.md) et [`../../project/history/milestones/m13/NEXUS_INTEGRATION.md`](../../project/history/milestones/m13/NEXUS_INTEGRATION.md).

## Mise en œuvre — baseline Java 25

La baseline de MINOS est **Java 25 LTS** ([ADR 0059](0059-migrer-le-socle-vers-java-25-lts.md)) ; les mentions de Java 24 ci-dessus décrivent le contexte de la décision. La décision est inchangée : les deux moteurs restent séparés par un contrat JSON local versionné, sans couplage Maven.
