# Tâches exécutables — stockage et frontières hexagonales

Date : 2026-10-05. Base d'observation : `develop` à `c9a339088f81b6b31c65c7ad12bc718be2a98a7b`.

Toutes les tâches sont **À faire**. P1/P2 indiquent un ordre de travail architectural, pas une nouvelle sévérité de vulnérabilité. Les chemins désignent la base observée ; appliquer la table de migration de SH-01 après déplacement.

[Backlog et démarrage](README.md) · [ADR 0055](../../adr/0055-unifier-les-adaptateurs-de-stockage.md) · [ADR 0056](../../adr/0056-ports-de-lecture-des-snapshots.md) · [ADR 0057](../../adr/0057-finaliser-les-frontieres-hexagonales.md)

## SH-01 — Établir la baseline et la cartographie de migration

- État : À faire
- Priorité : P1
- Dépendances : aucune
- Point d'entrée : `pom.xml`, `scripts/architecture/check-module-boundaries.py`, `docs/audit/archive/2026-09/ARCHI-SUIVI.md`, `docs/adr/0042-racine-de-composition.md`

### Actions

- [ ] Relever le SHA de develop, les PR concurrentes #331/#332/#333 et leur état ; ne pas intégrer une PR non fusionnée implicitement.
- [ ] Inventorier tous les consommateurs des deux coordonnées Maven, test-jars, packages, services SPI, codecs, commandes -pl, couverture, scripts vivants et références documentaires.
- [ ] Capturer les hashes des golden de caractérisation, les fixtures V1/V2/V3, les versions et résultats baseline ; distinguer tests exécutés, indisponibles et déjà rouges.
- [ ] Produire une table ancien module/package → destination et un ordre de commits ; décider si les anciennes coordonnées nécessitent des POM de relocation.

### Critères d'acceptation

- [ ] La cartographie nomme chaque consommateur et son action ; aucune classe/ressource de service oubliée.
- [ ] La baseline et les limites d'environnement sont consignées ; pas de régénération des golden ni de clôture automatique de l'audit.

### Vérification

python scripts/architecture/check-module-boundaries.py ; python scripts/docs/check-current-docs.py ; ./mvnw clean verify selon la toolchain du dépôt.

## SH-02 — Fusionner les artefacts local et PostgreSQL dans minos-storage

- État : À faire
- Priorité : P1
- Dépendances : SH-01
- Point d'entrée : `pom.xml`, `minos-storage-local/pom.xml`, `minos-storage-postgresql/pom.xml`, `minos-bootstrap/pom.xml`, `minos-provider-scip/pom.xml`, `minos-app/pom.xml`, `scripts/architecture/check-module-boundaries.py`

### Actions

- [ ] Créer minos-storage et déplacer sources, tests et ressources sans renommer les packages backend ; dédoublonner uniquement après comparaison.
- [ ] Réunir dépendances, configuration Surefire, test-jar et profil Windows/Testcontainers ; maintenir minos.postgresql.tests.required et son mode strict CI.
- [ ] Réécrire tous les POM consommateurs et le reactor ; remplacer exactement les deux nœuds storage dans la politique de frontières et ses auto-tests, sans autoriser application/surfaces → adaptateurs.
- [ ] Mettre à jour les chemins des scripts vivants, couverture agrégée, image Docker, installation, packaging, SBOM et services fusionnés du shaded JAR.
- [ ] Conserver StorageBackendSelection dans minos-bootstrap et les IDs local/postgresql ; documenter l'effet des dépendances JDBC transitives.

### Critères d'acceptation

- [ ] Un unique artefact storage est construit, aucune classe dupliquée, aucun package partagé entre modules, services présents une seule fois dans le JAR.
- [ ] Le backend local démarre et opère sans PostgreSQL/Docker ; PostgreSQL ne se connecte que lorsqu'il est sélectionné.
- [ ] Les deux suites de stockage, les consommateurs et les auto-tests de frontières passent ; aucun seuil de qualité abaissé.

### Vérification

./mvnw -pl minos-storage,minos-bootstrap,minos-app -am verify ; garde modules et ses auto-tests ; scénario local sans serveur ; PostgreSQL strict sur hôte Docker.

## SH-03 — Extraire le codec commun et interdire les dépendances entre backends

- État : À faire
- Priorité : P1
- Dépendances : SH-02
- Point d'entrée : `minos-storage-local/src/main/java/com/minos/storage/local/store/KnowledgeSnapshotCodecs.java`, `minos-storage-postgresql/src/main/java/com/minos/storage/postgresql/PostgresSnapshotPayloadCodec.java`, `docs/adr/0046-format-de-snapshot-v3-chaines-utf8.md`

### Actions

- [ ] Après fusion, cartographier puis déplacer les types de codec réellement partagés vers com.minos.storage.codec, avec leurs tests.
- [ ] Conserver les responsabilités fichiers locales hors codec ; aucun accès à JDBC ou au backend local depuis le codec.
- [ ] Remplacer les imports PostgreSQL vers local.store ; préserver magic, versions, hash logique, plafonds, repli V2 des surrogates et compatibilité V1/V2/V3.
- [ ] Ajouter une garde package local ↛ postgresql et postgresql ↛ local, codec ↛ backends ; prouver qu'une violation synthétique est rejetée.

### Critères d'acceptation

- [ ] Les fixtures historiques se lisent depuis les deux backends ; les bytes et identités encodés restent conformes au contrat existant.
- [ ] Aucun import backend croisé ; aucun besoin d'un module Maven codec supplémentaire.

### Vérification

Tests existants de codecs, allocation, snapshots autoritatifs, ré-import et compaction ; auto-test négatif de la règle de packages.

## SH-04 — Introduire un port de lecture neutre pour SnapshotQueryView

- État : À faire
- Priorité : P1
- Dépendances : SH-01
- Point d'entrée : `minos-engine/src/main/java/com/minos/store/SnapshotQueryView.java`, `minos-engine/src/main/java/com/minos/store/CodeKnowledgeStore.java`, `minos-engine/src/main/java/com/minos/store/InMemoryCodeKnowledgeStore.java`

### Actions

- [ ] Recenser les appels à queryStore(), indexMetrics(), les constructions de la vue et les consommateurs Java potentiels.
- [ ] Définir CodeKnowledgeReader et des métriques neutres minimales ; séparer l'interface de construction/mutation de celle de lecture.
- [ ] Consigner avant code le plan de compatibilité des records et signatures ; privilégier une transition additive, sans remplacement binaire silencieux.
- [ ] Migrer les cas d'usage de lecture, garder le snapshot autoritatif et vérifier identité/immutabilité logique pendant une promotion concurrente.

### Critères d'acceptation

- [ ] Un double non mémoire peut servir les requêtes ; les consommateurs de lecture n'ont pas besoin de la classe concrète ni de mutateurs.
- [ ] Résultats et budgets inchangés ; compatibilité vérifiée ou rupture explicitement versionnée et approuvée avant retrait.

### Vérification

Tests query/context/architecture/impact, tests de cohérence snapshot concurrente et consommateur Java compilé avant migration.

## SH-05 — Déplacer le workflow d'indexation de la CLI dans un cas d'usage

- État : À faire
- Priorité : P1
- Dépendances : SH-01
- Point d'entrée : `minos-cli/src/main/java/com/minos/cli/LocalAutonomousIndexOperations.java`, `minos-application/src/main/java/com/minos/application`, `minos-bootstrap/src/main/java/com/minos/bootstrap`

### Actions

- [ ] Extraire coordination préparation/exécution/reprise/empreintes/sémantique/rétention dans un cas d'usage applicatif injecté.
- [ ] La CLI conserve arguments, DTO de surface, formats et codes de sortie ; aucune nouvelle exposition d'écriture MCP.
- [ ] Préserver portée des baux, ordre des effets, traitement d'un commit incertain, NO_CHANGES et arrêt/interruption.
- [ ] Réutiliser les ports et bootstrap existants ; ne pas réintroduire un adaptateur dans application.

### Critères d'acceptation

- [ ] Le workflow est invocable dans un test sans CLI ; la CLI délègue au même chemin de production.
- [ ] Les golden restent byte-identiques et les tests reprise/crash/empreintes/verrous passent ; aucune double exécution.

### Vérification

Caractérisation A2 et golden, tests d'indexation/reprise existants ; scripts/quality/check-single-execution.py ; garde modules.

## SH-06 — Découpler le cycle SCIP de la persistance locale concrète

- État : À faire
- Priorité : P2
- Dépendances : SH-03, SH-05
- Point d'entrée : `minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/ScipProjectSnapshotLifecycle.java`, `minos-engine/src/main/java/com/minos/orchestration`, `minos-bootstrap/src/main/java/com/minos/bootstrap`

### Actions

- [ ] Inventorier créations FileSymbolSnapshotStore pour providers, staging et promotion ; définir les ports minimaux correspondants.
- [ ] Injecter les implémentations depuis bootstrap ; conserver nettoyage, chemins autorisés et limites d'artefacts.
- [ ] Faire rester conversion et normalisation SCIP dans provider ; retirer sa dépendance au module storage si plus aucun usage légitime ne subsiste.
- [ ] Préserver la publication atomique et la reprise ; aucune dépendance provider → bootstrap/application.

### Critères d'acceptation

- [ ] Tests du provider exécutables avec doubles des ports ; les tests intégrés confirment le même backend et les mêmes résultats.
- [ ] Graphe Maven sans dépendance inutile provider → storage ; absence d'inversion de dépendance cachée.

### Vérification

Tests ScipProjectSnapshotLifecycle, import/promotion multi-provider, reprise et invariants du reactor.

## SH-07 — Isoler l'adaptateur Ollama de la configuration applicative

- État : À faire
- Priorité : P2
- Dépendances : SH-01
- Point d'entrée : `minos-application/src/main/java/com/minos/application/semantic/OllamaEmbeddingProvider.java`, `minos-application/src/main/java/com/minos/application/semantic/EmbeddingProvider.java`, `minos-application/src/main/java/com/minos/application/MinosApplicationRuntimeConfiguration.java`, `minos-bootstrap/src/main/java/com/minos/bootstrap`

### Actions

- [ ] Définir la frontière d'adaptateur (package technique bootstrap ou module dédié si isolation justifiée) et documenter son graphe avant déplacement.
- [ ] Déplacer HTTP/Ollama et sa construction hors application ; conserver port, modèles et algorithmes de ranking côté cœur/cas d'usage.
- [ ] Préserver clés de configuration, opt-in, refus des endpoints non autorisés, timeouts, bornes, dimensions et redaction.
- [ ] Coordonner avec l'étude #333 ; aucune modification du modèle, des scores ou des providers par défaut dans cette extraction.

### Critères d'acceptation

- [ ] Les cas d'usage se testent sans endpoint HTTP ; l'assemblage réel sélectionne le même fournisseur.
- [ ] Tests de validation et limites Ollama conservés ; sorties et comportement sans fournisseur identiques.

### Vérification

Tests Ollama existants déplacés, tests de bootstrap/provider absent et tests hybrid/semantic inchangés.

## SH-08 — Séparer progressivement politiques et mécanismes I/O du moteur

- État : À faire
- Priorité : P2
- Dépendances : SH-01
- Point d'entrée : `minos-engine/src/main/java/com/minos/io`, `scripts/architecture/check-private-io.py`, `scripts/architecture/private-io-allowlist.json`, `docs/audit/archive/2026-09/SEC-SUIVI.md`

### Actions

- [ ] Cartographier chaque primitive, consommateur, contrat public, chargeur SPI et responsabilité de sécurité ; relier au résidu A5.
- [ ] Produire un graphe de destination minimal : garder politiques et ports dans le cœur, regrouper mécanismes durcis dans une frontière technique cohérente.
- [ ] Si un nouveau module ou une rupture est indispensable, écrire l'amendement ADR précis avant code ; ne pas déplacer à l'aveugle tout com.minos.io.
- [ ] Procéder par petits déplacements avec preuve négative des gardes ; préserver ACL, jonctions, TOCTOU, verrous bornés et annulation.

### Critères d'acceptation

- [ ] Cartographie examinable avant extraction ; chaque déplacement protège les garanties existantes et n'introduit aucun cycle.
- [ ] La liste blanche I/O n'augmente pas pour contourner une régression ; aucun claim de sécurité ajouté sans qualification OS.

### Vérification

Garde private-io et ses auto-tests ; tests ciblés de primitives sous Windows/Linux ; garde modules.

## SH-09 — Clarifier les capacités applicatives et les façades publiques

- État : À faire
- Priorité : P2
- Dépendances : SH-04, SH-05
- Point d'entrée : `minos-application/src/main/java`, `minos-api/src/main/java`, `minos-nexus/src/main/java/com/minos/nexus/NexusExportService.java`, `minos-mcp/src/main/java`, `minos-intellij/src/main/java`

### Actions

- [ ] Cartographier indexation/recherche/analyse/sémantique/runtime observé/hosted et leurs dépendances ; éviter un module Maven par package.
- [ ] Définir des façades de capacité ciblées ; faire produire les données d'export NEXUS par un cas d'usage, garder traduction JSON dans l'adaptateur.
- [ ] Maintenir API Java, schémas MCP, codes CLI et protocole IDE ; ne pas créer de nouvelles commandes ni modifier les résultats partiels.
- [ ] Évaluer séparément l'intérêt d'un artefact API de contrats : consigner la décision sans imposer sa scission dans ce lot.

### Critères d'acceptation

- [ ] Les surfaces utilisent les cas d'usage sans lire un backend concret ; responsabilités et dépendances documentées.
- [ ] Golden, contrat Java/NEXUS/MCP et plugin restent compatibles ; aucune nouvelle capacité revendiquée.

### Vérification

Tests de contrat existants API/NEXUS/MCP/CLI ; compilation et vérification plugin pour les changements de protocole éventuels.

## SH-10 — Aligner la découverte SPI dans les hôtes Java embarqués

- État : À faire
- Priorité : P2
- Dépendances : SH-02
- Point d'entrée : `minos-bootstrap/src/main/java/com/minos/bootstrap/StorageBackendSelection.java`, `minos-application/src/main/java/com/minos/application/MinosApplicationComposers.java`, `minos-engine/src/main/java/com/minos/io/StableFileSystemIdentity.java`, `docs/adr/0042-racine-de-composition.md`

### Actions

- [ ] Reprendre le résidu de chargeur contextuel documenté dans ADR 0042 et ARCHI-SUIVI (A8), sans le déclarer corrigé par la fusion.
- [ ] Choisir un chargeur explicite cohérent avec la composition et les extensions supportées ; qualifier hôte avec TCCL différent.
- [ ] Définir diagnostics provider absent/ID dupliqué et maintien du backend local ; préserver le refus fermé.
- [ ] Tester les services dans le JAR ombré, pas seulement dans le classpath Maven.

### Critères d'acceptation

- [ ] Composer et backend visibles de façon déterministe sous le chargeur supporté ; pas de sélection arbitraire en cas de doublon.
- [ ] Tests hôte embarqué et SPI packagé couvrent absence, doublon et sélection local/PostgreSQL.

### Vérification

Tests classloader isolé, packaging shaded et services ; aucune connexion DB nécessaire à la simple découverte.

## SH-11 — Renforcer les règles d'architecture et intégrer les nouveaux chemins CI

- État : À faire
- Priorité : P1
- Dépendances : SH-01
- Point d'entrée : `scripts/architecture/check-module-boundaries.py`, `scripts/architecture/test_check_module_boundaries.py`, `.github/workflows/pr-ci.yml`, `scripts/quality/check-jacoco.py`, `scripts/docs/product-facts.py`

### Actions

- [ ] Définir avant fusion les règles de packages et leurs contre-exemples ; implémenter leur adaptation dans les PR SH-02/03 puis étendre pour SH-04 à SH-09.
- [ ] Conserver application/surfaces ↛ adaptateurs, un package/un module, comparaison au reactor et règles I/O.
- [ ] Compléter avec analyse de classes/ArchUnit si un risque concret échappe au checker ; choisir une version compatible via documentation officielle lors de l'implémentation.
- [ ] Mettre à jour tous chemins exécutables et seuils associés sans les abaisser, garder le pipeline unique ; ne pas éditer les archives historiques gelées.
- [ ] Documenter quels checks sont seulement exécutés et lesquels sont réellement requis par le ruleset ; aucune modification de permissions dans ce lot.

### Critères d'acceptation

- [ ] Chaque règle nouvelle possède un contre-exemple rejeté ; aucun module/package échappe au contrôle après fusion.
- [ ] Les chemins CI/JaCoCo/produit correspondent au nouveau reactor ; chaque PR reste vérifiable isolément.

### Vérification

Auto-tests Python, garde modules, garde private-io, check-single-execution, check-current-docs, product-facts --check.

## SH-12 — Qualifier la migration complète et livrer le guide de reprise

- État : À faire
- Priorité : P1
- Dépendances : SH-02, SH-03, SH-04, SH-05, SH-06, SH-07, SH-08, SH-09, SH-10, SH-11
- Point d'entrée : `docs/architecture/diagrams/module-dependencies.md`, `docs/architecture/arc42`, `docs/developer`, `docs/user`, `docs/ROADMAP.md`, `docs/STATUS.md`, `docs/roadmap/storage-hexagonal-2026-10`

### Actions

- [ ] Régénérer le diagramme Maven depuis les POMs réellement modifiés ; actualiser arc42, guide développeur et migration des coordonnées.
- [ ] Qualifier local sans DB, PostgreSQL strict, reprise/promotion/concurrence, index sémantique, native/Docker et consommateurs publics selon les zones touchées.
- [ ] Exécuter sous Windows et Linux les scénarios dépendant de l'OS ; consigner SHA, commandes, environnement et limites dans VALIDATION.md.
- [ ] Mettre à jour tâches/ADR comme implémentés uniquement sur preuves ; conserver les écarts restants nommés. Livrer un prompt de reprise et le plan de rollback.

### Critères d'acceptation

- [ ] Tous les critères des lots sont reliés à des preuves ; aucune qualification PostgreSQL silencieusement sautée.
- [ ] Les golden historiques sont inchangés ; la documentation décrit le code livré et distingue les tâches différées.
- [ ] Une réversion du code de refactoring conserve les données lisibles, car aucun format de stockage n'a changé.

### Vérification

./mvnw clean verify ; PostgreSQL avec -Dminos.postgresql.tests.required=true sur hôte Docker ; gardes documentaires/architecture ; smoke du JAR packagé.


