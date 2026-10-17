# État courant — MINOS

Dernière mise à jour : **21 août 2026**.

Ce fichier est la synthèse autoritative de l'état produit courant. L'historique détaillé antérieur à la campagne post-#226 est conservé sans modification dans [`history/reconciliations/STATUS-pre-post226-audit-20260821.md`](history/reconciliations/STATUS-pre-post226-audit-20260821.md).

> **Convention.** Une capacité n'est considérée intégrée qu'après merge. Une correction présente sur une branche ou une PR reste « en qualification » jusqu'à son merge.

## Réconciliation post-PR #228

**PR #228 est intégrée** dans `develop`.

- HEAD exact qualifié de #228 : `1a551ff72f95db4e14e8a9597d897491b9c1589a` ;
- merge signé dans `develop` : `a042e97ac5e3e2ab7207fa603d85563ea1f71712` ;
- composition sandbox/provider local rétablie sans transformer un quota supervisé en claim hostile ;
- `bwrap`, `prlimit`, `sh`, `systemctl`, `systemd-run`, PowerShell et `cmd.exe` utilisés comme autorités de sécurité sont ancrés aux emplacements système qualifiés plutôt qu'à un PATH utilisateur/`ComSpec`.

Le réaudit complet du merge #228 n'a confirmé aucun P0. Il a toutefois identifié deux défauts distincts dans le niveau **managed-local-provider** : la perte de visibilité d'un writable root pouvait être comptée comme zéro par le superviseur de quota, et le stockage privé implicite d'un AppContainer Windows n'entrait pas dans le budget Java. Il a aussi identifié une sémantique `READY` Windows trop optimiste et des gates/docs incomplets.

La remédiation quota/readiness courante est **en qualification, non intégrée**. Elle conserve le contrat hostile strict : les workers distants restent fail-closed sans quota filesystem `OS_ENFORCED`.

### Quota managed-local-provider

La remédiation courante applique les règles suivantes :

- toute perte réelle de visibilité d'un writable root supervisé devient un breach et détruit le job ;
- une disparition concurrente normale d'une entrée ne provoque pas de faux breach ;
- sous Windows, le budget global historique de **8 GiB / 400 000 entrées** reste borné : **7 GiB / 350 000** pour les roots explicites MINOS et **1 GiB / 50 000** réservé au stockage fichier privé AppContainer ;
- les mutations du stockage registre privé AppContainer sont refusées avant reprise du child suspendu ;
- le superviseur du stockage privé est armé avant `ResumeThread` et tue le Job Object au dépassement ou à la perte de visibilité.

### Qualification Windows `READY`

La présence de PowerShell ou d'un launcher ne suffit plus à qualifier le backend AppContainer. La découverte exécute un probe réel et borné du launcher packagé : création du profil, token AppContainer, Job Object, limites relues depuis le noyau, assignment, membership et reprise d'un child inoffensif doivent tous réussir. La disponibilité d'un provider local dépend du sandbox réellement utilisé à l'exécution, et non d'un second launcher ownership-only inutilisé par ce chemin.

### Anti-régression

Un gate dédié post-#228 vérifie statiquement les invariants de quota/readiness et impose une couverture JaCoCo ciblée de `ProviderWriteQuotaSupervisor`. Un workflow exact-head Linux/Windows exécute le gate documentaire courant, `mvn verify`, les tests réels AppContainer/Job Object et le gate de couverture.

## État produit

- **C0 → M30** : terminés et intégrés.
- **M29 issue #107** : **CLOSED** ; **M29 PR #108** intégrée.
- **M30 PR #110** intégrée ; **M30 promotion PR #111** intégrée.
- **hardening PR #113** intégré ; **M28 Windows CI PR #117** intégré.
- **#98 sandbox OS réelle** : **IMPLÉMENTÉE + QUALIFIÉE** sur Linux et Windows dans la campagne de convergence.
- **PR #224** : traversées projet/NEXUS et couverture ciblée intégrées.
- **PR #225** : confinement workspace provider/discovery/ignore rules intégré.
- **PR #226** : provenance launcher IntelliJ et derniers walkers provider intégrés.
- **PR #227** : provider egress, provenance `CommandLocator`, reparse private storage et contrat de fallback confinement intégrés.
- **PR #228** : composition managed-local-provider et provenance des autorités de sandbox **intégrées** au HEAD qualifié `1a551ff72f95db4e14e8a9597d897491b9c1589a`, merge `a042e97ac5e3e2ab7207fa603d85563ea1f71712`.
- **remédiation quota/readiness post-#228** : en qualification, non intégrée.

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
- API/CLI/MCP/NEXUS/IntelliJ au-dessus du métier sans autorité concurrente ;
- providers locaux exécutés depuis une copie éphémère bornée avec réseau OS-enforced et job boundary agrégé ;
- qualification provider locale supervisée strictement distincte de toute claim hostile ;
- workers distants/hostiles fail-closed sans hard filesystem quota `OS_ENFORCED` ;
- egress provider `DENY` par défaut ;
- exécutables qui créent la sandbox résolus depuis des autorités système canoniques ;
- environnement provider allowlisté ;
- stockage privé AppContainer inclus dans la frontière de write containment de la remédiation courante ;
- local storage owner-only, symlink/junction/reparse refusés avant mutation ;
- Git distant, PostgreSQL, hosted control plane, MCP et Ollama conservent leurs frontières fail-closed déjà qualifiées ;
- supply-chain CI et release épinglées à des références immuables lorsqu'une telle garantie est revendiquée.

## Qualification de la remédiation courante

La correction ne sera déclarée intégrée qu'après succès exact-head des workflows applicables sur son HEAD final. Aucun merge n'est autorisé par ce document : l'intégration exige toujours une décision explicite après qualification.
