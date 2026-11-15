# Tasks

Constat **AUD-QUA-03**. Prérequis : `executer-le-front-de-l-explorateur-en-test` (AUD-TST-02, sprint 1) fusionné, pour le harnais `minos-explorer/src/test/js/` et le gate `scripts/quality/check-explorer-front.py` ; les changements du sprint 3 sur les erreurs de l'explorateur et celui du sprint 4 sur la carte fusionnés avant (ordre de fusion sur `app.js` : sprint 1, sprint 4, puis ce changement). Tout `-pl` s'accompagne de `-am` ; un build Maven à la fois.

## 0. Décision préalable

- [ ] 0.1 [AUD-QUA-03] **(manuelle)** Faire trancher D1 par le propriétaire (A recommandée : anglais pour le neuf, v1 figé ; B : A plus un contrat v2 ; C : renommage avant la première release). Consigner le choix daté dans `design.md`. Si C : arrêter et réécrire specs et tâches. Vérifier : la section D1 porte le choix.
- [ ] 0.2 [AUD-QUA-03] **(manuelle)** Écrire l'ADR « Langue des constantes des contrats publics » (domaine ARCH, prochain numéro libre à l'écriture, gabarit `docs/architecture/adr/gabarit/template.md`, commande `/minos:adr`), l'ajouter à `docs/architecture/adr/README.md` et à `docs/architecture/arc42/09-decisions.md`. Vérifier : `archgate check`, `python scripts/docs/check-current-docs.py`.

## 1. Valeurs du contrat v1 figées

- [ ] 1.1 [AUD-QUA-03] Relever sur le `main` du moment, après la fusion des changements du sprint 3 sur les erreurs de l'explorateur, les valeurs sérialisées par champ (état d'index, état de projection, divergence, refus de préparation, mode de recherche, nature de score, nature de document) dans l'API HTTP, l'API Java et la CLI, et la liste des codes d'erreur émis par `ExplorerApi` et `ExplorerServer` ; corriger les tableaux de `design.md`. Vérifier : tableaux à jour, `fichier:ligne` relus.
- [ ] 1.2 [AUD-QUA-03] Écrire les tests de contrat (design D2) dans `minos-explorer`, `minos-api` (`SemanticExplorationApiContractTest`) et `minos-cli` (`SemanticProjectionCommandLineTest`) : une table par champ, la liste close des huit valeurs françaises de taille affirmée (exploration-semantique › Les valeurs d'énumération du contrat v1 de l'explorateur sont figées). Rouge : renommer localement `PRETE` en `READY` en propageant le renommage à tous ses usages (dont `SemanticProjectionCommand.java:101`) pour que le dépôt compile, puis ajouter une neuvième valeur française ; noter les deux échecs du test de contrat, annuler les mutations. Vérifier : `./mvnw -B -ntp -pl minos-explorer -am test`, puis les deux tests ciblés des autres modules.

## 2. Codes d'erreur de liste fermée

- [ ] 2.1 [AUD-QUA-03] Rouge : test de contrat des codes dans `minos-explorer` — les valeurs de l'énumération des codes égalent la table du test (mutation consignée : `BUSY` renommé avec propagation) ; chaque valeur de `ProjectResolver.ErrorCode` y a son code de même nom ; chaque réponse d'erreur des routes et du serveur porte un code de la liste (exploration-semantique › Toute erreur de l'API de l'explorateur porte un code d'une liste fermée). Vérifier : le test échoue tant que la liste n'existe pas, sortie notée.
- [ ] 2.2 [AUD-QUA-03] Correction : introduire l'énumération des codes dans `minos-explorer` (visibilité package, design D3) ; la fabrique des réponses d'erreur ne reçoit que ce type ; `ExplorerApi` et `ExplorerServer` y prennent leurs codes (sites relevés en 1.1) ; aucun code émis ne change de nom, aucun message ne change. Vérifier : test 2.1 vert ; `ExplorerRoutesTest`, `ExplorerProjectionRoutesTest` et les tests du serveur sans attente modifiée ; `python scripts/quality/check-comment-language.py` ; `python scripts/quality/check-jacoco.py` (scope de `minos-explorer` s'il existe, `global`).

## 3. Traduction par la page

- [ ] 3.1 [AUD-QUA-03] Rouge : dans `minos-explorer/src/test/js/`, tests du front — réponse `BUSY` avec message anglais (le bandeau porte le libellé français, la phrase anglaise est absente), code inconnu (message générique et code en texte), 409 avec et sans code (même reprise qu'aujourd'hui), et table des codes de la liste fermée dont chacun a un libellé (exploration-semantique › La page de l'explorateur traduit le code d'erreur). Vérifier : `python scripts/quality/check-explorer-front.py` échoue sur ces tests, sortie notée.
- [ ] 3.2 [AUD-QUA-03] Correction : table `ERROR_LABELS` et repli dans `minos-explorer/src/main/resources/com/minos/explorer/web/app.js` (fonctions `api` et `report`, `:97-99`, `:117-124` au `89be3fb4`) ; insertion par `textContent`. Mettre à jour `WebResourcesContentTest` si un motif qu'il cherche bouge. Vérifier : `python scripts/quality/check-explorer-front.py` ; `./mvnw -B -ntp -pl minos-explorer -am test`.
- [ ] 3.3 [AUD-QUA-03] Documentation : `docs/user/semantic-explorer.md` (codes d'erreur et leur sens, sans renvoi au dépôt), `docs/developer/semantic-explorer.md` (règle de langue, liste fermée, tables de libellés, lien vers l'ADR). Vérifier : `python scripts/docs/check-current-docs.py`, `python scripts/docs/product-facts.py --check`, liens relatifs.

## 4. Clôture

- [ ] 4.1 [AUD-QUA-03] Mettre à jour la matrice de traçabilité du design en nommant les preuves manquantes.
- [ ] 4.2 [AUD-QUA-03] Mettre à jour `docs/architecture/diagrams/capacites/exploration-semantique.md` (trois exigences, diagramme de séquence des erreurs) ; vérifier par `python scripts/docs/check-capability-dossiers.py`.
- [ ] 4.3 [AUD-QUA-03] `openspec validate --all --strict`, `python .claude/scripts/run_gates.py`, `archgate check`.
- [ ] 4.4 [AUD-QUA-03] Lister les documents à mettre à jour avant archivage (`docs/project/audits/2026-11-15/SUIVI.md`, notes de version de la release qui livrera l'explorateur) ; vérifier que chacun est fait ou nommé comme reste.

## Workflow follow-up

- Archiver le changement après revue (`openspec archive trancher-la-langue-du-contrat-de-l-explorateur -y`), puis vérifier que `openspec/specs/exploration-semantique/spec.md` garde sa section `## Documentation liée`.
