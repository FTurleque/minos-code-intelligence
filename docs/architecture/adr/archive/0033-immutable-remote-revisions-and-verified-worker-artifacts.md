---
id: ADR-0033
title: "Immutable remote revisions and verified worker artifacts"
domain: security
rules: false
---

# ADR-0033 — Immutable remote revisions and verified worker artifacts

> **Archivé le 2026-10-23** (tri du registre, [ADR 0063](0063-ecrire-les-adr-au-format-archgate-dans-le-registre-arc42.md)) : Superseded by [ADR-0070](../SEC-0070-code-non-fiable-confinement-et-indexation-distante.md). Ce document est conservé pour l'historique ; il n'est plus lu par Archgate.

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
5. Le transport `minos-distributed-artifact-v1` est un ZIP contenant exactement `manifest.properties` et `index.scip`. Le manifest porte source, commit, run, projet, langage, provider/version, worker, isolation, réseau, temps, taille et SHA-256. Le format `minos-distributed-artifact-v2` étend le manifest avec `projectRelativeRoot` pour les monorepos multi-module.
6. Le coordinateur valide format, chemins, bornes, checksum et concordance intégrale de provenance en mode fail-closed — y compris le `projectRelativeRoot` du manifest face à la requête, ce qui rend un scope-swap entre modules cryptographiquement impossible. Le bundle reçu est détruit après acceptation ou rejet ; seul le cache vérifié borné subsiste.
7. L’artefact accepté retourne dans `ScipSymbolSnapshotImporter`, le staging et la promotion atomique existants. Les snapshots structurés restent autoritatifs.

## Do's and Don'ts

### Do

- Décrire une source distante par `RemoteRepositoryRequest` : endpoint HTTPS officiel `github.com` ou `gitlab.com`, ref canonique, commit SHA-1 complet, sous-répertoire relatif et politique `FETCH_ONLY`.
- Référencer les credentials par nom de variable d'environnement et ne les résoudre que pendant le clone.
- Valider en fail-closed le format, les chemins, les bornes, le checksum et la concordance intégrale de provenance de l'artefact du worker, `projectRelativeRoot` compris.
- Faire repasser l'artefact accepté par `ScipSymbolSnapshotImporter`, le staging et la promotion atomique existants.

### Don't

- N'écrire ni la valeur ni la référence d'un credential dans l'origin Git, les métadonnées de cache, les manifests ou les évidences CLI.
- Ne pas faire accepter `DENY` par un worker qui ne prouve pas l'enforcement réseau OS.
- Ne pas indexer directement le `HEAD` d'une branche.
- Ne pas importer directement le ZIP du worker.

## Consequences

- Une branche qui avance entre la demande et le clone produit un échec, jamais un autre snapshot silencieux.
- Le cache est une optimisation locale reconstructible, pas une autorité.
- Le backend natif de référence exige `ALLOW` explicite, car il ne revendique pas une isolation réseau OS qu’il ne possède pas.
- Un futur backend conteneur/VM peut implémenter le même port et prouver `DENY` sans modifier le cœur ni la CLI.
- GitHub/GitLab Enterprise, SSH, submodules, LFS externe, scheduler, workers non fiables et multi-tenant restent hors contrat M25.
- SCIP ne prouve toujours pas CFG, def-use, data-flow ou security capabilities ; ADR-0026/0028/0032 restent applicables.

## Compliance and Enforcement

- Gate `scripts/quality/check-remote-distributed-consistency.py`, exécuté par `scripts/remediation/check-post-mne.py` (job `invariants`) — vérifie dans les sources la validation de `RemoteRepositoryRequest` (hôtes, `https`, SHA-1 complet, `FETCH_ONLY`, ni `http://`, ni `ssh://`, ni `git@`), le clone sans submodule ni journalisation de credential, le cache borné, et que cet ADR, la roadmap M25 et les guides énoncent les mêmes faits (ligne de statut comprise).
- Revue : tout nouveau chemin d'import d'artefact distant passe par la validation du coordinateur et par le staging, jamais directement par le magasin.

## Rejected alternatives

- Indexer directement `HEAD` d’une branche : non reproductible.
- Placer un token dans l’URL ou le manifest : fuite persistante.
- Importer directement le ZIP du worker : contourne validation et staging.
- Présenter une simple copie de répertoire comme sandbox réseau : faux claim de sécurité.
- Construire dès M25 un orchestrateur hosted/multi-tenant : hors périmètre et prématuré.

## Evidence

Le HEAD `fc395d189cf7fc5a0e06130210a3dc763fc48637` a passé les runners exact-head Windows x86_64 et Linux x86_64 avant son merge dans `develop` via `1a82f18115184606cbc13a9070b7cc78643ebb35`.

Les dispositions finales sont `QUALIFIED_WITH_CONSTRAINTS` pour GitHub.com privé, GitLab.com public, le worker natif avec politique `ALLOW`, le transport `minos-distributed-artifact-v1` et les caches bornés. Le chemin credential GitLab privé reste contract-tested sans preuve live privée. Le réseau `DENY` demeure `BLOCKED/NOT_RUN` sur le worker natif et échoue fermé jusqu’à l’existence d’un backend prouvant l’isolation réseau OS.

La matrice détaillée et les marqueurs des gates exact-head sont enregistrés dans [`../../project/roadmap/M25_EXECUTION.md`](../../../project/roadmap/M25_EXECUTION.md).

## Écarts constatés

Relevés le 2026-10-23 (audit des ADR) ; la décision ci-dessus reste celle en vigueur tant qu'un amendement ou une correction du code n'a pas tranché.

- La conséquence sur le backend natif et la section « Evidence » consignent `QUALIFIED_WITH_CONSTRAINTS` pour le worker natif avec politique `ALLOW` ; au HEAD, `remote index` refuse avant toute matérialisation tant qu'aucun bac à sable n'est qualifié pour du code non fiable, en `ALLOW` comme en `DENY` (ADR 0036, « Costs » ; ADR 0041) — preuve : `minos-cli/src/main/java/com/minos/cli/operations/LocalRemoteIndexOperations.java:102,111-119` ; `README.md:197`.
