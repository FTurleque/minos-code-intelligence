# Design

## Context

Voir `proposal.md` (Pourquoi, constats, dépendance à `diagnostiquer-statut-mcp-et-erreurs`). État du code au HEAD, relu pendant la rédaction :

- **Les visiteurs fautifs.** `ProjectDiscoveryService.discoverModuleRoots` : `visitFileFailed` fait `throw exception` après `budget.accountTraversalEntry()` ; seuls les répertoires durcis sont écartés, dans `preVisitDirectory`. `ProjectFingerprintService.captureScope` : aucun override de `visitFileFailed`, donc le comportement par défaut de `SimpleFileVisitor` relance. `ProjectIgnorePolicy.scanVisibleFileNames` : idem. Le JDK ouvre le répertoire **avant** `preVisitDirectory` : un répertoire dont `newDirectoryStream` échoue va directement à `visitFileFailed`, jamais à `preVisitDirectory`, même durci ou ignoré.
- **Lacunes de la fiche D-01** (constatées en lisant tous les parcours) : (a) `ProviderWorkspaceFiles.copyWorkspace` (module `minos-runtime-local`, copie du projet vers l'espace de travail du provider, appelée par `LocalProviderWorkspace.create`) a le même `visitFileFailed` qui relance : sans le corriger, `minos index` continue d'échouer après la découverte et l'empreinte, ce qui contredit l'impact décrit par la fiche (« l'indexation devient impossible ») ; (b) `ProjectFingerprintService.toolingDirectory` (parcours de `.mvn/`…) n'a pas non plus de `visitFileFailed` ; (c) `BoundedProviderSourceProbe` et le staging de `ScipJavaProcessPlanFactory` (module `minos-provider-scip`) relancent aussi, mais parcourent *a priori* la racine isolée (copie) et non le projet enregistré : à vérifier en tâche 1.5, et traités si ce n'est pas le cas.
- **Statut.** `ProjectInspectionService.view()` appelle `discoveryService.discover(...)` sans protection : `minos_index_status` et `project inspect` héritent de la correction de la découverte (le rendre léger est D02/D03, hors périmètre).
- **Taxonomie publique.** `MinosApiSupport.execute` classe `AccessDeniedException` en `ErrorCode.ACCESS_DENIED` et toute autre `IOException` en `IO_FAILURE`. Le **type** de l'exception relancée fait donc partie du contrat public (API Java, MCP) : le message actionnable ne doit pas changer le type.
- **BOM.** `ProjectIgnoreRules.readRules` lit en UTF-8 via `InputStreamReader` ; `String.strip()` ne retire pas U+FEFF. Un seul parseur sert la découverte, l'empreinte, la copie du provider (`ProviderWorkspaceFiles`) et le staging scip-java : un seul site à corriger.
- **Ordre découverte/empreinte.** `LocalAutonomousIndexOperations.prepare` : `discover(...)` puis `fingerprintService.capture(...)` ; `IncrementalIndexingCoordinator.refreshLocked` : même ordre. Après le run, `stable = before.equals(after)` prouve l'absence de changement après `before`, pas depuis la découverte.
- **Couverture.** Aucun scope de `scripts/quality/check-jacoco.py` ne cible `com/minos/discovery/` ni `com/minos/incremental/`.

## Goals / Non-Goals

**Goals:**

- Un seul point de décision, partagé, pour « que faire d'un chemin que le parcours n'a pas pu ouvrir » : écarter si ignoré ou durci, échouer de façon actionnable sinon, échouer si c'est la racine.
- Conserver exactement le type d'exception, donc la classification publique.
- Un seul correctif de lecture des fichiers d'ignore (BOM).
- Fermer D06 sans rien changer d'autre que l'ordre de deux appels.

**Non-Goals:**

- Ne pas rendre la découverte paresseuse ni le statut léger (D02/D03).
- Ne pas éviter la descente dans les répertoires ignorés (D11 ; nécessiterait de traiter les règles de négation).
- Ne pas tolérer un fichier régulier non ignoré illisible : l'empreinte doit le lire.
- Ne pas résoudre la sémantique de casse sous NTFS, ni D12 (questions ouvertes).

## Qualification des capacités (règle capability-honesty)

| Capacité | Qualification | Justification |
|---|---|---|
| Tolérance aux répertoires illisibles ignorés ou durcis (découverte, empreinte, inventaire, copie provider) | **qualifiée** (Linux et Windows) après livraison, sous réserve d'un compte non privilégié | tests réels avec contrôle « le répertoire est-il réellement illisible » ; ignorés quand le compte est root/administrateur |
| Tolérance aux répertoires illisibles **non** ignorés | **non supportée** : échec fail-closed assumé, message actionnable | décision de produit en attente (Question ouverte 1) |
| Retrait du BOM UTF-8 | **qualifiée** (toutes plateformes) | test unitaire déterministe |
| Casse des motifs d'ignore sous NTFS | **non supportée** | Question ouverte 3 |
| Couverture partielle déclarée pour `NO_CHANGES` (liens, répertoires durcis) | **non supportée** | Question ouverte 2 (D12) |
| Capture avant découverte | **qualifiée** | test déterministe par détecteur injecté |

## Décisions

### 1. Un point de décision partagé, dans `ProjectIgnoreRules`

Les quatre visiteurs (`ProjectDiscoveryService`, `ProjectFingerprintService` y compris `toolingDirectory`, `ProjectIgnorePolicy.scanVisibleFileNames`, `ProviderWorkspaceFiles.copyWorkspace`) délèguent à la même décision. `ProjectIgnoreRules` (`com.minos.source`, public, dans `minos-engine`, déjà partagé par discovery, incremental et `minos-runtime-local`) porte la logique pure, sur le chemin relatif :

- le chemin est la racine (relatif vide) ⇒ relancer l'exception d'origine ;
- sinon, *ignoré ou durci* = `isHardIgnored(rel) || isIgnored(rel, directory)` ; si le type est inconnu (la lecture des attributs échoue aussi), être ignoré sous l'une ou l'autre interprétation suffit à écarter ;
- écarté ⇒ `CONTINUE` ; sinon ⇒ échec actionnable.

`ProjectIgnorePolicy` (qui détient déjà `reportUnreadable`, traces dédupliquées et bornées à 10 + synthèse, chemin relatif et classe d'exception uniquement) ajoute la trace pour les trois visiteurs de `minos-engine`. Elle expose une méthode publique unique (nom à fixer à l'implémentation, par exemple `onUnreadable`) car `ProjectFingerprintService` est dans un autre paquet que `reportUnreadable` (package-private). Surface publique additive. `ProviderWorkspaceFiles` n'a pas de `ProjectIgnorePolicy` : il appelle la décision de `ProjectIgnoreRules` et journalise lui-même une trace bornée de la même forme.

**Alternatives écartées :** *dupliquer la logique dans chaque visiteur* (quatre copies divergentes : le dépôt vient de supprimer d'autres duplications, Q13) ; *se contenter de `preVisitDirectory`* (n'est jamais appelé pour un répertoire illisible, voir Contexte) ; *pré-tester la lisibilité* d'un répertoire avant de descendre (TOCTOU, un `newDirectoryStream` supplémentaire par répertoire).

### 2. Le message actionnable conserve le type d'exception

Pour un répertoire non ignoré, l'échec garde le type d'origine : une `AccessDeniedException` reste une `AccessDeniedException`, construite avec le chemin **relatif** et la suggestion dans la raison (pas le chemin absolu comme premier argument), l'exception d'origine en cause. Sinon `MinosApiSupport.execute` le reclasserait de `ACCESS_DENIED` en `IO_FAILURE` : une modification de contrat public non voulue. Une autre `IOException` d'origine garde son type de base. Contrôle : un test affirme le type et l'absence de chemin absolu dans le message.

### 3. Le BOM est retiré dans `readRules`, uniquement en tête de la première ligne

Retrait de U+FEFF au début de la première ligne lue de chaque fichier d'ignore, avant `parseRuleOrDiscard`. Indépendant de la plateforme (Git le fait partout ; la fiche disait « Windows » parce que les éditeurs Windows en sont la source). Les littéraux exigés par `check-post-mne.py` (`root.resolve(".gitignore")`, `root.resolve(".minosignore")`, `BoundedInputStream`), `check-remote-distributed-consistency.py` et `check-advanced-provider-consistency.py` restent intacts.

### 4. D06 : inverser les deux lignes, ne rien ajouter

`prepare` (CLI) et `refreshLocked` (coordinateur) capturent `fingerprintService.capture(...)` avant `discover(...)`. Raisonnement : toute structure créée entre la capture et la fin du run figure dans `after` sans figurer dans `before`, donc `before.equals(after)` est faux et la baseline n'est pas promue ; l'effet conservateur est un `FULL` à la prochaine exécution (ADR 0014 : « les empreintes décrivent ce qui a été indexé »). Aucun nouveau code. La seule contrepartie est qu'une empreinte légèrement plus ancienne que la découverte sert au plan d'invalidation (`assess(…, current, discovery)`) : l'invalidation est alors plus conservatrice, jamais moins.

## Plateformes (Windows et Linux)

| Sujet | Linux | Windows |
|---|---|---|
| Rendre un répertoire illisible | permissions POSIX vides (`Set.of()`) | ACE de refus `LIST_DIRECTORY` pour l'utilisateur courant (alias de `READ_DATA` dans le JDK) |
| Contrôle « réellement illisible » | `Files.newDirectoryStream` doit lever `AccessDeniedException` ; sinon (root) le test est ignoré | idem (administrateur élevé : ignoré) |
| Restauration | permissions `rwx` rétablies dans un `finally`, sinon `@TempDir` ne peut pas nettoyer | ACE de refus retirée dans le même `finally` |
| BOM | identique | identique (source principale du problème) |
| Chemins relatifs dans les messages | séparateur `/` | séparateur `\` normalisé en `/` pour les messages, comme `portable(...)` ailleurs |

**Limite de preuve.** Les tests d'illisibilité sont **ignorés pour un compte root (Linux) ou administrateur élevé (Windows)** : sur un runner CI exécuté en root ils ne prouvent rien. Le modèle `UnreadableMarkerDiscoveryTest` / `UnreadableFile.deny` (fichiers) ne couvre pas les répertoires : `UnreadableFile.deny` ouvre un canal sur le fichier et ne convient pas à un répertoire ; l'assistant de répertoire contrôle par `newDirectoryStream`. Il est placé dans `com.minos.testsupport` (jar de tests de `minos-engine`, déjà consommé par d'autres modules pour `LogCapture`) pour servir `com.minos.discovery`, `com.minos.incremental` et `minos-runtime-local`.

## Direction des dépendances (ADR-0022)

Inchangée : la décision vit dans `minos-engine` (`com.minos.source`), consommée par `com.minos.discovery`, `com.minos.incremental` (même module) et par `minos-runtime-local` (qui dépend déjà de `minos-engine`). `minos-cli` ne reçoit que l'inversion de deux lignes. Aucune dépendance ajoutée. À rejouer : `scripts/architecture/check-module-boundaries.py`.

## Risques / Trade-offs

- **[Le correctif écarte silencieusement une source légitime illisible]** → seulement si elle est ignorée ou durcie, donc déjà exclue de l'index ; trace WARNING bornée dans les autres cas ; un répertoire non ignoré reste fail-closed.
- **[Règle de négation dans un répertoire illisible]** (`!pgdata/keep.txt`) → les fichiers qu'elle rendrait visibles ne peuvent pas être lus ; traité comme absent, trace émise. Ce n'est pas une régression : le répertoire faisait échouer le projet entier.
- **[Bruit de journal]** → une trace par répertoire écarté et par opération, bornée à 10 + 1 ; la découverte étant rejouée par chaque commande, la même ligne revient à chaque appel. Acceptable ; une trace de niveau inférieur pour les seuls répertoires déjà ignorés serait possible (non retenue : la fiche demande WARNING).
- **[Modification de contrat par le type d'exception]** → Décision 2 et test d'égalité du type.
- **[Les tests ne prouvent rien en root]** → ignorés explicitement (`Assumptions`), jamais verts par défaut ; consigné dans la tâche de clôture.
- **[Aucun scope JaCoCo sur `discovery`/`incremental`]** → les tests des tâches 1.x sont la seule garde ; Question ouverte 6.
- **[D06 : plan d'invalidation plus conservateur]** → voir Décision 4.

## Migration Plan

Aucune migration de données. Lots livrables séparément : (1) D01 (visiteurs, copie provider, message), (2) D10 (BOM), (3) D06 (ordre). Chaque lot est revertible seul. Retour arrière de D06 : restaurer l'ordre ; sans conséquence sur les baselines existantes.

## Open Questions

1. **D01 — répertoires NON ignorés illisibles : rester fail-closed ou tolérer ?** Défaut retenu : rester fail-closed avec message actionnable (cette tâche est faite). Tolérer (avertissement, exclusion, avec une mention de couverture partielle dans le résultat) est une décision de produit qui touche la promesse capability-honest : un répertoire de sources illisible deviendrait un index silencieusement incomplet. À trancher par l'utilisateur ; si oui, tâche conditionnelle 5.1 et exigence « Un répertoire illisible non ignoré échoue de façon actionnable » à modifier.
2. **D12 — `NO_CHANGES` face aux liens symboliques, jonctions et répertoires durcis.** Compter les liens non suivis et les inclure au diagnostic d'un `NO_CHANGES` (« N liens non suivis », fiche), ou documenter seulement la limite ? Aucune tâche tant que ce n'est pas tranché.
3. **D10 — casse des motifs et des noms durcis sous NTFS** (`Target/`, `Node_Modules/`, règle `build/` face à `Build/`) : Git avec `core.ignorecase=true` les ignore, MINOS non. RISQUE hors tâches bloquantes ; suppose de détecter l'insensibilité à la casse du système de fichiers et ne doit pas changer le comportement Linux.
4. **Priorité de D01** : la fiche proposait P1, le responsable a retenu P2 ; reflété dans le proposal. Sans effet sur le contenu.
5. **Descendre ou non dans un répertoire ignoré sans règle de négation** (optimisation, lié à D11) : hors périmètre ; à reprendre avec D11.
6. **Scope JaCoCo pour `com/minos/discovery/` et `com/minos/incremental/`** : en créer un (plancher à mesurer) pour ne pas dépendre des seuls tests ? Non bloquant.
7. **D06, injection dans le test CLI** : le test de la fiche (`minos-cli`, `MinosApplication.builder(home)`) suppose un point d'injection du service de découverte qui n'existe peut-être pas ; la tâche 3.1 prouve D06 au niveau du coordinateur (constructeur de paquet) et n'ajoute le test CLI que si l'injection existe déjà.
