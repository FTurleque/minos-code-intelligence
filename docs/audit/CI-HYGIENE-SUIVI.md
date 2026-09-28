# Suivi — chantier hygiène CI et dépôt (C1, G3)

> Branche : `ci-hygiene/audit-remediation` (depuis `develop`).
> Constats : **C1** (douze workflows qui se chevauchent sur chaque PR, § 10 de `AUDIT-2026-09.md`) et **G3** (prolifération de scripts et de workflows par jalon, `AUDIT-2026-09.md:201-202`).
> Règle : aucune CI déclenchée pendant ce chantier. Les durées ci-dessous viennent de `gh run view/list` (lecture seule, aucune exécution provoquée) sur des runs déjà terminés des PR #297/#298 (26–27 septembre 2026) et d'un run M19/M20 du 30 août 2026.
> Rien n'est retiré avant que la table « invariant → gate qui l'applique désormais » ne soit complète et vérifiée indépendamment (`verif-ci`, `verif-gates`).

## 1. Méthode

Chaque workflow déclenché sur `pull_request`/`push` a été lu intégralement (déclencheurs, jobs, `if:`, gates appelés, permissions). Les durées « observées » viennent de trois runs réels :
- PR #297 (`hautes/audit-remediation` → `develop`, run 12:31:39 UTC le 27/09)
- PR #298 (`hautes/impl-hexagone` → `develop`, run 21:17:54 UTC le 27/09)
- Dernier run M19/M20 déclenché par un chemin filtré : promotion `develop` → `main` du 30/08 (aucun run plus récent : M19/M20 ne se sont pas déclenchés sur les PR #292–299, aucune d'elles ne touchant `minos-domain/application/api/mcp` selon leurs filtres)

## 2. Inventaire des workflows déclenchés sur `pull_request`/`push`

| Workflow | Déclencheur | Jobs | Gates exécutés | Durée observée | Seul à vérifier |
|---|---|---|---|---|---|
| [`pr-ci.yml`](../../.github/workflows/pr-ci.yml) | `pull_request`/`push` main+develop, sans filtre de chemin | `vulnerability-scan` (osv-scanner réutilisable) ; `verify` (matrice ubuntu-24.04/windows-2022) | vuln-scan (:18-30) ; par OS (:40-181) : exact-head (:41-66), `check-workflow-pins.py` (:91-92), `check-audit-remediation-v2.py` (:94-96, Unix only), Inno provenance ps1 (:98-100), `check-p0-p2.py` (:102-103), `check-minos-01.py` (:105-106), `check-module-boundaries.py` (:108-109), sandbox Linux + cgroup (:111-120, Unix only), **`mvnw clean verify`** (:127-138), `check-jacoco.py` (:140-142/160-162), `product-facts.py --check` (:164-165), tests unitaires `check-docker-upgrade-evidence` (:167-168) | vuln-scan 27 s ; verify ubuntu 4 min 46 s ; verify windows 7 min 11 s (job 36351195062) | Seul à faire tourner la suite Maven complète sous Windows, seul à vérifier `check-audit-remediation-v2`/`check-p0-p2`/`check-minos-01`/`check-module-boundaries`/l'osv-scan/les tests unitaires du vérificateur Docker upgrade |
| [`mnd-remediation.yml`](../../.github/workflows/mnd-remediation.yml) | `pull_request`/`push` main+develop, sans filtre | `invariants` (matrice ubuntu-latest/windows-latest) | exact-head (:26-47) ; `check-workflow-pins.py` (:54-55) ; `check-mnd.py` (:57-58) | ubuntu 5 s, windows 35 s (job 36351194630) | Seul à vérifier `check-mnd.py` (invariants MND-01..17) |
| [`mne-remediation.yml`](../../.github/workflows/mne-remediation.yml) | `pull_request`/`push` main+develop, sans filtre | `invariants` (matrice ubuntu-latest/windows-latest) | exact-head ; `check-workflow-pins.py` (:54-55) ; `check-mne.py` (:57-58) | ubuntu 7 s, windows 18 s (job 36351194644) | Seul à vérifier `check-mne.py` (invariants MNE-01..17) |
| [`post-mne-remediation.yml`](../../.github/workflows/post-mne-remediation.yml) | `pull_request`/`push` main+develop, sans filtre | `invariants` (matrice ubuntu-latest/windows-latest) | exact-head ; `check-workflow-pins.py` (:61-62) ; `check-post-mne.py` (:64-65, **exécute aussi par sous-processus les 9 gates de jalon actifs `ACTIVE_MILESTONE_GATES`, voir § G3**) ; test de régression `ProviderSandboxSecurityRegressionTest` (:72-84) ; `product-facts.py --check` (:86-87) | ubuntu 22 s, windows 45 s (job 36351194667) | Seul à vérifier `check-post-mne.py` **et donc, indirectement, les 9 gates de jalon M21–M28 encore actifs** ; seul (avec pr-ci) à lancer `ProviderSandboxSecurityRegressionTest` en isolation |
| [`post-228-hardening.yml`](../../.github/workflows/post-228-hardening.yml) | `pull_request`/`push` main+develop, sans filtre | `verify-hardening` (ubuntu-24.04 seul) | exact-head ; `check-current-docs.py` (:44, **`scripts/docs/check-current-docs.py`**, à distinguer de `scripts/m28/check-current-docs.py`, voir § G3) ; `check-post228-hardening.py` (:46-47) | 5 s (job 36351194636) | Seul à vérifier `check-post228-hardening.py` ; seul appel direct à `scripts/docs/check-current-docs.py` dans un workflow PR |
| [`docker-release-validation.yml`](../../.github/workflows/docker-release-validation.yml) | `pull_request`/`push` main+develop, **sans filtre de chemin**, `timeout-minutes: 90` | `provider-complete-image` (ubuntu-latest) | exact-head ; `scripts/ci/qualify-docker-release.sh` (:55, qui fait son propre `mvnw package -DskipTests -DskipITs` puis `docker build`) | 2 min 58 s à 3 min 4 s observés (jobs 36351194606, 36319281174) — loin du plafond de 90 min alloué | Seul à construire et qualifier l'image Docker complète |
| [`m19-advanced-code-intelligence.yml`](../../.github/workflows/m19-advanced-code-intelligence.yml) | `pull_request` main+develop, **filtré** (`minos-domain/**`, `minos-application/**`, `minos-api/**`, `minos-mcp/**`, docs M19, `scripts/m19/**`, le workflow lui-même) | `java` (ubuntu, `clean verify`) ; `m19-final-windows` (**mort**, voir § 5) | sandbox Linux+cgroup ; `product-facts.py --check` (:65) ; `mvnw clean verify` (:70-75) ; `check-jacoco.py` (:78) | java 2 min 39 s (job 33338389363, 30/08) — ne s'est pas redéclenché depuis sur `develop` (aucune des PR #292–299 ne touche les chemins filtrés) | Le seul, avec m20, à refaire un `clean verify` complet dès qu'un chemin M19 est touché (2ᵉ/3ᵉ build Maven complet de la PR) |
| [`m20-semantic-hybrid-intelligence.yml`](../../.github/workflows/m20-semantic-hybrid-intelligence.yml) | `pull_request` main+develop, **filtré** (`minos-domain/**`, `minos-storage-local/**`, `minos-application/**`, `minos-api/**`, `minos-mcp/**`, `minos-nexus/**`, docs M20, `scripts/m20/**`, le workflow lui-même) | `java` (ubuntu, `clean verify`) ; `m20-final-windows` (**mort**, voir § 5) | identique à M19 | java 2 min 45 s (job 33338389356, 30/08) — idem, pas redéclenché depuis | Idem M19, 3ᵉ build Maven complet potentiel |
| [`intellij-plugin.yml`](../../.github/workflows/intellij-plugin.yml) | `pull_request`/`push` main+develop, filtré (`minos-intellij/**`, `minos-cli/**`, `minos-integration-git/**`, docs plugin, M18, `scripts/m18/**`, le workflow) | `plugin` (ubuntu, Gradle) ; `windows-ownership` (windows, Gradle) | garde anti-fuite `com.minos:` (:58-64) ; build/test/verify Gradle (:66-72) ; Plugin Verifier (:70-72) ; tests Windows (:110-113) | 5 min 27 s à 5 min 54 s observés (déclenché sur les deux dernières PR, qui touchaient `minos-cli`) | Seul à construire/vérifier le plugin IntelliJ (Gradle, hors reactor Maven) |
| [`windows-installer.yml`](../../.github/workflows/windows-installer.yml) | `pull_request`/`push` main+develop, filtré (`packaging/windows/**`, `scripts/install/**`, `scripts/release/**`, `scripts/m14/MinosNativeMcpSmoke.java`, `docker/**`, `pom.xml`, `minos-*/pom.xml`, docs release, le workflow, `release-windows.yml`) | `candidate` (windows-2022) | Inno provenance ps1 ; installation Inno Setup qualifiée ; build candidat ; installation/handshake MCP/désinstallation isolée ; vérification des artefacts | 4 min 26 s à 4 min 29 s observés | Seul à qualifier l'installeur Windows complet sur une PR |
| [`windows-in-place-upgrade.yml`](../../.github/workflows/windows-in-place-upgrade.yml) | `pull_request`/`push` main+develop, filtré (`packaging/windows/**`, `scripts/install/**`, `scripts/release/build-windows-*.ps1`, `check-workflow-pins.py`, `pom.xml`, le workflow) | `upgrade` (windows-2022) | suite de fault-injection PS1 ; build+installation Inno réelle en place ; vérification transactionnelle | 2 min 47 s observés | Seul à qualifier la mise à niveau transactionnelle en place |

### Workflows présents mais **non déclenchés** sur une PR vers `develop`

| Workflow | Déclencheur réel | Remarque |
|---|---|---|
| [`m0-java-ci.yml`](../../.github/workflows/m0-java-ci.yml) | `workflow_dispatch` seul (:6) | Malgré son nom, ne tourne jamais automatiquement ; `issues: write` + poste dans l'issue #3 (S11/T-CI de l'audit) — permission à garder minimale si on le réactive |
| [`docker-upgrade-qualification.yml`](../../.github/workflows/docker-upgrade-qualification.yml) | `workflow_dispatch` seul | Le commentaire du fichier (:3-6) explique lui-même pourquoi : la qualification A→B automatique vit dans `release-promotion-gate.yml`, pas ici, pour ne jamais tourner deux fois |
| [`historical-qualification.yml`](../../.github/workflows/historical-qualification.yml) | `workflow_dispatch` seul (choix m28/m15) | Rejoue `scripts/remediation/run-final.{sh,ps1}` et `scripts/m15/run-final.ps1` à la demande — c'est la seule voie encore vivante vers ces deux scripts (§ G3) |
| [`intellij-plugin-release.yml`](../../.github/workflows/intellij-plugin-release.yml) | `release: published` | Hors du chemin PR |
| [`release-promotion-gate.yml`](../../.github/workflows/release-promotion-gate.yml) | `pull_request` **vers `main` seulement** + `workflow_dispatch` | Ne concerne jamais une PR vers `develop` ; à garder dans un ruleset scopé `main`, jamais dans le ruleset partagé (le fichier le dit lui-même, :5-6) |
| [`release-windows.yml`](../../.github/workflows/release-windows.yml) | `workflow_dispatch` seul | Publication, hors du chemin PR |

## 3. Doublons de gates comptés sur une PR qui touche `minos-application/**`

| Gate | Exécutions | Détail |
|---|---|---|
| `scripts/quality/check-workflow-pins.py` | **8** | `pr-ci` ×2 OS (:91-92), `mnd-remediation` ×2 OS (:54-55), `mne-remediation` ×2 OS (:54-55), `post-mne-remediation` ×2 OS (:61-62) |
| `scripts/docs/product-facts.py --check` | **6** | `pr-ci` ×2 OS (:164-165), `post-mne-remediation` ×2 OS (:86-87), `m19` ×1 (:65), `m20` ×1 (:68) — seulement 4 si M19/M20 ne se déclenchent pas |
| `scripts/quality/check-jacoco.py` | **4** (2 invocations distinctes) | `pr-ci` ubuntu (plein, :140-142), `pr-ci` windows (`--skip-scope m30-postgresql-pgvector`, :160-162), `m19` (:78), `m20` (:81) |
| Checkout + assertion exact-head + `actions/checkout` | **11 fois** sur les job-runs qui se déclenchent systématiquement (pr-ci ×2, mnd ×2, mne ×2, post-mne ×2, post-228 ×1, docker-release ×1, intellij ×2 si déclenché) | Chaque checkout est `fetch-depth: 0` (historique complet) |
| `mvnw clean verify` (reactor complet) | **jusqu'à 3** (Linux) | `pr-ci` verify ubuntu, `m19` java, `m20` java — confirme le chiffre de l'audit (§10) ; **+1 sous Windows** (`pr-ci` verify windows), non compté par l'audit qui ne parle que de Linux |
| Installation + délégation cgroup sandbox Linux (`scripts/ci/install-linux-sandbox-toolchain.sh` + `delegate-linux-cgroup.sh`) | **jusqu'à 3** | `pr-ci` (Unix uniquement), `m19`, `m20` — chacun réinstalle son propre toolchain sandbox |

## 4. Avant / après — les deux PR de mesure

**Réalisé** (numéros vérifiés sur le pipeline commité, pas une estimation) : `mnd-remediation.yml`, `mne-remediation.yml`, `post-mne-remediation.yml`, `post-228-hardening.yml` sont supprimés, leur contenu fusionné dans un job unique `invariants` (ubuntu-24.04, sans Maven) à l'intérieur de `pr-ci.yml`. M19 et M20 restent des workflows séparés, déjà filtrés par chemin avant ce chantier — les fusionner dans `pr-ci.yml` n'était pas nécessaire pour supprimer le chevauchement (ce n'étaient pas eux, le problème : ils ne se sont pas déclenchés une seule fois sur les 10 dernières PR réelles, cf. § 1). `docker-release-validation.yml` garde son propre fichier (heavy, 90 min de budget) mais gagne un `paths-ignore`.

### PR type A — touche uniquement `minos-application/**` (un fichier `.java`, aucun `pom.xml`)

| | Avant | Après |
|---|---|---|
| Workflows déclenchés | 7 (`pr-ci`, `mnd-remediation`, `mne-remediation`, `post-mne-remediation`, `post-228-hardening`, `docker-release-validation`, `m19`, `m20`) | 4 (`pr-ci`, `m19`, `m20`, `docker-release-validation` — ce dernier se déclenche **toujours** ici : voir § 6.1, son `paths-ignore` n'exclut que docs/markdown/historique, jamais un changement de code) |
| Job-runs | 13 (vuln-scan 1, verify ×2, mnd ×2, mne ×2, post-mne ×2, post-228 ×1, docker-release ×1, m19 ×1[+1 mort], m20 ×1[+1 mort]) | **7** (vuln-scan 1, invariants 1, verify ×2, m19 ×1, m20 ×1, docker-release ×1) |
| `clean verify` complets (Linux) | 3 (pr-ci, m19, m20) | 3, inchangé — m19/m20 gardent leur propre build ciblé sur leurs modules ; aucune fusion n'était sûre sans dupliquer le déclencheur `pull_request` du reactor complet |
| `check-workflow-pins.py` | 8 (pr-ci ×2, mnd ×2, mne ×2, post-mne ×2) | **1** (job `invariants`, une seule fois) |
| Checkouts `fetch-depth:0` dans `pr-ci.yml` lui-même | 6 (verify ×2 + 4 workflows fusionnés ×1) | 3 (invariants ×1, verify ×2) |

### PR type B — touche uniquement `docs/**` (un fichier Markdown, aucun chemin listé par M19/M20/intellij/windows-*)

| | Avant | Après |
|---|---|---|
| Workflows déclenchés | 6 (`pr-ci`, `mnd-remediation`, `mne-remediation`, `post-mne-remediation`, `post-228-hardening`, `docker-release-validation`) | 1 (`pr-ci` seul — `docker-release-validation.yml` est désormais exclu par `paths-ignore: docs/**, **/*.md`) |
| Job-runs | 10 | **4** (vuln-scan, invariants, verify ×2) |
| `clean verify` complets | 1 (pr-ci uniquement) | 1, inchangé |
| Docker (90 min alloués, ~3 min observés) | déclenché pour un changement de documentation | ne se déclenche plus |
| `check-workflow-pins.py` | 8 | 1 |

**Critère de réussite de l'audit (§ C1, étape 8) tenu** : la PR docs passe de 10 à 4 job-runs (−60 %) et ne réserve plus 90 min de budget Docker ; la PR code passe de 13 à 7 job-runs (−46 %), `check-workflow-pins.py` de 8 à 1 exécution dans les deux cas.

## 5. Jobs morts

| Job | Condition | Pourquoi il ne peut plus être vrai |
|---|---|---|
| `m19-advanced-code-intelligence.yml:80-121` (`m19-final-windows`) | `github.event_name == 'pull_request' && github.head_ref == 'agent/m19-advanced-code-intelligence'` | Aucune branche `agent/m19-advanced-code-intelligence` n'est créée par le flux de travail actuel (branches `hautes/*`, `sprint*/*`, `residus/*`, `dependabot/*` observées sur les 10 dernières PR) ; la convention de nommage par agent de jalon a été abandonnée. Confirmé par `gh run view` : le job se termine à la même seconde qu'il démarre (0 s), signe d'un `if:` toujours faux. |
| `m20-semantic-hybrid-intelligence.yml:83-124` (`m20-final-windows`) | `github.head_ref == 'agent/m20-semantic-hybrid-intelligence'` | Identique |

**Piège des contrôles requis** — traité en § 6.

## 6. Contrôles requis et filtres de chemin — piège à traiter

### 6.1 `docker-release-validation.yml` — écart assumé par rapport à la suggestion littérale de l'audit

L'audit suggère de filtrer ce workflow sur `docker/**`, `scripts/release/**` et le POM du reactor. **Vérification contre le script réel** (`scripts/ci/qualify-docker-release.sh:28`) : il lance `./mvnw -B -ntp -DskipTests -DskipITs package`, c'est-à-dire un **build du reactor complet**, avant de construire l'image. Un changement dans n'importe quel module Java change potentiellement le JAR shadow embarqué dans l'image. Filtrer sur `docker/**`/`scripts/release/**` seul aurait fait manquer une régression introduite dans, par exemple, `minos-mcp` ou `minos-application`, qui n'est visible que dans l'image Docker qualifiée — exactement la perte d'invariant que ce chantier doit éviter.

**Décision retenue** : `paths-ignore` plutôt que `paths`, pour exclure uniquement ce qui ne peut structurellement pas affecter le JAR ou l'image (`docs/**`, `**/*.md`, `scripts/history/**`), sans jamais réduire la couverture sur un changement de code. Cela répond au critère concret de l'audit (« un build de 90 minutes ne doit pas partir sur une modification de documentation ») sans introduire l'angle mort du filtre suggéré.

### 6.2 Comportement de chaque contrôle requis quand son filtre ne matche pas

| Contrôle requis (nom de job GitHub) | Comportement si le filtre de chemin ne matche pas |
|---|---|
| `Dependency vulnerability gate / osv-scan`, `Verify (ubuntu-24.04)`, `Verify (windows-2022)` (pr-ci) | Aucun filtre de chemin : se déclenche toujours. Sans objet. |
| `Docker Release Validation / provider-complete-image` | `paths-ignore` (§ 6.1) : absent de la liste de contrôles d'une PR qui ne touche que `docs/**`/`**/*.md`/`scripts/history/**`. GitHub Actions traite un job absent (jamais déclenché faute de correspondance) comme **non requis pour cette PR** s'il est déclaré requis via une règle de branche qui accepte l'absence de déclenchement (« Require status checks to pass », coché sans « strict » supplémentaire) — mais **passe indéfiniment en attente** si la règle est plus stricte. À vérifier côté réglages du dépôt (hors périmètre de cette session) avant d'activer ce filtre en required-check. |
| `IntelliJ 2026.1 / Java 21`, `Windows process ownership / Java 21` | Filtré sur `minos-intellij/**`, `minos-cli/**`, `minos-integration-git/**`, docs ciblées. Même comportement que ci-dessus sur une PR hors de ces chemins. |
| `M19 Java 24 qualification`, `M20 Java 24 qualification` | Filtré sur leurs modules respectifs. Idem. |
| `Build and smoke Windows candidate` (windows-installer), `Transactional upgrade engine ...` (windows-in-place-upgrade) | Filtré sur `packaging/windows/**`, `scripts/install/**`, `scripts/release/**`, `pom.xml`. Idem. |

### 6.3 Uniformisation appliquée

`ubuntu-latest`/`windows-latest` remplacés par `ubuntu-24.04`/`windows-2022` dans `intellij-plugin.yml`, `m19-advanced-code-intelligence.yml`, `m20-semantic-hybrid-intelligence.yml`, `docker-release-validation.yml` (les autres workflows de PR étaient déjà épinglés). `intellij-plugin-release.yml` (déclencheur `release: published`, hors chemin PR) n'est pas touché.

### 6.4 Concurrence

Les 12 groupes de concurrence des workflows de PR annulaient un run en cours dès qu'un nouvel événement arrivait sur la même clé — y compris un **push** direct sur `develop`/`main`, où la clé retombe sur `github.ref` (donc partagée par tous les push sur cette branche). Deux merges rapprochés sur `develop` pouvaient annuler la validation du premier avant qu'elle ne se termine. Corrigé partout : `cancel-in-progress: ${{ github.event_name == 'pull_request' }}`.

### 6.5 Liste exacte des contrôles requis à mettre à jour dans la protection de branche

**À retirer** (workflows supprimés, ces noms de contrôle n'existeront plus jamais) :
- `MND exact-head invariants (ubuntu-latest)`, `MND exact-head invariants (windows-latest)`
- `MNE exact-head invariants (ubuntu-latest)`, `MNE exact-head invariants (windows-latest)`
- `Post-MNE exact-head invariants (ubuntu-latest)`, `Post-MNE exact-head invariants (windows-latest)`
- `Verify post-228 static hardening`

**À ajouter** (nouveau job dans `pr-ci.yml`) :
- `Static invariants (single run)`

**Inchangés** (toujours produits par `pr-ci.yml`, aucune action requise) :
- `Dependency vulnerability gate / osv-scan`
- `Verify (ubuntu-24.04)`, `Verify (windows-2022)`

Aucun autre workflow de la § 2 n'a changé de nom de job.

## 7. G3 — inventaire des scripts et workflows de jalon

Politique : [ADR 0043](../adr/0043-retrait-des-artefacts-de-jalon.md). Inventaire exhaustif des 101 fichiers sous `scripts/m0`…`scripts/m29`, `scripts/remediation`, plus `scripts/intellij/check-m21-parity.py`, établi par un agent de recherche dédié puis revérifié par des lectures directes (`check-post-mne.py`, `check-polyglot.py`, `check-provider.py`, `check-m28.py`, `run-runtime-e2e.py`, `run-hosted-e2e.py`) avant classification. Trois états, définis par l'ADR 0043 : **permanent**, **gelé par assertion**, **archivé**.

### 7.1 Mécanisme clé : exécution indirecte

`scripts/remediation/check-post-mne.py:14-24` définit `ACTIVE_MILESTONE_GATES` et l'exécute par sous-processus (`run_active_milestone_gates`, :63-75, appelée depuis `main()`). Ce script tourne sur **chaque PR** via `post-mne-remediation.yml:65`. Les neuf gates de la liste sont donc vivants alors qu'aucun fichier YAML ne les nomme :

```
scripts/m21/check-s7-provider.py
scripts/m22/check-provider.py
scripts/m23/check-semantic.py
scripts/m24/check-polyglot.py
scripts/m25/check-remote-distributed.py
scripts/m26/check-runtime-dynamic.py
scripts/m27/check-hosted.py
scripts/m28/check-m28.py
scripts/m28/check-current-docs.py   # importe et exécute aussi scripts/docs/check-current-docs.py (:15-21)
```

### 7.2 Catégorie « permanent » — à relocaliser hors numéro de jalon

| Script | Invariant appliqué | Chemin cible proposé |
|---|---|---|
| `scripts/m21/check-s7-provider.py` | Cohérence du provider Java AST avancé (capabilities, program graph) | `scripts/quality/check-advanced-provider-consistency.py` |
| `scripts/m22/check-provider.py` | Cohérence du provider Java AST de référence (ADR 0030) | `scripts/quality/check-java-ast-provider-consistency.py` |
| `scripts/m23/check-semantic.py` | Cohérence de la récupération sémantique (ADR 0031) | `scripts/quality/check-semantic-retrieval-consistency.py` |
| `scripts/m24/check-polyglot.py` | Cohérence des providers SCIP polyglottes sous preuve (ADR 0032) | `scripts/quality/check-polyglot-provider-consistency.py` |
| `scripts/m25/check-remote-distributed.py` | Révisions distantes épinglées, artefacts worker vérifiés (ADR 0033) | `scripts/quality/check-remote-distributed-consistency.py` |
| `scripts/m26/check-runtime-dynamic.py` | Observations runtime partielles corrélées à un snapshot exact (ADR 0034) | `scripts/quality/check-runtime-dynamic-consistency.py` |
| `scripts/m27/check-hosted.py` | Plan de contrôle tenant opt-in chiffré/audité (ADR 0035) | `scripts/quality/check-hosted-control-plane-consistency.py` |
| `scripts/m28/check-m28.py` | Convergence, surface verticale, frontières et décomposition M28 | `scripts/quality/check-vertical-decomposition-consistency.py` |
| `scripts/m28/check-current-docs.py` | Sur-couche M28 de `scripts/docs/check-current-docs.py` (fraîcheur doc étendue) | `scripts/quality/check-current-docs-vertical-extension.py` |

**Fait.** Les 9 fichiers ont été déplacés (`git mv`), `ACTIVE_MILESTONE_GATES` mis à jour dans `check-post-mne.py`, les références documentaires (`docs/developer/polyglot-providers.md`, `docs/roadmap/M24_EXECUTION.md`, `docs/developer/remote-distributed-indexing.md`, `docs/developer/team-hosted-mode.md`) mises à jour.

**Constat trouvé pendant la vérification, corrigé avant tout commit** : quatre des neuf gates (`check-semantic-retrieval-consistency.py`, `check-remote-distributed-consistency.py`, `check-runtime-dynamic-consistency.py`, `check-hosted-control-plane-consistency.py`) contiennent chacun un `require(..., "check-<ancien-nom-court>.py")` qui vérifie que le `run-final.ps1`/`.sh` frère cite littéralement l'ancien nom de fichier court du gate. Le déplacement (mise à jour des chemins complets dans les `run-final.*`) a mécaniquement fait disparaître cette sous-chaîne, puisque le nouveau nom de fichier ne contient plus l'ancien. Rejouer les 9 gates après le déplacement a immédiatement fait échouer ces quatre-là (`missing required contract text`/`missing semantic facts`/`missing required fact`) — exactement le type de régression silencieuse que ce chantier devait éviter. Corrigé en mettant à jour la chaîne attendue dans chacun des 4 gates vers le nouveau nom court. Les deux scripts de rejeu manuel `scripts/remediation/run-final.ps1`/`.sh` (vivants via `historical-qualification.yml`, option `m28`) référençaient aussi les anciens chemins de `check-m28.py`/`check-current-docs.py` : mis à jour pour que ce chemin de rejeu manuel reste fonctionnel. Les 9 gates + `check-post-mne.py` ont ensuite été rejoués : tous SUCCESS, chiffres inchangés (14 modules/498 sources).

### 7.3 Catégorie « gelé par assertion » — laissé en place, non résolu par ce chantier

Ces fichiers ne sont jamais exécutés par la CI, mais un gate vivant (§ 7.1/7.2) lit leur texte et exige un contenu précis (`read(path)`, pas `subprocess`). Les déplacer ou les supprimer sans réécrire le gate ferait échouer une CI qui passait aujourd'hui — hors périmètre de ce chantier (règle 4 : aucun changement de comportement de gate). Laissés en place, marqués ici pour ne pas redevenir invisibles :

| Fichier | Gelé par |
|---|---|
| `scripts/m22/run-final.ps1` | `scripts/m22/check-provider.py:50` |
| `scripts/m23/run-final.ps1`, `scripts/m23/evaluate-learned-quality.py` | `scripts/m23/check-semantic.py:163-176,234-268` |
| `scripts/m24/run-final.ps1`, `run-final.sh`, `run-provider-e2e.py`, `bootstrap-windows-toolchains.ps1`, `check-windows-prerequisites.ps1` | `scripts/m24/check-polyglot.py:108-112,329-379` |
| `scripts/m25/run-final.ps1`, `run-final.sh`, `run-remote-e2e.py` | `scripts/m25/check-remote-distributed.py:68-70,144-156` |
| `scripts/m26/run-final.ps1`, `run-final.sh`, `run-runtime-e2e.py`, `M26RuntimeFixture.java` (compilé par `run-runtime-e2e.py:19,110`, aucun module Maven) | `scripts/m26/check-runtime-dynamic.py:57-59,144-152` |
| `scripts/m27/run-final.ps1`, `run-final.sh`, `run-hosted-e2e.py`, `M27HostedFixture.java` (compilé par `run-hosted-e2e.py:20,94`, aucun module Maven) | `scripts/m27/check-hosted.py:160-162,163-169` |

**Sous-catégorie « vivant par rejeu manuel » (workflow_dispatch), à ne pas confondre avec « gelé »** — ceux-ci sont réellement exécutés, à la demande, via `historical-qualification.yml` :

| Fichier | Point d'entrée |
|---|---|
| `scripts/remediation/run-final.ps1` / `run-final.sh` | `historical-qualification.yml:104` (`m28-windows`) / `:64` (`m28-linux`), `workflow_dispatch` |
| `scripts/m28/run-program-graph-performance.ps1` / `.sh` | appelés par `run-final.ps1`/`.sh` ci-dessus (également lus statiquement par `check-m28.py:72-73`) |
| `scripts/m15/run-final.ps1`, `capture-baseline.ps1`, `capture-final-query.ps1`, `M15FinalQueryProbe.java` | `historical-qualification.yml:144` (`m15-windows`), `workflow_dispatch` |
| `scripts/m14/validate-local.ps1` | appelé par `scripts/m15/capture-baseline.ps1:222-244` (chaîne ci-dessus) |

`scripts/m29/run-s3.ps1` à `run-s7.ps1` sont dans un état voisin : jamais exécutés en CI, mais leur **contenu exact** est asserté par un test JUnit vivant (`M29S3RunnerPowerShellHostContractTest`, `M29DockerAdministrationContractTest`, `M29McpClientBackendAgnosticContractTest`, `M29InstallerBackendLifecycleContractTest`, exécutés par `mvnw clean verify` sur chaque PR) — même traitement que « gelé par assertion », laissés en place. `scripts/m29/run-s8.ps1` n'a aucun appelant : voir § 7.4.

### 7.4 Catégorie « archivé » — vers `scripts/history/`

Aucune référence vivante (ni exécution directe, ni sous-processus, ni assertion statique dans un gate vivant) ; au plus une mention documentaire.

| Lot | Fichiers | Référence restante avant retrait |
|---|---|---|
| `scripts/m0/**` (12 fichiers) | `README.md`, `export-glean-compatible-scip.ps1`, `install-glean-wsl.ps1`, `install-scip-tools.ps1`, `install-scip-typescript.ps1`, `run-in-memory-backend-benchmark.ps1`, `run-minos-scip-baseline.ps1`, `run-scip-java.ps1`, `run-scip-java.sh`, `run-scip-typescript.ps1`, `validate-local-ci.ps1`, `scip-java-windows-patch/.../ScipWriter.java` | Uniquement le propre `README.md` de M0 et `docs/history/milestones/m0/*.md` (déjà de l'historique) |
| `scripts/m15/` phase S1–S6 (11 fichiers) | `run-s1.ps1`…`run-s6.ps1`, `run-s2-final.ps1`, `capture-query-baseline.ps1`, `M15RepeatedQueryProbe.java`, `relocate-main-sources.ps1`, `relocate-tests.ps1` | `run-s1.ps1`/`run-s3.ps1` cités par `docs/history/milestones/m15/M15_S1_BASELINE.md` et `docs/adr/0042-racine-de-composition.md:199` (référence historique, à laisser telle quelle — l'ADR cite un test déjà joué, pas un gate vivant) |
| `scripts/m16/**` (9 fichiers) | tout le répertoire — **aucun workflow ne déclenche `run-final.ps1`, confirmé** | `docs/adr/0025-measurement-gated-storage-backend-evolution.md` (référence historique) |
| `scripts/m17/run-final.ps1` | 1 fichier | `docs/roadmap/M17_EXECUTION.md:84`, `docs/adr/0026-...md:79` (historique) |
| `scripts/m18/run-final.ps1` | 1 fichier — **son chemin est dans le filtre `paths:` de `intellij-plugin.yml:12,22` sans qu'aucune étape ne l'exécute** | Retirer le fichier **et** la ligne `scripts/m18/**` du filtre dans le même commit, sinon le filtre continue de retenir un chemin mort |
| `scripts/m19/run-final.ps1`, `scripts/m20/run-final.ps1` | 2 fichiers, appelés uniquement par les jobs morts `m19-final-windows`/`m20-final-windows` (§ 5, C1) | À retirer **dans le même commit** que la suppression de ces deux jobs — leur seul appelant disparaît en même temps |
| `scripts/m21/` branche morte (9 fichiers) | `run-local.ps1`, `run-s5.ps1`…`run-s9.ps1`, `run-s8-benchmark.ps1`, `M21SemanticScaleProbe.java`, `check-s8-results.py` | `docs/developer/semantic-scale-qualification.md`, `docs/developer/advanced-program-provider.md` (historique) |
| `scripts/intellij/check-m21-parity.py` | 1 fichier, appelé uniquement par `scripts/m21/run-s6.ps1` (lui-même mort) | Aucune |
| `scripts/remediation/apply-post-audit-remediation.py`, `post-audit-provenance.env` | 2 fichiers, aucune référence trouvée nulle part | Aucune |
| `scripts/m29/run-s8.ps1` | 1 fichier, aucun appelant (contrairement à `run-s3`…`run-s7`, lus par un test JUnit vivant) | Aucune |

**Total archivé : 49 fichiers.** Chaque lot part dans son propre commit (`git mv` vers `scripts/history/<jalon>/`), sauf M19/M20 qui partent avec la suppression du job mort correspondant.

### 7.5 Garde-fou (à livrer)

`scripts/quality/check-milestone-artifact-references.py` : échoue si un fichier sous `scripts/` (hors `scripts/history/`) n'est référencé par aucun des trois moyens ci-dessus ni par une liste explicite d'archives assumées. Auto-test : un fichier fictif non référencé doit faire échouer le contrôle ; un fichier référencé par chacune des voies (workflow direct, sous-processus depuis un gate vivant, assertion statique depuis un gate vivant, documentation) doit le laisser passer. Exécuté une fois dans le pipeline consolidé (C1).

## 8. Journal des constats de `verif-ci` / `verif-gates`

Adaptation à l'outillage réel : deux agents persistants sondant toutes les dix minutes ne sont pas réalisables tels quels avec les outils de cette session (un sous-agent s'exécute une fois et rend la main, il ne tourne pas en tâche de fond indéfiniment). À la place : `verif-gates` a été tenu par l'orchestrateur lui-même, en rejouant l'intégralité des gates locaux après chaque commit à risque (voir horodatage des commits pour la cadence réelle) ; `verif-ci` a été délégué à un agent de revue adversariale dédié après le lot G3 complet (inventaire, archivage, relocalisation, garde-fou), avant d'entamer C1.

### Constats retenus

| # | Origine | Constat | Sévérité | Résolution |
|---|---|---|---|---|
| J1 | orchestrateur (rejeu local) | 4 des 9 gates de jalon relocalisés (§7.2) vérifient littéralement que leur `run-final.ps1`/`.sh` frère cite leur propre ancien nom de fichier court (`require(path, text, "check-semantic.py")`) ; le déplacement a fait disparaître cette sous-chaîne puisque le nouveau nom de fichier ne la contient plus. Détecté en rejouant les 9 gates immédiatement après le `git mv`, avant tout commit. | bloquant | corrigé avant commit (`8b9e5e2a`) : chaîne attendue mise à jour vers le nouveau nom court dans chacun des 4 gates |
| J2 | agent `verif-ci` (revue adversariale du lot G3) | `docs/developer/semantic-hybrid-intelligence.md:222` pointait encore vers `scripts/m20/run-final.ps1`, archivé par `caf8924d` ; `docs/history/milestones/README.md:55` affirmait encore que `scripts/m0/` existe | à corriger (référence documentaire, rien d'exécutable) | corrigé (`2e0000d8`) |
| J3 | orchestrateur (rejeu local pendant l'implémentation C1) | `scripts/remediation/check-audit-remediation-v2.py` exigeait l'existence de `.github/workflows/post-228-hardening.yml` avec un contenu précis (et l'absence de `mvnw`/`matrix:`/`windows-2022` dedans) ; la suppression de ce fichier lors de la fusion C1 a fait échouer le gate immédiatement au rejeu local, avant tout commit. | bloquant | corrigé avant commit : le gate extrait désormais le bloc YAML du job `invariants` dans `pr-ci.yml` (nouvelle fonction `read_job_block`) et vérifie la même propriété (léger, ubuntu seul, jamais fusionné avec le job Maven) à son nouvel emplacement ; vérifié que l'extraction ne capture pas le job `verify` voisin (qui contient légitimement `matrix:`/`mvnw`/`windows-2022`) |
| J4 | agent `verif-ci` (revue adversariale du lot G3) | Mutation témoin rejouée dans un worktree jetable : import illégal `minos-application → minos-storage-local` ajouté, `check-module-boundaries.py` le rejette toujours (`A2 source boundary violated`) ; script fictif non référencé ajouté sous `scripts/quality/`, `check-milestone-artifact-references.py` le rejette toujours. Les deux mutations nettoyées (`git worktree remove`). | remarque (résultat attendu, confirmé) | aucune action |
| J5 | agent `verif-ci` (revue finale, branche complète C1+G3) | `m19-advanced-code-intelligence.yml`/`m20-...yml` gardaient `scripts/m19/**`/`scripts/m20/**` dans leur filtre `paths:` alors que ces répertoires sont vides depuis l'archivage de leur `run-final.ps1` (`caf8924d`) — même défaut que celui déjà corrigé pour `scripts/m18/**` dans `intellij-plugin.yml`, mais pas reporté ici. `docs/STATUS.md` décrivait encore `post-228-hardening.yml` comme un workflow séparé, supprimé par `4ffbf8c4`. Une coquille `0025-mesurement-gated` (au lieu de `measurement`) dans ce document. | à corriger (chemins morts, doc d'autorité perimée), remarque (coquille) | corrigé avant commit : lignes de filtre retirées, § « PR Validation »/« Post-228 Hardening Invariants » de STATUS.md réécrit pour décrire le job `invariants`, coquille corrigée |

**Verdict de la revue finale (branche complète C1+G3)** : aucun constat bloquant. `read_job_block` mutation-testé dans les deux sens (suppression d'un marqueur requis → échec correct ; injection de `mvnw`/`matrix:`/`windows-2022` dans le bloc `invariants` → échec correct, sans fuite vers le job `verify` voisin qui les contient légitimement). La suppression de la ré-exécution ciblée de `ProviderSandboxSecurityRegressionTest` a été revérifiée empiriquement par un agent indépendant (`mvnw -pl minos-runtime-local -am test` sans filtre : 2 tests exécutés, 0 échec). Branche jugée sûre pour l'ouverture d'une PR en brouillon et une unique exécution de validation.
