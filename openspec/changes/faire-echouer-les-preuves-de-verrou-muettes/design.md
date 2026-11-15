# Design

Changement de tests seulement : les sections Contexte, État vérifié au HEAD, Écarts, Décisions, Vérification et Traçabilité sont remplies. Les autres sections du modèle sont non applicables : aucun code de production, format ni contrat ne change.

## Contexte

Motivation : `proposal.md`. Trois classes de test de `minos-storage` sont touchées :

- `FileSemanticSyncConsistencyTest` lance une seconde JVM (`SemanticSyncLockHolderProcess`) qui prend le verrou de fichier `.sync-locks/<projet>.lock`, écrit `READY` sur sa sortie et le garde jusqu'à la fermeture de son entrée ;
- `ProjectLeaseOwnershipTest` couvre trois baux (`InMemoryIndexStateStore`, `FileIndexStateStore`, `ProjectIndexLease`) ;
- `SnapshotProjectLeaseTest` couvre `SnapshotProjectLease`.

`ParkedThreads.awaitParkedIn` (outillage de test de `minos-engine`, déjà utilisé par `FileSemanticSyncConsistencyTest`) observe qu'un fil est en attente dans une classe donnée, avec une échéance.

## État vérifié au HEAD

HEAD `89be3fb4`.

| Fait | Preuve |
|---|---|
| Saut si le détenteur ne signale pas `READY` | `minos-storage/src/test/java/com/minos/storage/local/store/FileSemanticSyncConsistencyTest.java:238-239` |
| Saut si la commande de la JVM ou le classpath sont indisponibles | `FileSemanticSyncConsistencyTest.java:284-288` |
| Sortie d'erreur du détenteur jetée ; environnement hérité | `FileSemanticSyncConsistencyTest.java:289-293` |
| L'attente du verrou est déjà observée par un signal, non par un délai | `FileSemanticSyncConsistencyTest.java:258-260` |
| Bail en mémoire : fermeture étrangère refusée, puis double fermeture du propriétaire, puis réacquisition par le **même** fil, `assertTrue(true)` | `minos-storage/src/test/java/com/minos/storage/local/orchestration/ProjectLeaseOwnershipTest.java:27-41` |
| Même schéma pour le magasin fichier et le bail de fichier | `ProjectLeaseOwnershipTest.java:43-57`, `:59-73` |
| `assertTrue(true)` après une échéance sur verrou étranger | `ProjectLeaseOwnershipTest.java:90-93` ; `SnapshotProjectLeaseTest.java:86-89` |
| Même schéma pour le bail de snapshot | `minos-storage/src/test/java/com/minos/storage/local/store/SnapshotProjectLeaseTest.java:52-68` |
| Six `assertTrue(true)` dans tous les `src/test` du dépôt | recherche : `ProjectLeaseOwnershipTest.java:39`, `:55`, `:71`, `:92` ; `SnapshotProjectLeaseTest.java:67`, `:88` |
| Bail en mémoire : acquisition bloquante sans échéance (`ReentrantLock.lock()`), contrôle du propriétaire avant la libération | `minos-engine/src/main/java/com/minos/orchestration/InMemoryIndexStateStore.java:62` (`lock()`), `:67-68` (contrôle du propriétaire), `:71` (libération) |
| Bail de fichier : acquisition avec échéance à visibilité de package, contrôle du propriétaire avant la libération | `minos-storage/src/main/java/com/minos/storage/local/orchestration/ProjectIndexLease.java:43-63` (`acquire`), `:69-78` (`close`) |
| Magasin fichier : bail ré-entrant par fil (compteur de profondeur) | `minos-storage/src/main/java/com/minos/storage/local/orchestration/FileIndexStateStore.java:92-99` |
| Bail de snapshot : acquisition avec échéance à visibilité de package ; verrous de la JVM pris dans un tableau fixe, sans compteur de verrous retenus | `minos-storage/src/main/java/com/minos/storage/local/store/SnapshotProjectLease.java:49`, `:32`, `:52` |
| Bail de fichier d'indexation : compteur de verrous retenus lisible des tests | `ProjectIndexLease.java:84-88` (`retainedJvmLockCount()`) |

Statut : **AUD-TST-05 confirmé** (lignes 239, 285, 288 ; `DISCARD` à la ligne 292, l'audit citait 289-293). **AUD-TST-07 confirmé** (six sites exacts).

## Écarts

| Écart | Source A | Source B | Suite proposée |
|---|---|---|---|
| E1 — « ou un mode requis comme minos.sandbox.tests.required » : aucun environnement légitime n'empêche de relancer la JVM qui exécute déjà le test | action de AUD-TST-05 | `FileSemanticSyncConsistencyTest.java:284-293` | assertions sans mode : pas d'option de plus à câbler en CI (D1) |
| E2 — « tenter une acquisition concurrente bornée » : le bail en mémoire et celui du magasin fichier n'ont pas d'acquisition à échéance dans leur port | action de AUD-TST-07 | `InMemoryIndexStateStore.java:63` ; `IndexStateStore.java:38` | pour ces deux baux, observer le concurrent en attente par `ParkedThreads.awaitParkedIn` ; pour les deux baux de fichier, échéance courte (D4) |
| E3 — La réacquisition des tests actuels se fait depuis le fil propriétaire ; le magasin fichier est ré-entrant par fil, donc elle ne prouverait rien même avec une affirmation | `ProjectLeaseOwnershipTest.java:53-56` | `FileIndexStateStore.java:94-99` | le concurrent est toujours un **autre** fil |
| E4 — Le détenteur hérite de l'environnement de la JVM de test, contre la règle des tests de processus fils | `docs/developer/testing.md:104` | `FileSemanticSyncConsistencyTest.java:289-293` | vider l'environnement du fils (D3) ; `ChildJvm` vit dans `minos-provider-scip` et n'est pas visible de `minos-storage` |
| E5 — Conserver la sortie d'erreur par un tube non lu peut bloquer le fils | action de AUD-TST-05 (« conserver stderr ») | commentaire du lanceur (« discarded so it never blocks ») | la vider en continu dans un tampon borné (D2) |

## Décisions

### D1 — Des assertions, pas un mode requis
[Décision] Les trois `assumeTrue` deviennent des assertions. Un `READY` absent est un échec dont le message porte le code de sortie du fils (attendu avec une échéance) et la fin de sa sortie d'erreur.

### D2 — Sortie d'erreur vidée en continu dans un tampon borné
[Décision] Le lanceur lit la sortie d'erreur du fils par un fil démon dans un tampon qui ne garde que les derniers kibioctets (borne fixée dans le test, de l'ordre de 4 Kio). Le fils ne peut pas se bloquer sur un tube plein ; le message d'échec reste court. Écarté : redirection vers un fichier temporaire (une écriture de plus à nettoyer) ; `redirectErrorStream(true)` (mélangerait le protocole `READY`).

### D3 — Environnement du fils vidé
[Proposition, recommandée] `environment().clear()`, puis seules les variables qu'exige le lancement d'une JVM sur le système courant (`SystemRoot` sous Windows). Le résultat du test ne dépend plus de l'hôte. Écarté : laisser l'héritage (écart E4 maintenu).

### D4 — Preuve du bail tenu, par bail
[Décision]
- `ProjectIndexLease`, `SnapshotProjectLease` : après le refus, un autre fil appelle `acquire(..., Duration.ofMillis(100))` et l'échec « timed out » est affirmé ; après la fermeture par le propriétaire, l'acquisition par cet autre fil réussit.
- `InMemoryIndexStateStore`, `FileIndexStateStore` : un autre fil appelle `acquireProjectLease` ; le test observe ce fil en attente (`ParkedThreads.awaitParkedIn`, échéance de 5 s, classe d'attente du verrou concerné) et affirme qu'il n'a pas acquis ; après la fermeture par le propriétaire, il acquiert, ferme, et le test joint le fil avec une échéance.

Aucun délai fixe ne sert d'affirmation négative (`persistance-snapshots › L'attente d'un verrou de mutation est prouvée par un signal observé`).

### D5 — Remplacement des affirmations constantes
[Décision] Les blocs de réacquisition affirment un fait : l'acquisition n'a levé aucune exception **et** un concurrent est refusé pendant que le bail réacquis est tenu ; pour `ProjectIndexLease` seulement, le compteur de verrous retenus par la JVM revient à sa valeur initiale après la fermeture (`ProjectIndexLease.retainedJvmLockCount()`) ; `SnapshotProjectLease` n'a pas de compteur (verrous pris dans un tableau fixe) et n'en reçoit pas. Les deux tests d'échéance sur verrou étranger (`:92`, `:88`) affirment la réussite de l'acquisition suivante par `assertDoesNotThrow` et la fermeture.

### D6 — Un test garde le lanceur
[Décision] `FileSemanticSyncConsistencyTest` reçoit un test qui lance le détenteur avec une classe principale inexistante et affirme que l'attente de `READY` produit une erreur d'assertion dont le message contient le code de sortie et un extrait de la sortie d'erreur. C'est le rouge permanent du constat AUD-TST-05.

## Vérification

- Rouge AUD-TST-05 : le test de D6 échoue au HEAD (le lanceur lève une hypothèse de saut au lieu d'une assertion).
- Rouge AUD-TST-07 : preuve **manuelle** sur le poste Windows, hors des scénarios de la spécification parce qu'elle n'est pas une commande reproductible, à reporter dans la PR. La copie locale non commitée marque le bail fermé avant de contrôler le fil propriétaire dans `InMemoryIndexStateStore`, `ProjectIndexLease` et `BoundedFileLease` (porteur de la fermeture de `SnapshotProjectLease`) : la fermeture étrangère refusée rend sans effet la fermeture du propriétaire. Une libération avant le contrôle n'est pas réalisable : un `ReentrantLock` libéré par un autre fil lève `IllegalMonitorStateException`. Sur cette copie, les trois nouveaux tests visés échouent (concurrent jamais servi) ; parmi les tests du HEAD, celui du bail en mémoire reste vert et ceux des deux baux de fichier échouent aussi (réacquisition par le même fil à l'échéance). Le rouge départage donc les anciens et les nouveaux tests pour le bail en mémoire seulement.
- `./mvnw -B -ntp -pl minos-storage -am test "-Dtest=FileSemanticSyncConsistencyTest,ProjectLeaseOwnershipTest,SnapshotProjectLeaseTest" "-Dsurefire.failIfNoSpecifiedTests=false"` rejoué 50 fois sur le poste (tests de concurrence), puis `./mvnw -B -ntp -pl minos-storage -am test`.
- Scopes JaCoCo : `semantic-vector-store`, `storage-codec-and-state` (`FileIndexStateStore`), comparés en CI.
- Linux : CI (`Verify (ubuntu-24.04)`) ou WSL ; le verrou de fichier inter-processus n'a pas la même sémantique sur les deux systèmes, les deux doivent passer.

## Traçabilité

| Exigence (capacité › titre) | Scénario | Conception ou diagramme | Tâche | Test ou preuve |
|---|---|---|---|---|
| recherche-semantique › La preuve inter-processus du verrou de synchronisation échoue quand son détenteur ne démarre pas | Détenteur prêt | D1, D3 | 1.2, 1.3 | `FileSemanticSyncConsistencyTest.crossJvmFileLockBlocksConditionalReplaceUntilReleased` (existant, modifié) |
| idem | Détenteur qui ne démarre pas | D1, D6 | 1.1, 1.2 | `FileSemanticSyncConsistencyTest.lockHolderThatCannotStartFailsTheProofInsteadOfSkippingIt` (rouge au HEAD : `TestAbortedException` au lieu d'`AssertionFailedError`) |
| idem | Sortie d'erreur volumineuse | D2 | 1.2 | `FileSemanticSyncConsistencyTest.lockHolderFailureKeepsOnlyTheBoundedTailOfALargeErrorOutput` (fils `NoisyFailingHolderProcess`) |
| persistance-snapshots › Une fermeture refusée depuis un autre fil laisse le bail tenu | Concurrent refusé après la fermeture étrangère | D4 | 2.1, 2.2 | `ProjectLeaseOwnershipTest.physicalFileLeaseWrongThreadCloseKeepsLeaseHeldUntilOwnerCloses`, `SnapshotProjectLeaseTest.wrongThreadCloseKeepsLeaseHeldUntilOwnerCloses` ; échouent sur la copie locale mutée, comme ceux du HEAD, preuve manuelle (Vérification) |
| idem | Concurrent en attente sur un bail sans échéance | D4 | 2.1, 2.2 | `ProjectLeaseOwnershipTest.inMemoryWrongThreadCloseKeepsLeaseHeldUntilOwnerCloses` ; rouge par copie locale mutée, preuve manuelle (Vérification). Le scénario vise le bail en mémoire, seul bail sans échéance. `ProjectLeaseOwnershipTest.fileStoreWrongThreadCloseKeepsLeaseHeldUntilOwnerCloses` applique la même observation au bail du magasin fichier, qui a une échéance de 10 s (`FileIndexStateStore.java:101`, `ProjectIndexLease.java:22`) plus longue que la fenêtre d'observation de 5 s du test ; aucune mutation du magasin fichier |
| idem | Plus d'affirmation constante | D5 | 2.3 | commande `grep` du scénario |

## Questions ouvertes

- [Question, propriétaire] D3 : vider l'environnement du processus détenteur (recommandé).
