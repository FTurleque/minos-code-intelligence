# Design

## Context

Sprint 10 de l'audit du 10 octobre 2026. HEAD analysé : `949e1569` (`develop`). Les décomptes et les sites des constats sont dans le design de `figer-la-dette-de-frontieres-par-des-cliquets` ; ce document ne porte que sur les **décisions** et l'**ordre**.

## État vérifié au HEAD

### Statut des ADR concernés (index `docs/architecture/adr/README.md`)

| ADR | Statut | Portée pour le sprint |
|---|---|---|
| 0022 | Accepted | « moteur → runtime process local concret » doit devenir impossible. Le texte vise une dépendance de **module** ; l'audit l'étend à l'usage de `ProcessBuilder`. |
| 0042 | Accepted | Le source de `minos-app` ne nomme aucun adaptateur (vérifié : 0 import d'adaptateur). |
| 0044 | Accepted | A placé `hosted` dans le moteur (« seul module capable d'accueillir les trois côtés »), laissé `NexusExportBridgeMain` dans `minos-app` « sans changer de nom », et préfère le déplacement à nom qualifié constant. |
| 0047 | **Accepted** (option A mise en œuvre le 2026-10-13, mesurée sur corpus réel le 2026-10-15 ; B et C conditionnelles) | Étape 1 (AUD-PERF-03) et option A livrées. |
| 0055, 0056 | Accepted, à implémenter | SH-01 à SH-12 tous « À faire ». |
| 0057 | Accepted, travaux résiduels | § 7 autorise des gardes au grain classe. |
| 0058 | Accepted | § 3 (justifier tout nouvel usage) est sans garde aujourd'hui. |

**Relecture (tâche 1.1, 2026-10-22)** : ADR 0044, 0047, 0055 à 0058 relus en entier ; ADR 0022 et 0042 relus sur les sections citées (frontières, `minos-app`, invariants) ; plan du chantier relu (`README.md`, backlog SH). Seule affirmation infirmée : le statut de l'ADR 0047, corrigé ci-dessus (le registre arc42 `09-decisions.md` le donnait encore « Proposed »). Les écarts 2 à 5 sont confirmés.

**Consommateurs du pont NEXUS (tâche 1.2)** : dans ce dépôt, `com.minos.integration.nexus.NexusExportBridgeMain` est nommé par la documentation (`docs/user/java-api.md:337`, liste des points d'entrée de processus qui gardent leur nom ; `docs/developer/README.md:91` ; `docs/architecture/arc42/05-vue-blocs.md:125`), par les ADR 0022 et 0044 et par des scripts d'historique M15. La copie locale du dépôt NEXUS (`nexus-context-engine`, `6563c2f3` du 2026-10-17) ne nomme ni cette classe ni aucun type `com.minos` : NEXUS consomme le contrat JSON (ADR 0020). La contrainte qui s'oppose au renommage est le contrat documenté de MINOS, pas un consommateur connu.

### AUD-ARC-07 — confirmé

`minos-storage-local/src/main/java/com/minos/storage/local/store/FileSymbolSnapshotStore.java:279` et `minos-storage-postgresql/src/main/java/com/minos/storage/postgresql/snapshot/PostgresCodeKnowledgeSnapshotStore.java:145` (l'audit citait `:142`, ancien package) reconstruisent un `InMemoryCodeKnowledgeStore` complet par snapshot lu. ADR 0047 accepté et mis en œuvre pour l'option A ; ADR 0056 partiellement mis en œuvre (`activeSnapshotId`), port de lecture SH-04 non commencé.

### Écarts avec l'action proposée par l'audit

1. **AUD-ARC-07 — « trancher 0047 avant SH-04 » est une lecture de l'audit, pas une décision du dépôt.** L'ADR 0055 dit que 0047 « demeure conditionnel et indépendant », et l'ADR 0056 n'annonce aucun gain de performance. L'ADR 0047 est déjà tranché pour son option A : `ramener-le-poids-des-vues-a-la-mesure` (AUD-PERF-03) l'a mise en œuvre et mesurée. Reste le port de lecture (SH-04), qui n'attend plus aucune mesure.
2. **AUD-ARC-05 — déplacer `hosted` vers `minos-application` contredit l'ADR 0044 § 2** ; `minos-storage-local` l'importe et ne peut pas dépendre de l'application. Et extraire le mécanisme d'ACL n'est pas un déplacement : `PrivateLocalStorage` est nommé par 9 modules et la primitive est statique.
3. **AUD-ARC-11 — les deux actions sont infaisables telles quelles** : le transport dans `minos-mcp` créerait `minos-mcp → minos-cli` (arête supprimée en M15-S4) ; le renommage du pont NEXUS casse un point d'entrée documenté. Les consommateurs externes de ce point d'entrée (dépôt NEXUS) ne sont pas vérifiables d'ici.
4. **AUD-ARC-12 — renommer contredit l'ADR 0044** et coûterait des gates littéraux, des scopes JaCoCo et du code « neuf » pour SonarCloud. Aucun package n'est partagé entre modules (règle A3 verte).
5. **AUD-ARC-01 et AUD-ARC-08 n'appellent pas de décision nouvelle** : les ADR 0055, 0057 et 0058 ont tranché ; il manque les prérequis et l'exécution.

## Goals / Non-Goals

**Goals** : quatre décisions prises et écrites ; un ordre de spécification des chantiers ; rien de décidé implicitement par une tâche d'implémentation.

**Non-Goals** : implémenter ; rédiger les ADR pendant la spécification ; rouvrir les ADR 0055 à 0058.

## Decisions

### D1. ADR 0047 — **décidé le 2026-10-22 : option A (ordre de l'ADR confirmé)**

- **Option A (retenue)** : l'accepter avec son ordre propre — étape 1 = AUD-PERF-03, puis mesure du tas d'un snapshot relu, puis option A de l'ADR (internement des chaînes au décodage). L'ADR était déjà accepté et cette séquence livrée et mesurée (section « Mise en œuvre » de l'ADR) ; la décision confirme l'ordre et les critères de B et C, consignés dans l'ADR le 2026-10-22.
- **Option B** : le laisser « Proposed » avec un critère de révision daté (par exemple : à la clôture d'AUD-PERF-03).
- **Option C** : le rejeter et assumer la borne par le tas (à documenter comme limite du produit).

### D2. Mécanisme d'ACL Windows et `hosted` — **décidé le 2026-10-22 : recommandation retenue (ADR 0062)**

- **Recommandation** : (a) un port d'E/S de plateforme pour le mécanisme d'ACL, fourni par `minos-runtime-local` et injecté, conçu **avec** AUD-SEC-06 (qui réécrit ce code) et pas avant ; (b) `hosted` **reste** dans le moteur (ADR 0044 maintenu) tant que SH-08 n'a pas sa cartographie.
- **Alternative** : amender l'ADR 0044 pour sortir `hosted` vers un module dédié. Coût : `minos-storage-local` en dépend ; gates littéraux (`check-private-io.py` et sa liste, `check-p0-p2.py`, `scripts/quality/check-hosted-control-plane-consistency.py`, scope JaCoCo, baseline SpotBugs).

### D3. `minos-app` — **décidé le 2026-10-22 : recommandation retenue (ADR 0062)**

- **Recommandation** : maintenir l'ADR 0044 (transport MCP Docker et pont NEXUS restent dans l'assemblage) et **documenter** l'arête d'exécution `minos-cli` → `minos-app`, ce que fait le changement des cliquets.
- **Alternative** : déplacer le service de route MCP dans un module de lancement neuf. Contraire à « ne jamais créer un module pour reproduire un package » sauf justification par ADR.

### D4. Nommage des packages — **décidé le 2026-10-22 : recommandation retenue (ADR 0062)**

- **Recommandation** : une convention pour les **nouveaux** packages seulement (préfixe par couche ou par module), écrite dans l'ADR et dans `.claude/rules/`, gardée par la règle A14 du changement des cliquets ; aucun renommage de l'existant.
- **Alternative** : renommage progressif à l'occasion des déplacements SH-01 / SH-02. À éviter pour les packages publics de `minos-api`.

### D5. Ordre de spécification des chantiers

| Chantier | Constat | Prérequis | À spécifier quand |
|---|---|---|---|
| Surfaces consommant des cas d'usage (suite de SH-05, SH-09) | AUD-ARC-01 | AUD-ARC-02 (`unifier-l-orchestration-d-indexation`, fait le 2026-10-21) | maintenant |
| Port d'ACL de plateforme (SH-08) | AUD-ARC-05 | AUD-SEC-06 (fusionné, PR #491 ; reste la mesure du nombre d'objets durcis), D2 (décidé) | avec cette mesure |
| Port de lecture des snapshots (SH-04) | AUD-ARC-07 | AUD-PERF-03 (fait), D1 (décidé) | maintenant |
| Fusion `minos-storage`, codec commun, staging injecté (SH-02, SH-03, SH-06) | AUD-ARC-08 | SH-05 pour SH-06 | après SH-05 |
| Documentation de l'arête, convention de nommage | AUD-ARC-11, AUD-ARC-12 | D3, D4 | couverts par les cliquets ; rien d'autre sans décision contraire |

Chaque chantier déplace des classes : code « neuf » pour SonarCloud (80 % par module), tests à déplacer avec la classe, scopes de `check-jacoco.py`, gates littéraux et baselines SpotBugs à réécrire. Ce coût est à écrire dans chaque spécification future.

## Direction des dépendances

Aucune arête modifiée. Les options recommandées maintiennent la direction de l'ADR 0022 et les décisions de l'ADR 0044.

## Qualification des capacités

Sans objet.

## Windows et Linux

Sans objet pour les décisions ; D2 concerne un mécanisme propre à Windows.

## Risks / Trade-offs

- Reporter les chantiers laisse la dette en place : elle est figée par les cliquets, et visible.
- Une décision prise sans la mesure d'AUD-PERF-03 (D1, option A acceptée d'emblée) engage un ordre, pas un résultat : l'ADR doit dire à quelle mesure son étape suivante est conditionnée.
- Si le propriétaire choisit une alternative (sortir `hosted`, déplacer le service MCP, renommer), le chantier correspondant change de taille : à respécifier alors.
