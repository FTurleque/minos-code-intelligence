# Design

## Context

Sprint 3 de l'audit du 10 octobre 2026 : **AUD-DEP-02** et **AUD-DEP-10**. HEAD analysé : `2d1cc214` (`develop`). Réunis parce qu'ils modifient les mêmes lignes de `docker/Dockerfile.mcp.release` et se prouvent par la même construction d'image.

## État vérifié au HEAD

### AUD-DEP-02 — confirmé

- `minos-provider-scip/src/main/resources/com/minos/adapter/scip/runtime/embedded-tools.json:133` : Node `24.20.0` pour `windows-x64` (embarqué) ; `:145` : `20.20.2` pour `linux-x64` (non embarqué, `dockerArgs` `NODE_VERSION` / `NODE_LINUX_SHA256` en `:153-154`).
- `docker/Dockerfile.mcp.release:13-14` : `ARG NODE_VERSION=20.20.2` et son empreinte ; `:89-90` : commentaire « scip-typescript 0.4.0 documents Node 18/20 support » ; `:227` : littéral d'inventaire `"limitation":"provider 0.4.0 documents Node 18/20 only"`. Ce sont les deux seules occurrences de « Node 18/20 ».
- scip-python est installé par le même Node (`:130-143`, `npm ci`).
- `check-tools-manifest.py` garde déjà les `ARG` du Dockerfile contre le catalogue (vert au HEAD, `components=11`).
- `docker/Dockerfile.mcp` n'embarque pas Node.

**Non mesuré** : l'empreinte SHA-256 et la taille de l'archive Node 24 `linux-x64` (réseau non utilisé) ; le comportement de scip-typescript 0.4.0 et de scip-python 0.6.6 sous Node 24 **sous Linux**.

### AUD-DEP-10 — confirmé, écart aggravé

| Toolchain | `FROM` au HEAD | Littéral de l'inventaire |
|---|---|---|
| Rust | `rust:1.99.0-bookworm` (`:2`) | `"toolchain":"1.97.1"` (`:232`) |
| Go | `golang:1.27.0-bookworm` (`:3`) | `"toolchain":"1.26.5"` (`:231`) |
| .NET SDK | `dotnet/sdk:10.0.401-noble` (`:4`) | `"sdk":"10.0.302"` (`:230`) |

- L'audit relevait Rust 1.98.1 ; une montée Dependabot de plus est passée depuis : l'inventaire a deux versions de retard.
- Aucun consommateur ne lit `sdk` ni `toolchain` : `scripts/ci/qualify-docker-release.sh:86-114` ne vérifie que `formatVersion`, `platform`, `minosCommit` et les identifiants de providers.
- Les commandes de version tournent déjà dans le Dockerfile (`:71-75`), sans que leur sortie soit reprise.

### Écarts avec l'action proposée par l'audit

1. **AUD-DEP-02 — scip-python n'est qualifié sous Node 24 sur aucune plateforme.** L'audit s'appuie sur Windows pour dire que Node 24 convient ; c'est vrai pour scip-typescript seulement (scip-python n'a pas d'entrée `assembled`, il n'est pas embarqué sous Windows). Sa qualification est à **mesurer**, pas à déduire.
2. **AUD-DEP-02 — l'alignement passe par le catalogue.** La valeur se change dans `embedded-tools.json`, puis `python scripts/release/sync-tools-manifest.py` propage (ce script n'a pas d'option de contrôle seul) ; modifier le Dockerfile à la main ferait échouer `check-tools-manifest.py`.
3. **AUD-DEP-10 — des `ARG` « dérivés du tag » ne sont pas possibles.** Docker ne dérive pas un `ARG` d'un `FROM` épinglé par digest, et Dependabot monterait le tag sans l'`ARG` : on retomberait sur le même défaut. Seule la **relecture au build** tient.
4. **AUD-DEP-10 — le contrôle statique ne suffit pas.** `check-tools-manifest.py` peut refuser un littéral ; il ne peut pas prouver que la valeur écrite est celle du binaire. La preuve se place dans `qualify-docker-release.sh`, sur l'image construite.
5. **Le même bloc porte Node** (`"runtime":"Node ${NODE_VERSION}"`) : il est relu lui aussi, pour que la règle soit une seule.

## Goals / Non-Goals

**Goals** : le runtime Node de l'image reçoit des correctifs ; l'inventaire dit ce qui est installé et le reste après une montée Dependabot ; un écart échoue en CI.

**Non-Goals** : changer les versions des providers ; élargir le format de l'inventaire ; ajouter une veille de CVE (AUD-DEP-03).

## Decisions

### D1. Node 24 LTS si les deux providers passent, sinon dérogation datée — **décision en attente du propriétaire**

Recommandation : qualifier d'abord (tâche 1.2), puis **aligner sur la version de Windows** (même majeur, même version si l'archive Linux existe). Si scip-python échoue sous Node 24 : garder Node 20 pour lui n'est pas souhaitable (deux runtimes dans l'image) ; inscrire la dérogation au registre des risques avec une échéance et le dire dans l'inventaire (`limitation` exacte, mesurée).

Alternative écartée : Node 22 comme compromis. Il rapproche la fin de support sans aligner les plateformes.

### D2. L'inventaire est généré depuis les binaires

Le `RUN` qui écrit `provider-inventory.json` lit `dotnet --version`, `go env GOVERSION` (préfixe `go` retiré), `rustc --version` (deuxième champ) et `node --version` (préfixe `v` retiré) dans des variables de shell, et échoue (`set -eu`) si l'une est vide. Les versions des providers gardent leurs `ARG` (déjà contrôlés contre le catalogue).

### D3. Deux contrôles complémentaires

- **Statique** (`check-tools-manifest.py`) : dans le bloc d'inventaire, la valeur de `sdk`, `toolchain` et `runtime` ne contient aucun chiffre littéral ; un cas d'auto-test refusé et un accepté.
- **Sur l'image** (`qualify-docker-release.sh`) : pour chaque toolchain, la valeur de l'inventaire égale la sortie du binaire dans le conteneur ; échec nommé.

### D4. La limitation de Node disparaît ou devient exacte

Après qualification sous Node 24, le champ `limitation` de scip-typescript est retiré de l'inventaire et le commentaire `:89-90` réécrit. Une limitation n'est écrite que si elle a été mesurée.

## Direction des dépendances

Aucune dépendance de module modifiée. Le catalogue reste dans `minos-provider-scip` (ADR 0040).

## Qualification des capacités

- scip-typescript sous Node 24, Linux : **non qualifiée** au HEAD ; qualifiée par la tâche 1.2 (fixtures TypeScript existantes) et le gate exact-head.
- scip-python sous Node 24, Linux : **non qualifiée**, à mesurer ; partielle ou non supportée si la mesure échoue (D1).
- Windows : inchangé.

## Windows et Linux

Changement propre à l'image Linux. Le gate statique tourne dans `invariants` (Ubuntu) ; la comparaison aux binaires exige Docker et tourne dans `docker-release-validation.yml`. L'image est aussi construite sur le poste de l'utilisateur (`docker/scripts/mcp-lifecycle.ps1`) : le `RUN` ne doit dépendre d'aucun outil absent de l'image.

## Risks / Trade-offs

- Une montée de Node change le comportement d'un provider : couvert par les fixtures et le gate exact-head, qui n'est pas un check exigé et porte `paths-ignore` (à lancer explicitement, tâche 3.2).
- La sortie d'un `--version` peut changer de forme : le `RUN` échoue sur une valeur vide ou non numérique plutôt que d'écrire une valeur fausse.
- Collision de lignes avec `migrer-le-socle-vers-java-25` (`:6`) et `separer-l-image-de-requete` : ordre recommandé dans `SUIVI.md`.
