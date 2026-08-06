# Section 11 — Risques et dette technique

> Preuves : ADR-0036 (Proposed), ADR-0037 (parité pending), ADR-0022 (contraintes restantes),
> ADR-0031 (ANN derrière mesures), ADR-0027 (IntelliJ external), ADR-0021 (Docker autonomy).

Légende : Probabilité (P) et Impact (I) : Élevé / Moyen / Faible. Exposition = P × I.

---

## Registre des risques

| Réf | Titre | P | I | Exposition | Mitigation | Propriétaire | Date cible |
|-----|-------|---|---|-----------|-----------|-------------|-----------|
| R-01 | **Parité Docker incomplète** — Backend Docker MCP non encore autonome (indexation, données) | Élevé | Moyen | Élevée | ADR-0037 S2–S8 à compléter ; jalons mesurés avant promotion | Équipe MINOS | M29-S8 |
| R-02 | **CI automatique de PR manquante** — Qualification uniquement locale Windows | Élevé | Moyen | Élevée | Affecté M15-S10 (dette volontaire) ; risque de régression silencieuse | Équipe MINOS | M30 |
| R-03 | **Absence de provider sémantique réel** — `LocalHashEmbeddingProvider` n'est pas un modèle de langage | Moyen | Moyen | Moyenne | Provider external/cloud configurable via `EmbeddingProvider` SPI (ADR-0029) | Équipe MINOS | M31 |
| R-04 | **ANN non encore décidé** — Recherche vectorielle linéaire O(n) | Faible | Moyen | Faible | ANN introduit uniquement sous mesures d'échelle (ADR-0031) | Équipe MINOS | Non daté |
| R-05 | **Plugin IntelliJ Java 21 externe** — Protocole CLI JSON négocié, mais non qualifié dans la CI | Moyen | Faible | Faible | ADR-0027 définit le protocole ; tests IDE à ajouter | Équipe MINOS | M31 |
| R-06 | **ADR-0036 Proposed** — Convergence par mesures et interdiction des claims non qualifiés non encore finalisée | Moyen | Moyen | Moyenne | Revue ADR-0036 en cours | Équipe MINOS | M30 |
| R-07 | **Aucun test cross-boundary automatique** — Tests Failsafe dans `minos-app` peuvent cacher des régressions de frontière | Moyen | Moyen | Moyenne | Compléter les tests de frontière + CI PR (R-02) | Équipe MINOS | M30 |
| R-08 | **PostgreSQL optionnel non promu** — L'évolution backend est gouvernée par mesures (ADR-0025) ; sans mesures, le backend reste non décidé | Faible | Faible | Faible | Benchmark à planifier avant promotion | Équipe MINOS | Non daté |

---

## Dette technique

| Réf | Description | Module concerné | Priorité | Affectation |
|-----|------------|-----------------|---------|------------|
| DT-01 | Suppression du routage métier `minos-mcp → minos-cli` transitoire | `minos-mcp`, `minos-cli` | Haute | M15-S4 (mentionné ADR-0022) |
| DT-02 | CI automatique de PR (GitHub Actions ou équivalent) | Tous | Haute | M15-S10 (dette volontaire) |
| DT-03 | Tests d'intégration IDE (plugin IntelliJ vs protocole CLI JSON) | `minos-cli`, `minos-api` | Moyenne | M31 |
| DT-04 | Provider d'embeddings réel (au-delà de `LocalHashEmbeddingProvider`) | `minos-application` | Moyenne | M31 |
| DT-05 | Implémentation complète backend Docker (indexation autonome, portabilité des données) | `minos-app`, `minos-mcp` | Haute | M29 S2–S8 |
