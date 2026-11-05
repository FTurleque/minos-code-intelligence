# Tasks

## 1. Scénarios alignés sur leurs preuves

- [x] 1.1 journalisation › La cause d'un journal n'est transmise que par une liste nominative : relire la liste nominative au moment de l'archivage ; vérifier que `python scripts/architecture/check-private-io.py` réussit sur le dépôt et que chaque entrée de cause a un fichier, un maximum et une justification
  - Preuve (2026-11-04) : `check-private-io.py` : SUCCESS ; six entrées `LOGGER.log(cause)` dans `scripts/architecture/private-io-allowlist.json` (trois de bac à sable, une de reprise d'indexation, deux du plan de contrôle hébergé), chacune avec `file`, `max` et `justification`.
- [x] 1.2 distribution-docker › Chaque service Docker utilise l'image qui correspond à son rôle : vérifier que `./mvnw -B -ntp -pl minos-app -am test "-Dtest=M29DockerQueryImageContractTest"` réussit et que seuls PostgreSQL et Ollama gardent une image tierce dans `docker/compose-mcp.connected.yaml`
  - Preuve (2026-11-04) : `./mvnw -B -ntp -pl minos-app,minos-integration-git,minos-engine -am test "-Dtest=M29DockerQueryImageContractTest,GitIntelligenceTrustTest,RelatedTestDerivationServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false"` : BUILD SUCCESS, `M29DockerQueryImageContractTest` 6/6, 0 saut ; images tierces : `minos-postgres` et `minos-ollama` seulement.
- [x] 1.3 analyse-git › Aucun filtre déclaré par le dépôt analysé ne s'exécute : vérifier que `./mvnw -B -ntp -pl minos-integration-git -am test "-Dtest=GitIntelligenceTrustTest"` réussit, `aTrapOfTheMainRepositoryIsNotExecutedFromALinkedWorktree` compris
  - Preuve (2026-11-04) : `./mvnw -B -ntp -pl minos-app,minos-integration-git,minos-engine -am test "-Dtest=M29DockerQueryImageContractTest,GitIntelligenceTrustTest,RelatedTestDerivationServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false"` : BUILD SUCCESS, `GitIntelligenceTrustTest` 12/12, 0 saut.
- [x] 1.4 tests-lies-et-impact › Les preuves d'une relation de test lié sont bornées et l'omission est déclarée : vérifier que `./mvnw -B -ntp -pl minos-engine -am test "-Dtest=RelatedTestDerivationServiceTest"` réussit, `capChangesNeitherPairsNorConfidencesNorOrder` compris
  - Preuve (2026-11-04) : `./mvnw -B -ntp -pl minos-app,minos-integration-git,minos-engine -am test "-Dtest=M29DockerQueryImageContractTest,GitIntelligenceTrustTest,RelatedTestDerivationServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false"` : BUILD SUCCESS, `RelatedTestDerivationServiceTest` 11/11, 0 saut.
- [x] 1.5 **(manuelle)** Faire valider par le propriétaire que les quatre comportements décrits sont les comportements voulus (proposal.md, Statut des informations)
  - Preuve : validé par le propriétaire le 2026-11-04.

## 2. Clôture

- [x] 2.1 Mettre à jour la matrice de traçabilité du design avec les résultats des tâches 1.1 à 1.4 ; vérifier qu'aucune ligne n'affirme une couverture sans preuve exécutée
- [x] 2.2 Valider les spécifications ; vérifier par `openspec validate --all --strict`
- [x] 2.3 Lister les documents à mettre à jour avant archivage : aucun dans `docs/user/`, `docs/developer/`, STATUS ni ROADMAP (aucun comportement ne change) ; les dossiers de capacité suivent l'archivage

## Workflow follow-up

- Archiver après validation du propriétaire (`openspec archive aligner-quatre-scenarios-sur-leurs-preuves -y`), puis vérifier que les quatre specs de référence gardent leur section `## Documentation liée`.
- Clore dans les dossiers durables les écarts `journalisation` E2, `distribution-docker` E3 et E4, `analyse-git` E2 (volet filtres), `tests-lies-et-impact` E3, mettre à jour les lignes de scénarios couverts, puis rejouer `python scripts/docs/check-capability-dossiers.py`.
