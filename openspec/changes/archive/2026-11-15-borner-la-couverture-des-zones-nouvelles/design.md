# Design

Changement d'outillage (table d'un gate Python et sa documentation) : seules les sections Contexte, État vérifié au HEAD, Écarts, Décisions, Vérification et Traçabilité sont remplies. Les autres sont non applicables : aucun composant, modèle, traitement, format persisté ni surface du produit ne change.

## Contexte

Motivation : `proposal.md`. `scripts/quality/check-jacoco.py` lit le rapport agrégé `target/site/jacoco-aggregate/jacoco.xml` produit par `minos-app` et évalue une table `SCOPES` : préfixes de classes, plancher de lignes et de branches (nombre, ou dictionnaire `linux` / `windows`), exclusions, planchers de classe (`prefixMinimums`). Un préfixe qui ne désigne aucune classe fait échouer le scope ; un scope sans plancher pour le système courant échoue (`controles-de-fusion › Un scope sans plancher pour le système courant échoue`). Le gate tourne une fois par système dans `pr-ci.yml` (lignes 429 et 465) et son auto-test dans `invariants` (ligne 206).

## État vérifié au HEAD

HEAD `89be3fb4`. Aucun build n'a été lancé : les mesures de couverture ne sont pas relevées ici.

| Fait | Preuve |
|---|---|
| Le scope `global` couvre `com/minos/` hors `com/minos/app/`, planchers Linux 0,84 / 0,69 et Windows 0,81 / 0,67 | `scripts/quality/check-jacoco.py:19-24` |
| Aucun scope ne nomme `explorer`, `wsl`, `Budget` ni `Projection` ; `semantic` n'apparaît que pour `FileSemanticVectorStore`, `OllamaEmbeddingProvider`, cinq classes de recherche hybride et deux API | `scripts/quality/check-jacoco.py:96-108` et recherche sur le fichier entier |
| `minos-explorer` : 9 classes, 1 291 lignes ; `RequestGuard` 77 l., `SessionToken` 54 l., `ListeningPolicy` 105 l. | `minos-explorer/src/main/java/com/minos/explorer/` ; contrôle hôte / origine / jeton : `RequestGuard.java:67-76` |
| `minos-explorer` entre dans le rapport agrégé | `pom.xml:36`, `minos-app/pom.xml:29`, `minos-app/pom.xml:72` |
| Voie WSL2 : 19 fichiers, 1 899 l. sous `com/minos/runtime/local/wsl/` ; 4 fichiers `Wsl*` (120 l.) dans `com/minos/application/` ; 3 fichiers `LocalWsl*` (283 l.) dans `com/minos/bootstrap/` ; `com/minos/cli/WslWorkerLaunch` (65 l.) ; total 27 fichiers, 2 367 l. | décompte `wc -l` au HEAD |
| Projection 2D : `PrincipalAxes` 189 l., `ProjectionPreparation` 245 l., `SemanticProjectionService` 273 l., `FileSemanticProjectionStore` 310 l. (1 017 l.) ; s'y ajoutent `SemanticProjectionCommand` 214 l. et les types du domaine `SemanticProjection` 123 l., `SemanticProjectionStore` 60 l. | `minos-application/src/main/java/com/minos/application/semantic/`, `minos-storage/src/main/java/com/minos/storage/local/store/`, `minos-cli/src/main/java/com/minos/cli/command/`, `minos-domain/src/main/java/com/minos/semantic/` |
| `QueryViewMemoryBudget` : 327 l., testée par `QueryViewMemoryBudgetTest` | `minos-storage/src/main/java/com/minos/storage/codec/QueryViewMemoryBudget.java` |
| Des tests de la voie WSL2 sont restreints à un système | `@EnabledOnOs` dans `WslLinuxWorkerTest`, `LocalWslIndexingRouteTest`, `IndexCommandViaWslTest`, `DoctorCommandWslJavaTest` |
| L'auto-test du gate vérifie déjà préfixe mort, exclusion sans objet, plancher par système | `scripts/quality/check-jacoco.py:366-537` |
| Une clé de `prefixMinimums` doit figurer aussi dans `prefixes` : sinon `prefix minimum references undeclared prefix` et échec ; le préfixe mort n'est détecté que pour un préfixe déclaré | `scripts/quality/check-jacoco.py:614-626`, `:640-644` ; modèle `:43-47` (`com/minos/io/` et `com/minos/io/LeaseDeadline`) |
| Un plancher de classe est un nombre unique, sans variante par système | `scripts/quality/check-jacoco.py:649-650` (`float(minimums["line"])`) |
| Règle écrite des planchers de classe : « mesure moins environ 5 points » | `docs/developer/quality-gates.md:225` ; appliquée `scripts/quality/check-jacoco.py:39-40` |
| Le résultat `jacoco-gate.json` est écrit dans `target/m21-quality/`, dossier que l'artefact `pr-validation` ne publie pas ; le journal du job porte une ligne `JaCoCo <scope>: <statut> line=…` par scope | `scripts/quality/check-jacoco.py:542`, `:692` ; `.github/workflows/pr-ci.yml:491-495` |
| `com/minos/cli/ExplorerCommandLine` et `com/minos/cli/ExplorerLaunchRoute` portent le lancement de l'explorateur côté CLI | `minos-cli/src/main/java/com/minos/cli/` |

## Mesure de référence (tâche 1.1)

Run `pr-ci.yml` 43650742311 (`push` sur `main`, commit `89be3fb4`, 2026-11-14), artefacts `pr-validation-Linux-1` et `pr-validation-Windows-1`, rapport agrégé `target/site/jacoco-aggregate/jacoco.xml` de chaque job `Verify`. Chaque préfixe désigne au moins une classe mesurée (E5 levé) ; `com/minos/application/Wsl` désigne 6 classes à 12 lignes et aucune branche.

| Périmètre | Linux ligne / branche | Windows ligne / branche | Plancher retenu |
|---|---|---|---|
| `explorer-surface` (45 classes) | 0,950 (958/1008) / 0,882 (456/517) | identique | 0,93 / 0,86 |
| `com/minos/explorer/RequestGuard` | 1,000 (26/26) / 1,000 (18/18) | identique | 0,95 / 0,95 |
| `com/minos/explorer/SessionToken` | 1,000 (13/13) / 1,000 (4/4) | identique | 0,95 / 0,95 |
| `com/minos/explorer/ListeningPolicy` | 0,821 (23/28) / 1,000 (24/24) | identique | 0,77 / 0,95 |
| `wsl-lane` (34 classes) | 0,941 (822/874) / 0,847 (365/431) | 0,922 (806/874) / 0,833 (359/431) | Linux 0,92 / 0,82 ; Windows 0,90 / 0,81 |
| `semantic-projection` (35 classes) | 0,979 (654/668) / 0,887 (236/266) | identique | 0,95 / 0,86 |
| `storage-codec-and-state` avec `QueryViewMemoryBudget` (24 classes) | 0,924 (1660/1796) / 0,797 (509/639) | 0,924 / 0,798 (510/639) | inchangé : 0,89 / 0,73 |
| `com/minos/storage/codec/QueryViewMemoryBudget` | 0,943 (115/122) / 0,868 (59/68) | identique | 0,89 / 0,81 |

Le gate modifié, rejoué sur ces deux rapports, rend `PASS` pour les trois scopes, le scope `storage-codec-and-state` et les quatre planchers de classe (les autres échecs de ce rejeu viennent du poste : scopes de l'autre système, rapport de `minos-app` absent, `m30-postgresql-pgvector` sans `--skip-scope`).

## Écarts

| Écart | Source A | Source B | Suite proposée |
|---|---|---|---|
| E1 — L'audit propose un plancher unique par scope ; la voie WSL2 a des tests restreints à un système, donc deux mesures | action de AUD-TST-01 | `@EnabledOnOs` des tests WSL | plancher `linux` / `windows` pour `wsl-lane`, et pour tout scope dont les deux mesures diffèrent de plus de 2 points (D2) |
| E2 — Les préfixes proposés pour `wsl-lane` oublient `com/minos/cli/WslWorkerLaunch`, compté dans les 2 367 lignes | action de AUD-TST-01 | décompte au HEAD | l'ajouter au scope (D3) |
| E3 — `SemanticCorpusQueryService` (service derrière toutes les routes de l'explorateur) et `SemanticIndexBudget` ne sont dans aucun scope nominatif et ne sont pas cités par l'audit | action de AUD-TST-01 | `scripts/quality/check-jacoco.py:98-103` | les rattacher à `explorer-surface` (D3) : le sprint 4 modifie ce service |
| E4 — Ajouter `QueryViewMemoryBudget` aux préfixes de `storage-codec-and-state` change la mesure d'un scope dont le plancher (0,89 / 0,73) vient d'une mesure du 2026-10-15 | action de AUD-TST-01 | `scripts/quality/check-jacoco.py:26-35` | recalculer la mesure du scope avec la classe et ne pas baisser le plancher sans l'écrire ; la classe porte de toute façon son plancher propre (D4) |
| E5 — Les interfaces sans code (`WslIndexingRoute`, `WslCommandRunner`…) n'ont pas d'entrée de classe mesurable : un préfixe qui ne viserait qu'elles serait mort | forme des préfixes proposés | règle du préfixe mort du gate | chaque préfixe retenu désigne au moins une classe à lignes exécutables ; vérifié sur le rapport de la CI en tâche 1.1 |

## Décisions

### D1 — Trois scopes nouveaux, un rattachement
[Décision] `explorer-surface`, `wsl-lane`, `semantic-projection` sont des scopes nouveaux ; `QueryViewMemoryBudget` rejoint `storage-codec-and-state`. Écarté : un scope unique « zones nouvelles », qui laisserait une zone compenser l'autre.

### D2 — Planchers : scope à la mesure moins 2 points, classe à la mesure moins environ 5 points
[Décision] Les deux règles sont celles du dépôt. Plancher de **scope** : mesure du rapport agrégé de la CI moins 2 points (`scripts/quality/check-jacoco.py:17-18`), en dictionnaire `linux` / `windows` quand les deux mesures diffèrent. Plancher de **classe** : mesure moins environ 5 points (`docs/developer/quality-gates.md:225`), en **un seul nombre** — le gate n'accepte pas de plancher de classe par système — calculé sur la plus basse des deux mesures. La mesure est lue dans le rapport agrégé de chaque job `Verify` d'un même commit de `main` ; le commentaire du scope cite le run, le commit et les mesures. Aucune valeur n'est fixée dans la spécification : elle sort de la tâche 1.1.

### D3 — Périmètre des scopes
[Proposition, recommandée]
- `explorer-surface` : préfixes `com/minos/explorer/`, `com/minos/explorer/RequestGuard`, `com/minos/explorer/SessionToken`, `com/minos/explorer/ListeningPolicy`, `com/minos/application/semantic/SemanticCorpusQueryService`, `com/minos/application/semantic/SemanticIndexBudget` ; `prefixMinimums` sur les trois classes de garde. Chaque classe plancherisée est répétée dans `prefixes`, comme `com/minos/io/LeaseDeadline` : une clé de `prefixMinimums` hors de `prefixes` fait échouer le scope, et c'est sa présence dans `prefixes` qui fait détecter la classe disparue.
- `wsl-lane` : `com/minos/runtime/local/wsl/`, `com/minos/bootstrap/LocalWsl`, `com/minos/application/Wsl`, `com/minos/cli/WslWorkerLaunch`.
- `semantic-projection` : `com/minos/application/semantic/PrincipalAxes`, `com/minos/application/semantic/ProjectionPreparation`, `com/minos/application/semantic/SemanticProjectionService`, `com/minos/storage/local/store/FileSemanticProjectionStore`, `com/minos/cli/command/SemanticProjectionCommand`, `com/minos/semantic/SemanticProjection`.

`com/minos/cli/ExplorerCommandLine` et `com/minos/cli/ExplorerLaunchRoute` restent au seul scope `global` : ils ne décident pas de servir une requête. Écarté : s'en tenir à la liste de l'audit (laisse `SemanticCorpusQueryService` et `WslWorkerLaunch` au seul scope global).

### D4 — Plancher de classe pour le budget mémoire
[Décision] `QueryViewMemoryBudget` entre dans `prefixes` **et** dans `prefixMinimums` de `storage-codec-and-state` : le budget ne peut pas se cacher derrière le codec. Le plancher du scope est recalculé avec la classe ; s'il doit baisser, la raison est écrite dans le commentaire du scope.

### D6 — Le résultat du gate est publié avec l'artefact de validation
[Décision] `target/m21-quality/**` est ajouté aux chemins de l'artefact `pr-validation` de `pr-ci.yml` (une ligne), pour que `jacoco-gate.json`, seul porteur des mesures par classe, soit lisible depuis une PR. Sans cela, la preuve par scope reste la ligne `JaCoCo <scope>: PASS line=…` du journal, et la preuve par classe le seul code de sortie.

### D5 — L'auto-test garde la déclaration
[Décision] `--self-test` reçoit un cas qui évalue la vraie table `SCOPES` : les trois scopes et les quatre planchers de classe y sont présents, et une table privée de l'un d'eux est refusée. Sans ce cas, retirer un scope repasse sous le seul scope global sans échec.

## Vérification

- Rouge : le cas d'auto-test de D5 échoue avant l'ajout des scopes.
- `python scripts/quality/check-jacoco.py --self-test` (poste et CI, job `invariants`).
- `python scripts/quality/check-jacoco.py` sur le rapport agrégé : seule la CI le produit sur les deux systèmes (`Verify (ubuntu-24.04)`, `Verify (windows-2022)`). Sur le poste Windows, le scope `m24-polyglot-provider-platform` est rouge hors régression : comparer à `main`.
- `python .claude/scripts/run_gates.py` : les gates de cohérence qui lisent `check-jacoco.py` (`check-vertical-decomposition-consistency.py`, `check-semantic-retrieval-consistency.py`, `check-hosted-control-plane-consistency.py`, `check-runtime-dynamic-consistency.py`, `check-java-ast-provider-consistency.py`, `check-polyglot-provider-consistency.py`, ainsi que `scripts/docs/check-current-docs.py:195-199` et `scripts/remediation/check-p0-p2.py:338-341`, qui y affirment des littéraux) restent verts.
- Non vérifiable localement : la mesure Linux des classes WSL.

## Traçabilité

| Exigence (capacité › titre) | Scénario | Conception ou diagramme | Tâche | Test ou preuve |
|---|---|---|---|---|
| controles-de-fusion › Les zones créées après la mesure de référence sont sous un scope nominatif | Les trois scopes sont évalués | D1, D3 | 1.1, 1.3, 1.5 | rejeu local du gate sur les rapports du run 43650742311 (PASS sur les deux systèmes) ; **manquant** : ligne `JaCoCo <scope>: PASS` des deux jobs `Verify` de la PR et `jacoco-gate.json` de son artefact (D6) |
| idem | Les tests d'une zone disparaissent | D2 | 1.2, 1.3 | règle existante du plancher de scope, cas « threshold breach » de `--self-test` ; aucun cas propre à `explorer-surface` |
| idem | Une zone est renommée sans son scope | règle existante du préfixe mort | 1.2 | `--self-test`, cas « dead prefix beside live » et « dead package prefix » |
| idem | Déclaration retirée du gate | D5 | 1.2, 1.3 | `--self-test`, cas « real table declares the new zones and their class floors » et « table deprived of a zone scope is refused » (`missing_zone_declarations`) |
| controles-de-fusion › La garde de la surface HTTP locale a un plancher de classe | Garde affaiblie ; Classe de garde absente | D3 | 1.2, 1.3 | `--self-test`, cas « guard class under its floor in a passing scope » et « guard class absent from the report » ; **manquant** : `jacoco-gate.json` de l'artefact de la PR |
| controles-de-fusion › Le budget mémoire des vues a un plancher de classe | Budget sous son plancher ; État du dépôt | D4 | 1.2, 1.4, 1.5 | `--self-test`, cas « real table declares… » (déclaration) et « weak live prefix » (règle du plancher de classe) ; rejeu local sur le run 43650742311 ; **manquant** : `jacoco-gate.json` de l'artefact de la PR |

## Questions ouvertes

- [Question, non bloquante] Valeurs des planchers : sorties de la tâche 1.1.
- [Question, propriétaire] D3 : inclure `SemanticCorpusQueryService` et `SemanticIndexBudget` dans `explorer-surface`. Recommandation : oui, avant le sprint 4.
