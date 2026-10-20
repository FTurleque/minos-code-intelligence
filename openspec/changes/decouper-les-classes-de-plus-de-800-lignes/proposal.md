# Proposal

## Why

Le superviseur de code (hook de fin de tour) relève deux fichiers de production de plus de 800 lignes :

- `minos-engine/src/main/java/com/minos/io/PrivateLocalStorage.java` — **1 031 lignes** ;
- `minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/install/ManagedScipProviderRuntimeManager.java` — **1 019 lignes**.

Le dépôt ne fixe aucune limite de longueur de fichier (ni `.editorconfig` ni Checkstyle) ; le seuil de 800 lignes est celui du superviseur, pas une règle du dépôt. Le constat est pourtant fondé : chacune des deux classes porte, à côté de sa responsabilité propre, des blocs autonomes qui ont leurs propres tests, leurs propres entrées dans les gates et un autre rythme de changement. Le diagnostic tient dans la **relecture** (voir `design.md`), pas dans le seuil.

- `PrivateLocalStorage` : la politique « propriétaire seul » (création, durcissement, vérification POSIX et ACL) cohabite avec le lancement d'`icacls` (`WindowsDacl`), le diagnostic d'un refus d'écriture, la sonde du propriétaire du processus et le crochet d'essai `CapabilityProbe`.
- `ManagedScipProviderRuntimeManager` : l'inspection et l'installation de deux providers cohabitent avec l'extraction bornée d'archives, la vérification et l'installation du classpath de scip-java, et les gestes élémentaires d'installation (lancer une commande, publier, écrire, supprimer).

## What Changes

Remaniement **à comportement constant**, par extraction de classes de premier niveau **dans le même package et le même module** (ADR 0044 : un package appartient à un seul module ; aucune classe ne change de module) :

- `PrivateLocalStorage` cède `WindowsDacl`, un diagnostic de refus d'écriture, la sonde du propriétaire et l'interface de test `CapabilityProbe` à des classes de `com.minos.io` (minos-engine). La garde des emplacements partagés (H23, 20 lignes) reste. Ses méthodes publiques gardent leur signature.
- `ManagedScipProviderRuntimeManager` cède l'extraction bornée d'archives, l'installation du classpath de scip-java et ses gestes d'installation à des classes de `com.minos.adapter.scip.runtime.install` (minos-provider-scip). `list`, `inspect`, `install`, `executor` et les constantes publiques restent sur le manager.
- Chaque entrée de `scripts/architecture/private-io-allowlist.json` dont l'appel brut change de fichier **suit l'appel** : même total, même maximum, nouvelle clé de fichier.
- Les gates littéraux qui lisent ces deux fichiers sont repointés ; les planchers JaCoCo des classes issues du découpage sont fixés sur une mesure, sans abaisser la couverture agrégée des classes d'origine.
- Les tests suivent le code déplacé dans le même module ; le bloc `WindowsDacl`, qui ne s'exécutait que sous Windows, reçoit une couture de test pour être couvert aussi sous Linux.
- `CapabilityProbe`, interface de test imbriquée dans `PrivateLocalStorage`, devient un type de premier niveau de `com.minos.io` : seuls des fichiers de test, dont un de `minos-bootstrap`, changent de nom de type (`design.md` D4, Q3).

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

(aucune : pur remaniement à comportement constant. Aucune exigence n'est ajoutée, modifiée ni retirée ; `skip_specs: true` est posé dans `.openspec.yaml`, comme pour `decouper-les-methodes-d-analyse-et-d-ingestion`, et le changement n'a pas de dossier `specs/`. Les exigences de `openspec/specs/stockage-prive-et-secrets`, `openspec/specs/installation-des-providers` et `openspec/specs/frontieres-de-modules` restent la référence et doivent rester satisfaites ; la preuve est la tâche de chaque section, pas une exigence nouvelle.)

## Hors périmètre

- Tout seuil de taille de fichier ou de méthode dans un gate, un Checkstyle ou SonarCloud : l'audit du 10 octobre 2026 exclut les seuils bloquants (AUD-QUA-07), et ajouter un gate de taille est une décision séparée (voir `design.md`, décision Q1 : non).
- L'extraction de l'acquisition épinglée de Coursier, Maven et Node (~125 lignes), couverte à 11 % : elle commence par des tests et fait l'objet d'un changement ultérieur (`design.md` D7). L'extraction de l'amorçage depuis la charge utile embarquée (~190 lignes, couverte à 81 %) est une option soumise au propriétaire (`design.md`, décision Q2 : dans un changement suivant).
- `ManagedPolyglotScipRuntimeManager` (564 lignes) et `ManagedScipPythonRuntimeManager` (286 lignes), qui dupliquent `run` et `moveDirectory` : aucune unification ici.
- Toute modification de comportement, de message d'erreur, d'ordre d'effets, de format de fichier ou de surface publique.
- La résolution du conflit de fusion entre les deux branches `refactor/*` du superviseur et `develop` : elle est une condition préalable (voir ci-dessous), pas un livrable.

## Condition préalable

Les branches `refactor/complexite-points-superviseur` (`c4531427`) et `refactor/duplications-points-superviseur` (`ae8ff500`, empilée sur la première) **modifient les deux mêmes fichiers**. Elles ne sont pas fusionnées dans `develop` (`f0f6e535`, PR #510) au moment de la rédaction. Les numéros de ligne de ce changement sont ceux de la pile obtenue en les fusionnant sur `develop` ; la fusion entre en conflit dans `ManagedScipProviderRuntimeManager.acquireAndExtractZip` (la migration #510 a introduit `moveFile` et `extractDistribution`, la factorisation a introduit `acquireAndExtractZip`). La tâche 1.1 relit les deux fichiers sur le `develop` réel avant tout code. Aucune implémentation ne commence avant que ces deux branches soient dans `develop`.

## Impact

- **Modules du reactor** : `minos-engine` (`com/minos/io`), `minos-provider-scip` (`adapter/scip/runtime/install`). Aucune dépendance Maven ajoutée, aucun package nouveau, aucun cycle (ADR 0022, ADR 0044, règle A8).
- **Surfaces publiques** : aucune (CLI, API Java, MCP, IntelliJ, NEXUS inchangés). Toutes les méthodes publiques de `PrivateLocalStorage` gardent leur signature, à une exception près : `CapabilityProbe` devient `com.minos.io.CapabilityProbe` et `useForTesting` en prend le nouveau type. C'est une interface publique de `minos-engine` réservée aux tests (`useForTesting` refuse de s'exécuter hors d'un runtime JUnit) ; quatre fichiers de test, dont `MinosApplicationTest` de `minos-bootstrap`, changent de nom de type ; `ManagedScipProviderRuntimeManager` garde son constructeur et ses méthodes publiques.
- **ADR** : aucun nouvel ADR ni amendement. ADR 0044 (propriété des packages) et ADR 0045 (constructeur unique) sont respectés ; ADR 0061 (niveau partiel de `openNewTreeFile`) concerne du code qui ne bouge pas.
- **Gates à rejouer** : `scripts/architecture/check-private-io.py` et son auto-test (cliquet, entrées déplacées) ; `scripts/quality/check-jacoco.py` (scopes `confined-io-primitives`, `global`, `m24-polyglot-provider-platform`) ; `scripts/remediation/check-post-mne.py`, `check-mnd.py`, `check-p0-p2.py`, `scripts/quality/check-tools-manifest.py` ; `check-module-boundaries.py` ; baselines SpotBugs de `minos-engine` et `minos-provider-scip` (`quality/spotbugs-baseline/`).
- **Plateformes** : Windows et Linux ; le bloc `WindowsDacl` ne s'exécute qu'avec Windows, d'où la couture de test de la tâche 2.1.
