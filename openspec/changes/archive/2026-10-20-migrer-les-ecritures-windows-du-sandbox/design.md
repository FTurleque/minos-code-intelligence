# Design

## Context

Suite d'AUD-SEC-03 (sprint 8) : lot « runtime local », partie Windows. HEAD analysé : `4922f4ac` (`develop`). Indépendant de `completer-les-primitives-d-e-s-privees` (les deux primitives employées existent). Aucun code, test ni gate n'est modifié par cette spécification.

## État vérifié au HEAD

- `check-private-io.py` : `SandboxLauncherScript.java` / `Files.move` maximum 1 (`:289`) ; `WindowsAppContainerWorkerSandboxBackend.java` / `ProcessBuilder.redirect(file)` maximum 1 (`:424`).
- **Publication du lanceur** (`SandboxLauncherScript.java:268-306`) : `publish` crée un temporaire privé (`createPrivateTempFile`), y écrit les octets (`writePrivateFile`), puis `moveWithRetry` : `Files.move(ATOMIC_MOVE)` répété `MOVE_ATTEMPTS = 5` fois, `MOVE_RETRY_DELAY_MILLIS = 50` entre deux essais, au premier `FileSystemException` : si la cible existe, elle est acceptée (« un autre processus a publié ; `verify()` décide »), sinon nouvel essai. Puis `dos:readonly`. L'appelant (`materializeChecked`, `:98-130`) supprime d'abord un lanceur dont le contenu ne correspond pas et vérifie le propriétaire de ce qui préexiste.
- **Sonde** (`WindowsAppContainerWorkerSandboxBackend.java:369-402`, `:423-431`) : `probeSucceeds(builder, output, timeout)` fait `builder.redirectErrorStream(true).redirectOutput(output.toFile()).start()`, attend la fin, lit `head(output)` par `openRegularFileNoFollow` (8 Kio). `output` = `probeRoot/probe-output.log`, dossier créé par `ensurePrivateDirectory` (`:374-375`) ; le fichier est créé par le processus fils sous l'héritage du dossier.
- **Tests existants** : `AppContainerProbeOutputTest.java:29,38` (régression H18, sortie plus grande qu'un tube), `WindowsSandboxAclAndProbeRefusalTest.java:128`, `SandboxLauncherScriptRefusalTest`, `WindowsAppContainerWorkerSandboxBackendTest`.
- **Gates littéraux** : `check-post228-hardening.py:42-52` exige `probeOsIsolation()`, `CAPABILITY_PROBE_CACHE` et d'autres jetons dans le backend (conservés). `check-minos-01.py` et `check-mnd.py` lisent le backend. Aucune attente sur `redirectOutput`, `Files.move` ni `ATOMIC_MOVE` pour ces fichiers.
- **Scope JaCoCo** : `provider-sandbox-windows` (plancher ligne 0,55 / branche 0,35, plateforme Windows).
- **Recouvrements** (changements actifs non archivés, code dans `develop` sauf mention) : `verifier-le-proprietaire-des-dossiers-prives` (`SandboxLauncherScript`), `conserver-les-refus-conditionnels-windows` (backend), `decouper-les-methodes-d-analyse-et-d-ingestion` (backend, **non implémenté**).

**Non vérifié** : aucun test Windows exécuté ; la création d'un lien dur dans `minos-launchers/<empreinte>/` n'a pas été essayée sur le poste (NTFS requis) ; l'effet d'un antivirus temps réel sur `forceFile` du lanceur n'est pas mesuré.

## Écarts avec la justification de la liste

1. **La sonde ne se « remplace par un fichier créé par une primitive » que si le processus fils l'écrit.** Un fichier créé privé puis passé à `Redirect.appendTo` serait toujours signalé par le gate (`Redirect.to` / `appendTo` figurent dans la règle `ProcessBuilder.redirect(file)`). Seule la lecture par tube supprime l'entrée.
2. **Le fichier de sortie de la sonde n'est pas borné** aujourd'hui (le lanceur écrit autant qu'il veut) ; `head` n'en lit que 8 Kio. La lecture par `BoundedProcessOutput.capture` la borne (`DEFAULT_MAX_BYTES_PER_STREAM`).
3. **`ATOMIC_MOVE` sur le lanceur remplace** une cible existante (Windows : `MOVEFILE_REPLACE_EXISTING`) alors que le commentaire de `moveWithRetry` suppose qu'une cible existante fait échouer le déplacement. L'exclusivité réelle est celle du lien dur de `DurableAtomicFile.publish` (`DurableAtomicFile.java:154-176`).

## Goals / Non-Goals

**Goals** : deux entrées retirées ; lanceur publié sans écraser ; sortie de la sonde bornée et privée ; la régression H18 reste couverte.

**Non-Goals** : changer la vérification du lanceur, la politique d'ACL, la sonde elle-même (commande, délai 15 s).

## Decisions

### D1. Lanceur — recommandation : `publish` dans la boucle de reprise existante

`DurableAtomicFile.publish` ne réessaie que le déplacement atomique (`replace`), pas le lien dur : la boucle de `SandboxLauncherScript.moveWithRetry` (5 essais, 50 ms) est conservée autour d'elle, et son traitement « cible existante → acceptée si `verify()` passe » est inchangé (`FileAlreadyExistsException` est une `FileSystemException`). Option B : `replace` (remplace). Rejetée : écart 3. La boucle de reprise est **élargie** : `publishExclusively` (`DurableAtomicFile.java:161-176`) convertit toute `FileSystemException` générique du `createLink` (une violation de partage causée par un antivirus, par exemple) en `IOException` « non supporté » que `moveWithRetry` (qui attrape `FileSystemException`) ne retente pas, alors que le déplacement actuel la retente. La reprise attrape donc aussi cette `IOException` quand sa cause est une `FileSystemException` non spécifique. L'attribut `dos:readonly` est partagé par les deux noms du lien dur : il n'est posé qu'après la publication et la suppression du nom source, sinon `deleteIfExists(partial)` du `finally` échoue. Conséquence nommée : un volume sans lien dur (ReFS ancien, FAT, partage réseau) fait échouer la publication avec « filesystem does not support required exclusive … » ; le lanceur est sous `%LOCALAPPDATA%` / `MINOS_HOME`, sur NTFS dans tous les environnements de CI et de production connus.

### D2. Sonde — recommandation : tube vidé en continu vers un fichier privé

`redirectErrorStream(true)` conservé ; `BoundedProcessOutput.capture(process, output, null)` (surcharge publique à chemins, `BoundedProcessOutput.java:30`, qui ouvre le fichier par `openTarget`, donc par `createPrivateOutputStream` après `migrer-les-ecritures-d-artefacts-du-runtime-local`) ; `capture.await()` avant `head`, **aussi sur le chemin du délai dépassé** (après `terminateTree`) : sans cela, flux et fichier restent ouverts et, sous Windows, `probeRoot` ne peut pas être supprimé (test `aProbeThatDoesNotFinish…` de `WindowsSandboxAclAndProbeRefusalTest`). Sans ce changement-là, `openTarget` crée déjà le fichier privé par `createPrivateFile` : les deux changements sont indépendants. Le délai et la terminaison sont inchangés. Option B : garder la redirection et inscrire l'entrée comme durable (« fichier tiré du dossier parent »). Rejetée par défaut : elle laisse un fichier écrit par un processus fils hors de toute primitive ; à retenir seulement si la mesure de la tâche 3.1 montre une régression du test H18.

### D3. Mesure Windows avant fusion — recommandation : **(manuelle)**

Sur un poste Windows avec antivirus : publier le lanceur 50 fois de suite et mesurer la proportion de reprises ; exécuter la sonde réelle. Seuil d'abandon : plus d'une reprise sur dix, ou une sonde qui rapporte « indisponible » à tort.

## Constat de mise en œuvre (2026-10-18)

- `moveWithRetry` devient `publishWithRetry(source, target, Publisher, délai)` (éditeur injectable, état nul) : `DurableAtomicFile.publish` dans la boucle de reprise existante (5 essais, 50 ms), élargie à l'`IOException` qui a une `FileSystemException` pour cause ; passé la borne, une `FileSystemException` simple garde le message « sandbox launcher could not be published », une exception encapsulée garde le message de la primitive (« filesystem does not support required exclusive … »). La cible existante reste acceptée, `verify()` décide.
- La sonde lit son tube par `BoundedProcessOutput.capture` vers un fichier privé borné (surcharge `probeSucceeds(…, maxBytes)` de paquet), et attend la fin du drain sur tous les chemins, délai dépassé compris.
- Mesure D3 : 50 publications, 0 reprise (Defender actif).
- `allowlisted-occurrences` passe de 107 à 105 ; la justification de `Files.createDirectories` du backend est réécrite comme durable.

## Direction des dépendances

`runtime → engine` (ADR 0022) ; `com.minos.io` est déjà importé par les deux classes. Le backend importe déjà sept types du package `process` (`WindowsAppContainerWorkerSandboxBackend.java:3-9`) et aucune classe de `process` n'importe `sandbox` : l'ajout de `BoundedProcessOutput` ne crée pas de cycle (règle A8) et ne change pas la liste des types exportés (règle A10 : `BoundedProcessOutput` y figure déjà).

## Qualification des capacités

- Publication exclusive du lanceur : **qualifiée** sous NTFS ; **non supportée** (échec fermé) ailleurs.
- Sortie de sonde bornée et privée : **qualifiée** par `AppContainerProbeOutputTest` étendu.

## Windows et Linux

Code Windows seul. Linux : seul le gate Python s'exécute ; les tests sont `@EnabledOnOs(WINDOWS)` (convention `tests-java`). La CI Windows fait foi.

## Risks / Trade-offs

- **Reprises supplémentaires** sous antivirus : borne existante (5 essais) inchangée ; mesure D3.
- **Régression H18** (sonde déclarée indisponible à tort) : le test existant la garde ; le tube est vidé par un fil dédié (`capture`).
