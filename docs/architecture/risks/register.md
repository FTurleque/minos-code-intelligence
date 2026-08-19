# Registre des risques

> Référence : [Section 11 — Risques et dette](../arc42/11-risques-dette.md)

Dernière réconciliation : **19 août 2026**, réaudit complet de `develop` après PR #215, #216, #217 et #218.

> **Convention de référencement.** L'état courant est ancré sur des **numéros de PR**, jamais sur un SHA de `develop`. Un SHA cité dans un document est périmé dès le merge qui l'introduit — le commit de merge est nécessairement postérieur au contenu qu'il publie —, ce qui recréait une dérive à chaque réconciliation. Les SHA immuables (tags de release, par exemple `v1.0.1`) restent cités explicitement : eux ne bougent jamais.

---

## Risques actifs / résiduels

| Réf | Titre | P | I | Exposition | Propriétaire | Statut | Mitigation durable |
|-----|-------|---|---|-----------|-------------|--------|--------------------|
| R-04 | ANN non encore décidé | Faible | Moyen | Faible | Équipe MINOS | Watchlist | Aucun claim ANN ; évolution uniquement après mesure d'un besoin réel. |
| R-09 | Disponibilité des primitives sandbox et de la délégation cgroup selon l'OS / LSM | Moyenne | Élevé | Moyenne | Équipe MINOS / opérateur | Mitigé / capability-honest | Linux sonde réellement `bubblewrap`, les user namespaces **et** la délégation cgroup v2 avant de déclarer `OS_ENFORCED`; Windows utilise AppContainer + Job Object vérifié. `WorkerResourceContainment` distingue garantie OS, supervision MINOS et simple mesure. En absence d'une primitive qualifiée, toute exécution distante (`ALLOW` ou `DENY`) échoue de façon fail-closed. Les diagnostics MINOS sont ouverts avant l'exécution non fiable et restent liés à des descripteurs hôte afin qu'un remplacement de pathname/symlink depuis le sandbox ne puisse pas rediriger une écriture hôte. |
| R-11 | Absence de quota disque par job non privilégié sur Linux et Windows | Moyenne | Moyen | Moyenne | Équipe MINOS | Mitigé / assumé | Le budget d'écriture (octets et entrées) est appliqué pendant l'exécution par `ProviderWriteQuotaSupervisor`, qui détruit la frontière de job au dépassement, et est déclaré `SUPERVISED_HARD_KILL` — jamais `OS_ENFORCED`. |
| R-10 | Dérive future de provenance supply-chain | Faible | Élevé | Moyenne | Équipe MINOS | Mitigé / surveillé | GitHub Actions épinglées par SHA, images OCI par digest, archive Ubuntu datée pour les paquets système, Maven Wrapper et Maven Docker vérifiés par SHA-256 possédé par le dépôt, providers binaires directs par checksum attendu, npm via lockfiles v3 + `npm ci --ignore-scripts`, `scip-dotnet` via nupkg SHA-256 + source NuGet locale, Go via version exacte + `proxy.golang.org`/`sum.golang.org`. Gate packaging anti-régression. |
| R-12 | Disponibilité de la frontière forte du CLI IntelliJ selon la plateforme | Faible | Moyen | Faible | Équipe MINOS / opérateur | Mitigé / capability-honest | Windows utilise un Job Object établi avant reprise du CLI ; Linux exige un user manager systemd capable de créer un scope transitoire. Une plateforme non qualifiée ou sans primitive disponible échoue fermée au lieu de revenir au polling `ProcessHandle`. |
| R-13 | Graphe transitif Coursier de `scip-java` non matérialisé par un lockfile possédé par le dépôt | Faible | Moyen | Faible | Équipe MINOS | Assumé / observable | La version/coordonnée `org.scip-code:scip-java:0.13.1` et le launcher Coursier sont épinglés ; le binaire standalone produit est hashé dans les preuves de build. MINOS **ne revendique pas** une reproductibilité bit-à-bit du bootstrap Coursier tant qu'un verrou transitif repository-owned n'existe pas. La query plane reste offline et aucun provider n'est téléchargé à l'exécution MCP. |

---

## Risques résolus par les jalons et le post-audit

| Réf | Ancien risque | Résolution |
|-----|---------------|------------|
| R-01 | Parité Docker incomplète | Résolu par M29 : runtime provider-complete, routage `native|docker`, qualification et packaging dédiés. |
| R-02 | CI automatique de PR manquante | Résolu : PR Validation Linux/Windows, PostgreSQL/pgvector réel sur Linux, OSV, JaCoCo ciblé et workflows spécialisés. |
| R-03 | Absence de provider sémantique réel | Résolu : provider Ollama qualifié, parsing JSON robuste et mode `local-hash` conservé comme fallback explicite. |
| R-05 | Plugin IntelliJ non qualifié en CI | Résolu : tests/build/structure + IntelliJ Plugin Verifier et packaging release. |
| R-06 | ADR-0036 non finalisé | Résolu : ADR accepté après convergence M28 et qualification post-audit des frontières sandbox. |
| R-07 | Tests cross-boundary insuffisants | Réduit à un niveau acceptable : gates API/CLI/MCP, remote/distributed, packaging, PostgreSQL, IntelliJ et tests sandbox négatifs Linux/Windows, complétés par fault paths de containment, divergence snapshot et attaques de pathname/symlink. |
| R-08 | PostgreSQL backend non promu | Résolu par M30 et durci post-audit : migrations sérialisées par advisory lock, schéma v2, unicité racine et upsert atomique. |
| R-14 | Divergence silencieuse entre état autonome et snapshot autoritatif | Résolu : `LocalAutonomousIndexOperations` utilise le `ProjectIndexStateReconciler` commun ; un metadata state qui référence un snapshot absent échoue fermé et l'évidence n'est plus réécrite en `NEVER_INDEXED`. |
| R-15 | Confusion d'identité dans les stores file-backed | Résolu : projet, workspace, project index state et run vérifient systématiquement l'UUID embarqué contre la clé/nom de fichier attendu, lookup comme listing. |
| R-16 | Ressource de containment créée avant l'armement du cleanup | Résolu : le scope de release englobe désormais transformation, validation, préparation diagnostics et exécution ; un échec pré-start libère la frontière OS. |

---

## Dette technique active

| Réf | Description | Module | Priorité | État |
|-----|-------------|--------|----------|------|
| DT-06 | Décider ANN uniquement si les profils sémantiques montrent un besoin mesuré | semantic/storage | Faible | Watchlist |
| DT-08 | Continuer la hausse progressive des seuils JaCoCo à mesure que des tests comportementaux utiles sont ajoutés | tous | Faible | Continu |
| DT-09 | Étudier un lock transitif repository-owned pour le bootstrap Coursier `scip-java` si une exigence de reproductibilité bit-à-bit Docker devient contractuelle | packaging/supply-chain | Faible | Watchlist — aucune claim bit-reproducible actuelle |
| DT-11 | L'étape CI `Install and authorize Linux worker sandbox runtime` (`apt-get install bubblewrap util-linux apparmor apparmor-profiles` + `apparmor_parser`) se bloque par intermittence sur les runners GitHub-hosted : ~15 s en temps normal, observée à 19 min puis encore bloquée après relance le 19 août 2026, alors que le **même commit** (`develop` post-#218) franchissait cette étape en 4 min sur un run parallèle. L'étape ne produit **aucune sortie** avant expiration : `apt-get update` cale d'emblée, sans réponse de miroir. Incident apt/réseau externe, sans rapport avec le code MINOS. Cause précise identifiée dans les logs : le miroir `azure.archive.ubuntu.com` du runner ne répond que par `Ign`, apt bascule alors sur `archive.ubuntu.com` (qui répond correctement) mais n'a plus le temps de terminer. Double mitigation sur les 4 occurrences de l'étape (pr-ci ×2, m19, m20) : (1) `apt-get` reçoit `Acquire::http/https::Timeout=15` et surtout `Acquire::Retries=1` — apt n'a par défaut aucun délai global sur un miroir bloqué, et un nombre de retries élevé est **contre-productif** avec une mirrorlist : il épuise le budget sur le miroir mort au lieu de laisser le fallback aboutir ; (2) `timeout-minutes: 10` reste le garde-fou pour une panne de tous les miroirs. Comparaison A/B observée le 19 août 2026 : sur l'ancien workflow (sans borne) l'étape est restée bloquée > 26 min ; sur le nouveau elle échoue proprement à 10 min. | ci/infrastructure | Faible | Mitigé / surveillé — cause externe, non corrigeable côté MINOS |
| DT-10 | `WindowsStrongProcessOwnershipContainmentTest` est racy sous charge CI (timing de la création/lecture du PID d'un enfant détaché et de l'autorisation de chemin avant lancement) : confirmé le 19 août 2026 en observant le même commit `7f7d8a27` produire deux résultats différents sur deux runs CI parallèles déclenchés simultanément (`push` : 134/134 ; `pull_request` : 2 erreurs sur les mêmes tests). Pas de régression fonctionnelle constatée. | minos-runtime-local (tests) | Faible | Ouvert — robustifier l'attente déterministe au lieu du timing fixe |

## Dette clôturée par les campagnes post-audit

- dépendance architecturale `minos-api → minos-cli` supprimée ;
- configuration par `MINOS_HOME` rendue sans contamination JVM globale ;
- courses PostgreSQL migration/enregistrement supprimées ;
- `ProviderId`, confinement de chemins et artefacts `NOFOLLOW_LINKS` durcis ;
- faux contournement SAST `safeCommand` supprimé ;
- sorties des processus IntelliJ bornées ;
- Maven Wrapper et principales entrées Docker/provider supply-chain épinglés et vérifiés ;
- worker sandbox OS réel implémenté et qualifié sur Linux et Windows ;
- diagnostics provider rendus résistants au remplacement de pathname/symlink ;
- lifecycle de containment rendu sûr sur les échecs pré-start ;
- réconciliation autonome alignée sur l'autorité snapshot commune ;
- identités des stores file-backed rendues fail-closed ;
- **DT-07** — prérequis opérateur Linux (`bubblewrap`, `util-linux`, profil AppArmor `bwrap-userns-restrict` quand applicable, délégation cgroup v2 via `Delegate=yes` ou `MINOS_SANDBOX_CGROUP_ROOT`) documentés dans `docs/user/remote-indexing.md` avec un script de provisioning dédié (`scripts/deploy/provision-linux-sandbox-cgroup.sh`) pour l'alternative manuelle à une unité systemd déléguée. **Correction du 19 août 2026** : la première version de cette procédure accordait au compte MINOS la propriété de `/sys/fs/cgroup/cgroup.procs` (racine), ce qui constituait une évasion de délégation — sa clôture initiale était donc prématurée. La procédure applique désormais le modèle contenu décrit ci-dessous (`--attach-pid`) et ne demande plus aucun droit hors du sous-arbre délégué ;
- **délégation cgroup v2 contenue** — la migration privilégiée est effectuée par le script de provisioning lui-même (`--attach-pid`), qui place le shell lanceur dans `$ROOT/minos-controller` ; MINOS démarre donc déjà dans le cgroup contrôleur, ne migre aucun processus et n'écrit que dans le sous-arbre qu'il possède. `scripts/remediation/check-p0-p2.py` interdit désormais toute réintroduction d'un `chown`/`chmod`/`chgrp`/`setfacl` visant le `cgroup.procs` racine, et exige que les workflows exerçant la sandbox Linux attachent réellement leur shell ;
- restauration fail-closed d'un artefact provider préexistant et rejet des jonctions Windows dans les walkers de suppression récursive/mesure (PR #216) ;
- moindre privilège des workflows de release Windows/IntelliJ et immutabilité de la release IntelliJ liée au commit résolu (PR #216) ;
- arguments de chaîne des outils MCP bornés par des `maxLength` sémantiques centralisés, appliqués au schéma et revérifiés côté serveur (PR #216) ;
- stores file-backed, décodage SCIP, endpoints JGit, providers locaux et registre PostgreSQL durcis contre les frontières de confiance provider/filesystem (PR #215).
