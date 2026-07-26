# ADR-0022 — Imposer les frontières MINOS par un reactor Maven progressif

Date : 26 juillet 2026

Statut : **Accepted**

Origine : M15-S2

## Contexte

À la fin de M14, MINOS est livré comme un unique projet Maven `com.minos:minos-code-intelligence` contenant dans le même artefact le domaine normalisé, le moteur de requête, la persistance locale, les adapters SCIP/Git, la CLI, l'API Java, le MCP et l'intégration NEXUS.

Les frontières existent principalement par packages et conventions. Elles ne sont donc pas suffisamment protégées par la compilation : une dépendance interdite peut être introduite sans que Maven impose la direction d'architecture voulue.

M15 doit rendre ces frontières structurelles sans effectuer un big-bang qui mélangerait simultanément déplacement de sources, changement de packaging, composition applicative et évolution fonctionnelle.

## Décision

Le dépôt devient un **reactor Maven multi-module** piloté par un parent racine `com.minos:minos-parent`.

La cible de travail est :

```text
minos-parent
├── minos-domain
├── minos-engine
├── minos-runtime-local
├── minos-storage-local
├── minos-provider-scip
├── minos-integration-git
├── minos-api
├── minos-cli
├── minos-mcp
├── minos-nexus
└── minos-app
```

Le nombre final de modules peut être réduit ou ajusté si une frontière ne protège aucun invariant réel. Il est interdit de créer un module uniquement pour reproduire mécaniquement un package existant.

`minos-runtime-local` est un ajustement explicite de la cible initiale : il protège l'invariant « exécution locale générique des providers ≠ provider SCIP ». Sans cette frontière, la CLI `doctor` devrait dépendre du provider SCIP uniquement pour utiliser `CommandLocator`, ou le moteur devrait absorber de l'infrastructure de processus locale.

### Direction des dépendances

```text
minos-domain
    ↑
minos-engine
    ↑
minos-runtime-local / minos-storage-local
    ↑
minos-provider-scip / minos-integration-git
    ↑
minos-app (composition)
    ↑
CLI / API / MCP / NEXUS
```

Les dépendances suivantes doivent devenir impossibles ou explicitement détectées :

- domaine → SCIP ;
- domaine → MCP SDK ;
- moteur → CLI ;
- moteur → protocole MCP ;
- moteur → runtime process local concret ;
- runtime local générique → provider SCIP ;
- architecture/impact → couches d'exposition ;
- API publique → backend local concret lorsqu'un port stable peut être utilisé.

### Frontières ratifiées pendant S2

Le premier checkpoint qualifié ratifie `minos-domain` comme artefact sans dépendance externe : il possède `com.minos.domain` et `minos-app` ne compile plus ces classes directement.

Le checkpoint suivant ratifie `minos-engine` autour du noyau de requête provider/backend-independent :

```text
minos-engine
├── com.minos.query.*
└── com.minos.store.CodeKnowledgeStore   # port moteur, pas implémentation locale
```

`CodeKnowledgeStore` appartient à la frontière moteur car son contrat est défini par les besoins MINOS et ne reflète aucun backend particulier. Les implémentations mémoire/fichier restent hors de `minos-engine` et dépendent de ce port.

Le checkpoint suivant, qualifié localement sur `cbbc0b5f6b7a59a627cfa6af98b24107f3435edb`, fixe deux frontières d'infrastructure supplémentaires :

```text
minos-storage-local
└── com.minos.store.*                    # hors CodeKnowledgeStore

minos-integration-git
└── com.minos.git.*
```

`minos-storage-local` dépend de `minos-engine`, jamais l'inverse. `minos-integration-git` est le seul module qui déclare directement `org.eclipse.jgit`; `minos-app` ne porte plus JGit lui-même.

Le checkpoint SCIP positionne ensuite les contrats provider-neutres nécessaires à l'exécution dans `minos-engine` :

```text
minos-engine
├── com.minos.discovery.ProjectDiscovery
├── com.minos.orchestration.IndexerCapability
├── com.minos.orchestration.IndexerQualification
├── com.minos.orchestration.IndexerDescriptor
├── com.minos.orchestration.IndexingRequirements
├── com.minos.orchestration.IndexerNegotiationResult
├── com.minos.orchestration.IndexerRegistry
├── com.minos.orchestration.IndexingMode
└── com.minos.orchestration.IndexingRuntimePorts
```

Ces types sont des contrats et règles provider-indépendants, sans dépendance SCIP/MCP/JGit.

`minos-runtime-local` possède l'infrastructure générique d'exécution locale :

```text
com.minos.runtime.CommandLocator
com.minos.runtime.IndexerProcessPlan
com.minos.runtime.IndexerProcessPlanFactory
com.minos.runtime.ProcessIndexerExecutor
com.minos.runtime.ProviderRuntimeManager
com.minos.runtime.ProviderRuntimeStatus
```

Il dépend uniquement de `minos-engine`. `MinosVersion` reste dans l'application pendant ce checkpoint car il représente la version observable de l'artefact produit et non un runtime de provider.

`minos-provider-scip` possède enfin `com.minos.adapter.scip.*`, déclare directement `scip-java-bindings` et embarque les ressources qualifiées Windows `scip-java-windows-runner.ps1` et `ScipWriter.java`. Le provider dépend des contrats engine, du runtime local et du stockage local, mais aucune de ces couches ne dépend de SCIP.

## Migration séquentielle

S2 suit une migration en plusieurs commits qualifiables :

1. transformer la racine en parent/reactor ;
2. créer `minos-app` en conservant temporairement les sources historiques à leur emplacement actuel ;
3. extraire `minos-domain` ;
4. extraire les contrats et services du moteur ;
5. extraire persistance, runtime local, provider SCIP et intégration Git ;
6. extraire API/CLI/MCP/NEXUS ;
7. réduire `minos-app` au bootstrap/composition ;
8. relocaliser physiquement les sources dans leurs modules et supprimer toute compatibilité transitoire de source layout ;
9. qualifier le reactor complet et les distributions M14.

Les checkpoints 3 à 5 utilisent volontairement une phase d'**ownership Maven avant relocation physique** : les sources restent temporairement dans l'arbre historique, mais chaque module cible les compile explicitement et `minos-app` les exclut. Les runners S2 inspectent ensuite les JARs, les ressources et `target/classes` pour prouver que l'ownership est réellement imposé par Maven.

Cette configuration est transitoire et doit disparaître avant la fermeture de M15-S2. La relocation physique devient alors une opération mécanique effectuée après stabilisation des frontières, plutôt qu'un mélange de décisions architecturales et de mouvements de fichiers.

## Coordonnée et packaging utilisateur

L'artefact exécutable historique reste :

```text
com.minos:minos-code-intelligence:<version>
```

Il est désormais produit par `minos-app`.

Pendant la migration, son répertoire de build reste `target/` à la racine afin de conserver les contrats des scripts M14 : shaded JAR, JPackage, ZIP Windows, installateur et smoke MCP.

La racine utilise une version Maven CI-friendly :

```xml
<version>${revision}</version>
```

avec `revision=0.2.0-SNAPSHOT` par défaut. Les builds de release passent leur version avec `-Drevision=<version>` ; aucun POM temporaire mono-module n'est généré.

## Invariants

Chaque étape S2 doit conserver :

- `./mvnw clean verify` depuis la racine ;
- Java 24 / Maven 3.9.x ;
- l'artefact `minos-code-intelligence-<version>-all.jar` ;
- `com.minos.cli.MinosLauncher` comme entrée utilisateur tant que S3 ne remplace pas le composition root ;
- les contrats CLI/API/MCP/NEXUS ;
- les replays providers Java/TypeScript ;
- la distribution et l'installation Windows ;
- le handshake MCP natif.

## Conséquences positives

- Maven devient un garde-fou d'architecture réel ;
- IntelliJ peut représenter progressivement les sous-modules du produit ;
- SCIP et JGit sont confinés aux modules qui en ont réellement besoin ;
- la CLI peut utiliser le runtime local générique sans dépendre du provider SCIP ;
- S3 à S8 pourront évoluer derrière des contrats compilés plutôt que des conventions de packages ;
- le packaging utilisateur reste stable pendant la migration.

## Contraintes

- la période de migration contient temporairement un module `minos-app` plus large que sa responsabilité finale ;
- les scripts de build/release doivent raisonner sur le reactor et non sur un POM mono-module ;
- chaque extraction doit déplacer les tests pertinents avec le code ou conserver une justification explicite ;
- aucun module cible ne peut être déclaré terminé sans compilation et replays sur un SHA exact ;
- la phase d'ownership Maven par source roots externes doit être supprimée avant la gate finale S2.

## Alternatives rejetées

### Big-bang vers tous les modules en un seul commit

Rejeté : surface de diff trop grande, diagnostic de régression difficile et impossibilité d'isoler les erreurs de dépendances des erreurs de packaging.

### Conserver un seul module et utiliser uniquement ArchUnit/tests de frontières

Rejeté comme cible M15-S2 : utile en complément, mais insuffisant pour imposer les dépendances au niveau du build.

### Placer le runtime générique dans `minos-provider-scip`

Rejeté : `DoctorCommand` utilise `CommandLocator`; cette solution introduirait une dépendance de la CLI vers SCIP pour une capacité générique de découverte de commandes.

### Placer le runtime process local dans `minos-engine`

Rejeté : l'exécution de processus, la découverte du PATH et la gestion d'outils locaux sont des détails d'infrastructure, pas des responsabilités du moteur provider-indépendant.

### Créer immédiatement des modules vides

Rejeté : un module sans responsabilité ni code ne protège aucune frontière réelle.

## Liens

- issue M15 : #55
- roadmap opérationnelle : [`../roadmap/M15_EXECUTION.md`](../roadmap/M15_EXECUTION.md)
- baseline S1 : [`../history/milestones/m15/M15_S1_BASELINE.md`](../history/milestones/m15/M15_S1_BASELINE.md)
- PR S1 : #56
