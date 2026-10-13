# Design

## Context

Sprint 8 de l'audit du 10 octobre 2026 (« Confinement et E/S privées ») : **AUD-SEC-03**. HEAD analysé : `949e1569` (`develop`). Premier changement du sprint : les appels ajoutés par les autres changements passeront sous le gate étendu.

## État vérifié au HEAD

### AUD-SEC-03 — confirmé, décomptes à corriger

- `python scripts/architecture/check-private-io.py` (exécuté) : `PRIVATE I/O PRIMITIVES GATE SUCCESS (sources=527, allowlisted-occurrences=46, forbidden=10, primitives=4)`. L'audit donnait 522 / 37 / 8.
- `scripts/architecture/check-private-io.py:54-65` : `FORBIDDEN` contient `Files.createDirectories`, `Files.write`, `Files.writeString`, `Files.newInputStream`, `Files.*`, `FileChannel.open`, `AsynchronousFileChannel.open`, `FileChannel.lock`, et deux règles de journal ajoutées depuis l'audit. `:47-52` : quatre primitives (`ConfinedFileOpener`, `DurableAtomicFile`, `BoundedFileLease`, `BoundedProperties`). `:44` : `minos-intellij` exclu.
- Liste de tolérance `scripts/architecture/private-io-allowlist.json` : 18 entrées, 46 occurrences, 16 fichiers.
- Décompte par `grep -rnE "Files\.<api>\("` dans `*/src/main/java`, hors `minos-intellij` :

| API | Occurrences | Remarque |
|---|---|---|
| `Files.newOutputStream` | 21 | audit : 23 ; dont 1 dans `PrivateLocalStorage` |
| `Files.move` | 12 | audit : 14 |
| `Files.createTempFile` | 5 | audit : 8 ; dont 2 dans `PrivateLocalStorage` (`:154`, `:280`) |
| `Files.createDirectory` | 5 | dont 3 dans `LinuxCgroupJob` (cgroupfs), 1 dans `PrivateLocalStorage` |
| `Files.copy` | 4 | |
| `Files.newBufferedWriter` | 3 | |
| `Files.createTempDirectory` | 1 | `PrivateLocalStorage` |
| `Files.newByteChannel` | 5 hors primitives | **non relevé par l'audit** ; 1 de plus dans `ConfinedFileOpener` (primitive) |
| `Files.createFile` | 1 | **non relevé par l'audit** ; `PrivateLocalStorage` |

  Total hors primitives : 43 couples (fichier, API) dans 30 fichiers. L'exemple de l'audit est décalé : `ManagedPolyglotScipRuntimeManager.java:455` (et non `:449`).
- Redirection d'un processus vers un fichier, **non relevée par l'audit** : une occurrence, `minos-runtime-local/src/main/java/com/minos/runtime/local/sandbox/WindowsAppContainerWorkerSandboxBackend.java:424` (`redirectOutput(output.toFile())`). Les autres redirections du code de production visent `ProcessBuilder.Redirect.DISCARD`.
- L'auto-test `scripts/architecture/test_check_private_io.py` (câblé dans `.github/workflows/pr-ci.yml:114`) : `:37-48` un témoin par règle (`WITNESSES`), `test_the_witnesses_cover_every_forbidden_call` impose l'égalité avec `FORBIDDEN` ; `:167-170` `test_the_similar_but_allowed_calls_are_not_caught` **affirme** que `Files.createDirectory`, `Files.newOutputStream` et `Files::newOutputStream` ne sont pas signalés.
- La capacité `journalisation` cite déjà ce gate dans un scénario (`openspec/specs/journalisation/spec.md:42`).

**Non vérifié** : aucun build ni test Java exécuté. Les décomptes viennent de `grep` : le gate, lui, ignore commentaires et littéraux et compte aussi les références de méthode et les imports statiques ; son décompte fera foi (tâche 1.1). Le décompte de 4 `createTempFile` fourni par la contre-vérification est contredit par le relevé (5).

### Écarts avec l'action proposée par l'audit

1. **`PrivateLocalStorage` n'est pas une primitive du gate.** La docstring (`check-private-io.py:8-10`) dit qu'il « n'a besoin de rien de brut », et deux cas d'auto-test le fixent (`:194-196` quatre primitives exactement, `:198-202` « ancienne primitive vérifiée comme un autre fichier »). Avec les règles nouvelles, il porte 6 occurrences (`:137`, `:154`, `:172`, `:212`, `:280`, `:321`). `.claude/rules/java-production.md:25` le range pourtant déjà parmi les primitives. Décision D2.
2. **Trois familles manquent à la liste de l'audit** : `Files.newByteChannel`, `Files.createFile`, redirection de processus vers un fichier.
3. **« Puis les migrer » n'est pas dans ce changement.** Trente fichiers de six modules : la migration se fait par lots, chacun avec ses tests. Ici, seuls le gate, la liste et l'auto-test changent.
4. **Trois occurrences ne seront jamais migrées** : `LinuxCgroupJob` crée des répertoires dans cgroupfs, qui n'est pas du stockage privé. Leur entrée de liste est définitive et le dit.
5. **`minos-intellij` reste hors gate** : ses quatre écritures brutes ne sont ni comptées ni traitées.

## Goals / Non-Goals

**Goals** : toute création ou écriture de fichier nouvelle hors des primitives fait échouer le job `invariants` ; l'existant est figé, nominativement, et ne peut que diminuer ; le gate prouve ses refus.

**Non-Goals** : migrer un site ; étendre le gate à `minos-intellij`, aux tests ou aux ressources ; reconnaître les types (le gate reste lexical, limite déjà documentée).

## Decisions

### D1. Règles ajoutées

Dans `FORBIDDEN` et `_FILES_METHODS` : `Files.newOutputStream`, `Files.newBufferedWriter`, `Files.newByteChannel`, `Files.createFile`, `Files.createDirectory`, `Files.createTempFile`, `Files.createTempDirectory`, `Files.copy`, `Files.move`. Le mécanisme existant couvre déjà l'appel, la référence de méthode (`Files::…`) et l'import statique nominatif.

Une règle de redirection, `ProcessBuilder.redirect(file)` : `Redirect.to(`, `Redirect.appendTo(`, et `redirectOutput(` / `redirectError(` dont l'argument n'est pas une constante `Redirect.DISCARD`, `Redirect.PIPE` ou `Redirect.INHERIT`. `redirectInput` (lecture) n'est pas visé.

`Files.createDirectory` ne doit pas être confondu avec `Files.createDirectories` (déjà compté) : le motif est borné par `\(`, comme aujourd'hui.

### D2. Statut de `PrivateLocalStorage` — **décision en attente du propriétaire**

Option B (recommandée) : cinq entrées de liste (`Files.createFile` 1, `Files.createTempFile` 2, `Files.createTempDirectory` 1, `Files.newOutputStream` 1, `Files.createDirectory` 1), justifiées par « primitive de création à droits privés ». Le gate garde `primitives=4`, ses deux cas d'auto-test et sa docstring (une phrase à corriger : « they need nothing raw » devient faux). Un appel brut de plus dans `PrivateLocalStorage` échoue, ce que l'option A ne garantit pas. C'est le traitement déjà appliqué à `SharedCacheLeaseRegistry`.

Option A : remettre `PrivateLocalStorage` dans `PRIMITIVES` (`primitives=5`). Plus conforme à `.claude/rules/java-production.md:25`, mais renverse une décision antérieure fixée par deux cas d'auto-test, et soustrait au cliquet la classe où une régression de droits coûterait le plus.

Dans les deux cas, `.claude/rules/java-production.md` est aligné sur la liste réelle du gate dans la même tâche.

### D3. Entrées de liste : nominatives, justifiées, rattachées à un lot

Une entrée par couple (fichier, API), au maximum égal au décompte du gate. La justification dit soit pourquoi l'appel est légitime et restera (cgroupfs ; copie d'un flux vers une cible explicite choisie par l'utilisateur), soit quel lot de migration la retirera. Les règles de validation existantes s'appliquent (jamais un dossier, jamais un joker, justification de 20 caractères au moins, entrée périmée refusée).

### D4. L'auto-test prouve le refus de chaque règle

Un témoin par règle dans `WITNESSES` (l'égalité avec `FORBIDDEN` est déjà imposée) ; un cas « référence de méthode » et un cas « import statique » sur une API nouvelle ; pour la redirection, un cas refusé (`redirectOutput(file.toFile())`) et un cas accepté (`Redirect.DISCARD`). Le cas `:167-170` est réécrit : il ne garde que les appels réellement permis (`Files.readAllBytes`, un nom voisin comme `Files.writeFoo`).

## Direction des dépendances

Sans objet : aucun module modifié, aucun import ajouté (ADR 0022 non concerné).

## Qualification des capacités

- Refus d'une écriture brute **nouvelle** dans le code de production du reactor : **non qualifiée** au HEAD pour les dix familles visées ; qualifiée par ce changement.
- Confinement des écritures **existantes** : **partielle** après ce changement (figées et comptées, pas migrées).
- `minos-intellij` : **non supportée** par le gate (hors périmètre déclaré).

## Windows et Linux

Le gate et son auto-test s'exécutent sous les deux systèmes avec la même sortie (chemins normalisés en `/`). Les entrées de `LinuxCgroupJob` (Linux seul) et de `WindowsAppContainerWorkerSandboxBackend` (Windows seul) sont comptées sur les deux : le gate lit les sources, il ne les exécute pas.

## Risks / Trade-offs

- **Faux positifs lexicaux** : un `newByteChannel` en lecture seule est signalé comme une écriture ; le gate ne lit pas les options d'ouverture. Accepté : une lecture hors `ConfinedFileOpener` suit les liens, ce que la règle `Files.newInputStream` refuse déjà.
- **Une liste longue banalise l'exception** : près de 45 entrées d'un coup. Chaque entrée nomme son lot ; le cliquet empêche d'en ajouter sans échec visible en revue.
- **Conflits de fusion** sur `private-io-allowlist.json` avec les changements parallèles qui touchent un fichier listé : le maximum d'une entrée doit être recalculé à la fusion (le gate le signale dans les deux sens).
- Les changements suivants du sprint (dont `conserver-les-refus-conditionnels-windows`) ajoutent des appels qui passeront sous ces règles : c'est l'effet recherché.
