# État courant — MINOS

Dernière mise à jour documentaire : **26 juillet 2026**

Ce fichier résume l'état produit livré. Les preuves détaillées sont conservées dans [`history/milestones/`](history/milestones/) et dans les PR/issues de qualification ; les décisions durables sont indexées dans [`adr/`](adr/README.md).

## Synthèse

```text
C0 — Cadrage                         TERMINÉ
M0 — Faisabilité technique          TERMINÉ ET LIVRÉ
M1 — Découverte et orchestration    TERMINÉ ET LIVRÉ
M2 — Intelligence des symboles      TERMINÉ ET LIVRÉ
M3 — Intelligence des relations     TERMINÉ ET LIVRÉ
M4 — Recherche et contexte compact  TERMINÉ ET LIVRÉ
M5 — Tests liés et dérivations      TERMINÉ ET LIVRÉ
M6 — Intelligence d'architecture    TERMINÉ, VALIDÉ ET LIVRÉ
M7 — Indexation incrémentale        TERMINÉ, VALIDÉ ET LIVRÉ
M8 — Analyse d'impact               TERMINÉ, VALIDÉ ET LIVRÉ
M9 — CLI stabilisée                 TERMINÉ, VALIDÉ ET LIVRÉ
M10 — Serveur MCP                   TERMINÉ, VALIDÉ ET LIVRÉ
M11 — API publique                  TERMINÉ, VALIDÉ ET LIVRÉ
M12 — Multi-dépôts + Git            TERMINÉ, VALIDÉ ET LIVRÉ
M13 — Intégration NEXUS             TERMINÉ, VALIDÉ ET LIVRÉ
M14 — Indexation autonome + PROD    TERMINÉ, VALIDÉ ET LIVRÉ
M15 — Industrialisation Core        TERMINÉ, VALIDÉ ET LIVRÉ
M16 — Scalabilité et performance    TERMINÉ, VALIDÉ ET LIVRÉ
```

## M16 — Scalabilité et performance à grande échelle

M16 ajoute une campagne de performance reproductible sans changer les contrats publics MINOS ni présélectionner un backend plus complexe.

Acquis M16 :

```text
S1   harness benchmark exact-head + machine/JVM                    ✅
S2   datasets synthétiques déterministes et fixtures réelles       ✅
S3   query benchmark p50/p95/p99                                   ✅
S4   MCP sustained load sur serveur STDIO long-lived               ✅
S5   indexation réelle FULL/NONE + débit/RSS                        ✅
S6   profil heap/RSS/disque/indexes                                 ✅
S7   décision backend gouvernée par mesures                         ✅
S8   optimisation uniquement sous goulot prouvé                    ✅
S9   rétention/compaction snapshots + runs                          ✅
```

### Campagne STANDARD

Le gate obligatoire utilise le profil versionné :

```text
10 000 fichiers logiques
100 000 symboles
500 000 occurrences
250 000 relations
seed 16000031
```

Les profils `SMOKE`, `EXTENDED` et `STRESS` restent disponibles pour développement et diagnostic, mais seul `STANDARD` est requis pour fermer M16 de façon reproductible sur les machines de qualification.

Les mesures comprennent : cold start, publication/chargement snapshot, construction des indexes, p50/p95/p99 des requêtes, peak/retained heap, RSS processus, disque, débit d'indexation et séquences MCP.

### Requêtes couvertes

```text
find-symbol
find-usages
dependencies
dependents
related-tests
search
architecture
impact
```

Le benchmark MCP rejoue les mêmes familles d'opérations sur un serveur STDIO long-lived et vérifie qu'une vue inchangée ne provoque pas de rechargement complet systématique du snapshot.

### Indexation

Le benchmark fournisseur réel utilise les fixtures et runtimes déjà qualifiés par M14 :

- premier passage forcé : `FULL` ;
- workspace inchangé : `NONE` / `NO_CHANGES` ;
- source modifiée : plan observé et reporté ;
- l'incrémental n'est jamais déclaré mesuré si le provider ne publie pas explicitement cette capability.

### Mémoire, disque et décision backend

Le backend M15 reste la référence :

```text
snapshots fichiers versionnés
        +
SnapshotQueryView bornée
        +
indexes mémoire reconstruisibles
```

M16 fournit un comparateur SQLite expérimental par la bibliothèque standard Python, hors runtime et hors Maven. Il sert uniquement à comparer certaines clés d'accès sur les mêmes cardinalités.

[ADR-0025](adr/0025-measurement-gated-storage-backend-evolution.md) impose qu'un changement de backend n'est autorisé qu'après échec d'un seuil produit, identification du goulot et comparaison sur le même dataset/seed. Si tous les seuils STANDARD passent, **conserver le backend courant est la décision attendue**.

### Rétention / compaction

La croissance disque est désormais bornée par une politique explicite :

```text
snapshots : actif toujours conservé + 2 historiques
runs      : 20 réussis + 10 non réussis
```

Le `latestRunId` référencé par l'état projet reste protégé même lorsqu'une politique plus agressive est utilisée. La compaction ne modifie jamais le snapshot actif.

### Qualification

La porte reproductible M16 est :

```text
scripts/m16/run-final.ps1
```

Elle rejoue d'abord `clean verify` et l'intégralité de la qualification M14/providers/Windows, puis exécute la campagne STANDARD, MCP sustained, indexation réelle, comparaison backend et rétention. Le verdict requis est :

```text
M16 FINAL SCALABILITY VALIDATION SUCCESS
```

La preuve exacte (SHA, machine, latences, mémoire, disque, débit, décision backend) est enregistrée dans la PR M16 et l'issue #63 afin de ne pas modifier le head après qualification.

## M15 — Industrialisation du Core Engine

M15 transforme le socle M14 en plateforme modulaire, réutilisable en processus long et protégée par des gates automatiques, sans changement fonctionnel volontaire des contrats utilisateur.

Acquis M15 :

```text
S1   baseline non-régression et coût initial                  ✅
S2   reactor Maven multi-module                               ✅
S3   MinosApplication / composition root partagé              ✅
S4   MCP découplé de la CLI métier                            ✅
S5   ProjectResolver commun                                   ✅
S6   persistance snapshots décomposée                         ✅
S7   cache borné du snapshot actif                            ✅
S8   indexes de requête reconstruisibles                      ✅
S9   JaCoCo + quality gates ciblées                           ✅
S10  CI automatique des pull requests Linux/Windows           ✅
S11  cohérence documentaire calculable                        ✅
```

### Architecture M15

```text
CLI / API / MCP / NEXUS
          ↓
    MinosApplication
          ↓
 services applicatifs communs
          ↓
 FileSymbolSnapshotStore
          ↓
 active.pointer → SnapshotQueryView
                    ├── snapshot immuable
                    └── indexes reconstruisibles
```

La clé logique du cache est `(projectId, snapshotId)`. Le descriptor persistant complet reste comparé afin qu'une republication du même identifiant logique avec un fichier/checksum différent ne serve jamais une vue obsolète.

La correction après promotion ne dépend pas d'un callback d'invalidation : le pointeur actif est relu avant de publier une vue construite. Le cache applique également une borne d'entrées pour rendre le coût mémoire explicite avant M16.

### Persistance

`FileSymbolSnapshotStore` reste la façade compatible. Les responsabilités sont séparées entre :

- `SnapshotRepository` ;
- `ActiveSnapshotRepository` ;
- `SnapshotCodecV1` / `SnapshotCodecV2` ;
- `SnapshotIntegrityService` ;
- `SnapshotRetentionService`.

Les formats historiques `.symbols`, `.knowledge` et `active.pointer` restent compatibles.

### Requêtes

Les indexes mémoire dérivés couvrent notamment :

- symboles par identifiant, nom normalisé, nom qualifié et fichier ;
- occurrences par symbole résolu ;
- relations par source, cible et type.

Le snapshot persisté reste la source de vérité. Les indexes peuvent être détruits et reconstruits sans migration.

### Qualité continue

Le reactor est instrumenté par JaCoCo et publie un rapport agrégé sous :

```text
target/site/jacoco-aggregate/
```

Les seuils ciblés et leur justification sont décrits dans [`developer/quality-gates.md`](developer/quality-gates.md).

La CI de PR exécute automatiquement `clean verify` sur Linux et Windows, puis les gates JaCoCo et cohérence documentaire. La publication de release reste un workflow distinct et explicite.

### Documentation calculable

Les facts produit mécaniques sont générés depuis le code par :

```text
python scripts/docs/product-facts.py
python scripts/docs/product-facts.py --check
```

La version, le contrat API, le catalogue MCP, les commandes/formats CLI et les providers qualifiés sont publiés dans [`generated/product-facts.md`](generated/product-facts.md).

## Contrats publics courants

- CLI : stable avec codes de sortie `0/1/2` ;
- API Java : contrat fournisseur-indépendant ;
- MCP : STDIO read-only et accès direct aux services applicatifs, sans routage métier via CLI ;
- NEXUS : export JSON local versionné ;
- installation PROD Windows : ZIP versionné, runtime Java embarqué, doctor et MCP natif ;
- Docker MCP : mode durci optionnel.

Les valeurs calculables exactes sont dans [`generated/product-facts.md`](generated/product-facts.md).

## Frontières architecturales courantes

- MINOS reste propriétaire des faits de Code Intelligence ;
- NEXUS reste propriétaire du ranking, de la sélection et du budget de contexte ;
- les capacités fournisseur absentes ne sont jamais inventées ;
- l'analyse d'impact reste potentielle, jamais une preuve runtime exhaustive ;
- une relation cross-repository exige une identité exacte et unique ;
- l'activité Git reste distincte de l'importance architecturale ;
- CLI, API, MCP et NEXUS consomment le même cœur applicatif ;
- MCP ne consomme pas la CLI comme couche métier ;
- les snapshots persistés sont la source de vérité des vues/indexes mémoire ;
- toute évolution de backend est gouvernée par des mesures reproductibles M16.

## Suite

M17 — **Provider & Discovery Platform** — est le prochain jalon planifié. Il généralise discovery et providers à de nouveaux écosystèmes tout en conservant les contrats et règles de preuve établis par M15/M16.

## Documentation

- portail : [`README.md`](README.md) ;
- roadmap : [`ROADMAP.md`](ROADMAP.md) ;
- exécution M15 : [`roadmap/M15_EXECUTION.md`](roadmap/M15_EXECUTION.md) ;
- exécution M16 : [`roadmap/M16_EXECUTION.md`](roadmap/M16_EXECUTION.md) ;
- utilisateur : [`user/README.md`](user/README.md) ;
- développeur : [`developer/README.md`](developer/README.md) ;
- qualité : [`developer/quality-gates.md`](developer/quality-gates.md) ;
- facts générés : [`generated/product-facts.md`](generated/product-facts.md) ;
- décisions : [`adr/README.md`](adr/README.md) ;
- preuves historiques : [`history/milestones/README.md`](history/milestones/README.md).

## Source de vérité

`STATUS.md` décrit l'état livré. `ROADMAP.md` décrit la progression produit. Les ADR décrivent les décisions durables. Les rapports sous `history/milestones/` restent des archives et peuvent contenir des états intermédiaires propres à leur date de validation.
