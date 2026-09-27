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

### PR type A — touche uniquement `minos-application/**` (un fichier `.java`, aucun `pom.xml`)

| | Avant | Après (cible C1) |
|---|---|---|
| Workflows déclenchés | 7 (`pr-ci`, `mnd-remediation`, `mne-remediation`, `post-mne-remediation`, `post-228-hardening`, `docker-release-validation`, `m19`, `m20` si le fichier est dans un des 4 modules filtrés) | 1 pipeline (`pr-ci`) avec jobs conditionnés |
| Job-runs | 13 (vuln-scan 1, verify ×2, mnd ×2, mne ×2, post-mne ×2, post-228 ×1, docker-release ×1, m19 ×1[+1 mort], m20 ×1[+1 mort]) | À chiffrer après fusion (§ 6) ; cible : vuln-scan + verify ×2 OS + 1 job « invariants légers » ×1 OS + jacoco M19/M20 fusionné dans verify = **≈ 4-5 job-runs** |
| `clean verify` complets (Linux) | 3 (pr-ci, m19, m20) | 1 (pr-ci), les gates M19/M20 deviennent des étapes conditionnelles dans ce même job |
| `check-workflow-pins.py` | 8 | 1 (ou 2 si matrice OS conservée) |
| Checkouts `fetch-depth:0` | 8+ | 2 (un par OS) |
| Docker (90 min alloués, ~3 min observés) | déclenché même si aucun chemin Docker/release touché | ne se déclenche plus (filtre `docker/**`, `scripts/release/**`, `pom.xml` racine) |

### PR type B — touche uniquement `docs/**` (un fichier Markdown, aucun chemin listé par M19/M20/intellij/windows-*)

| | Avant | Après (cible C1) |
|---|---|---|
| Workflows déclenchés | 6 (`pr-ci`, `mnd-remediation`, `mne-remediation`, `post-mne-remediation`, `post-228-hardening`, `docker-release-validation` — les 4 derniers **sans aucun rapport avec le fichier modifié**) | 1 pipeline (`pr-ci`), toujours avec `verify` complet (le doc peut affecter `product-facts`/`check-current-docs`) |
| Job-runs | 10 | **≈ 3** (vuln-scan + verify ×2 OS) |
| `clean verify` complets | 1 (pr-ci uniquement — m19/m20 ne se déclenchent déjà pas sur `docs/**` générique) | 1, inchangé |
| Docker 90 min alloués | déclenché pour un changement de documentation | ne se déclenche plus |
| `check-workflow-pins.py` | 8 | 1-2 |

*Chiffrage exact des job-runs « après » à figer une fois la fusion des jobs commitée (§ 6) et revérifié par `verif-gates` avant la demande d'autorisation de run de validation.*

## 5. Jobs morts

| Job | Condition | Pourquoi il ne peut plus être vrai |
|---|---|---|
| `m19-advanced-code-intelligence.yml:80-121` (`m19-final-windows`) | `github.event_name == 'pull_request' && github.head_ref == 'agent/m19-advanced-code-intelligence'` | Aucune branche `agent/m19-advanced-code-intelligence` n'est créée par le flux de travail actuel (branches `hautes/*`, `sprint*/*`, `residus/*`, `dependabot/*` observées sur les 10 dernières PR) ; la convention de nommage par agent de jalon a été abandonnée. Confirmé par `gh run view` : le job se termine à la même seconde qu'il démarre (0 s), signe d'un `if:` toujours faux. |
| `m20-semantic-hybrid-intelligence.yml:83-124` (`m20-final-windows`) | `github.head_ref == 'agent/m20-semantic-hybrid-intelligence'` | Identique |

**Piège des contrôles requis** — traité en § 6.

## 6. Contrôles requis et filtres de chemin — piège à traiter

*(section à compléter après implémentation — liste exacte des noms de contrôles requis à remettre à l'utilisateur avant toute demande de run de validation)*

## 7. G3 — inventaire des scripts et workflows de jalon

*(en cours — voir agent de recherche `a9f7e8b48c16e7bd3`, résultats à intégrer)*

## 8. Journal des constats de `verif-ci` / `verif-gates`

*(vide pour l'instant — premier commit de code pas encore posé)*
