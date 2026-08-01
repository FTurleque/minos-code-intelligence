# ADR-0033 — Immutable remote revisions and verified worker artifacts

Status: **Accepted — final M25 dispositions recorded from exact-head Windows + Linux evidence.**

Date: 2026-07-29

## Context

Une URL distante, une branche mobile ou un résultat de worker ne constituent pas une preuve reproductible. Les credentials peuvent fuiter dans une URL, un log, la configuration Git ou un manifest. Un ZIP non borné ou un `index.scip` dont la provenance est seulement déclarative peut contourner les invariants de staging et de promotion atomique.

M25 doit permettre l’indexation distante et une frontière d’exécution distribuable sans introduire un control plane hébergé ni faire confiance implicitement au réseau ou au worker.

## Decision

1. Une source distante est décrite par `RemoteRepositoryRequest` : endpoint HTTPS officiel `github.com` ou `gitlab.com`, ref canonique, commit SHA-1 complet, sous-répertoire relatif et politique `FETCH_ONLY`.
2. Les credentials sont référencés par nom de variable d’environnement et résolus uniquement pendant le clone. Ni valeur ni référence ne sont écrites dans l’origin Git, les métadonnées de cache, les manifests ou les évidences CLI.
3. `JGitRemoteRepositoryMaterializer` clone sans submodule dans une entrée temporaire, vérifie origin, HEAD exact, worktree propre et confinement du sous-répertoire, puis publie atomiquement dans un cache reconstructible et borné.
4. `DistributedIndexing.Worker` est provider-neutral. L’isolation déclarée est `PROCESS_EPHEMERAL_WORKSPACE`; la politique réseau est obligatoire. Un worker ne peut accepter `DENY` que s’il prouve réellement l’enforcement réseau OS.
5. Le transport `minos-distributed-artifact-v1` est un ZIP contenant exactement `manifest.properties` et `index.scip`. Le manifest porte source, commit, run, projet, langage, provider/version, worker, isolation, réseau, temps, taille et SHA-256.
6. Le coordinateur valide format, chemins, bornes, checksum et concordance intégrale de provenance en mode fail-closed. Le bundle reçu est détruit après acceptation ou rejet ; seul le cache vérifié borné subsiste.
7. L’artefact accepté retourne dans `ScipSymbolSnapshotImporter`, le staging et la promotion atomique existants. Les snapshots structurés restent autoritatifs.

## Consequences

- Une branche qui avance entre la demande et le clone produit un échec, jamais un autre snapshot silencieux.
- Le cache est une optimisation locale reconstructible, pas une autorité.
- Le backend natif de référence exige `ALLOW` explicite, car il ne revendique pas une isolation réseau OS qu’il ne possède pas.
- Un futur backend conteneur/VM peut implémenter le même port et prouver `DENY` sans modifier le cœur ni la CLI.
- GitHub/GitLab Enterprise, SSH, submodules, LFS externe, scheduler, workers non fiables et multi-tenant restent hors contrat M25.
- SCIP ne prouve toujours pas CFG, def-use, data-flow ou security capabilities ; ADR-0026/0028/0032 restent applicables.

## Rejected alternatives

- Indexer directement `HEAD` d’une branche : non reproductible.
- Placer un token dans l’URL ou le manifest : fuite persistante.
- Importer directement le ZIP du worker : contourne validation et staging.
- Présenter une simple copie de répertoire comme sandbox réseau : faux claim de sécurité.
- Construire dès M25 un orchestrateur hosted/multi-tenant : hors périmètre et prématuré.

## Evidence

Le HEAD `fc395d189cf7fc5a0e06130210a3dc763fc48637` a passé les runners exact-head Windows x86_64 et Linux x86_64 avant son merge dans `develop` via `1a82f18115184606cbc13a9070b7cc78643ebb35`.

Les dispositions finales sont `QUALIFIED_WITH_CONSTRAINTS` pour GitHub.com privé, GitLab.com public, le worker natif avec politique `ALLOW`, le transport `minos-distributed-artifact-v1` et les caches bornés. Le chemin credential GitLab privé reste contract-tested sans preuve live privée. Le réseau `DENY` demeure `BLOCKED/NOT_RUN` sur le worker natif et échoue fermé jusqu’à l’existence d’un backend prouvant l’isolation réseau OS.

La matrice détaillée et les marqueurs des gates exact-head sont enregistrés dans [`../roadmap/M25_EXECUTION.md`](../roadmap/M25_EXECUTION.md).
