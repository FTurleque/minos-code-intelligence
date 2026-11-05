# Design

## Contexte

Motivation : voir proposal.md (Why). Le changement ne touche que des scénarios de quatre specs de référence ;
aucun code, aucune surface, aucune plateforme. Il est documentaire au sens du schéma : la conception se réduit à
l'état vérifié, aux décisions, à la vérification et à la traçabilité.

## État vérifié au HEAD

Relevé au commit `3b516b1e`.

| Fait | Preuve |
|---|---|
| [Existant vérifié] Six entrées `LOGGER.log(cause)` dans la liste nominative : trois de bac à sable, une de reprise d'indexation, deux du plan de contrôle hébergé | `scripts/architecture/private-io-allowlist.json` |
| [Existant vérifié] Le test de contrat Docker vérifie le classement des services et tolère les images PostgreSQL et Ollama | `minos-app/src/test/java/com/minos/packaging/M29DockerQueryImageContractTest.java:39` |
| [Existant vérifié] `check-compose-limits.py` contrôle les plafonds CPU, mémoire et PID, pas les images | `scripts/quality/check-compose-limits.py` |
| [Existant vérifié] Un worktree lié n'exécute pas le filtre déclaré par le dépôt principal et déclare `REPOSITORY_FILTERS_NOT_APPLIED` | `minos-integration-git/src/test/java/com/minos/integration/git/GitIntelligenceTrustTest.java:96-116` |
| [Existant vérifié] Le plafond de preuves est la constante `MAX_LOCATIONS_PER_EVIDENCE_TYPE` ; le test compare une occurrence par paire à plus que le plafond | `minos-engine/src/main/java/com/minos/query/RelatedTestDerivationService.java:53`, `minos-engine/src/test/java/com/minos/query/RelatedTestDerivationServiceTest.java:252-275` |

## Écarts

Les écarts traités viennent des dossiers durables : `journalisation` E2, `distribution-docker` E3 et E4,
`analyse-git` E2 (volet filtres), `tests-lies-et-impact` E3. Les autres écarts de ces dossiers restent ouverts
(proposal.md, Hors périmètre).

## Exigences couvertes

- journalisation › La cause d'un journal n'est transmise que par une liste nominative — D1
- distribution-docker › Chaque service Docker utilise l'image qui correspond à son rôle — D1
- analyse-git › Aucun filtre déclaré par le dépôt analysé ne s'exécute — D2
- tests-lies-et-impact › Les preuves d'une relation de test lié sont bornées et l'omission est déclarée — D1

## Objectifs et non-objectifs

Non applicable : changement documentaire, voir proposal.md.

## Responsabilités et frontières des composants

Non applicable : aucun composant modifié.

## Modèle de domaine et invariants

Non applicable : aucun type modifié.

## Traitements et interactions

Non applicable : aucun traitement modifié ; la séquence d'ouverture d'un dépôt est dans le dossier `analyse-git`.

## États et transitions

Non applicable : aucun cycle de vie concerné.

## Contrats d'interface

Non applicable : aucune surface publique modifiée.

## Persistance et intégrité

Non applicable : aucune donnée persistée.

## Transactions, concurrence et idempotence

Non applicable : aucun traitement modifié.

## Sécurité et isolation

Non applicable : les garanties décrites (filtres neutralisés, causes de journal nominatives) restent identiques ;
seule leur description change.

## Erreurs et reprise

Non applicable : aucun traitement modifié.

## Observabilité

Non applicable : aucun journal modifié.

## Performance et mesure

Non applicable : aucun traitement modifié.

## Compatibilité, migration et retour arrière

Non applicable : retour arrière par revert du changement ; aucune donnée ni surface concernée.

## Diagrammes

Non applicable : les vues des capacités concernées vivent dans leurs dossiers durables
(`docs/architecture/diagrams/capacites/`) et restent exactes.

## Décisions

### D1 — Réécrire un scénario pour qu'il décrive sa preuve réelle

[Proposition] Quand un scénario affirme plus, ou autre chose, que ce que sa preuve vérifie, et que le
comportement du produit est le comportement voulu, le scénario est réécrit sous MODIFIED en gardant le texte de
l'exigence et les autres scénarios intacts. Alternative écartée : ajouter un test pour coller au scénario
d'origine ; impossible pour le plafond (constante), faux pour la liste (les trois entrées non sandbox sont
voulues), et pour les images (les services tiers ne peuvent pas porter une image MINOS).

### D2 — Énoncer une extension déjà testée

[Proposition] La neutralisation des filtres depuis un worktree lié est implémentée et testée : un scénario l'ajoute
à l'exigence existante plutôt qu'une exigence nouvelle, puisque c'est le même comportement sur une autre forme de
dépôt. Le contrôle du propriétaire sur le répertoire commun, implémenté mais sans test de refus, n'est pas énoncé
ici : un scénario sans preuve contredirait le but du changement.

## Qualification des capacités

Inchangée : les quatre capacités gardent leur qualification ; aucune capacité n'est annoncée en plus.

## Risques et compromis

- [Risque] Une précision masque un vrai défaut → chaque réécriture s'appuie sur un fait vérifié au HEAD (tableau
  ci-dessus) et sur la décision qu'il s'agit du comportement voulu, que le propriétaire valide (proposal.md,
  Statut des informations).

## Vérification

- `openspec validate aligner-quatre-scenarios-sur-leurs-preuves --strict` et `openspec validate --all --strict`.
- Tests existants qui prouvent les scénarios réécrits, rejoués avant archivage :
  `./mvnw -B -ntp -pl minos-integration-git,minos-engine,minos-app -am test "-Dtest=GitIntelligenceTrustTest,RelatedTestDerivationServiceTest,M29DockerQueryImageContractTest"`
  et `python scripts/architecture/check-private-io.py`.
- `python scripts/docs/check-capability-dossiers.py` après mise à jour des dossiers.

## Documentation durable à mettre à jour

Dossiers `docs/architecture/diagrams/capacites/journalisation.md`, `distribution-docker.md`, `analyse-git.md`,
`tests-lies-et-impact.md` : clore les écarts traités après l'archivage.

## Traçabilité

| Exigence (capacité › titre) | Scénario | Conception ou diagramme | Tâche | Test ou preuve |
|---|---|---|---|---|
| journalisation › La cause d'un journal n'est transmise que par une liste nominative | Diagnostic de bac à sable listé | D1 | 1.1 | `scripts/architecture/check-private-io.py` sur le dépôt : SUCCESS le 2026-11-04 ; six entrées de cause (trois de bac à sable, une de reprise, deux du plan de contrôle hébergé), chacune avec fichier, maximum et justification |
| distribution-docker › Chaque service Docker utilise l'image qui correspond à son rôle | Classement des services réels | D1 | 1.2 | `M29DockerQueryImageContractTest#everyComposeServiceUsesTheImageOfItsRole` : classe 6/6, 0 saut, le 2026-11-04 ; seuls `minos-postgres` et `minos-ollama` gardent une image tierce dans `docker/compose-mcp.connected.yaml` |
| analyse-git › Aucun filtre déclaré par le dépôt analysé ne s'exécute | Filtre du dépôt principal atteint depuis un worktree lié | D2 | 1.3 | `GitIntelligenceTrustTest#aTrapOfTheMainRepositoryIsNotExecutedFromALinkedWorktree` : classe 12/12, 0 saut, le 2026-11-04 |
| tests-lies-et-impact › Les preuves d'une relation de test lié sont bornées et l'omission est déclarée | Score inchangé par le plafond | D1 | 1.4 | `RelatedTestDerivationServiceTest#capChangesNeitherPairsNorConfidencesNorOrder` : classe 11/11, 0 saut, le 2026-11-04 |

## Questions ouvertes

Aucune.
