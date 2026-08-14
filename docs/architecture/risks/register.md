# Registre des risques

> Référence : [Section 11 — Risques et dette](../arc42/11-risques-dette.md)

Dernière réconciliation : **14 août 2026**, réaudit complet de `develop@20ce803ea43fbfa579b463f79e04e9272b2b81ce` après PR #183 et campagne de remédiation associée.

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
| DT-07 | Documenter/provisionner les prérequis Linux de sandbox (`bubblewrap`, util-linux, politique userns/LSM, racine cgroup v2 déléguée via `Delegate=yes` ou `MINOS_SANDBOX_CGROUP_ROOT`) dans chaque environnement opérateur | runtime/deployment | Moyenne | Ouvert — l'absence reste fail-closed |
| DT-08 | Continuer la hausse progressive des seuils JaCoCo à mesure que des tests comportementaux utiles sont ajoutés | tous | Faible | Continu |
| DT-09 | Étudier un lock transitif repository-owned pour le bootstrap Coursier `scip-java` si une exigence de reproductibilité bit-à-bit Docker devient contractuelle | packaging/supply-chain | Faible | Watchlist — aucune claim bit-reproducible actuelle |

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
- identités des stores file-backed rendues fail-closed.
