# Registre des risques

> Référence : [Section 11 — Risques et dette](../arc42/11-risques-dette.md)

---

## Risques actifs

| Réf | Titre | P | I | Exposition | Propriétaire | Date cible | Statut |
|-----|-------|---|---|-----------|-------------|-----------|--------|
| R-01 | Parité Docker incomplète (indexation autonome) | Élevé | Moyen | Élevée | Équipe MINOS | M29-S8 | Ouvert |
| R-02 | CI automatique de PR manquante | Élevé | Moyen | Élevée | Équipe MINOS | M30 | Ouvert |
| R-03 | Absence de provider sémantique réel | Moyen | Moyen | Moyenne | Équipe MINOS | M31 | Ouvert |
| R-04 | ANN non encore décidé | Faible | Moyen | Faible | Équipe MINOS | Non daté | Watchlist |
| R-05 | Plugin IntelliJ non qualifié en CI | Moyen | Faible | Faible | Équipe MINOS | M31 | Ouvert |
| R-06 | ADR-0036 Proposed non finalisé | Moyen | Moyen | Moyenne | Équipe MINOS | M30 | Ouvert |
| R-07 | Tests cross-boundary insuffisants | Moyen | Moyen | Moyenne | Équipe MINOS | M30 | Ouvert |
| R-08 | PostgreSQL backend non promu | Faible | Faible | Faible | Équipe MINOS | Non daté | Watchlist |

---

## Dette technique

| Réf | Description | Module | Priorité | Affectation |
|-----|------------|--------|---------|------------|
| DT-01 | Supprimer routage `minos-mcp → minos-cli` transitoire | minos-mcp, minos-cli | Haute | M15-S4 |
| DT-02 | CI automatique de PR | Tous | Haute | M15-S10 |
| DT-03 | Tests intégration IDE (protocole CLI JSON) | minos-cli, minos-api | Moyenne | M31 |
| DT-04 | Provider d'embeddings réel | minos-application | Moyenne | M31 |
| DT-05 | Backend Docker complet (indexation autonome) | minos-app, minos-mcp | Haute | M29 S2–S8 |
