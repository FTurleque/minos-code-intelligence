# Proposal

## Why

Trois constats du sprint 2 de l'audit du 10 octobre 2026 touchent la même chaîne, « une erreur laisse-t-elle une trace exploitable, sans fuite ? » :

- **AUD-DEP-08** (moyenne) : le jar ombré n'embarque qu'un seul binding SLF4J, `slf4j-nop` (`minos-app/pom.xml:30`). Le SDK MCP et JGit journalisent par SLF4J ; leurs erreurs (transport et désérialisation JSON-RPC, échecs réseau et pack) **disparaissent en production** (Docker, Windows, plugin). Le code de MINOS, lui, journalise par `System.Logger` (48 appels `LOGGER.log` dans 24 fichiers) et n'est pas concerné.
- **AUD-QUA-04** (moyenne) : trois `Task.Backgroundable` du plugin IntelliJ attrapent `Throwable`, mémorisent l'échec sans rien journaliser, et l'affichage se replie sur « Unknown failure » quand le message est absent. Aucun `Logger` dans tout le plugin ; les `Error` de la JVM (mémoire) sont avalées au lieu d'être rapportées par la plateforme.
- **AUD-QUA-06** (faible) : deux régimes de journal coexistent. Des WARNING écrivent un chemin absolu et la cause complète (`RunDirectoryRetention`, `ProviderResidueReclamation`), alors que d'autres classes des mêmes modules n'écrivent que la classe de l'exception. Le serveur MCP en STDIO journalise sur stderr, capturé par les clients MCP : le nom de l'utilisateur et l'arborescence de `MINOS_HOME` y apparaissent selon la classe qui échoue.

Une vérification de ce changement a révélé que **l'action proposée pour AUD-DEP-08 est inversée** : `slf4j-jdk-platform-logging` redirige `System.Logger` **vers** SLF4J, pas l'inverse. Elle laisserait SLF4J sans binding (donc muet) et enverrait en plus les journaux de MINOS dans ce vide. Voir `design.md`, D1.

## What Changes

- **Binding SLF4J** : remplacer `slf4j-nop` par `org.slf4j:slf4j-jdk14` (même version, 2.0.20, déjà gérée par `dependencyManagement`) : SLF4J → `java.util.logging`, le même backend que `System.Logger` par défaut, donc **un seul régime** de configuration, écrit sur stderr, jamais sur stdout. Un test sur le jar ombré vérifie le binding et l'absence de sortie sur stdout.
- **Plugin IntelliJ** : une seule tâche d'arrière-plan factorisée (nouveau package feuille `com.minos.intellij.task`) remplace les quatre copies ; elle laisse passer `ProcessCanceledException` et les `Error`, journalise toute autre exception avec `Logger.getInstance` (pile complète dans `idea.log`), et l'affichage nomme le type de l'exception quand le message est absent.
- **Journaux WARNING de récupération** : les cinq sites de `RunDirectoryRetention` et `ProviderResidueReclamation` écrivent le chemin **relatif** à la racine gérée et la classe de l'exception, sans cause (le relevé du HEAD compte cinq sites, l'audit en cite trois).
- **Garde** : le gate `check-private-io.py` (déjà câblé et autotesté) gagne deux règles de journalisation : concaténer un `Path` dans un appel `LOGGER.log` est interdit sans exception ; passer la cause ou `getMessage()` à un appel `LOGGER.log` n'est permis que pour les sites nommés d'une liste (les diagnostics de bac à sable, où la cause est voulue).
- Une capacité `journalisation` est créée ; `client-intellij` reçoit une exigence.

## Capabilities

### New Capabilities

- `journalisation` : ce que MINOS garantit sur ses journaux (bibliothèques comprises) : chaque erreur de bibliothèque atteint stderr, jamais stdout, et les messages de MINOS n'écrivent pas de chemin absolu. Capacité nouvelle : `audit-qualite-code` porte l'audit à la demande, `surfaces-publiques` les erreurs rendues à l'appelant ; aucune ne traite du journal d'exploitation.

### Modified Capabilities

- `client-intellij` : exigence ajoutée sur les échecs de tâche d'arrière-plan (journalisés, jamais avalés).

## Hors périmètre

- **Les autres constats du sprint 2** (AUD-SEC-01, SEC-02, SEC-12, SEC-13 : autres changements).
- Unifier les **trois** régimes de journal en un utilitaire commun (`LinuxCgroupJob.describeFailure` et `redactPaths` en sont une troisième variante) : nommé dans `design.md` comme suite possible, non traité ici.
- Les **diagnostics de bac à sable** qui passent la cause à dessein (`LinuxBubblewrapWorkerSandboxBackend`, `WindowsJobObjectProcessOwnership`, `WindowsAppContainerWorkerSandboxBackend`) : conservés, inscrits dans la liste du garde avec leur justification.
- L'avertissement du validateur de schéma du SDK MCP (MINOS-AUD-C13) : bénéficiaire de DEP-08, non traité ici.
- Réglage du niveau de journalisation par l'utilisateur (documenté, pas ajouté).

## Impact

- **Modules du reactor touchés** : `minos-app` (POM, un test d'intégration sur le jar ombré), `minos-runtime-local` (deux classes), `scripts/architecture/` (garde et auto-test). Plugin `minos-intellij` (hors reactor, Gradle) : nouveau package `com.minos.intellij.task`, quatre sites modifiés.
- **Surfaces publiques impactées** : stderr du CLI, du serveur MCP et du conteneur reçoit désormais les messages des bibliothèques (changement observable, non contractuel : le protocole MCP n'utilise que stdout). Panneau « Result » du plugin : message d'erreur enrichi du type. Aucune sortie JSON, aucun schéma.
- **ADR** : aucun nouvel ADR, aucun amendement. Un paragraphe de `docs/developer/` documente le backend de journalisation et son réglage (`-Djava.util.logging.config.file`).
- **Gates à rejouer** : `python scripts/architecture/check-private-io.py` et `python scripts/architecture/test_check_private_io.py`, `python scripts/quality/check-ci-wiring.py`, `python scripts/docs/check-current-docs.py`, `python scripts/remediation/check-single-execution.py`, et les deux gates littéraux qui lisent les classes modifiées : `python scripts/remediation/check-minos-01.py` (cite `ProviderResidueReclamation.java` et `RunDirectoryRetention.java`) et `python scripts/remediation/check-post-mne.py` (cite `RunDirectoryRetention.java`) ; JaCoCo : le scope `provider-execution-trust-boundary` contient `ProviderResidueReclamation` (`check-jacoco.py:108`) ; plugin : `IntelliJ plugin (gate)` (la portée calculée par `plugin-gate.py` inclut le plugin). Dependency-Check/OSV : `slf4j-jdk14` 2.0.20 est de la même famille et de la même version que `slf4j-api` déjà embarqué.
- **Plateformes** : Windows et Linux ; JUL écrit sur stderr dans les deux cas. Le jar ombré doit garder `java.logging` dans le runtime `jpackage` (les racines sont calculées par `jdeps` sur le jar : le nouveau binding les y ajoute de lui-même ; à vérifier, tâche 2.3).
