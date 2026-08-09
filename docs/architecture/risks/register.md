# Registre des risques

> Référence : [Section 11 — Risques et dette](../arc42/11-risques-dette.md)

Dernière réconciliation : **9 août 2026**, campagne post-audit #132 / PR #135.

---

## Risques actifs / résiduels

| Réf | Titre | P | I | Exposition | Propriétaire | Statut | Mitigation durable |
|-----|-------|---|---|-----------|-------------|--------|--------------------|
| R-04 | ANN non encore décidé | Faible | Moyen | Faible | Équipe MINOS | Watchlist | Aucun claim ANN ; évolution uniquement après mesure d'un besoin réel. |
| R-09 | Disponibilité des primitives sandbox selon l'OS / LSM | Faible | Élevé | Moyenne | Équipe MINOS / opérateur | Mitigé / capability-honest | Linux sonde réellement `bubblewrap`/user namespaces avant de déclarer `OS_ENFORCED`; Windows utilise AppContainer + Job Object. En absence de primitive qualifiée, `DENY` échoue de façon fail-closed. |
| R-10 | Dérive future de provenance supply-chain | Faible | Élevé | Moyenne | Équipe MINOS | Mitigé / surveillé | GitHub Actions épinglées par SHA, images OCI par digest, providers binaires par checksum attendu, gate `check-workflow-pins.py`. |

---

## Risques résolus par les jalons et le post-audit

| Réf | Ancien risque | Résolution |
|-----|---------------|------------|
| R-01 | Parité Docker incomplète | Résolu par M29 : runtime provider-complete, routage `native|docker`, qualification et packaging dédiés. |
| R-02 | CI automatique de PR manquante | Résolu : PR Validation Linux/Windows, PostgreSQL/pgvector réel sur Linux, OSV, JaCoCo ciblé et workflows spécialisés. |
| R-03 | Absence de provider sémantique réel | Résolu : provider Ollama qualifié, parsing JSON robuste et mode `local-hash` conservé comme fallback explicite. |
| R-05 | Plugin IntelliJ non qualifié en CI | Résolu : tests/build/structure + IntelliJ Plugin Verifier et packaging release. |
| R-06 | ADR-0036 non finalisé | Résolu : ADR accepté après convergence M28 et qualification post-audit des frontières sandbox. |
| R-07 | Tests cross-boundary insuffisants | Réduit à un niveau acceptable : gates API/CLI/MCP, remote/distributed, packaging, PostgreSQL, IntelliJ et tests sandbox négatifs Linux/Windows. |
| R-08 | PostgreSQL backend non promu | Résolu par M30 et durci post-audit : migrations sérialisées par advisory lock, schéma v2, unicité racine et upsert atomique. |

---

## Dette technique active

| Réf | Description | Module | Priorité | État |
|-----|-------------|--------|----------|------|
| DT-06 | Décider ANN uniquement si les profils sémantiques montrent un besoin mesuré | semantic/storage | Faible | Watchlist |
| DT-07 | Documenter/provisionner les prérequis Linux de sandbox (`bubblewrap`, util-linux, politique userns/LSM) dans chaque environnement opérateur | runtime/deployment | Moyenne | Ouvert — l'absence reste fail-closed |
| DT-08 | Continuer la hausse progressive des seuils JaCoCo à mesure que des tests comportementaux utiles sont ajoutés | tous | Faible | Continu |

## Dette clôturée par la campagne post-audit

- dépendance architecturale `minos-api → minos-cli` supprimée ;
- configuration par `MINOS_HOME` rendue sans contamination JVM globale ;
- courses PostgreSQL migration/enregistrement supprimées ;
- `ProviderId`, confinement de chemins et artefacts `NOFOLLOW_LINKS` durcis ;
- faux contournement SAST `safeCommand` supprimé ;
- sorties des processus IntelliJ bornées ;
- bootstrap Coursier et image/provider supply-chain rendus immuables et vérifiés ;
- worker sandbox OS réel implémenté et qualifié sur Linux et Windows.
