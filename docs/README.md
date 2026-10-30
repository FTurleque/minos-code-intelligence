# Documentation MINOS

MINOS Code Intelligence est un moteur de code intelligence **local-first**, multi-langages et indépendant des fournisseurs d'IA. L'état livré se lit dans [l'état opérationnel](project/governance/STATUS.md).

La documentation est rangée en trois espaces, un par lecteur, plus le dossier d'architecture, qui porte le registre des ADR. Chaque page n'appartient qu'à un seul endroit.

| Vous voulez… | Espace | Entrée |
|---|---|---|
| installer, configurer et utiliser MINOS | [`user/`](user/) | [Guide utilisateur](user/README.md) |
| compiler, modifier, intégrer ou étendre MINOS | [`developer/`](developer/) | [Guide développeur](developer/README.md) |
| suivre le projet : état, plans, audits, historique | [`project/`](project/) | [Suivi du projet](project/README.md) |
| lire le dossier d'architecture (arc42) | [`architecture/`](architecture/) | [Dossier d'architecture](architecture/README.md) |
| retrouver une décision d'architecture | [`architecture/adr/`](architecture/adr/) | [Registre des ADR](architecture/adr/README.md) |

## Utilisateur — [`user/`](user/)

| Besoin | Document |
|---|---|
| installer MINOS sous Windows | [Installation PROD Windows](user/production-installation.md) |
| installer depuis les sources | [Installation](user/installation.md) |
| indexer un projet sans préparer d'index | [Indexation autonome](user/autonomous-indexing.md) |
| trouver une commande | [Référence CLI](user/cli.md) |
| connecter un client MCP | [MCP](user/mcp.md) |
| utiliser l'API Java locale | [API Java](user/java-api.md) |
| utiliser le plugin IntelliJ | [Plugin IntelliJ](user/intellij-plugin.md) |
| exporter vers NEXUS | [Intégration NEXUS](user/nexus.md) |
| résoudre un problème | [Dépannage](user/troubleshooting.md) |
| lire ce qui change dans une version | [Notes de version](user/release-notes/) |

## Développeur — [`developer/`](developer/)

| Besoin | Document |
|---|---|
| comprendre les modules et les invariants | [Guide développeur](developer/README.md) · [Architecture interne](developer/architecture.md) |
| connaître la toolchain de référence | [Politique de toolchain](developer/TOOLCHAIN_POLICY.md) |
| tester et valider | [Tests et validation](developer/testing.md) · [Gates qualité](developer/quality-gates.md) |
| analyse statique et mutation | [Audit de code : SpotBugs et PIT](developer/quality/code-audit.md) |
| surfaces publiques (CLI, API Java, MCP) | [Surfaces publiques](developer/public-surfaces.md) · [Faits produit générés](developer/generated/product-facts.md) |
| configurer les agents IA du dépôt | [Configuration IA](developer/ai-configuration.md) |
| exécuter les règles Archgate des ADR | [Archgate](developer/archgate.md) |

## Architecture — [`architecture/`](architecture/)

| Besoin | Document |
|---|---|
| retrouver ou écrire une décision | [Registre des ADR](architecture/adr/README.md) · [gabarit](architecture/adr/gabarit/template.md) |
| vue d'ensemble du système | [Vue d'ensemble](architecture/overview.md) |
| dossier arc42 complet | [Index arc42](architecture/README.md) |

## Projet — [`project/`](project/)

| Besoin | Document |
|---|---|
| connaître l'état livré et la progression | [État opérationnel](project/governance/STATUS.md) · [Roadmap](project/governance/ROADMAP.md) |
| lire le plan d'un jalon ou d'un chantier | [Plans d'exécution](project/roadmap/) |
| lire un audit | [Audits](project/audits/README.md) |
| études et expériences | [Études](project/research/) |
| consulter un relevé historique (C0, M0 → M13, validations) | [Historique](project/history/README.md) |

Les plans, audits et relevés historiques sont datés : ils ne sont pas réécrits pour leur faire revendiquer un résultat postérieur. Les pages de `user/` et `developer/` décrivent la baseline courante.
