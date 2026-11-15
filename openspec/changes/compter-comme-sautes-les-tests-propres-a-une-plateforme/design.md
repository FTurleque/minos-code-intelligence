# Design

Changement de tests et d'outillage : les sections Contexte, État vérifié au HEAD, Écarts, Décisions, Vérification et Traçabilité sont remplies. Les autres sections du modèle sont non applicables : aucun code de production ne change.

## Contexte

Motivation : `proposal.md`. JUnit compte un test restreint par `@EnabledOnOs` comme sauté (« Disabled on operating system ») ; un test qui sort par `return` est compté réussi. Le dépôt a déjà converti 21 sites de `minos-runtime-local` (changement archivé `2026-10-10-rendre-visibles-les-tests-de-confinement-sautes`) et vérifie le résultat par une commande `grep` écrite dans une exigence et dans le suivi d'audit, sans gate.

## État vérifié au HEAD

HEAD `89be3fb4`. Recherche multiligne exécutée sur toutes les sources `*/src/test/**/*.java` (hors `target/` et worktrees) : `if (` … mention du système … `)` puis `return;`, accolade et saut de ligne facultatifs.

| Fait | Preuve |
|---|---|
| Retour anticipé sur deux lignes, test Windows | `minos-runtime-local/src/test/java/com/minos/runtime/local/process/CommandLocatorTest.java:155-157` (`realWindowsBatchInvocationUsesCanonicalSystem32Cmd`), `:179-181` (`realWindowsCmdPreservesAdversarialQuotedArguments`) |
| idem | `minos-provider-scip/src/test/java/com/minos/adapter/scip/runtime/install/ManagedPolyglotScipRuntimeManagerTest.java:62-64` (`windowsReportsClangAsBlockedInsteadOfPretendingCrossPlatformSupport`) |
| idem | `minos-app/src/test/java/com/minos/app/adapter/scip/M24PolyglotProviderTest.java:120-122` (`windowsDoesNotPretendScipClangRuntimeIsReady`) |
| Retour anticipé sur une ligne, test Linux, par variable locale `os` | `CommandLocatorTest.java:88-90` (`linuxSecurityAuthorityCommandsUseRootOwnedSystemPathsInsteadOfPath`) |
| Retours anticipés de capacité `if (!createSymbolicLink(...)) return;` : 34 occurrences dans 14 fichiers de `minos-engine` et `minos-storage` | recherche au HEAD ; exemples `minos-engine/src/test/java/com/minos/storage/MinosRuntimeSettingsConfigurationFileTest.java:52`, `:63` |
| Des assistants de test rendent une valeur sous une garde de système (`return <valeur>;`), sans passage à vide | `minos-engine/src/test/java/com/minos/io/StaleScratchReclamationTest.java:241` |
| Retours anticipés hors méthode de test (`beforeAll` / `afterAll` d'une extension, outillage) | `minos-runtime-local/src/test/java/com/minos/runtime/local/sandbox/AppContainerGrantLeakCheck.java:33`, `:39` ; `minos-engine/src/test/java/com/minos/testsupport/IcaclsTestAccessControl.java:36`, `:42` |
| Règle écrite | `docs/developer/testing.md:121` ; `.claude/rules/code-review.md` (« Parité Windows et Linux ») |
| Contrôle existant : `grep -rnE 'if \(.*(currentPlatform\(\)\|isWindows).*\) *return;' minos-runtime-local/src/test` | `docs/project/audits/2026-10-10/SUIVI.md:9` ; `openspec/specs/confinement-code-non-fiable/spec.md:189-191` |
| Aucun gate Python ne lit les sources de test pour ce motif | recherche de `EnabledOnOs` dans `scripts/` : 0 fichier |

Statut du constat : **confirmé**, et plus large que décrit (cinq tests, non quatre).

## Écarts

| Écart | Source A | Source B | Suite proposée |
|---|---|---|---|
| E1 — L'audit compte quatre tests ; un cinquième existe dans le même fichier | titre de AUD-TST-04 | `CommandLocatorTest.java:88-90` | le corriger et faire reconnaître la forme « variable locale » par le gate |
| E2 — « ajouter au gate du suivi une recherche multiligne » : le suivi ne contient pas de gate, seulement une commande `grep` | action de AUD-TST-04 | `SUIVI.md:9` du 10/10 | écrire un gate Python avec auto-test, câblé dans `invariants` (D2) |
| E3 — L'expression régulière proposée refuserait quatre sites légitimes d'extensions et d'outillage de test | action de AUD-TST-04 | `AppContainerGrantLeakCheck.java:33`, `:39` ; `IcaclsTestAccessControl.java:36`, `:42` | liste fermée d'exceptions, par fichier et par nombre, hors méthode `@Test` (D3) |
| E4 — `installation-des-providers › Un test du provider propre à Windows est compté comme sauté ailleurs` est écrite, et un test du module la contredit | `openspec/specs/installation-des-providers/spec.md:121-122` | `ManagedPolyglotScipRuntimeManagerTest.java:62-64` | corriger le test ; l'exigence reste telle quelle |
| E5 — `@EnabledOnOs(OS.WINDOWS)` de classe n'est pas applicable : les trois classes mêlent tests communs et tests d'un système | action de AUD-TST-04 | lecture des trois classes | annotation au niveau de la méthode |
| E6 — Les gardes de lien symbolique passent à vide de la même façon, à 34 endroits ; l'audit ne les cite pas | périmètre de AUD-TST-04 | recherche au HEAD (34 occurrences, 14 fichiers) | hors de ce changement : constat de suivi (D4) ; en corriger une seule laisserait 33 sites identiques |

## Décisions

### D1 — `@EnabledOnOs` au niveau de la méthode
[Décision] `@EnabledOnOs(OS.WINDOWS)` pour les quatre tests Windows, `@EnabledOnOs(OS.LINUX)` pour le test Linux ; la garde et son `return` sont supprimés, le corps est inchangé. La variable locale `os` devenue inutile est retirée.

### D2 — Un gate Python, motif multiligne, tous modules
[Décision] `scripts/quality/check-test-platform-guards.py` (bibliothèque standard, `gate_cli` comme les gates voisins) parcourt `**/src/test/**/*.java` hors `target/`, `build/` et worktrees, et refuse toute instruction `if (<condition>) [{] return;` — `return` **sans valeur** : un assistant qui rend une valeur sous une garde de système ne fait passer aucun test à vide — dont la condition, ou une variable locale définie dans la même méthode à partir de `os.name`, mentionne le système (`os.name`, `isWindows`, `isLinux`, `currentPlatform`, `OS.`, `"win"`, `"linux"`, `"mac"`). Il échoue si aucun fichier n'est lu. Il affiche fichier, ligne et extrait. `--self-test` : arbres temporaires avec chaque forme refusée et chaque forme acceptée. Écarté : garder un `grep` dans le suivi (non exécuté) ; une règle ArchUnit (ne voit pas le corps des méthodes).

### D3 — Exceptions : liste fermée, hors méthode de test
[Décision] Table du gate : chemin du fichier, nombre d'occurrences, raison (« callback d'extension JUnit : rien à relever hors Windows », « outillage ACL Windows »). Le gate refuse une exception dont l'occurrence est dans une méthode annotée `@Test`, `@ParameterizedTest`, `@RepeatedTest` ou `@TestFactory`, et une exception dont le nombre ne correspond plus. Quatre occurrences au HEAD, dans deux fichiers.

### D4 — Gardes de capacité : hors périmètre, constat de suivi
[Décision] Les 34 retours anticipés sur l'échec de création d'un lien symbolique ne sont pas touchés. Le gate ne cherche pas ce motif. Le suivi de l'audit reçoit un constat : convertir ces sites en hypothèses nommées (`assumeTrue` avec message) et étendre le gate au motif `createSymbolicLink`. Écarté : en corriger un seul ici.

## Vérification

- Rouge : `python scripts/quality/check-test-platform-guards.py` échoue au HEAD en nommant les cinq sites, avant toute correction de test.
- Après correction : gate vert, `--self-test` vert, `python scripts/quality/check-ci-wiring.py` vert une fois les deux étapes ajoutées à `invariants`.
- Poste Windows : `./mvnw -B -ntp -pl minos-runtime-local,minos-provider-scip -am test`, puis `./mvnw -B -ntp -pl minos-app -am test` ; rapport Surefire de `CommandLocatorTest` : un test sauté de plus (le test Linux), les deux tests Windows exécutés.
- Linux : seule la CI (`Verify (ubuntu-24.04)`) ou WSL donne le décompte « trois tests Windows sautés de plus » ; non vérifiable sur le poste sans WSL.
- Scopes JaCoCo : `provider-execution-trust-boundary` et `m24-polyglot-provider-platform` inchangés en attendu (aucune ligne de production couverte en moins : les tests ne s'exécutaient déjà pas sur l'autre système).

## Traçabilité

| Exigence (capacité › titre) | Scénario | Conception ou diagramme | Tâche | Test ou preuve |
|---|---|---|---|---|
| controles-de-fusion › Aucun test ne passe à vide derrière un retour anticipé de plateforme | État du dépôt | D1, D2 | 1.1, 2.1, 2.2 | `python scripts/quality/check-test-platform-guards.py` (vert, 914 fichiers, 2 exceptions) |
| idem | Garde sur deux lignes ; Garde par variable locale ; Aucun fichier lu | D2 | 1.1, 1.2 | `--self-test`, cas `two-line guard`, `local variable from os.name`, `no test source` |
| idem | Test Windows sous Linux | D1 | 2.1, 2.3 | **manquant** : rapport Surefire du job `Verify (ubuntu-24.04)` (la CI n'a pas tourné) ; sous Windows, le test Linux de `CommandLocatorTest` est sauté et les deux tests Windows exécutés |
| controles-de-fusion › Les retours anticipés de plateforme tolérés sont une liste fermée et motivée | Exception devenue sans objet ; Exception dans une méthode de test | D3 | 1.2 | `--self-test`, cas `stale exception refused`, `exception in a test method refused`, `exception without reason refused` |
| idem | Auto-test câblé | D2 | 3.1, 3.2 | `check-ci-wiring.py` rouge avant câblage, vert après |

## Questions ouvertes

Aucune.
