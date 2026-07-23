# MINOS

**MINOS** est un moteur d'intelligence du code (*Code Intelligence Engine*) conçu pour construire une compréhension structurée, persistante, interrogeable et explicable de projets logiciels.

MINOS est pensé pour fonctionner **localement**, être **agnostique du langage**, indépendant des fournisseurs d'IA et découplé des moteurs d'indexation ou de stockage utilisés en interne.

MINOS n'est ni un chatbot, ni un LLM, ni un simple moteur de recherche textuelle.

Son rôle est notamment de répondre à des questions comme :

- Où est défini ce symbole ?
- Qui l'utilise, l'appelle, l'étend ou l'implémente ?
- De quoi dépend-il ?
- Qu'est-ce qui dépend de lui ?
- Quels tests lui sont liés ?
- Quels éléments peuvent être impactés par une modification ?
- Quelle est la topologie générale du projet ?

## Position dans l'écosystème

```text
                       JARVIS
                    Orchestration
                         │
            ┌────────────┴────────────┐
            │                         │
            ▼                         ▼
          NEXUS                     MINOS
   Context Intelligence       Code Intelligence
            │                         │
            └────────────┬────────────┘
                         ▼
                 ALFRED / BRAINIAC
                  Agents / profils IA
```

Cette vue décrit les responsabilités fonctionnelles de l'écosystème. MINOS reste autonome et ne dépend fonctionnellement ni de JARVIS, ni de NEXUS, ni d'Alfred, ni de Brainiac.

Le flux de connaissance peut également être représenté ainsi :

```text
CODEBASE / WORKSPACE
        │
        ▼
      MINOS
 Code Intelligence
« Je comprends le code »
        │
        ▼
      NEXUS
Context Intelligence
« Je sélectionne le bon contexte »
        │
        ▼
 AGENT / LLM / IDE
```

Voir [`docs/ECOSYSTEME.md`](docs/ECOSYSTEME.md) pour la description détaillée.

## Phase actuelle

Le projet a terminé C0, M0, M1, M2, M3, M4 et M5. Les jalons M2 à M5 sont
validés localement dans la PR de livraison courante ; **M6 — Intelligence
d'architecture** est le prochain jalon de la roadmap.

> **M5 — Tests liés et dérivations explicables — TERMINÉ ET VALIDÉ LOCALEMENT**

La porte finale M5 a validé `93` sources principales, `49` sources de test et
`140/140` tests, puis le launcher Windows avec `minos.cmd --help`. M5 dérive
`RELATED_TEST` depuis les références, les appels disponibles, le nommage et la
proximité de namespace, avec un score et des preuves structurées. Il ajoute
`minos related-tests`. Voir la [`dérivation M5`](docs/m5/RELATED_TEST_DERIVATION.md)
et la [`décision M5`](docs/m5/DECISION_M5.md).

M4 ajoute `minos search`, qui compose symboles, relations, preuves, usages et
plages source sous des limites de profondeur et de tokens, ainsi que
`minos get-source` pour la récupération complète explicitement demandée. Le
verdict, la politique de volume et le benchmark réel sont dans la
[`décision M4`](docs/m4/DECISION_M4.md).

M3 fournit des requêtes relationnelles normalisées et isolées par projet,
normalise les quatre drapeaux SCIP sans inventer de sémantique, dérive des
dépendances explicables, persiste symboles/occurrences/relations dans un
snapshot v2 rétrocompatible et expose `find-usages`, implémentations, appels
disponibles, dépendances et dépendants dans la CLI. Voir la
[`décision M3`](docs/m3/DECISION_M3.md), le
[`format v2`](docs/m3/CODE_KNOWLEDGE_SNAPSHOTS.md) et les
[`commandes relationnelles`](docs/m3/CLI_RELATIONSHIPS.md).

Le tableau de bord [`docs/STATUS.md`](docs/STATUS.md) indique ce qui est
terminé et la porte active.

Le premier incrément M2 structure la recherche par texte, nom qualifié, type et
module, ajoute un classement déterministe et expose les symboles déclarés dans
un fichier. Le second extrait les noms qualifiés Java et TypeScript depuis les
descripteurs SCIP globaux sans sur-déclarer une identité canonique. Voir
[`docs/m2/SYMBOL_SEARCH.md`](docs/m2/SYMBOL_SEARCH.md) et
[`docs/m2/SCIP_QUALIFIED_NAMES.md`](docs/m2/SCIP_QUALIFIED_NAMES.md). Le
troisième incrément ajoute le DTO compact
[`SymbolResult`](docs/m2/SYMBOL_RESULTS.md). Le quatrième fournit ses rendus
déterministes [`TEXT` et `JSON`](docs/m2/SYMBOL_OUTPUT.md), prêts à être branchés
sur la CLI. Le cinquième ajoute le contrat et le dispatcher
[`find-symbol`](docs/m2/FIND_SYMBOL_CLI.md), avec un port explicite pour le
chargement du projet et de son snapshot actif. La clôture ajoute le
[`snapshot persistant`](docs/m2/SYMBOL_SNAPSHOTS.md), le launcher exécutable et
la [`décision M2`](docs/m2/DECISION_M2.md).

La phase **C0 — Cadrage fonctionnel et architectural est clôturée**.

C0 a validé :

- le cahier des charges ;
- le MVP strict ;
- le modèle de domaine minimal pour M0 ;
- le modèle des fournisseurs et capacités ;
- la stratégie de tests et de métriques ;
- **ADR-0001** — cœur agnostique du langage et de l'indexeur ;
- **ADR-0002** — SCIP comme protocole sémantique privilégié, non obligatoire ;
- **ADR-0003** — `CodeKnowledgeStore` comme frontière MINOS et principe **MINOS-first, Glean-optional** ;
- **ADR-0004** — Maven 3.9.x + Maven Wrapper + cœur sans framework serveur ;
- **ADR-0005** — **alignement de MINOS sur Java 24**, version déjà utilisée dans l'environnement de développement principal.

M0 doit maintenant **tester les hypothèses par des expérimentations mesurables**, sans construire prématurément le produit complet.

## Stack M0

```text
Langage        Java 24
Build          Apache Maven 3.9.x
Wrapper        Maven Wrapper 3.3.4 / Maven 3.9.16
Framework      Aucun framework serveur dans le cœur
```

La version Java suit la toolchain de référence des projets de l'environnement de développement. Une montée de version devra être coordonnée plutôt qu'imposée uniquement à MINOS.

Le choix d'un framework pour une future API ou couche MCP reste différé jusqu'au besoin réel.

## Fondation technique M0

```text
Repository
    │
    ▼
IndexerRegistry
    │
    ├── SCIP Providers      ← chemin privilégié
    ├── Native Providers
    └── Specialized Providers
    │
    ▼
MINOS Normalization
    │
    ▼
CodeKnowledgeStore
    │
    ├── InMemory            ← tests
    ├── Lightweight         ← baseline M0
    └── Glean               ← backend avancé candidat
    │
    ▼
MINOS Query Services
```

Principe :

> **MINOS-first, Glean-optional.**

SCIP est privilégié lorsqu'un fournisseur suffisamment fiable existe. Glean doit démontrer pendant M0 une valeur suffisante pour justifier son coût opérationnel ; MINOS doit pouvoir fonctionner sans lui.

## Écosystèmes de validation M0

- **Java** — premier écosystème ;
- **TypeScript** — second écosystème ;
- **Python** — repli expérimental si nécessaire.

Dépôt Java réel principal :

```text
FTurleque/ariane-chatbot
```

## Expérimentations M0

```text
A — Qualifier scip-java
B — Baseline SCIP → MINOS sans Glean
C — SCIP → Glean → MINOS
D — Reproduire le pipeline avec TypeScript
E — Comparer backend léger et Glean
```

Les mêmes contrats MINOS et les mêmes jeux de données doivent être utilisés pour comparer les chemins techniques.

## Documents de référence

- [`docs/STATUS.md`](docs/STATUS.md) — état opérationnel et prochaines portes ;
- [`docs/CAHIER_DES_CHARGES.md`](docs/CAHIER_DES_CHARGES.md) — cahier des charges validé ;
- [`docs/MVP.md`](docs/MVP.md) — MVP strict validé ;
- [`docs/ECOSYSTEME.md`](docs/ECOSYSTEME.md) — positionnement dans l'écosystème ;
- [`docs/architecture/overview.md`](docs/architecture/overview.md) — architecture générale ;
- [`docs/architecture/MODELE_DOMAINE.md`](docs/architecture/MODELE_DOMAINE.md) — modèle de domaine validé pour M0 ;
- [`docs/architecture/INDEXEURS_CAPACITES.md`](docs/architecture/INDEXEURS_CAPACITES.md) — fournisseurs et capacités validés pour M0 ;
- [`docs/METRIQUES_VALIDATION.md`](docs/METRIQUES_VALIDATION.md) — métriques et seuils ;
- [`docs/M0_PLAN_EXPERIMENTATIONS.md`](docs/M0_PLAN_EXPERIMENTATIONS.md) — protocole M0 ;
- [`docs/ROADMAP.md`](docs/ROADMAP.md) — feuille de route ;
- [`docs/adr/`](docs/adr/) — décisions d'architecture ;
- [`docs/research/COMPARATIF_FONDATIONS_CODE_INTELLIGENCE.md`](docs/research/COMPARATIF_FONDATIONS_CODE_INTELLIGENCE.md) — comparatif des fondations.

## Règle de développement M0

> **Mesurer avant d'industrialiser.**

M0 doit produire des preuves techniques, des profils de qualité et des décisions documentées. Toute infrastructure non nécessaire à une expérimentation doit être différée.
