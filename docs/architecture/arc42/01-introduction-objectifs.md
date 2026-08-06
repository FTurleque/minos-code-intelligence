# Section 1 — Introduction et objectifs

> Preuves : `pom.xml` racine, `docs/adr/README.md`, `docs/adr/0001-*.md`,
> `docs/research/COMPARATIF_FONDATIONS_CODE_INTELLIGENCE.md`,
> `minos-cli/src/main/java/com/minos/cli/MinosCli.java` (USAGE)

---

## 1.1 Résumé du système

**MINOS Code Intelligence** est un moteur local-first de Code Intelligence multi-langages et multi-indexeurs.
Il indexe des dépôts de code source privés, normalise les résultats en un modèle de domaine neutre, puis les expose via :

- une **CLI stable** (`minos <commande>`) ;
- un **serveur MCP STDIO read-only** (`minos mcp`) consommable par tout agent IA ;
- une **API Java publique** (`minos-api`) ;
- un **contrat JSON NEXUS** pour l'intégration avec des orchestrateurs externes.

MINOS n'est ni un service cloud, ni un serveur HTTP, ni une base de données clé-en-main.
C'est un composant local embarqué, distribué sous la forme d'un shaded JAR et d'un exécutable natif Windows.

---

## 1.2 Objectifs métier

| # | Objectif | Origine |
|---|----------|---------|
| OB-1 | Fournir aux développeurs et agents IA un contexte de code précis, borné et traçable, sans envoyer le code source hors du poste local | ADR-0001, ADR-0029 |
| OB-2 | Supporter tout langage et tout indexeur sans modifier le cœur métier | ADR-0001 |
| OB-3 | Permettre la navigation de symboles (définitions, références, implémentations, callers/callees, impact) sur des dépôts privés | `MinosCli.java` USAGE |
| OB-4 | Exposer MINOS comme source de Code Intelligence dans les agents IA (Claude Code, Copilot, Codex…) via MCP | ADR-0017 |
| OB-5 | Fournir une analyse d'impact conservative et expliquée sur le graphe observé | ADR-0015 |
| OB-6 | Supporter un mode équipe opt-in avec contrôle tenant, chiffrement et audit | ADR-0035 |

---

## 1.3 Parties prenantes

| Partie prenante | Rôle | Attente principale |
|----------------|------|-------------------|
| Développeur local | Utilisateur CLI et IDE | Navigation de code rapide, résultats fiables et bornés |
| Agent IA (Claude Code, Copilot, Codex) | Consommateur MCP | Réponses JSON structurées, read-only, sans side-effects |
| Intégrateur Java | Consommateur `minos-api` | Contrat Java stable et versionné |
| Orchestrateur NEXUS | Consommateur du contrat JSON NEXUS | Export snapshot normalisé conforme au format NEXUS |
| Équipe engineering MINOS | Mainteneur | Frontières d'architecture imposées par Maven, tests qualifiés |
| Administrateur tenant (mode équipe) | Gestionnaire RBAC | Contrôle des workspaces partagés, audit, chiffrement |

---

## 1.4 Objectifs qualité priorisés

| Priorité | Qualité | Scénario représentatif | Seuil |
|---------|---------|----------------------|-------|
| 1 | **Exactitude / Honnêteté** | Une relation retournée doit être traçable jusqu'à une preuve du provider ; aucune relation inventée | 0 relation sans `Evidence` ni `ResolutionStatus` |
| 2 | **Isolement local** | Aucune donnée de code source ne quitte le poste sans consentement explicite | Aucun appel réseau implicite dans le mode natif |
| 3 | **Extensibilité du langage** | Ajout d'un provider Python sans modifier `minos-domain` ni `minos-engine` | 0 ligne modifiée dans les modules ≤ `minos-engine` |
| 4 | **Stabilité des surfaces** | Un consommateur CLI M14 fonctionne avec M29 sans réécriture de sa configuration | 0 breaking change dans les contrats CLI/MCP/API qualifiés |
| 5 | **Performance de requête** | Requête repeated p50 ≤ 5 ms sur jeu de test M15 | p50 ≤ 5 ms, p95 ≤ 10 ms (baseline : p50 = 3,88 ms, p95 = 5,34 ms) |
