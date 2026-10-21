# Design

## Context

Sprint 9 de l'audit du 10 octobre 2026 : **AUD-ARC-04**. HEAD analysé : `949e1569` (`develop`). **Piège de nom** : « A8 » dans le titre du constat est un identifiant de l'audit d'architecture de septembre (résidu de chargeur contextuel) ; ce n'est pas la règle A8 de `scripts/architecture/check-module-boundaries.py`, qui garde les cycles de packages.

## État vérifié au HEAD

### AUD-ARC-04 — confirmé

- 4 `ServiceLoader.load` dans les sources de production. Avec chargeur explicite et refus de zéro ou plusieurs : `minos-application/src/main/java/com/minos/application/MinosApplicationComposers.java:36` (sélection `:39-53`), `minos-cli/src/main/java/com/minos/cli/McpLaunchRoutes.java:31`.
- Sans chargeur explicite : `minos-bootstrap/src/main/java/com/minos/bootstrap/StorageBackendSelection.java:23` (premier fournisseur dont l'`id()` égale le backend configuré, `:24-25` ; sinon « provider is not available », `:28`) ; `minos-engine/src/main/java/com/minos/orchestration/StableFileSystemIdentity.java:46` (premier fournisseur qui rend une identité).
- Fournisseur d'identité : `minos-runtime-local/src/main/java/com/minos/runtime/local/sandbox/WindowsExecutionPathIdentityProvider.java`, déclaré dans `minos-runtime-local/src/main/resources/META-INF/services/com.minos.orchestration.ExecutionPathIdentityProvider` (l'audit citait l'ancien package `com.minos.runtime.local`).
- La capture est **statique** : `minos-engine/src/main/java/com/minos/orchestration/IndexingRuntimePorts.java:137` (`ExecutionPathAuthorization.tryCapture`) appelle `StableFileSystemIdentity.capture` (`:149`), et le constructeur public du record `IndexingExecutionRequest` l'appelle (`:279-292`). 22 fichiers de production nomment ce record.
- Double de test : `minos-bootstrap/src/test/java/com/minos/bootstrap/CloseCountingStorageProvider.java:30-32` déclare l'identifiant `postgresql` ; il est enregistré dans les ressources de test de `minos-api`, `minos-cli` et `minos-mcp`. Sa Javadoc dit qu'il n'est jamais enregistré dans `minos-bootstrap`, où le vrai fournisseur PostgreSQL est sur le chemin de classes de test.
- `docs/architecture/adr/0042-racine-de-composition.md:258-259` documente déjà le résidu de chargeur de contexte.

**Non vérifié** : aucun build ni test exécuté ; en particulier, **l'effet du refus des doublons sur les tests** de `minos-api`, `minos-cli` et `minos-mcp` (le vrai fournisseur PostgreSQL y est-il visible par transitivité ?) est à établir par exécution, en tâche 1.1. Le comportement dans le JAR ombré n'a pas été observé.

### Écarts avec l'action proposée par l'audit

1. **« Injecter le fournisseur depuis `minos-bootstrap` » touche une surface.** La capture est déclenchée par un constructeur public d'un record du moteur que nomment 22 fichiers de production (adaptateurs compris) : l'injecter change cette forme. Reporté (D3).
2. **Pour l'identité de chemin il n'y a pas d'`id`.** `ExecutionPathIdentityProvider` n'expose que `capture` : « refus des doublons » signifie « plus d'un fournisseur enregistré ». Sa Javadoc envisage qu'« une autre implémentation de confiance » puisse établir l'identité : refuser plusieurs fournisseurs resserre ce contrat (D2).
3. **Le refus des doublons peut casser des tests existants.** Si le double `postgresql` et le vrai fournisseur sont visibles ensemble dans un module, les tests qui s'appuient sur « le premier gagne » échoueront. D'où la tâche 1.1 avant toute correction.
4. **Rien n'empêche un cinquième site.** L'audit ne demande pas de gate ; sans lui le défaut reviendra (D4).

## Goals / Non-Goals

**Goals** : découverte déterministe et indépendante du chargeur de contexte ; aucun choix arbitraire entre fournisseurs ; refus fermé conservé ; règle gardée.

**Non-Goals** : changer la forme de `IndexingExecutionRequest` ; supporter des extensions tierces ; qualifier un hôte embarqué de bout en bout.

## Decisions

### D1. Chargeur de MINOS, comme pour la racine de composition

Chaque site passe le chargeur de la classe du **port** (`StorageBackendProvider.class.getClassLoader()`, `ExecutionPathIdentityProvider.class.getClassLoader()`), motif de `MinosApplicationComposers`. Une variante à chargeur paramétrable, de visibilité paquet, sert aux tests de chargeur isolé.

### D2. Refus des doublons

- Stockage : les fournisseurs sont regroupés par `id()` comparé sans casse avec `Locale.ROOT` ; deux fournisseurs de l'identifiant **demandé** font échouer l'ouverture avec un message qui nomme leurs classes. Le refus ne porte que sur l'identifiant demandé : le backend `local` ne passe pas par la découverte (`StorageBackendSelection.java:20-22`) et ne doit pas échouer à cause d'un doublon qu'il n'utilise pas.
- Identité de chemin : plus d'un fournisseur enregistré fait échouer la capture (identité indisponible, donc lancement refusé), avec un journal qui nomme les classes. Le contrat de la Javadoc est resserré à « au plus un fournisseur ».

Aucun message ne contient de chemin absolu.

### D3. Injection depuis la racine de composition — **décision en attente du propriétaire**

Recommandation : **ne pas** l'inclure ici. Chargeur explicite et refus des doublons ferment le défaut observable ; l'injection demande de retirer la capture du constructeur de `IndexingExecutionRequest` et de faire porter le fournisseur par les ports d'exécution, ce qui modifie un type public du moteur et ses 22 fichiers appelants. À spécifier avec SH-10 ou avec le chantier AUD-ARC-01.

Alternative : le faire maintenant. Supprime la dernière arête implicite du moteur vers `minos-runtime-local`, au prix d'un changement de signature large et d'un risque sur le confinement (toute requête construite sans autorisation de chemin doit rester refusée).

### D4. Une règle du garde de frontières

`check-module-boundaries.py` gagne une règle : tout `ServiceLoader.load(` d'un `src/main` a deux arguments. Son cas refusé et son cas accepté vont dans `test_check_module_boundaries.py` (déjà câblé dans `invariants`). Le numéro de règle est le prochain libre **après** celles que réserve `figer-la-dette-de-frontieres-par-des-cliquets` (A11 et suivantes) : à fixer à l'implémentation.

## Direction des dépendances

`domain → engine → runtime/storage → adapters → application → surfaces`. Aucune arête ajoutée. L'arête implicite `minos-engine` → `minos-runtime-local` par SPI subsiste (D3) : elle est déclarée ici, pas présentée comme résolue. `application ↛ adaptateurs` et `surfaces ↛ adaptateurs` inchangés.

## Qualification des capacités

- Découverte du backend PostgreSQL et de l'identité de chemin sous un chargeur de contexte différent : **non qualifiée** au HEAD ; qualifiée par les tests de chargeur isolé de ce changement.
- Hôte Java embarqué de bout en bout et JAR ombré : **partielle** (reste de SH-10).
- Extensions tierces : non supportées.

## Windows et Linux

Le fournisseur d'identité n'est consulté que lorsque ni `fileKey()` ni `unix:dev,ino` ne sont disponibles (`StableFileSystemIdentity.java:30-44`), donc en pratique sous Windows : le test de chargeur de contexte vide de l'identité tourne sous Windows ; sous Linux il vérifie seulement que la découverte n'est pas sollicitée. La sélection du stockage se teste sur les deux.

## Risks / Trade-offs

- Tests cassés par le refus des doublons : établi en 1.1 ; le correctif attendu est de test (un seul fournisseur `postgresql` visible par module), pas d'assouplir la règle.
- Un hôte qui comptait, sans le savoir, sur le chargeur de contexte pour **ajouter** un fournisseur ne le verra plus : non supporté, à écrire dans l'ADR 0042.
- Le message de doublon est nouveau pour un client strict : il n'apparaît que dans une configuration aujourd'hui arbitraire.

## Relevé d'exécution (tâche 1.1, 2026-10-21)

`mvnw dependency:list -DincludeArtifactIds=minos-storage-postgresql` sur `minos-api`, `minos-cli`, `minos-mcp`, `minos-bootstrap`, `minos-app` :

- `minos-api`, `minos-cli`, `minos-mcp` : **aucun** artefact `minos-storage-postgresql` (la dépendance de `minos-bootstrap` vers lui est de portée `test`, non transitive) ; seul le double `CloseCountingStorageProvider` y est déclaré.
- `minos-bootstrap` : `minos-storage-postgresql` en portée `test` (jar et test-jar) ; aucun fichier de service de test, donc seul le vrai fournisseur.
- `minos-app` : `minos-storage-postgresql` en portée `compile` ; aucun fichier de service de test.

Aucun module ne voit deux fournisseurs `postgresql` : aucun test ne dépendait de « le premier gagne », aucun correctif de test n'a été nécessaire. Le fournisseur d'identité de chemin n'est déclaré que par `minos-runtime-local`.

JAR ombré (`target/minos-code-intelligence-1.3.0-SNAPSHOT-all.jar`, produit par `mvnw -pl minos-app -am verify`) : `META-INF/services/com.minos.storage.StorageBackendProvider` contient `com.minos.storage.postgresql.PostgresStorageBackendProvider` et `META-INF/services/com.minos.orchestration.ExecutionPathIdentityProvider` contient `com.minos.runtime.local.sandbox.WindowsExecutionPathIdentityProvider`, un fournisseur chacun. La découverte dans un hôte qui exécute ce JAR n'a pas été observée.
