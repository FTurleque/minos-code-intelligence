# Tasks

Ordre de fusion conseillé : après `fiabiliser-la-surface-de-l-explorateur` (sprint 3) et `unifier-le-contrat-des-codes-de-sortie`, qui touchent `ExplorerSession` et `ExplorerLaunchRouteProvider`.

## 1. Interprétation unique du mode équipe (AUD-ARC-05)

- [ ] 1.1 AUD-ARC-05 — Rouge : écrire `HostedModeTest` dans `minos-application` (table absent, vide, `disabled`, `DISABLED`, `enabled`, `Enabled`, `maybe`) et un test qui ouvre l'application avec chaque valeur et compare le classement. Exigence : exploration-semantique › Le mode équipe est interprété par une seule règle au lancement de l'explorateur (scénario « Même règle que l'application »). Vérifier : échec faute de classe.
- [ ] 1.2 AUD-ARC-05 — Correction : créer `HostedMode` dans `com.minos.application` (pas dans `com.minos.application.team`, qui fermerait un cycle avec `MinosApplication`, dont les constantes `HOSTED_MODE_PROPERTY` / `HOSTED_MODE_ENV` restent en place) et faire appeler `HostedMode.from` par `MinosApplicationRuntimeConfiguration.configureHostedMode`, message d'erreur inchangé. Déplacer vers `HostedMode.java` l'attente `"enabled"` / `"disabled"` de `scripts/quality/check-hosted-control-plane-consistency.py:100-102`. Vérifier : `./mvnw -B -ntp -pl minos-application -am test` ; `python scripts/quality/check-hosted-control-plane-consistency.py` (`:97-98` inchangé) ; `python scripts/architecture/check-module-boundaries.py` (A8 : 0 cycle).

## 2. Politique de lancement dans minos-explorer (AUD-ARC-05)

- [ ] 2.1 AUD-ARC-05 — Rouge : écrire `ExplorerLaunchPolicyTest` (`minos-explorer`, emplacement d'exécution de type `ProjectPathMapping.RuntimeLocation`) et modifier `ExplorerLaunchRouteProviderTest.java:104-118` : la boucle garde `enabled` et `ENABLED` ; `anything-else` sort de la boucle et attend `unsupported hosted mode`, code 2, application non ouverte ; même texte de refus par la route et par `ExplorerSession.serve` ; backend Docker premier sur un poste. Exigences : exploration-semantique › Le mode équipe est interprété par une seule règle au lancement de l'explorateur ; › Chaque refus de lancement de l'explorateur a un seul texte. Vérifier : les cas nouveaux échouent.
- [ ] 2.2 AUD-ARC-05 — Correction : créer `ExplorerLaunchPolicy` dans `minos-explorer` avec les deux messages ; supprimer les constantes de `ExplorerLaunchRouteProvider` et la seconde `TEAM_MODE_REFUSED` d'`ExplorerSession` ; la route appelle `HostedMode.from` puis la politique. Vérifier : la recherche du texte du refus ne rend qu'une ligne de `minos-explorer` ; `./mvnw -B -ntp -pl minos-app -am test` ; `check-post-mne.py` et `check-p0-p2.py` verts ; scope JaCoCo de `check-jacoco.py:236-237`.
- [ ] 2.3 AUD-ARC-05 — Preuve et documentation : chercher le texte du refus dans les goldens de `minos-app/src/test/resources/characterization/` et dans `docs/` ; mettre à jour `docs/user/semantic-explorer.md` et `docs/user/team-hosted-mode.md` (valeurs admises, message pour une valeur non reconnue) ; corriger la Javadoc de `McpBackendConfiguration` et `McpBackendConfigurationStore` (backend d'exécution du home). Vérifier : `git diff --exit-code minos-app/src/test/resources/characterization/` ; `python scripts/docs/check-current-docs.py` ; `python scripts/quality/check-comment-language.py`.
- [ ] 2.4 AUD-ARC-05 — **(manuelle, propriétaire)** confirmer D3 du design (pas de renommage de `McpBackend*`). Vérifier : choix consigné dans la PR.

## 3. Liste fermée des classes de minos-app (AUD-ARC-05)

- [ ] 3.1 AUD-ARC-05 — Rouge : ajouter à `scripts/architecture/test_check_module_boundaries.py` les cas « nouvelle classe », « entrée périmée », « rôle absent » et le cas accepté. Exigence : frontieres-de-modules › Les classes de production de minos-app sont tenues par liste fermée. Vérifier : échec faute de fonction `check_*`.
- [ ] 3.2 AUD-ARC-05 — Correction : ajouter au garde la table (8 classes au HEAD, chacune avec son rôle) et sa règle (prochain numéro libre) ; brancher dans `main`. Mettre à jour les dossiers `docs/architecture/diagrams/capacites/frontieres-de-modules.md` et `exploration-semantique.md`. Vérifier : `python scripts/architecture/check-module-boundaries.py`, son auto-test, `check-ci-wiring.py`, `check-single-execution.py`, `python scripts/docs/check-capability-dossiers.py`.

## 4. Clôture

- [ ] 4.1 AUD-ARC-05 — Mettre à jour la matrice de traçabilité du design en nommant les preuves manquantes ; vérifier qu'aucune ligne n'affirme une couverture sans preuve.
- [ ] 4.2 AUD-ARC-05 — Contrôler les dossiers de capacité ; vérifier par `python scripts/docs/check-capability-dossiers.py`.
- [ ] 4.3 AUD-ARC-05 — Valider les spécifications ; vérifier par `openspec validate --all --strict` et `python .claude/scripts/run_gates.py --fast`.
- [ ] 4.4 AUD-ARC-05 — Lister les documents à mettre à jour avant archivage (STATUS, ROADMAP, notes de version pour le message modifié, `docs/user`, suivi de l'audit) ; vérifier que chacun est fait ou nommé comme reste.

## Workflow follow-up

- Archiver le changement après revue (`openspec archive centraliser-la-politique-de-lancement-de-l-explorateur -y`), puis vérifier que les deux specs de référence gardent leur section `## Documentation liée`.
