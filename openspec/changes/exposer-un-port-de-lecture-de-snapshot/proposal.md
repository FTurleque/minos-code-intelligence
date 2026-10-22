# Proposal

## Why

Constat **AUD-ARC-07** (audit du 10 octobre 2026, sprint 10), tâche **SH-04** du chantier `storage-hexagonal-2026-10`, mise en œuvre de l'**ADR 0056**. Le prérequis est rempli : l'option A de l'ADR 0047 (dédoublonnage des chaînes au décodage) est livrée et mesurée sur corpus réel, et le propriétaire a confirmé l'ordre de l'ADR 0047 le 2026-10-22 (changement `trancher-les-decisions-d-architecture-du-sprint-10`, décision D1).

La vue de requête du snapshot actif expose sa structure de données concrète :

- le record public `SnapshotQueryView` (`minos-engine`, `com.minos.store`) a pour composant `InMemoryCodeKnowledgeStore queryStore` et rend `InMemoryCodeKnowledgeStore.IndexMetrics` par `indexMetrics()` ;
- `FileSymbolSnapshotStore` (`minos-storage-local`) et `PostgresCodeKnowledgeSnapshotStore` (`minos-storage-postgresql`) instancient chacun un `InMemoryCodeKnowledgeStore` complet par snapshot lu et calculent le poids de leur cache avec une méthode statique de cette classe ;
- quatre cas d'usage de lecture de `minos-application` passent par `queryStore()` et reçoivent un objet qui porte aussi les mutateurs `putSymbols`, `putOccurrences`, `putRelationships` ;
- le port `CodeKnowledgeStore` mêle ces écritures et les lectures ; les services de requête qui ne font que lire le reçoivent entier.

Aucune autre implémentation de lecture ne peut donc servir les requêtes, et rien n'empêche un cas d'usage de lecture d'écrire dans l'index d'une vue partagée.

## What Changes

- **Port de lecture neutre** dans `minos-engine` (nom retenu, D1 : `CodeKnowledgeReader`), limité aux lectures appelées par les consommateurs relevés au HEAD ; `CodeKnowledgeStore` en hérite et garde la voie d'écriture de l'ingestion. `InMemoryCodeKnowledgeStore` reste l'implémentation, et une optimisation interne.
- **Index des arêtes entrantes nommé par une interface neutre** : l'analyse d'impact n'importe plus le type imbriqué `InMemoryCodeKnowledgeStore.SymbolEdgeIndex`.
- **Migration additive du record public**, selon la règle de l'ADR 0056 et sur le modèle de `ReconciledState` : une nouvelle vue neutre (nom retenu, D1 : `SnapshotReadView`), une lecture `loadActiveReadView` sur le port `CodeKnowledgeSnapshotStore` (méthode `default` dérivée de l'ancienne), et `SnapshotQueryView.readView()`. `SnapshotQueryView`, `queryStore()`, `indexMetrics()` et `loadActiveQueryView` sont **conservés et dépréciés** (`@Deprecated(since = "1.3.0")`, sans retrait programmé) et rendent le même résultat qu'aujourd'hui.
- **Construction de l'index par le cœur** : les deux stores obtiennent la vue d'une fabrique du cœur et ne nomment plus `InMemoryCodeKnowledgeStore` ; le coût de l'index (une construction par vue, sur défaut de cache) est inchangé.
- **Cas d'usage de lecture migrés** : `ProjectQueryService`, `ImpactAnalysisService`, `LocalProjectImpactQuery`, `RuntimeIntelligenceService` lisent la vue neutre et n'ont plus accès aux mutateurs.
- **Cliquet de frontière** : une nouvelle règle A17 de `scripts/architecture/check-module-boundaries.py` refuse toute classe de production hors du package `com.minos.store` de `minos-engine` qui nomme `InMemoryCodeKnowledgeStore`, et limite à une liste fermée (les implémentations dépréciées des deux stores et la surcharge dépréciée de l'analyse d'impact) le nom `SnapshotQueryView` et les appels `loadActiveQueryView(` et `queryStore()`, qui atteignent la classe mémoire sans la nommer. L'extension du refus au port d'écriture `CodeKnowledgeStore` dans `minos-application` attend la décision D7.
- **Preuve de substituabilité** : un double de lecture non mémoire sert recherche, usages, relations, impact et rapport runtime dans des tests du cœur et de l'application.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `persistance-snapshots` : la vue de lecture du snapshot actif est servie par un port neutre, l'ancien contrat reste disponible et cohérent, et la cohérence pendant une promotion concurrente est conservée.
- `frontieres-de-modules` : un cliquet interdit de nommer la classe mémoire hors du cœur et borne l'usage de l'ancienne vue.

## Hors périmètre

- **Le coût de l'index en mémoire.** Les deux stores construisent toujours un index complet par vue sur défaut de cache (ADR 0024) ; l'ADR 0056 n'annonce aucun gain de performance. Réduire le tas ou supprimer cette construction relève des options B et C de l'ADR 0047, sous leurs critères de mesure.
- Le retrait de `SnapshotQueryView`, `queryStore()`, `indexMetrics()` et `loadActiveQueryView` : rupture à versionner explicitement (ADR 0056), non décidée ici.
- Les lectures du snapshot brut (`CodeKnowledgeSnapshot`, ses listes complètes) : 25 classes de production les consomment (ADR 0047, option C) ; la vue neutre continue de les exposer.
- La fusion des modules de stockage et le codec commun (SH-02, SH-03), le staging SCIP injecté (SH-06), la règle A13.
- Le cache du graphe de programme et la recherche hybride, qui ne passent pas par `queryStore()`.
- Toute mesure de durée ou de tas : les preuves sont des décomptes et des égalités de résultats.

## Impact

- **Modules du reactor** : `minos-engine` (`com.minos.store`, `com.minos.query`), `minos-storage-local`, `minos-storage-postgresql`, `minos-application` (`application`, `impact`, `context`, `application.dynamic`) ; tests de `minos-bootstrap` et `minos-app` qui construisent une vue. Aucune dépendance Maven nouvelle ; la direction `engine ← storage`, `engine ← application` est inchangée (ADR 0022).
- **Surfaces publiques** : aucune sortie CLI, MCP, API Java (`com.minos.api`), IntelliJ ni NEXUS ne change. `com.minos.store` n'appartient pas à l'API publiée (`docs/user/java-api.md`), mais ses types sont publics et publiés depuis la 1.2.0 : ajouts et dépréciations seulement pour la vue et le port de snapshot ; changement du type de paramètre de trois constructeurs de services de requête et de la méthode statique `ModuleFilterGuard.require`, décidé par D4 et listé dans `docs/user/java-api.md` § Ruptures.
- **ADR** : aucun nouvel ADR ; note de mise en œuvre dans l'ADR 0056 (nom final des types, migration additive) ; statut de l'ADR 0056 et du registre `arc42/09-decisions.md` mis à jour ; mention de la vue neutre dans `arc42` (`05-vue-blocs.md`, `08-concepts-transverses.md`, `12-glossaire.md`).
- **Gates à rejouer** : `scripts/architecture/check-module-boundaries.py` et ses auto-tests (nouvelle règle), `scripts/quality/check-jacoco.py` (scope `persistence-cache-indexes`, préfixes ajoutés ; scopes `m30-postgresql-pgvector`, `m26-runtime-dynamic-intelligence` et `global` rejoués), `scripts/ci/test_plugin_gate.py` (cite le chemin de `InMemoryCodeKnowledgeStore.java`, inchangé), `scripts/remediation/check-mne.py`, `check-mnd.py`, `check-p0-p2.py` (littéraux des deux stores, conservés), `check-runtime-dynamic-consistency.py` (`RuntimeIntelligenceService`), `python .claude/scripts/run_gates.py --fast`.
- **Plateformes** : Windows et Linux, sans comportement propre à l'une ; le store PostgreSQL n'est testé contre un serveur réel que sous Linux en CI (`-Dminos.postgresql.tests.required=true`).
