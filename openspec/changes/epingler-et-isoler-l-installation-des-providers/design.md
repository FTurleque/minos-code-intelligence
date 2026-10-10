# Design

## Context

Sprint 2 de l'audit du 10 octobre 2026 : **AUD-SEC-02**. HEAD analysé : `465e970d` (`develop`). Chaîne concernée : `minos tools install <provider>` → `ManagedScipProviderRuntimeManager` / `ManagedScipPythonRuntimeManager` / `ManagedPolyglotScipRuntimeManager` → commandes externes (`coursier`, `npm`, `dotnet`, `go`) lancées par des `ProcessBuilder` locaux, par un lanceur de confiance **hors bac à sable** (le bac à sable ne s'applique qu'à l'indexation, `ProcessIndexerExecutor`).

## État vérifié au HEAD

### AUD-SEC-02 — confirmé, périmètre de l'environnement plus large que l'audit

- `ManagedScipProviderRuntimeManager.java:292-309` (`installJava`) : si `classpath.txt` n'existe pas, `run(["coursier","--help"])` (`:301`) puis `run(scipJavaInstallationProbe(coursier))` (`:303`) ; `:311-314` : `coursier launch org.scip-code:scip-java:0.13.1 --jvm system --main org.scip_code.scip_java.ScipJava -- --version` ; `:316-…` `requireExpectedScipJavaVersion` lit le journal pour la ligne `scip-java version 0.13.1`. **Aucun hachage** de jar.
- `ManagedScipProviderRuntimeManager.java:747-752` (`run`) : `new ProcessBuilder(command)`, `directory`, `redirectErrorStream`, **sans** `environment().clear()` ni liste d'autorisation. Le même `run` lance `npm ci … --ignore-scripts` pour scip-typescript (`:276-278`). `ManagedScipPythonRuntimeManager.java:251-256` : copie de `run` pour `npm ci` (scip-python, `:126-132`), même défaut. `ManagedPolyglotScipRuntimeManager.java:450-455` : `builder.environment().putAll(overrides)` sur l'environnement hérité, pour `dotnet tool install` (`:261-266`, source NuGet locale épinglée) et `go install` (`:296`, `GOPROXY`/`GOSUMDB` forcés mais le reste hérité), et pour les **sondes** `dotnet --version` / `go version` (`:155`, `:242`, appel `run(command, home, PROBE_TIMEOUT, Map.of())`).
- Catalogue : `embedded-tools.json:231-239` — `scip-java-classpath`, plateforme `windows-x64` seule, `payload: assembled/scip-java-classpath-0.13.1-windows-x64.zip`, recette `coursier-fetch`. Pas d'entrée Linux. ADR 0040 (§ Mise en œuvre) : les arbres assemblés « n'ont aucun épinglage amont et sont comparés au manifeste du paquet signé, plus faible » : même sous Windows, le classpath n'est pas épinglé **par jar** dans le catalogue.
- Contraste : `ProcessIndexerExecutor.java:291-299` retire l'environnement (`applyForTrustedLauncher` pour un lanceur de confiance, `apply` pour un provider).
- Image Docker : `docker/Dockerfile.mcp.release:175-197` exécute `cs launch` puis `cs bootstrap --standalone` à la construction ; le commentaire reconnaît que « le graphe transitif complet n'est pas représenté par un fichier de verrou du dépôt ». C'est un choix connu du mainteneur, non du poste de l'utilisateur.
- À l'**indexation** sous Linux, `ScipJavaProcessPlanFactory.java:147-156` construit encore `coursier launch <coordonnée>` (le fichier de classpath est « Unused off Windows », Javadoc `:54-56`).
- `TRUSTED_LAUNCHER_KEYS` (`ProviderProcessEnvironment.java`) est une liste **mesurée pour les lanceurs PowerShell Windows** (`SystemRoot`, `ComSpec`, `PSModulePath`, `PSModuleAnalysisCachePath`…) : sous Linux elle ne conserverait que `PATH`, `TEMP` et `TMP`, sans `HOME`, dont `npm`, `go` et Coursier ont besoin. La classe est de plus **de visibilité package** dans `minos-runtime-local`. L'action de l'audit (« utiliser `applyForTrustedLauncher` plus les variables de proxy et de cache nécessaires ») n'est donc pas applicable telle quelle ; la liste des **providers** (`COMMON_SAFE_INHERITED_KEYS` : `PATH`, `JAVA_HOME`, `TMPDIR`, `LANG`, `GOPATH`, `GOCACHE`, `COURSIER_CACHE`…) est la bonne base mais n'a pas non plus `HOME` hors Windows (il est dans la liste Windows seulement).

### Écarts avec l'action proposée par l'audit

| Action de l'audit | Relevé | Retenu |
|---|---|---|
| Publier l'artefact « assembled » Linux **ou** vérifier chaque jar contre une liste SHA-256 | Les deux sont possibles ; l'assemblé est de la « nature faible » de l'ADR 0040 | Verrou par jar (D1) |
| `applyForTrustedLauncher` + proxy et caches | Liste Windows, package-private, sans `HOME` Linux | Façade publique fondée sur la liste des providers (D3) |
| Ne plus lancer scip-java pour sonder, lire le manifeste du jar | Le manifeste du jar n'est pas établi ; l'empreinte vérifiée suffit | Version = verrou (D1) |
| (non cité) | Sondes `dotnet`/`go` et `npm ci` héritent aussi de l'environnement | Inclus (D3) |

## Goals / Non-Goals

**Goals** : aucun secret de MINOS dans l'environnement d'une commande d'installation ou de sondage ; plus aucun code scip-java exécuté à l'installation avant d'avoir vérifié son empreinte ; l'intégrité de l'installation Linux ne dépend plus de l'amont ni du miroir de l'utilisateur.

**Non-Goals** : exécuter l'installation dans le bac à sable ; changer le plan d'indexation Linux (D2) ; épingler le build Docker ; supporter des variables d'environnement d'authentification (D4).

## Decisions

### D1. Verrou par jar plutôt qu'artefact assemblé Linux — **décision en attente du propriétaire**

Le verrou est une liste `nom du jar → SHA-256` (et taille) du résultat de `coursier fetch --classpath org.scip-code:scip-java:0.13.1`, produite à la construction par la recette `coursier-fetch` de `scripts/release/build-embedded-tools.py` (qui copie déjà chaque jar et lit sa coordonnée Maven pour le SBOM), stockée en ressource de `minos-provider-scip` et déclarée dans `embedded-tools.json` comme composant « verrou ». À l'installation (toutes plateformes sauf livraison embarquée Windows) : `coursier fetch --classpath <coordonnée>` (jamais `launch`), puis comparaison **ensemble à ensemble** (aucun jar absent, aucun en trop, chaque empreinte égale) avant d'écrire `classpath.txt` ; le moindre écart supprime le résultat partiel et échoue avec un message qui nomme le jar (nom de fichier seul, pas de chemin absolu).

| Option | Intégrité | Coût |
|---|---|---|
| **A : verrou par jar (recommandée)** | Plus forte que l'assemblé : chaque jar est épinglé, l'amont et un miroir ne peuvent rien substituer | Un fichier à régénérer à chaque montée de version (le gate de catalogue la rend obligatoire) |
| B : zip « assembled » `linux-x64` | Même nature faible qu'aujourd'hui sous Windows (manifeste du paquet signé) | Un artefact de plus à publier et à signer |
| C : les deux | Redondant | |

Avant de figer l'option A, la tâche 2.1 compare la résolution Linux à celle du zip Windows (mêmes jars ? mêmes empreintes ?) : si les deux résolutions diffèrent (classifieurs propres à la plateforme), le verrou devient un verrou **par plateforme**, ce qui reste dans l'option A.

La version n'est plus « sondée » : les empreintes du verrou désignent l'artefact 0.13.1, et la vérification du jar `scip-java-0.13.1.jar` du verrou remplace la ligne `scip-java version 0.13.1` du journal. `scipJavaInstallationProbe`, `requireExpectedScipJavaVersion` et leurs tests (`ManagedScipProviderRuntimeManagerTest.java:37`, `:134-140`) sont remplacés par des tests du vérificateur.

### D2. Le plan d'indexation Linux est hors de ce changement — **décision en attente du propriétaire**

Épingler à l'installation ferme la fenêtre « télécharger puis exécuter sans vérifier » du constat. Le plan d'indexation Linux relance toutefois `coursier launch` (dans le bac à sable, sans réseau : il lit le cache rempli à l'installation). Lui faire consommer `classpath.txt` (`java -cp` avec liaison en lecture seule des jars) fermerait aussi le risque d'un cache altéré après coup par le **même utilisateur** (compromission déjà équivalente à celle du compte).

Recommandation : **hors périmètre ici**, à ouvrir comme changement séparé de confinement (candidat naturel du sprint 8, « Confinement et E/S privées »), parce qu'il modifie le plan bubblewrap, qui s'exécute sous la qualification de confinement exigée en CI (`-Dminos.sandbox.tests.required=true`). Le risque résiduel est écrit dans `docs/user/polyglot-providers.md`.

### D3. Une façade publique d'environnement, fondée sur la liste des providers

Dans `minos-runtime-local`, une classe publique (nom proposé : `InstallationProcessEnvironment`, méthode `apply(ProcessBuilder, Map<String,String> declared)`) applique `ProviderProcessEnvironment.sanitize` sur l'environnement hérité (une seule source de vérité pour « ce qu'un outil externe peut recevoir ») et ajoute :

- sous Linux : `HOME`, `USER`, `LOGNAME` ;
- les variables de proxy et de confiance TLS, en deux casses : `HTTP_PROXY`, `HTTPS_PROXY`, `NO_PROXY`, `ALL_PROXY`, `SSL_CERT_FILE`, `SSL_CERT_DIR` ;
- les caches : `XDG_CACHE_HOME`, `npm_config_cache`, `NUGET_PACKAGES`, `DOTNET_CLI_HOME`, `COURSIER_CACHE` (déjà dans la base).

Puis un **motif de refus l'emporte sur toute liste** : un nom correspondant à `MINOS_*`, `*TOKEN*`, `*SECRET*`, `*PASSWORD*`, `*KEY*`, `*CREDENTIAL*`, `GITHUB_*`, `NPM_*` est retiré, même s'il est dans la liste ou dans `declared`. Les valeurs de proxy qui contiennent des identifiants (`https://utilisateur:motdepasse@…`) sont transmises telles quelles : c'est le prix d'un proxy authentifié, que l'audit demande de conserver.

La façade évite l'écriture d'une troisième liste d'autorisation (le risque de duplication que `PROMPT-FIABILITE.md` nomme). Elle est publique parce que `minos-provider-scip` (adaptateur) ne peut pas atteindre une classe package-private d'un autre module (ADR 0044). Le scope JaCoCo `provider-execution-trust-boundary` cite `com/minos/runtime/local/ProviderProcessEnvironment` : la façade, de nom différent, n'y entre pas, ce qui est volontaire (ses tests sont ceux de ce changement).

Les trois gestionnaires construisent désormais leur `ProcessBuilder` par une même méthode (`InstallationProcessEnvironment.apply` appelée juste après `new ProcessBuilder`) ; la consolidation plus large des trois copies de `run` n'est pas faite ici.

### D4. Pas de contournement par l'environnement

Un utilisateur qui a besoin d'un miroir ou d'identifiants de dépôt déclare ces éléments dans le **fichier** de configuration de l'outil (Coursier, npm, NuGet, Go) : un fichier n'est pas l'environnement, il n'est pas affecté par ce changement, et avec le verrou (D1) un miroir ne peut plus altérer l'intégrité. Aucune variable `MINOS_TOOLS_ENV_PASSTHROUGH`. Si un besoin réel apparaît, ce sera une décision séparée.

### D5. ADR 0040 amendé, pas de nouvel ADR

Le principe « un composant embarqué est un composant tracé » (§6) et les deux natures de composant (§ Mise en œuvre) s'étendent d'une troisième : **classpath résolu à l'installation, vérifié contre un verrou**. La modification est planifiée en tâche 4.2 (non écrite ici).

## Risks / Trade-offs

- Un environnement trop restreint casse une installation légitime (variable de toolchain oubliée). Atténuation : la liste de D3 reprend les variables déjà mesurées pour les providers ; une installation réelle sous Linux est une tâche manuelle de preuve (4.1).
- Le verrou doit être régénéré à chaque montée de scip-java : le gate de catalogue échoue si le verrou ne correspond pas à la version du catalogue.
- Si la résolution Coursier dépend de l'hôte (classifieurs), le verrou unique serait faux : la tâche 2.1 le mesure avant d'écrire du code.
- Résidu documenté (D2) : exécution à l'indexation sous Linux par `coursier launch`.

## Windows et Linux

Sous Windows, le classpath est livré par le paquet (`windows-x64`) et `installJava` n'appelle pas Coursier tant que `classpath.txt` existe : le verrou ne change pas ce chemin, mais la façade d'environnement s'applique aux commandes qui y subsistent (`npm ci`, `dotnet tool install`, `go install`). Sous Linux, `HOME` est conservé, les chemins sont `/`-normalisés dans les messages. Les deux chemins sont couverts par des tests unitaires avec un faux `coursier` (script ou petit programme Java imprimant un classpath de jars de test), exécutables sur les deux jobs `Verify`.

## Qualification de la capacité

`installation-des-providers` : **qualifiée** pour l'environnement (tests sur les deux plateformes) ; **partielle** pour l'intégrité Linux tant que D2 n'est pas traité (installation vérifiée, indexation non) ; l'installation réelle sous Linux reste à prouver à la main (4.1).
