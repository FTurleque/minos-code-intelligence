# Tasks

Ordre : rouge, correction, preuve. Tout `-pl` s'accompagne de `-am`.

## 1. Relevé (AUD-PERF-05)

- [ ] 1.1 [AUD-PERF-05] Relire les littéraux de `check-hosted-control-plane-consistency.py` sur `MinosApplicationMcpBackend` ; chercher `getModuleContext` et `getArchitectureIntelligence` dans `scripts/` ; lister les implémentations et faux de `ProjectArchitectureQuery`. Consigner dans `design.md`.

## 2. Un seul chargement de contexte (AUD-PERF-05)

- [ ] 2.1 [AUD-PERF-05] Rouge : tests avec `ProjectDiscoveryService` et store espions — graphe Mermaid avec module par le backend MCP, graphe DOT avec module par `ArchitectureCommand` : 1 découverte, 1 chargement ; module inconnu : même erreur publique, 1 découverte. **Preuve du rouge : 2 et 2 au HEAD, sur les deux surfaces.**
- [ ] 2.2 [AUD-PERF-05] Port `ProjectArchitectureQuery` : opération `default` rendant la vue et le module résolu (D1), redéfinie dans `LocalProjectArchitectureQuery` avec un seul `loadContext` ; `MinosApplicationMcpBackend` et `ArchitectureCommand` l'appellent. Preuve : tests verts ; sorties identiques aux goldens (`./mvnw -B -ntp -pl minos-app -am verify`) ; scope JaCoCo `mcp-mapping` ; `check-hosted-control-plane-consistency.py` et `python scripts/architecture/check-module-boundaries.py` verts.
- [ ] 2.3 [AUD-PERF-05] Mesure indicative avant/après de `minos architecture` avec module sur ce dépôt (durée, nombre de découvertes).

## 3. Clôture

- [ ] 3.1 Mettre à jour `docs/audit/2026-10-10/SUIVI.md` (AUD-PERF-05 : statut, site CLI ajouté, mesure).
- [ ] 3.2 `openspec validate --all --strict` ; docs à mettre à jour avant archivage : `docs/STATUS.md` (aucune autre attendue).
