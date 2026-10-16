# Proposal

## Why

Quatre constats du sprint 6 de l'audit du 10 octobre 2026 portent sur le **même gate** (`scripts/quality/check-jacoco.py`) : il mesure bien ce qu'il regarde, mais il regarde peu.

- **AUD-TST-01** (faible, confirmé) : 28 scopes ciblés (`check-jacoco.py:13-199`), **aucun plancher global**. Le `pom.xml` racine ne configure que `prepare-agent` et `report` (`pom.xml:219-243`). Planchers les plus bas : `nexus-export` 0,30 / 0,12, `m27-team-hosted-control-plane` 0,45 / 0,25, `semantic-vector-store` 0,45 / 0,20.
- **AUD-TST-02** (moyenne, confirmé) : recalcul au HEAD, **221 fichiers de production sur 527** sont sous un scope, soit 34 344 lignes sur 71 746 (47,9 %) ; `minos-cli` 19 %, `minos-provider-scip` 26 %, `minos-engine` 37 %, `minos-storage-local` 37 %, `minos-bootstrap` 38 %, `minos-application` 43 %. Les 24 classes du cœur citées par l'audit sont toujours hors de tout scope (codec binaire des snapshots, stores d'état et d'empreinte, registre de projets, primitives d'E/S confinées, propriété de processus, rétention des runs).
- **AUD-TST-04** (moyenne, **corrigé pour le test**) : `PostgresRuntimeObservationStoreTest` existe (9 tests). Reste : aucun plancher de classe dans le scope `m30-postgresql-pgvector`, qui agrège tout le paquet.
- **AUD-TST-12** (faible, **corrigé pour le test**) : `WindowsJobObjectProcessOwnershipPlanTest` existe. Reste : la classe n'est dans aucun scope.

Conséquence : une classe hors scope peut perdre toute sa couverture sans qu'aucun contrôle de fusion ne rougisse.

## What Changes

- **Mesure d'abord** : les rapports JaCoCo d'un `verify` sur Ubuntu 24.04 et sur Windows Server 2022 fixent chaque plancher ; aucun chiffre n'est repris de l'audit.
- **Le gate apprend deux choses** : un plancher **par plateforme** pour un même scope, et une exclusion nommée de préfixes ; auto-test étendu.
- **Scope `global`** (`com/minos/`), plancher par plateforme, résultat publié dans `jacoco-gate.json`.
- **Scopes du cœur** : codec et état du stockage local ; primitives d'E/S confinées (`com/minos/io/`, 16 classes) ; rattachement de chaque classe de propriété de processus au scope de **sa** plateforme ; rétention des runs.
- **Plancher de classe** pour `PostgresRuntimeObservationStore` ; `WindowsJobObjectProcessOwnership` ajoutée à `provider-sandbox-windows`.
- **Un test pour `LeaseDeadline`**, que rien ne nomme aujourd'hui.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `controles-de-fusion` : cinq exigences ajoutées (couverture globale bornée sur chaque système ; plancher déclaré pour la plateforme courante ou échec ; classes du cœur sous un scope ciblé ; classe propre à une plateforme jugée sur sa plateforme ; plancher de classe dans un scope de paquet).

## Hors périmètre

- Ajouter le but Maven `jacoco:check` : le gate Python reste l'unique lieu de décision (un seul contrôle, une seule liste).
- Relever les planchers existants des 28 scopes, ou écrire des tests pour remonter une couverture basse : ce changement **borne**, il ne remonte pas.
- La couverture du plugin IntelliJ (AUD-TST-05, changement `mesurer-la-couverture-du-plugin-intellij`) : le plugin est hors du reactor et du rapport agrégé.
- Le seuil SonarCloud de 80 % sur le nouveau code : inchangé.
- Rendre PostgreSQL exécutable sous Windows en CI : le scope `m30-postgresql-pgvector` reste qualifié sur Linux.
- Les tests de mutation (AUD-TST-11, changement `completer-la-mutation-et-son-document`).

## Impact

- **Modules du reactor** : `minos-engine` (un test pour `LeaseDeadline`) ; aucun code de production modifié. Selon la décision D5 : tests de `minos-storage-local` et `minos-storage-postgresql`.
- **Fichiers** : `scripts/quality/check-jacoco.py` (scopes, planchers par plateforme, exclusions, auto-test) ; `docs/developer/quality-gates.md` et `docs/developer/testing.md`.
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune.
- **ADR** : aucun.
- **Gates à rejouer** : `python scripts/quality/check-jacoco.py --self-test` ; `python scripts/docs/check-current-docs.py` (`:193-197` exige quatre chaînes de `check-jacoco.py`) ; `python scripts/remediation/check-p0-p2.py` (`:338-341` exige deux planchers de classe au caractère près) ; les six `check-*-consistency.py` de `scripts/quality/` qui lisent `check-jacoco.py` (hosted-control-plane, java-ast-provider, polyglot-provider, runtime-dynamic, semantic-retrieval, vertical-decomposition) ; `python scripts/quality/check-single-execution.py` ; `python scripts/remediation/check-audit-remediation-v2.py` ; `python scripts/quality/check-ci-wiring.py`.
- **Plateformes** : Ubuntu 24.04 et Windows Server 2022 ; les planchers diffèrent par système, c'est l'objet du changement.
