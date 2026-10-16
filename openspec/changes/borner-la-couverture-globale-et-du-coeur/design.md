# Design

## Context

Sprint 6 de l'audit du 10 octobre 2026 : **AUD-TST-01**, **AUD-TST-02**, et les restes d'**AUD-TST-04** et d'**AUD-TST-12**. HEAD analysé : `949e1569` (`develop`). Prérequis déjà livrés (sprint 1) : AUD-TST-07 (auto-test du gate rejoué par `invariants`) et AUD-TST-08 (rapport d'un scope lu dans le module qui produit ses classes). `GATE` = `scripts/quality/check-jacoco.py`.

Ordre dans le sprint 6 : ce changement passe **en premier**, parce qu'il demande une mesure sur les deux systèmes dont les autres profitent ; les cinq autres changements du sprint sont indépendants de lui.

## État vérifié au HEAD

### AUD-TST-01 — confirmé

- `GATE:13-199` : 28 scopes, aucun ne couvre `com/minos/` en entier.
- `pom.xml:219-243` : `jacoco-maven-plugin` avec `prepare-agent` et `report` seulement.
- Planchers bas : `GATE:176` `nexus-export` 0,30 / 0,12 ; `GATE:144-149` `m27-team-hosted-control-plane` 0,45 / 0,25 ; `GATE:47` `semantic-vector-store` 0,45 / 0,20.
- Exécution du gate : `.github/workflows/pr-ci.yml:284` (Linux, complet) et `:299` (Windows, `--skip-scope m30-postgresql-pgvector`). Sortie : `target/m21-quality/jacoco-gate.json` (`GATE:411`).
- Un scope à `platform` est `SKIPPED` ailleurs (`GATE:437-442`) ; un scope n'a aujourd'hui qu'**un** couple `line` / `branch` (`GATE:488-489`).

### AUD-TST-02 — confirmé, décomptes recalculés

- 221 fichiers sur 527 sous scope, 34 344 lignes sur 71 746 (47,9 %).
- Chemins actuels (après le rangement en sous-packages) des classes hors scope : `minos-storage-local/src/main/java/com/minos/storage/local/store/SnapshotBinaryCodecSupport.java` (le préfixe `com/minos/storage/local/store/SnapshotCodec` de `GATE:18` ne la recouvre pas), `minos-storage-local/src/main/java/com/minos/storage/local/incremental/FileProjectFingerprintSnapshotStore.java`, `minos-storage-local/src/main/java/com/minos/storage/local/orchestration/FileIndexStateStore.java`, `minos-storage-local/src/main/java/com/minos/storage/local/registry/LocalProjectRegistry.java`, `minos-runtime-local/src/main/java/com/minos/runtime/local/sandbox/CgroupJobOwnership.java`, `minos-runtime-local/src/main/java/com/minos/runtime/local/sandbox/WindowsJobObjectProcessOwnership.java`, `minos-runtime-local/src/main/java/com/minos/runtime/local/workspace/RunDirectoryRetention.java`, `minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/install/ManagedScipProviderRuntimeManager.java`.
- `com/minos/io/` (dans `minos-engine`) contient **16** classes ; `LeaseDeadline` n'est nommée par aucun fichier de test.
- Autres gros absents relevés : `minos-engine/src/main/java/com/minos/source/ProjectIgnoreRules.java`, `minos-cli/src/main/java/com/minos/cli/command/IdeIntelligenceCommand.java`, `minos-provider-scip/src/main/java/com/minos/adapter/scip/ingestion/ScipIngestionAdapter.java`, `minos-runtime-local/src/main/java/com/minos/runtime/local/sandbox/SandboxLauncherScript.java`.

### AUD-TST-04 — corrigé pour le test, plancher absent

- `minos-storage-postgresql/src/test/java/com/minos/storage/postgresql/snapshot/PostgresRuntimeObservationStoreTest.java` : 9 tests.
- `GATE:175` : `m30-postgresql-pgvector` n'a qu'un préfixe de paquet et aucun `prefixMinimums`.
- Ces tests sont sautés sans Docker sur un poste ; en CI Linux ils sont exigés (`pr-ci.yml:275`, `-Dminos.postgresql.tests.required=true`) ; sous Windows le scope est écarté (`pr-ci.yml:299`).
- Aucune suite de contrat commune avec `FileRuntimeObservationStoreTest`.

### AUD-TST-12 — corrigé pour le test, scope absent

- `minos-runtime-local/src/test/java/com/minos/runtime/local/sandbox/WindowsJobObjectProcessOwnershipPlanTest.java:37` : `@EnabledOnOs(OS.WINDOWS)`.
- `GATE:131-139` : `provider-sandbox-windows` ne liste que `WindowsAppContainerWorkerSandboxBackend` et `WindowsContainmentScript`.

**Non mesuré** : aucun build Maven n'a été exécuté pendant l'analyse. Les couvertures de l'audit (83,0 % des lignes, 66,4 % des branches, poste Windows du 10 octobre) n'ont pas été rejouées et ne valent pas pour Linux. **Non vérifié** : la configuration SonarCloud côté serveur.

### Écarts avec l'action proposée par l'audit

1. **Un plancher `global` unique est inapplicable sur les deux systèmes.** Sous Windows, les classes Linux (bubblewrap, cgroup) sont à 0 et PostgreSQL est sauté ; sous Linux, c'est l'inverse pour AppContainer et Job Object. Un même chiffre serait soit trop bas pour protéger, soit rouge sur un système. Le gate ne sait porter qu'un couple par scope (`GATE:488-489`) : il faut l'étendre (D1).
2. **Le rapport agrégé ne porte pas les tests de `minos-app`** (`GATE:155-156` : 0 ligne couverte sur 10). Un scope `com/minos/` lu dans l'agrégat compterait `com/minos/app/` à zéro (D2).
3. **Un scope `process-ownership` à un seul `platform` est inapplicable** : `CgroupJobOwnership` est Linux, `WindowsJobObjectProcessOwnership` Windows, `RunDirectoryRetention` commune. Trois rattachements (D4).
4. **`com/minos/io/` contient 16 classes, pas 4**, et `LeaseDeadline` n'a aucun test : un scope de paquet sans test préalable fixerait un plancher sur du hasard.
5. **« Mesure CI moins 2 points » suppose une mesure par système**, qui n'existe pas dans le dépôt : la première section des tâches la produit.
6. **Un plancher de classe exige que le préfixe soit déclaré** dans `prefixes` (`GATE:495-497`, « undeclared prefix ») : pour `m30-postgresql-pgvector`, le préfixe de classe s'ajoute à côté du préfixe de paquet (sans double compte, `GATE:461`).
7. **Des gates affirment des chaînes de `check-jacoco.py`** : `scripts/docs/check-current-docs.py:193-197` (« through M30 » et trois noms de scope), `scripts/remediation/check-p0-p2.py:338-341` (deux planchers de classe au caractère près), six `check-*-consistency.py`. L'audit n'en dit rien.

## Mesures des tâches 1.1 et 1.2 (2026-10-15)

Rapports agrégés (artefacts `pr-validation-<OS>-1`) et rapports de module (`jacoco-modules-<OS>`) des runs `pr-ci.yml` **38719668720** (`develop` `83dd1fbb`) et **38697957273** (`develop` `ae7b02b0`), lecture seule ; les deux runs concordent à 0,001 près.

| Mesure | Linux (ligne / branche) | Windows (ligne / branche) |
|---|---|---|
| `com/minos/` | 26 999 / 31 406 (0,860) ; 10 070 / 14 156 (0,711) | 26 031 / 31 406 (0,829) ; 9 825 / 14 156 (0,694) |
| `com/minos/` sans `com/minos/app/` | 0,865 ; 0,716 | 0,834 ; 0,698 |
| `storage-codec-and-state` (4 classes, 17 avec les internes) | 0,912 ; 0,758 | 0,912 ; 0,758 |
| `com/minos/io/` (34 classes avec les internes) | 0,801 ; 0,701 | 0,814 ; 0,717 |
| `LeaseDeadline` (sans test propre) | 26 / 32 (0,812) ; 13 / 16 (0,812) | 0,812 ; 12 / 16 (0,750) |
| `RunDirectoryRetention` | 0,913 ; 0,843 | 0,898 ; 0,843 |
| `CgroupJobOwnership` | 175 / 180 (0,972) ; 89 / 96 (0,927) | (0,950 ; 0,906, classe Linux) |
| `ManagedScipProviderRuntimeManager` | 0,711 ; 0,572 | 0,668 ; 0,548 |
| `ProjectIgnoreRules` | 0,896 ; 0,800 | idem |
| `IdeIntelligenceCommand` | 0,590 ; 0,778 | idem |
| `ScipIngestionAdapter` | 0,943 ; 0,765 | idem |
| `SandboxLauncherScript` | 0,074 ; 0,100 | 0,839 ; 0,743 (code Windows) |

Appariement rejoué au HEAD de la branche : **223 fichiers sur 531** sous un scope ciblé (48,1 % des lignes) sur `origin/develop` (l'analyse du 13 octobre comptait 221 sur 527) ; **246 sur 531** (58,6 %) avec les scopes de ce changement, `global` couvrant le reste.

## Mesures pour AUD-TST-04 et AUD-TST-12

Relevé du 2026-10-14 dans les rapports JaCoCo de `pr-ci.yml` (lecture seule) : run `38470947827` (`develop` `90c24fd0`), confirmé à l'identique pour ces deux classes par le run `38466461171` (`develop` `bf57169c`). Rapport agrégé (artefact `pr-validation-<OS>-1`) et rapport de module (`jacoco-modules-<OS>`) concordent. Le gate compte par préfixe : la classe et ses classes internes.

| Classe (préfixe) | Système | Classes | Lignes | Branches | Plancher de classe |
|---|---|---|---|---|---|
| `WindowsJobObjectProcessOwnership` (+ `$1`) | Windows | 2 | 67 / 68 (0,985) | 15 / 20 (0,750) | 0,93 / 0,70 |
| `WindowsJobObjectProcessOwnership` (+ `$1`) | Linux | 2 | 2 / 68 (0,029) | 0 / 20 (0,000) | sans objet (`provider-sandbox-windows` `SKIPPED`) |
| `PostgresRuntimeObservationStore` | Linux | 1 | 65 / 65 (1,000) | 18 / 20 (0,900) | 0,95 / 0,85 |
| `PostgresRuntimeObservationStore` | Windows | 1 | 0 / 65 | 0 / 20 | sans objet (`--skip-scope m30-postgresql-pgvector`) |

- Planchers de classe = mesure moins environ 5 points, arrondie au centième inférieur ; ils ne préjugent pas de D3, qui porte sur le plancher `global`.
- `provider-sandbox-windows` passe sous Windows de 317 / 346 lignes et 118 / 148 branches (3 classes) à 384 / 414 et 133 / 168 (5 classes), au-dessus de ses planchers inchangés 0,55 / 0,35. `m30-postgresql-pgvector` reste à 42 classes (le préfixe de classe ne compte pas deux fois) : 1 537 / 1 658 lignes, 519 / 619 branches sous Linux.
- **Vérifié sur rapport CI seulement** : le gate a été rejoué sur un poste Windows contre les rapports téléchargés des deux runs, avec les options de la CI (Linux : sans option ; Windows : `--skip-scope m30-postgresql-pgvector`). Pour le rapport Linux, `sys.platform` a été fixé à `linux` avant de charger le gate. Aucun build Maven n'a été exécuté ; la preuve sur les runners reste la tâche 7.1.

## Goals / Non-Goals

**Goals** : la couverture globale est bornée sur chaque système de qualification ; les classes du cœur nommées sont sous un scope ; aucune classe propre à une plateforme n'est jugée là où elle ne peut pas s'exécuter ; chaque plancher vient d'une mesure datée.

**Non-Goals** : remonter une couverture ; ajouter `jacoco:check` ; toucher au seuil SonarCloud ; mesurer le plugin IntelliJ.

## Decisions

### D1. Plancher par plateforme, dans le gate

Un scope peut déclarer `line` et `branch` **par plateforme** (`linux`, `windows`). Sans plancher pour la plateforme courante, le scope **échoue** (il n'est pas sauté) : seul `platform` ou `--skip-scope` produit `SKIPPED`, comme aujourd'hui. Les 28 scopes existants gardent leur forme. Alternative écartée : deux scopes `global-linux` / `global-windows`, qui dupliquent la liste des préfixes et dérivent.

### D2. `global` lit le rapport agrégé et exclut nommément `com/minos/app/`

Le scope porte une liste d'exclusions (préfixes) ; chaque exclusion est nominative et affichée dans la sortie et dans `jacoco-gate.json`. `com/minos/app/` reste jugé par `m29-backend-routing` (`report_of: minos-app`). Une exclusion qui ne désigne plus aucune classe échoue, comme un préfixe mort.

### D3. Valeur des planchers et cliquet — **recommandation retenue par le propriétaire le 2026-10-15** (moins 2 points par système ; aucun scope ciblé pour les quatre autres absents, couverts par `global` : le plus faible, `IdeIntelligenceCommand`, est à 0,590 en lignes)

Recommandation : plancher = mesure de la tâche 1.1 **moins 2 points**, par système, pour les lignes et pour les branches ; relevé à chaque clôture de sprint ; jamais abaissé sans justification écrite dans `SUIVI.md`. Alternatives : moins 1 point (plus de faux rouges sur une PR qui ajoute du code peu couvert) ; moins 5 points (protège moins). La mesure, la date et le run sont consignés à côté du plancher.

### D4. Scopes du cœur

- `storage-codec-and-state` (commun) : `SnapshotBinaryCodecSupport`, `FileIndexStateStore`, `FileProjectFingerprintSnapshotStore`, `LocalProjectRegistry`.
- `confined-io-primitives` (commun) : `com/minos/io/`, avec un plancher de classe pour `LeaseDeadline` une fois son test écrit.
- `CgroupJobOwnership` rejoint `provider-sandbox-linux` ; `WindowsJobObjectProcessOwnership` rejoint `provider-sandbox-windows` ; chacune avec un plancher de classe, pour ne pas se cacher derrière ses voisines ni faire baisser le scope sans le dire.
- `RunDirectoryRetention` : scope commun `run-directory-retention`.
- `ManagedScipProviderRuntimeManager` : rejoint `m24-polyglot-provider-platform` avec un plancher de classe ; ce scope est rouge sur poste Windows hors régression, à comparer à `develop`.
- Les quatre autres gros absents (`ProjectIgnoreRules`, `IdeIntelligenceCommand`, `ScipIngestionAdapter`, `SandboxLauncherScript`) sont couverts par `global` ; un scope ciblé pour eux se décide sur la mesure (tâche 1.3), pas d'avance.

### D5. Suite de contrat commune des stores d'observation — **recommandation retenue le 2026-10-15 : pas de suite dans ce changement**

Recommandation : **ne pas** l'écrire dans ce changement. Les deux tests existent dans deux modules ; une suite commune demande un support de test partagé (un `test-jar`, `minos-engine/pom.xml:27-33` en publie déjà un) et dépasse l'objet « borner ». Le plancher de classe (écart 6) ferme le risque signalé par l'audit. Alternative : classe abstraite de contrat dans le `test-jar` de `minos-engine`, héritée par les deux modules.

### D6. Les littéraux des autres gates sont conservés

« through M30 », les noms des scopes `m29-backend-routing`, `m30-storage-backend-selection`, `m30-postgresql-pgvector`, et les deux lignes de plancher exigées par `check-p0-p2.py:338-341` restent **au caractère près**. Le message de succès de l'auto-test change de décompte (`GATE:404`) : aucun gate ne l'affirme.

## Direction des dépendances

Aucune arête nouvelle : un gate Python et des tests dans le module qui porte la classe (`LeaseDeadline` dans `minos-engine`). Si D5 retient l'alternative, la dépendance de test vers le `test-jar` de `minos-engine` existe déjà pour `minos-storage-local`.

## Qualification des capacités

Aucune capacité produit. Qualification du contrôle lui-même : plancher global **qualifié** sur Linux et sur Windows une fois mesuré ; `m30-postgresql-pgvector` **partiel** (jugé sur Linux seulement, déclaré `SKIPPED` sous Windows).

## Windows et Linux

Les planchers diffèrent par système et sont nommés par système. `provider-sandbox-linux` reste `SKIPPED` sous Windows et inversement. Le test de `LeaseDeadline` est commun.

## Risks / Trade-offs

- Un plancher global trop proche de la mesure rend `Verify` rouge sur une PR qui ajoute du code peu couvert : c'est l'effet voulu, borné par la marge (D3).
- Ajouter une classe à un scope existant déplace son ratio : la mesure précède, et le plancher de classe isole l'ajout.
- La couverture d'un `verify` varie de quelques dixièmes d'un run à l'autre (tests conditionnels) : relever deux runs par système avant de fixer.
- Le plancher global ne remplace pas les scopes ciblés : une classe peut encore s'éroder à l'intérieur de la marge.
