# ADR-0020 — Intégrer NEXUS par un contrat JSON local versionné

- **Statut** : Accepted
- **Date** : 24 juillet 2026
- **Origine** : M13 — Intégration NEXUS

## Contexte

MINOS utilise Java 24 et possède les faits normalisés de Code Intelligence. NEXUS reste en Java 21 et possède le ranking, la sélection et le budget de contexte. Un couplage Maven imposerait le runtime MINOS à NEXUS et une réimplémentation créerait deux sources de vérité.

## Décision

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

## Conséquences

Les deux moteurs restent indépendants et peuvent évoluer sur des runtimes Java différents. La frontière doit évoluer par version de contrat explicite.

## Preuves

Voir [`../history/milestones/m13/DECISION_M13.md`](../history/milestones/m13/DECISION_M13.md) et [`../history/milestones/m13/NEXUS_INTEGRATION.md`](../history/milestones/m13/NEXUS_INTEGRATION.md).
