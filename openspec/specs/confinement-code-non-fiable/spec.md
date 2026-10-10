# confinement-code-non-fiable Specification

## Purpose
Garantit que le confinement du code de provider non fiable n'a pas d'effet de bord sur un sandbox concurrent vivant et ne laisse aucun résidu non borné après un arrêt brutal du processus MINOS, sous Windows et sous Linux.

## Requirements

### Requirement: Un sandbox vivant n'est jamais altéré par un autre lanceur
Sous Windows, tant que le propriétaire d'un sandbox AppContainer est vivant, aucun autre lanceur MINOS, du même processus ou d'un autre, SHALL retirer les droits accordés à ce sandbox, supprimer son profil ou supprimer son journal de reprise.

#### Scenario: Un second lanceur démarre pendant qu'un provider tourne
- **WHEN** un sandbox A exécute un provider qui dort 20 secondes puis écrit un fichier dans son répertoire de travail, et qu'un second lanceur B démarre depuis le même `MINOS_HOME` et s'exécute jusqu'à sa fin
- **THEN** le journal de reprise de A existe toujours, les droits du profil de A sont toujours présents sur ses racines, et le fichier attendu est écrit par le provider A à la fin des 20 secondes

#### Scenario: Une commande de qualification s'exécute pendant une indexation
- **WHEN** une commande qui qualifie le sandbox (sonde de capacité) lance un lanceur pendant qu'un provider d'un autre processus MINOS est en cours
- **THEN** le provider en cours n'observe ni accès refusé ni perte de son profil du fait de cette sonde

### Requirement: Un sandbox dont le propriétaire est mort est récupéré
Sous Windows, un lanceur MINOS SHALL récupérer un sandbox laissé par un propriétaire dont la mort est prouvée : retirer les droits accordés à son profil, supprimer son profil AppContainer et son journal de reprise.

#### Scenario: Le lanceur propriétaire est tué brutalement
- **WHEN** le processus lanceur d'un sandbox A est terminé de force sans passer par sa sortie normale, puis qu'un lanceur B démarre depuis le même `MINOS_HOME`
- **THEN** B retire les droits du profil de A sur chacune de ses racines, supprime le profil de A et supprime son journal de reprise

#### Scenario: Une sortie normale ne laisse rien
- **WHEN** un lanceur termine normalement, avec succès ou en échec du provider
- **THEN** ses droits, son profil et son journal de reprise ont disparu

### Requirement: Une propriété non prouvable n'est jamais récupérée
Sous Windows, un lanceur MINOS SHALL NOT récupérer un sandbox dont la mort du propriétaire n'est pas prouvée positivement : preuve de propriété illisible ou partielle, journal sans marque de propriétaire, journal d'un format antérieur. Il le laisse en place et le signale par un avertissement.

#### Scenario: Journal sans marque de propriétaire
- **WHEN** le répertoire de reprise contient un journal sans marque de propriétaire et qu'un lanceur démarre
- **THEN** ce journal, son profil et ses droits sont intacts et un avertissement l'identifie comme non récupérable

#### Scenario: La preuve de propriété ne peut pas être lue
- **WHEN** la marque de propriétaire d'un journal existe mais ne peut pas être examinée (accès refusé, erreur d'entrée-sortie autre que « propriétaire vivant »)
- **THEN** le journal est laissé en place et signalé, jamais récupéré

### Requirement: La preuve de propriété ne dépend ni d'une horloge ni d'un identifiant réutilisable
Sous Windows, la décision de récupérer ou non un sandbox SHALL NOT dépendre de l'horloge murale ni d'un identifiant de processus susceptible d'être réutilisé : la mort du propriétaire est établie par une marque que le système d'exploitation libère à la mort du processus.

#### Scenario: Saut d'horloge
- **WHEN** l'horloge murale avance ou recule de façon importante pendant qu'un sandbox vivant s'exécute et qu'un autre lanceur démarre
- **THEN** le sandbox vivant n'est pas récupéré

#### Scenario: Identifiant de processus réutilisé
- **WHEN** l'identifiant de processus d'un propriétaire mort est attribué à un processus sans rapport
- **THEN** le sandbox du propriétaire mort est tout de même récupéré, sans que le processus sans rapport ne le protège

### Requirement: Un lanceur qui ne prouve pas sa propre propriété refuse de démarrer
Sous Windows, un lanceur MINOS SHALL NOT démarrer le code d'un provider tant qu'il n'a pas établi la marque de propriété de son propre sandbox. S'il ne le peut pas, il échoue avant toute création de profil, sans lancer de provider et sans mode dégradé.

#### Scenario: La marque de propriété ne peut pas être établie
- **WHEN** la création de la marque de propriété échoue (collision de nom, accès refusé, volume en lecture seule)
- **THEN** le lanceur se termine en échec avant toute exécution du provider et ne laisse ni profil ni droits

### Requirement: Un répertoire de reprise d'un format antérieur n'est pas balayé par le nouveau lanceur
Sous Windows, le nouveau lanceur SHALL écrire ses journaux dans un emplacement de reprise distinct de celui des lanceurs antérieurs et SHALL NOT récupérer les journaux de l'ancien emplacement, de sorte qu'aucun balayage ne s'exerce entre versions.

#### Scenario: Journal hérité d'un lanceur antérieur
- **WHEN** un journal du format antérieur existe dans l'ancien emplacement de reprise et qu'un nouveau lanceur démarre
- **THEN** ce journal reste en place, intact, et le nouveau lanceur ne l'examine pas pour récupération

#### Scenario: Le nouveau lanceur écrit dans son propre emplacement
- **WHEN** un nouveau lanceur démarre un sandbox
- **THEN** son journal de reprise est créé dans l'emplacement du nouveau format et jamais dans l'ancien

### Requirement: Les résidus de travail d'un run mort ont une durée de vie bornée
Lorsque MINOS crée un nouveau répertoire de travail éphémère de provider, il SHALL supprimer les répertoires de travail de runs précédents dont la dernière modification est antérieure à la durée de vie unique des résidus, quelle que soit la plateforme.

#### Scenario: Un répertoire orphelin ancien est supprimé
- **WHEN** `local-provider-workspaces/` contient le répertoire d'un run mort dont la dernière modification remonte à 48 heures, et qu'un autre run crée son répertoire de travail
- **THEN** le répertoire ancien et tout son contenu sont supprimés

#### Scenario: Les autres racines de résidus suivent la même borne
- **WHEN** le cache de dépôts distants contient un temporaire `.entry-*.tmp`, ou le cache d'artefacts distribués un temporaire `.accept-*`, d'un processus mort et plus ancien que la durée de vie, et qu'une nouvelle matérialisation ou acceptation démarre
- **THEN** ce temporaire est supprimé, et aucune entrée valide du cache n'est supprimée

### Requirement: Un résidu récent, protégé ou daté dans le futur n'est jamais supprimé
Le balayage des résidus SHALL conserver tout répertoire de travail plus récent que la durée de vie, le répertoire du run courant, et tout résidu dont la date est dans le futur ; la durée de vie SHALL excéder strictement la durée maximale d'un run ou d'un clonage qui possède un tel résidu.

#### Scenario: Un run récent est conservé
- **WHEN** `local-provider-workspaces/` contient le répertoire d'un run dont la dernière modification est récente et qu'un autre run crée son répertoire de travail
- **THEN** le répertoire récent est conservé intact

#### Scenario: Date dans le futur
- **WHEN** un résidu porte une date de dernière modification postérieure à l'instant courant (saut d'horloge)
- **THEN** il n'est pas supprimé

### Requirement: Le balayage des résidus ne sort jamais de sa racine et reste non fatal
Le balayage des résidus SHALL ne supprimer que des enfants directs de la racine qui lui est donnée, sans jamais suivre un lien symbolique ni une jonction, et SHALL NOT faire échouer l'opération qui le déclenche : un résidu qui ne peut pas être supprimé est signalé par un avertissement et laissé en place. Son coût par déclenchement est borné.

#### Scenario: Un lien symbolique ancien dans la racine
- **WHEN** la racine des répertoires de travail contient un lien symbolique ou une jonction ancien pointant hors de la racine
- **THEN** seul le lien est supprimé ; la cible n'est ni parcourue ni modifiée

#### Scenario: Un résidu ne peut pas être supprimé
- **WHEN** un résidu ancien refuse la suppression (droits, verrou de fichier)
- **THEN** le nouveau répertoire de travail est créé normalement, l'opération réussit et un avertissement signale le résidu sans chemin absolu

#### Scenario: Racine volumineuse
- **WHEN** la racine contient plus de résidus anciens que la borne de travail d'un déclenchement
- **THEN** seul un nombre borné de résidus est supprimé à ce déclenchement et les autres le seront aux suivants

### Requirement: Les autorisations accordées à un conteneur sont retirées même après un arrêt brutal
Toute autorisation qu'un lanceur Windows accorde au SID d'un conteneur sur un chemin de l'hôte SHALL être journalisée dans le répertoire de récupération du run avant d'être appliquée, et SHALL être retirée par la fin normale du run ou, si le lanceur est tué, par la reprise qui prouve la mort de son propriétaire. Aucune autorisation d'un run terminé ou mort SHALL NOT subsister après cette reprise.

#### Scenario: Lanceur tué après l'octroi
- **WHEN** un lanceur accorde la lecture d'un répertoire de runtime à son conteneur puis est tué avant son nettoyage
- **THEN** la reprise suivante, qui prouve la mort du propriétaire, retire l'entrée de ce SID sur le répertoire

#### Scenario: Run vivant
- **WHEN** un autre lanceur examine un run dont le propriétaire est vivant
- **THEN** il ne retire aucune autorisation de ce run

#### Scenario: Propriété non prouvable
- **WHEN** la mort du propriétaire ne peut pas être prouvée
- **THEN** aucune autorisation n'est retirée et le diagnostic signale le run

### Requirement: Les autorisations orphelines existantes sont recensées
Un diagnostic SHALL lister les entrées d'ACL de SID d'AppContainer présentes sur les chemins que MINOS accorde et qu'aucun run vivant ne possède, sans les retirer sans demande explicite de l'utilisateur.

#### Scenario: Poste avec entrées accumulées
- **WHEN** la racine d'un runtime porte des entrées `S-1-15-2-…` sans run propriétaire vivant
- **THEN** le diagnostic les compte et nomme le chemin, et ne modifie rien sans confirmation

### Requirement: Un confinement indisponible fait échouer sa qualification quand elle est exigée
Quand la propriété `minos.sandbox.tests.required` vaut `true`, un test de qualification dont le mécanisme de confinement de la plateforme courante (bubblewrap, cgroup v2 délégué, propriété de processus forte, AppContainer) est indisponible SHALL échouer en citant les diagnostics de la découverte ; sinon il SHALL être sauté avec la même raison. Un mécanisme disponible SHALL être exercé quelle que soit la propriété.

#### Scenario: Confinement absent et qualification exigée
- **WHEN** `SandboxTestSupportTest` appelle la décision avec « exigé = vrai » et « disponible = faux »
- **THEN** elle lève un échec d'assertion dont le message contient la raison fournie

#### Scenario: Confinement absent et qualification facultative
- **WHEN** la décision est appelée avec « exigé = faux » et « disponible = faux »
- **THEN** elle abandonne le test (saut visible dans le rapport Surefire) avec la raison fournie

#### Scenario: Confinement présent
- **WHEN** la décision est appelée avec « disponible = vrai », exigé ou non
- **THEN** elle ne lève rien et le test s'exécute

#### Scenario: Racine cgroup absente
- **WHEN** la propriété vaut `true` sur un hôte Linux sans racine cgroup v2 déléguée et que `LinuxCgroupJobContainmentTest` ou `LinuxCgroupJobOwnershipIsolationTest` s'exécute
- **THEN** le test échoue en citant la variable d'environnement de la racine, et ne se saute pas

### Requirement: La CI exige la qualification du confinement sur chacun de ses deux systèmes
Chaque commande Maven `verify` de `pr-ci.yml` (Ubuntu 24.04 et Windows Server 2022) SHALL passer `-Dminos.sandbox.tests.required=true`. Dans un job de CI, la propriété SHALL être effectivement vraie dans le processus des tests.

#### Scenario: Argument absent d'une commande
- **WHEN** une des deux commandes `mvnw … verify` de `pr-ci.yml` ne porte pas `-Dminos.sandbox.tests.required=true`
- **THEN** `python scripts/docs/check-current-docs.py` se termine avec le code 1 et nomme la ligne

#### Scenario: Propriété perdue en route
- **WHEN** le job `verify` du workflow `PR Validation` s'exécute et que la propriété n'est pas transmise au processus des tests
- **THEN** `SandboxTestSupportTest.requiredModeIsActiveOnThePullRequestVerificationJob` échoue

#### Scenario: Qualification effectivement exécutée sur Windows
- **WHEN** le job `Verify (windows-2022)` termine
- **THEN** les rapports Surefire de `WindowsAppContainerWorkerSandboxBackendTest`, `WindowsJobObjectContainmentTest`, `WindowsStrongProcessOwnershipContainmentTest` et `WindowsAppContainerRecoveryOwnershipTest` ne comptent aucun test sauté, et le seul saut de confinement restant est l'exemption nommée de `WindowsNonElevatedIndexingTest`

#### Scenario: Qualification effectivement exécutée sur Linux
- **WHEN** le job `Verify (ubuntu-24.04)` termine
- **THEN** les rapports Surefire de `LinuxBubblewrapWorkerSandboxBackendTest`, `LinuxBubblewrapWorkerSandboxIsolationTest`, `LinuxCgroupJobContainmentTest`, `LinuxCgroupJobOwnershipIsolationTest` et `LinuxStrongProcessOwnershipContainmentTest` ne comptent aucun test sauté

### Requirement: Un test propre à une plateforme est compté comme sauté sur l'autre
Un test de `minos-runtime-local` qui ne s'applique qu'à une plateforme SHALL être restreint par `@EnabledOnOs` et SHALL NOT se terminer par un `return` anticipé qui le fait passer à vide. Un test qui vérifie le comportement sur les deux plateformes (par exemple l'absence de revendication de bac à sable hors Windows) SHALL rester exécuté sur chacune.

#### Scenario: Test Linux sur un poste Windows
- **WHEN** `LinuxBubblewrapWorkerSandboxBackendTest` s'exécute sous Windows
- **THEN** son rapport Surefire indique 4 tests et 4 sautés, avec la raison « Disabled on operating system »

#### Scenario: Test Windows sur Linux
- **WHEN** `WindowsAppContainerWorkerSandboxBackendTest` s'exécute sous Linux
- **THEN** 14 de ses 15 tests sont sautés et `qualificationOnlyPermitsSandboxClaimOnWindows` s'exécute et réussit

#### Scenario: Plus de passage à vide
- **WHEN** on cherche dans les tests de `minos-runtime-local` un `if` sur `currentPlatform()` ou `isWindows()` dont le corps est `return;`
- **THEN** `grep -rnE 'if \(.*(currentPlatform\(\)|isWindows).*\) *return;' minos-runtime-local/src/test` ne retourne aucune ligne

#### Scenario: Saut de plateforme distinct d'un saut de capacité
- **WHEN** `LinuxCgroupJobOwnershipIsolationTest` s'exécute sous Windows avec la propriété à `true`
- **THEN** la classe est sautée par `@EnabledOnOs(OS.LINUX)` et le job n'échoue pas

### Requirement: Les sauts d'hypothèse d'environnement des classes de confinement sont nommés
Les hypothèses d'environnement sans rapport avec la disponibilité du confinement (présence de `/dev/shm`, de `python3`, absence de `WRITE_DAC` du compte sur `%SystemRoot%`, verrou étranger non ouvrable, exemption volontaire des runners de CI élevés pour le test de non-élévation) SHALL rester des sauts par hypothèse, chacun avec un message qui énonce l'hypothèse. Aucune disponibilité de confinement SHALL être exprimée par un `assumeTrue` direct dans ces classes.

#### Scenario: Aucun saut de disponibilité direct
- **WHEN** on cherche `assumeTrue(discovered`, `assumeTrue(root` et `assumeTrue(executor` dans `minos-runtime-local/src/test`
- **THEN** la recherche ne retourne aucune ligne

#### Scenario: Exemption de non-élévation sur un runner élevé
- **WHEN** `WindowsNonElevatedIndexingTest` s'exécute sous un compte élevé avec la variable `CI` définie
- **THEN** le test est sauté avec un message qui explique l'exemption, et il échoue sous un compte élevé sans la variable `CI`
