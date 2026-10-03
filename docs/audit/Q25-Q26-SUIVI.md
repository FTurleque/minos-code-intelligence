# Suivi du chantier Q25 et Q26 (les deux derniers résidus du sprint 7)

Chantier ouvert le 2026-10-03 sur `docs/audit/AUDIT-2026-09.md`. Deux constats de sévérité basse, tous deux résidus du
sprint 7 (la tolérance aux registres abîmés), de la même famille : **le sprint 7 a rendu l'inventaire tolérant, et le
reste du produit ne le sait pas encore.**

| ID | Constat | Lot |
|---|---|---|
| Q25 | quatre scripts traitent tout code de sortie non nul comme un échec, alors qu'ils lancent une commande par nom qui peut rendre **3** (résultat partiel) | 1 |
| Q26 | `listWorkspaces` et `findWorkspace` restent stricts : un seul fichier de projet abîmé les fait échouer | 2 |

La doctrine est celle de `CLI-SUIVI.md` § 2 (quatre conditions, une règle par famille, un seul sens pour le code 3). Elle
n'est pas réécrite ici.

## 1. Base et mesures de départ

- Base : `origin/develop` (812fd95c). Lot 1 sur `cli/q25-consommateurs`, lot 2 sur `api/q26-workspaces-tolerants`
  (empilé sur le lot 1 : le suivi est un seul fichier, deux branches indépendantes l'auraient fait entrer en conflit).
- Gates avant le premier commit : `check-module-boundaries` modules=14 sources=517 paquets=45 ; `check-private-io`
  sources=517, **liste blanche = 37** ; `check-milestone-artifact-references` scripts=113 ; `check-single-execution`
  workflows=11 ; `check-current-docs`, `product-facts --check`, `check-compose-limits`, `check-mne`, `check-post-mne` verts ;
  `test_check_private_io` 30 tests.

## 2. Lot 1 — quatre scripts : une décision par script

### 2.1 Mesure : est-ce que chaque script tourne encore ? (2026-10-03)

| Script | Ce qu'il en est | Preuve |
|---|---|---|
| `scripts/history/m17/run-final.ps1` | **irrejouable**, déjà dans l'archive | 3 des 11 fichiers qu'il exige n'existent plus (`ProjectDetector.java`, `BuildSystemDetector.java`, `CompositeProviderRuntimeManager.java`) : il s'arrête à `Required M17 file is missing` avant la première commande `minos`. Il se relance aussi par `scripts\m17\run-final.ps1`, chemin disparu |
| `scripts/m24/run-provider-e2e.py` | **échoue déjà, pour une autre raison** | `JAR = target/minos-code-intelligence-0.2.0-SNAPSHOT-all.jar` ; la révision est `1.3.0-SNAPSHOT`, le jar s'appelle `…-1.3.0-SNAPSHOT-all.jar`. `java -jar` rend 1 sur le premier appel : le script n'atteint jamais `project inspect`. Même défaut dans `m25/run-remote-e2e.py`, `m26/run-runtime-e2e.py`, `m27/run-hosted-e2e.py`. **Gelé par assertion** (ADR 0043 § 1) par `check-polyglot-provider-consistency.py` (l. 112, 379) et appelé par `m24/run-final.{ps1,sh}` |
| `scripts/m14/validate-local.ps1` | vivant par référence (appelé par `m15/capture-baseline.ps1`), non exécutable de bout en bout ici (Maven complet, tête exacte, worktree propre, fournisseurs réels) | `Invoke-MinosJson` (l. 103-112) : `throw` sur tout non-zéro ; `index-status m14-java` (l. 243) est la seule commande qui peut rendre 3 |
| `scripts/m29/run-s5.ps1` | vivant par assertion (`M29S3RunnerPowerShellHostContractTest` en lit des chaînes, `run-s4.ps1` en contrôle le parsing), non exécutable ici (Docker Desktop, tête exacte) | `Assert-NativeSuccess` (l. 52-57) : `throw` sur tout non-zéro ; `project list` (l. 138) et `index-status m29-s5-polyglot` (l. 186) peuvent rendre 3 |

### 2.2 Décisions (écrites avant le code)

| Script | Issue | Pourquoi |
|---|---|---|
| `history/m17/run-final.ps1` | **laissé, nommé** (déjà archivé) | Il est dans le répertoire d'archive, hors du périmètre du gate de références, et il est irrejouable : lui ajouter `{0, 3}` serait un correctif sur un script qui n'atteint jamais la commande. L'archiver n'a rien à ajouter, il l'est |
| `m24/run-provider-e2e.py` | **laissé, nommé** | Un correctif de code de sortie sur un script qui échoue avant la première commande est cosmétique. Ce qu'il faudrait pour trancher : (a) décider si M24 e2e est un outil de rejeu à restaurer (résoudre le jar par motif, comme `m14` le fait, dans les quatre `*-e2e.py` d'un coup) ou un jalon à archiver en réécrivant `check-polyglot-provider-consistency.py` pour qu'il n'asserte plus ce fichier (ADR 0043, « gelés par assertion ») ; (b) ensuite seulement, lire la liste de commandes de § 2.3 depuis Python. Ce constat nouveau part dans « à traiter plus tard » |
| `m14/validate-local.ps1` | **corrigé** | Vivant, référencé, et la correction tient en un appel : `Invoke-MinosJson` accepte `{0, 3}` pour les commandes de la liste unique, avertit en clair, et garde tout autre non-zéro comme un échec |
| `m29/run-s5.ps1` | **corrigé** | Idem : `Assert-NativeSuccess` reçoit les codes acceptés, `Invoke-AdminJson` les demande à la liste unique. `Invoke-QueryJson` n'est pas touchée : elle ne lance que `semantic status` et `hybrid status`, qui ne rendent jamais 3. **Exception écrite** : `fresh project list` reste strict. Un registre « neuf » dont l'inventaire est partiel n'est pas neuf : le 3 y est un échec, pas un résultat à accepter |

Je n'archive ni `m14` ni `m29/run-s5` : le premier a un appelant, le second est lu par un test Java et par `run-s4.ps1`.
Les archiver demande de réécrire ces consommateurs, un chantier distinct (ADR 0043 : « gelé par assertion »).

### 2.3 Une seule liste, trois dialectes

La liste des commandes qui peuvent rendre 3 n'était écrite nulle part : elle était recopiée dans les commentaires et les
`@(0, 3)` des consommateurs (plugin IntelliJ, `mcp-lifecycle.ps1`). Elle vit maintenant dans **un fichier de données**,
`scripts/lib/partial-result-commands.json`, lu par le seul assistant PowerShell (`scripts/lib/MinosExitCode.ps1`) et par
le gate (Python). Une garde Java dans `minos-cli` (`PartialResultCommandsContractTest`) **épingle ce fichier au
comportement réel de la CLI** : chaque commande listée rend bien 3 devant un registre abîmé, et les commandes par nom qui
restent strictes ne le rendent jamais.

Le plugin IntelliJ (Gradle, hors réacteur) et `mcp-lifecycle.ps1` gardent leur traitement propre (Q23) : les
migrer vers le fichier serait un élargissement. Ils sont nommés dans « à traiter plus tard ».

## 3. Journal des commits

| Commit | Contenu | Preuve |
|---|---|---|
| `0402a947` | décisions du lot 1, script par script | mesure § 2.1 |
| `6ac53c03` | liste unique `scripts/lib/partial-result-commands.json`, assistant `MinosExitCode.ps1`, garde Java `PartialResultCommandsContractTest` | garde bidirectionnelle (une commande de la table de lecture rend 3 si et seulement si elle est listée) ; mutation : retirer `inspect` et ajouter `architecture` la fait échouer |
| `d8cf2f70` | `m14/validate-local.ps1` et `m29/run-s5.ps1` acceptent `{0, 3}` pour les commandes listées et le disent | rouge : 3 échecs sur 9 (`'RETURNED' != 'THREW'` ×2, `-Strict` absent) ; vert : 9/9 sous PowerShell 7.6 **et** Windows PowerShell 5.1 |
| `40907248` | gate consultatif `check-partial-result-consumers.py`, branché dans `pr-ci.yml` | rouge démontré sur les scripts d'origine (il nomme `m14` l. 243 et `run-s5` l. 138, 186) |
| `9ba4de1a` | **V1** : `run-s5.ps1` charge réellement l'assistant ; gate durci (V2, V3) ; Javadoc fusionnées (V4) | `ConsumerWiringTests` : rouge démontré sur `run-s5` sans dot-source, vert avec ; 10 tests sous les deux hôtes |
| `f3b725b5` | W1 documenté, W3 corrigé | gate et self-test verts |

## 4. Constats de `verif-q`

| Id | Fichier:ligne | Ce qui casse | Scénario | Sévérité | Résolution |
|---|---|---|---|---|---|
| V1 | `scripts/m29/run-s5.ps1:126,128` | `Invoke-AdminJson` appelait des fonctions de l'assistant que le script ne chargeait jamais | dès `project add` (2e appel admin), `CommandNotFoundException` sous `$ErrorActionPreference = 'Stop'` : régression, le script allait au bout avant Q25. Le harnais de test chargeait l'assistant à la place du script | **bloquant** | corrigé : dot-source ajouté ; `ConsumerWiringTests` exige, par l'AST, un dot-source réel dans chaque script (rouge démontré sur run-s5 sans le dot-source) |
| V2 | `check-partial-result-consumers.py` | une mention en commentaire valait prise en charge (cause de V1 : faux négatif du gate) | `# voir partial-result-commands.json` + `if ($LASTEXITCODE -ne 0) { throw }` passait | à corriger | corrigé : commentaires ignorés, dot-source réel exigé pour un `.ps1` ; cas « commentaire seul » au self-test ; démontré sur run-s5 sans dot-source |
| V3 | idem, `KNOWN_GAPS` | « ne fait que rétrécir » n'était garanti par rien | une PR future ajoute son script avec une raison de complaisance | à corriger | corrigé : `GAP_CEILING` littéral ; toute entrée hors plafond échoue ; le plafond est une convention de revue, dite dans la docstring |
| V4 | `LazyWiringGuardTest.java:42-48` | deux Javadoc empilées, la première orpheline | — | à corriger | fusionnées |
| V5 | `pr-ci.yml` | « consultatif » n'est vrai que par l'état du ruleset (pas de `continue-on-error`) | un faux positif rougit le job `Static invariants` | remarque | dit tel quel dans la PR |
| V6 | docstring du gate | angles morts non cités (workflows non scannés, fenêtre de 2 lignes, faux positif d'un mot seul quoté) | — | remarque | ajoutés à la docstring |
| W1 | `check-partial-result-consumers.py:56-61` (2e passe) | seules les lignes entières commençant par `#` sont ignorées : un dot-source dans `<# #>`, un here-string ou un docstring Python compte comme prise en charge | un script strict qui contient `<# . x\MinosExitCode.ps1 #>` passe | à corriger | **documenté** dans la docstring (« ce qu'il ne détecte pas ») : le gate est déclaré heuristique et consultatif ; l'exiger par l'AST est le durcissement possible |
| W2 | idem `:56` | `\.\s+` exige un espace après le point : `.(Join-Path …)` est un faux positif | — | remarque | laissé (faux positif, pas dangereux) |
| W3 | idem `:119` | les commandes citées en commentaire comptaient comme lancées | `# minos inspect foo` seul faisait échouer | remarque | corrigé : les commentaires sont retirés avant de chercher les commandes (le décompte de lignes est conservé) |
| W4, W5 | self-test, liste du test d'AST | chemin `GAP_CEILING` et cas W1 non couverts au self-test ; liste de deux scripts en dur dans le test | — | remarque | laissés, nommés ; numéros de ligne de V1 corrigés |
| V8 | `MinosExitCode.ps1` | fins de ligne LF dans le worktree | — | remarque | artefact de checkout : `*.ps1` est `eol=crlf`, Git normalise |

## 5. À traiter plus tard

- **Les quatre `*-e2e.py` (`m24` à `m27`) visent un jar qui n'existe plus** (`0.2.0-SNAPSHOT`). Voir § 2.2.
- **Le plugin IntelliJ et `docker/scripts/mcp-lifecycle.ps1` portent leur propre `{0, 3}`** (Q23). Les faire lire
  `scripts/lib/partial-result-commands.json` demande un lecteur côté Gradle ; hors périmètre.

## 6. Résultats de `clean verify`, gates, PR

| Lot | `clean verify` | Gates | PR |
|---|---|---|---|
| 1 | `./mvnw clean verify` : **BUILD SUCCESS** (14 modules, 12 golden de caractérisation inchangés, 0 échec, 0 ignoré) ; `check-jacoco.py` SUCCESS | `check-module-boundaries` modules=14 sources=517 paquets=45 ; `check-private-io` sources=517, **liste blanche = 37** ; `check-milestone-artifact-references` 117 (113 + json, ps1, test, gate) ; `check-single-execution` workflows=11 ; `check-current-docs`, `product-facts --check`, `check-workflow-pins`, `check-compose-limits`, `check-mne`, `check-post-mne`, `check-audit-remediation-v2` verts ; `check-partial-result-consumers` + `--self-test` verts | [#331](https://github.com/FTurleque/minos-code-intelligence/pull/331) |
| 2 | | | |
