# 0044 — Un package, un module

Status: Accepted (2026-09-29) — mise en œuvre en cours sur la branche `archi/a3-packages`, suivie dans [`ARCHI-SUIVI.md`](../audit/ARCHI-SUIVI.md).

Complète l'audit [`AUDIT-2026-09.md`](../audit/AUDIT-2026-09.md) (constats A3 et A7). Amende l'[ADR 0022](0022-maven-reactor-and-module-boundaries.md) sur l'emplacement de `discovery`, `incremental`, `orchestration` et `MinosVersion`. Prolonge l'[ADR 0042](0042-racine-de-composition.md), dont il garde toutes les frontières.

## Contexte

Le reactor Maven (ADR 0022) a découpé MINOS en modules sans découper les packages : une même déclaration `package com.minos.x;` apparaît dans les sources de production de plusieurs modules. A2 (ADR 0042) a accentué l'éclatement en descendant des ports dans `minos-engine` « dans leur package » : sur la base `10486cb7`, **14 packages de production** sont déclarés par plusieurs modules (l'audit en comptait 9), et **45 fichiers de test** sont logés dans un package dont la production appartient à un autre module. Inventaire daté et détaillé : `ARCHI-SUIVI.md` § 2.

Trois conséquences :

1. **La visibilité « package » traverse les jars.** Le bytecode de la base montre quatre accès de production réels à des membres package-private d'un autre module (le lanceur vers `MinosCliRunner`, application et storage-local vers deux règles de format d'engine) et quatre depuis des tests. Rien ne les signale : ils ne tiennent qu'à l'égalité des noms de package.
2. **JPMS est inaccessible.** Un package ne peut appartenir qu'à un seul module nommé ; tant qu'il est éclaté, aucun `module-info.java` n'est possible, même en module automatique.
3. **Le nom d'un package ne dit plus où vit une classe.** `com.minos.store` désigne à la fois des ports d'engine et des adaptateurs fichiers ; `com.minos.runtime` à la fois la version du produit, des ports et l'exécution de processus.

A7 s'y ajoute : la liste des modules de `scripts/architecture/check-module-boundaries.py` est codée en dur et jamais confrontée aux `<modules>` du POM racine. Un module ajouté au reactor et oublié dans le script échappe à toutes ses règles.

## Décision

### 1. Principe

Chaque package Java appartient à **un seul module** : ses sources de production vivent dans ce module, et les tests déclarés dans ce package vivent dans ce module.

Un package éclaté se replie selon cet ordre de préférence :

1. **Déplacement** des classes dans le module le plus bas capable de les accueillir toutes sans violer la politique de dépendances (`ALLOWED_DEPENDENCIES`) ni les rôles A2. Le nom pleinement qualifié ne change pas : un consommateur Java ne voit aucune rupture, seul le jar qui porte la classe change.
2. Un **adaptateur ne descend jamais** dans `minos-engine` ou `minos-domain`.
3. Quand les deux côtés sont épinglés, on **renomme le côté interne** (adaptateurs, classes sans contrat externe), jamais un point d'entrée documenté. Le package renommé prend l'espace de noms de son module : `<espace-du-module>.<ancien-suffixe>` (par exemple `com.minos.storage.local.store`, cohérent avec `com.minos.storage.postgresql`).

Un accès package-private entre jars révélé par le repli se résout par déplacement (du code ou du test) ; toute visibilité élargie est justifiée par écrit dans le commit et dans le suivi.

### 2. Table des décisions

| Package éclaté | Décision |
|---|---|
| `discovery`, `discovery.spi`, `incremental`, `orchestration` (côté application, 31 classes) | **déplacés** dans `minos-engine` : fermeture close sur engine et domain |
| `hosted` (application 18, domain 10) | **déplacés** dans `minos-engine`, seul module capable d'accueillir les trois côtés |
| `runtime` | `MinosVersion` **déplacé** d'application dans engine ; les 34 classes de runtime-local **renommées** `com.minos.runtime.local` (ressources et fichier de service compris) |
| `dynamic` | les 2 services d'application **renommés** `com.minos.application.dynamic` ; le port `RuntimeObservationStore` déclare `IOException`, il ne descend pas dans domain : les 9 records de domain **montent** dans engine |
| `semantic` | les 10 services d'application **renommés** `com.minos.application.semantic` ; domain garde ses 5 types |
| `storage` (côté application) | `StorageBackends` **renommé** `com.minos.application.StorageBackends` |
| `store`, `registry`, `storage`, `orchestration`, `incremental` (côté storage-local) | **renommés** `com.minos.storage.local.store`, `com.minos.storage.local.registry`, `com.minos.storage.local`, `com.minos.storage.local.orchestration`, `com.minos.storage.local.incremental` |
| `git` | les 6 classes d'integration-git **renommées** `com.minos.integration.git` ; le port `GitIntelligence` reste dans engine |
| `adapter.scip` | les 2 DTO d'engine (`ScipSymbolSnapshotRequest`, `ScipSymbolSnapshotReport`) **renommés** dans `com.minos.orchestration`, à côté du port `ScipArtifactImporter` ; provider-scip garde `com.minos.adapter.scip` |
| `cli` | `com.minos.cli.MinosLauncher`, point d'entrée documenté, est **déplacé** dans `minos-cli` sans changer de nom ; sa route `mcp` passe par un SPI minimal déclaré dans `minos-cli`, fourni par `minos-app` (`META-INF/services`), chargé par le chargeur de classes de MINOS, en échec explicite à zéro ou plusieurs fournisseurs, ordre de routage inchangé. `com.minos.cli.DockerRuntimeBootstrap`, point d'entrée des fichiers compose Docker (`entrypoint`), est lui aussi **déplacé** dans `minos-cli` sans changer de nom. Les cinq autres classes de `minos-app` (`McpBackend*`, `DockerMcpTransport`) sont **renommées** `com.minos.app` |
| `integration.nexus` | `NexusExportBridgeMain`, point d'entrée de processus de NEXUS, reste dans `minos-app` sans changer de nom ; les 4 classes de `minos-nexus` sont **renommées** `com.minos.nexus` |
| tests en package étranger | relogés dans un package de leur module, ou dans le module testé quand A2 le permet |
| alias dépréciés de `minos-cli` | `ProjectOperations`, `ProjectSymbolQuery`, `LocalProjectOperations`, `LocalProjectSymbolQuery` **supprimés** ; leurs appelants utilisent les types de `com.minos.application` |

La correspondance classe par classe (ancien module et FQN → nouveau) est tenue dans `ARCHI-SUIVI.md` § 4.

### 3. Contrôle

`scripts/architecture/check-module-boundaries.py` gagne deux règles, avec un auto-test (`scripts/architecture/test_check_module_boundaries.py`) exécuté par la CI à côté du script :

- **aucun package éclaté** : un package déclaré par les sources de production de plus d'un module est une erreur ; un fichier de test déclaré dans un package dont la production appartient à un autre module est une erreur. Le message nomme le package et les modules.
- **A7** : la liste des modules du script est confrontée aux `<modules>` du POM racine ; un module du reactor absent du script, ou un module du script absent du reactor, fait échouer le contrôle.

La règle arrive en **cliquet** : une liste explicite des éclatements encore tolérés, égale à l'inventaire, qui échoue aussi sur toute entrée périmée ; chaque commit de repli retire ses entrées ; au dernier commit du lot la liste est vide, puis supprimée.

## Ruptures

Aucune signature publique de `minos-api` ne change : sur la base, les signatures publiques et protégées de `minos-api` (`javap -public`, types génériques compris) ne nomment que `com.minos.api.*` et `com.minos.application.MinosApplication`, qui ne bouge pas. Les points d'entrée de processus gardent leur nom : `com.minos.cli.MinosLauncher`, `com.minos.cli.DockerRuntimeBootstrap`, `com.minos.integration.nexus.NexusExportBridgeMain`, `com.minos.mcp.MinosMcpServer`, `com.minos.adapter.scip.runtime.StampManagedProviderMarkers`.

Les packages renommés sont tous internes (adaptateurs, services applicatifs, classes d'assemblage). Un code Java qui les importait directement doit changer ses imports :

| Ancien package (module) | Nouveau package |
|---|---|
| `com.minos.runtime` (runtime-local) | `com.minos.runtime.local` |
| `com.minos.store` (storage-local) | `com.minos.storage.local.store` |
| `com.minos.registry` (storage-local) | `com.minos.storage.local.registry` |
| `com.minos.storage` (storage-local) | `com.minos.storage.local` |
| `com.minos.orchestration` (storage-local) | `com.minos.storage.local.orchestration` |
| `com.minos.incremental` (storage-local) | `com.minos.storage.local.incremental` |
| `com.minos.git` (integration-git) | `com.minos.integration.git` |
| `com.minos.integration.nexus` (nexus) | `com.minos.nexus` |
| `com.minos.dynamic` (application) | `com.minos.application.dynamic` |
| `com.minos.semantic` (application) | `com.minos.application.semantic` |
| `com.minos.storage.StorageBackends` (application) | `com.minos.application.StorageBackends` |
| `com.minos.adapter.scip.ScipSymbolSnapshotRequest`/`Report` (engine) | `com.minos.orchestration` |
| `com.minos.cli` (app, hors points d'entrée) | `com.minos.app` |

Les quatre alias dépréciés de `minos-cli` disparaissent. `docs/user/java-api.md` reçoit la même liste, dans sa section des ruptures, au commit de chaque renommage.

## Amendement de l'ADR 0022

L'ADR 0022 plaçait dans `minos-application` « discovery local, incrémental, orchestration locale » et `MinosVersion`. Ces classes ne dépendent que d'engine et de domain ; elles rejoignent `minos-engine`, conformément à la vue des blocs (arc42, section 5 : « engine : ports, orchestration indexeurs… »). `minos-application` garde les services applicatifs qui ont besoin d'un contexte applicatif (architecture, contexte, impact, sortie, analyse de programme, espace de travail, résolution de projet, sémantique, runtime dynamique, composition). Le reste de l'ADR 0022 est inchangé.

## Conséquences

- Chaque package a un propriétaire unique, lisible dans son nom ; la visibilité package ne traverse plus aucun jar, en production comme en test. La voie vers JPMS est ouverte (elle n'est pas prise ici).
- **`minos-engine` grossit** (≈ 71 classes de production et une quarantaine de tests en plus). C'est le prix de « déplacer plutôt que renommer » : le constat A5 (« engine fourre-tout ») s'aggrave en volume, et son découpage éventuel (sous-modules ports / orchestration) devient plus pressant. A5 n'est pas traité ici.
- Les classes renommées qui journalisent par `System.getLogger(X.class.getName())` (huit classes de runtime-local) changent de nom de journal. Aucune configuration de journalisation du dépôt ni aucun golden n'en dépend.
- Les scripts de contrôle qui citent des chemins ou des FQN sont mis à jour à assertion identique, seul le chemin change (liste : `ARCHI-SUIVI.md` § 5).

## Limites connues

- Deux méthodes d'engine passent de package-private à `public` : `FileFingerprint.requireSha256` et `IndexingRun.portable`. Ce sont des règles de format sur disque partagées entre un port et ses adaptateurs ; storage-local les utilisait par la seule égalité des noms de package. Les copier aurait créé deux sources de vérité pour un même format ; elles sont donc exposées, et leur Javadoc le dit (arbitrage du 2026-09-29, `ARCHI-SUIVI.md` § 3.2).
- `RuntimeIntelligenceServiceTest` est scindé : les cas qui dépendent de l'horloge rejoignent `minos-application` avec des doublures en mémoire des ports d'engine ; ceux qui ont besoin des adaptateurs fichiers restent dans `minos-bootstrap`, dans un package de bootstrap. Le constructeur à `Clock` reste package-private.
- La règle raisonne sur la déclaration `package` des sources ; elle ne voit pas les ressources. Les ressources placées sous un chemin de package (`com/minos/runtime/**`) suivent leur classe par convention, pas par contrôle.
