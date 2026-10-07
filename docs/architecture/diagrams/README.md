# Diagrammes MINOS — Index

Tous les diagrammes sont en Mermaid (blocs ````mermaid`).
Ces diagrammes reprennent ceux des sections arc42, en plus simples : peu de nœuds, étiquettes courtes, noms exacts
de classes dans les tableaux sous chaque diagramme.

Les sept fichiers ont été **relus contre le code le 2026-10-07** et corrigés (classes renommées ou déplacées
depuis août). Les mêmes diagrammes sont repris dans arc42 (§ 3.4, 5.1, 5.3, 6.1 à 6.4, 7.2) ; en cas d'écart,
ces fichiers font foi. Le diagramme de déploiement Docker d'arc42 § 7.3 n'a pas été relu.

| Fichier | Type | Portée |
|---------|------|--------|
| [c4-context.md](c4-context.md) | Contexte (flowchart) | Système complet |
| [c4-container.md](c4-container.md) | Couches (flowchart) | Modules Maven, par couche |
| [c4-component-application.md](c4-component-application.md) | Composants (flowchart) | `minos-application` |
| [module-dependencies.md](module-dependencies.md) | UML classDiagram | Dépendances Maven |
| [seq-indexation-nominale.md](seq-indexation-nominale.md) | sequenceDiagram | Flux indexation Java |
| [seq-erreur-provider.md](seq-erreur-provider.md) | sequenceDiagram | Provider SCIP absent |
| [seq-mcp-startup.md](seq-mcp-startup.md) | sequenceDiagram | Démarrage MCP STDIO |
| [deployment-native.md](deployment-native.md) | Déploiement (flowchart) | Backend natif |
