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
├── minos-storage-local
├── minos-provider-scip
├── minos-integration-git
├── minos-api
├── minos-cli
├── minos-mcp
├── minos-nexus
└── minos-app
```

Le nombre final de modules peut être réduit si une frontière ne protège aucun invariant réel. Il est interdit de créer un module uniquement pour reproduire mécaniquement un package existant.

### Direction des dépendances

```text
minos-domain
    ↑
minos-engine
    ↑
adapters / storage / integrations
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
- architecture/impact → couches d'exposition ;
- API publique → backend local concret lorsqu'un port stable peut être utilisé.

## Migration séquentielle

S2 suit une migration en plusieurs commits qualifiables :

1. transformer la racine en parent/reactor ;
2. créer `minos-app` en conservant temporairement les sources historiques à leur emplacement actuel ;
3. extraire `minos-domain` ;
4. extraire les contrats et services du moteur ;
5. extraire persistance, provider SCIP et intégration Git ;
6. extraire API/CLI/MCP/NEXUS ;
7. réduire `minos-app` au bootstrap/composition ;
8. supprimer toute compatibilité transitoire de source layout ;
9. qualifier le reactor complet et les distributions M14.

Le premier incrément utilise donc volontairement `minos-app` avec `sourceDirectory`/`testSourceDirectory` pointant vers les répertoires historiques de la racine. **Cette configuration est transitoire et doit disparaître avant la fermeture de M15-S2.** Elle permet de prouver d'abord le changement de reactor sans déplacer simultanément 182 sources principales et 92 sources de tests.

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
- les dépendances externes seront confinées aux modules qui en ont réellement besoin ;
- S3 à S8 pourront évoluer derrière des contrats compilés plutôt que des conventions de packages ;
- le packaging utilisateur reste stable pendant la migration.

## Contraintes

- la période de migration contient temporairement un module `minos-app` plus large que sa responsabilité finale ;
- les scripts de build/release doivent raisonner sur le reactor et non sur un POM mono-module ;
- chaque extraction doit déplacer les tests pertinents avec le code ou conserver une justification explicite ;
- aucun module cible ne peut être déclaré terminé sans compilation et replays sur un SHA exact.

## Alternatives rejetées

### Big-bang vers tous les modules en un seul commit

Rejeté : surface de diff trop grande, diagnostic de régression difficile et impossibilité d'isoler les erreurs de dépendances des erreurs de packaging.

### Conserver un seul module et utiliser uniquement ArchUnit/tests de frontières

Rejeté comme cible M15-S2 : utile en complément, mais insuffisant pour imposer les dépendances au niveau du build.

### Créer immédiatement des modules vides

Rejeté : un module sans responsabilité ni code ne protège aucune frontière réelle.

## Liens

- issue M15 : #55
- roadmap opérationnelle : [`../roadmap/M15_EXECUTION.md`](../roadmap/M15_EXECUTION.md)
- baseline S1 : [`../history/milestones/m15/M15_S1_BASELINE.md`](../history/milestones/m15/M15_S1_BASELINE.md)
- PR S1 : #56
