# Documentation MINOS

Ce répertoire est le point d’entrée de la documentation **utilisateur**, **développeur**, **architecturale** et **historique** de MINOS Code Intelligence.

MINOS est un moteur local de Code Intelligence : il enregistre des projets, importe des index structurés, normalise symboles et relations, produit des vues d’architecture et d’impact, expose ces capacités en CLI, API Java et MCP, et peut exporter sa connaissance vers NEXUS.

## Choisir son parcours

```mermaid
flowchart LR
    A[Documentation MINOS] --> U[Utiliser]
    A --> D[Développer]
    A --> ADR[Comprendre les décisions]
    A --> H[Consulter l'historique]
    U --> U1[user/]
    D --> D1[developer/]
    ADR --> A1[adr/]
    H --> H1[history/]
```

## Documentation utilisateur

- [Guide utilisateur](user/README.md)
- [Installation et mise en route](user/installation.md)
- [Référence CLI](user/cli.md)
- [API Java locale](user/java-api.md)
- [Utiliser MINOS via MCP](user/mcp.md)
- [Intégration MINOS → NEXUS](user/nexus.md)
- [Dépannage](user/troubleshooting.md)

## Documentation développeur

- [Guide développeur](developer/README.md)
- [Architecture interne](developer/architecture.md)
- [Modèle de domaine](developer/domain-model.md)
- [Indexation, lifecycle et stockage](developer/indexing-and-storage.md)
- [CLI, API Java, MCP et export NEXUS](developer/public-surfaces.md)
- [Multi-dépôts et intelligence Git](developer/multi-repo-git.md)
- [Tests, validation et contribution](developer/testing.md)

## Décisions architecturales

Les choix techniques durables sont documentés sous forme d’[Architecture Decision Records](adr/README.md).

Les ADR répondent à la question **« pourquoi MINOS est-il architecturé ainsi aujourd’hui ? »**. Ils ne servent pas de journal de validation de milestone.

## Historique

L'ensemble du cadrage et des preuves historiques est regroupé sous :

- [Index historique](history/README.md)
- [Cadrage C0](history/c0/README.md)
- [Validations transverses historiques](history/validation/README.md)
- [Historique des jalons M0 → M13](history/milestones/README.md)

Ces documents répondent aux questions **« comment MINOS a-t-il été cadré ? »** et **« comment cette capacité a-t-elle été expérimentée, prouvée et livrée ? »**. Ils conservent volontairement leurs hypothèses, anciens SHA, nombres de tests, limitations fournisseur et états intermédiaires.

## Documents courants à la racine

- [État opérationnel](STATUS.md)
- [Roadmap](ROADMAP.md)
- [ADR](adr/README.md)

> Pour installer ou utiliser MINOS, privilégier `user/`. Pour modifier le code, privilégier `developer/`. Pour comprendre une décision durable, privilégier `adr/`. Pour auditer le cadrage ou une livraison passée, utiliser `history/`.
