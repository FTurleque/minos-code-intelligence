# Proposal

## Why

**AUD-SEC-03, lot « providers SCIP », partie installation** (sprint 8 de l'audit du 10 octobre 2026). Neuf entrées de `scripts/architecture/private-io-allowlist.json` (14 occurrences) nomment ce lot : l'installation des outils SCIP écrit, copie et déplace des archives, des binaires, des JAR et des arbres de modules avec `Files.newOutputStream`, `Files.copy`, `Files.move` et `Files.createTempFile` dans cinq fichiers de `minos-provider-scip` (`LockedNpmPackage`, `ManagedPolyglotScipRuntimeManager`, `ManagedScipProviderRuntimeManager`, `ManagedScipPythonRuntimeManager`, `PinnedArtifactSource`) et la copie du projet dans l'arbre de `scip-java` (`ScipJavaProcessPlanFactory`).

Ces écritures ne sont pas homogènes. Les unes produisent un fichier de plan de contrôle (verrou npm, archive téléchargée), les autres des milliers de fichiers dont l'accès **doit** venir de l'héritage du dossier (la Javadoc de `installClasspath` le dit : « un fichier protégé n'hériterait pas de l'accès que le bac à sable Windows accorde sur le dossier »). Une migration uniforme vers le durcissement par fichier casserait l'installation de `scip-java` sous Windows. D'où deux niveaux d'écriture dans le socle `completer-les-primitives-d-e-s-privees`.

## What Changes

- Fichier de plan de contrôle qui peut préexister (verrou npm) : `openPrivateOutputStream`. Ressources empaquetées lues par le conteneur (`scip-java-windows-runner.ps1`, patch) et exécutable Coursier : `openNewTreeFile` puis `DurableAtomicFile.replace`.
- Fichiers d'arbre (JAR du classpath `scip-java`, extraction ZIP, archives téléchargées, copie du projet pour `scip-java`) : `openNewTreeFile` (`CREATE_NEW`, aucun suivi de lien, ACL héritée).
- Déplacements de **fichiers** (archive vers sa place, binaire vers sa place) : `DurableAtomicFile.replace`. Déplacements de **répertoires** (arbres installés, swap `previous`) : `DurableAtomicFile.moveDirectory`. Le helper `move` unique de `ManagedScipProviderRuntimeManager` est scindé selon ce qu'il déplace.
- Le journal de commande temporaire de `ManagedPolyglotScipRuntimeManager.run` est créé par `createPrivateTempFile`.
- Le repli « `ATOMIC_MOVE` refusé, déplacement non atomique » disparaît des trois helpers qui le portaient : l'installation échoue fermée.
- Neuf entrées retirées.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `installation-des-providers` : une exigence ajoutée (l'installation écrit et publie par les primitives, sans repli non atomique).
- `stockage-prive-et-secrets` : une exigence ajoutée (un fichier d'arbre d'installation n'est jamais durci par fichier ni écrit en suivant un lien).

## Hors périmètre

- Le contenu et l'ordre d'installation, les empreintes SHA-256 épinglées (ADR 0040), les budgets d'extraction.
- Les `Files.createDirectories` de `ManagedScipProviderRuntimeManager` (`:827`, `:830`), `RustAnalyzerScipProcessPlanFactory` et `ScipJavaProcessPlanFactory` (`:118`, `:237`, `:258`, `:277`) : entrées durables, l'héritage d'ACL du bac à sable en dépend.
- Les suppressions (`Files.deleteIfExists`, `FileTreeOperations.deleteRecursively`), hors du gate.
- L'ingestion : `migrer-l-ingestion-scip-vers-les-primitives`.

## Impact

- **Modules du reactor** : `minos-provider-scip` seul. Dépend du socle.
- **Surfaces publiques** : aucune.
- **ADR** : aucun créé ni amendé (ADR 0040 inchangé : l'empreinte est toujours vérifiée avant exécution).
- **Gates à rejouer** : `python scripts/architecture/check-private-io.py`, `python scripts/quality/check-tools-manifest.py`, `python scripts/quality/check-polyglot-provider-consistency.py`, `python scripts/remediation/check-mnd.py`, `check-mne.py`, `check-post-mne.py`, `check-p0-p2.py`, `python scripts/quality/check-jacoco.py` (scope `m24-polyglot-provider-platform`, rouge sous Windows hors régression ; planchers par classe `ManagedScipProviderRuntimeManager` 0,61 / 0,49, `ManagedPolyglotScipRuntimeManager` 0,28 / 0,20, `ScipJavaProcessPlanFactory` 0,35 / 0,15), `python .claude/scripts/run_gates.py --fast`.
- **Plateformes** : Windows (Coursier, Node, Maven, `dotnet`) et Linux (`scip-java`, `npm`) ; le comportement de l'héritage d'ACL est la garantie à qualifier sous Windows.
