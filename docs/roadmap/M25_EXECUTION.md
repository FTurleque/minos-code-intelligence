# M25 — Remote & Distributed Indexing — exécution

Statut : **TERMINÉ, VALIDÉ EXACT-HEAD WINDOWS + LINUX ET FUSIONNÉ DANS develop — 9/9.**

```text
Issue          : #84 — CLOSED / completed
PR             : #85 — MERGED
Branche        : m25-remote-distributed-indexing
Base           : develop @ b17631de59871848351a4139b12be6e0354989bc
Qualified HEAD : fc395d189cf7fc5a0e06130210a3dc763fc48637
Merge develop  : 1a82f18115184606cbc13a9070b7cc78643ebb35
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

### M25-S9 — Qualification finale exact-head Windows + Linux ✅ VALIDÉ

Les runners ont produit sur le même SHA exact et des worktrees propres :

```text
M25 FINAL REMOTE DISTRIBUTED INDEXING VALIDATION SUCCESS
Validated HEAD: fc395d189cf7fc5a0e06130210a3dc763fc48637

M25 LINUX REMOTE DISTRIBUTED INDEXING VALIDATION SUCCESS
Validated HEAD: fc395d189cf7fc5a0e06130210a3dc763fc48637
```

Les JSON d’évidence Windows et Linux enregistrent `status: PASS`, le commit exact, `scip-go@0.2.7`, le cache source `MISS→HIT→HIT`, le bundle/provenance vérifié et le snapshot actif promu. Les HEAD locaux/distants concordaient, les worktrees étaient propres et le diff `.github/workflows` était vide.

## Dispositions finales

| Surface | Disposition | Preuve / limite |
|---|---|---|
| GitHub.com HTTPS | `QUALIFIED_WITH_CONSTRAINTS` | dépôt privé, ref + SHA exacts, cache MISS→HIT et indexation jusqu’au snapshot actif sous Windows x86_64 et Linux x86_64 |
| GitLab.com HTTPS | `QUALIFIED_WITH_CONSTRAINTS` | dépôt public, ref + SHA exacts, cache MISS→HIT et indexation jusqu’au snapshot actif sous Windows x86_64 et Linux x86_64 ; credential privé contract-tested, pas de preuve live privée |
| worker natif local | `QUALIFIED_WITH_CONSTRAINTS` | `PROCESS_EPHEMERAL_WORKSPACE` + `ALLOW` sous Windows/Linux ; `DENY` est `BLOCKED/NOT_RUN` et échoue fermé faute d’isolation réseau OS |
| `minos-distributed-artifact-v1` | `QUALIFIED_WITH_CONSTRAINTS` | structure, tailles, SHA-256, provenance et nettoyage vérifiés sous Windows/Linux |
| caches source et artefact | `QUALIFIED_WITH_CONSTRAINTS` | bornes, reconstruction, verrouillage et rejet des entrées corrompues vérifiés |

La qualification principale a exercé le dépôt GitHub privé `FTurleque/minos-code-intelligence` au HEAD candidat. Une preuve live complémentaire sur le même produit qualifié a exercé le dépôt GitLab public `t-demo/terraform-lambda-example` au commit `0e16aeada066d2d48c900bcf98579048d29d21bb` sous les deux OS.

Restent hors qualification M25 : GitLab privé live, réseau `DENY` effectivement isolé, GitHub/GitLab Enterprise, SSH, submodules, scheduler/control plane hosted, workers non fiables et multi-tenant.

## Correctif post-merge — MINOS-02 : scope-swap distribué

Vulnérabilité identifiée après le merge de M25 : dans un monorepo, un worker peut renvoyer un bundle produit pour le module B alors que le coordinateur a demandé le module A, car `minos-distributed-artifact-v1` ne porte pas de `projectRelativeRoot`. Le correctif introduit `minos-distributed-artifact-v2` avec le champ `projectRelativeRoot` et ajoute sa comparaison cryptographique dans `verifyManifest`. Un manifest v1 est fail-closed pour toute requête non-racine. La clé de cache est partitionnée par scope. Les tests adversariaux (`ScopeSwapRejectionTest`) couvrent les six variantes d'attaque. Ce correctif est appliqué sur la branche `develop` et n'affecte ni les invariants M25 ni les dispositions finales.

## Critères de sortie

M25 a satisfait les critères de sortie :

1. les gates statiques/documentaires et le reactor Maven Java 24 passent ;
2. les caches restent bornés et les chemins/secrets non fiables sont rejetés ;
3. le e2e distant réel prouve ref + commit + cache-hit + provider + artefact + snapshot ;
4. Windows et Linux valident exactement le même HEAD propre ;
5. la PR est revue puis fusionnée dans `develop` avec protection du HEAD ;
6. l’issue #84 est fermée completed ;
7. la présente réconciliation documentaire enregistre les SHA réels et place M26 comme prochain jalon.
