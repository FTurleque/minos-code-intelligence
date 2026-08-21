# État courant — MINOS

Dernière mise à jour : **21 août 2026**.

Ce fichier est la synthèse autoritative de l'état produit courant. L'historique détaillé antérieur à la campagne post-#226 est conservé sans modification dans [`history/reconciliations/STATUS-pre-post226-audit-20260821.md`](history/reconciliations/STATUS-pre-post226-audit-20260821.md). Les preuves de jalons restent sous [`roadmap/`](roadmap/), [`history/milestones/`](history/milestones/), [`adr/`](adr/README.md) et [`architecture/`](architecture/README.md).

> **Convention.** Une capacité n'est considérée intégrée qu'après merge. Une remédiation en PR reste explicitement marquée « en qualification » jusqu'à son merge, même si son code et ses tests sont déjà présents sur la branche de travail.

## Réconciliation post-PR #227 — audit complet du 21 août 2026

**PR #227 est intégrée** dans `develop` par le merge signé `32c376ed36595ff60daa7cda9367cba787547069`. Le nouvel audit complet de ce HEAD n'a confirmé aucun P0 ; il a identifié un blocage P1 de composition sandbox/provider, un P2 de provenance des exécutables qui constituent l'autorité de sandbox, et deux écarts P3 de tests/gates et de documentation.

Une remédiation distincte est actuellement en qualification sur une branche dédiée. Elle ne relâche ni l'egress `DENY`, ni le contrat hostile/untrusted des workers distants, ni l'exigence de quota filesystem `OS_ENFORCED` lorsqu'une exécution est présentée comme sûre pour du code hostile.

### P1 — composition sandbox/provider local

Le modèle `WorkerResourceContainment` est volontairement strict : un quota filesystem seulement supervisé ne peut pas qualifier l'exécution de code hostile, car un writer peut dépasser le seuil entre deux échantillons. Les backends Linux bubblewrap/cgroup v2 et Windows AppContainer/Job Object déclarent donc correctement `SUPERVISED_HARD_KILL` sur les dimensions filesystem et restent `UNTRUSTED_CODE_UNSUPPORTED` tant qu'aucun quota disque kernel/hard n'existe.

Le défaut était la réutilisation de ce contrat hostile dans le chemin provider local géré : `WorkerSandboxBackends.strongestAvailable()` éliminait les deux backends, `StrongOwnedProcessExecutors.qualifyOwnership()` transformait ensuite les runtimes installés en `BLOCKED`, et l'indexation autonome refusait de construire les executors.

La remédiation sépare deux qualifications machine-readable :

- **hostile/untrusted** : contrat historique inchangé, filesystem bytes+entries obligatoirement `OS_ENFORCED`, utilisé par les workers distants ;
- **managed local provider** : réseau OS-enforced, job boundary agrégé OS, descendants OS-owned, timeout actif, quotas filesystem bytes+entries appliqués pendant l'exécution et reclamation scratch active. Les quotas filesystem peuvent être `SUPERVISED_HARD_KILL`, sans jamais devenir une claim hostile.

`StrongOwnedProcessExecutors` et `StrongProcessOwnershipIndexerExecutor` utilisent uniquement ce second sélecteur pour l'indexation locale. `LocalIsolatedIndexWorker` conserve le sélecteur hostile strict et reste fail-closed tant qu'un hard filesystem quota n'est pas disponible.

### P2 — provenance des exécutables d'autorité

Le hardening #227 des PATH relatifs reste en place, mais un répertoire PATH absolu ne suffit pas à établir la provenance d'un binaire qui crée la frontière de sécurité.

Sous Linux, `bwrap`, `prlimit` et le `sh` utilisé pour entrer dans le cgroup sont désormais résolus sans PATH depuis les répertoires système fixes `/usr/bin`, `/bin`, `/usr/sbin`, `/sbin`. Le répertoire réel et l'exécutable réel doivent être UID 0 et non modifiables par group/others ; un symlink qui sort du répertoire système réel est refusé.

Sous Windows, le PowerShell utilisé par AppContainer/Job Object est résolu uniquement sous `SystemRoot\System32\WindowsPowerShell\v1.0`. Le plugin IntelliJ applique la même règle : `cmd.exe` est ancré à `SystemRoot\System32`, `ComSpec` n'est plus une source de confiance, `/v:off` désactive delayed expansion, et `systemctl`/`systemd-run` utilisent sur Linux des chemins système canoniques root-owned pour le probe, l'exécution et l'arrêt du scope.

### P3 — tests, gates et documentation

Les tests distinguent maintenant explicitement `sandboxClaimPermitted()` de `managedLocalProviderClaimPermitted()`, couvrent la composition `StrongOwnedProcessExecutors`, vérifient que le sélecteur distant ne se transforme pas en contrat local, et qualifient la provenance réelle de `cmd.exe`, PowerShell, `sh`, `systemctl` et `systemd-run` sur les OS concernés.

Le gate `scripts/docs/product-facts.py --check`, déjà exécuté par PR Validation, exige désormais que STATUS, ROADMAP et registre des risques présentent #227 comme intégrée.
Il rejette les marqueurs de statut contradictoires associés à cette PR dans les documents courants.

## État produit

- **C0 → M30** : terminés et intégrés.
- **M29 issue #107** : **CLOSED** ; **M29 PR #108** intégrée.
- **M30 PR #110** intégrée ; **M30 promotion PR #111** intégrée.
- **hardening PR #113** intégré ; **M28 Windows CI PR #117** intégré.
- **#98 sandbox OS réelle** : **IMPLÉMENTÉE + QUALIFIÉE** sur Linux et Windows dans la campagne de convergence.
- **PR #224** : traversées projet/NEXUS et couverture ciblée intégrées.
- **PR #225** : confinement workspace provider/discovery/ignore rules intégré.
- **PR #226** : provenance launcher IntelliJ et derniers walkers provider intégrés.
- **PR #227** : provider egress, provenance `CommandLocator`, reparse private storage et contrat de fallback confinement — **intégrée**.
- **remédiation post-audit courant** : composition provider locale, provenance des autorités de sandbox, tests/gates et réconciliation documentaire — **en qualification, non intégrée**.

## Release 1.0.1

La release **MINOS v1.0.1** a été publiée le **9 août 2026** après validation utilisateur réelle du setup Windows.

Tag autoritatif :

```text
v1.0.1 → f762025d66e33c40324c811079f1527d122f90f9
```

La release **v1.0.1 est PUBLIÉE et immuable**.

- URL : https://github.com/FTurleque/minos-code-intelligence/releases/tag/v1.0.1 ;
- publication : **10 assets**, soit **5 paires** artefact/checksum ;
- workflow de publication : `31288322126` ;
- setup Windows, distribution et plugin IntelliJ restent soumis aux gates de provenance, OSV et Plugin Verifier applicables.

## Garanties structurantes courantes

- snapshots structurés autoritatifs et promotions fail-closed ;
- API/CLI/MCP/NEXUS/IntelliJ au-dessus du métier sans réintroduire une autorité concurrente ;
- providers locaux exécutés depuis une copie éphémère bornée avec réseau OS-enforced et job boundary agrégé ;
- la qualification provider locale supervisée est distincte de toute claim d'exécution hostile ;
- workers distants/hostiles toujours fail-closed sans hard filesystem quota `OS_ENFORCED` ;
- egress provider `DENY` par défaut et jamais inféré du seul besoin de résolution de dépendances ;
- exécutables qui créent la sandbox résolus depuis des autorités système canoniques, pas depuis un PATH utilisateur ;
- environnement provider allowlisté ;
- Git distant borné et épinglé à l'endpoint attendu ;
- PostgreSQL hors loopback avec TLS `verify-full`, configuration JDBC allowlistée et transactions encadrées ;
- hosted control plane avec authentification, membership/RBAC et chaîne d'audit HMAC ;
- local storage owner-only, symlink/junction/reparse refusés avant mutation ;
- supply-chain CI et release épinglée à des références immuables lorsqu'une telle garantie est revendiquée.

## Qualification de la remédiation courante

La remédiation ne doit être déclarée intégrée qu'après succès exact-head des workflows applicables sur son HEAD final, notamment PR Validation Linux/Windows, IntelliJ Plugin Validation et les validations spécialisées déclenchées par les chemins modifiés. Aucun merge n'est autorisé par ce document ; la décision d'intégration reste explicite après qualification.
