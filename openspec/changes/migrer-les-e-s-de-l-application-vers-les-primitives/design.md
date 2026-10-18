# Design

## Context

Suite d'AUD-SEC-03 (sprint 8) : lot « application et registre », partie `minos-application`. HEAD analysé : `4922f4ac` (`develop`). Indépendant de `completer-les-primitives-d-e-s-privees`. Aucun code, test ni gate n'est modifié par cette spécification.

## État vérifié au HEAD

- `check-private-io.py` : `LocalProjectOperations.java` / `Files.newOutputStream` (maximum 1, `:211`) ; `FingerprintConstrainedJavaProgramGraphProvider.java` et `JavaSourceWorkspace.java` / `Files.newInputStream` (maximum 1 chacun, `:214` et `:150`).
- **Historique** (`LocalProjectOperations.java:201-220`) : `ensureDirectory`, quatre propriétés, `createPrivateTempFile(historyDirectory, projectId + ".", ".tmp")`, `properties.store(OutputStream)` (jeu de caractères de `Properties.store(OutputStream)`, ISO-8859-1 avec échappements `\uXXXX`), `DurableAtomicFile.replace`, `deleteIfExists`. Aucun test dédié à `LocalProjectOperations` dans `minos-application` (un autre module le teste par la CLI).
- **Empreinte de sources** (`FingerprintConstrainedJavaProgramGraphProvider.java:186`) : `candidate = root.resolve(fileId).normalize()`, `Files.isRegularFile(candidate)` (suit les liens), `real = candidate.toRealPath()`, `real.startsWith(root)`, taille, puis `sha256Exact(real, taille, …)` **sans option**. `JavaSourceWorkspace.java:110` : `updateBounded(digest, source.path(), MAX_SOURCE_BYTES, …)` sans option. Les chemins lus sont donc des chemins réels résolus : un lien dont la cible est dans le projet est déjà résolu avant la lecture, la lecture elle-même ne rencontre pas de lien. Seul un remplacement **après** `toRealPath` est visé. Les lectures de configuration de sécurité (`:206`, `:116`) passent déjà `NOFOLLOW_LINKS`.
- **Gates littéraux** : `check-mne.py:98-102` exige dans `FingerprintConstrainedJavaProgramGraphProvider.java` les jetons `sha256Exact`, « grew while being fingerprinted » et `MAX_SECURITY_CONFIG_BYTES`, et interdit `private static String sha256(Path file)` ; `check-mnd.py:103` exige `DEFAULT_MAX_FINGERPRINT_CACHE_WEIGHT_BYTES`, `trimCache`, `fingerprintCacheStats` ; `check-p0-p2.py:222` exige `SOURCE_DIFFERS_FROM_SNAPSHOT_FINGERPRINT`. La méthode `sha256Exact` et ses messages **restent** ; seule l'ouverture du flux change.
- **Tests** : `JavaSecurityConfigConfinementTest` (liens et configuration de sécurité), `ProgramGraphSourceStateReuseTest`, `JavaSourceProgramGraphDecompositionTest`, `MinosApplicationTest` (bootstrap). Scopes JaCoCo `program-graph-analysis`, `java-advanced-provider`.
- **Recouvrements** : `mutualiser-les-caches-lru-et-nommer-les-additions-bornees` (0/15, **non implémenté**) modifie `FingerprintConstrainedJavaProgramGraphProvider` ; `donner-un-contrat-d-erreur-au-port-d-etat-d-index` et `reserver-minos-ollama-au-runtime-docker` citent `LocalProjectOperations`. Conflits de texte seulement.

**Non vérifié** : aucun build ni test exécuté ; le coût de l'`attributes` supplémentaire par fichier source (`openRegularFileNoFollow` lit les attributs avant d'ouvrir) n'est pas mesuré sur un grand projet.

## Écarts avec la justification de la liste

1. L'historique est une écriture de petit fichier : `PrivateLocalStorage.writePrivateFile` suffit, comme pour le magasin local.
2. Les deux lectures de sources ne sont **pas** dans les 44 occurrences du sprint 8 mais dans la liste plus ancienne, « à migrer vers `ConfinedFileOpener` » ; leur justification dit qu'elles lisent un chemin « déjà résolu par la découverte confinée », ce qui est exact : le gain est de refuser un remplacement du leaf entre la résolution et la lecture, rien de plus.
3. `JavaSourceWorkspace.updateBounded(…, OpenOption… options)` et `sha256Exact(…, OpenOption… options)` : la variante sans option cesse d'exister (tous les appels deviennent `NOFOLLOW`). C'est un durcissement du chemin des sources : une source dont le leaf est un lien **après** `toRealPath` est refusée au lieu d'être lue.

## Goals / Non-Goals

**Goals** : trois entrées retirées ; un leaf de source remplacé par un lien est refusé ; l'historique est écrit sans suivre de lien.

**Non-Goals** : changer une empreinte (valeur persistée du scope inchangée), un message, un cache.

## Decisions

### D1. Inclure les deux lectures de sources — recommandation : oui

Même module, même primitive que le reste de la série, deux entrées de moins. Option B : un changement séparé. Rejetée : trois lignes de code et un test, pas de risque d'ordre.

### D2. Historique — recommandation : octets en mémoire, `Properties.store(OutputStream)` conservé

`properties.store(ByteArrayOutputStream, comment)` conserve l'encodage exact d'aujourd'hui (ISO-8859-1 échappé) ; test d'octets identiques sur un `providerVersion` non ASCII. Option B : UTF-8. Rejetée : changerait le contenu des fichiers existants, relus ailleurs.

### D3. Lecture de source — recommandation : `openRegularFileNoFollow` enveloppé dans `BoundedInputStream`

Le comptage d'octets et les messages existants restent (`grew while being fingerprinted`). Option B : `BoundedFileDigest.sha256Exact`. Rejetée : sa sémantique (taille exacte lue sur le descripteur, plafond) diffère de celle de l'empreinte attendue et ses messages ne sont pas ceux exigés par `check-mne.py`.

## Direction des dépendances

`application → engine` (ADR 0022) ; `com.minos.io` est déjà importé par les trois fichiers ou leurs voisins (`BoundedInputStream`, `Sha256`). Aucun package nouveau.

## Qualification des capacités

- Historique d'indexation écrit sans suivre de lien : **qualifiée**.
- Lecture de source sans suivre de lien : **qualifiée** pour le leaf ; l'ancêtre n'est pas prouvé sous Windows (déclaré pour `ConfinedFileOpener`).

## Windows et Linux

Aucune branche de plateforme. Test de lien symbolique : `assumeTrue` sur la capacité (convention `tests-java`) ; la jonction Windows est couverte par `ConfinedFileOpenerWindowsJunctionTest` du moteur.

## Risks / Trade-offs

- **Source qui est un lien après résolution** : refusée (écart 3), jamais observée ; couverte par test.
- **Lecture d'attributs en plus** par fichier source : négligeable devant le hachage ; à mesurer sur un projet de plusieurs milliers de fichiers (tâche 2.3).
- **Conflit avec `mutualiser-les-caches-lru-…`** : ordre de fusion, jetons du gate conservés.
