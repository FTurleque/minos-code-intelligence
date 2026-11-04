# Tasks

## 1. Retrait fail-closed des droits AppContainer

- [x] 1.1 Rouge : ajouter à `WindowsAppContainerRecoveryOwnershipTest` les tests `aGrantThatCannotBeRemovedAtTheEndOfARunKeepsItsJournalForTheNextLauncher` et `aRecoveryWhoseGrantRemovalFailsKeepsTheJournalForTheNextLauncher` (échec injecté par un refus `OWNER RIGHTS:(WDAC)`) pour « confinement-code-non-fiable › Les autorisations accordées à un conteneur sont retirées même après un arrêt brutal » ; vérifier qu'ils échouent avant la correction
- [x] 1.2 Correction : tester `$LASTEXITCODE` dans `Remove-AppContainerPath`, ne supprimer le journal qu'après retraits et `DeleteProfile` prouvés (fin de run et `Recover-Stale`), garder le fichier de verrou tant que le journal existe (`Close-OwnershipLock`) ; mettre à jour dans `WindowsContainmentScriptTest` le SHA-256 du fragment et la dérive approuvée `removeApprovedFailClosedGrantRemoval` ; vérifier que les tests 1.1, `WindowsAppContainerRecoveryOwnershipTest` et `WindowsContainmentScriptTest` passent sans test sauté ; rejouer `python .claude/scripts/run_gates.py --fast` (dont `scripts/remediation/check-post228-hardening.py`)
- [ ] 1.3 Documentation, une fois le chantier detailed-uml commité et cette branche mise à jour sur develop : dans `docs/architecture/diagrams/capacites/confinement-code-non-fiable.md`, ajouter à la vue V2 l'alternative « journal réduit aux chemins en échec et verrou relâché conservés », passer la ligne de traçabilité de l'exigence à `couverte` en citant les deux tests 1.1, clore l'écart E1 ; vérifier par `python scripts/docs/check-capability-dossiers.py`

## 2. Clôture

- [x] 2.1 Mettre à jour la matrice de traçabilité du design (exigence → scénario → conception ou diagramme → tâche → test ou preuve) en nommant les preuves manquantes ; vérifier qu'aucune ligne n'affirme une couverture sans preuve
- [ ] 2.2 Mettre à jour le dossier durable de chaque capacité touchée (tâche 1.3) ; vérifier par `python scripts/docs/check-capability-dossiers.py`
- [x] 2.3 Valider les spécifications ; vérifier par `openspec validate --all --strict`
- [x] 2.4 Lister les documents à mettre à jour avant archivage (STATUS, ROADMAP, docs/user, docs/developer, arc42, ADR) ; vérifier que chacun est fait ou nommé comme reste — aucun : correctif interne sans surface publique ni décision structurante ; seul le dossier de capacité change (1.3)
- [ ] 2.5 **(manuelle)** Constater le passage des deux tests 1.1 sur `Verify (windows-2022)` dans la CI de la PR

## Workflow follow-up

- Archiver le changement après revue (`openspec archive conserver-le-journal-si-le-retrait-echoue -y`), puis vérifier que la spec de référence garde sa section `## Documentation liée`.
