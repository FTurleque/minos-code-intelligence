# Remote & Distributed Indexing — M25

M25 ajoute deux frontières provider-neutral : une source Git distante immuable et un worker qui renvoie un artefact transporté. Le lifecycle local M14 reste propriétaire de la découverte, de la négociation, du staging, de l’import et de la promotion.

## Invariants de source

`RemoteRepositoryRequest` accepte uniquement :

- `https://github.com/<owner>/<repo>[.git]` ;
- `https://gitlab.com/<group...>/<repo>[.git]` ;
- une ref `refs/heads/...` ou `refs/tags/...` ;
- un commit SHA-1 complet de 40 caractères ;
- un sous-répertoire relatif confiné ;
- une référence optionnelle de credential conforme au nom d’une variable d’environnement.

User info, port non standard, query, fragment, segment `..`, SHA court et ref Git dangereuse sont rejetés avant tout accès réseau.

## Cache de source

`JGitRemoteRepositoryMaterializer` utilise `MINOS_HOME/remote-cache` :

```text
remote-cache/
  locks/<cache-key>.lock
  repositories/<cache-key>/
    entry.properties
    repository/.git
```

La clé SHA-256 couvre host, URI canonique, ref, commit, subdir et `FETCH_ONLY`. Le clone est shallow, sans submodule, validé propre puis déplacé atomiquement. Une entrée sale/corrompue est supprimée et reconstruite. La politique par défaut est 8 entrées / 10 GiB.

Le credential est converti en `char[]`, transmis au seul clone puis effacé au mieux. Sa valeur et le nom de la variable ne sont pas persistés ni rendus par la CLI.

## Worker et réseau

Le port `DistributedIndexing.Worker` reçoit l’`IndexingExecutionRequest` existante et la provenance distante. Le worker local de référence :

- exécute le provider derrière son process adapter existant ;
- copie les sources dans `PROCESS_EPHEMERAL_WORKSPACE` ;
- exclut `.git` ;
- refuse symlinks, entrées spéciales et dépassements de 100 000 fichiers / 2 GiB ;
- nettoie workspace et enveloppe de transport dans les chemins de succès et d’échec.

La politique `WorkerNetworkPolicy` est obligatoire. `DENY` échoue avec le backend natif parce que celui-ci ne peut pas prouver de filtre réseau OS. `ALLOW` est un consentement explicite, pas une promesse d’absence de trafic du provider.

## Format transport

`minos-distributed-artifact-v1` contient exactement :

```text
manifest.properties
index.scip
```

Le transport est borné : manifest limité à 64 KiB, artefact limité à 2 GiB et cache vérifié à 32 entrées / 5 GiB par défaut. Les champs inconnus, doublons, chemins supplémentaires, traversal, tailles incohérentes, checksum SHA-256 incorrect ou provenance divergente sont fail-closed.

Le coordinateur compare run, projet, URI, commit, langage, provider/version, worker, isolation et politique/enforcement réseau. L’artefact accepté rejoint ensuite le chemin SCIP normal ; aucun snapshot n’est promu par le worker.

## Composition et extension

`LocalRemoteIndexOperations` décore les `IndexerExecutor` choisis par le lifecycle existant avec `DistributedIndexerExecutor`. Aucun branchement langage/provider n’est ajouté à discovery.

Un backend distant futur implémente `Worker`. Pour annoncer `DENY`, `enforcesNetworkDeny()` doit être vrai et la plateforme doit réellement imposer cette politique. Transport, authentification du worker et attestation supplémentaires devront rester derrière l’adapter, sans affaiblir le manifest v1.

## Tests et gates

```powershell
.\mvnw.cmd -pl minos-integration-git,minos-cli -am test
python scripts/m25/check-remote-distributed.py
python scripts/docs/check-current-docs.py
```

La qualification finale est locale exact-head Windows + Linux ; GitHub Actions reste hors preuve en juillet 2026.
