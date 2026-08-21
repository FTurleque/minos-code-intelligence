# État courant — MINOS

Dernière mise à jour : **21 août 2026**.

Ce fichier est la synthèse autoritative de l'état produit courant. L'historique détaillé antérieur à la campagne post-#226 est conservé sans modification dans [`history/reconciliations/STATUS-pre-post226-audit-20260821.md`](history/reconciliations/STATUS-pre-post226-audit-20260821.md). Les preuves de jalons restent sous [`roadmap/`](roadmap/), [`history/milestones/`](history/milestones/), [`adr/`](adr/README.md) et [`architecture/`](architecture/README.md).

> **Convention.** Une capacité n'est considérée intégrée qu'après merge. Une remédiation en PR reste explicitement marquée « en qualification » jusqu'à son merge, même si son code et ses tests sont déjà présents sur la branche de travail.

## Réconciliation post-PR #226 — audit complet du 21 août 2026

`develop` intègre **PR #226** (`fix: harden IntelliJ launcher trust and provider traversal`). Le nouvel audit complet a été exécuté sur `develop@9ee7043fbf00cbf05692df72813b233fb5848d5f`. Il n'a confirmé **aucun P0**, mais a identifié un P1, un P2 et trois écarts P3. Leur remédiation est portée par **PR #227**, actuellement **en qualification et non intégrée**.

### P1 — frontière réseau des providers

La copie éphémère `LocalProviderWorkspace` protège bien le working tree enregistré, mais l'ancienne politique accordait implicitement `WorkerNetworkPolicy.ALLOW` à `scip-java`, `scip-dotnet`, `scip-go` et `rust-analyzer-scip`. Or leurs descendants peuvent exécuter des éléments contrôlés par le dépôt (Maven wrapper/plugins, MSBuild targets, Cargo build scripts ou mécanismes équivalents). Un dépôt non fiable pouvait donc lire sa copie complète et disposer simultanément d'un egress réseau.

**PR #227** supprime tout passe-droit implicite. `StrongOwnedProcessExecutors` délègue uniquement à `IndexerProcessPlanFactory.networkPolicy()`, dont le défaut est `DENY`. `ALLOW` devient un opt-in explicite réservé à un chemin dont la confiance est prouvée ou séparée dans une phase dédiée. Les factories SCIP gérées courantes restent `DENY`.

### P2 — provenance générale des exécutables

`CommandLocator` n'accepte plus les entrées `PATH` vides ou relatives et ne les transforme plus en chemins dépendants du CWD. Chaque répertoire admissible doit être absolu puis canonisé par `toRealPath()`, et le résultat retourné est lui-même un fichier réel absolu. Sous Windows, l'invocation batch refuse également le fallback vers un `cmd.exe` nu : le command processor doit être résolu vers un fichier `cmd.exe` absolu existant.

### P3 — stockage privé et reparse points Windows

`PrivateLocalStorage` rejette maintenant explicitement `BasicFileAttributes.isOther()` avant vérification ou mutation d'ACL. Les junctions/reparse points Windows sont rapportés `EXPOSED` par le diagnostic et refusés par les points d'entrée d'enforcement. Une régression Windows réelle crée une junction via `mklink /J`.

### P3 — confinement de lecture capability-honest

`ConfinedFileOpener` conserve la garantie handle-relative forte quand `SecureDirectoryStream` est disponible. Le fallback Windows/non-secure conserve `NOFOLLOW_LINKS`, la revalidation de la chaîne et rejette désormais aussi `isOther()`, mais ne revendique plus la même preuve d'identité d'ancêtres qu'une descente `openat` par handles. Les callers peuvent tester `supportsDirectoryHandleTraversal(...)` lorsqu'ils exigent cette garantie plus forte. Une régression Windows `mklink /J` vérifie le refus d'une junction d'ancêtre.

### P3 — gates et documentation

Le gate `scripts/remediation/check-p0-p2.py` exige désormais les invariants post-#226 : absence d'`ALLOW` implicite dans le wrapper provider, PATH absolu/canonisé, command processor Windows absolu, rejet `isOther()` dans stockage/lecture et wording capability-honest. STATUS, ROADMAP et registre des risques sont réconciliés dans #227 ; leurs versions précédentes sont archivées intégralement sous `docs/history/reconciliations/`.

## État produit

- **C0 → M30** : terminés et intégrés.
- **M29 issue #107** : **CLOSED** ; **M29 PR #108** intégrée.
- **M30 PR #110** intégrée ; **M30 promotion PR #111** intégrée.
- **hardening PR #113** intégré ; **M28 Windows CI PR #117** intégré.
- **#98** : sandbox OS réel Linux/Windows implémenté et qualifié dans la campagne de convergence.
- **PR #224** : traversées projet/NEXUS et couverture ciblée intégrées.
- **PR #225** : confinement workspace provider/discovery/ignore rules intégré.
- **PR #226** : provenance launcher IntelliJ et derniers walkers provider intégrés.
- **PR #227** : provider egress, provenance `CommandLocator`, reparse private storage et contrat de fallback confinement — **en qualification, non intégrée**.

## Release 1.0.1

La release **v1.0.1 est PUBLIÉE et immuable**.

- commit publié : `f762025d66e33c40324c811079f1527d122f90f9` ;
- URL : https://github.com/FTurleque/minos-code-intelligence/releases/tag/v1.0.1 ;
- publication : **10 assets**, soit **5 paires** artefact/checksum ;
- workflow de publication : `31288322126` ;
- setup Windows, distribution et plugin IntelliJ restent soumis aux gates de provenance, OSV et Plugin Verifier applicables.

## Garanties structurantes courantes

- snapshots structurés autoritatifs et promotions fail-closed ;
- API/CLI/MCP/NEXUS/IntelliJ au-dessus du métier sans réintroduire une autorité concurrente ;
- providers locaux exécutés depuis une copie éphémère bornée dans une sandbox OS qualifiée ;
- egress provider `DENY` par défaut et jamais inféré du seul besoin de résolution de dépendances ;
- environnement provider allowlisté ;
- Git distant borné et épinglé à l'endpoint attendu ;
- PostgreSQL hors loopback avec TLS `verify-full`, configuration JDBC allowlistée et transactions encadrées ;
- hosted control plane avec authentification, membership/RBAC et chaîne d'audit HMAC ;
- local storage owner-only, symlink/junction/reparse refusés avant mutation ;
- supply-chain CI et release épinglée à des références immuables lorsqu'une telle garantie est revendiquée.

## Qualification de PR #227

La remédiation ne doit être déclarée intégrée qu'après succès exact-head des workflows applicables sur son HEAD final, notamment PR Validation Linux/Windows et les validations spécialisées déclenchées par les chemins modifiés. Aucun merge n'est autorisé par ce document ; la décision d'intégration reste explicite après qualification.
