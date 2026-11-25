# Design

## Contexte

Motivation : voir `proposal.md` (Why). C'est un changement d'outillage : seuls un gate Python existant, son auto-test, une page de `docs/developer/` et la mise en œuvre d'un ADR changent. Le Dockerfile reste tel quel. Le design est réduit au contexte, à l'état vérifié, aux écarts, aux décisions, à la vérification et à la traçabilité.

## État vérifié au HEAD

Relu au commit `75e10c681` (PR #819), le 2026-11-25.

### Inventaire des contrôles d'intégrité de `docker/`

| Ce qui est téléchargé | Contrôle | Où | Gardé aujourd'hui par |
|---|---|---|---|
| Archive Maven 3.9.16 (deux miroirs, reprise `--continue-at`) | `sha256sum -c -` contre `MAVEN_ZIP_SHA256`, puis `unzip` | `docker/Dockerfile.mcp.release:163-178` (contrôle `:174`, `unzip` `:175`) | valeur de l'`ARG` : `check-tools-manifest.py` et `M29…Test:161` ; présence de `${MAVEN_ZIP_SHA256}` et de `sha256sum -c -` : `M29…Test:163`, `:168` |
| Node 24.20.0 | `sha256sum -c -` contre `NODE_LINUX_SHA256`, puis `tar` | `:183-190` (`:186`, `:188`) | valeur de l'`ARG` : `check-tools-manifest.py` ; aucune assertion sur le contrôle |
| Lanceur Coursier | contre `COURSIER_LINUX_SHA256`, puis `gunzip` | `:192-198` (`:194`, `:195`) | valeur : `check-tools-manifest.py`, `M29…Test:157` |
| scip-clang | contre `SCIP_CLANG_LINUX_SHA256`, puis `install` | `:200-205` (`:202`, `:203`) | valeur : `check-tools-manifest.py`, `M29…Test:158` |
| rust-analyzer | contre `RUST_ANALYZER_LINUX_GZ_SHA256`, puis `gunzip` | `:209-217` (`:211`, `:212`) | valeur : `check-tools-manifest.py`, `M29…Test:159` |
| Paquet NuGet de scip-dotnet | contre `SCIP_DOTNET_NUPKG_SHA256`, puis `dotnet tool install` depuis une source locale seule | `:240-260` (`:247`) | valeur : `check-tools-manifest.py`, `M29…Test:178-179` ; source locale : `M29…Test:181-182` |
| Paquets npm de scip-typescript et scip-python | `npm ci --ignore-scripts` avec les verrous du dépôt (intégrité par paquet) | `:219-235` | verrous du catalogue (`check-tools-manifest.py`, verrous de classpath) |
| scip-go | `go install` avec `GOSUMDB=sum.golang.org`, contournements vidés | `:262-270` | `M29…Test:184-188` (présences) |
| Graphe de scip-java | `cs fetch` / `cs bootstrap`, sans verrou du dépôt ; l'exécutable produit est consigné dans les preuves de l'image | `:277-300` | R-13 (écrit en commentaire, `:272-276`) |
| Paquets apt | instantané daté `UBUNTU_APT_SNAPSHOT`, dépôts signés | `:79`, `:122-140` | `M29…Test:151-152` (date exacte) |
| Images de base | `image:tag@sha256:digest` | `:2-4`, `:26`, `:37`, `:73` | `check-image-pins.py` |

`docker/Dockerfile.mcp` ne télécharge rien (`:12-44`).

| Fait | Preuve |
|---|---|
| Les six `ARG` d'empreinte sont exactement ceux que le catalogue attribue à l'image par la clé `sha256` de `dockerArgs` | `embedded-tools.json` lu par script : coursier (linux-x64), maven (any), nodejs (linux-x64), scip-clang (linux-x64), rust-analyzer (linux-x64), scip-dotnet-nupkg (any) ; `check-tools-manifest.py:274-292` (`expected_docker_args`) |
| Les six contrôles ont la même forme `printf '%s  %s\n' "${ARG}" <fichier> \| sha256sum -c -; \`, chacun dans un `RUN` ouvert par `set -eux` | `docker/Dockerfile.mcp.release:163`, `:183`, `:192`, `:200`, `:209`, `:240` |
| Le Maven `RUN` efface le fichier partiel entre deux miroirs (`rm -f`, `:172`) avant le contrôle : une ligne antérieure au contrôle peut légitimement nommer le fichier | `:165-173` |
| `check_dockerfile` compare les `ARG` au catalogue, refuse un `ARG …_SHA256` qu'il ne décrit pas et une empreinte écrite hors d'un `ARG` ; il ne lit pas les `RUN` | `scripts/quality/check-tools-manifest.py:295-327` |
| Un découpage en instructions numérotées existe déjà dans le gate (`dockerfile_instructions`, continuation par `\`) | `check-tools-manifest.py:330-341` |
| L'auto-test construit un Dockerfile minimal sans `RUN` de téléchargement ; un cas « second copy of a hash » écrit `RUN echo <sha> \| sha256sum -c -` | `scripts/quality/test_check_tools_manifest.py:69-73`, `:150-152` |
| Le gate et son auto-test sont lancés par le job `invariants` | `.github/workflows/pr-ci.yml:136`, `:140` |
| `fetch-verified.sh` publie atomiquement un fichier vérifié, passe au miroir suivant sur indisponibilité, échoue sans essayer d'autre miroir sur une empreinte fausse ; il est testé (6 cas) et câblé | `scripts/ci/fetch-verified.sh:1-54` ; `scripts/ci/test_fetch_verified.py:39-73` ; `.github/workflows/pr-ci.yml:227` |
| `fetch-verified.sh` écrit dans un fichier temporaire neuf à chaque miroir (pas de `--continue-at`), avec `--max-time 120` | `scripts/ci/fetch-verified.sh:30`, `:36` |
| Le contexte de construction de l'image n'est pas `docker/` : il est assemblé (jar et deux verrous npm) par `build-local-windows-candidate.ps1` et par `docker/scripts/mcp-lifecycle.ps1` pour l'installeur | `scripts/release/build-local-windows-candidate.ps1:86-97` ; `docker/Dockerfile.mcp.release:27`, `:219-220` |

### Mutations rejouées

Le 2026-11-25, le dépôt est extrait par `git archive HEAD` dans le scratchpad, une copie par mutation. Gates lancés avec la racine de la copie. Le harnais extrait le `RUN` Maven du Dockerfile de chaque copie et l'exécute avec le bash de Git pour Windows, avec de faux `curl` (copie d'un fichier local), `unzip` (pose un témoin), `ln` et `mvn`. Aucun `docker` n'est lancé.

| Mutation (copie) | 5 gates¹ | archive saine | archive altérée |
|---|---|---|---|
| Témoin | 0 | rc 0, `unzip` exécuté | **rc 1, `unzip` non exécuté** |
| `sha256sum -c - \|\| true` (Maven) | 0 | rc 0 | rc 0, **`unzip` exécuté** |
| `set -ux` au lieu de `set -eux` (Maven) | 0 | rc 0 | rc 0, **`unzip` exécuté** |
| contrôle déplacé après `unzip`, avec `\|\| true` | 0 | rc 0 | rc 0, **`unzip` exécuté** |
| `\|\| true` sur le contrôle de Node | 0 | non rejoué | non rejoué |
| contrôle du paquet NuGet supprimé | 0 | non rejoué | non rejoué |

¹ `check-toolchain-baseline.py`, `check-tools-manifest.py`, `check-workflow-pins.py`, `check-image-pins.py` ; `check-current-docs.py` lancé sur la copie `|| true` : 0.

Pour la copie `|| true`, les assertions `M29…Test:161-168` sont toutes vraies par lecture (chaque chaîne reste présente). Le test Java lui-même n'a pas été exécuté (pas de `mvnw` pendant la spécification). Non rejoué : le cas « aucun miroir » de l'audit.

## Écarts

| Écart | Source A | Source B | Suite proposée |
|---|---|---|---|
| L'audit nomme Node, Coursier, scip-clang et rust-analyzer en plus de Maven ; il oublie le paquet NuGet de scip-dotnet (`:247`), sixième archive vérifiée | `findings.json`, AUD-DEP-10, action | inventaire ci-dessus | la liste vient du catalogue : les six, et tout ajout futur |
| L'action de l'audit vise `\|\| true`. Retirer `-e` de `set -eux`, ou déplacer le contrôle après l'usage, neutralise tout autant le contrôle et passe les gates | `findings.json`, AUD-DEP-10, action | mutations `set -ux` et « après `unzip` » | D1 : `set -e…` en tête, aucun usage avant le contrôle |
| L'action propose une expression régulière dans le test Java (`M29…Test:168`) : elle ne serait jouée que par `Verify` (Maven, deux systèmes, une vingtaine de minutes) et resterait propre à Maven | `findings.json`, AUD-DEP-10, action | job `invariants` : gate Python en quelques secondes | D1 |
| L'option « déléguer à `fetch-verified.sh` » demande de copier le script dans un contexte de construction assemblé par deux scripts PowerShell, et perd la reprise `--continue-at` et les délais longs ajoutés contre les 429 de Maven Central (PR #815) | `findings.json`, AUD-DEP-10, action | `fetch-verified.sh:30`, `:36` ; `build-local-windows-candidate.ps1:86-97` | D2 : écartée |

## Exigences couvertes

- socle-d-execution › Chaque archive épinglée de l'image est vérifiée avant usage — D1.

## Décisions

### D1 — Gate Python dans `check-tools-manifest.py`, liste dérivée du catalogue [Proposition, recommandation]

Options :

- (a) **règle de `check-tools-manifest.py`** (recommandée). Il lit déjà le catalogue, le Dockerfile et ses instructions, et sait quels `ARG` portent une empreinte. Il tourne dans `invariants` en quelques secondes, sur chaque PR, et son auto-test permet un rouge sur arbre temporaire. Pour chaque `ARG` d'empreinte du catalogue :
  1. exactement une instruction `RUN` le consomme ;
  2. elle commence par `set -e` (options `-eux`, `-eu`… acceptées tant que `e` y figure) et ne contient ni `set +e`, ni `trap`, ni `||` sur la ligne du contrôle, ni `|| true`, ni `|| :` ailleurs dans l'instruction ;
  3. elle contient exactement une fois la ligne `printf '%s  %s\n' "${ARG}" <fichier> | sha256sum -c -; \` (la dernière instruction de la chaîne peut finir sans `\`) ;
  4. aucune ligne de l'instruction antérieure au contrôle ne nomme `<fichier>`, hormis une commande `curl` qui l'écrit et `rm -f` ;
  5. messages : chemin relatif, numéro de ligne, nom de l'`ARG`.
- (b) expression régulière dans `M29DockerAdministrationContractTest` (action de l'audit) : exécutée seulement par `Verify`, propre à Maven, et sans rouge rapide. Le test Java reste tel quel : ses assertions de présence ne contredisent pas la règle (proposition, hors périmètre).
- (c) les deux : deux implémentations de la même règle, qui divergeront.

Ce que la règle ne prouve pas, et que la docstring écrira : qu'un contrôle bien formé porte sur le bon fichier sur le disque (le harnais de la tâche 1.1 le montre pour Maven), ni que l'image se construit (qualification Docker).

### D2 — `fetch-verified.sh` n'est pas utilisé dans l'image [Proposition, recommandation]

Le script est sain et testé, mais l'adopter dans l'image demande trois choses. Il faut le copier dans un contexte de construction assemblé ailleurs (`build-local-windows-candidate.ps1`, `mcp-lifecycle.ps1`), donc changer deux scripts de livraison et la liste des fichiers livrés. Il faut lui ajouter la reprise et des délais réglables pour retrouver le comportement de la PR #815. Et il faut faire tourner la qualification Docker pour six blocs. La règle (a), qui ne coûte qu'un gate existant, couvre le même risque, la régression du contrôle, sans toucher à l'image. Option à reconsidérer si un septième téléchargement apparaît avec une forme différente.

## Vérification

- Rouge : les cas de 1.2 échouent tant que la règle n'existe pas ; sur les copies de 1.1, le gate actuel rend 0.
- Correction : `python scripts/quality/check-tools-manifest.py` vert sur le dépôt (six contrôles reconnus, nombre affiché) ; auto-test vert ; sur chaque copie mutée, le gate rend 1 et nomme l'`ARG` et la ligne.
- Windows et Linux : bibliothèque standard ; lecture du Dockerfile indépendante des fins de ligne (`splitlines`) ; le Dockerfile ne change pas, la qualification Docker n'est donc pas requise par ce changement (`scripts/ci/docker-gate.py` : `scripts/quality/` ne fait pas partie des chemins concernés, à vérifier par `test_docker_gate.py`).

## Documentation durable à mettre à jour

- `docs/developer/quality-gates.md`, § « Outils livrés : une seule description » : une phrase sur la règle.
- ADR BE-0065, « Compliance and Enforcement », ligne de `check-tools-manifest.py` : « chaque archive épinglée de l'image est vérifiée avant usage ». C'est la mise en œuvre complétée, la décision est inchangée.
- `docs/architecture/diagrams/capacites/socle-d-execution.md` : vue du gate (`:25`, `:41`) et ligne de traçabilité de l'exigence ajoutée, à l'archivage.

## Traçabilité

| Constat | Exigence | Décision | Tâches | Preuve exécutée (2026-11-25, poste Windows) | Preuve manquante |
|---|---|---|---|---|---|
| AUD-DEP-10 | socle-d-execution › Chaque archive épinglée de l'image est vérifiée avant usage | D1 (a), D2 | 1.1-1.4, 2.1-2.3 | mutations sur copie (gates verts) ; harnais du `RUN` Maven : témoin rc 1 sans `unzip` sur archive altérée, trois mutations rc 0 avec `unzip` ; implémentation (2026-11-25) : tableau « Mutations rejouées » rejoué sur copies `git archive` (six copies, `check-tools-manifest.py --root` rend 0 pour les cinq mutations avant la règle, harnais identique au tableau), puis 1 après la règle en nommant l'`ARG` et la ligne (témoin : 0) ; auto-test : 16 cas nouveaux en erreur contre le gate d'avant (copie du scratchpad), 62 tests verts après ; dépôt : six archives vérifiées (`verified archives=6`) ; `sync-tools-manifest.py`, `test_build_embedded_tools.py`, `check-image-pins.py`, `check-toolchain-baseline.py`, `test_docker_gate.py`, `check-ci-wiring.py`, `check-comment-language.py`, `check-single-execution.py`, `archgate check` (17/17) verts | test Java `M29…` non exécuté (consigne, tâche 2.3 en CI) ; construction Docker non lancée (consigne) ; dossier de capacité et ligne du suivi d'audit (à l'archivage) |
