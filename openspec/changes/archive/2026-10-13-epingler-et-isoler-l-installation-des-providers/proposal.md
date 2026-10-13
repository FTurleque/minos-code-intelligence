# Proposal

## Why

Constat **AUD-SEC-02** (moyenne, effort M, sprint 2 de l'audit du 10 octobre 2026) : sous Linux, `minos tools install scip-java` fait résoudre par Coursier les jars de scip-java **et leurs dépendances transitives**, sans empreinte épinglée, puis **exécute** le programme (`coursier launch … -- --version`, pour « sonder » sa version) sur l'hôte, hors bac à sable, avec l'**environnement complet** de MINOS. Cet environnement peut contenir `MINOS_TEAM_KEY_*` (clés maîtresses du plan de contrôle), `MINOS_TEAM_TOKEN`, `GITHUB_TOKEN`, `MINOS_REMOTE_TOKEN*`. L'intégrité repose alors sur TLS et sur la configuration Coursier de l'utilisateur (miroirs, `COURSIER_REPOSITORIES`), non sur le catalogue épinglé que l'ADR 0040 applique ailleurs. Un amont compromis, ou un miroir hostile, vaut exécution de code avec ces secrets.

Le relevé au HEAD montre que le défaut d'environnement est **plus large que le seul scip-java** : les six lancements de commandes des gestionnaires de providers (`coursier --help`, `coursier launch`, `npm ci` pour scip-typescript et scip-python, `dotnet tool install`, `go install`) et les sondes `dotnet --version` / `go version` héritent tous de l'environnement complet, alors que `ProcessIndexerExecutor` retire explicitement l'environnement des providers (« it may carry tokens and passwords »).

## What Changes

- **Environnement minimal pour toute commande d'installation et de sondage** : une façade publique du module `minos-runtime-local` construit l'environnement à partir de la liste d'autorisation déjà mesurée pour les providers, complétée de `HOME`/`USER`/`LOGNAME` (Linux), des variables de proxy et d'autorité de certification, et de quelques caches. Un motif de secret (`MINOS_*`, `*TOKEN*`, `*SECRET*`, `*PASSWORD*`, `*KEY*`, `*CREDENTIAL*`, `GITHUB_*`, `NPM_*`) est exclu **même s'il figurerait dans la liste**. Les gestionnaires `ManagedScipProviderRuntimeManager`, `ManagedScipPythonRuntimeManager` et `ManagedPolyglotScipRuntimeManager` l'appliquent à leurs lancements.
- **Classpath de scip-java épinglé jar par jar, sur toutes les plateformes** : un verrou (liste de jars avec SHA-256 ; généré à la construction par la recette `coursier-fetch` déjà employée pour Windows, tenu par le catalogue des outils embarqués) est contrôlé après `coursier fetch --classpath` et avant tout usage. Tout jar absent, en trop ou différent fait échouer l'installation et supprime le résultat partiel.
- **Plus de lancement de scip-java pour « sonder » sa version** : la version est celle du verrou (l'empreinte du jar de scip-java est celle du jar 0.13.1) ; `scipJavaInstallationProbe` et `requireExpectedScipJavaVersion` disparaissent.
- **Gate de catalogue** : `check-tools-manifest.py` vérifie la cohérence verrou ↔ catalogue.
- **ADR 0040 amendé** (section « Mise en œuvre ») : une troisième nature de composant, « classpath résolu à l'installation, vérifié contre un verrou ».
- Une capacité `installation-des-providers` est créée.

## Capabilities

### New Capabilities

- `installation-des-providers` : ce que `minos tools install` (et les sondes de toolchain) garantit sur l'environnement qu'il transmet et sur l'intégrité de ce qu'il exécute. Capacité nouvelle : `confinement-code-non-fiable` porte le bac à sable des providers **à l'indexation** ; l'installation est exécutée par un lanceur de confiance hors de ce bac à sable.

### Modified Capabilities

(aucune)

## Hors périmètre

- **Les autres constats du sprint 2** (AUD-SEC-01 : un autre changement ; AUD-SEC-12 et SEC-13 : un autre changement).
- **L'exécution de scip-java à l'indexation sous Linux** : `ScipJavaProcessPlanFactory` y lance encore `coursier launch` (le fichier de classpath n'y est « pas utilisé hors Windows »). Faire consommer le classpath vérifié par le plan Linux (`java -cp @classpath.txt`, liaison en lecture seule dans bubblewrap) est un changement de confinement distinct : décision D2 de `design.md`.
- **Le build de l'image Docker** (`docker/Dockerfile.mcp.release`, étape `cs launch` puis `cs bootstrap`) : exécuté chez le mainteneur, pas sur le poste de l'utilisateur ; la lacune est déjà reconnue en commentaire dans le Dockerfile. Le même verrou pourra l'alimenter plus tard.
- **La vérification du classpath Windows contre le verrou** : il reste vérifié par le manifeste du paquet signé (ADR 0040) ; l'aligner sur le verrou est possible, non requis.
- **Un contournement de l'environnement** (`MINOS_TOOLS_ENV_PASSTHROUGH`, etc.) : non ajouté (décision D4) ; un miroir d'entreprise se déclare par le fichier de configuration de Coursier, non par l'environnement.
- Les toolchains du projet analysé (ADR 0040 « hors périmètre »).

## Impact

- **Modules du reactor touchés** : `minos-runtime-local` (une classe publique d'environnement et ses tests), `minos-provider-scip` (trois gestionnaires, le catalogue `embedded-tools.json`, un verrou en ressource, tests), scripts `scripts/release/build-embedded-tools.py`, `scripts/quality/check-tools-manifest.py` et leurs auto-tests. `minos-app` : aucun.
- **Surfaces publiques impactées** : la CLI `minos tools install` / `tools verify` / `doctor` (diagnostics : message d'échec de vérification du classpath ; plus de sortie « scip-java version » dans le journal d'installation). Aucune sortie JSON, aucun schéma, MCP et plugin inchangés. Les goldens de caractérisation ne doivent pas bouger.
- **ADR** : **amende l'ADR 0040** (§ Mise en œuvre : troisième nature de composant). Aucun nouvel ADR. La façade d'environnement peut justifier une ligne dans l'ADR 0044 (une classe publique du module `runtime-local` utilisée par un adaptateur) : à constater à l'écriture, non présumé.
- **Gates à rejouer** : `python scripts/quality/check-tools-manifest.py` (avec `--distribution` et `--variant` selon le cas) et son auto-test `test_check_tools_manifest.py`, `python scripts/release/sync-tools-manifest.py` (catalogue ↔ `Dockerfile.mcp.release`), `python scripts/remediation/check-mnd.py` (lit `ManagedScipProviderRuntimeManager.java`), `python scripts/architecture/check-module-boundaries.py`, `python scripts/architecture/check-private-io.py`, `python scripts/docs/check-current-docs.py`, `python scripts/quality/check-polyglot-provider-consistency.py`. JaCoCo : scope `provider-execution-trust-boundary` (contient `ProviderProcessEnvironment`) et scopes de `minos-provider-scip` ; SonarCloud juge le nouveau code module par module (≥ 80 %).
- **Plateformes** : Windows et Linux. Le verrou et la façade valent pour les deux ; sous Windows le classpath reste livré par le paquet (aucun `coursier fetch` à l'installation). La preuve réelle de l'installation Linux (réseau, Coursier) est une tâche manuelle.
