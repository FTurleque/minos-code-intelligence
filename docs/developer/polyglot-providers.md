# Développer et qualifier un provider polyglotte

M24 ne crée pas de seconde architecture provider. Un nouvel écosystème doit traverser les mêmes frontières M17 que Java, TypeScript, Kotlin et Python.

## Frontières

```text
DefaultDiscoveryPlugins
  -> ProjectDetector / BuildSystemDetector / SourceRootDetector / LanguageDetector

ScipIndexerCatalog
  -> IndexerDescriptor
  -> ProviderCapabilityProfile
  -> ProviderOperationalProfile

CompositeProviderRuntimeManager
  -> ProviderRuntimeManager extension
  -> IndexerProcessPlanFactory
  -> ProcessIndexerExecutor

index.scip
  -> ScipIngestionAdapter
  -> ScipSymbolNormalizer
  -> ScipSymbolSnapshotImporter
  -> snapshot structuré MINOS
```

`ProjectDiscoveryService`, CLI, MCP, IntelliJ et NEXUS ne doivent pas recevoir un `switch` par langage. Ils consomment les contrats partagés.

## Les trois profils obligatoires

### `IndexerDescriptor`

Déclare :

- `id` et version ;
- langages et builds ;
- capabilities utilisables pour la négociation ;
- disposition `QUALIFIED`, `QUALIFIED_WITH_CONSTRAINTS` ou `EXPERIMENTAL` ;
- score/priorité de négociation ;
- limitations visibles.

### `ProviderCapabilityProfile`

Le profil contient **chaque** valeur de `IndexerCapability`. Il n'existe pas de valeur implicite.

Un provider qui émet des symboles/références ne reçoit pas automatiquement `CALL_RELATIONS`, CFG, def-use, data-flow ou sécurité. Les analyses avancées M22 sont une preuve séparée.

### `ProviderOperationalProfile`

M24 ajoute des preuves opérationnelles distinctes des capabilities :

- plateformes réellement qualifiées ;
- exigences runtime ;
- readiness ;
- comportement d'installation ;
- comportement stable identity ;
- comportement provenance.

Un provider expérimental peut avoir zéro plateforme qualifiée avant les gates. Une promotion finale ne le peut pas : la plateforme annoncée doit correspondre à un log exact-head concret.

## Runtimes locaux

Une installation gérée doit rester sous :

```text
MINOS_HOME/tools/<provider>/<version>/
```

M24 applique deux modèles :

- `scip-dotnet` et `scip-go` : installation locale gérée et versionnée ;
- `scip-clang` et `rust-analyzer` : runtime operator-managed, parce que M24 refuse d'installer implicitement des toolchains compilateur ou de prétendre à une portabilité non prouvée.

Toute installation doit être fail-closed et atomique : préparation dans un répertoire partiel, validation de l'exécutable/version, puis remplacement du répertoire final.

## Opt-in expérimental

La négociation automatique utilise `IndexingRequirements.baseline()` et exclut les providers expérimentaux.

Un `--provider <id>` explicite construit le même ensemble de capabilities requises avec `allowExperimental=true`. Cet override sert aux fixtures et à l'évaluation ; il n'est pas une promotion.

## Stable identity

Le pipeline SCIP conserve deux niveaux :

- symboles locaux : `STRUCTURAL_FALLBACK`, déterministe sur projet/langage/kind/qualified-name/signature ;
- symboles externes : `PROVIDER_SCOPED_FALLBACK`.

La raw identity upstream est préservée dans `ProviderReference`. M24 exige :

1. deux indexations identiques produisant les mêmes identités MINOS ;
2. absence de collisions triviales entre namespaces/packages/modules homonymes ;
3. aucune déclaration de canonicité cross-provider non démontrée.

## Provenance

Chaque fait normalisé conserve `Origin` :

```text
providerId
sourceKind = SCIP_INDEXER
providerVersion
indexRunId
source = SCIP
```

Un index run différent peut changer la provenance de run sans changer l'identité stable du symbole.

Une référence externe/non résolue n'est jamais convertie arbitrairement en relation locale.

## Build fingerprints

M24 étend la politique de build fingerprint avec :

```text
CMakeLists.txt
compile_commands.json
*.csproj
*.sln
go.mod
go.sum
go.work
Cargo.toml
Cargo.lock
```

Le store de fingerprints conserve la compatibilité FORMAT_VERSION=1 : les snapshots historiques dont le `buildHash` a été calculé avec la politique M17 restent vérifiables, tandis que les nouvelles captures utilisent la politique M24.

## Fixtures M24

```text
fixtures/m24/clang
fixtures/m24/csharp
fixtures/m24/go
fixtures/m24/rust
```

Une fixture doit rester petite, sans dépendance externe non nécessaire, et être copiée vers un espace temporaire lors d'une indexation réelle afin de ne jamais salir le checkout avec `index.scip`, `build/`, `bin/`, `obj/`, `target/` ou autres artefacts.

## Qualification

Tests structurels :

```text
M24PolyglotDiscoveryTest
M24PolyglotProviderTest
M24PolyglotIdentityProvenanceTest
M24PolyglotProcessPlanFactoryTest
ManagedPolyglotScipRuntimeManagerTest
```

Gate statique :

```text
python scripts/m24/check-polyglot.py
```

Runners exact-head :

```powershell
.\scripts\m24\run-final.ps1 -ExpectedHead <sha>
```

```bash
./scripts/m24/run-final.sh <sha>
```

Les runners doivent vérifier le HEAD et le worktree au début et à la fin, interdire tout changement M24 sous `.github/workflows`, réutiliser les gates historiques applicables et produire un marqueur de succès unique. Un changement après le PASS, même documentaire, impose un nouveau replay.

## Sémantique M23

Le provider polyglotte produit d'abord des faits structurés. Le pipeline M20/M23 peut ensuite fabriquer des documents sémantiques, mais :

- le snapshot structuré reste autoritatif ;
- les résultats learned restent `HEURISTIC` ;
- l'opt-in M23 reste obligatoire ;
- `KEEP_CURRENT_M20_BACKEND` reste la décision en vigueur.
