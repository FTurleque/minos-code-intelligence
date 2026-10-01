# Suivi du chantier « résidus CLI » (Q21, Q22, Q23, Q24)

Chantier ouvert le 2026-10-01 sur `docs/audit/AUDIT-2026-09.md`. Quatre constats de sévérité basse, tous sur la
frontière entre la ligne de commande et le reste.

| ID | Constat | Né de | Lot |
|---|---|---|---|
| Q21 | une commande de lecture écrit dans `MINOS_HOME` : `project list` crée `distributed-artifacts/.leases` (et `remote-cache/`), parce que `MinosCliRunner` construit toutes les opérations pour n'importe quelle commande | chantier Code (sprint 5) | 1 |
| Q22 | une erreur d'usage ouvre encore `MINOS_HOME` (`find-symbol --bogus`), et un `--help` au-delà du troisième argument reste une option inconnue | chantier Code (sprint 5) | 1 |
| Q24 | le listage strict reste strict : une entrée de registre abîmée fait échouer `project add`, `inspect <nom>` et `listWorkspaces` en 1 ; `inspect <identifiant>` passe | chantier Fiabilité (sprint 6) | 2 |
| Q23 | le plugin IntelliJ n'accepte que le code 0 de `project list`, qui sort en 3 sur un inventaire partiel | chantier Fiabilité (sprint 6) | 3 |

## 1. Base du chantier

- **Base : `origin/develop` (a1bd556f), pas `fiab/q8-q9-tolerance`.** Les cinq PR du chantier Fiabilité (309 à 313) sont
  fusionnées dans `develop` ; le code de sortie 3 et `DegradedEntry` y sont (PR 313). Les trois PR de ce chantier
  ciblent donc `develop`.
- Les chemins cités par l'audit sont datés. Relocalisation faite au 2026-10-01 :

| Cité par l'audit | Réalité |
|---|---|
| câblage « sous `minos-app` » | `MinosCliRunner` et `MinosCli` sont dans `minos-cli` (`com.minos.cli`) ; la racine de composition est `MinosApplicationComposer`, résolue par `ServiceLoader` depuis `minos-bootstrap` (ADR 0042) |
| analyse d'options | `CliOptions` (`minos-cli`), un seul analyseur ; chaque commande déclare un `CliOptions.Spec` |
| `DegradedEntry` | `com.minos.registry.DegradedEntry`, module `minos-engine` |
| inventaire tolérant | `ProjectRegistry.inventory()` (`minos-engine`), `ProjectInspectionService.inventory()` (`minos-application`), `ProjectOperations.inventory()` |

## 2. La question qui relie Q23 et Q24 : jusqu'où la tolérance du sprint 6 doit-elle aller, et qui doit la comprendre ?

*Écrite avant le code, au 2026-10-01 ; les décisions commande par commande sont confirmées ou corrigées par les
mesures du lot 2 (§ 5).*

**Doctrine.** Une commande n'écarte une entrée illisible que si les quatre conditions tiennent :

1. **sa réponse reste vraie** pour ce qu'elle a lu (un projet affiché existe bel et bien) ;
2. **elle ne tire pas de conclusion négative d'une lecture incomplète** : « ce nom n'existe pas » est une conclusion
   négative, elle exige d'avoir tout lu ;
3. **elle dit ce qu'elle a écarté** : le nombre d'entrées illisibles est compté et affiché, jamais avalé ;
4. **l'écarter ne peut rien casser** : une mutation qui décide à partir de ce qu'elle a lu (unicité, écrasement) ne
   peut pas se permettre d'ignorer ce qu'elle n'a pas lu.

D'où une règle par famille de commandes :

| Famille | Commandes | Comportement devant une entrée abîmée |
|---|---|---|
| **Inventaire** (la réponse est un ensemble) | `project list`, `listWorkspaces` | tolérant : liste ce qui est lisible, compte et affiche les entrées dégradées, sort en **3** |
| **Résolution** (la réponse est un élément) | `inspect <nom>`, `index-status <nom>`, `nexus-export` (par racine), `findWorkspace` | tolérante **dans sa réponse, pas dans son verdict** : si l'élément est trouvé parmi les entrées lisibles, c'est un succès (condition 1) ; s'il n'est pas trouvé, la réponse n'est plus « il n'existe pas » mais « introuvable, et N entrées sont illisibles » : message distinct et sortie **3** (la réponse est incomplète), jamais un 1 qui se confond avec « absent » |
| **Mutation** | `project add`, `index`, `import-scip`, … | stricte : échoue en **1**, mais le message dit la vérité (« N entrées du registre sont illisibles, l'unicité ne peut pas être garantie ») au lieu d'une erreur générique |
| **Identifiant exact** | `inspect <identifiant>` | inchangé : lit directement l'entrée demandée, un voisin abîmé ne le concerne pas |

**Une seule convention de code.** Le code **3** signifie « résultat partiel : la sortie est valide pour ce qui a pu
être lu, mais des entrées ont été écartées et comptées », **quelle que soit la commande**. Aucun autre code ne
s'ajoute. Le contrat des codes reste : 0 succès, 1 échec d'exécution, 2 erreur d'usage, 3 résultat partiel. La
convention est écrite dans `docs/user/cli.md` (lot 2).

**La tolérance ne masque pas une panne.** Un résultat partiel suppose qu'au moins une entrée a pu être lue ou que
le registre est lisible et ne contient que des entrées dégradées sans que la lecture elle-même ait échoué. Un
`MINOS_HOME` inaccessible, un répertoire de registre illisible, un disque plein sont des **échecs (1)**, jamais des
inventaires partiels. Le cas « toutes les entrées sont abîmées » est tranché et testé au lot 2.

**Qui doit la comprendre ?** Tout consommateur du code de sortie. Qui traite « non-zéro » comme « échec » pour une
commande qui peut rendre 3 est le même constat que Q23 : le lot 3 en dresse la liste complète.

## 3. Lot 1 — rien ne s'ouvre avant d'avoir compris la commande (Q21, Q22)

### 3.1 Mesure de départ (2026-10-01, `MinosApplication.open` sur un `MINOS_HOME` vide)

- `MinosApplication.open(home)` **seul** crée le squelette du stockage local : `registry/{projects,workspaces}`,
  `symbol-snapshots`, `staged-snapshots`, `fingerprint-snapshots`, `index-state/{projects,runs,runs/.by-id/v1.ready}`,
  `semantic-index`, `runtime-observations`, `retention-locks`, `runs`.
- `MinosCliRunner.run(application, project list)` ajoute **par la construction des opérations** :
  `distributed-artifacts/.leases`, `remote-cache/{leases,locks,repositories}` (c'est `LocalRemoteIndexOperations`,
  construite pour toute commande), et `registry/.registry.lock` (verrou inter-processus du registre, pris même en lecture).
- `find-symbol --bogus` (usage, code 2) ouvre `MINOS_HOME` et laisse tout cet arbre.

Ce qui relève du câblage (Q21, Q22) : `distributed-artifacts/`, `remote-cache/`, et l'ouverture avant l'analyse.
Ce qui **n'en relève pas** : le squelette du stockage créé à l'ouverture et le fichier de verrou du registre. Le
second est le régime de verrous unique du chantier Fiabilité (une lecture du registre prend son verrou) ; voir § 6.

### 3.2 Conception retenue

- **Un seul point de construction différée : `LazyApplication`** (`minos-cli`, package-private). Il ouvre
  l'application à la première utilisation, une seule fois (synchronisé), et la ferme à la fermeture seulement si
  elle l'a ouverte. `MinosCliRunner.run(MinosApplication, …)` (application déjà ouverte par l'appelant) l'enveloppe
  sans la posséder.
- Les services que le câblage passe à `MinosCli` sont des **poignées différées** : `LazyApplication.service(contrat,
  fabrique)` rend un mandataire qui ne construit le service réel qu'au premier appel de méthode. Les commandes
  analysent donc leurs arguments (déjà sans service depuis `CliOptions`) **avant** que la moindre ouverture ou
  construction n'ait lieu, et une commande ne construit que ce qu'elle appelle.
- Les commandes adossées à une classe finale (`providers`, `runtime`, `team`, `ide <opération>`, `semantic|hybrid
  status`) reçoivent un `Supplier` par un constructeur de paquet ; leur constructeur public d'origine est conservé.
- La racine de composition reste `minos-bootstrap` (ADR 0042) ; `ServiceLoader` garde son point unique
  (`MinosApplicationComposers.resolve()`), appelé par `MinosApplication.open`. Rien n'est déplacé vers l'appelant.
- **Échec d'ouverture** : conservé tel quel pour l'utilisateur (`error: MINOS bootstrap failed: …`, code 1). L'échec
  surgit désormais depuis le corps d'une commande ; il est relayé par une exception non vérifiée dédiée
  (`LazyApplication.OpenFailure`) que les trois sites qui interceptent tout (`CliCommandSupport.run`,
  `NexusExportCommand`, `RetrievalStatusCommand`) laissent remonter au lanceur. Le relais porte l'exception d'origine,
  `IOException` **ou** non vérifiée (`dimensions must be between 32 and 16384`, par exemple) : `MinosCliRunner.run(Path, …)`
  la relève avec son type, ce que le golden `cli-semantic` impose. Une garde (`anOpenFailureIsStillReported…`) vérifie
  que chaque commande qui a besoin de l'application répond encore `MINOS bootstrap failed` en code 1.
- **Mode hébergé désactivé** : la route `team` est toujours câblée ; son fournisseur de service lève
  `MinosCli.ServiceNotConfigured` au premier appel, que la route traduit en « not configured in this CLI bootstrap »
  (code 1), comme avant. Seul l'ordre change : l'analyse précède, donc une erreur d'usage sort 2 (§ 4).
- **Simplification** : le câblage « sans état » de l'aide (`unused(...)`, mandataires qui échouent) disparaît ; l'aide
  s'appuie sur le câblage réel, désormais sans effet à la construction. Un seul chemin de câblage : `cli(LazyApplication)`.
- **`--help` à toute position** : un `--help` placé après une commande connue est servi sans état, quelle que soit sa
  position (`-h` garde ses positions actuelles : une valeur peut légitimement valoir `-h`).

### 3.3 Garde (livrable central du lot)

`LazyWiringGuardTest` énumère les sous-commandes **depuis la table de routes** de `MinosCli`
(`MinosCliRunner.statelessHelpCommands()`), jamais depuis une liste recopiée, et pour **chacune** :

1. une erreur d'usage sur un `MINOS_HOME` inexistant : code 2, **aucun fichier ni répertoire créé**, ouvreur jamais appelé ;
2. `--help` aux positions 1 à 5 : code 0, aucun fichier ni répertoire créé ;
3. les commandes de **lecture**, avec des arguments valides, sur un `MINOS_HOME` déjà initialisé : arbre identique
   avant/après, octet par octet ;
4. chaque commande de la table est **classée** (lecture ou mutation) dans la garde : une commande ajoutée sans
   classement fait échouer la suite.

### 3.4 Ce que le code a appris (2026-10-01)

- **Rouge avant correctif** : sur la base, `LazyWiringGuardTest` échoue 3 fois sur 4 (le classement passe) — les 29
  commandes de la table ouvrent `MINOS_HOME` sur une erreur d'usage, 29 commandes ouvrent `MINOS_HOME` ou refusent un
  `--help` placé au-delà de la première position, et 22 commandes de lecture le modifient (`distributed-artifacts/`,
  `remote-cache/`, …).
- **Golden `team`** (`team.golden`), seule modification d'un des 12 golden : la ligne `team tenant --format json` sur un
  home sans mode hébergé sortait 1 (« not configured ») ; or `team tenant` n'accepte pas `--format`, c'était une erreur
  d'usage masquée parce que la commande n'était pas analysée. Elle sort maintenant 2 avec `unknown team option:
  --format` et l'usage — la correction de Q22. La ligne valide `team tenant`, ajoutée au test, garde l'ancienne sortie
  exacte (« not configured », code 1). `cli-semantic` et les dix autres golden sont inchangés.
- **Gardes de source adaptées, pas affaiblies** : `ApplicationOwnershipGuardTest` (Q10) déclare `MinosCliRunner` et
  `MinosLauncher` propriétaires via une `LazyApplication` fermée par un `try`, et `LazyApplication` comme fermeur ;
  `TeamCommandDispatchGuardTest` pointe `invocation.run(service.get(), this::token)` ; `MinosCliSurfaceTest` compte
  trois méthodes de plus (`*Supplier`, visibilité paquet) ; `StableCliHelpTest` n'affirme plus qu'un `--help` au-delà du
  troisième argument n'est pas une aide (c'était le constat Q22) et affirme qu'un `-h` y reste une valeur.
- **`MinosLauncherLifecycleTest`** : `theApplicationIsClosedAfterAnUsageError` affirmait `[open, close]`, soit le défaut
  lui-même ; elle affirme maintenant `[]`.
- **Constat hors câblage, trouvé par la garde** : sous Windows, `doctor`, `providers`, `architecture`, … créent
  `sandbox/` (script AppContainer, `WindowsAppContainerWorkerSandboxBackend`) la première fois qu'une commande
  qualifie le bac à sable des workers. C'est la sonde, pas le câblage ; la garde compare donc à un home sur lequel la
  sonde a déjà tourné. Voir § 6.

## 4. Tableau « commande → codes de sortie possibles »

Établi par `verif-cli` sur la base avant le premier commit du lot 1, refait à la fin de chaque lot (voir les PR).
Aucun code ne change, sauf : **une erreur d'usage sort en 2** (Q22), y compris `team --bogus` quand le mode hébergé
est désactivé (avant : 1, « not configured », parce que la commande n'était même pas analysée).

## 5. Lot 2 — décision commande par commande

*(à écrire au lot 2, avant le code, à partir des mesures ; le principe est au § 2)*

## 6. À traiter plus tard

- **Lecture d'un `MINOS_HOME` en lecture seule.** Une commande de lecture ne réussit pas sur un `MINOS_HOME` en
  lecture seule, et ne le pourra pas par le seul câblage : `MinosApplication.open` crée le squelette du stockage, et
  toute lecture du registre prend (et crée au besoin) `registry/.registry.lock` (`InterProcessLocalProjectRegistry`).
  Y remédier suppose un mode de stockage sans écriture ou un verrou de lecture sans fichier, c'est-à-dire de rouvrir
  le régime de verrous du chantier Fiabilité (P1, Q3, Q4). Hors périmètre de ce chantier ; la garde du lot 1 mesure
  ce qui relève du câblage sur un `MINOS_HOME` déjà initialisé.
- **La sonde du bac à sable écrit dans `MINOS_HOME` (Windows).** Trouvé par `LazyWiringGuardTest` : `doctor`,
  `providers`, `architecture` et les commandes qui qualifient le runtime matérialisent `sandbox/` à la première
  utilisation. C'est un effet de la qualification elle-même (module `minos-runtime-local`, code de sécurité), pas du
  câblage ; à traiter dans un chantier de sécurité du runtime, pas ici.
- **`MinosApplication.open` réécrit l'ACL de `MINOS_HOME` (Windows).** Constaté par `verif-cli` : sur un home dont on a
  retiré l'écriture (`icacls /deny`), la première commande qui ouvre l'application supprime l'ACE de refus ; seuls
  `--help` et les erreurs d'usage, qui n'ouvrent plus rien, laissent l'ACL intacte. Un `MINOS_HOME` réellement en
  lecture seule n'est donc pas testable sous Windows. Comportement de `PrivateLocalStorage` (module `minos-storage-local`),
  non modifié par ce chantier.
