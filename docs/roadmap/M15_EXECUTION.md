# M15 — Exécution : industrialisation du Core Engine

Statut : **TERMINÉ — 11/11 sous-incréments**

Issue principale : **#55**  
PR de planification : **#54**  
PR de finalisation : **#62**

## Objectif produit

Transformer le socle M14 en une plateforme de Code Intelligence modulaire, durable et performante, sans modifier volontairement les résultats fonctionnels déjà livrés.

M15 ferme les dettes structurelles suivantes :

```text
monolithe Maven
    ↓
composition dispersée
    ↓
MCP → CLI → moteur
    ↓
résolution projet dupliquée
    ↓
persistance concentrée
    ↓
rechargements snapshot répétés
    ↓
scans mémoire systématiques
    ↓
qualité / CI / documentation non verrouillées
```

Cible finale :

```text
frontières Maven explicites
        ↓
MinosApplication
        ↓
services applicatifs communs
        ↓
ProjectResolver
        ↓
persistance décomposée
        ↓
SnapshotQueryView en cache
        ↓
indexes reconstruisibles
        ↓
CLI / API / MCP / NEXUS
        ↓
JaCoCo + CI PR + docs calculables
```

## Lecture de l'avancement

- ✅ = implémenté et couvert par la qualification finale du head exact ;
- les qualifications intermédiaires S1–S6 restent attachées aux SHA enregistrés dans leurs PR ;
- la qualification intégrale S7–S11 est attachée au head exact de la PR #62 ;
- un nouveau commit invalide toujours la qualification exacte d'un head antérieur.

| Étape | Fonction | État | Gate principale |
|---|---|---:|---|
| M15-S1 | Baseline de non-régression | ✅ | replays M14 + mesures de référence |
| M15-S2 | Maven multi-module | ✅ | reactor vert + frontières compilées |
| M15-S3 | `MinosApplication` | ✅ | composition root unique |
| M15-S4 | Découplage MCP | ✅ | aucun appel métier MCP → CLI |
| M15-S5 | Résolution projet commune | ✅ | résolution unique et cohérente |
| M15-S6 | Persistance décomposée | ✅ | codecs/repositories séparés et compatibles |
| M15-S7 | Cache snapshot actif | ✅ | chargement réutilisé + promotion visible |
| M15-S8 | Indexes de requête | ✅ | résultats identiques, scans structurants supprimés |
| M15-S9 | Qualité continue | ✅ | JaCoCo + gates ciblées |
| M15-S10 | CI de PR | ✅ | `clean verify` automatique Linux/Windows |
| M15-S11 | Cohérence documentaire | ✅ | facts calculables générés/vérifiés |

---

# M15-S1 — Baseline de non-régression

**Statut : ✅ terminé. PR #56.**

S1 capture le comportement M14 avant refactor : `clean verify`, replays CLI/API/MCP/providers, packaging Windows et coût d'une série de requêtes répétées.

La baseline a notamment montré que le chemin historique réalisait un `loadActiveKnowledge()` complet par requête mesurée. Cette mesure devient la référence de comparaison S7.

---

# M15-S2 — Maven multi-module

**Statut : ✅ terminé. PR #57, merge `7b064196b31a0676852a5f7effb552beb396cc8a`.**

Reactor ratifié :

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

Soit **12 modules enfants + le parent = 13 projets Maven**.

Qualification fonctionnelle S2 : SHA `637402782c29526b926968e0b8b525a2fa6fdc2c`, 238 PASS, M14/Windows/install/doctor/MCP/ownership PASS.

Décision : [ADR-0022](../adr/0022-maven-reactor-and-module-boundaries.md).

---

# M15-S3 — `MinosApplication`

**Statut : ✅ terminé. PR #58, merge `09e643007321d9dbad12c01ea1ba15612a13bd63`.**

`MinosApplication` centralise pour un `MINOS_HOME` : registry, stores, runtime provider, discovery, invalidation, architecture, impact, workspace et Git intelligence.

CLI, API et MCP peuvent partager la même instance. Les ports restent injectables pour les tests.

Qualification exacte enregistrée : `50b4d73b5dcab0edd4e86973e193bbf548d7c8f5`, 241 PASS.

---

# M15-S4 — Découplage MCP

**Statut : ✅ terminé. PR #59, merge `d317efd8de4517b23c2f87e409d38b8454fa3e92`.**

Chemin final :

```text
MCP tool
  ↓
validation / mapping protocole
  ↓
MinosApplicationMcpBackend
  ↓
services typés MinosApplication
  ↓
réponse MCP
```

La dépendance Maven `minos-mcp -> minos-cli` est supprimée. Le catalogue MCP est préservé.

Qualification exacte : `fe4d6d2b8205c1539371661854f59521571294a6`, 242 PASS.

---

# M15-S5 — Résolution projet commune

**Statut : ✅ terminé. PR #60, merge `a5566046084d9389f976534889d2961d5465b2e8`.**

`ProjectResolver` porte la sémantique UUID/nom et les diagnostics communs :

```text
PROJECT_NOT_FOUND
PROJECT_REFERENCE_AMBIGUOUS
INVALID_PROJECT_REFERENCE
```

Query, inspection, architecture, impact, import SCIP et indexation autonome utilisent la même résolution.

Qualification exacte : `572a5b4c5176823973f41a3b06a31dedaf2b702c`, 245 PASS.

---

# M15-S6 — Persistance décomposée

**Statut : ✅ terminé. PR #61, merge `3b0ae2d4df8b7a5bdbabbc1e34716eb38bd4ac13`.**

Architecture :

```text
FileSymbolSnapshotStore
        ├── SnapshotRepository
        ├── ActiveSnapshotRepository
        ├── SnapshotIntegrityService
        ├── SnapshotRetentionService
        └── SnapshotCodec
              ├── SnapshotCodecV1
              └── SnapshotCodecV2
```

Aucun changement de backend ou format disque. `.symbols`, `.knowledge`, `active.pointer`, SHA-256 et promotion atomique sont conservés.

Qualification exacte : `ecbd6f64c8d0c7ad7a875d39d8905702f8dadef9`, 251 PASS, M14/providers/Windows/install/doctor/MCP/repeated-query PASS.

Décision : [ADR-0023](../adr/0023-decomposed-local-snapshot-persistence.md).

---

# M15-S7 — Cache du snapshot actif

**Statut : ✅ terminé via PR #62.**

Identité logique :

```text
(projectId, snapshotId)
```

`FileSymbolSnapshotStore` résout d'abord le descriptor actif, puis réutilise un `SnapshotQueryView` immuable. La comparaison du descriptor complet protège le cas où le même `snapshotId` est republié avec un fichier/checksum différent.

Le cache :

- est borné en nombre d'entrées ;
- est thread-safe ;
- ne publie jamais une vue construite pour un descriptor devenu inactif ;
- relit le pointeur après construction sur cache miss ;
- voit une promotion effectuée par une autre instance du store sans dépendre d'un callback d'invalidation.

Gate S7 : la série de requêtes répétées doit produire **un seul chargement complet** et **une seule construction de vue** sur un snapshot inchangé.

---

# M15-S8 — Indexes de requête

**Statut : ✅ terminé via PR #62.**

Indexes reconstruisibles :

```text
symbolId          -> Symbol
normalizedName    -> List<Symbol>
qualifiedName     -> List<Symbol>
fileId            -> List<Symbol>
resolvedSymbolId  -> List<Occurrence>
sourceEntity      -> List<Relationship>
targetEntity      -> List<Relationship>
relationshipKind  -> List<Relationship>
```

Le snapshot persisté reste la source de vérité. Les indexes sont des dérivations mémoire immuables entre mutations et peuvent être reconstruits intégralement.

Les comparateurs, filtres, limites et ordres historiques restent appliqués après sélection des candidats indexés.

Décision S7/S8 : [ADR-0024](../adr/0024-active-snapshot-query-view-and-rebuildable-indexes.md).

---

# M15-S9 — Qualité continue

**Statut : ✅ terminé via PR #62.**

Le reactor utilise JaCoCo. `minos-app` produit le rapport agrégé :

```text
target/site/jacoco-aggregate/index.html
target/site/jacoco-aggregate/jacoco.xml
```

`scripts/quality/check-jacoco.py` impose des seuils ciblés sur :

- domaine/invariants ;
- persistance/cache/indexes ;
- résolution projet ;
- API publique ;
- mapping MCP.

Les seuils sont documentés dans [`../developer/quality-gates.md`](../developer/quality-gates.md). La couverture ne remplace pas les replays fonctionnels.

---

# M15-S10 — CI automatique des pull requests

**Statut : ✅ terminé via PR #62.**

`.github/workflows/pr-ci.yml` se déclenche sur les PR vers `main` et exécute :

```text
checkout
  ↓
Java 24 + Python
  ↓
clean verify Linux + Windows
  ↓
JaCoCo ciblé
  ↓
facts documentaires --check
  ↓
artefacts de diagnostic
```

Pour la PR finale M15, un job Windows supplémentaire exécute `scripts/m15/run-final.ps1`, y compris les replays providers et distribution.

Le workflow de release Windows reste séparé et manuel.

---

# M15-S11 — Cohérence documentaire

**Statut : ✅ terminé via PR #62.**

`scripts/docs/product-facts.py` dérive depuis le code :

- version Maven ;
- version du contrat API ;
- nombre et noms des tools MCP ;
- commandes CLI ;
- formats de sortie ;
- providers, versions et capabilities.

Le résultat est [`../generated/product-facts.md`](../generated/product-facts.md). `--check` échoue si ce fichier diverge du code.

Les documents courants (`STATUS`, `ROADMAP`, documentation développeur) sont réalignés. Les rapports historiques M0–M14 ne sont pas réécrits.

---

# Qualification finale M15

Runner :

```text
scripts/m15/run-final.ps1
```

Le runner refuse de fermer M15 si un des éléments suivants échoue :

1. head exact et worktree propre ;
2. reactor à 13 projets ;
3. frontières S2–S6 ;
4. cache et indexes S7/S8 ;
5. `clean verify` ;
6. nombre attendu de sources/tests ;
7. replay M14 complet ;
8. providers Java/TypeScript + STALE recovery ;
9. distribution Windows, installation, doctor et MCP natif ;
10. JaCoCo ciblé ;
11. facts documentaires ;
12. probe cache/indexes avec un seul full snapshot load ;
13. head inchangé à la fin du run.

Les preuves exactes (SHA, versions Java/Maven, tests, métriques cache/indexes, latences et mémoire) sont enregistrées dans la PR #62 et l'issue #55 afin de ne pas modifier le head après sa qualification.

## Gate finale M15

M15 est fermé lorsque :

- tous les replays M14 restent verts ;
- CLI/API/MCP conservent leurs résultats fonctionnels ;
- MCP n'exécute plus la CLI comme couche de service ;
- les frontières principales sont imposées par Maven ;
- le snapshot actif n'est plus désérialisé à chaque requête répétée ;
- une promotion devient immédiatement visible ;
- les snapshots historiques restent lisibles ;
- les requêtes structurantes exploitent des indexes reconstruisibles ;
- JaCoCo et quality gates sont reproductibles ;
- une CI de PR automatique protège les changements ;
- les facts documentaires calculables ne divergent plus silencieusement du code.

**Verdict attendu et exigé sur le head final :**

```text
M15 FINAL EXACT-HEAD VALIDATION SUCCESS
```

---

# Hors périmètre M15

Restent explicitement affectés aux jalons suivants :

- benchmark grands codebases et politique de rétention automatique → M16 ;
- nouveau backend persistant choisi par mesures → M16 ;
- nouveaux langages/build systems/providers → M17 ;
- plugin IntelliJ → M18 ;
- CFG/data-flow/CPG → M19 ;
- embeddings/recherche sémantique → M20.
