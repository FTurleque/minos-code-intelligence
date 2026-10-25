# Proposal

## Why

Le constat **AUD-ARC-05** de l'audit du 10 octobre 2026 relève que `minos-engine` lance des processus, alors que l'ADR 0068 § 2 réserve le runtime de processus à `minos-runtime-local`. `PrivateLocalStorage` lance `icacls` à deux sites (`minos-engine/src/main/java/com/minos/io/PrivateLocalStorage.java:828`, `:866`), seule entrée de la règle A12 (`PROCESS_LAUNCHERS`).

La décision D2 du 2026-10-22, reprise par l'ADR 0068 § 2, fixe la sortie : « son mécanisme d'ACL Windows sort du moteur par un port d'E/S de plateforme fourni par `minos-runtime-local` et injecté », après AUD-SEC-06. AUD-SEC-06 est fusionné (PR #491). Il restait à mesurer le nombre d'objets durcis pendant une indexation ; c'est fait (`docs/project/audits/2026-10-10/SUIVI.md`, « Phase 2 — mesure des objets durcis ») :

- un premier `minos index` réel sous Windows durcit 80 objets, avec 164 appels `icacls` ;
- une réindexation durcit 33 objets, et chaque processus durcit à nouveau tout ce qu'il touche ;
- le durcissement pèse environ 12 % du temps d'un petit index.

La mesure a aussi révélé un défaut : un chemin de plus de 260 caractères fait échouer `icacls` (code 3), et l'index refuse. Le préfixe `\\?\` le corrige.

## What Changes

- **Port de plateforme** `com.minos.io.PlatformAccessControl` dans `minos-engine`, avec deux opérations : couper l'héritage d'une DACL déjà écrite, et refuser un objet qui porte un refus conditionnel ou dont la DACL est illisible. `PrivateLocalStorage` le découvre une seule fois par `ServiceLoader`, avec son propre chargeur de classes (A16). Il ne le consulte que sur un système de fichiers à ACL, jamais sous POSIX.
- **Fail-closed** : sur un système de fichiers à ACL, aucune implémentation, ou plusieurs, fait échouer le durcissement par une erreur sans chemin. Le moteur ne lance plus aucun processus, et `PROCESS_LAUNCHERS` (A12) est vide.
- **Implémentation dans `minos-runtime-local`** (nouveau package `com.minos.runtime.local.acl`). Un seul lanceur d'`icacls` sert le port et les octrois du bac à sable (`WindowsAclGrants`). Il lance par chemin absolu sous le répertoire Windows, sans shell, avec une attente bornée, et passe le chemin cible avec le préfixe `\\?\`.
- **Tests des modules inférieurs** : `minos-engine`, les deux stockages, `minos-application` et `minos-integration-git` ne peuvent pas voir `minos-runtime-local`. Leurs tests Windows (95 classes, mesurées) reçoivent une doublure de test du port, publiée dans le `test-jar` de `minos-engine` et enregistrée par ces seuls modules. Un test de parité dans `minos-runtime-local` vérifie que la doublure et l'implémentation réelle produisent la même DACL.
- **Comportement Windows inchangé** : refus d'un refus conditionnel (XD), garde du profil (H23), dossier d'un autre compte, retrait d'une autorisation conditionnelle héritée. Les tests existants sont rejoués sans être affaiblis.
- **Cartographie de `hosted`** : le package ne lance aucun processus et ne fait aucune E/S de fichier. Il reste dans le moteur (ADR 0068 § 3).

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `stockage-prive-et-secrets` : trois exigences ajoutées (port découvert fail-closed, chemins longs, parité de la doublure).
- `frontieres-de-modules` : l'exigence A12 est modifiée (la liste est vide), et une exigence est ajoutée (le package `acl` du runtime local n'est nommé par aucun autre module).

## Hors périmètre

- **Lot 2 d'AUD-ARC-01** (diagnostic de l'hôte, `DoctorCommand`) : changement suivant de la phase 2, après la CI de celui-ci.
- **Remplacer `icacls` par un appel natif** (API Foreign Function, `SetSecurityInfo`) : le port le permet sans toucher au moteur, mais c'est une décision séparée.
- **Mémoire des objets protégés entre processus** : chaque commande CLI durcit à nouveau ce qu'elle touche (mesure) ; la garder sur disque serait un changement de modèle de confiance.
- **Changement actif `decouper-les-classes-de-plus-de-800-lignes`** : il passe après celui-ci (décision du propriétaire du 2026-10-25). Sa tâche d'extraction de `WindowsDacl` dans le moteur devient sans objet et se met à jour à son rebase.
- La fixture `fixtures/typescript/typescript-modules`, qui étend un `tsconfig.base.json` absent.

## Impact

- **Modules du reactor** : `minos-engine` (port, découverte, retrait de `WindowsDacl`), `minos-runtime-local` (implémentation, lanceur commun, tests déplacés), et en test seulement `minos-storage-local`, `minos-storage-postgresql`, `minos-application` et `minos-integration-git` (dépendance au `test-jar` du moteur et enregistrement de la doublure). Aucune arête Maven de compilation n'est ajoutée. Le service crée une arête d'exécution `minos-engine` → `minos-runtime-local`, qui existe déjà (`ExecutionPathIdentityProvider`).
- **Surfaces publiques** : aucune. CLI, API Java, MCP, IntelliJ et NEXUS sont inchangés, et les goldens ne bougent pas. `PrivateLocalStorage` garde ses méthodes publiques.
- **ADR** : aucun nouvel ADR, la décision existe (ADR 0068 § 2, D2). L'ADR 0068 reçoit une note de mise en œuvre (A12 vide, port découvert par `ServiceLoader`, `hosted` cartographié).
- **Gates** : `check-module-boundaries.py` (A12 vidée, A10 étendue au package `acl`, auto-test), `check-private-io.py` (l'entrée `Files.createTempFile` du fichier SDDL change de fichier), `check-jacoco.py` (portée `confined-io-primitives` re-mesurée), base SpotBugs (8 entrées de `PrivateLocalStorage$WindowsDacl` déplacées).
- **Plateformes** : le comportement change sous Windows seulement (mécanisme déplacé, chemins longs acceptés). Sous Linux, le chemin POSIX ne consulte jamais le port. Les deux jobs `Verify` le prouvent.
- **Constat** : AUD-ARC-05 est fermable quand A12 est vide.
