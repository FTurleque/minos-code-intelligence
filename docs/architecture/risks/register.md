# Registre des risques

Dernière réconciliation : **21 août 2026**. **PR #227 intégrée** dans `develop@32c376ed36595ff60daa7cda9367cba787547069`.

Le nouvel audit complet de ce HEAD a ouvert une remédiation distincte, actuellement en qualification. La version historique détaillée du registre avant la campagne post-#226 est conservée intégralement dans [`../../history/reconciliations/risk-register-pre-post226-audit-20260821.md`](../../history/reconciliations/risk-register-pre-post226-audit-20260821.md).

## Risques actifs / résiduels

| Réf | Titre | P | I | Exposition | Statut | Mitigation durable |
|---|---|---:|---:|---:|---|---|
| R-04 | ANN non encore décidé | Faible | Moyen | Faible | Watchlist | Aucun claim ANN ; décision uniquement après mesure. |
| R-09 | Disponibilité des primitives sandbox / délégation cgroup | Moyenne | Élevé | Moyenne | Mitigé / capability-honest | Linux sonde bubblewrap/userns/cgroup ; Windows AppContainer + Job Object vérifié ; absence de primitive qualifiée => fail-closed. |
| R-10 | Dérive future de provenance supply-chain | Faible | Élevé | Moyenne | Mitigé / surveillé | Actions par SHA, images/dépendances épinglées ou checksummées quand la garantie est revendiquée, gates packaging/release. |
| R-11 | Pas de quota disque kernel par job | Moyenne | Élevé pour hostile | Moyenne | Résiduel / assumé | Quota d'écriture supervisé + kill du job au dépassement ; jamais présenté comme `OS_ENFORCED`. Le worker hostile/distant reste fail-closed. Le niveau managed-local-provider accepte explicitement la supervision sans modifier `supportsUntrustedCode()`. |
| R-12 | Disponibilité de la frontière forte CLI IntelliJ | Faible | Moyen | Faible | Mitigé / capability-honest | Job Object Windows / systemd scope Linux ; absence => fail-closed ; launchers d'autorité résolus depuis les racines système plutôt que PATH/ComSpec. |
| R-13 | Graphe transitif Coursier scip-java non locké repository-owned | Faible | Moyen | Faible | Assumé / observable | Coordonnée/version et launcher épinglés ; aucune claim bit-reproducible sans lock transitif. |
| R-20 | Fallback Windows de lecture confinée moins fort que `openat` | Faible | Moyen | Faible | Résiduel / capability-honest | `NOFOLLOW_LINKS`, revalidation et rejet `isOther()` conservés ; seule la stratégie `SecureDirectoryStream` revendique la preuve handle-relative. Les callers exigeant cette propriété testent `supportsDirectoryHandleTraversal`. |
| R-21 | Exécutable d'autorité sandbox substituable via PATH absolu | Moyenne avant remédiation | Élevé | Moyenne | Remédiation en qualification | Linux : `bwrap`, `prlimit`, `sh`, `systemctl`, `systemd-run` depuis racines système canoniques UID 0 non group/world-writable. Windows : PowerShell/cmd ancrés à System32 ; `ComSpec` refusé comme trust source. |
| R-22 | Contrat hostile réutilisé par erreur pour le provider local | Élevée avant remédiation | Élevé fonctionnel | Élevée | Remédiation en qualification | Sélecteurs distincts hostile et managed-local-provider ; tests de composition ; le quota supervisé ne devient jamais une claim hostile. |

## Risques résolus / reclassés récemment

| Réf | Ancien risque | Résolution |
|---|---|---|
| R-01 | Parité Docker incomplète | Résolu par M29 / PR #108. |
| R-02 | CI automatique de PR manquante | Résolu : PR Validation Linux/Windows, OSV, JaCoCo et workflows spécialisés. |
| R-03 | Provider sémantique réel manquant | Résolu par Ollama qualifié, avec fallback local-hash explicite. |
| R-05 | Plugin IntelliJ non qualifié | Résolu : build/tests/structure + Plugin Verifier. |
| R-06 | ADR-0036 non finalisé | Résolu après M28 et qualification sandbox. |
| R-07 | Tests cross-boundary insuffisants | Réduit : API/CLI/MCP/remote/packaging/PostgreSQL/IntelliJ/sandbox + tests adversariaux Windows/Linux. |
| R-08 | PostgreSQL backend non promu | Résolu par M30 et hardening transactionnel/TLS. |
| R-14 | Divergence autonome/snapshot autoritatif | Résolu par réconciliation fail-closed commune. |
| R-15 | Confusion d'identité stores file-backed | Résolu par validation UUID lookup/listing. |
| R-16 | Containment créé avant armement cleanup | Résolu : lifecycle englobe transform/validation/pré-start/exécution. |
| R-17 | Provenance launcher IntelliJ depuis un projet non fiable | Résolu par **PR #226** pour la surface projet/CWD ; la provenance des exécutables d'autorité système est renforcée séparément par la remédiation courante. |
| R-18 | Egress réseau d'un descendant repository-controlled dans un provider | Résolu par **PR #227** : aucun `ALLOW` implicite ; `networkPolicy()` reste `DENY` par défaut. |
| R-19 | Résolution d'exécutable influencée par le CWD via PATH relatif/vide | Résolu par **PR #227** : éléments vides/relatifs refusés, répertoires/exécutables canonisés, batch Windows ancré en absolu. |

## Dette technique active

| Réf | Description | Priorité | État |
|---|---|---:|---|
| DT-06 | Décider ANN uniquement après mesure d'un besoin réel | Faible | Watchlist |
| DT-08 | Augmenter progressivement les seuils JaCoCo avec des tests comportementaux utiles | Faible | Continu |
| DT-09 | Étudier un lock transitif repository-owned pour Coursier si la reproductibilité bit-à-bit devient contractuelle | Faible | Watchlist |
| DT-11 | Instabilité ponctuelle apt/mirror GitHub-hosted pour le toolchain sandbox Linux | Faible | Mitigé / surveillé par timeout + fallback miroir |
| DT-12 | Évaluer une primitive Windows native handle-relative/file-identity si une équivalence stricte `openat` devient nécessaire pour les lectures de projet concurrentes | Faible | Watchlist ; aucun faux claim dans le produit |
| DT-13 | Étudier un hard filesystem quota portable par job/provider | Moyenne | Nouveau / nécessaire avant activation d'une claim hostile distante complète |

## Barrières anti-régression de la remédiation courante

- `WorkerResourceContainment` distingue explicitement hard hostile et supervised managed-local-provider ;
- `WorkerSandboxBackends` expose deux sélecteurs séparés et le sélecteur strict distant reste basé sur `supportsUntrustedCode()` ;
- test de composition `StrongOwnedProcessExecutors` : quota filesystem supervisé => provider local `READY`, mais hostile claim toujours fausse ;
- `CommandLocator` résout les commandes Linux d'autorité sans PATH depuis des racines UID 0 non group/world-writable ;
- tests Windows vérifiant `cmd.exe` et PowerShell canoniques System32 ;
- tests IntelliJ vérifiant `/v:off`, `cmd.exe` System32 et `systemctl`/`systemd-run` root-owned ;
- `product-facts.py --check` exige que #227 soit exposée comme intégrée dans les trois documents courants ;
- le même gate refuse les marqueurs de statut contradictoires associés à cette PR.

Aucun risque de la remédiation courante n'est considéré clos par la seule présence du code sur sa branche : la clôture exige qualification exact-head puis merge explicite.
