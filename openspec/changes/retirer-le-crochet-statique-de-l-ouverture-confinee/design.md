# Design

## Context

Sprint 8 de l'audit du 10 octobre 2026 : **AUD-QUA-10**. HEAD analysé : `949e1569` (`develop`). Changement indépendant des autres du sprint.

## État vérifié au HEAD

### AUD-QUA-10 — confirmé

- `minos-engine/src/main/java/com/minos/io/ConfinedFileOpener.java:64` : `static volatile Runnable beforeOpenForTests = () -> { };` (commentaire « TEST-ONLY seam » en `:58-63`).
- Trois appels, et non un : `:131` dans `openRegularFileNoFollow` (publique, `:124`) ; `:201` dans `openThroughDirectoryHandles` (privée, `:164`) ; `:225` dans `openAndVerifyWhileHeld` (privée, `:222`). Les deux dernières servent `openConfinedRegularFile` (publique, `:92`).
- 13 occurrences du nom dans le dépôt : 4 en production ; 9 en test — `minos-engine/src/test/java/com/minos/io/ConfinedFileOpenerTest.java` (`:27` Javadoc, `:37` remise à zéro en `@AfterEach`, `:59`, `:86`, `:109`, `:128`, `:235`) et `minos-engine/src/test/java/com/minos/io/OpenRegularFileNoFollowTest.java` (`:29` remise à zéro, `:73`).
- Appelants de production des deux entrées publiques : 50 appels dans 29 fichiers.
- Gates littéraux : `scripts/remediation/check-p0-p2.py:140-141` exige dans `ConfinedFileOpener.java` les chaînes `attributes.isOther()` et `does <em>not</em> claim the same ancestor-identity proof` ; `:335` et `scripts/remediation/check-audit-remediation-v2.py:143,153` exigent, **chez des appelants**, `ConfinedFileOpener.openConfinedRegularFile(sourceRoot, relative)`, `ConfinedFileOpener.openRegularFileNoFollow(file)` et `ConfinedFileOpener.openConfinedRegularFile`. Aucun ne cite `beforeOpenForTests`.
- Aucune configuration de parallélisme JUnit dans le dépôt (recherche de `junit.jupiter.execution.parallel` et de `<parallel>` dans les `pom.xml` et fichiers de propriétés : aucun résultat).
- Q15 figure « CONFIRMÉ » dans `docs/audit/archive/2026-09/AUDIT-2026-09.md:531`, fichier **archivé**.

**Non vérifié** : aucun build ni test exécuté. L'affirmation de l'audit selon laquelle le constructeur de `IndexingExecutionRequest` ne fait plus d'E/S n'a pas été relue ici.

### Écarts avec l'action proposée par l'audit

1. **Une seule surcharge ne suffit pas.** L'audit propose `openRegularFileNoFollow(path, Runnable beforeOpen)` ; le crochet sert aussi les deux chemins de `openConfinedRegularFile` (`:201`, `:225`) ; la répartition des tests entre les deux entrées n'a pas été relue (tâche 1.1). Il faut une surcharge par entrée publique, et le paramètre doit descendre dans les deux méthodes privées.
2. **L'impact « tests parallèles » est théorique aujourd'hui** : rien n'active le parallélisme. Le défaut réel est un état global modifiable dans une primitive de sécurité, et la dépendance des tests à un `@AfterEach`.
3. **« Mettre à jour Q15 » ne peut pas viser l'archive.** La mise à jour se fait dans `docs/audit/2026-10-10/SUIVI.md` (partie E/S du record close selon l'audit, partie crochet close par ce changement).

## Goals / Non-Goals

**Goals** : aucun état statique modifiable dans `ConfinedFileOpener` ; les tests de remplacement restent déterministes (pas de course contre une horloge) ; l'API publique et les gates littéraux ne bougent pas.

**Non-Goals** : supprimer le point d'injection (les tests de remplacement entre contrôle et ouverture en dépendent) ; revoir les contrôles de confinement.

## Decisions

### D1. Deux surcharges package-private, crochet en paramètre

`openConfinedRegularFile(Path root, Path relative, Runnable beforeOpen)` et `openRegularFileNoFollow(Path file, Runnable beforeOpen)`, toutes deux package-private. Les méthodes publiques délèguent avec une constante `private static final` qui ne fait rien. `openThroughDirectoryHandles` et `openAndVerifyWhileHeld` reçoivent le crochet en paramètre. Le crochet est appelé exactement là où il l'est aujourd'hui : après la dernière validation, avant l'ouverture.

Écartée : un champ d'instance et une instance par appel — `ConfinedFileOpener` est une classe utilitaire à constructeur privé, et 50 sites d'appel statiques en dépendent.

### D2. Un test de structure garde l'invariant

Dans `minos-engine`, un test par réflexion : aucun champ déclaré de `ConfinedFileOpener` n'est à la fois `static` et non `final`. Il est rouge au HEAD (le champ `:64`). Il ne lit pas le source : il ne casse pas à un renommage.

### D3. Aucune chaîne exigée par un gate n'est touchée

Les chaînes de `check-p0-p2.py:140-141` restent dans le fichier ; les expressions d'appel à deux et un argument des appelants (`:335`, `check-audit-remediation-v2.py:143,153`) ne changent pas puisque les signatures publiques sont conservées.

## Direction des dépendances

Changement interne au package `com.minos.io` de `minos-engine` ; aucun import nouveau (ADR 0022 respecté).

## Qualification des capacités

Ouverture confinée sans suivi de lien : qualifiée, **inchangée**. Aucune capacité nouvelle.

## Windows et Linux

Les deux chemins sont concernés : `openThroughDirectoryHandles` quand le système fournit un `SecureDirectoryStream` (Linux), `openAndVerifyWhileHeld` sinon (cas attendu sous Windows ; le choix se fait à l'exécution, `:100-105`). Les tests de remplacement existants s'exécutent sur les deux systèmes de la CI ; ceux qui créent des liens symboliques peuvent être sautés sous Windows sans le privilège requis (à relever en tâche 1.1).

## Risks / Trade-offs

- **Surcharges package-private appelables depuis le package** : un code de `com.minos.io` peut encore passer un crochet, mais pour son propre appel seulement, plus pour toute la JVM.
- **Couverture du nouveau code** : les lignes modifiées de `ConfinedFileOpener` sont du code neuf pour SonarCloud (seuil 80 % sur `minos-engine`) ; les deux classes de test existantes les parcourent sur les deux chemins, à vérifier par le rapport.
- **Régression silencieuse d'un test de remplacement** : si un test passait désormais un crochet à la mauvaise entrée, il ne prouverait plus le refus. Chaque test migré garde son assertion de refus et son assertion « le crochet a bien été exécuté ».
