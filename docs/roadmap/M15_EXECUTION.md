# M15 — Exécution : industrialisation du Core Engine

Statut : **DÉMARRÉ — 0/11 sous-incréments terminés**

Issue principale : **#55**  
PR de planification : **#54**

## Objectif produit

Transformer le socle M14 en une plateforme de Code Intelligence modulaire, durable et performante, sans modifier volontairement les résultats fonctionnels déjà livrés.

M15 doit fermer les dettes structurelles révélées par la croissance de MINOS :

```text
monolithe Maven
    ↓
composition dispersée
    ↓
MCP → CLI → moteur
    ↓
chargements snapshot répétés
    ↓
scans mémoire complets
    ↓
coût croissant par requête
```

La cible est :

```text
frontières Maven explicites
        ↓
MinosApplication
        ↓
services applicatifs communs
        ↓
cache du snapshot actif
        ↓
indexes de requête reconstruisibles
        ↓
CLI / API / MCP / NEXUS
```

M15 est un jalon d'**industrialisation interne**. Il ne doit pas devenir un prétexte pour ajouter de nouvelles capacités produit sans rapport avec les limites observées.

---

## Lecture de l'avancement

- ✅ = implémenté **et validé sur le head final exact** ;
- 🟡 = implémenté mais le head courant doit encore passer la qualification complète ;
- ⬜ = non implémenté ;
- une validation sur un ancien SHA ne valide jamais automatiquement un nouveau head.

| Étape | Fonction | État | Gate principale |
|---|---|---:|---|
| M15-S1 | Baseline de non-régression | ⬜ | replays M14 + mesures de référence |
| M15-S2 | Maven multi-module | ⬜ | reactor vert + frontières compilées |
| M15-S3 | `MinosApplication` | ⬜ | composition root unique |
| M15-S4 | Découplage MCP | ⬜ | aucun appel métier MCP → CLI |
| M15-S5 | Résolution projet commune | ⬜ | résolution unique et cohérente |
| M15-S6 | Persistance décomposée | ⬜ | codecs/repositories séparés et compatibles |
| M15-S7 | Cache snapshot actif | ⬜ | chargement réutilisé + invalidation prouvée |
| M15-S8 | Indexes de requête | ⬜ | résultats identiques, scans systématiques supprimés |
| M15-S9 | Qualité continue | ⬜ | JaCoCo + gates ciblées |
| M15-S10 | CI de PR | ⬜ | `clean verify` automatique avant merge |
| M15-S11 | Cohérence documentaire | ⬜ | faits calculables vérifiés/générés |

---

# Stratégie d'exécution

## Pas de refactor big bang

L'ordre M15 est intentionnel :

```text
S1 baseline
 ↓
S2 frontières Maven
 ↓
S3 composition root
 ↓
S4/S5 simplification des adapters
 ↓
S6 persistance
 ↓
S7 cache
 ↓
S8 indexes
 ↓
S9/S10 qualité et CI
 ↓
S11 cohérence documentaire
 ↓
qualification finale
```

Chaque sous-incrément doit conserver le dépôt compilable et testable.

## Politique de PR

Préférer un PR par sous-incrément, ou un PR regroupant uniquement des étapes fortement couplées lorsque la séparation produirait un état artificiel.

Nommage recommandé :

```text
m15/s1-baseline
m15/s2-maven-modules
m15/s3-application-root
m15/s4-mcp-decoupling
m15/s5-project-resolution
m15/s6-persistence
m15/s7-snapshot-cache
m15/s8-query-indexes
m15/s9-quality
m15/s10-pr-ci
m15/s11-doc-consistency
```

Chaque PR doit indiquer :

- le sous-incrément M15 couvert ;
- les invariants protégés ;
- les tests/replays exécutés ;
- le SHA exact qualifié ;
- les métriques avant/après lorsqu'elles sont pertinentes ;
- les limitations restantes.

---

# M15-S1 — Baseline de non-régression

## But

Capturer l'état M14 avant tout déplacement structurel afin de pouvoir démontrer que M15 conserve les contrats fonctionnels.

## Travaux

1. figer le SHA de départ M15 ;
2. exécuter `./mvnw clean verify` ;
3. relever :
   - nombre de sources principales ;
   - nombre de sources de test ;
   - nombre de tests exécutés ;
   - échecs/erreurs/skips ;
4. rejouer les parcours autonomes Java et TypeScript lorsque l'environnement de qualification le permet ;
5. rejouer les contrats CLI structurants ;
6. rejouer l'API publique ;
7. rejouer le serveur MCP et son catalogue ;
8. capturer les résultats de requêtes représentatives ;
9. relever une baseline de coût pour les requêtes répétées sur un même snapshot.

## Requêtes de référence minimales

```text
project list/get
find-symbol
find-usages
relationships/dependencies
related-tests
architecture
architecture-graph
module-context
impact
Git/workspace queries
```

## Mesures de référence

Sur un fixture stable, enregistrer au minimum :

```text
active_snapshot_load_count
first_query_latency
repeated_query_latency
heap_after_load
symbol_count
occurrence_count
relationship_count
```

Le but de S1 n'est pas encore de fixer les seuils M16, mais d'empêcher M15 d'améliorer l'architecture en masquant une régression de comportement ou de coût.

## Gate S1

- `clean verify` vert ;
- replays M14 verts ;
- sorties de référence archivées ;
- mesures avant refactor enregistrées ;
- SHA exact documenté.

---

# M15-S2 — Maven multi-module

## But

Transformer les frontières aujourd'hui principalement logiques en frontières vérifiées par compilation.

## Cible initiale

Le découpage exact doit être ratifié par ADR, mais la cible de travail est :

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

Il est permis de fusionner certains modules si une frontière n'apporte aucune protection réelle. Il est interdit de créer des modules uniquement pour reproduire les packages existants.

## Règles de dépendance cibles

```text
minos-domain
    ↑
minos-engine
    ↑
implementations/adapters
    ↑
minos-app composition
    ↑
CLI / API / MCP / NEXUS
```

Interdictions structurelles :

- domaine → SCIP ;
- domaine → MCP SDK ;
- moteur → CLI ;
- moteur → protocole MCP ;
- architecture/impact → classes d'exposition ;
- API publique → backend local concret lorsque le contrat peut dépendre d'un port.

## Migration

1. introduire le parent reactor sans déplacer tout le code ;
2. déplacer d'abord le domaine et les contrats stables ;
3. déplacer le moteur ;
4. déplacer storage/provider/integrations ;
5. déplacer les surfaces publiques ;
6. déplacer le bootstrap final dans `minos-app` ;
7. adapter packaging shaded/JPackage/release sans modifier le parcours utilisateur.

## Gate S2

- `./mvnw clean verify` depuis la racine construit tout le reactor ;
- les tests de frontières restent verts ou sont remplacés par des protections plus fortes ;
- le shaded JAR/launcher final reste fonctionnel ;
- les scripts de release continuent de produire la même surface utilisateur ;
- IntelliJ importe les modules Maven correctement ;
- aucune dépendance circulaire entre modules.

---

# M15-S3 — `MinosApplication` et composition root

## But

Remplacer la composition dispersée dans `MinosLauncher`, `LocalMinosApi` et les adapters par une racine applicative commune.

## Cible

```text
MinosApplication
├── ProjectService
├── IndexingService
├── SymbolQueryEngine
├── RelationshipQueryEngine
├── CodeSearchEngine
├── RelatedTestEngine
├── ArchitectureEngine
├── ImpactEngine
├── WorkspaceService
└── GitIntelligence
```

Les noms exacts peuvent évoluer ; la responsabilité ne doit pas.

## Travaux

- introduire un objet de configuration applicative explicite ;
- centraliser la construction du registry, snapshot store, state stores, provider registry et services ;
- rendre les services réutilisables en processus long ;
- conserver des ports injectables pour les tests ;
- réduire les wrappers lazy spécialisés du launcher ;
- faire de `MinosLauncher` un bootstrap de transport, pas un conteneur métier.

## Gate S3

- CLI, API et MCP peuvent être construits à partir de la même instance `MinosApplication` ;
- la composition du moteur n'est plus recopiée dans plusieurs surfaces ;
- les tests peuvent construire une application avec stores/providers de test ;
- résultats fonctionnels identiques à S1.

---

# M15-S4 — Découplage MCP

## But

Supprimer le chemin actuel où un tool MCP reconstruit une commande CLI et réexécute `MinosLauncher`.

## Avant

```text
MCP tool
  ↓
CLI arguments
  ↓
MinosLauncher.run
  ↓
MinosCli
  ↓
local query/service
```

## Après

```text
MCP tool
  ↓
MCP request mapping
  ↓
MinosApplication service
  ↓
MCP response mapping
```

La CLI suit le même principe :

```text
CLI args
  ↓
CLI mapping
  ↓
MinosApplication service
  ↓
text/json rendering
```

## Contraintes

- ne pas dupliquer les règles métier ;
- conserver les schémas MCP et bornes de sécurité ;
- conserver les outils read-only par défaut ;
- conserver les codes de sortie CLI indépendamment du MCP.

## Gate S4

- aucune invocation métier MCP ne passe par `MinosLauncher.run(...)` ;
- catalogue MCP inchangé sauf évolution explicitement versionnée ;
- replays MCP verts ;
- sorties fonctionnelles équivalentes à la baseline.

---

# M15-S5 — Résolution projet commune

## But

Garantir une seule sémantique pour retrouver un projet par UUID, nom ou référence utilisateur.

## Cible

Introduire un composant du type :

```text
ProjectResolver
├── resolve(ProjectRef)
├── resolveById(UUID)
├── resolveByName(String)
└── listCandidates(...)
```

Erreurs communes :

```text
PROJECT_NOT_FOUND
PROJECT_REFERENCE_AMBIGUOUS
INVALID_PROJECT_REFERENCE
```

## Gate S5

- CLI/API/MCP utilisent la même résolution ;
- aucune surface ne réimplémente la logique d'ambiguïté ;
- tests nom/UUID/not-found/ambiguïté partagés ;
- messages de transport peuvent différer, mais le diagnostic métier est identique.

---

# M15-S6 — Persistance décomposée

## But

Réduire la concentration de responsabilités du stockage snapshot tout en préservant son atomicité et sa compatibilité.

## Cible logique

```text
SnapshotRepository
SnapshotManifestRepository
ActiveSnapshotRepository
SnapshotCodec
├── SnapshotCodecV1
├── SnapshotCodecV2
└── versions futures
SnapshotIntegrityService
SnapshotRetentionService
```

## Contraintes

- conserver la promotion atomique ;
- conserver les checksums ;
- aucune perte silencieuse de snapshot ;
- les limites de lecture restent défensives ;
- toute incompatibilité historique produit une erreur explicite ou une migration explicite.

## Gate S6

- les snapshots actuels sont lisibles ;
- publication/promotion/rollback restent atomiques ;
- corruptions et versions non supportées restent diagnostiquées ;
- les codecs sont testables indépendamment du repository ;
- la rétention est un service séparé, même si sa politique complète est finalisée en M16.

---

# M15-S7 — Cache du snapshot actif

## But

Éviter de désérialiser et reconstruire la connaissance complète à chaque requête portant sur le même snapshot actif.

## Identité de cache

```text
(projectId, snapshotId)
```

Ne jamais utiliser uniquement le nom du projet ou un timestamp implicite.

## Sémantique

```text
request
  ↓
resolve active snapshot id
  ↓
cache hit ? ── yes ──> immutable query view
  │
  no
  ↓
load snapshot
  ↓
build immutable query view
  ↓
cache
```

Lors d'une promotion :

```text
old snapshot A active
        ↓
promote B
        ↓
active pointer = B
        ↓
new requests resolve B
        ↓
A no longer selected
```

Une invalidation explicite peut compléter cette identité, mais la correction ne doit pas dépendre d'une invalidation fragile lorsque l'identifiant actif a changé.

## Contraintes

- vues de requête immuables ;
- comportement thread-safe ;
- borne mémoire documentée ;
- pas de partage entre projets par erreur ;
- test de promotion pendant un processus long.

## Gate S7

- deuxième requête sur le même snapshot ne provoque pas un chargement complet supplémentaire ;
- promotion d'un snapshot rend immédiatement le nouveau snapshot observable ;
- ancien snapshot jamais retourné après résolution du nouveau active pointer ;
- tests concurrence/cache hit/cache miss/promotion verts.

---

# M15-S8 — Indexes de requête

## But

Remplacer les scans complets systématiques de l'`InMemoryCodeKnowledgeStore` par des indexes secondaires reconstruisibles.

## Indexes minimaux

```text
symbolId                    -> Symbol
normalizedName              -> List<Symbol>
qualifiedName               -> List<Symbol>
fileId                      -> List<Symbol>
resolvedSymbolId            -> List<Occurrence>
sourceEntity                -> List<Relationship>
targetEntity                -> List<Relationship>
relationshipKind            -> List<Relationship>
```

Des indexes composites peuvent être ajoutés uniquement lorsqu'un profil démontre leur utilité.

## Règles

- snapshot = source de vérité ;
- indexes = dérivés reconstructibles ;
- aucun index ne change la sémantique d'ordre, de limite ou de filtrage ;
- structures immuables une fois publiées ;
- déterminisme conservé.

## Gate S8

- mêmes réponses que S1 sur le corpus de référence ;
- tests différentiels scan-vs-index pendant la transition ;
- les requêtes principales ne parcourent plus systématiquement toutes les collections ;
- coût de construction et empreinte mémoire mesurés ;
- données suffisantes enregistrées pour préparer M16.

---

# M15-S9 — Qualité continue

## But

Ajouter une mesure de couverture utile sans transformer le pourcentage global en objectif artificiel.

## Travaux

- intégrer JaCoCo au reactor ;
- produire un rapport agrégé ;
- définir des gates prioritaires sur :
  - domaine et invariants ;
  - codecs/persistance ;
  - résolution projet ;
  - cache/invalidation ;
  - query indexes ;
  - API publique ;
  - mapping MCP ;
- documenter les exclusions justifiées ;
- conserver les tests d'intégration et replays comme preuve distincte de la couverture ligne/branche.

## Gate S9

- rapport JaCoCo reproductible ;
- seuils documentés ;
- seuils suffisamment ciblés pour empêcher la régression des composants critiques ;
- aucune suppression de test fonctionnel sous prétexte de couverture équivalente.

---

# M15-S10 — CI automatique des PR

## But

Protéger `main` avec une validation automatique et reproductible.

## Pipeline minimal

```text
checkout
 ↓
Java 24
 ↓
./mvnw clean verify
 ↓
architecture/boundary tests
 ↓
JaCoCo gates
 ↓
artifacts diagnostics en cas d'échec
```

Le workflow release Windows reste séparé et explicitement déclenché.

## Contraintes

- ne pas réintroduire la publication automatique de release ;
- distinguer problème GitHub Actions/infrastructure d'un échec MINOS ;
- conserver des logs exploitables ;
- permettre un smoke Windows ciblé lorsque nécessaire sans faire de chaque PR une release complète.

## Gate S10

- PR → workflow automatique ;
- un échec `verify` empêche le merge lorsque les règles de branche le permettent ;
- diagnostics accessibles ;
- workflow manuel de release inchangé.

---

# M15-S11 — Cohérence documentaire

## But

Éliminer les divergences silencieuses entre code et documentation pour les faits calculables.

## Faits candidats

```text
version produit
version contrat API
tool count MCP
catalogue tools MCP
commandes CLI
formats de sortie
providers qualifiés
versions providers
capabilities providers
```

## Approche

Selon le fait :

1. générer la section documentaire depuis le code ; ou
2. ajouter un test qui compare documentation et source de vérité ; ou
3. centraliser la valeur dans un manifest/version catalog consommé par les deux.

Ne pas générer la documentation narrative : seules les informations mécaniquement vérifiables sont concernées.

## Gate S11

- aucune divergence connue entre catalogue MCP courant et docs courantes ;
- commandes/formats/providers calculables vérifiés automatiquement ;
- `STATUS.md`, README et docs développeur gardent des rôles clairement séparés ;
- l'historique d'un jalon reste historique et n'est pas réécrit pour refléter le présent.

---

# Qualification finale M15

M15 est terminé uniquement après une qualification complète sur le head exact.

## Build

```text
./mvnw clean verify
```

Doivent être enregistrés :

```text
HEAD_SHA
java_version
maven_version
main_source_count
test_source_count
tests_run
failures
errors
skipped
reactor_modules
jacoco_gate
build_status
```

## Replays fonctionnels

Au minimum :

```text
CLI                 PASS
API                 PASS
MCP handshake       PASS
MCP catalog         PASS
MCP real call       PASS
Java index flow     PASS
TypeScript flow     PASS
snapshot promotion  PASS
STALE/recovery      PASS
architecture        PASS
impact              PASS
workspace/Git       PASS
```

Les replays dépendant d'outils externes doivent enregistrer clairement les préconditions disponibles et ne jamais être simulés silencieusement.

## Qualification structurelle

```text
Maven reactor boundaries          PASS
no MCP -> CLI business routing    PASS
single application composition    PASS
project resolution shared         PASS
snapshot codecs isolated          PASS
active snapshot cache             PASS
cache promotion invalidation      PASS
query indexes                     PASS
doc consistency checks            PASS
```

## Comparaison baseline

Comparer avec M15-S1 :

```text
functional results        identical
active snapshot reloads   reduced
repeated query latency     non-regressed / improved
peak memory                measured
query view build cost      measured
```

M15 ne fixe pas les objectifs de très grande échelle de M16, mais aucune régression majeure inexpliquée n'est acceptable.

---

# Critères de fermeture de l'issue #55

L'issue principale peut être fermée lorsque :

- [ ] M15-S1 ✅
- [ ] M15-S2 ✅
- [ ] M15-S3 ✅
- [ ] M15-S4 ✅
- [ ] M15-S5 ✅
- [ ] M15-S6 ✅
- [ ] M15-S7 ✅
- [ ] M15-S8 ✅
- [ ] M15-S9 ✅
- [ ] M15-S10 ✅
- [ ] M15-S11 ✅
- [ ] qualification finale sur SHA exact ✅
- [ ] `docs/STATUS.md` mis à jour ✅
- [ ] `docs/ROADMAP.md` mis à jour ✅
- [ ] preuves archivées sous `docs/history/milestones/m15/` ✅
- [ ] ADR M15 applicables référencées ✅

---

# Hors périmètre M15

Les éléments suivants appartiennent aux jalons suivants :

```text
nouveau backend choisi par benchmark       -> M16
nouveaux langages/build systems/providers   -> M17
plugin IntelliJ                             -> M18
CFG / data-flow / CPG / sécurité avancée    -> M19
embeddings / recherche sémantique           -> M20
```

Cette séparation est une gate produit : M15 doit rendre le moteur prêt à évoluer, pas absorber toute la roadmap M15→M20.