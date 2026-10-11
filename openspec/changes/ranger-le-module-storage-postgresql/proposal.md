# Proposal

## Why

`minos-storage-postgresql` (18 classes, 3 254 lignes) tient dans un seul package plat, `com.minos.storage.postgresql` : connexion et politique d'URL JDBC, migrations, stores de snapshots, registre des projets, verrou de mutation et requêtes sémantiques y sont mêlés. Le propriétaire a demandé de ranger ce module, après `minos-provider-scip` (PR #411), en acceptant le coût mesuré : des classes aujourd'hui package-private deviennent publiques.

## What Changes

- Quatre sous-packages : `support` (connexion, politique d'URL, migrations, codecs, verrou de mutation), `snapshot` (stores de snapshots et d'observations), `project` (registre, états d'index, rétention), `semantic` (requêtes et store vectoriel). `PostgresStorageBackend` et `PostgresStorageBackendProvider` restent à la racine.
- Les dépendances vont de `snapshot`, `project` et `semantic` vers `support`, et de la racine vers tous : aucun cycle.
- **13 classes deviennent publiques**, avec les membres que d'autres sous-packages appellent. Aucun autre module ne les importe (le module n'est atteint que par `ServiceLoader`) : la surface utilisable de l'extérieur ne change pas en pratique, mais l'encapsulation par le compilateur est perdue.
- Les tests suivent leurs classes ; `PostgresTestSupport` devient public.
- Aucun comportement, aucun schéma SQL, aucune migration ne change.

## Capabilities

### Modified Capabilities
- `frontieres-de-modules` : une exigence ajoutée sur l'organisation interne de `minos-storage-postgresql`.

## Hors périmètre

- Changer le comportement, le SQL, le schéma ou les migrations.
- Introduire JPMS (`module-info`) pour rétablir une encapsulation entre sous-packages.
- Ranger `minos-cli` et `minos-runtime-local` (changements séparés).
- Renommer `PostgresStorageBackendProvider` (nommé par `META-INF/services` et par `ShadedJarCompositionRootIT`).

## Impact

- Module : `minos-storage-postgresql` seul (aucun import depuis un autre module).
- Surfaces publiques (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune.
- ADR : aucun créé ; ADR 0044 (un package, un module) respecté : les nouveaux packages appartiennent à ce seul module.
- Gates à rejouer : `check-module-boundaries.py` (A3, A8), `check-private-io.py` et son allowlist, `check-jacoco.py`, gates `remediation` qui citent des chemins du module, baseline SpotBugs du module (les empreintes dépendent du nom de classe).
- Couverture : SonarCloud compte un fichier déplacé comme code neuf (80 %) ; les tests du module exigent PostgreSQL réel, exécuté par la CI.
- Plateformes : Ubuntu et Windows, sans différence.
