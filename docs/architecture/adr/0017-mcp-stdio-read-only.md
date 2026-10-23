---
id: ADR-0017
title: "Exposer MINOS en MCP via STDIO read-only"
domain: architecture
rules: false
---

# ADR-0017 — Exposer MINOS en MCP via STDIO read-only

- **Statut** : Accepted
- **Date** : 24 juillet 2026
- **Origine** : M10 — Serveur MCP

## Context

Les clients IA doivent pouvoir consommer MINOS via un protocole standard sans introduire de serveur HTTP obligatoire, de logique métier parallèle ou d’opérations mutables implicites.

## Decision

MINOS expose un serveur MCP local via **STDIO**, en lecture seule, construit sur le SDK Java MCP officiel.

- les tools délèguent aux services MINOS existants ;
- la surface MCP ne duplique pas le métier ;
- les schémas, résultats et erreurs restent bornés ;
- les preuves et limitations sont conservées ;
- aucune mutation de projet ou de code n’est introduite par les tools MCP de ce contrat.

## Do's and Don'ts

### Do

- Exposer le serveur MCP local en **STDIO**, en lecture seule, sur le SDK Java MCP officiel.
- Faire déléguer chaque tool aux services MINOS existants.
- Borner les schémas, les résultats et les erreurs.
- Conserver les preuves et les limitations dans les réponses.

### Don't

- Ne pas dupliquer le métier dans la surface MCP.
- Ne pas introduire de mutation de projet ou de code par les tools MCP de ce contrat.
- Ne pas rendre obligatoire un serveur HTTP.

## Consequences

Les IDE, agents et orchestrateurs peuvent utiliser MINOS comme fournisseur de Code Intelligence local sans déployer un service réseau dans le cœur.

## Compliance and Enforcement

- Tests `MinosMcpReadOnlyHomeTest` et `MinosMcpWriteProtectedHomeTest` (`minos-mcp`, lancés par `mvnw verify`) — les tools répondent sur un `MINOS_HOME` protégé en écriture sans le modifier et n’inspectent pas les runtimes des providers.
- Test `M29DockerQueryImageContractTest` (`minos-app`) et `scripts/ci/qualify-docker-release.sh` (workflow `docker-release-validation.yml`) — la qualification interroge le serveur MCP sur un répertoire de données monté en lecture seule.
- Revue : tout tool `minos_*` nouveau ou modifié est en lecture seule, délègue à un service existant et borne sa réponse.

## Preuves

Voir [`../../project/history/milestones/m10/DECISION_M10.md`](../../project/history/milestones/m10/DECISION_M10.md) et [`../../project/history/milestones/m10/MCP_SERVER.md`](../../project/history/milestones/m10/MCP_SERVER.md).
