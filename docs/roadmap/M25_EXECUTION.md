# M25 — Remote & Distributed Indexing — exécution

Statut : **EN COURS — S1→S8 implémentés et couverts localement ; S9 / qualification exact-head Windows + Linux en attente.**

```text
Issue          : #84 — OPEN / in progress
PR             : à ouvrir en Draft
Branche        : m25-remote-distributed-indexing
Base           : develop @ b17631de59871848351a4139b12be6e0354989bc
Qualified HEAD : en attente
Merge develop  : en attente
Date           : 29 juillet 2026
```

M21-S2 / GitHub Actions reste **strictement en pause jusqu’en août 2026**. M25 ne modifie, n’exécute et n’utilise aucun workflow CI comme preuve.

## Question produit

> MINOS peut-il indexer une révision distante immuable et distribuer l’exécution d’un provider sans abandonner la reproductibilité, la confidentialité, la provenance ni l’autorité du snapshot local ?

## Périmètre retenu

- dépôts HTTPS officiels `github.com` et `gitlab.com` uniquement ;
- branche ou tag explicite plus commit Git SHA-1 complet de 40 caractères ;
- cache de sources local, reconstructible, verrouillé et borné à 8 entrées / 10 GiB par défaut ;
- secret résolu par référence de variable d’environnement uniquement pendant le clone ;
- worker provider-neutral dans un workspace éphémère copié, sans `.git` ni liens symboliques ;
- politique réseau worker obligatoire `ALLOW` ou `DENY` ;
- `DENY` fail-closed tant qu’un backend ne prouve pas l’isolation réseau OS ;
- bundle ZIP v1 contenant exactement `manifest.properties` et `index.scip` ;
- validation stricte taille, chemin, champs, SHA-256 et provenance avant import ;
- cache d’artefacts vérifiés borné à 32 entrées / 5 GiB, artefact individuel 2 GiB maximum ;
- staging SCIP et promotion atomique M1/M14 inchangés et autoritatifs.

Sont hors périmètre M25 : SSH arbitraire, clone non épinglé, orchestration hébergée, scheduler, contrôle multi-tenant et confiance implicite d’un worker. Ces sujets appartiennent notamment à M27.

## Architecture

```text
GitHub/GitLab HTTPS + ref + SHA exact
                  ↓
JGitRemoteRepositoryMaterializer
  cache local borné + checkout propre + HEAD vérifié
                  ↓
RemoteIndexOperations / lifecycle autonome existant
                  ↓
DistributedIndexerExecutor
                  ↓
Worker provider-neutral
  PROCESS_EPHEMERAL_WORKSPACE + policy réseau explicite
                  ↓
minos-distributed-artifact-v1
  manifest.properties + index.scip + SHA-256 + provenance
                  ↓
DistributedArtifactBundleStore
  validation fail-closed + cache local borné
                  ↓
ScipSymbolSnapshotImporter → staging → promotion atomique
```

Le transport ne devient jamais une source de vérité. Seul l’artefact accepté, normalisé, staged puis promu par le lifecycle existant devient visible dans le snapshot actif.

## Sous-incréments

### M25-S1 — Cadrage, menaces, issue et ADR ✅ IMPLÉMENTÉ

Issue #84, branche dédiée, ADR-0033, périmètre explicite et invariants secrets/réseau/provenance.

### M25-S2 — Révision distante immuable ✅ IMPLÉMENTÉ

`RemoteRepositoryRequest` impose HTTPS, hôtes officiels, ref canonique, SHA complet, sous-répertoire confiné et politique `FETCH_ONLY`.

### M25-S3 — Matérialisation et cache de sources ✅ IMPLÉMENTÉ

JGit clone sans submodule, depth 1, origin canonique, HEAD exact et worktree propre ; publication atomique, verrou inter-processus, reconstruction des entrées corrompues et éviction bornée.

### M25-S4 — Contrat worker et isolation ✅ IMPLÉMENTÉ

`DistributedIndexing.Worker` reste provider-neutral. Le worker natif copie une workspace éphémère bornée, exclut `.git`, rejette liens symboliques et entrées spéciales, puis détruit workspace et enveloppe de transport.

### M25-S5 — Transport d’artefact versionné ✅ IMPLÉMENTÉ

Format `minos-distributed-artifact-v1`, deux entrées exactes, manifest strict, tailles bornées, SHA-256 et provenance source/provider/worker/réseau complètes.

### M25-S6 — Acceptation et promotion locale ✅ IMPLÉMENTÉ

Le coordinateur compare toutes les dimensions du manifest à la requête exacte avant de rendre `index.scip` au pipeline SCIP existant. Aucun chemin de promotion parallèle n’est ajouté.

### M25-S7 — CLI et documentation ✅ IMPLÉMENTÉ

`minos remote materialize` et `minos remote index` exposent un contrat opt-in, JSON/text, sans sérialiser le secret ni le nom de sa variable dans les évidences.

### M25-S8 — Tests sécurité, cache et e2e local ✅ IMPLÉMENTÉ

Tests des URL/ref/subdir, credentials, cache source, worktree sale, commit inattendu, traversal/entrée inconnue/tampering/oversize, cache artefact, provenance malveillante, réseau fail-closed et parcours complet jusqu’au snapshot actif.

### M25-S9 — Qualification finale exact-head Windows + Linux ⏳ EN ATTENTE

Les runners doivent produire sur le même SHA :

```text
M25 FINAL REMOTE DISTRIBUTED INDEXING VALIDATION SUCCESS
Validated HEAD: <sha>

M25 LINUX REMOTE DISTRIBUTED INDEXING VALIDATION SUCCESS
Validated HEAD: <sha>
```

Ils doivent en outre vérifier worktree propre, HEAD stable, zéro modification `.github/workflows`, cache-hit déterministe, commit exact, bundle/provenance vérifiés et snapshot actif promu.

## Critères de sortie

M25 n’est terminé que si :

1. les gates statiques/documentaires et le reactor Maven Java 24 passent ;
2. les caches restent bornés et les chemins/secrets non fiables sont rejetés ;
3. le e2e distant réel prouve ref + commit + cache-hit + provider + artefact + snapshot ;
4. Windows et Linux valident exactement le même HEAD propre ;
5. la PR est revue puis fusionnée dans `develop` avec protection du HEAD ;
6. l’issue #84 est fermée completed ;
7. une PR documentaire post-merge enregistre les SHA réels et place M26 comme prochain jalon.
