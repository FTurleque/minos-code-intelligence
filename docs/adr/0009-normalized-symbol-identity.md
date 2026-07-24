# ADR-0009 — Modéliser les identités de symboles sans inventer de canonicité

- **Statut** : Accepted
- **Date** : 24 juillet 2026
- **Origine** : M2 — Intelligence des symboles

## Contexte

MINOS reçoit des identités de symboles produites par des indexeurs dont la précision varie selon le langage et le fournisseur. Une identité fournisseur peut être exploitable sans être suffisamment stable ou complète pour être qualifiée de canonique.

## Décision

MINOS conserve une identité normalisée indépendante du fournisseur et qualifie explicitement sa qualité.

- `CANONICAL` n’est utilisé que lorsqu’une identité canonique est réellement démontrée ;
- les symboles projet peuvent utiliser un `STRUCTURAL_FALLBACK` ;
- les symboles externes peuvent utiliser un `PROVIDER_SCOPED_FALLBACK` ;
- aucun `qualifiedName`, kind, surcharge ou symbole externe n’est inventé pour compenser une lacune fournisseur ;
- les non-résolutions restent explicites et persistables.

## Conséquences

La recherche et les snapshots peuvent rester stables sans faire passer une heuristique pour un fait. Les consommateurs doivent tenir compte de la qualité d’identité lorsqu’ils comparent ou corrèlent des symboles.

## Preuves

Voir [`../history/milestones/m2/DECISION_M2.md`](../history/milestones/m2/DECISION_M2.md) et les rapports M2 associés.
