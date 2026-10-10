# Quality gates MINOS

MINOS mesure la couverture et qualifie les frontières critiques avec des gates reproductibles. Un pourcentage global n'est pas un objectif produit : les seuils ciblent les responsabilités dont une régression serait significative.

Dernière réconciliation : **9 août 2026**, campagne post-audit #132 / PR #135.

## Gate de PR autoritatif

`.github/workflows/pr-ci.yml` s'exécute sur les PR et pushes `main` / `develop`.

La matrice obligatoire couvre :

- Ubuntu : Java 24, PostgreSQL/pgvector Testcontainers réel et fail-closed, Maven `clean verify`, JaCoCo complet et Product Facts ;
- Windows : Java 24, Maven `clean verify`, JaCoCo hors scope PostgreSQL déjà qualifié sur Linux et Product Facts ;
- OSV Scanner bloquant ;
- vérification de l'immutabilité des références GitHub Actions ;
- installation de `bubblewrap`, util-linux et du profil AppArmor officiel `bwrap-userns-restrict` sur Ubuntu afin que la qualification du worker sandbox Linux puisse réellement exercer les namespaces non privilégiés.

Les workflows spécialisés IntelliJ, Windows Installer et Windows in-place upgrade complètent cette matrice selon les chemins modifiés. Les workflows de publication restent séparés et ne remplacent jamais la qualification de PR. Les anciens workflows M19 et M20, qui répétaient le job `verify` Ubuntu sur une partie des chemins, sont retirés (constat C2, voir ci-dessous).

## Chaque gate lourd s'exécute une seule fois par PR (C2)

Sur une PR, un seul workflow lance un build Maven : `pr-ci.yml`, job `verify`, une fois sous Ubuntu et une fois sous Windows (la matrice est voulue : seule la suite Windows exerce AppContainer, les Job Objects et les chemins Windows). Les quatre contrôles coûteux qu'un second workflow aurait tendance à recopier ne s'exécutent qu'à cet endroit :

| Contrôle | Où, combien de fois |
|---|---|
| `./mvnw … clean verify` | `verify` Ubuntu (PostgreSQL exigé) et `verify` Windows : une fois chacun |
| `scripts/quality/check-jacoco.py` | `verify` Ubuntu (périmètre complet) et `verify` Windows (`--skip-scope m30-postgresql-pgvector`) : une fois chacun |
| `scripts/docs/product-facts.py --check` | job `invariants` : une fois |
| `scripts/ci/install-linux-sandbox-toolchain.sh` et la délégation cgroup v2 | `verify` Ubuntu : une fois |

Le gate `python scripts/quality/check-single-execution.py` (auto-test `test_check_single_execution.py`, branché dans le job `invariants`) échoue si un autre workflow démarré par `pull_request` ou `push` lance l'un de ces contrôles, si `pr-ci.yml` en lance un plus d'une fois par système, ou s'il n'en lance plus aucun (un gate dont l'unique exécutant a disparu ne tourne plus). Les workflows lancés à la main (`m0-java-ci.yml`, `historical-qualification.yml`, les publications) sont des outils de rejeu, pas des gates de PR, et sont hors de son champ.

**Ce que faisaient `m19-advanced-code-intelligence.yml` et `m20-semantic-hybrid-intelligence.yml` (supprimés)** : sur `pull_request` vers `main` ou `develop`, filtrés par chemins (M19 : `minos-domain`, `minos-engine`, `minos-application`, `minos-api`, `minos-mcp`, quelques documents ; M20 : les mêmes plus `minos-storage-local`, `minos-nexus`, `docs/developer/**`, `docs/user/**`), ils installaient Java 24 et la chaîne de bac à sable Linux, délégaient un cgroup v2, jouaient `product-facts.py --check`, puis `./mvnw -B -ntp clean verify` sur le reactor entier (aucun test ni module propre à M19 ou M20) et `check-jacoco.py`. Chacune de ces affirmations est portée, à l'identique ou en plus strict (PostgreSQL réel exigé), par `pr-ci.yml`, qui n'a aucun filtre de chemins et couvre les mêmes branches. Leurs checks `M19 Java 24 qualification` et `M20 Java 24 qualification` n'étaient pas exigés par le contrôle de branche. L'inventaire complet avant/après est dans `docs/audit/S23-SUIVI.md`, « Lot 3 ».

## Gardes d'architecture (`check-module-boundaries.py`)

`python scripts/architecture/check-module-boundaries.py` (job `invariants`, auto-test `test_check_module_boundaries.py`) applique, sans compilation :

- **A7** : la liste `MODULES` est exactement le reactor ; **A2** (ADR 0022, 0042, 0058) : politique de dépendances Maven par module, règles hexagonales (application, surfaces, adaptateurs, racine de composition), aucune dépendance interne cachée dans un `<profile>` ou un `<dependencyManagement>`, aucune source qui nomme une classe d'adaptateur hors `minos-bootstrap` ; **A3** (ADR 0044) : un package, un module.
- Une dépendance est **interne** parce que son `artifactId` est celui d'un module, avec le `groupId` lu dans le POM racine : un `groupId` non résolu (`${…}`) ou voisin est refusé. `sourceDirectory`, `testSourceDirectory` et les `includes`/`excludes` du compilateur sont refusés dans un `<profile>` comme à la racine (les `plugins` d'un profil restent permis).
- **A8, cycles de packages en cliquet** : le graphe package → package (imports et noms qualifiés des 14 modules et de `minos-intellij`, hors reactor) ne doit contenir aucune composante cyclique absente de `KNOWN_PACKAGE_CYCLES`, et chaque entrée de la table doit rester exactement une composante : la table ne peut que rétrécir, par une édition visible, quand un cycle est levé. Limites : approximation par imports (pas de bytecode, ni réflexion ni `ServiceLoader`), et une arête ajoutée à l'intérieur d'un cycle listé n'est pas vue. Les quatre cycles actuels (15 packages) sont levés par le changement `casser-les-cycles-de-packages`, à ouvrir.
- **A9, documentation courante** : pour chaque titre `### minos-…` de `docs/architecture/arc42/05-vue-blocs.md`, la ligne à puce « Dépendances » doit citer exactement les dépendances internes directes du POM (toutes portées ; « tous les modules » n'est admis que pour `minos-app`). Une section ou une ligne absente est un échec. Les diagrammes de couches (`c4-container.md`) se déclarent simplifiés et ne sont pas gardés ; le fichier généré `diagrams/module-dependencies.md` fait foi et reste contrôlé à l'identique (`--write-doc` pour le régénérer).
- L'auto-test échoue si une fonction `check_*` du script n'est référencée par aucun test, et rejoue des mutations sur une copie des vrais POM.

## Supply-chain des workflows

Le gate reproductible est :

```text
python scripts/quality/check-workflow-pins.py
```

Toute action externe durable doit être référencée par un SHA de commit immuable. Le commentaire de version (`# vN`) reste présent pour la lisibilité humaine. Les installations Chocolatey utilisées dans le packaging doivent également fixer leur version.

Le même gate refuse aussi :

- toute interpolation `${{ ... }}` directement à l'intérieur d'un bloc `run:` (hors valeurs GitHub à énumération fermée comme `steps.*.outcome`) : une valeur externe (input `workflow_dispatch`, SHA, métadonnées PR/tag) doit toujours traverser la frontière `env:` avant d'atteindre le shell ;
- un workflow qui installe Inno Setup via Chocolatey sans résoudre et exporter un `ISCC_PATH` déterministe, ou sans transmettre `-IsccPath` au script de build correspondant ; `scripts/release/build-windows-installer.ps1` doit utiliser exactement ce binaire sur le chemin release/CI, sans recherche ambiguë via PATH ;
- toute invocation susceptible de compiler un setup (`publish-windows-release.ps1`, `build-local-windows-candidate.ps1`) qui ne propage pas `-IsccPath` **et** `-RequiredIsccVersion` — y compris l'appel `-SkipBuild -ValidateOnly`, qui compile encore le setup smoke ; seul `-PublishOnly`, qui ne compile rien, est exempté ;
- un `build-windows-installer.ps1` qui n'assérerait plus la version moteur remontée par le compilateur réellement exécuté, ou qui accepterait `-IsccPath` et `-RequiredIsccVersion` séparément ;
- des filtres `paths` de `windows-installer.yml` qui ne couvriraient plus `release-windows.yml` et le gate lui-même, ou qui divergeraient entre `pull_request` et `push`.

La provenance du compilateur Inno Setup repose sur deux couches distinctes : les métadonnées Chocolatey prouvent ce qui a été **installé**, tandis que la ligne `Compiler engine version: Inno Setup X.Y.Z` émise pendant la compilation prouve ce qui a réellement **compilé** le setup. Seule la seconde est autoritative ; ses règles d'analyse et de rejet sont couvertes par `scripts/release/test-iscc-provenance.ps1`.

La supply-chain produit applique le même principe : images de base par digest OCI, launcher Coursier par commit immuable + SHA-256 attendu, et binaires providers téléchargés avec checksum attendu avant exécution.


## Supply-chain des images de conteneur

Le gate reproductible est :

```text
python scripts/quality/check-image-pins.py
python scripts/quality/test_check_image_pins.py
```

Toute image référencée par `docker/Dockerfile*` (`FROM`) et `docker/compose*.yaml` (`image:`, y compris le défaut d'un `${VAR:-défaut}`) doit s'écrire `<image>:<tag>@sha256:<digest d'index>`. Le digest est l'épinglage immuable ; le tag est ce que Dependabot suit pour proposer un nouveau digest (une référence avec digest sans tag est ramenée à `latest`). Seule `${MINOS_IMAGE}`, l'image MINOS elle-même (construite ou chargée par le workflow de release), échappe à la règle.

Une image de service managé (pgvector, ollama) n'a **qu'une** source : le défaut de `MINOS_POSTGRES_IMAGE` / `MINOS_OLLAMA_IMAGE` dans `docker/compose-mcp.connected.yaml`. Le configurateur `configure-m30-docker-services.ps1` ne la réécrit que sur `-PostgresImage` / `-OllamaImage` explicites, et les tests PostgreSQL (`PostgresTestSupport`) la lisent dans ce fichier ; le gate refuse toute seconde copie dans les scripts, workflows ou sources.

Un second gate, `python scripts/quality/check-compose-limits.py` (auto-test `test_check_compose_limits.py`, 25 cas), exige que chaque service des deux fichiers compose tire son plafond d'un bloc `x-limits-<rôle>` (une définition par rôle, surchargeable par variable, jamais écrite dans un service) : défauts **mémoire et PID strictement positifs** (ni vide, ni `0`, ni `-1`, ni `0g`, qui signifient « illimité » pour Docker), **CPU par défaut `0` (aucun plafond) ou au plus 1** (le démon refuse un `cpus` supérieur au nombre de CPU de l'hôte ; 1 CPU démarre partout ; ne pas relever la valeur) ; les variables et leurs défauts figurent dans `docs/user/docker-runtime.md` et dans `docker/.env.example`. **Ce qu'il compare** : les blocs des deux fichiers *normalisés par son analyseur de lignes* (pour chaque clé, le nom de variable et son défaut ; ordre, commentaires et espaces ignorés), pas le texte brut ni un parse YAML (PyYAML n'est pas garanti sur l'interpréteur de la CI) ; ce que Compose fait ensuite de l'ancre et de la fusion `<<` n'est pas rederivé (se vérifie avec `docker compose config`). Les deux gates d'images et de plafonds, et leurs auto-tests, sont exécutés par le job `invariants` de `.github/workflows/pr-ci.yml`. **Ce que chaque gate vérifie lui-même** (analyse par lignes du YAML, sans parseur) : le job `invariants` existe, il contient une étape `run: python <gate>` et une étape `run: python <auto-test>`, et ni le job ni ces étapes ne portent de condition `if:` ; les étapes d'un autre job, une ligne commentée ou une étape conditionnelle ne comptent pas (auto-tests dédiés). **Ce qu'il ne prouve pas** : que le job se déclenche sur les événements voulus (`on:`), ni l'absence de condition au niveau du workflow, ni que l'exécution a réussi. Le YAML a été validé hors CI par `rhysd/actionlint`.

Dependabot (écosystème `docker-compose`, `directory: "/docker"`) met à jour ces défauts parce que les fichiers s'appellent `compose-mcp.prod.yaml` et `compose-mcp.connected.yaml` : son motif de nom n'accepte qu'un segment pointé. Le même gate refuse un fichier compose de `docker/` dont le nom sort de ce motif (l'ancien `compose.mcp.prod.yaml` serait invisible) et un `.github/dependabot.yml` sans entrée `docker-compose` pour `/docker` (analyse par lignes, sans parseur : il ne prouve pas que Dependabot s'exécute). Détail et limites : `docs/developer/supply-chain.md`.

## Outils livrés : une seule description

```text
python scripts/quality/check-tools-manifest.py
python scripts/quality/test_check_tools_manifest.py
python scripts/release/test_build_embedded_tools.py
python scripts/release/sync-tools-manifest.py            # --write pour réécrire les ARG du Dockerfile
```

`minos-provider-scip/src/main/resources/com/minos/adapter/scip/runtime/embedded-tools.json` décrit une fois chaque outil que MINOS livre ou installe : version, artefact épinglé (URL HTTPS, SHA-256, taille, licence), et pour le zip Windows la mention `embedded`. Ses consommateurs : les gestionnaires Java (qui lisent le catalogue au démarrage et n'écrivent aucune empreinte), `docker/Dockerfile.mcp.release` (dont les lignes `ARG` doivent porter exactement ses valeurs, `sync-tools-manifest.py --write` les réécrit) et `scripts/release/build-embedded-tools.py` (appelé par `build-windows-distribution.ps1`, qui assemble `tools/` et `TOOLS-MANIFEST.json`). Le gate refuse : un `ARG` qui diffère ou une empreinte écrite deux fois dans le Dockerfile, un SHA-256 ou un commit littéral dans les sources Java du provider, une version qui diffère de `ScipIndexerCatalog`, un script de distribution qui porte sa propre liste. Avec `--distribution <dossier>` il vérifie aussi un paquet construit : `tools/` contient exactement les composants que le catalogue marque `embedded`, chaque fichier a la taille et le SHA-256 consignés (un artefact épinglé porte celui du catalogue), rien d'autre n'est livré, le SBOM et les notices nomment chaque composant.

### Qualification « machine neuve, sans réseau » (ADR 0040, lot 5)

`scripts/release/qualify-offline-install.ps1 -Package minos-<version>-windows-x64.zip` est un critère de publication local, jamais lancé par la CI. Il refuse de démarrer tant que la machine atteint le réseau (résolution DNS et connexion TCP sondées), installe le ZIP complet dans un répertoire jetable avec l'installeur portable (ni PATH, ni client MCP, ni Docker), exécute `tools verify --all --format json` (scip-typescript `READY` avec `tools origin: …=embedded` ; scip-java : outils embarqués présents, seuls des `machine prerequisite` manquent), indexe la fixture TypeScript `typescript-simple` avec `MINOS_TOOLS_OFFLINE=1`, et vérifie qu'un proxy canari local (JVM et variables de proxy) n'a vu aucune tentative de connexion et que `tools/` du répertoire d'installation n'a pas changé. Sortie : `OFFLINE QUALIFICATION PASS`, `FAIL` ou `NOT RUN` ; `-AllowOnline` ne sert qu'à répéter le scénario (le verdict le dit et ne qualifie rien). L'indexation Java n'en fait pas partie (voir `docs/audit/S23-SUIVI.md`, D1-L1).

`qualify-offline-install.ps1 -ObserveNetwork` ajoute, en lecture seule (aucun réglage modifié : `netstat -ano`, table des processus, `Get-DnsClientCache`), l'observation de tout l'arbre de processus lancé par le script : échantillonnage toutes les ~100 à 300 ms des connexions TCP (et des points de terminaison UDP, à titre d'information) des PID descendants, comparaison du cache DNS avant et après. Hors ligne, il n'a pas de témoin positif possible et le sondage hors ligne fait foi ; en ligne, il exige d'abord deux témoins (un `connect()` direct vers github.com tenu ouvert et une résolution DNS d'un nom inédit, tous deux vus par l'échantillonneur), puis ZÉRO connexion hors boucle locale et aucune résolution d'un hôte de téléchargement d'outils causée par la run MINOS. Limite : une connexion TCP ouverte puis fermée entre deux échantillons n'est pas vue ; le proxy canari et `MINOS_TOOLS_OFFLINE=1` restent en place, et la coupure physique du réseau reste l'essai du propriétaire. Auto-test sans Internet : `scripts/release/test-network-observer.ps1` (analyse de `netstat`, connexion directe vers l'adresse non bouclée de la machine, comparaison de caches DNS).

`scripts/release/qualify-seeding-container.ps1 -Distribution <dossier de la distribution complète>` répète la partie « amorçage et refus » dans un conteneur `docker run --network none` (image Java 24 locale, charge Windows montée en lecture seule, `MINOS_TOOLS_OFFLINE=1`) : la charge valide est copiée, vérifiée et extraite (origine `embedded`), la même charge dont une archive Node.js a un octet modifié est refusée (`INVALID`) sans téléchargement. Rien n'y est exécuté (la charge est Windows) ; c'est une preuve d'amorçage, pas d'indexation.

## JaCoCo

Le reactor exécute `jacoco:prepare-agent` et produit un rapport par module pendant `verify`. `minos-app` produit en plus le rapport agrégé :

```text
target/site/jacoco-aggregate/index.html
target/site/jacoco-aggregate/jacoco.xml
```

Le gate reproductible est :

```text
python scripts/quality/check-jacoco.py
```

Chaque préfixe déclaré dans un scope doit désigner au moins une classe réellement présente dans le rapport. Sans cette règle, une classe renommée ou supprimée cesse silencieusement d'être mesurée dès qu'un préfixe voisin du même scope continue de matcher, et le scope reste `PASS` sur une surface qui rétrécit. Un préfixe mort provoque désormais un `FAIL` explicite qui le nomme. La logique de décision du gate est elle-même vérifiée par :

```text
python scripts/quality/check-jacoco.py --self-test
```

Le résultat machine-readable est écrit par défaut dans :

```text
target/m21-quality/jacoco-gate.json
```

### Seuils ciblés actuels

| Scope | Ligne | Branche |
|---|---:|---:|
| domaine / invariants | 35 % | 20 % |
| persistance + cache + indexes | 50 % | 35 % |
| résolution projet | 70 % | 50 % |
| API publique | 31 % | 21 % |
| mapping MCP | 30 % | 20 % |
| Program Graph | 50 % | 30 % |
| provider Java avancé | 45 % | 25 % |
| impact / sécurité avancés | 47 % | 27 % |
| semantic vector store | 45 % | 20 % |
| provider sémantique Ollama | 52 % | 32 % |
| recherche hybride sémantique | 50 % | 30 % |
| API avancée M19/M20 | 45 % | 25 % |
| catalogue MCP M19/M20 | 50 % | 30 % |
| plateforme provider polyglotte M24 | 30 % | 15 % |
| indexation remote/distribuée M25 | 47 % | 27 % |
| runtime dynamique M26 | 55 % | 35 % |
| control plane hosted/team M27 | 45 % | 25 % |
| routing backend M29 | 55 % | 30 % |
| sélection storage M30 | 52 % | 32 % |
| PostgreSQL/pgvector M30 | 47 % | 27 % |

Le seuil API publique reste supérieur au baseline historique 30/20 tout en restant soutenu par la couverture mesurée. Une baisse de seuil exige une justification documentée dans la PR. Une hausse doit être soutenue par des tests qui prouvent un comportement utile, pas par du code artificiellement exercé pour augmenter un compteur.

## Preuves fonctionnelles séparées

Une ligne couverte ne prouve pas un contrat fonctionnel. JaCoCo reste complémentaire des preuves suivantes :

- replays CLI/API/MCP ;
- fixtures providers et Program Graph ;
- précision/rappel et vérités terrain contrôlées ;
- promotion de snapshot et états STALE/recovery ;
- budgets de contexte ;
- PostgreSQL/pgvector réel ;
- OSV et supply-chain ;
- packaging et smoke tests ;
- IntelliJ Plugin Verifier ;
- installateur Windows exact-head ;
- tests négatifs de confinement et de sandbox OS.

## Qualification sandbox OS

Le backend worker n'annonce `OS_ENFORCED` que si la primitive actuelle peut réellement être exercée.

- Linux : `bubblewrap` + namespaces OS, racine hôte en lecture seule, capacités supprimées, network namespace isolé pour `DENY`, **frontière de job cgroup v2** (`memory.max`, `memory.swap.max`, `pids.max`, `cpu.max`, `cgroup.kill`) et sonde de capacité au runtime ; les limites `prlimit` restent une défense en profondeur par processus, jamais une garantie agrégée ;
- Windows : AppContainer avec ensemble de capabilities vide pour `DENY` ou seule capability `internetClient` pour `ALLOW`, validation `TokenIsAppContainer`, ACL temporaires sur les racines gérées par MINOS et Job Object configuré avant la création du processus (mémoire, processus, CPU, job time, kill-on-close, terminaison explicite, breakaway interdit) ;
- absence de primitive qualifiée — y compris l’absence de délégation cgroup v2 — : backend process-only conservé pour le diagnostic, mais `ALLOW` et `DENY` sont rejetés avant toute exécution remote du provider.

### Gate MINOS-01

Le gate reproductible du confinement agrégé est :

```text
python scripts/remediation/check-minos-01.py
```

Il interdit de revenir à un simple contrôle par processus, de supprimer la sonde de capacité, de retirer le quota d’écriture supervisé ou de supprimer les tests adversariaux de confinement. Le job Ubuntu de `pr-ci.yml` provisionne en plus une racine cgroup v2 déléguée (`MINOS_SANDBOX_CGROUP_ROOT`) afin que ces tests s’exécutent réellement.

Cette délégation est **contenue** : le script de provisioning effectue lui-même, pendant sa phase privilégiée, l'unique migration nécessaire (`--attach-pid` place le shell du workload dans `$ROOT/minos-controller`). Le compte MINOS ne reçoit jamais de droit sur `/sys/fs/cgroup/cgroup.procs` — un tel droit lui permettrait de sortir de sa propre frontière de délégation. Parce que chaque bloc `run:` de GitHub Actions est un shell distinct, l'étape qui exécute réellement le workload doit passer `--attach-pid $$` elle-même ; `scripts/remediation/check-p0-p2.py` vérifie ces deux invariants.

La campagne #135 ajoute une preuve exact-head Linux/Windows qui interdit explicitement les skips et exécute également le chemin réel `ProcessIndexerExecutor → sandbox → provider → artefact`.

## SonarCloud

SonarCloud est une preuve complémentaire lorsqu'il est configuré par le dépôt/service et exécuté sur le candidat concerné. Il ne remplace ni Maven, ni les scopes JaCoCo ciblés, ni les gates fonctionnels. Aucune configuration Sonar ou secret ne doit être inventé dans une PR uniquement pour fabriquer un PASS.

## Exclusions et limites

Aucune classe critique explicitement ciblée n'est exclue du gate. Les classes d'assemblage, DTO simples, renderers et adapters non listés restent visibles dans le rapport agrégé mais ne portent pas nécessairement de seuil individuel.

La règle durable reste : **prouver les comportements critiques, échouer de façon fail-closed lorsque la preuve manque, puis relever progressivement les seuils quand les tests le justifient**.
