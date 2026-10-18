# Design

## Context

Suite d'AUD-SEC-03 (sprint 8) : lot « application et registre », partie `minos-app`. HEAD analysé : `4922f4ac` (`develop`). Indépendant de `completer-les-primitives-d-e-s-privees`. Aucun code, test ni gate n'est modifié par cette spécification.

## État vérifié au HEAD

- `check-private-io.py` : `McpBackendConfigurationStore.java` / `Files.newBufferedWriter`, maximum 1, position `:90`.
- `McpBackendConfigurationStore.save` (`:76-101`) : `ensurePrivateRuntimeDirectory` (`:108-111`, `MINOS_HOME` et le dossier du fichier), durcissement d'un fichier existant (`secureExistingConfigurationFile`, `:113-118`), `Properties` à quatre clés, temporaire `createPrivateTempFile(parent, FILE_NAME + ".", ".tmp")`, écriture `StandardCharsets.UTF_8` avec `WRITE, TRUNCATE_EXISTING`, `DurableAtomicFile.replace(temporaire, fichier, …)`, `PrivateLocalStorage.verifyPrivateFile(file)`, suppression du temporaire en `finally`.
- Tests : `McpBackendStoreSymlinkTest` (lien à la place du fichier) et `McpBackendRouterTest` ; aucun test dédié à la sauvegarde elle-même.
- Gates littéraux : `check-post-mne.py:178-184` exige `BoundedProperties` dans le fichier (lecture, inchangée) ; `check-jacoco.py` scope `m29-backend-routing` lit le rapport de `minos-app` (plancher ligne 0,55 / branche 0,30).
- Recouvrement : `verifier-le-proprietaire-des-dossiers-prives` (6/7, code dans `develop`, non archivé) cite le fichier.

**Non vérifié** : aucun test exécuté.

## Écarts avec la justification de la liste

Aucun sur la primitive (`PrivateLocalStorage`). Précision : l'écriture en mémoire convient (quatre clés), pas besoin de flux.

## Goals / Non-Goals

**Goals** : retirer l'entrée ; un lien substitué au temporaire est refusé ; contenu identique.

**Non-Goals** : changer le format, les clés, la lecture ou le routage.

## Decisions

### D1. Primitive — recommandation : `writePrivateFile` sur des octets en mémoire

`properties.store(Writer)` vers un `StringWriter` (ou `ByteArrayOutputStream` + `OutputStreamWriter` UTF-8), puis `getBytes(UTF_8)`. Comparaison octet pour octet avant / après sur le jeu de quatre clés, y compris un nom de conteneur non ASCII, **hors la ligne de commentaire datée** de `Properties.store` (`java.properties.date` fixé ou lignes `#` écartées). Option B : flux privé. Rejetée : voir `migrer-le-stockage-local-vers-les-primitives` D1 ; pas de dépendance au socle.

### D2. Test de sauvegarde — recommandation : un test dédié ajouté

`McpBackendConfigurationStore.save` n'a pas de test de sauvegarde. Le test est écrit avec le changement (rouge d'abord), dans le module (`minos-app`) : SonarCloud juge la couverture du nouveau code module par module.

## Direction des dépendances

`surfaces/assemblage → engine` (ADR 0022) ; `com.minos.io` déjà importé (`:4-5`). Aucun package nouveau.

## Qualification des capacités

- Configuration écrite sans suivre de lien : **qualifiée** sous Windows et Linux.

## Windows et Linux

Aucune branche de plateforme. Le test de lien symbolique suit la convention (`assumeTrue` sur la capacité de créer un lien).

## Risks / Trade-offs

- **Aucun test de sauvegarde aujourd'hui** : le risque de régression de format est couvert par D2.
- **Conflit de texte** avec `verifier-le-proprietaire-des-dossiers-prives` : rebase.
