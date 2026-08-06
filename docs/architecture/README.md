# Documentation d'architecture MINOS Code Intelligence

> Structure arc42 v8 — diagrammes C4 (Mermaid) — ADR Markdown  
> Version : 1.0.1-SNAPSHOT · Java 24 · Maven multi-module reactor  
> Dernière mise à jour : 2026-08-06

---

## Organisation des sections

| Section | Fichier | Résumé |
|---------|---------|--------|
| 01 | [Introduction et objectifs](arc42/01-introduction-objectifs.md) | Mission, parties prenantes, objectifs qualité |
| 02 | [Contraintes](arc42/02-contraintes.md) | Contraintes métier, techniques, réglementaires, organisationnelles |
| 03 | [Contexte et périmètre](arc42/03-contexte-perimetre.md) | Frontière système, acteurs, diagramme C4 Context |
| 04 | [Stratégie de solution](arc42/04-strategie-solution.md) | Principes, style de décomposition, mécanismes qualité |
| 05 | [Vue des blocs](arc42/05-vue-blocs.md) | Diagrammes C4 Container et Component |
| 06 | [Vue d'exécution](arc42/06-vue-execution.md) | Scénarios nominal, erreur et exploitation |
| 07 | [Vue de déploiement](arc42/07-vue-deploiement.md) | Environnements, nœuds, protocoles |
| 08 | [Concepts transverses](arc42/08-concepts-transverses.md) | Sécurité, résilience, observabilité, persistance… |
| 09 | [Décisions](arc42/09-decisions.md) | Index des ADR |
| 10 | [Exigences qualité](arc42/10-exigences-qualite.md) | Arbre qualité, scénarios |
| 11 | [Risques et dette](arc42/11-risques-dette.md) | Registre priorisé |
| 12 | [Glossaire](arc42/12-glossaire.md) | Termes métier, techniques et acronymes |

## ADR

- [Index des ADR](adr/README.md)
- [Template ADR](adr/template.md)

## Scénarios qualité

- [Scénarios](quality/scenarios.md)

## Registre des risques

- [Registre](risks/register.md)

---

## Conventions de notation

| Stéréotype | Usage |
|-----------|-------|
| `«Person»` | Acteur humain |
| `«Software System»` | Système logiciel externe |
| `«Container»` | Processus ou artefact exécutable |
| `«Component»` | Unité de code dans un Container |
| `«interface»` | Port ou SPI défini par le domaine MINOS |
| `«adapter»` | Implémentation d'un port pour un fournisseur externe |
| `«node»` | Nœud d'infrastructure ou d'exécution |
| `«database»` | Stockage persistant |

Tous les diagrammes sont en Mermaid. Aucun diagramme ASCII ni image binaire n'est généré.
