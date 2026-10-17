# Design

## Context

Sprint 8 de l'audit du 10 octobre 2026 : **AUD-PERF-08**. HEAD analysé : `949e1569` (`develop`). Le prérequis AUD-TST-03 (confinement exigé en CI) est fermé (`docs/project/audits/2026-10-10/SUIVI.md:10`). Changement indépendant des autres du sprint.

## État vérifié au HEAD

### AUD-PERF-08 — confirmé, coût non mesuré

- `minos-runtime-local/src/main/java/com/minos/runtime/local/process/ProcessOwnershipTracker.java` (déplacé dans le sous-package `process` depuis l'audit) : `:27` `OWNERSHIP_POLL_MILLIS = 10L` ; `:28` `POST_ROOT_EXIT_TRACK_MILLIS = 250L` ; boucle `trackOwnership` `:96-104` (`remember(process.descendants().toList())`, arrêt 250 ms après la sortie de la racine, `Thread.sleep(OWNERSHIP_POLL_MILLIS)`). Autres appels de `descendants()` de la classe (`:45`, `:54`, `:59`, `:65`, `:170`) : construction et arrêt, hors boucle.
- Un suivi par processus provider : `minos-runtime-local/src/main/java/com/minos/runtime/local/process/ProcessIndexerExecutor.java:130`.
- Copie dans le plugin : `minos-intellij/src/main/java/com/minos/intellij/protocol/MinosProcessSupervisor.java:36` (`OWNERSHIP_POLL_MILLIS = 10L`), `:37` (250 ms), boucle `:213-223` ; seul appelant de production `minos-intellij/src/main/java/com/minos/intellij/protocol/MinosCliClient.java:149`. Le superviseur détient aussi une frontière forte (`:41`, `:207`).
- Les autres `descendants()` du code de production (`ProcessTreeTermination.java:64,71`, trois gestionnaires d'installation de `minos-provider-scip`) sont des appels uniques à l'arrêt : non concernés.
- Test dépendant de la cadence, relu : `minos-runtime-local/src/test/java/com/minos/runtime/local/process/ProcessIndexerExecutorTest.java:179` (`normalProviderExitCleansAPreviouslyObservedDetachedChild`). Le provider factice lance un enfant détaché puis dort **1 000 ms** avant de sortir : l'enfant doit être observé dans cette seconde, sans quoi il n'est plus un descendant de la racine. Le provider est lancé comme source Java : le délai entre le démarrage et la création de l'enfant n'est pas connu.
- Tests existants : `ProcessOwnershipTrackerCleanupFailureTest`, `ProcessOwnershipTrackerHostProtectionTest` (runtime local) ; `MinosProcessSupervisorTest`, `MinosProcessSupervisorOrphanTest`, `MinosProcessSupervisorHostProtectionTest` (plugin). `MinosProcessSupervisorTest` est exclu de PIT (`minos-intellij/build.gradle.kts:101`).
- La capacité `client-intellij` exige que le plugin ne dépende d'aucun artefact `com.minos:*` (`openspec/specs/client-intellij/spec.md:77-79`).

**Non mesuré** : le coût réel d'un appel de `descendants()` (aucun profil, aucun benchmark), sur aucun des deux systèmes ; la cadence réelle de la boucle sous Windows, où la résolution de `Thread.sleep` peut dépasser 10 ms. **Non vérifié** : aucun build ni test exécuté ; les tests du plugin n'ont pas été relus.

### Écarts avec l'action proposée par l'audit

1. **« Factoriser la logique entre runtime-local et le plugin » est interdit.** Le plugin est un client externe du protocole CLI, sans dépendance `com.minos:*`, compilé en Java 21 hors du reactor. Les deux implémentations restent distinctes et sont tenues alignées par la même période et le même test.
2. **« S'appuyer sur le confinement noyau et ne garder le sondage qu'en repli » retire une défense voulue.** Le code dit que le suivi reste actif sous le bac à sable « as defence in depth » (`StrongProcessOwnershipIndexerExecutor.java:149-152`), et la documentation utilisateur du plugin le dit aussi (`docs/user/intellij-plugin.md:24`). Le sondage est espacé, pas supprimé.
3. **La période n'est pas libre.** Un descendant n'est retenu que s'il est observé tant que son parent vit ; un intermédiaire qui meurt entre deux sondages fait perdre ses enfants au suivi. Le test `:179` fixe une borne : l'enfant doit être vu en moins d'une seconde.
4. **Le chemin du fichier a changé** depuis l'audit (`process/ProcessOwnershipTracker.java`).

## Goals / Non-Goals

**Goals** : au moins cinq fois moins d'énumérations en régime établi, dans les deux implémentations ; aucun test de nettoyage affaibli ; un test qui échoue si la cadence remonte.

**Non-Goals** : garantir par le sondage ce que seule la frontière noyau garantit ; partager du code avec le plugin ; changer les délais d'arrêt (`GRACEFUL_WAIT_MILLIS`, `FORCED_WAIT_MILLIS`).

## Decisions

### D1. Mesurer avant de fixer la période

Tâche 1 : durée moyenne d'une énumération et cadence réelle de la boucle, sous Windows et sous Linux, sur un hôte de quelques centaines de processus ; relecture des tests du plugin qui dépendent d'une observation. Les valeurs de D2 sont arrêtées sur ces mesures et consignées ici.

### Mesures (tâche 1.1 puis 4.1, 2026-10-17, Windows 10, JDK 25, 595 à 598 processus sur l'hôte)

Une énumération `descendants().toList()` d'un processus vivant coûte **30 à 34 ms** (moyenne de 500 appels) : plus que la période de 10 ms. La boucle du HEAD ne tourne donc pas à 100 appels par seconde mais à **22 à 23 appels par seconde** (énumération puis `sleep(10)`, réel 10,8 ms), soit environ 70 % d'un cœur occupé par provider. Avec la période de régime de 100 ms : **7,7 appels par seconde** (environ 23 % d'un cœur). Le gain réel sous Windows est donc de **trois fois** en énumérations et d'un facteur trois en occupation, non de dix : l'objectif « au moins cinq fois moins d'énumérations » ne vaut que pour un hôte dont l'énumération est rapide, et le test de cadence (processus factice, énumération instantanée) le prouve pour la boucle, pas pour le coût réel. **Non mesuré** : Linux (aucun hôte Linux sur ce poste). Le test factice voit 178 à 179 énumérations en 2 s au HEAD et un maximum de 30 après correction.

### D2. Sondage adaptatif — **tranchée par le propriétaire le 2026-10-17 : option A** (10 ms pendant la première seconde, puis 100 ms ; fenêtre de 250 ms après la sortie de la racine conservée)

Option A (recommandée) : 10 ms pendant la première seconde de vie du processus (là où un lanceur crée et abandonne le plus souvent ses intermédiaires), puis **100 ms**. Régime établi : dix énumérations par seconde au lieu de cent. Le test `:179` garde au moins neuf observations dans sa fenêtre d'une seconde.

Option B : période fixe de 100 ms dès le démarrage. Plus simple, un seul régime, même gain en régime établi ; mais la phase de démarrage est dix fois moins couverte qu'aujourd'hui.

Option C (non recommandée) : ne sonder que si aucune frontière noyau n'est active. C'est l'action de l'audit ; elle retire la défense en profondeur (écart 2) et demande au suivi de connaître la frontière, qu'il ignore aujourd'hui.

Dans tous les cas : la fenêtre de 250 ms après la sortie de la racine est conservée, avec au moins deux sondages ; un sondage est fait immédiatement à la construction et à l'arrêt, comme aujourd'hui.

### D3. Deux implémentations, une même règle

`ProcessOwnershipTracker` et `MinosProcessSupervisor` portent les mêmes constantes (durée de la phase initiale, période de régime) et chacune son test de cadence, de même forme et de même borne. Aucun code partagé. Pas de gate de parité textuelle entre les deux fichiers : la parité est prouvée par les deux tests.

### D4. Test de cadence par processus factice

Un `Process` factice, vivant, dont `descendants()` compte ses appels et ne rend rien. Après la phase initiale, sur une fenêtre de 2 s : au plus 30 appels (20 attendus avec les options A et B ; l'exigence fixe 15 énumérations par seconde au plus). Au HEAD, la même fenêtre en compte plus de 100. Le test n'affirme pas de minimum serré (charge de la CI) ; il affirme au moins un appel, pour qu'un suivi arrêté ne passe pas pour une économie.

## Direction des dépendances

`minos-runtime-local` : changement interne au package `process`, aucun import nouveau (ADR 0022). `minos-intellij` : changement interne au package `protocol` ; aucune dépendance `com.minos:*` ajoutée, ce que la garde existante de la capacité `client-intellij` vérifie.

## Qualification des capacités

- Suivi de propriété par sondage : **partielle** par nature (défense en profondeur ; un descendant dont le parent meurt entre deux sondages échappe au suivi). L'espacement élargit cette fenêtre en régime établi ; la capacité qualifiée reste la frontière noyau.
- Réduction du coût : **non qualifiée** tant que D1 n'est pas faite ; l'exigence porte sur le nombre d'énumérations, pas sur un temps processeur.

## Windows et Linux

`descendants()` parcourt `/proc` sous Linux et prend un instantané du système sous Windows : les deux coûts sont mesurés (D1). Le test de cadence s'exécute sur les deux systèmes de la CI pour `minos-runtime-local`, et dans le check `IntelliJ plugin (gate)` pour le plugin. La résolution de l'horloge sous Windows peut allonger la phase à 10 ms : la borne du test ne porte que sur le régime établi.

## Risks / Trade-offs

- **Un descendant éphémère échappe au sondage** entre deux passages de 100 ms : accepté, la frontière noyau le tient ; à écrire dans la Javadoc des deux classes et à ne pas présenter comme une garantie.
- **Test `:179` fragilisé** si l'enfant naît dans la phase lente : neuf observations restent possibles en une seconde ; à prouver par vingt exécutions consécutives sur les deux systèmes.
- **Dérive entre les deux implémentations** : une correction faite d'un seul côté. Les deux tests de même borne la révèlent pour la cadence, pas pour le reste de la logique (limite acceptée, D3).
- **Test sensible au temps** : fenêtre de 2 s et borne large pour ne pas produire de faux rouge sur un runner chargé.
