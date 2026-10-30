# Proposal

## Why

**AUD-PERF-05** (moyenne, sprint 4 de l'audit du 10 octobre 2026). Une requête de graphe d'architecture en Mermaid ou DOT filtrée sur un module fait **deux découvertes complètes du dépôt et deux chargements du snapshot** : `MinosApplicationMcpBackend.java:176-178` appelle `getArchitectureIntelligence(project)` puis `getModuleContext(project, module)` pour n'en tirer que l'identifiant du module, et chacune de ces méthodes passe par `LocalProjectArchitectureQuery.loadContext` (`:81-86`), qui recharge le snapshot et relance la découverte.

La découverte est la part dominante d'une requête d'architecture : 12,7 à 36,3 s sur ce dépôt et 96 % du coût (audit d'architecture de septembre). `minos_architecture_graph` avec un module coûte donc le double sans raison et dépasse plus facilement les délais des clients MCP.

Le relevé au HEAD trouve le **même double appel dans la CLI** (`ArchitectureCommand.java:40-43`), que l'audit ne cite pas.

## What Changes

- **Un seul chargement de contexte** par requête de graphe d'architecture avec module, côté MCP **et** côté CLI : l'identifiant du module est résolu sur le contexte déjà obtenu.
- **Même erreur pour un module inconnu** qu'aujourd'hui.
- **Aucun cache d'architecture** n'est introduit (décision existante, respectée).

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `program-graph-et-architecture` : une exigence ajoutée (une requête de graphe d'architecture avec module découvre le dépôt une seule fois, sur chaque surface).

## Hors périmètre

- Mettre en cache la découverte ou l'architecture : écarté par la décision A4 de l'audit d'architecture et l'ADR 0045 ; constat Info AUD-PERF-14 (« ne pas corriger sans nouvelle mesure »).
- Accélérer la découverte elle-même.
- Les autres outils d'architecture, qui ne font qu'un chargement.
- Les autres constats du sprint 4.

## Impact

- **Modules du reactor** : `minos-application` (port `ProjectArchitectureQuery`, `architecture/LocalProjectArchitectureQuery`), `minos-mcp` (`MinosApplicationMcpBackend`), `minos-cli` (`command/ArchitectureCommand`). Les surfaces n'importent aucun adaptateur.
- **Surfaces publiques** : sorties MCP et CLI identiques. Le port gagne une méthode `default` (additif).
- **ADR** : aucun.
- **Gates à rejouer** : scope JaCoCo `mcp-mapping`, `check-hosted-control-plane-consistency.py` (il cite `MinosApplicationMcpBackend` ; littéraux à relire en tâche 1.1), `CliParsingGuardTest`, goldens de `minos-app`, `python scripts/architecture/check-module-boundaries.py`.
- **Plateformes** : Windows et Linux, sans différence.
