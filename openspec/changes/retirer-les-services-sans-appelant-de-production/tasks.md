# Tasks

Ordre : relevé et décision, puis une section par sort de classe (rouge, correction, preuve), puis le gate. Tout `-pl` s'accompagne de `-am`. Les sections 2 à 4 appliquent la recommandation de D1 ; si le propriétaire décide autrement pour une classe, réviser la section par `/opsx:update` avant d'implémenter.

## 1. Relevé et décision (AUD-QUA-02)

- [ ] 1.1 [AUD-QUA-02] Pour chacune des six classes : relire ses tests et dire ce qu'ils prouvent ; établir si l'alignement empreinte / snapshot actif de `ProjectFingerprintSnapshotAlignmentService` est déjà vérifié sur le chemin du produit (`ProjectInvalidationService`) ; relever ce que `M17ProviderPlatformTest` et `ProviderConformanceKitTest` attendent de `IndexerProviderRegistry`. Consigner dans `design.md`.
- [ ] 1.2 [AUD-QUA-02] Calculer sur le HEAD, par un script jetable, la liste de tous les types publics de premier niveau sans référence de production, avec et sans les exemptions par règle de D2 ; consigner le décompte par module dans `design.md` (taille initiale de la liste).
- [ ] 1.3 [AUD-QUA-02] **Décision à obtenir** : D1, classe par classe (tableau de `design.md`), en particulier `NexusSemanticSignalService` (surface publique) et `IndexerProviderRegistry` (ADR 0026). Consigner dans `design.md`.

## 2. Classes supprimées (AUD-QUA-02)

- [ ] 2.1 [AUD-QUA-02] Rouge : `git grep -lw` de `ProjectFingerprintSnapshotAlignmentService`, `IndexerProviderRegistry` et `InterproceduralFlowService` dans les sources Java et dans `scripts/quality/` retourne des fichiers. **Preuve du rouge : 4, 3 et 2 fichiers Java, et `check-jacoco.py:29`.**
- [ ] 2.2 [AUD-QUA-02] Supprimer les trois classes selon D1 ; réécrire sur `IndexerRegistry` les deux tests de `IndexerProviderRegistry` ; retirer les tests devenus sans objet en nommant ce que chacun prouvait ; retirer `InterproceduralFlowService` de `scripts/quality/check-jacoco.py:29` dans cette tâche. Preuve : recherches vides ; `./mvnw -B -ntp -pl minos-bootstrap -am verify` ; `./mvnw -B -ntp -pl minos-app -am verify` ; `python scripts/quality/check-jacoco.py` (portée de `:29` rejouée, seuil inchangé) ; `python scripts/architecture/check-module-boundaries.py`.

## 3. Évaluateurs (AUD-QUA-02)

- [ ] 3.1 [AUD-QUA-02] Rouge : `SemanticSearchEvaluator` est dans `src/main` de `minos-application` et dans la portée `check-jacoco.py:53`. Déplacer la classe dans les sources de test de `minos-bootstrap` (package de ce module, ADR 0044) et la retirer de la portée dans cette tâche. Preuve : `./mvnw -B -ntp -pl minos-bootstrap -am verify` ; `python scripts/quality/check-jacoco.py` (portée de `:53` rejouée).
- [ ] 3.2 [AUD-QUA-02] `ProgramGraphEvaluator` reste en production (D1) : aucun déplacement ; vérifier que `python scripts/quality/check-java-ast-provider-consistency.py` reste vert (`:109`). Son inscription sur la liste se fait en 4.2.

## 4. Gate à cliquet (AUD-QUA-02)

- [ ] 4.1 [AUD-QUA-02] Rouge : dans `scripts/architecture/test_check_module_boundaries.py`, sur arbres temporaires — classe publique sans appelant refusée ; classe nommée seulement par un test refusée ; classe nommée dans `META-INF/services` acceptée ; classe à `main` acceptée ; entrée périmée, entrée devenue inutile et entrée sans raison refusées. **Preuve du rouge : la règle, sans liste, échoue sur le HEAD en nommant au moins les classes de D1 restantes.**
- [ ] 4.2 [AUD-QUA-02] Règle et liste `UNREFERENCED_PRODUCTION_TYPES` dans `scripts/architecture/check-module-boundaries.py` (D2) ; liste initiale issue de 1.2, chaque entrée avec sa raison (dont `ProgramGraphEvaluator`, `NexusSemanticSignalService`, `CodeKnowledgeSnapshotBinaryCodec`, `HostedAvailabilityPort`, `HostedTransportSecurityPort` si elles sont dans le cas). Preuve : `python scripts/architecture/check-module-boundaries.py` et son auto-test verts ; `python scripts/quality/check-ci-wiring.py` et `python scripts/quality/check-single-execution.py` verts ; `python .claude/scripts/run_gates.py --fast`.

## 5. Clôture

- [ ] 5.1 Documents qui nomment une classe retirée ou déplacée : `docs/developer/semantic-hybrid-intelligence.md`, `docs/developer/java-advanced-provider.md`, `docs/developer/public-surfaces.md`, `docs/user/java-api.md` selon D1 ; note de « Mise en œuvre » de l'ADR 0026 si `IndexerProviderRegistry` est supprimé ; `docs/developer/quality-gates.md` (règle nouvelle et façon de réduire la liste). Rejouer `python scripts/docs/check-current-docs.py` et `python scripts/docs/product-facts.py --check`.
- [ ] 5.2 Mettre à jour `docs/audit/2026-10-10/SUIVI.md` (AUD-QUA-02 : décision par classe, taille initiale de la liste, faux positifs relevés sur `NexusSemanticSignalService` et `IndexerProviderRegistry`).
- [ ] 5.3 `openspec validate --all --strict` ; docs à mettre à jour avant archivage : `docs/STATUS.md`, `docs/developer/quality-gates.md`, ADR 0026.
