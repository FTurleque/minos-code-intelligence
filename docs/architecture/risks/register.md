# Registre des risques

Dernière réconciliation : **21 août 2026**. **PR #227 intégrée**, puis **PR #228 intégrée** dans `develop` après qualification du head `1a551ff72f95db4e14e8a9597d897491b9c1589a`, merge signé `a042e97ac5e3e2ab7207fa603d85563ea1f71712`.

Le réaudit complet de ce merge a ouvert une remédiation quota/readiness distincte, actuellement en qualification. La version historique détaillée du registre avant la campagne post-#226 est conservée intégralement dans [`../../project/history/reconciliations/risk-register-pre-post226-audit-20260821.md`](../../project/history/reconciliations/risk-register-pre-post226-audit-20260821.md).

Réaudit complémentaire **30 août 2026** : les 17 issues Sonar signalées sur la PR #259 ont été corrigées (PR #262), la mise en cache du handshake `MinosCliClient` a été durcie contre un remplacement de binaire en place (PR #263), `docs/architecture/arc42/11-risques-dette.md` a été réconcilié avec cet état réel (PR #264), et un gate `Docker upgrade evidence gate` bloquant a été introduit pour empêcher une promotion `main` sans preuve d'upgrade A→B réelle pour le SHA candidat exact (PR #265, voir **R-25** ci-dessous — actuellement OPEN faute de runner self-hosted enregistré).

## Risques actifs / résiduels

| Réf | Titre | P | I | Exposition | Statut | Mitigation durable |
|---|---|---:|---:|---:|---|---|
| R-04 | ANN non encore décidé | Faible | Moyen | Faible | Watchlist | Aucun claim ANN ; décision uniquement après mesure. |
| R-09 | Disponibilité des primitives sandbox / délégation cgroup | Moyenne | Élevé | Moyenne | Mitigé / capability-honest | Linux sonde bubblewrap/userns/cgroup ; Windows exige désormais un probe réel AppContainer + Job Object avant qualification ; absence de primitive qualifiée => fail-closed. |
| R-10 | Dérive future de provenance supply-chain | Faible | Élevé | Moyenne | Mitigé / surveillé | Actions par SHA, images/dépendances épinglées ou checksummées quand la garantie est revendiquée, gates packaging/release. |
| R-11 | Pas de quota disque kernel par job | Moyenne | Élevé pour hostile | Moyenne | Résiduel / assumé | Quota d'écriture supervisé + kill du job ; jamais présenté comme `OS_ENFORCED`. Le worker hostile/distant reste fail-closed. |
| R-12 | Disponibilité de la frontière forte CLI IntelliJ | Faible | Moyen | Faible | Mitigé / capability-honest | Job Object Windows / systemd scope Linux ; absence => fail-closed ; launchers d'autorité depuis racines système qualifiées. |
| R-13 | Graphe transitif Coursier scip-java non locké repository-owned | Faible | Moyen | Faible | Assumé / observable | Coordonnée/version et launcher épinglés ; aucune claim bit-reproducible sans lock transitif. |
| R-20 | Fallback Windows de lecture confinée moins fort que `openat` | Faible | Moyen | Faible | Résiduel / capability-honest | `NOFOLLOW_LINKS`, revalidation et rejet `isOther()` ; seule `SecureDirectoryStream` revendique la preuve handle-relative. |
| R-23 | Perte de visibilité du quota filesystem managed-local-provider | Moyenne avant remédiation | Élevé disponibilité | Moyenne | Remédiation en qualification | Perte réelle de visibilité => breach + kill du job ; disparition concurrente normale tolérée ; test adversarial avec FD déjà ouvert. |
| R-24 | Stockage privé AppContainer hors budget / `READY` Windows trop optimiste | Moyenne avant remédiation | Élevé disponibilité / Moyen fonctionnel | Moyenne | Remédiation en qualification | Budget global partitionné roots explicites + stockage fichier privé ; registre privé non mutable ; superviseur armé avant `ResumeThread` ; probe réel AppContainer/Job Object requis avant qualification. |
| R-25 | Aucun runner self-hosted `minos-docker` enregistré : la promotion `develop → main` ne peut produire de preuve Docker A→B réelle pour le candidat | Faible (infra opérateur) | Élevé pour une promotion sans preuve | Moyenne | **OPEN — bloque la promotion tant que non résolu** | `.github/workflows/release-promotion-gate.yml` + `scripts/release/check-docker-upgrade-evidence.py` rendent ce manque **bloquant et visible** plutôt que silencieux : le check `Docker upgrade evidence gate` est requis sur `main` (ruleset dédié, main uniquement) et échoue tant qu'aucune qualification A→B réussie n'existe pour le SHA candidat exact. Résolution : enregistrer le runner self-hosted `minos-docker` (Windows + Docker Desktop Linux containers) et déclencher `docker-upgrade-qualification.yml` pour le candidat avant de merger une promotion. |
| R-27 | Quatre CVE (CVE-2026-71290, CVE-2026-64607, CVE-2026-54399, CVE-2026-54428) dans `httpclient5` 5.5.1 et `httpcore5` 5.3.6, copiés dans `docker-java-transport-zerodep` 3.7.1 qu'embarque Testcontainers 2.0.5 (MINOS-AUD-H03, AUD-DEP-14) | Faible | Moyen (machines de build et de CI) | Faible | **Accepté jusqu'au 2027-01-15** (décision du propriétaire, 2026-10-17) | Portée test seulement, rien n'est livré ; les CVE de `httpcore5` exigent un démon Docker malveillant sur la machine de build. Aucune correction possible au 2026-10-17 : docker-java 3.7.1 est la dernière version, le transport `zerodep` recopie le client HTTP (une version imposée par `dependencyManagement` ne le remplace pas) et Testcontainers instancie ce transport en dur. Les CVE restent visibles dans le rapport `-Paudit-dependency-check-tests` (aucune exception, règle 2 de `docs/developer/quality/code-audit.md`). Révision : dès qu'un docker-java postérieur à 3.7.1 paraît (PR Dependabot du groupe `testcontainers`), au plus tard le 2027-01-15. |
| R-28 | Release Windows non signée par Authenticode : l'installateur, les zips et les exécutables qu'ils contiennent ne portent aucune signature d'éditeur (AUD-DEP-06) | Certaine | Faible (avertissement SmartScreen « éditeur inconnu » au premier lancement) | Faible | **Accepté jusqu'au 2027-10-24** (décision du propriétaire, 2026-10-24) | Motif : aucun service de signature n'est accessible à un mainteneur individuel établi en France au 2026-10-24. Azure Artifact Signing n'est ouvert qu'aux particuliers des États-Unis et du Canada et aux organisations ; SignPath Foundation exige une licence OSI pour tout le projet ; un certificat OV exige un module matériel, donc un runner auto-hébergé. Ce qui protège à la place : l'attestation de provenance `actions/attest-build-provenance` du job `publish` de `release-windows.yml` sur chaque fichier publié, vérifiable par `gh attestation verify <fichier> --repo FTurleque/minos-code-intelligence`, et les sommes SHA-256 publiées. L'état est écrit, jamais présumé : manifeste de release (`"authenticode": {"required": false, "state": "unsigned"}`), texte de la release (`scripts/release/release-integrity-notes-unsigned.md`), `docs/user/production-installation.md`. Le garde-fou `MINOS_REQUIRE_SIGNED_RELEASE` reste en place : activer la signature sera un réglage et un mode de signature (avec son ADR), pas un chantier. Révision : à la création d'une structure juridique dans l'Union européenne, ou dès que des utilisateurs bloquent sur SmartScreen ; au plus tard le 2027-10-24. |
| R-29 | Un avis OSV sur le classpath verrouillé de scip-java 0.13.2 (`scip-java-classpath.lock.json`, AUD-DEP-03) : `GHSA-xxph-c9ww-hj94` (guava 33.6.0-jre) | Faible | Moyen (déni de service par épuisement mémoire dans le bac à sable d'indexation) | Faible | **Accepté jusqu'au 2027-01-28** (décision du propriétaire, 2026-10-28) | guava vient de maven-core 3.9.16 et de guice 5.1.0, que tire `kotlin-scripting-dependencies-maven` 2.4.21 de scip-java ; un jar du verrou ne se remplace pas seul, l'avis se corrige en montant scip-java (`docs/developer/supply-chain.md`). L'avis n'est corrigé qu'en guava 33.7.2, qu'aucune version de scip-java n'embarque au 2026-10-28. Exposition : sous Windows natif scip-java est `BLOCKED` ([ADR 0074](../adr/SEC-0074-java-non-indexable-depuis-windows-voie-wsl2.md)), le classpath livré n'est pas exécuté ; le plan d'administration Docker ne lance aucun provider ; sous Linux natif il tourne dans le bac à sable bubblewrap (plafonds cgroup, egress `DENY`). Dérogation datée dans `scripts/quality/embedded-tools-advisory-waivers.json`, vue par la veille hebdomadaire. Révision : à la première version de scip-java embarquant guava ≥ 33.7.2, au plus tard le 2027-01-28. |

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
| R-17 | Provenance launcher IntelliJ depuis un projet non fiable | Résolu par **PR #226**. |
| R-18 | Egress réseau d'un descendant repository-controlled dans un provider | Résolu par **PR #227** : aucun `ALLOW` implicite ; `networkPolicy()` reste `DENY` par défaut. |
| R-19 | Résolution d'exécutable influencée par le CWD via PATH relatif/vide | Résolu par **PR #227**. |
| R-21 | Exécutable d'autorité sandbox substituable via PATH absolu | Résolu par **PR #228** : commandes d'autorité Linux root-owned depuis racines système ; PowerShell/cmd ancrés à System32 ; `ComSpec` refusé. |
| R-26 | Runtime Java hors support : tous les livrables tournaient sur le JDK 24, sans correctif depuis juillet 2025 (AUD-DEP-01) | Résolu par la migration vers Java 25 LTS (ADR 0059) : `maven.compiler.release` 25, enforcer `[25,26)`, images `eclipse-temurin:25`, gate `check-toolchain-baseline.py` ; la politique de toolchain date la fin de support (septembre 2031) et ouvre le changement suivant douze mois avant. |
| R-22 | Contrat hostile réutilisé par erreur pour le provider local | Résolu par **PR #228** : qualification managed-local distincte et sélecteur distant hostile inchangé. |

## Dette technique active

| Réf | Description | Priorité | État |
|---|---|---:|---|
| DT-06 | Décider ANN uniquement après mesure d'un besoin réel | Faible | Watchlist |
| DT-08 | Augmenter progressivement les seuils JaCoCo avec des tests comportementaux utiles | Faible | Continu |
| DT-09 | Étudier un lock transitif repository-owned pour Coursier si la reproductibilité bit-à-bit devient contractuelle | Faible | Watchlist |
| DT-11 | Instabilité ponctuelle apt/mirror GitHub-hosted pour le toolchain sandbox Linux | Faible | Mitigé / surveillé par timeout + fallback miroir |
| DT-12 | Évaluer une primitive Windows native handle-relative/file-identity si une équivalence stricte `openat` devient nécessaire | Faible | Watchlist ; aucun faux claim |
| DT-13 | Étudier un hard filesystem quota portable par job/provider | Moyenne | Nécessaire avant activation d'une claim hostile distante complète |

## Barrières anti-régression de la remédiation courante

- `ProviderWriteQuotaSupervisor` tue le job sur perte réelle de visibilité et possède un test adversarial FD-ouvert/répertoire illisible ;
- le budget Windows reste borné à la somme du quota roots explicites et du quota stockage privé AppContainer ;
- le stockage registre privé AppContainer reçoit un deny d'écriture avant reprise du child ;
- le stockage fichier privé est supervisé avant `ResumeThread` et une perte de visibilité tue le Job Object ;
- la découverte Windows doit réussir un probe réel AppContainer/Job Object ;
- la qualification provider gérée dépend du sandbox réellement utilisé à l'exécution ;
- le worker distant reste basé sur `supportsUntrustedCode()` et exige toujours le hard filesystem quota hostile ;
- le workflow `Post-228 Hardening Invariants` exécute Linux + Windows exact-head, docs, `mvn verify`, tests réels et couverture ciblée ;
- le gate post-#228 exige que STATUS, ROADMAP et ce registre présentent #228 comme intégrée avec head `1a551ff72f95db4e14e8a9597d897491b9c1589a` et merge `a042e97ac5e3e2ab7207fa603d85563ea1f71712`.

Aucun risque R-23/R-24 n'est considéré clos par la seule présence du code sur sa branche : la clôture exige qualification exact-head puis merge explicite.
