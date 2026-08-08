# Synthèse exécutive — Documentation d'architecture MINOS Code Intelligence

Date de production : 2026-08-06  
Version analysée : 1.0.1-SNAPSHOT (branche `develop`, HEAD `a0ed8ab`)

---

## Ce que le dépôt révèle avec certitude

**MINOS Code Intelligence** est un moteur local-first de Code Intelligence multi-langages,
distribué en Java 24, structuré en reactor Maven multi-module de 13 projets (12 enfants + parent).

Son architecture est **hexagonale, capabilist et fail-closed** :

- `minos-domain` est le cœur sans dépendance externe.
- `minos-engine` définit les ports (interfaces) provider-indépendants.
- Les adapters (`minos-provider-scip`, `minos-integration-git`, `minos-storage-local`, `minos-storage-postgresql`) implémentent ces ports.
- Les surfaces (`minos-cli`, `minos-mcp`, `minos-api`, `minos-nexus`) consomment les services applicatifs via `minos-application`.
- `minos-app` est le seul composition root et produit le shaded JAR distribué.

37 ADR documentent chaque décision structurante depuis le jalon C0 jusqu'à M29.
La direction de dépendances est imposée par Maven comme garde-fou de compilation.

---

## Checklist de complétude

| Élément | Statut |
|---------|--------|
| Section 1 — Introduction et objectifs | ✅ Complet |
| Section 2 — Contraintes | ✅ Complet |
| Section 3 — Contexte + diagramme C4 Context | ✅ Complet |
| Section 4 — Stratégie de solution | ✅ Complet |
| Section 5 — Vue blocs + C4 Container + C4 Component (application) | ✅ Complet |
| Section 6 — Vue d'exécution (3 scénarios) | ✅ Complet |
| Section 7 — Vue de déploiement (natif + Docker) | ✅ Complet |
| Section 8 — Concepts transverses (12 sous-sections) | ✅ Complet |
| Section 9 — Décisions (index de 37 ADR) | ✅ Complet |
| Section 10 — Exigences qualité (7 scénarios) | ✅ Complet |
| Section 11 — Risques et dette (8 risques, 5 dettes) | ✅ Complet |
| Section 12 — Glossaire | ✅ Complet |
| Template ADR | ✅ Produit |
| Diagramme dépendances Maven (classDiagram) | ✅ Produit |
| Registre des risques | ✅ Produit |
| Scénarios qualité manquants identifiés | ✅ Produit |

---

## ADR à créer (manquants)

Les ADR suivants couvrent des comportements observés dans le code mais ne possèdent pas encore d'ADR dans `docs/adr/` :

| Proposition | Justification | Priorité |
|------------|--------------|---------|
| ADR-0038 — Shaded JAR comme artefact distribué unique | La décision de bundler en shaded JAR vs modules JPMS vs GraalVM native n'est pas formalisée | Moyenne |
| ADR-0039 — Absence de framework serveur (SLF4J NOP en mode natif) | La décision explicite de ne pas logger en mode natif standard est implicite | Faible |
| ADR-0040 — Format de codec snapshot v2 | La décision d'évoluer du codec v1 au v2 et ses invariants de compatibilité ne sont pas dans un ADR | Haute |

---

## Scénarios qualité manquants

Voir [quality/scenarios.md](quality/scenarios.md) — section « Scénarios manquants identifiés » :

- SM-1 : Chiffrement tenant illisible sans clé
- SM-2 : Rotation de clé tenant sans perte d'audit
- SM-3 : Indexation incrémentale sur fichier unique
- SM-4 : Requête MCP après redémarrage sans snapshot
- SM-5 : Export NEXUS sur snapshot vide

---

## Incohérences détectées

| # | Incohérence | Gravité |
|---|------------|---------|
| I-1 | ADR-0036 est à l'état `Proposed` dans l'index mais le code semble déjà en tenir compte (fail-closed). Vérifier si l'ADR doit passer à `Accepted`. | Faible |
| I-2 | La dépendance `minos-mcp → minos-cli` transitoire mentionnée dans ADR-0022 (DT-01) est-elle encore présente après M15-S4 ? À vérifier dans le code courant. | Moyenne |
| I-3 | `minos-storage-postgresql` dépend de `minos-application` — **confirmé intentionnel** : le module PostgreSQL implémente `StorageBackend`, `ProjectRegistry` et `ProjectFingerprintSnapshotStore` qui sont des ports définis dans `minos-application`. C'est une décision de concevoir le backend PostgreSQL comme remplacement de toute la couche locale, pas seulement du port engine. Aucune action requise, mais l'ADR-0025 devrait mentionner explicitement ce niveau. | Faible (documentaire) |
| I-4 | ADR-0037 indique « parité Docker pending » mais aucune date cible précise n'est donnée pour S2–S8. Le registre des risques R-01 est daté M29-S8 sans date calendaire. | Faible |

---

## Plan de migration priorisé

| Priorité | Action | Jalon cible |
|---------|--------|------------|
| P1 | Documenter explicitement dans ADR-0025 que `minos-storage-postgresql → minos-application` est intentionnel (niveau d'abstraction StorageBackend) | M30 |
| P2 | Mettre en place CI automatique de PR (DT-02 / R-02) | M15-S10 / M30 |
| P3 | Supprimer le routage transitoire `minos-mcp → minos-cli` (DT-01) | M15-S4 |
| P4 | Créer ADR-0040 sur le format codec snapshot v2 | M30 |
| P5 | Compléter la parité Docker (R-01) | M29 S2–S8 |
| P6 | Ajouter les 5 scénarios qualité manquants | M30 |
| P7 | Finaliser et accepter ADR-0036 | M30 |

---

## Contrôles CI recommandés

| Contrôle | Outil | Bénéfice |
|---------|-------|---------|
| Vérification des dépendances Maven interdites | `maven-enforcer-plugin` + règle custom | Empêche les violations de frontière |
| Analyse statique des dépendances de packages | ArchUnit (test JUnit) | Renforce les règles ADR-0022 à l'intérieur des modules |
| Couverture de code seuil | JaCoCo `jacoco:check` | Maintient la couverture au niveau des jalons |
| Test de fumée MCP | Failsafe `minos-app` | Garantit le handshake MCP après chaque build |
| Test de replay M14 | Failsafe `minos-app` | Garantit la rétrocompatibilité CLI |
| Validation des snapshots | Test `SnapshotIntegrityServiceTest` | Prévient les régressions de codec |
| Lint Mermaid sur les diagrammes d'architecture | Action GitHub `mermaid-js/mermaid-action` | Garantit que les diagrammes sont syntaxiquement valides |

---

## Hypothèses à valider

| Hypothèse | Preuve requise |
|-----------|---------------|
| La dépendance `minos-storage-postgresql → minos-application` est intentionnelle | **Confirmé** : `PostgresStorageBackend` implémente `StorageBackend` (package `com.minos.storage`, dans `minos-application`). `PostgresProjectRegistry` implémente `ProjectRegistry` et `PostgresFingerprintSnapshotStore` implémente `ProjectFingerprintSnapshotStore`. |
| ADR-0036 est déjà implémenté dans la codebase courante | Grep sur `fail-closed` et conditions de validation dans `minos-app` |
| La parité fonctionnelle Docker (indexation) sera atteinte avant M29-S8 | Résultats des jalons S2–S7 |
| Le protocole CLI JSON IntelliJ est stable et testé | Présence de tests dans `minos-cli` ou `minos-api` |
