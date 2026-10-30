---
id: ADR-0062
title: "Confirmer les emplacements de l'ADR 0044 et ne créer de package que sous les racines de son module"
domain: architecture
rules: false
---

# ADR-0062 — Confirmer les emplacements de l'ADR 0044 et ne créer de package que sous les racines de son module

> **Archivé le 2026-10-23** (tri du registre, [ADR 0063](0063-ecrire-les-adr-au-format-archgate-dans-le-registre-arc42.md)) : Superseded by [ADR-0068](../ARCH-0068-modules-dependances-et-propriete-des-packages.md). Ce document est conservé pour l'historique ; il n'est plus lu par Archgate.

- **Statut** : Accepted
- **Date** : 2026-10-22
- **Origine** : audit du 10 octobre 2026, sprint 10 (AUD-ARC-05, AUD-ARC-11, AUD-ARC-12) — changement OpenSpec `trancher-les-decisions-d-architecture-du-sprint-10`, décisions D2 à D4 du propriétaire
- **Remplace** : —
- **Remplacé par** : —

---

## Context

L'audit du 10 octobre 2026 propose trois actions qui touchent aux emplacements fixés par l'[ADR 0044](0044-un-package-un-module.md) :

- **AUD-ARC-05** : sortir le package `hosted` de `minos-engine` vers `minos-application`, et retirer du moteur le lancement d'`icacls.exe`. `minos-engine` ne contient qu'un site de lancement de processus : `PrivateLocalStorage` (primitive statique d'ACL Windows, nommée par neuf modules). L'ADR 0044 § 2 a placé `hosted` dans le moteur comme « seul module capable d'accueillir les trois côtés » ; `minos-storage-local` l'importe et ne peut pas dépendre de l'application. AUD-SEC-06 réécrit ce même code d'ACL.
- **AUD-ARC-11** : déplacer le transport MCP Docker de `minos-app` dans `minos-mcp` et renommer le pont NEXUS. Le premier créerait l'arête `minos-mcp → minos-cli`, supprimée en M15-S4 (ADR 0022) ; le second casse un point d'entrée de processus documenté (`docs/user/java-api.md`, section « Ruptures »), que l'ADR 0044 garde « sans changer de nom ». Le dépôt NEXUS ne nomme aucun type `com.minos` (relu le 2026-10-22 sur sa copie locale) ; la contrainte vient du contrat documenté de MINOS, pas d'un consommateur connu. L'arête d'exécution `minos-cli` → `minos-app` passe par le service `McpLaunchRoute` et n'apparaissait dans aucun document.
- **AUD-ARC-12** : 23 racines de packages `com.minos.<x>` (moteur 15, application 6, domaine 2) ne disent pas leur module. Aucun package n'est partagé entre deux modules (règle A3). Renommer contredirait la préférence de l'ADR 0044 pour le déplacement à nom qualifié constant, et coûterait gates littéraux, portées JaCoCo et code neuf pour SonarCloud.

---

## Critères de décision

- Ne pas rouvrir une décision acceptée sans gain démontré (ADR 0044, 0055 à 0058).
- Ne créer ni arête interdite (ADR 0022) ni module qui reproduit un package.
- Garder les points d'entrée et les surfaces publiques stables.
- Rendre la dette impossible à accroître sans édition visible.

---

## Options évaluées

### Option A — Confirmer l'ADR 0044 et figer la dette par des cliquets (retenue)

Les emplacements restent ; les règles A11 à A14 de `scripts/architecture/check-module-boundaries.py` interdisent toute croissance ; les chantiers de fond sont spécifiés quand leurs prérequis sont livrés.

### Option B — Amender l'ADR 0044

Sortir `hosted` vers un module dédié, déplacer le service de route MCP dans un module de lancement neuf, renommer progressivement les racines. Coût : une dépendance nouvelle de `minos-storage-local`, un module créé pour reproduire un package, gates littéraux (`check-private-io.py` et sa liste, `check-p0-p2.py`, `check-hosted-control-plane-consistency.py`), portées JaCoCo, baselines SpotBugs, et une rupture de nom pour des points d'entrée documentés.

---

## Decision

1. **ACL Windows et `hosted` (D2).** Le mécanisme d'ACL Windows de `PrivateLocalStorage` sortira du moteur par un **port d'E/S de plateforme**, fourni par `minos-runtime-local` et injecté. Ce port est conçu **avec** AUD-SEC-06, qui réécrit ce code, et pas avant. D'ici là, `PrivateLocalStorage` est le seul lanceur de processus admis dans `minos-domain`, `minos-engine` et `minos-application` (règle A12). `hosted` **reste** dans `minos-engine` (ADR 0044 § 2) tant que la cartographie de SH-08 n'a pas montré un meilleur emplacement.
2. **`minos-app` (D3).** Le transport MCP Docker et le pont NEXUS (`com.minos.integration.nexus.NexusExportBridgeMain`) **restent** dans l'assemblage, sous leurs noms. L'arête d'exécution `minos-cli` → `minos-app`, et toute arête qu'un fichier `META-INF/services` crée entre deux modules, figure dans le document généré `docs/architecture/diagrams/module-dependencies.md`, dont le contrôle échoue si elle manque.
3. **Nommage des packages (D4).** Aucun package existant n'est renommé. Un **nouveau** package se crée sous une racine `com.minos.<x>` que son module possède déjà (règle A14, table `PACKAGE_ROOTS_BY_MODULE`), et de préférence sous l'espace de noms propre au module quand il en a un (`com.minos.application`, `com.minos.storage.local`, `com.minos.storage.postgresql`, `com.minos.runtime.local`, `com.minos.adapter.scip`, `com.minos.integration.git`, `com.minos.bootstrap`, `com.minos.cli`, `com.minos.api`, `com.minos.mcp`, `com.minos.nexus`, `com.minos.app`). Une **nouvelle racine** est une décision d'architecture : elle s'ajoute à la table dans une modification relue qui cite sa raison.
4. Les usages existants des surfaces vers le moteur (ADR 0058 § 3) et entre adaptateurs (ADR 0055) sont tenus par des listes fermées (règles A11 et A13) qui ne peuvent que rétrécir.

## Do's and Don'ts

### Do

- Sortir l'ACL Windows du moteur par un port d'E/S de plateforme fourni par `minos-runtime-local`, conçu avec AUD-SEC-06.
- Laisser `hosted` dans `minos-engine` tant que la cartographie de SH-08 n'a pas montré un meilleur emplacement.
- Écrire dans `docs/architecture/diagrams/module-dependencies.md` toute arête d'exécution créée par un fichier `META-INF/services`.
- Créer un nouveau package sous une racine que son module possède déjà ; une nouvelle racine est une modification relue qui cite sa raison.

### Don't

- Ne renommer aucun package existant.
- Aucun lanceur de processus dans `minos-domain`, `minos-engine` et `minos-application` hors `PrivateLocalStorage`.
- Ne pas déplacer ni renommer le transport MCP Docker et le pont NEXUS.
- Ne pas laisser croître les listes des règles A11 et A13 : elles ne peuvent que rétrécir.

---

## Consequences

### Conséquences positives

- Aucun point d'entrée, aucune signature publique ni aucun nom qualifié ne change.
- La dette mesurée ne peut plus croître sans une ligne ajoutée à une table du garde, que la revue voit.
- L'arête d'exécution cachée par le service de route MCP est écrite et contrôlée.

### Conséquences négatives

- `minos-engine` continue de lancer `icacls.exe` jusqu'au port de plateforme ; `hosted` et les racines sans préfixe de module restent en place.
- Tout nouvel usage légitime d'un type du moteur par une surface, ou d'un adaptateur par un autre, demande d'éditer une liste.

### Conséquences neutres / contraintes

- Les chantiers qui résorbent la dette (port de plateforme SH-08, port de lecture SH-04, fusion du stockage et codec commun SH-02/SH-03, staging injecté SH-06) déplacent des classes : chaque spécification écrit son coût (couverture du nouveau code par module, tests déplacés avec la classe, portées de `check-jacoco.py`, gates littéraux, baselines SpotBugs). Leur ordre et leurs prérequis sont dans [le plan du chantier](../../../project/roadmap/storage-hexagonal-2026-10/README.md).
- Si une cartographie future justifie de sortir `hosted` ou de renommer une racine, un nouvel ADR remplace le point concerné de celui-ci.

## Compliance and Enforcement

- Gate `scripts/architecture/check-module-boundaries.py` (job `invariants`) — règles A11 (types du moteur par surface), A12 (lanceurs de processus), A13 (types entre adaptateurs), A14 (racines de packages) et arêtes d'exécution du document généré ; auto-test `test_check_module_boundaries.py` (cas refusé, accepté et entrée morte par règle).
- Revue : toute ligne ajoutée à `PACKAGE_ROOTS_BY_MODULE`, `ENGINE_TYPES_NAMED_BY_SURFACE` ou `ADAPTER_TYPES_NAMED_BY_ADAPTER` cite sa raison.

---

## Méthode de validation

`python scripts/architecture/check-module-boundaries.py` (job `invariants`) applique les règles A11 (types du moteur par surface), A12 (lanceurs de processus), A13 (types entre adaptateurs) et A14 (racines de packages), et contrôle les arêtes d'exécution du document généré. Son auto-test `scripts/architecture/test_check_module_boundaries.py` couvre, pour chaque règle, un cas refusé, un cas accepté et une entrée morte.

---

## References

- ADR liés : [0022](0022-maven-reactor-and-module-boundaries.md), [0042](../ARCH-0042-racine-de-composition.md), [0044](0044-un-package-un-module.md), [0055](0055-unifier-les-adaptateurs-de-stockage.md), [0057](../ARCH-0057-finaliser-les-frontieres-hexagonales.md), [0058](../ARCH-0058-declarer-les-usages-de-modules.md)
- Garde : `scripts/architecture/check-module-boundaries.py` ; description dans `docs/developer/quality-gates.md`
- Plan : [chantier stockage et frontières hexagonales](../../../project/roadmap/storage-hexagonal-2026-10/README.md)
