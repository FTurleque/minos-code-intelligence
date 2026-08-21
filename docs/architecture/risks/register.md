# Registre des risques

Dernière réconciliation : **21 août 2026**. État courant de `develop` ancré jusqu'à **PR #226 intégrée** ; remédiation du nouvel audit complet portée par **PR #227**, en qualification et non intégrée.

La version historique détaillée du registre avant cette campagne est conservée intégralement dans [`../../history/reconciliations/risk-register-pre-post226-audit-20260821.md`](../../history/reconciliations/risk-register-pre-post226-audit-20260821.md).

## Risques actifs / résiduels

| Réf | Titre | P | I | Exposition | Statut | Mitigation durable |
|---|---|---:|---:|---:|---|---|
| R-04 | ANN non encore décidé | Faible | Moyen | Faible | Watchlist | Aucun claim ANN ; décision uniquement après mesure. |
| R-09 | Disponibilité des primitives sandbox / délégation cgroup | Moyenne | Élevé | Moyenne | Mitigé / capability-honest | Linux sonde bubblewrap/userns/cgroup ; Windows AppContainer + Job Object vérifié ; absence de primitive qualifiée => fail-closed. |
| R-10 | Dérive future de provenance supply-chain | Faible | Élevé | Moyenne | Mitigé / surveillé | Actions par SHA, images/dépendances épinglées ou checksummées quand la garantie est revendiquée, gates packaging/release. |
| R-11 | Pas de quota disque kernel par job | Moyenne | Moyen | Moyenne | Mitigé / assumé | Quota d'écriture supervisé + kill du job au dépassement ; jamais présenté comme `OS_ENFORCED`. |
| R-12 | Disponibilité de la frontière forte CLI IntelliJ | Faible | Moyen | Faible | Mitigé / capability-honest | Job Object Windows / systemd scope Linux ; absence => fail-closed. |
| R-13 | Graphe transitif Coursier scip-java non locké repository-owned | Faible | Moyen | Faible | Assumé / observable | Coordonnée/version et launcher épinglés ; aucune claim bit-reproducible sans lock transitif. |
| R-18 | Egress réseau d'un descendant repository-controlled dans un provider | Élevée avant #227 | Élevé | Moyenne | Remédiation PR #227 | Aucun `ALLOW` implicite. `IndexerProcessPlanFactory.networkPolicy()` est `DENY` par défaut ; ALLOW exige un opt-in explicite et une phase dont la confiance est démontrée. |
| R-19 | Résolution d'exécutable influencée par le CWD via PATH relatif/vide | Moyenne avant #227 | Élevé | Moyenne | Remédiation PR #227 | `CommandLocator` ignore les éléments PATH vides/relatifs, canonise les répertoires/exécutables et refuse un `cmd.exe` Windows non résolu en absolu. |
| R-20 | Fallback Windows de lecture confinée moins fort que `openat` | Faible | Moyen | Faible | Résiduel / capability-honest | `NOFOLLOW_LINKS`, revalidation et rejet `isOther()` conservés ; seule la stratégie `SecureDirectoryStream` revendique la preuve handle-relative. Les callers exigeant cette propriété testent `supportsDirectoryHandleTraversal`. |

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
| R-17 | Provenance launcher IntelliJ depuis un projet non fiable | Résolu par **PR #226** pour la surface IntelliJ : settings IDE-global, launcher résolu avant CWD, PATH relatif/vide refusé, ownership reparse refusé. Le finding plus général `CommandLocator` est suivi séparément par R-19 / PR #227. |

## Dette technique active

| Réf | Description | Priorité | État |
|---|---|---:|---|
| DT-06 | Décider ANN uniquement après mesure d'un besoin réel | Faible | Watchlist |
| DT-08 | Augmenter progressivement les seuils JaCoCo avec des tests comportementaux utiles | Faible | Continu |
| DT-09 | Étudier un lock transitif repository-owned pour Coursier si la reproductibilité bit-à-bit devient contractuelle | Faible | Watchlist |
| DT-11 | Instabilité ponctuelle apt/mirror GitHub-hosted pour le toolchain sandbox Linux | Faible | Mitigé / surveillé par timeout + fallback miroir |
| DT-12 | Évaluer une primitive Windows native handle-relative/file-identity si une équivalence stricte `openat` devient nécessaire pour les lectures de projet concurrentes | Faible | Nouveau / watchlist ; aucun faux claim dans le produit |

## Barrières anti-régression PR #227

- test de politique provider : `DENY` est le défaut, `ALLOW` uniquement si la factory l'exprime explicitement ;
- test `CommandLocator` avec entrée PATH relative réellement résolvable depuis le CWD : elle doit être ignorée ;
- test Windows vérifiant que `cmd.exe` est absolu et existant ;
- vraies junctions `mklink /J` pour `PrivateLocalStorage` et `ConfinedFileOpener` ;
- `check-p0-p2.py` exige les invariants de réseau, provenance des commandes, rejet `isOther()` et wording capability-honest.

Aucun risque de cette section n'est considéré clos par la seule présence du code dans #227 : la clôture exige qualification exact-head puis merge explicite.
