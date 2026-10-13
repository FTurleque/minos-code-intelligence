# Tasks

Ordre : mesure, décision, puis rouge, correction, preuve. Tout `-pl` s'accompagne de `-am`. Les exigences de `specs/` décrivent l'option recommandée (D1-A ou B) ; si le propriétaire choisit C ou « ne rien changer », les réviser par `/opsx:update` avant d'implémenter.

## 1. Mesure et décision (AUD-PERF-07)

- [ ] 1.1 [AUD-PERF-07] Mesurer `cacheStats().providerKeyNanos()` sur ce dépôt, sur un succès de cache : snapshot avec empreinte exacte, puis snapshot sans empreinte (indexation pendant laquelle un fichier est modifié). Relever aussi le nombre de sources lues. Consigner dans `design.md` ; requalifier la confiance du constat (« confirmé » ou « non reproduit »).
- [ ] 1.2 [AUD-PERF-07] Établir si `JavaProgramGraphEngine` construit le graphe depuis les sources courantes du disque ou depuis le snapshot (ce que dira exactement la limitation). Relire les littéraux de `check-advanced-provider-consistency.py:96`, `check-mnd.py:101-104`, `check-mne.py:98-101` et `ProgramGraphPerformanceQualificationTest.java:76`.
- [ ] 1.3 [AUD-PERF-07] **Décision à obtenir** : D1 (A, recommandé ; B ; C ; ou ne rien changer si 1.1 montre un coût négligeable). Consigner dans `design.md`.

## 2. Cache servi sans relecture (AUD-PERF-07)

- [ ] 2.1 [AUD-PERF-07] Rouge : test de `ProgramGraphService` avec un espace de sources qui compte ses lectures — deux `getGraph`, avec puis sans empreinte exacte : aucune lecture au second appel. **Preuve du rouge : sans empreinte, toutes les sources sont relues au HEAD.**
- [ ] 2.2 [AUD-PERF-07] Correction selon D1 ; `provider.cacheKey(project, snapshot)` reste l'appel d'obtention de la clé (gate) ou le gate est mis à jour dans cette tâche. Réécrire `ProgramGraphPerformanceQualificationTest` (D4). Preuve : tests verts ; `python scripts/quality/check-advanced-provider-consistency.py`, `python scripts/remediation/check-mnd.py`, `check-mne.py` verts ; scope JaCoCo `advanced-impact-security` ; `./mvnw -B -ntp -pl minos-bootstrap -am verify`.

## 3. Limitation déclarée (AUD-PERF-07)

- [ ] 3.1 [AUD-PERF-07] Rouge : tests des trois outils — sans empreinte exacte, la limitation est présente avec le même identifiant (MCP et CLI) ; avec empreinte, elle est absente et les sorties égalent les goldens.
- [ ] 3.2 [AUD-PERF-07] Constante de limitation unique émise par le service et relayée par `minos_program_graph`, `minos_impact_v2`, `minos_security_paths` (D3). Preuve : tests verts ; `./mvnw -B -ntp -pl minos-app -am verify` (goldens inchangés avec empreinte).
- [ ] 3.3 [AUD-PERF-07] Vérifier que le plugin IntelliJ tolère la limitation inconnue (`minos-intellij/gradlew test`, test de protocole) ; documenter la limitation dans `docs/user/mcp.md` et la référence CLI. Rejouer `python scripts/docs/check-current-docs.py`.

## 4. Clôture

- [ ] 4.1 Mettre à jour `docs/audit/2026-10-10/SUIVI.md` (AUD-PERF-07 : confiance requalifiée, mesure 1.1, décision D1, écart sur l'heuristique taille + date).
- [ ] 4.2 `openspec validate --all --strict` ; docs à mettre à jour avant archivage : `docs/STATUS.md`, `docs/user/mcp.md`, note de mise en œuvre de l'ADR 0052 si D1-A.
