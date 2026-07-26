# ADR-0022 — Imposer les frontières MINOS par un reactor Maven progressif

Date : 26 juillet 2026

Statut : **Accepted — implémenté et qualifié par M15-S2**

Origine : M15-S2

## Contexte

À la fin de M14, MINOS est livré comme un unique projet Maven `com.minos:minos-code-intelligence` contenant dans le même artefact le domaine normalisé, le moteur de requête, la persistance locale, les adapters SCIP/Git, la CLI, l'API Java, le MCP et l'intégration NEXUS.

Les frontières existent principalement par packages et conventions. Elles ne sont donc pas suffisamment protégées par la compilation : une dépendance interdite peut être introduite sans que Maven impose la direction d'architecture voulue.

M15 doit rendre ces frontières structurelles sans effectuer un big-bang qui mélangerait simultanément déplacement de sources, changement de packaging, composition applicative et évolution fonctionnelle.

## Décision

Le dépôt devient un **reactor Maven multi-module** piloté par un parent racine `com.minos:minos-parent`.

Le découpage ratifié à la fermeture de S2 est :

```text
minos-parent
├── minos-domain
├── minos-engine
├── minos-runtime-local
├── minos-storage-local
├── minos-provider-scip
├── minos-integration-git
├── minos-application
├── minos-nexus
├── minos-cli
├── minos-api
├── minos-mcp
└── minos-app
```

Soit **12 modules enfants + le parent racine = 13 projets Maven**.

Le nombre de modules a été ajusté pendant S2 uniquement lorsqu'une frontière protégeait un invariant réel. Il reste interdit de créer un module uniquement pour reproduire mécaniquement un package existant.

`minos-runtime-local` est un ajustement explicite de la cible initiale : il protège l'invariant « exécution locale générique des providers ≠ provider SCIP ». Sans cette frontière, la CLI `doctor` devrait dépendre du provider SCIP uniquement pour utiliser `CommandLocator`, ou le moteur devrait absorber de l'infrastructure de processus locale.

`minos-application` est également une frontière explicite : elle porte les services applicatifs partagés qui ne relèvent ni du moteur pur, ni d'un transport public, ni du composition root exécutable. Elle prépare M15-S3 sans prétendre encore constituer le `MinosApplication` commun final.

### Direction des dépendances

La direction conceptuelle est :

```text
minos-domain
    ↑
minos-engine
    ↑
minos-runtime-local / minos-storage-local
    ↑
adapters et intégrations
    ↑
services applicatifs / surfaces
    ↑
minos-app composition/process root
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

La dépendance MCP → CLI encore présente après S2 est **transitoire et explicitement affectée à M15-S4**. S2 supprime déjà MCP → `MinosLauncher` afin que le launcher système reste dans le composition root.

### Frontières ratifiées pendant S2

Le premier checkpoint qualifié ratifie `minos-domain` comme artefact sans dépendance externe : il possède `com.minos.domain` et `minos-app` ne compile plus ces classes directement.

Le checkpoint suivant ratifie `minos-engine` autour du noyau de requête provider/backend-independent :

```text
minos-engine
├── com.minos.query.*
└── com.minos.store.CodeKnowledgeStore   # port moteur, pas implémentation locale
```

`CodeKnowledgeStore` appartient à la frontière moteur car son contrat est défini par les besoins MINOS et ne reflète aucun backend particulier. Les implémentations mémoire/fichier restent hors de `minos-engine` et dépendent de ce port.

Le checkpoint qualifié sur `cbbc0b5f6b7a59a627cfa6af98b24107f3435edb` fixe deux frontières d'infrastructure supplémentaires :

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

Il dépend uniquement de `minos-engine`. `MinosVersion` appartient à `minos-application` car il représente la version observable de l'artefact produit et non un runtime de provider.

`minos-provider-scip` possède `com.minos.adapter.scip.*`, déclare directement `scip-java-bindings` et embarque les ressources qualifiées Windows `scip-java-windows-runner.ps1` et `ScipWriter.java`. Le provider dépend des contrats engine, du runtime local et du stockage local, mais aucune de ces couches ne dépend de SCIP.

Les surfaces et services sont ensuite séparés :

- `minos-application` : architecture, contexte, discovery local, impact, incrémental, orchestration locale, output, registry, workspace et `MinosVersion` ;
- `minos-nexus` : `NexusExportContract` et `NexusExportService` ;
- `minos-cli` : commandes et `MinosCliRunner`, hors `MinosLauncher` ;
- `minos-api` : contrats Java publics et implémentations locales ;
- `minos-mcp` : serveur MCP et ses 16 outils ;
- `minos-app` : uniquement les points d'entrée de composition/process (`MinosLauncher`, `NexusExportBridgeMain`) et l'artefact distribué.

## Migration séquentielle

S2 a suivi une migration en plusieurs commits qualifiables :

1. transformer la racine en parent/reactor ;
2. créer `minos-app` en conservant temporairement les sources historiques à leur emplacement actuel ;
3. extraire `minos-domain` ;
4. extraire les contrats et services du moteur ;
5. extraire persistance, runtime local, provider SCIP et intégration Git ;
6. extraire services applicatifs et surfaces API/CLI/MCP/NEXUS ;
7. réduire `minos-app` au bootstrap/composition ;
8. relocaliser physiquement **183 sources de production** et **2 ressources SCIP** dans leurs modules ;
9. relocaliser physiquement **92 sources de test**, en conservant dans `minos-app` les tests cross-boundary qui créeraient sinon des dépendances inverses/cycliques de test ;
10. supprimer les bridges externes `sourceDirectory`, `testSourceDirectory` et ressources transitoires ;
11. qualifier le reactor complet, les artefacts de modules et les distributions M14.

Les checkpoints intermédiaires ont utilisé volontairement une phase d'**ownership Maven avant relocation physique** : les sources restaient temporairement dans l'arbre historique, mais chaque module cible les compilait explicitement et `minos-app` les excluait. Les runners S2 inspectaient ensuite les JARs, les ressources et `target/classes` pour prouver que l'ownership était réellement imposé par Maven.

Cette configuration transitoire a été **entièrement supprimée avant la fermeture de S2**. Les sources et tests sont maintenant sous les `src/main/...` et `src/test/...` de leurs modules propriétaires.

## Coordonnée et packaging utilisateur

L'artefact exécutable historique reste :

```text
com.minos:minos-code-intelligence:<version>
```

Il est produit par `minos-app`.

Son répertoire de build reste `target/` à la racine afin de conserver les contrats des scripts M14 : shaded JAR, JPackage, ZIP Windows, installateur et smoke MCP.

La racine utilise une version Maven CI-friendly :

```xml
<version>${revision}</version>
```

avec `revision=0.2.0-SNAPSHOT` par défaut. Les builds de release passent leur version avec `-Drevision=<version>` ; aucun POM temporaire mono-module n'est généré.

## Qualification finale S2

Le head fonctionnel final de la PR #57 a été qualifié localement sur Windows avec Java 24 avant merge :

```text
HEAD      637402782c29526b926968e0b8b525a2fa6fdc2c
Java      OpenJDK 24.0.1
Maven     3.9.16
Tests     237 Surefire + 1 Failsafe = 238 PASS
M14       PASS
Doctor    READY
MCP       native handshake PASS
```

La distribution Windows `0.2.0-rc1`, l'installation vierge, les providers Java/TypeScript, la récupération STALE, l'ownership des JARs de modules et le shaded JAR sont également verts.

Baseline repeated-query enregistrée sur ce SHA :

```text
first   22.5657 ms
p50      3.8794 ms
p95      5.3350 ms
loads   21
```

ZIP qualifié :

```text
SHA-256 455cec6afeff2b9ea33afa2cf19244e2ca40c1fc23fce4974148d0faf285e26e
```

Verdict :

```text
M15-S2 FULL MODULE-BOUNDARY VALIDATION SUCCESS
```

PR #57 mergée dans `main` par le merge commit `7b064196b31a0676852a5f7effb552beb396cc8a`.

## Invariants

Chaque étape S2 a conservé :

- `./mvnw clean verify` depuis la racine ;
- Java 24 / Maven 3.9.x ;
- l'artefact `minos-code-intelligence-<version>-all.jar` ;
- `com.minos.cli.MinosLauncher` comme entrée utilisateur ;
- les contrats CLI/API/MCP/NEXUS ;
- les replays providers Java/TypeScript ;
- la distribution et l'installation Windows ;
- le handshake MCP natif.

## Conséquences positives

- Maven devient un garde-fou d'architecture réel ;
- IntelliJ dispose désormais d'un layout Maven physique standard avec 12 modules enfants, sans source root externe partagé ;
- SCIP et JGit sont confinés aux modules qui en ont réellement besoin ;
- la CLI peut utiliser le runtime local générique sans dépendre du provider SCIP ;
- S3 à S8 peuvent évoluer derrière des contrats compilés plutôt que des conventions de packages ;
- le packaging utilisateur reste stable pendant la migration.

## Contraintes restantes

- M15-S3 doit encore introduire le `MinosApplication` commun et centraliser la composition applicative ;
- M15-S4 doit supprimer le routage métier MCP → CLI ;
- les scripts de build/release doivent continuer de raisonner sur le reactor ;
- chaque futur déplacement de responsabilité doit conserver une justification explicite pour les tests cross-boundary ;
- la CI automatique de PR reste une dette volontairement affectée à M15-S10.

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
- PR S2 : #57