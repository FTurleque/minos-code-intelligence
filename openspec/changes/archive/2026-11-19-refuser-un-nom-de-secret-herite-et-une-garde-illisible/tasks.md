# Tasks

## 1. Motif de secret sur les variables héritées (AUD-SEC-05)

- [x] 1.1 AUD-SEC-05 — Rouge : écrire `ProviderProcessEnvironmentInheritedSecretsTest` dans `minos-runtime-local` : (a) parcours des trois listes héritées, échec sur une entrée qui satisfait `isDeniedName` (vert dès l'écriture : il garde les listes) ; (b) avec une liste injectée contenant `EXAMPLE_TOKEN_FILE`, la variable héritée est absente de l'environnement du provider (`sanitize`) et de celui du lanceur (`applyForTrustedLauncher`) (`installation-des-providers › Une variable héritée à motif de secret n'atteint ni un provider ni un lanceur`). Vérifier que (b) échoue parce que la variable est transmise
- [x] 1.2 AUD-SEC-05 — Correction : appliquer `!isDeniedName(key)` dans les deux boucles héritées de `ProviderProcessEnvironment` et corriger la javadoc de `isDeniedName` ; vérifier que 1.1 passe et que `ProviderProcessEnvironmentTest`, `ProviderProcessEnvironmentAllowlistTest`, `ProviderProcessEnvironmentDeclaredSecretsTest` et `InstallationProcessEnvironmentTest` restent verts sans changement d'attente (`./mvnw -B -ntp -pl minos-runtime-local -am test`) ; rejouer `python scripts/quality/check-comment-language.py`
- [x] 1.3 AUD-SEC-05 — Preuve et documentation : `.claude/rules/securite.md` (règle 3) et `docs/architecture/diagrams/capacites/installation-des-providers.md` mis à jour. Couverture de `ProviderProcessEnvironment` mesurée sur les seuls tests ciblés de la tâche (rapport `jacoco:report` du module, pas le rapport agrégé de `check-jacoco.py`, non rejoué localement) : lignes 99 % (57 sur 58), branches 87 % (33 sur 38), à l'usage de `relever-les-planchers-de-la-frontiere-des-providers`, qui se fusionne après celui-ci

## 2. Garde Windows fail-closed sur une variable illisible (AUD-QUA-05)

- [x] 2.1 AUD-QUA-05 — Rouge : extraire, sans changer le comportement, la surcharge de portée paquet de `isOverBroadGrant` et de `isWindowsSystemRoot` qui reçoit le lecteur d'environnement (D3) ; écrire `WindowsOverBroadGrantGuardTest`, qui juge la réponse des deux prédicats (écart E5), avec les quatre scénarios de `confinement-code-non-fiable › Un emplacement large illisible fait refuser l'octroi d'une racine d'outillage sous Windows`. Vérifier que « Variable de profil non analysable » échoue (réponse « non trop large ») et que « Racine système non analysable » échoue sur une exception. Chercher d'abord `isOverBroadGrant` et `isWindowsSystemRoot` dans `scripts/` (gates littéraux)
- [x] 2.2 AUD-QUA-05 — Arbitrage de l'axe Sécurité sur D2 : la recommandation du design (juger trop large) est retenue le 2026-11-15, faute de décision contraire du propriétaire ; correction : n'intercepter que `InvalidPathException`, répondre « trop large » dans la garde et dans le contrôle de la racine système. Vérifié : `WindowsOverBroadGrantGuardTest` vert (3 échecs sur 8 avant la correction, dont une exception qui reprenait la valeur de la variable), `WindowsAppContainerWorkerSandboxBackendTest` (15) et `WindowsAppContainerSandboxPlanTest` (11) verts sous Windows, aucun message de test ne contient la valeur
- [ ] 2.3 AUD-QUA-05 — Preuve et documentation : **(manuelle)** `Verify (windows-2022)` vert sur la PR, puis `python scripts/quality/check-jacoco.py` sur le rapport agrégé Windows pour le scope qui nomme `WindowsAppContainerWorkerSandboxBackend` (mesure partielle locale sur les seuls tests ciblés : lignes 91 %, branches 76 %) ; `docs/architecture/diagrams/capacites/confinement-code-non-fiable.md` mis à jour en prose (le dossier n'a pas de diagramme de la garde) — **Reste au 2026-11-19** : archivé sans cette tâche, voir `docs/project/audits/2026-11-15/SUIVI.md`.

## 3. Clôture

- [x] 3.1 Matrice de traçabilité du design mise à jour : chaque scénario nomme son test écrit
- [x] 3.2 Dossiers durables mis à jour en prose ; `python scripts/docs/check-capability-dossiers.py` vert. Les lignes de la table de traçabilité des deux exigences ajoutées ne s'écrivent qu'à l'archivage : le contrôle refuse une ligne dont l'exigence n'est pas encore dans la spec de référence (règle 5 du gate)
- [x] 3.3 Valider les spécifications ; vérifier par `openspec validate --all --strict` et `python .claude/scripts/run_gates.py --fast`
- [x] 3.4 Lister les documents à mettre à jour avant archivage (STATUS, ROADMAP, `.claude/rules/securite.md`, `SUIVI.md` de l'audit du 2026-11-15) ; vérifier que chacun est fait ou nommé comme reste

## Workflow follow-up

- Archiver le changement après revue (`openspec archive refuser-un-nom-de-secret-herite-et-une-garde-illisible -y`), puis vérifier que chaque spec de référence garde sa section `## Documentation liée`.
