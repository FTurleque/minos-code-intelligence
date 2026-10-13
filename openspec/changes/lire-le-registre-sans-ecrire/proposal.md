# Proposal

## Why

Défaut constaté le 13 octobre 2026 pendant `separer-l-image-de-requete` (PR #441), reproduit le même jour au HEAD de `develop` : avec le répertoire de données MINOS monté en **lecture seule** — ce que déclarent `docker/compose-mcp.prod.yaml:48-49` et `docker/compose-mcp.connected.yaml:64-65` pour le service `minos-mcp`, et ce qu'exige le test de contrat `M29DockerAdministrationContractTest:29` — **aucun outil MCP qui lit un projet ne répond** : 26 outils sur 31 rendent « MINOS tool execution failed », les 5 autres refusent avant toute lecture (mode équipe désactivé). Avec les mêmes données montées en écriture, aucun échec interne.

La cause est une seule ligne : toute **lecture** du registre des projets prend son verrou inter-processus en ouvrant `registry/.registry.lock` **en écriture** (`InterProcessLocalProjectRegistry.java:93-120,137-144` → `BoundedFileLease.java:102-105`). Le montage en lecture seule date du 4 août 2026 (`2de847bd`), le verrou du 10 août 2026 (`845136ed`) : depuis cette date, le déploiement Docker documenté (`docs/user/docker-runtime.md:11`, « MCP query … read-only ») ne peut pas répondre à une requête. Les qualifications ne le voyaient pas : elles s'arrêtaient à `tools/list`, qui ne lit pas le registre ; la requête ajoutée par la PR #441 montait les données en écriture.

MINOS annonce donc une capacité qu'il n'a pas (plan de requête sur données en lecture seule, ADR 0017 et ADR 0021). Il faut soit la tenir, soit cesser de l'annoncer.

## What Changes

Deux options, **à trancher par le propriétaire** (D1 de `design.md`). Les spécifications et les tâches de ce changement décrivent l'option recommandée (A) ; l'option B est décrite dans le design avec ce qu'elle remplacerait.

**Option A (recommandée) — tenir la lecture seule.**

- Une lecture du registre prend un bail **partagé** sur le fichier de verrou existant, ouvert en lecture : rien n'est créé, durci ni ouvert en écriture. Un écrivain (bail exclusif, inchangé) exclut toujours les lecteurs, et réciproquement.
- Les deux autres écritures que le plan de requête peut déclencher sont traitées de la même façon : la lecture des observations runtime (second verrou de lecture) et la migration d'une entrée de registre héritée, aujourd'hui déclenchée par une lecture.
- La qualification de l'image de release interroge le serveur MCP avec les données **réellement** en lecture seule (index préparé dans un volume, remonté `readonly`, sonde d'écriture refusée, puis `minos_find_symbols`).

**Option B — dire la vérité.** Monter les données en écriture pour `minos-mcp` dans les deux fichiers compose, corriger le test de contrat, `docs/user/docker-runtime.md` et l'ADR 0021 : le plan de requête Docker écrit dans le répertoire de données (verrous).

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `surfaces-publiques` : exigence ajoutée — tout outil MCP répond sur un répertoire de données qu'il ne peut pas écrire.
- `stockage-prive-et-secrets` : exigences ajoutées — une lecture du registre et des observations runtime n'écrit rien ; elle reste exclue par un écrivain ; un fichier de verrou non conforme est refusé à la lecture, jamais réparé.
- `distribution-docker` (capacité créée par `separer-l-image-de-requete`, non encore archivée) : exigence ajoutée — la qualification interroge le plan de requête sur des données en lecture seule.

## Hors périmètre

- Le **démarrage** du serveur sur un répertoire de données vide ou incomplet en lecture seule : l'ouverture crée une quinzaine de répertoires (`LocalStorageBackend.java:36-43`, stores) et échoue si l'un manque. C'est un refus correct (rien à lire) ; seul son message est concerné, et il n'est pas traité ici.
- L'inspection des runtimes providers faite à **chaque démarrage** du serveur MCP (`CompositeProviderRuntimeManager.java:25`), qui peut écrire sous le répertoire de données quand des toolchains sont présentes. Sans effet dans l'image de requête (aucune toolchain) ; contraire à l'esprit de l'ADR 0017 sur un poste natif. Changement séparé.
- Un support en lecture seule sous **Windows** natif : le durcissement réécrit la DACL de chaque objet au premier contact de chaque JVM (`PrivateLocalStorage.java:387-390`). Le déploiement en lecture seule est le conteneur Linux ; sous Windows, la garantie apportée ici est qu'une lecture ne demande aucun droit d'écriture de données.
- Le backend PostgreSQL (`MINOS_STORAGE_BACKEND=postgresql`) : son registre n'est pas un fichier.
- Les verrous d'écriture (indexation, publication de snapshot, rétention, index sémantique) : inchangés.

## Impact

- **Modules du reactor** : `minos-engine` (`com.minos.io.BoundedFileLease`), `minos-storage-local` (`registry`, `store`), tests dans `minos-engine`, `minos-storage-local`, `minos-mcp`, `minos-app` (contrat Docker). Aucune dépendance nouvelle entre modules de production ; `minos-mcp` gagne la dépendance de **test** `minos-engine:test-jar` (comme `minos-storage-local`).
- **Surfaces publiques** : aucune sortie CLI, MCP ou API Java ne change de forme. Comportement corrigé : les outils MCP répondent là où ils échouaient.
- **ADR** : aucun nouvel ADR en option A (elle met en œuvre l'ADR 0017) ; « Mise en œuvre » de l'ADR 0021 complétée. L'option B **amende** l'ADR 0021 (le mode Docker n'est plus « read-only » pour les données).
- **Gates à rejouer** : `python scripts/remediation/check-private-io.py` (aucune ouverture de fichier hors des primitives ; le nouveau mode d'ouverture reste dans `BoundedFileLease`), gates littéraux qui citent `BoundedFileLease`, `openPrivateLockChannel`, `withLock` ou `InterProcessLocalProjectRegistry` (recherche dans `scripts/` avant modification), tests `M29Docker*`, gate « Provider-complete image exact-head ».
- **Plateformes** : Linux (cible du montage en lecture seule) et Windows (lecture sans droit d'écriture).
- **Dépendance** : la modification de `scripts/ci/qualify-docker-release.sh` s'appuie sur la version de la PR #441 ; ce changement se fusionne après elle.
