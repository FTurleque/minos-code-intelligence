# Tasks

## 1. Motif de secret sur les variables héritées (AUD-SEC-05)

- [ ] 1.1 AUD-SEC-05 — Rouge : écrire `ProviderProcessEnvironmentInheritedSecretsTest` dans `minos-runtime-local` : (a) parcours des trois listes héritées, échec sur une entrée qui satisfait `isDeniedName` (vert dès l'écriture : il garde les listes) ; (b) avec une liste injectée contenant `EXAMPLE_TOKEN_FILE`, la variable héritée est absente de l'environnement du provider (`sanitize`) et de celui du lanceur (`applyForTrustedLauncher`) (`installation-des-providers › Une variable héritée à motif de secret n'atteint ni un provider ni un lanceur`). Vérifier que (b) échoue parce que la variable est transmise
- [ ] 1.2 AUD-SEC-05 — Correction : appliquer `!isDeniedName(key)` dans les deux boucles héritées de `ProviderProcessEnvironment` et corriger la javadoc de `isDeniedName` ; vérifier que 1.1 passe et que `ProviderProcessEnvironmentTest`, `ProviderProcessEnvironmentAllowlistTest`, `ProviderProcessEnvironmentDeclaredSecretsTest` et `InstallationProcessEnvironmentTest` restent verts sans changement d'attente (`./mvnw -B -ntp -pl minos-runtime-local -am test`) ; rejouer `python scripts/quality/check-comment-language.py`
- [ ] 1.3 AUD-SEC-05 — Preuve et documentation : après `verify`, rejouer `python scripts/quality/check-jacoco.py` (scope qui nomme `com/minos/runtime/local/process/ProviderProcessEnvironment`) et noter dans le design la couverture de branches mesurée, à l'usage du changement `relever-les-planchers-de-la-frontiere-des-providers` (AUD-TST-03), qui se fusionne après celui-ci ; mettre à jour `.claude/rules/securite.md` (règle 3) et `docs/architecture/diagrams/capacites/installation-des-providers.md`

## 2. Garde Windows fail-closed sur une variable illisible (AUD-QUA-05)

- [ ] 2.1 AUD-QUA-05 — Rouge : extraire, sans changer le comportement, la surcharge de portée paquet de `isOverBroadGrant` et de `isWindowsSystemRoot` qui reçoit le lecteur d'environnement (D3) ; écrire `WindowsOverBroadGrantGuardTest`, qui juge la réponse des deux prédicats (écart E5), avec les quatre scénarios de `confinement-code-non-fiable › Un emplacement large illisible fait refuser l'octroi d'une racine d'outillage sous Windows`. Vérifier que « Variable de profil non analysable » échoue (réponse « non trop large ») et que « Racine système non analysable » échoue sur une exception. Chercher d'abord `isOverBroadGrant` et `isWindowsSystemRoot` dans `scripts/` (gates littéraux)
- [ ] 2.2 AUD-QUA-05 — **(manuelle)** Arbitrage de l'axe Sécurité sur D2 (juger trop large, recommandé, ou refuser le plan) ; puis correction : n'intercepter que `InvalidPathException`, répondre « trop large » dans la garde et dans le contrôle de la racine système. Vérifier que 2.1 passe, que `WindowsAppContainerWorkerSandboxBackendTest` et `WindowsAppContainerSandboxPlanTest` restent verts (`mvnw.cmd -B -ntp -pl minos-runtime-local -am test` sous Windows), et qu'aucun message de test ne contient la valeur de la variable
- [ ] 2.3 AUD-QUA-05 — Preuve et documentation : `Verify (windows-2022)` vert sur la PR ; rejouer `python scripts/quality/check-jacoco.py` sous Windows pour le scope qui nomme `WindowsAppContainerWorkerSandboxBackend` ; mettre à jour `docs/architecture/diagrams/capacites/confinement-code-non-fiable.md` (diagramme de la garde)

## 3. Clôture

- [ ] 3.1 Mettre à jour la matrice de traçabilité du design (exigence → scénario → conception ou diagramme → tâche → test ou preuve) en nommant les preuves manquantes ; vérifier qu'aucune ligne n'affirme une couverture sans preuve
- [ ] 3.2 Mettre à jour le dossier durable de chaque capacité touchée ; vérifier par `python scripts/docs/check-capability-dossiers.py`
- [ ] 3.3 Valider les spécifications ; vérifier par `openspec validate --all --strict` et `python .claude/scripts/run_gates.py --fast`
- [ ] 3.4 Lister les documents à mettre à jour avant archivage (STATUS, ROADMAP, `.claude/rules/securite.md`, `SUIVI.md` de l'audit du 2026-11-15) ; vérifier que chacun est fait ou nommé comme reste

## Workflow follow-up

- Archiver le changement après revue (`openspec archive refuser-un-nom-de-secret-herite-et-une-garde-illisible -y`), puis vérifier que chaque spec de référence garde sa section `## Documentation liée`.
