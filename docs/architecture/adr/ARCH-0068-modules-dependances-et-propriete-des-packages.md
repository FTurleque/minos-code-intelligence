---
id: ARCH-0068
title: "Modules, dépendances et propriété des packages"
domain: architecture
rules: true
---

# Modules, dépendances et propriété des packages

- **Statut** : Accepted
- **Date** : 2026-10-23
- **Origine** : consolidation du registre (décision du propriétaire du 2026-10-23)
- **Remplace** : [ADR-0022](archive/0022-maven-reactor-and-module-boundaries.md), [ADR-0044](archive/0044-un-package-un-module.md), [ADR-0055](archive/0055-unifier-les-adaptateurs-de-stockage.md), [ADR-0062](archive/0062-confirmer-l-adr-0044-et-geler-les-racines-de-packages.md)
- **Remplacé par** : —

## Context

MINOS est un monolithe modulaire. Des frontières tenues seulement par des packages et des conventions ne sont pas protégées par la compilation : une dépendance interdite s'introduit sans que Maven impose la direction voulue (ADR 0022 § Context).

Découper en modules sans découper les packages laisse un même nom de package déclaré par plusieurs modules, avec trois effets : la visibilité « package » traverse les jars, aucun `module-info.java` n'est possible (JPMS), et le nom d'un package ne dit plus où vit une classe (ADR 0044 § Context). Une liste de modules codée en dur dans le gate de frontières, jamais confrontée au reactor, laisse un module nouveau échapper à toutes les règles (ADR 0044 § Context, A7).

Les deux adaptateurs de stockage sont livrés ensemble ; le backend PostgreSQL dépend du backend local pour ses codecs (ADR 0055 § Context). Une partie de la dette d'architecture mesurée (package `hosted` dans le moteur, lancement d'`icacls.exe` par `PrivateLocalStorage`, racines de packages sans préfixe de module) ne se résorbe qu'avec des chantiers dont les prérequis ne sont pas livrés (ADR 0062 § Context).

Options écartées :

- **Un seul module contrôlé par ArchUnit et des tests de frontière** : utile en complément, insuffisant pour imposer les dépendances au build (ADR 0022 § Alternatives rejetées).
- **Placer le runtime d'exécution générique dans `minos-provider-scip` ou dans `minos-engine`** : la CLI dépendrait de SCIP pour découvrir des commandes, ou le moteur absorberait de l'infrastructure de processus (ADR 0022 § Alternatives rejetées).
- **Créer des modules vides** : un module sans responsabilité ni code ne protège aucune frontière (ADR 0022 § Alternatives rejetées).
- **Garder deux modules de stockage** : isolation Maven et distributions séparables, au prix du couplage des codecs ou d'un troisième artefact (ADR 0055 § Alternatives et conséquences).
- **Amender les emplacements** (module dédié à `hosted`, module de lancement neuf, renommage des racines) : dépendance nouvelle de `minos-storage-local`, module qui reproduit un package, gates littéraux, portées JaCoCo, baselines SpotBugs et rupture de points d'entrée documentés (ADR 0062 § Option B).

La racine de composition (`minos-bootstrap`) relève de l'[ADR 0042](ARCH-0042-racine-de-composition.md), la déclaration de chaque module utilisé de l'[ADR 0058](ARCH-0058-declarer-les-usages-de-modules.md), et la finalisation des frontières hexagonales de l'[ADR 0057](ARCH-0057-finaliser-les-frontieres-hexagonales.md) : ils restent en vigueur et ne sont pas repris ici.

## Decision

### 1. Reactor Maven

Le dépôt est un reactor Maven multi-module piloté par le parent `com.minos:minos-parent` (ADR 0022 § Decision). Le découpage ratifié est `minos-domain`, `minos-engine`, `minos-runtime-local`, `minos-storage-local`, `minos-provider-scip`, `minos-integration-git`, `minos-application`, `minos-nexus`, `minos-cli`, `minos-api`, `minos-mcp` et `minos-app` (ADR 0022 § Decision). L'ADR 0042 y ajoute la racine de composition `minos-bootstrap` et range `minos-storage-postgresql` parmi les adaptateurs ; le § 4 réunit les deux modules de stockage en un seul.

- Un module protège un invariant réel. Il est interdit de créer un module uniquement pour reproduire un package existant, ou un module vide, sans responsabilité ni code (ADR 0022 § Decision et § Alternatives rejetées ; ADR 0062 § Critères de décision).
- `minos-runtime-local` protège l'invariant « exécution locale générique des providers ≠ provider SCIP » ; `minos-application` porte les services applicatifs partagés qui ne relèvent ni du moteur pur, ni d'un transport public, ni de la composition (ADR 0022 § Decision).
- `minos-app` porte les points d'entrée de processus et l'artefact distribué (ADR 0022 § Decision) ; c'est l'assemblage final, la racine de composition étant `minos-bootstrap` (ADR 0042).
- L'artefact exécutable est `com.minos:minos-code-intelligence:<version>`, produit par `minos-app` dans le répertoire `target/` racine. La racine utilise la version CI-friendly `${revision}` ; une release passe sa version par `-Drevision=<version>`, sans POM temporaire (ADR 0022 § « Coordonnée et packaging utilisateur »).

### 2. Direction des dépendances

La direction est (ADR 0022 § « Direction des dépendances ») :

```text
minos-domain
    ↑
minos-engine
    ↑
minos-runtime-local / adaptateurs de stockage
    ↑
adaptateurs et intégrations
    ↑
services applicatifs / surfaces
    ↑
assemblage minos-app
```

- Sont interdites : domaine → SCIP ; domaine → SDK MCP ; moteur → CLI ; moteur → protocole MCP ; moteur → runtime de processus local concret ; runtime local générique → provider SCIP ; architecture et impact → couches d'exposition ; API publique → backend local concret lorsqu'un port stable peut être utilisé (ADR 0022 § « Direction des dépendances »). `minos-engine` ne dépend pas d'un adaptateur de stockage (ADR 0022 § Decision). L'arête `minos-mcp` → `minos-cli` n'existe pas et ne se recrée pas (ADR 0062 § Context et § Critères de décision).
- La table `ALLOWED_DEPENDENCIES` de `scripts/architecture/check-module-boundaries.py` est l'ensemble maximal des dépendances internes directes : retirer une dépendance est permis, en ajouter une hors de la table est refusé.
- `minos-integration-git` est le seul module qui déclare `org.eclipse.jgit` ; `minos-provider-scip` déclare `scip-java-bindings` ; aucune couche dont dépend le provider SCIP ne dépend de SCIP (ADR 0022 § Decision).
- Dans `minos-domain`, `minos-engine` et `minos-application`, `PrivateLocalStorage` est le seul lanceur de processus admis (règle A12). Son mécanisme d'ACL Windows sort du moteur par un port d'E/S de plateforme fourni par `minos-runtime-local` et injecté, conçu avec AUD-SEC-06 et pas avant (ADR 0062 § 1 ; ADR 0022 § Mise en œuvre).
- Une arête d'exécution qu'un fichier `META-INF/services` crée entre deux modules, dont `minos-cli` → `minos-app` par le service `McpLaunchRoute`, figure dans le document généré `docs/architecture/diagrams/module-dependencies.md`, dont le contrôle échoue si elle manque (ADR 0062 § 2).

### 3. Un package, un module

Chaque package Java appartient à un seul module : ses sources de production vivent dans ce module, et les tests déclarés dans ce package vivent dans ce module (ADR 0044 § 1). Aucun éclatement n'est toléré ; le répertoire d'un test déclare son package, comme pour la production (ADR 0044 § 3). Un parent lexical et ses sous-packages peuvent appartenir à des modules différents ; une déclaration de package identique ne le peut pas (ADR 0055 § Decision).

Un package éclaté se replie selon cet ordre (ADR 0044 § 1) :

1. **Déplacement** des classes dans le module le plus bas capable de les accueillir toutes sans violer `ALLOWED_DEPENDENCIES` ni les rôles A2 ; le nom pleinement qualifié ne change pas.
2. Un **adaptateur ne descend jamais** dans `minos-engine` ou `minos-domain`.
3. Quand les deux côtés sont épinglés, on **renomme le côté interne**, jamais un point d'entrée documenté ; le package renommé prend l'espace de noms de son module (`<espace-du-module>.<ancien-suffixe>`).

Un accès package-private entre jars se résout par déplacement du code ou du test ; toute visibilité élargie est justifiée par écrit dans le commit et dans le suivi (ADR 0044 § 1).

**Emplacements** (ADR 0044 § 2 et § « Amendement de l'ADR 0022 », confirmés par l'ADR 0062 ; espaces de noms des modules : ADR 0062 § 3). La table ne donne que les emplacements que ces ADR tranchent ; les racines que possède chaque module sont celles de `PACKAGE_ROOTS_BY_MODULE` (§ 5).

| Package | Module |
|---|---|
| `com.minos.domain`, `com.minos.semantic` (types du domaine) | `minos-domain` |
| `com.minos.query`, ports `com.minos.store` (dont `CodeKnowledgeStore`), `com.minos.git`, `com.minos.runtime` (dont `MinosVersion`), `com.minos.storage`, `com.minos.discovery`, `com.minos.incremental`, `com.minos.orchestration` (dont `ScipSymbolSnapshotRequest` et `ScipSymbolSnapshotReport`), `com.minos.hosted`, `com.minos.dynamic` | `minos-engine` |
| `com.minos.runtime.local` | `minos-runtime-local` |
| `com.minos.storage.local` (dont `store`, `registry`, `orchestration`, `incremental`) | `minos-storage-local` |
| `com.minos.storage.postgresql` | `minos-storage-postgresql` |
| `com.minos.adapter.scip` | `minos-provider-scip` |
| `com.minos.integration.git` | `minos-integration-git` |
| `com.minos.application` (dont `dynamic`, `semantic`, `StorageBackends`) | `minos-application` |
| `com.minos.bootstrap` | `minos-bootstrap` ([ADR 0042](ARCH-0042-racine-de-composition.md)) |
| `com.minos.nexus` | `minos-nexus` |
| `com.minos.cli` (dont `MinosLauncher`, `DockerRuntimeBootstrap`, le SPI `McpLaunchRoute`) | `minos-cli` |
| `com.minos.api` | `minos-api` |
| `com.minos.mcp` | `minos-mcp` |
| `com.minos.app` (`McpBackend*`, `DockerMcpTransport`, `McpLaunchRouteProvider`), `com.minos.integration.nexus.NexusExportBridgeMain` | `minos-app` |

- Les points d'entrée de processus gardent leur nom : `com.minos.cli.MinosLauncher`, `com.minos.cli.DockerRuntimeBootstrap`, `com.minos.integration.nexus.NexusExportBridgeMain`, `com.minos.mcp.MinosMcpServer`, `com.minos.adapter.scip.runtime.install.StampManagedProviderMarkers` (ADR 0044 § Ruptures).
- La route `minos mcp` passe par le SPI `com.minos.cli.McpLaunchRoute`, déclaré dans `minos-cli` et fourni par `minos-app` (`META-INF/services`) ; `MinosLauncher` le résout dans le seul chargeur de classes de MINOS et échoue explicitement, sans chemin, à zéro ou plusieurs fournisseurs ; le JAR ombré en déclare exactement un (ADR 0044 § 2 et § Consequences).
- `hosted` reste dans `minos-engine` tant que la cartographie de SH-08 n'a pas montré un meilleur emplacement ; le transport MCP Docker et le pont NEXUS restent dans `minos-app`, sous leurs noms (ADR 0062 § 1 et § 2).

### 4. Adaptateurs de stockage réunis dans `minos-storage` (accepté, à mettre en œuvre)

- Un seul artefact Maven `com.minos:minos-storage` remplace `minos-storage-local` et `minos-storage-postgresql` (ADR 0055 § Decision).
- Les implémentations gardent `com.minos.storage.local.*` et `com.minos.storage.postgresql.*` ; les codecs réellement partagés rejoignent `com.minos.storage.codec` dans le même artefact. Aucune classe de production n'est déclarée dans `com.minos.storage`, qui appartient à `minos-engine` (ADR 0055 § Decision).
- Les ports `StorageBackend`, `StorageBackendProvider`, `CodeKnowledgeSnapshotStore` et les modèles neutres restent dans le cœur ; aucune dépendance application → stockage concret n'est réintroduite. L'assemblage et la sélection, dont `StorageBackendSelection`, restent dans `minos-bootstrap` : pas de second bootstrap dans `minos-storage` (ADR 0055 § Decision).
- `local` et `postgresql` ne dépendent pas l'un de l'autre ; ils peuvent dépendre de `codec` et du cœur ; `codec` ne dépend d'aucun backend concret (ADR 0055 § Decision).
- Les identifiants de configuration, fichiers de services SPI, la sélection explicite du backend, le contrat Java public et les formats persistés sont conservés. Le mode local n'ouvre aucune connexion PostgreSQL et n'exige ni serveur ni Docker. Le raccourci de sélection locale est conservé ; une uniformisation SPI éventuelle est séparée et testée (ADR 0055 § Decision).

L'ordre des tâches et leurs prérequis sont dans [le plan du chantier stockage](../../project/roadmap/storage-hexagonal-2026-10/README.md) (SH-02, SH-03).

### 5. Racines de packages et cliquets

- Aucun package existant n'est renommé. Un nouveau package se crée sous une racine `com.minos.<x>` que son module possède déjà (règle A14, table `PACKAGE_ROOTS_BY_MODULE`), et de préférence sous l'espace de noms propre au module quand il en a un (`com.minos.application`, `com.minos.storage.local`, `com.minos.storage.postgresql`, `com.minos.runtime.local`, `com.minos.adapter.scip`, `com.minos.integration.git`, `com.minos.bootstrap`, `com.minos.cli`, `com.minos.api`, `com.minos.mcp`, `com.minos.nexus`, `com.minos.app`) (ADR 0062 § 3).
- Une nouvelle racine est une décision d'architecture : elle s'ajoute à la table dans une modification relue qui cite sa raison (ADR 0062 § 3).
- Les usages existants des surfaces vers le moteur ([ADR 0058](ARCH-0058-declarer-les-usages-de-modules.md) § 3) et entre adaptateurs sont tenus par des listes fermées (règles A11 et A13) qui ne peuvent que rétrécir (ADR 0062 § 4).
- Si une cartographie future justifie de sortir `hosted` ou de renommer une racine, un nouvel ADR remplace le point concerné de celui-ci (ADR 0062 § Consequences).

## Do's and Don'ts

### Do

- Respecter la direction domaine → moteur → runtime et stockage → adaptateurs → services applicatifs et surfaces → `minos-app`, dans la limite de `ALLOWED_DEPENDENCIES`.
- Faire appartenir chaque package Java à un seul module, sources de production et tests compris.
- Replier un package éclaté d'abord par déplacement à nom qualifié constant, sinon en renommant le côté interne dans l'espace de noms de son module.
- Créer un nouveau package sous une racine que son module possède déjà ; une nouvelle racine passe par une modification relue qui cite sa raison.
- Écrire dans `docs/architecture/diagrams/module-dependencies.md` toute arête d'exécution créée par un fichier `META-INF/services`.
- Réunir les adaptateurs de stockage dans `com.minos:minos-storage` (`local`, `postgresql`, `codec`) en gardant ports et sélection hors de ce module.

### Don't

- Ne pas créer de module pour reproduire un package, ni de module vide.
- Ne pas faire descendre un adaptateur dans `minos-engine` ou `minos-domain`.
- Ne renommer ni un package existant ni un point d'entrée documenté.
- Ne lancer aucun processus dans `minos-domain`, `minos-engine` et `minos-application` hors de `PrivateLocalStorage`.
- Ne pas faire dépendre `local` et `postgresql` l'un de l'autre, ni déclarer de classe de production dans `com.minos.storage` hors du moteur.
- Ne pas laisser croître les listes des règles A11 et A13.

## Consequences

### Positives

- Maven est un garde-fou d'architecture réel ; SCIP et JGit sont confinés aux modules qui en ont besoin ; la CLI utilise le runtime local générique sans dépendre du provider SCIP (ADR 0022 § Consequences).
- Chaque package a un propriétaire unique, lisible dans son nom ; la visibilité package ne traverse aucun jar, en production comme en test. La voie vers JPMS est ouverte, sans être prise (ADR 0044 § Consequences).
- Aucun point d'entrée, aucune signature publique ni aucun nom qualifié ne change pour tenir ces règles ; la dette mesurée ne croît pas sans une ligne ajoutée à une table du gate, que la revue voit (ADR 0062 § Consequences).

### Négatives

- `minos-engine` concentre ports, orchestration, `hosted` et `MinosVersion` : c'est le prix de « déplacer plutôt que renommer » (ADR 0044 § Consequences).
- `minos-engine` lance `icacls.exe` jusqu'au port de plateforme ; `hosted` et les racines sans préfixe de module restent en place (ADR 0062 § Consequences).
- Tout nouvel usage légitime d'un type du moteur par une surface, ou d'un adaptateur par un autre, demande d'éditer une liste (ADR 0062 § Consequences).
- Après la fusion du stockage, les dépendances JDBC et Jackson entrent dans le graphe transitif du module commun : l'impact sur l'API embarquée, la taille, le SBOM et les avis tiers est inventorié, et PostgreSQL n'est pas présenté comme absent du classpath local ; les frontières internes du stockage sont tenues par les packages et des tests d'architecture, pas par Maven seul (ADR 0055 § Alternatives et conséquences).
- Les coordonnées Maven du stockage changent : les consommateurs sont vérifiés avant suppression, la migration est documentée ; des POM de relocation, sans duplication de classes, sont proposés et validés séparément si d'anciennes coordonnées sont consommées. Signatures Java et octets persistés restent inchangés (ADR 0055 § Alternatives et conséquences).
- Chaque chantier qui résorbe la dette (port de plateforme SH-08, port de lecture SH-04, fusion du stockage et codec commun SH-02 et SH-03, staging injecté SH-06) déplace des classes et écrit son coût : couverture du nouveau code par module, tests déplacés avec la classe, portées de `check-jacoco.py`, gates littéraux, baselines SpotBugs (ADR 0062 § Consequences).

### Limites connues

- Le contrôle de propriété raisonne sur la déclaration `package` des sources ; les ressources placées sous un chemin de package suivent leur classe par convention, pas par contrôle (ADR 0044 § Limites connues).
- `FileFingerprint.requireSha256` et `IndexingRun.portable` sont publiques : ce sont des règles de format sur disque partagées entre un port et ses adaptateurs, exposées plutôt que copiées, et leur Javadoc le dit (ADR 0044 § Limites connues).

## Compliance and Enforcement

- Gate `scripts/architecture/check-module-boundaries.py` (job `invariants`, `.github/workflows/pr-ci.yml:137`) et son auto-test `scripts/architecture/test_check_module_boundaries.py` (`.github/workflows/pr-ci.yml:140`) :
  - A2 — dépendances internes des POM bornées par `ALLOWED_DEPENDENCIES`, déclarées comme arêtes simples de `<dependencies>`, rôles hexagonaux par portée et contrôle des sources ([ADR 0042](ARCH-0042-racine-de-composition.md)) ;
  - A3 — aucun package déclaré par les sources de production de deux modules, aucun test dans un package dont la production appartient à un autre module (`check_package_ownership`) ;
  - A7 — la liste `MODULES` du script est exactement les `<modules>` du POM racine (`check_reactor_modules`) ;
  - A8 — aucun cycle de packages, plugin IntelliJ compris, hors de `KNOWN_PACKAGE_CYCLES` (vide) ;
  - A9 — la ligne « Dépendances » de chaque module dans `docs/architecture/arc42/05-vue-blocs.md` égale ses dépendances internes directes ;
  - A10 — hors de `minos-runtime-local`, seuls ses types exportés de bac à sable, processus et espace de travail sont nommés ;
  - A11 — chaque surface n'utilise que les types du moteur de sa liste `ENGINE_TYPES_NAMED_BY_SURFACE`, chacun depuis le nombre de fichiers listé ; un appel d'accesseur de `MinosApplication` compte comme un usage du type rendu ;
  - A12 — aucun lancement de processus dans `minos-domain`, `minos-engine` et `minos-application` hors de `PROCESS_LAUNCHERS`, et, par classe listée, exactement le nombre de sites de lancement listé ;
  - A13 — un adaptateur ne nomme les types d'un autre que par `ADAPTER_TYPES_NAMED_BY_ADAPTER`, chacun depuis le nombre de fichiers listé ;
  - A14 — chaque package de production d'un module est sous une de ses racines `PACKAGE_ROOTS_BY_MODULE` ;
  - A15 — chaque type public de production est nommé par la production ou listé avec sa raison ;
  - A16 — chaque `ServiceLoader.load` de production passe un chargeur de classes explicite ([ADR 0042](ARCH-0042-racine-de-composition.md)) ;
  - le document généré `docs/architecture/diagrams/module-dependencies.md` est à jour et montre les arêtes d'exécution des fichiers `META-INF/services`.
  Les listes A8 et A10 à A15 sont des cliquets : une entrée morte fait aussi échouer le gate.
- Test ArchUnit `ModuleArchitectureTest` (`minos-app`, lancé par `mvnw verify`) — `everyModuleIsImportedCompletely`, `everyPackageBelongsToOneModule` (A3 sur le bytecode), `adaptersDoNotReachBackIntoUpperLayers`, `applicationOnlyKnowsPorts`, `surfacesDoNotCompileAgainstTheCompositionRootOrAdapters`, `coreModulesStayAtTheBottom` (domaine et moteur en bas), `everyModuleOnlyUsesItsAllowedModules` (usages déclarés, ADR 0058), `surfacesUseOnlyListedEngineTypes` (A11 sur le bytecode, table `ENGINE_TYPES_USED_BY_SURFACE_BYTECODE` : types du moteur et nombre de classes par surface, appels chaînés compris).
- Règle Archgate `governed-modules-match-reactor` (`ARCH-0068-modules-dependances-et-propriete-des-packages.rules.ts`) — porte A7 en lisant la liste `MODULES` du script et les `<modules>` du POM racine ; lancée par `archgate check`, pas par la CI.
- Règle Archgate `ARCH-0068-modules-dependances-et-propriete-des-packages.rules.ts` / `one-package-one-module` : refuse un package déclaré par les sources de production de deux modules, un test dont le répertoire ne correspond pas à son package, et un test déclaré dans un package dont la production appartient à un autre module ; second exécutant de `check_package_ownership` (A3, `check-module-boundaries.py`).
- Règle Archgate `ARCH-0068-modules-dependances-et-propriete-des-packages.rules.ts` / `package-roots-owned-by-module` : refuse un package de production sous une racine `com.minos.<x>` absente des racines de son module dans `PACKAGE_ROOTS_BY_MODULE` (table lue dans le script, jamais recopiée), et une entrée de cette table qui ne nomme pas un module de `MODULES` ; second exécutant de `check_package_roots` (A14, `check-module-boundaries.py`).
- Aucun contrôle automatique du confinement de JGit à `minos-integration-git`, ni des critères propres à la fusion du stockage (un seul module de stockage, aucune dépendance directe entre backends), vérifiables seulement une fois la fusion réalisée.
- Revue : un module nouveau protège un invariant réel ; un nouveau package est déclaré par un seul module, sous l'espace de noms de ce module ; toute ligne ajoutée à `PACKAGE_ROOTS_BY_MODULE`, `PROCESS_LAUNCHERS`, `ENGINE_TYPES_NAMED_BY_SURFACE` ou `ADAPTER_TYPES_NAMED_BY_ADAPTER` cite sa raison ; la fusion du stockage ne crée aucune classe de production dans `com.minos.storage` ni de second bootstrap, et le mode local reste utilisable sans PostgreSQL.

## Mise en œuvre

**§ 2, port d'E/S de plateforme (SH-08, AUD-ARC-05, changement OpenSpec `extraire-le-port-d-acl-de-plateforme`)** : `minos-domain`, `minos-engine` et `minos-application` ne lancent aucun processus ; `PROCESS_LAUNCHERS` (A12) est vide. Le mécanisme d'ACL Windows du stockage privé est le port `com.minos.io.PlatformAccessControl`. Son implémentation `com.minos.runtime.local.acl.WindowsIcaclsAccessControl` est fournie par `minos-runtime-local` et enregistrée dans `META-INF/services`. `PrivateLocalStorage` la découvre une fois par processus, avec son propre chargeur de classes : c'est l'injection retenue, parce que le stockage privé sert avant toute composition (`MinosLauncher`, pont NEXUS). Sur un système de fichiers à ACL, aucune implémentation, ou plusieurs, fait échouer le durcissement. Le lanceur d'`icacls` est unique dans `minos-runtime-local`, partagé avec les octrois du bac à sable, et passe la cible sous sa forme longue (`\\?\`). Le package `acl` est gardé par A10. Les modules qui ne voient pas `minos-runtime-local` testent avec la doublure `com.minos.testsupport.IcaclsTestAccessControl` du `test-jar` du moteur, qu'un test de parité garde alignée.

**§ 3, `hosted`** : la cartographie de SH-08 ne trouve dans `com.minos.hosted` ni lancement de processus, ni ACL, ni écriture de fichier. Le package reste dans `minos-engine`.

## Écarts constatés

Relevés le 2026-10-23 (audit des ADR) ; la décision ci-dessus reste celle en vigueur tant qu'un amendement ou une correction du code n'a pas tranché.

- Fusion du stockage (§ 4) à réaliser : le reactor déclare toujours `minos-storage-local` et `minos-storage-postgresql`, sans module `minos-storage` ni package `com.minos.storage.codec` — preuve : `pom.xml:27`, `pom.xml:31`.
- `minos-storage-postgresql` dépend de `minos-storage-local`, contrairement au point « `local` et `postgresql` ne dépendent pas l'un de l'autre », et la table du gate l'autorise — preuve : `minos-storage-postgresql/pom.xml:31`, `scripts/architecture/check-module-boundaries.py:67`.
- Le découpage ratifié par l'ADR 0022 compte 12 modules enfants ; le reactor en compte 14 : `minos-bootstrap` (ADR 0042) et `minos-storage-postgresql`, que l'ADR 0042 range parmi les adaptateurs sans qu'un ADR source l'ajoute à la liste ratifiée — preuve : `pom.xml:24-37`.

## References

- ADR remplacés : [0022](archive/0022-maven-reactor-and-module-boundaries.md) (reactor, direction des dépendances ; trace de qualification M15-S2), [0044](archive/0044-un-package-un-module.md) (un package, un module ; table de repli et ruptures), [0055](archive/0055-unifier-les-adaptateurs-de-stockage.md) (fusion du stockage), [0062](archive/0062-confirmer-l-adr-0044-et-geler-les-racines-de-packages.md) (confirmation des emplacements, cliquets A11 à A14).
- ADR liés : [0042](ARCH-0042-racine-de-composition.md) (racine de composition), [0057](ARCH-0057-finaliser-les-frontieres-hexagonales.md) (frontières hexagonales), [0058](ARCH-0058-declarer-les-usages-de-modules.md) (usages de modules déclarés).
- Gate : `scripts/architecture/check-module-boundaries.py` ; description dans [`docs/developer/quality-gates.md`](../../developer/quality-gates.md).
- Vue des blocs : [`docs/architecture/arc42/05-vue-blocs.md`](../arc42/05-vue-blocs.md) ; dépendances générées : [`docs/architecture/diagrams/module-dependencies.md`](../diagrams/module-dependencies.md).
- Plan : [chantier stockage et frontières hexagonales](../../project/roadmap/storage-hexagonal-2026-10/README.md).
- Historique : [`ARCHI-SUIVI.md`](../../project/audits/archive/2026-09/ARCHI-SUIVI.md) (correspondance classe par classe du repli des packages), [`docs/user/java-api.md`](../../user/java-api.md) (section « Ruptures »), [`docs/project/history/milestones/m15/`](../../project/history/milestones/m15/M15_S1_BASELINE.md).
