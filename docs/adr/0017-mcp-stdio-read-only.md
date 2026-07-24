# ADR-0017 — Exposer MINOS en MCP via STDIO read-only

- **Statut** : Accepted
- **Date** : 24 juillet 2026
- **Origine** : M10 — Serveur MCP

## Contexte

Les clients IA doivent pouvoir consommer MINOS via un protocole standard sans introduire de serveur HTTP obligatoire, de logique métier parallèle ou d’opérations mutables implicites.

## Décision

MINOS expose un serveur MCP local via **STDIO**, en lecture seule, construit sur le SDK Java MCP officiel.

- les tools délèguent aux services MINOS existants ;
- la surface MCP ne duplique pas le métier ;
- les schémas, résultats et erreurs restent bornés ;
- les preuves et limitations sont conservées ;
- aucune mutation de projet ou de code n’est introduite par les tools MCP de ce contrat.

## Conséquences

Les IDE, agents et orchestrateurs peuvent utiliser MINOS comme fournisseur de Code Intelligence local sans déployer un service réseau dans le cœur.

## Preuves

Voir [`../history/milestones/m10/DECISION_M10.md`](../history/milestones/m10/DECISION_M10.md) et [`../history/milestones/m10/MCP_SERVER.md`](../history/milestones/m10/MCP_SERVER.md).
