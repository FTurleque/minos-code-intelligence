# Section 11 — Risques et dette technique

> Preuves : ADR-0036 (Proposed), ADR-0037 (parité pending), ADR-0022 (contraintes restantes),
> ADR-0031 (ANN derrière mesures), ADR-0027 (IntelliJ external), ADR-0021 (Docker autonomy).

> **Registre courant** : ce document est l'historique arc42 du registre de risques initial (période M15–M31). Le
> registre **vivant**, tenu à jour à chaque réconciliation, est [`docs/architecture/risks/register.md`](../risks/register.md)
> — c'est la source à consulter pour l'état actuel. Cette section a été réconciliée le **30 août 2026** avec cet état
> réel (workflows CI actuels, `register.md`) plutôt que laissée à son état de rédaction initiale ; elle n'est plus
> mise à jour au fil de l'eau pour éviter de dupliquer `register.md` en source parallèle divergente.

Légende : Probabilité (P) et Impact (I) : Élevé / Moyen / Faible. Exposition = P × I.
Statut : **OPEN** (toujours vrai) / **MITIGATED** (réduit, pas totalement clos) / **CLOSED** (résolu, preuve ci-dessous) / **ACCEPTED** (risque assumé sciemment).

---

## Registre des risques (état initial M15–M31, réconcilié 30/08/2026)

| Réf | Titre | P | I | Exposition | Statut | Preuve de clôture / mitigation | Propriétaire |
|-----|-------|---|---|-----------|--------|-------------------------------|-------------|
| R-01 | **Parité Docker incomplète** — Backend Docker MCP non encore autonome (indexation, données) | Élevé | Moyen | Élevée | **CLOSED** | Résolu par M29 / PR #108. `docker-release-validation.yml` qualifie l'image provider-complete exacte à chaque candidat ; `docker-upgrade-qualification.yml` qualifie l'upgrade réel A→B. | Équipe MINOS |
| R-02 | **CI automatique de PR manquante** — Qualification uniquement locale Windows | Élevé | Moyen | Élevée | **CLOSED** | `.github/workflows/pr-ci.yml` : Maven `clean verify` exact-head Linux **et** Windows, PostgreSQL requis sur Linux, JaCoCo ciblé, OSV, invariants d'ascendance `main`. | Équipe MINOS |
| R-03 | **Absence de provider sémantique réel** — `LocalHashEmbeddingProvider` n'est pas un modèle de langage | Moyen | Moyen | Moyenne | **CLOSED** | `OllamaEmbeddingProvider` qualifié (SSRF/redirect/host allowlist durcis), avec `LocalHashEmbeddingProvider` conservé comme fallback explicite et non trompeur via l'SPI `EmbeddingProvider` (ADR-0029). | Équipe MINOS |
| R-04 | **ANN non encore décidé** — Recherche vectorielle linéaire O(n) | Faible | Moyen | Faible | **OPEN** (Watchlist) | Toujours vrai. ANN reste introduit uniquement sous mesure d'échelle réelle (ADR-0031) ; aucun claim de scalabilité ANN n'est fait. Suivi actif dans `register.md` (R-04). | Équipe MINOS |
| R-05 | **Plugin IntelliJ Java 21 externe** — Protocole CLI JSON négocié, mais non qualifié dans la CI | Moyen | Faible | Faible | **CLOSED** | `.github/workflows/intellij-plugin.yml` : `test`, `buildPlugin`, `verifyPluginProjectConfiguration`, `verifyPluginStructure`, **Plugin Verifier** (`verifyPlugin`) sous Linux, ownership Windows. Handshake/cache d'identité binaire couvert par `MinosCliClientTest`/`MinosExecutableIdentityTest` (PR #263). | Équipe MINOS |
| R-06 | **ADR-0036 Proposed** — Convergence par mesures et interdiction des claims non qualifiés non encore finalisée | Moyen | Moyen | Moyenne | **CLOSED** | Résolu après M28 et la qualification sandbox (probe réel AppContainer/Job Object avant qualification ; capability-honest documenté dans `register.md`). | Équipe MINOS |
| R-07 | **Aucun test cross-boundary automatique** — Tests Failsafe dans `minos-app` peuvent cacher des régressions de frontière | Moyen | Moyen | Moyenne | **MITIGATED** | Tests de frontière (`NamespaceConventionTest`, `ProviderBoundaryTest`, `MinosApiContractTest`, `MinosMultiRepositoryApiContractTest`) + intégration (CLI/MCP/remote/packaging/PostgreSQL/IntelliJ/sandbox) exécutés dans `pr-ci.yml` sur Linux et Windows. Réduit, pas revendiqué exhaustif — voir `docs/developer/testing.md`. | Équipe MINOS |
| R-08 | **PostgreSQL optionnel non promu** — L'évolution backend est gouvernée par mesures (ADR-0025) ; sans mesures, le backend reste non décidé | Faible | Faible | Faible | **CLOSED** | Résolu par M30 : hardening transactionnel/TLS, PostgreSQL requis sur la voie Linux de `pr-ci.yml`. | Équipe MINOS |

---

## Dette technique (état initial, réconciliée 30/08/2026)

| Réf | Description | Module concerné | Priorité | Statut | Preuve / note |
|-----|------------|-----------------|---------|--------|--------------|
| DT-01 | Suppression du routage métier `minos-mcp → minos-cli` transitoire | `minos-mcp`, `minos-cli` | Haute | **OPEN** — non réévalué dans cette passe | Aucune preuve de clôture identifiée pendant cette réconciliation ; à revalider explicitement avant de le clore. |
| DT-02 | CI automatique de PR (GitHub Actions ou équivalent) | Tous | Haute | **CLOSED** | Voir R-02. |
| DT-03 | Tests d'intégration IDE (plugin IntelliJ vs protocole CLI JSON) | `minos-cli`, `minos-api` | Moyenne | **MITIGATED** | `intellij-plugin.yml` qualifie build/tests/structure/Plugin Verifier ; `MinosCliClientTest`/`MinosExecutableIdentityTest` couvrent désormais le cache de handshake (PR #263). Pas revendiqué exhaustif sur l'ensemble du protocole. |
| DT-04 | Provider d'embeddings réel (au-delà de `LocalHashEmbeddingProvider`) | `minos-application` | Moyenne | **CLOSED** | Voir R-03. |
| DT-05 | Implémentation complète backend Docker (indexation autonome, portabilité des données) | `minos-app`, `minos-mcp` | Haute | **CLOSED** | Voir R-01. |

Pour la dette technique **active** et les risques ouverts depuis la campagne post-#226 (R-09 et suivants, DT-06 et suivants), voir le registre vivant [`docs/architecture/risks/register.md`](../risks/register.md).
