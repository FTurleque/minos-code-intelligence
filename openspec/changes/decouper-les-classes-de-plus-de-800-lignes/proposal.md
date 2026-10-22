# Proposal

## Why

Le superviseur de code (hook de fin de tour) relève deux fichiers de production de plus de 800 lignes ; au HEAD `5ce7b79b` :

- `minos-engine/src/main/java/com/minos/io/PrivateLocalStorage.java` — **1 031 lignes** ;
- `minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/install/ManagedScipProviderRuntimeManager.java` — **1 004 lignes**.

Le dépôt ne fixe aucune limite de longueur de fichier (ni `.editorconfig` ni Checkstyle) et l'audit du 10 octobre 2026 écarte tout seuil bloquant (AUD-QUA-07, qui constate qu'aucun fichier ne dépasse 1 040 lignes) ; le seuil de 800 lignes est celui du superviseur. Le constat est pourtant fondé : chacune des deux classes porte, à côté de sa responsabilité propre, des blocs autonomes qui ont leurs propres tests, leurs propres entrées dans les gates et un autre rythme de changement. Le diagnostic tient dans la **relecture** (voir `design.md`), pas dans le seuil.

- `PrivateLocalStorage` : la politique « propriétaire seul » (création, durcissement, vérification POSIX et ACL) cohabite avec le lancement d'`icacls` (`WindowsDacl`), le diagnostic d'un refus d'écriture, la sonde du propriétaire du processus et le crochet d'essai `CapabilityProbe`.
- `ManagedScipProviderRuntimeManager` : l'inspection et l'installation de deux providers cohabitent avec l'extraction bornée d'archives, la vérification et l'installation du classpath de scip-java, et les gestes élémentaires d'installation (lancer une commande, publier, écrire, supprimer).

Quatre autres fichiers de production dépassent 800 lignes au HEAD (`design.md`, « Classes de production de plus de 800 lignes ») ; ils restent hors de ce changement (décision Q5, en attente).

## What Changes

Remaniement **à comportement constant**, par extraction de classes de premier niveau **dans le même package et le même module** (ADR 0044 : un package appartient à un seul module ; ADR 0062 : aucune racine de package nouvelle ; aucune classe ne change de module) :

- `PrivateLocalStorage` cède `WindowsDacl`, un diagnostic de refus d'écriture, la sonde du propriétaire et l'interface de test `CapabilityProbe` à des classes de `com.minos.io` (minos-engine). La garde des emplacements partagés (H23, 20 lignes) reste. Ses méthodes publiques gardent leur signature.
- `ManagedScipProviderRuntimeManager` cède l'extraction bornée d'archives, l'installation du classpath de scip-java et ses gestes d'installation à des classes de `com.minos.adapter.scip.runtime.install` (minos-provider-scip). `list`, `inspect`, `install`, `executor` et les constantes publiques restent sur le manager.
- Chaque entrée de `scripts/architecture/private-io-allowlist.json` dont l'appel brut change de fichier **suit l'appel** : même somme des maximums (56), deux entrées de plus (25), jamais un appel brut de plus.
- L'unique entrée de la règle A12 (`PROCESS_LAUNCHERS` de `scripts/architecture/check-module-boundaries.py`) devient `minos-engine/io.WindowsDacl`, la classe qui porte alors le `ProcessBuilder` d'`icacls` (décision Q4, en attente).
- Les gates littéraux qui lisent ces deux fichiers sont repointés ; les planchers JaCoCo des classes issues du découpage sont fixés sur une mesure, sans abaisser la couverture agrégée des classes d'origine.
- Les tests suivent le code déplacé dans le même module ; `WindowsDacl.protect`, qui ne s'exécute que sous Windows, reçoit une couture de test pour être couverte aussi sous Linux.
- `CapabilityProbe`, interface de test imbriquée dans `PrivateLocalStorage`, devient un type de premier niveau de `com.minos.io` : seuls des fichiers de test, dont un de `minos-bootstrap`, changent de nom de type (`design.md` D4, Q3).

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

(aucune : pur remaniement à comportement constant. Aucune exigence n'est ajoutée, modifiée ni retirée ; `skip_specs: true` est posé dans `.openspec.yaml`, comme pour `decouper-les-methodes-d-analyse-et-d-ingestion`, et le changement n'a pas de dossier `specs/`. Les exigences de `openspec/specs/stockage-prive-et-secrets`, `openspec/specs/installation-des-providers`, `openspec/specs/frontieres-de-modules` et `openspec/specs/audit-qualite-code` restent la référence et doivent rester satisfaites (liste dans `design.md`, « Recouvrements ») ; la preuve est la tâche de chaque section, pas une exigence nouvelle.)

## Hors périmètre

- Tout seuil de taille de fichier ou de méthode dans un gate, un Checkstyle ou SonarCloud : l'audit exclut les seuils bloquants (AUD-QUA-07), et ajouter un gate de taille est une décision séparée (`design.md`, décision Q1 : non).
- Les quatre autres fichiers de production de plus de 800 lignes : `SnapshotBinaryCodecSupport` (1 119), `IndexingRunExecutor` (984), `WindowsAppContainerWorkerSandboxBackend` (816), `LinuxCgroupJob` (813) (`design.md`, décision Q5).
- Le port d'E/S de plateforme qui sortira le mécanisme d'ACL du moteur (ADR 0062, Décision § 1, conçu avec AUD-SEC-06) : `WindowsDacl` reste dans `com.minos.io`.
- L'extraction de l'acquisition épinglée de Coursier, Maven et Node (~110 lignes), couverte à 11 %, et de l'amorçage depuis la charge utile embarquée (191 lignes) : un changement ultérieur qui commence par des tests (`design.md` D7, décision Q2).
- `ManagedPolyglotScipRuntimeManager` (564 lignes) et `ManagedScipPythonRuntimeManager` (286 lignes), qui dupliquent `run` et `moveDirectory` : aucune unification ici.
- Toute modification de comportement, de message d'erreur, d'ordre d'effets, de format de fichier ou de surface publique.

## Impact

- **Modules du reactor** : `minos-engine` (`com/minos/io`), `minos-provider-scip` (`adapter/scip/runtime/install`) ; tests de `minos-bootstrap` (un nom de type). Aucune dépendance Maven ajoutée, aucun package nouveau, aucun cycle (ADR 0022, ADR 0044, ADR 0062, règles A3, A8, A14).
- **Surfaces publiques** : aucune (CLI, API Java, MCP, IntelliJ, NEXUS inchangés). Toutes les méthodes publiques de `PrivateLocalStorage` gardent leur signature, à une exception près : `CapabilityProbe` devient `com.minos.io.CapabilityProbe` et `useForTesting` en prend le nouveau type. C'est une interface publique de `minos-engine` réservée aux tests (`useForTesting` refuse de s'exécuter hors d'un runtime JUnit), que la règle A15 accepte parce que `PrivateLocalStorage` la nomme ; quatre fichiers de test, dont `MinosApplicationTest` de `minos-bootstrap`, changent de nom de type ; `ManagedScipProviderRuntimeManager` garde son constructeur et ses méthodes publiques.
- **ADR** : aucun nouvel ADR. Si Q4 est acceptée, une note de mise en œuvre datée est ajoutée à l'ADR 0062 (nom du lanceur `icacls`), sans changer sa décision. ADR 0044 (propriété des packages), ADR 0045 (constructeur unique) et ADR 0061 (niveau partiel de `openNewTreeFile`, code qui ne bouge pas) sont respectés.
- **Gates à rejouer** : `scripts/architecture/check-private-io.py` et `test_check_private_io.py` (entrées déplacées) ; `scripts/architecture/check-module-boundaries.py` et `test_check_module_boundaries.py` (A12 ; A3, A8, A10, A11, A13, A14, A15 inchangés) ; `scripts/quality/check-jacoco.py` (scopes `confined-io-primitives`, `global`, `m24-polyglot-provider-platform`) et son `--self-test` ; `scripts/remediation/check-post-mne.py`, `check-mnd.py`, `check-p0-p2.py` ; `scripts/quality/check-tools-manifest.py` et `test_check_tools_manifest.py` ; baselines SpotBugs de `minos-engine` et `minos-provider-scip` (`quality/spotbugs-baseline/`) ; SonarCloud (80 % du nouveau code, par module).
- **Plateformes** : Windows et Linux ; `WindowsDacl` ne s'exécute qu'avec Windows, d'où la couture de test de la tâche 2.1.
