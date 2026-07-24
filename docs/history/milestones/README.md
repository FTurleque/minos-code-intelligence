# Historique des jalons MINOS

Ce répertoire conserve les **preuves, rapports d’expérimentation, validations et décisions de clôture historiques** des jalons MINOS.

Il ne décrit pas nécessairement l’état courant du produit. Pour cela, utiliser :

- [`../../STATUS.md`](../../STATUS.md) pour l’état livré ;
- [`../../ROADMAP.md`](../../ROADMAP.md) pour la feuille de route ;
- [`../../developer/`](../../developer/) pour l’architecture actuelle ;
- [`../../user/`](../../user/) pour l’utilisation actuelle ;
- [`../../adr/`](../../adr/) pour les décisions architecturales durables.

## Règle documentaire

```text
ADR
  = décision structurante encore pertinente

user/ + developer/
  = documentation de l’état courant

history/milestones/mX/
  = preuves et contexte historique de livraison
```

Un document historique peut donc contenir un ancien SHA, un ancien nombre de tests, un statut « validation en attente » ou une limitation correspondant exactement au moment où le jalon a été travaillé. Ces informations sont conservées intentionnellement et ne doivent pas être réinterprétées comme l’état courant.

## Jalons archivés

| Jalon | Objet | Décision durable principale |
|---|---|---|
| M0 | Faisabilité technique | ADR-0001 à ADR-0005, ADR-0008 |
| M1 | Découverte et orchestration | ADR-0006 à ADR-0008 |
| M2 | Intelligence des symboles | ADR-0009 |
| M3 | Intelligence des relations | ADR-0010 |
| M4 | Recherche et contexte compact | ADR-0011 |
| M5 | Tests liés | ADR-0012 |
| M6 | Intelligence d’architecture | ADR-0013 |
| M7 | Indexation incrémentale | ADR-0014 |
| M8 | Analyse d’impact | ADR-0015 |
| M9 | CLI stabilisée | ADR-0016 |
| M10 | Serveur MCP | ADR-0017 |
| M11 | API publique | ADR-0018 |
| M12 | Multi-dépôts et Git | ADR-0019 |
| M13 | Intégration NEXUS | ADR-0020 |

Les scripts et benchmarks expérimentaux restent volontairement dans `scripts/m0/` et `benchmarks/m0/` : ils sont des artefacts reproductibles, pas des documents d’architecture.
