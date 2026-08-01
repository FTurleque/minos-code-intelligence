# Feuille de route — MINOS

Statut au **1er août 2026** : **C0 → M28 terminés et intégrés sur `main`; MINOS 1.0.0 publié; maintenance Windows 1.0.1 en préparation et non publiée.**

L'état courant est dans [`STATUS.md`](STATUS.md). Les preuves d'exécution détaillées restent sous [`roadmap/`](roadmap/), les décisions durables sous [`adr/`](adr/README.md) et les preuves historiques sous [`history/milestones/`](history/milestones/README.md).

## Principes de roadmap

- une capacité n'est acquise qu'avec une preuve reproductible ;
- facts, dérivations, heuristiques et observations partielles restent distincts ;
- les snapshots structurés restent autoritatifs ;
- les capacités provider absentes ne sont jamais extrapolées ;
- CLI, API, MCP, NEXUS et IntelliJ restent des surfaces au-dessus du métier, pas des implémentations métier parallèles ;
- les décisions de backend sont guidées par la mesure ;
- les claims remote/hosted/sandbox restent fail-closed lorsqu'ils ne sont pas prouvés ;
- une release est immuable : un défaut publié reçoit une version corrective, jamais un retag silencieux ;
- pour Windows, le binaire packagé et son runtime embarqué doivent être testés, pas seulement le JAR sur un JDK complet.

## Trajectoire livrée C0 → M28

| Jalon | Résultat principal | État |
|---|---|---|
| C0 | cadrage fonctionnel et architectural | ✅ livré |
| M0 | faisabilité SCIP/Glean/backend local | ✅ livré |
| M1 | discovery projets, modules et lifecycle d'indexation | ✅ livré |
| M2 | symboles normalisés et identités stables | ✅ livré |
| M3 | références, appels, implémentations et dépendances | ✅ livré |
| M4 | recherche structurée et contexte compact | ✅ livré |
| M5 | tests liés et dérivations explicables | ✅ livré |
| M6 | intelligence d'architecture | ✅ livré |
| M7 | indexation incrémentale et fingerprints | ✅ livré |
| M8 | analyse d'impact bornée et explicable | ✅ livré |
| M9 | CLI stabilisée | ✅ livré |
| M10 | serveur MCP STDIO read-only | ✅ livré |
| M11 | API Java publique versionnée | ✅ livré |
| M12 | multi-dépôts et intelligence Git | ✅ livré |
| M13 | export NEXUS versionné | ✅ livré |
| M14 | indexation autonome + installation PROD Windows | ✅ livré |
| M15 | reactor multi-module et industrialisation core | ✅ livré |
| M16 | qualification scalabilité/performance | ✅ livré |
| M17 | plateforme discovery/providers | ✅ livré |
| M18 | client/plugin IntelliJ | ✅ livré |
| M19 | ProgramGraph / CFG / data-flow / sécurité | ✅ livré |
| M20 | recherche sémantique et hybride | ✅ livré |
| M21 | Production Integrity & Surface Convergence | ✅ #73 closed/completed |
| M22 | Advanced Provider Intelligence | ✅ livré |
| M23 | Semantic Retrieval 2.0 | ✅ livré |
| M24 | Polyglot Expansion C/C++, C#, Go, Rust | ✅ livré |
| M25 | Remote & Distributed Indexing | ✅ livré avec contraintes sandbox explicites |
| M26 | Runtime & Dynamic Intelligence | ✅ livré avec observations partielles |
| M27 | Team / Hosted Mode embarqué | ✅ livré avec frontière no-SaaS |
| M28 | Production Convergence & Architectural Hardening | ✅ #93 closed/completed ; PR #102 merged |

Les roadmaps détaillées M15→M28 restent disponibles dans `docs/roadmap/` et ne doivent pas être réinterprétées comme état courant lorsqu'elles décrivent une étape historique de leur exécution.

## Ligne de production 1.x

### 1.0.0 — première stable

État : **PUBLIÉE**.

```text
main/tag v1.0.0 : 1adbc45339efe37cd26d1937025bfa69d7b57811
PR promotion     : #102 MERGED
M21              : #73 CLOSED / completed
M28              : #93 CLOSED / completed
```

Voir [`releases/1.0.0.md`](releases/1.0.0.md).

Un défaut post-publication du packaging Windows a été identifié : le runtime `jpackage` embarqué était sous-spécifié et pouvait manquer `java.xml`, provoquant un `NoClassDefFoundError: org/w3c/dom/Node` au bootstrap du serveur MCP. La correction n'altère pas `v1.0.0`; elle est portée par 1.0.1.

### 1.0.1 — Windows release hardening

État : **EN PRÉPARATION / NON PUBLIÉE** sur `fix/v1.0.1-release-hardening`.

Objectifs obligatoires avant publication :

1. dériver les modules du runtime avec `jdeps` depuis le JAR final ;
2. vérifier le runtime produit avec `java --list-modules` et interdire la régression `java.xml` ;
3. lancer un vrai handshake MCP sur la distribution portable ;
4. lancer le même handshake sur une installation setup isolée ;
5. empêcher les smoke tests de toucher une installation MINOS réelle ;
6. restaurer l'UX d'installation avec page **Intégrations MCP natives** et détection des clients ;
7. capability-prober Copilot CLI, Claude Code et Codex CLI ;
8. prendre en charge Codex Desktop via sa configuration utilisateur lorsque ce mode est détecté ;
9. préserver, sauvegarder et désinstaller uniquement les configurations appartenant à MINOS ;
10. générer localement `MINOS-1.0.1-windows-x64-setup.exe` ;
11. faire valider visuellement le setup et tester la connexion MCP réelle dans Copilot avant toute publication ;
12. ne créer `v1.0.1` qu'après autorisation explicite de publication.

Voir [`releases/1.0.1.md`](releases/1.0.1.md) et [`user/production-installation.md`](user/production-installation.md).

## Reliquat produit explicite

### #98 — sandbox OS worker réelle

État : **OPEN**.

Le worker distant natif ne revendique toujours pas une sandbox OS pour code non fiable. `DENY` reste fail-closed lorsqu'aucun backend OS qualifié ne peut le garantir. La suite de la roadmap peut implémenter/qualifier les backends Windows et Linux suivis par #98, mais aucun document ni release 1.x ne doit présenter cette capacité comme acquise avant preuve.

## Prochaine décision produit

Après validation et publication éventuelle de 1.0.1, deux axes sont séparés :

- maintenance 1.x : correctifs, packaging, ergonomie, compatibilité clients et sécurité sans expansion de claims ;
- évolution fonctionnelle : nouveaux jalons uniquement après cadrage explicite, sans masquer le reliquat #98.

Aucune `v1.0.1` n'est considérée publiée tant que le tag et la GitHub Release n'existent pas après les gates manuels/automatisés autorisés.
