# Supply-chain et provenance de release

M21-S5 durcit la distribution Windows sans modifier les workflows GitHub Actions avant leur reprise explicite en août 2026.

## Preuves générées

Le build de release compile/package d'abord le reactor, puis `build-windows-distribution.ps1` invoque explicitement le goal agrégateur CycloneDX **depuis la racine d'exécution Maven** :

```text
target/sbom/minos-cyclonedx.json
```

Ce découplage est volontaire. `makeAggregateBom` est un goal aggregator et ne doit pas être attaché au module enfant `minos-app` : CycloneDX 2.9.2 ignore alors l'exécution avec `Skipping CycloneDX on non-execution root`.

Contrat courant :

```text
format        CycloneDX JSON
specVersion   1.6
scope test    exclu
reactor       agrégé depuis la racine Maven
provider      org.cyclonedx:cyclonedx-maven-plugin:2.9.2
```

La distribution Windows embarque ensuite :

```text
supply-chain/minos.cdx.json
supply-chain/THIRD-PARTY-NOTICES.txt
RELEASE-MANIFEST.json
```

`RELEASE-MANIFEST.json` contient le SHA-256 et la taille de chaque fichier de la distribution, hors manifest lui-même, ainsi que la version et le commit Git exacts.

Le build produit aussi des sidecars de release :

```text
minos-<version>.cdx.json
minos-<version>.cdx.json.sha256
MINOS-<version>-THIRD-PARTY-NOTICES.txt
MINOS-<version>-THIRD-PARTY-NOTICES.txt.sha256
```

Ils sont publiés avec le setup, le ZIP et leurs checksums par `publish-windows-release.ps1`.

## Politique licences tierces

`scripts/release/generate-third-party-notices.py` dérive l'inventaire depuis le SBOM et exclut les composants `com.minos`.

En mode release M21, `--strict` impose qu'un composant tiers fournisse au moins une métadonnée de licence exploitable dans le SBOM. Une dépendance sans licence connue bloque donc la qualification jusqu'à clarification ou correction de ses métadonnées ; MINOS ne devine jamais une licence.

Le fichier de notices est un index de coordonnées et de métadonnées publiées. Il ne remplace pas le texte de licence autoritatif de chaque projet tiers.

## Notices et licences tierces dans les livrables

Le jar ombré redistribue des dépendances Java ; il garde leurs fichiers de notice et de licence (AUD-DEP-16, changement `conserver-les-notices-tierces`, octobre 2026).

| Entrée du jar | Contenu |
|---|---|
| `META-INF/NOTICE` | les notices de toutes les dépendances qui en livrent une (`META-INF/NOTICE`, `NOTICE.txt`, `NOTICE.md`), agrégées par `ApacheNoticeResourceTransformer` ; les paragraphes communs à plusieurs notices n'y figurent qu'une fois. Il commence par le nom de MINOS, son titulaire et une phrase qui dit que MINOS est propriétaire |
| `META-INF/LICENSE` | la licence de MINOS (`LICENSE` de la racine du dépôt) |
| `META-INF/licenses/<artefact>-<version>/` | les fichiers de licence et de notice de la dépendance, **copiés sans modification** (un répertoire par jar de dépendance : deux fichiers de même nom ne s'écrasent plus) |
| `META-INF/licenses/com.ongres.*/…` | les licences que le pilote PostgreSQL range lui-même, à leur chemin d'origine |

Mise en œuvre, dans `minos-app/pom.xml` : une étape `maven-dependency-plugin` (`unpack-dependencies`, phase `prepare-package`) extrait ces fichiers des dépendances d'exécution avant l'empaquetage. C'est un plugin de construction (version épinglée par `maven.dependency.plugin.version`), pas une dépendance : rien n'entre dans le classpath d'exécution ni dans le SBOM. `ApacheLicenseResourceTransformer` n'est **pas** utilisé : il retire les licences du jar. `ApacheNoticeResourceTransformer` écrit par défaut, au nom du projet ombré, un copyright et une attribution à l'Apache Software Foundation, même avec `addHeader=false` : son nom de projet, son organisation et sa phrase d'introduction sont donc renseignés pour MINOS.

Le test d'intégration `ShadedJarNoticesIT` ouvre le jar produit et échoue si la notice d'une dépendance embarquée manque dans `META-INF/NOTICE`, si un de ses fichiers de licence n'est pas gardé à l'identique dans son répertoire, si l'ombrage écrit une ligne qui mentionne Apache au nom de MINOS, ou si `META-INF/LICENSE` n'est pas la licence de MINOS. Les dépendances contrôlées sont calculées depuis le classpath du test : une dépendance ajoutée est contrôlée sans toucher au test.

Par livrable :

| Livrable | Ce qu'il contient |
|---|---|
| Jar ombré | les entrées ci-dessus |
| Distribution Windows | le jar, donc ses entrées, et `supply-chain/THIRD-PARTY-NOTICES.txt` (index de coordonnées issu du SBOM) |
| Images Docker (`Dockerfile.mcp.release`, `Dockerfile.mcp`) | le jar, et les mêmes fichiers extraits du jar pendant la construction sous `/opt/minos/licenses/` (`META-INF/NOTICE`, `META-INF/LICENSE`, `META-INF/licenses/…`), lisibles sans exécuter MINOS. La construction échoue si l'extraction ne produit rien ; `scripts/ci/qualify-docker-release.sh` compare l'empreinte de chaque fichier de l'image de release à celle de l'entrée du jar |

**Limites.**

- Douze dépendances ne livrent aucun fichier de licence dans leur jar (itu, JavaEWAH, json-schema-validator, jspecify, mcp, mcp-core, mcp-json-jackson3, protobuf-java, reactive-streams, reactor-core, scip-java-bindings, snakeyaml-engine) : il n'y a rien à conserver pour elles, et MINOS ne reconstitue pas un texte de licence. Elles ne sont couvertes que par l'index de coordonnées.
- Les outils de l'image de release qui ne viennent pas de Maven (Node.js et les paquets npm de scip-typescript et scip-python, Go, Rust, le SDK .NET, les indexeurs, les paquets Ubuntu) sont **hors périmètre** : leurs licences restent là où leur installation les pose (`node_modules`, archives et paquets d'origine), sans inventaire ni contrôle de MINOS. À rouvrir si un inventaire de licences de l'image est demandé.
- Ce n'est pas un avis juridique sur la compatibilité des licences.
- `Dockerfile.mcp` n'est construit par aucun workflow : son extraction est gardée par un test de contrat, pas par une image de CI.

## Intégrité

`scripts/release/check-supply-chain.py` vérifie :

- CycloneDX 1.6 ;
- présence de composants tiers ;
- cohérence du nombre de composants entre SBOM et notices ;
- présence des licences en mode strict ;
- cohérence `VERSION` / version demandée / commit exact ;
- cohérence du manifest ;
- SHA-256 et taille de chaque fichier ;
- absence de fichier non déclaré ou de chemin stale dans le manifest.

Les ZIP, setup, SBOM et notices disposent chacun d'un sidecar `.sha256`.

## Authenticode

La signature Windows n'est jamais simulée.

Le helper :

```powershell
.\scripts\release\sign-windows-artifact.ps1 `
  -Artifact .\target\dist\MINOS-<version>-windows-x64-setup.exe `
  -CertificateThumbprint <thumbprint> `
  -TimestampUrl <url-optionnelle>
```

utilise `signtool.exe`, SHA-256 et vérifie ensuite `Get-AuthenticodeSignature`.

Le runner S5 accepte un candidat non signé tant qu'aucun certificat de production n'est configuré. Pour rendre la signature obligatoire :

```powershell
$env:MINOS_REQUIRE_SIGNED_RELEASE = '1'
```

Dans ce mode, un setup sans signature Authenticode `Valid` échoue.

## Gate M21-S5

Entrée autoritative locale :

```powershell
.\scripts\m21\run-s5.ps1 -ExpectedHead <sha>
```

Le runner :

1. rejoue le gate M21/M20 exact-head ;
2. reconstruit le package de release sans répéter les tests déjà passés par le gate core ;
3. génère le SBOM agrégé depuis la racine Maven ;
4. génère notices et manifest ;
5. vérifie tous les checksums ;
6. construit le setup ;
7. rejoue install/uninstall ZIP + setup via `publish-windows-release.ps1 -ValidateOnly` ;
8. vérifie le statut Authenticode selon la politique locale ;
9. confirme HEAD inchangé et worktree propre.

## Images de conteneur : mise à jour des digests par Dependabot

Toutes les images sont écrites `<image>:<tag>@sha256:<digest d'index>` (gate `scripts/quality/check-image-pins.py`). Dependabot (`.github/dependabot.yml`) les suit par **deux** écosystèmes, chacun avec ses propres fichiers :

| Écosystème | Fichiers lus (`directory: "/docker"`) | Ce qu'il met à jour |
| --- | --- | --- |
| `docker` | `Dockerfile.mcp`, `Dockerfile.mcp.release` (`FROM`) | l'image de base Temurin (le majeur est ignoré : il doit suivre `maven.compiler.release`) |
| `docker-compose` | `compose-mcp.prod.yaml`, `compose-mcp.connected.yaml` (`image:`, y compris le défaut d'un `${VAR:-<image>:<tag>@sha256:…}`) | les images `pgvector/pgvector` (défaut de `MINOS_POSTGRES_IMAGE`) et `ollama/ollama` (défaut de `MINOS_OLLAMA_IMAGE`) du compose connecté |

Dependabot réécrit **ensemble** le tag et le digest, ou seulement le digest quand le tag n'a pas bougé (reconstruction du tag amont). Pour pgvector il ne compare que les tags de même suffixe (`-pg17`) : un changement de majeur PostgreSQL n'est jamais proposé. Il ne lit pas `${MINOS_IMAGE}` (l'image MINOS elle-même, sans défaut) ni les services `build:`.

**Pourquoi les fichiers s'appellent `compose-mcp.*.yaml`.** L'écosystème `docker-compose` ne retient un fichier que si son nom correspond à `(docker-)?compose(-\w+)?(\.[\w-]+)?\.ya?ml` (`FILENAME_REGEX`, `docker_compose/file_fetcher.rb` de `dependabot-core`) : au plus **un** segment pointé. Les anciens noms à deux segments pointés étaient invisibles pour lui, donc les digests pgvector et ollama, épinglés par le lot S11, ne se seraient jamais mis à jour ; ce n'est plus le cas. `check-image-pins.py` refuse un fichier compose de `docker/` dont le nom sort de ce motif, et l'absence de l'entrée `docker-compose` pour `/docker` dans `dependabot.yml` (auto-tests rouge puis vert).

**Ce que Dependabot ne fait pas, et qui reste à la charge de la revue de sa PR :**

1. Il ne sait pas pourquoi une version est montée. Une PR pgvector ou ollama se relit avec les notes de version de l'amont : pgvector peut demander `ALTER EXTENSION vector UPDATE` sur une base existante, Ollama peut changer le comportement d'un runner ou l'empreinte mémoire.
2. Les plafonds mémoire et PID du compose (`x-limits-ollama`, `x-limits-postgres`, `docs/user/docker-runtime.md`, « Mesures et marges ») ont été **mesurés** sur les versions épinglées au moment de S11 : après une montée d'Ollama ou de pgvector, rejouer la mesure avant de fusionner.
3. Il ne lance pas la qualification Docker (`docker-upgrade-qualification.yml`, déclenchée à la main) : la lancer avant de fusionner une PR qui touche l'image du compose.
4. Le gate `check-image-pins.py` reste rouge si Dependabot produisait un tag sans digest ou un digest sans tag ; il ne dit rien du bien-fondé de la montée.

**Ce qui a été vérifié, et ce qui ne l'a pas été.** Rejoué hors ligne sur les fichiers réels (S23-SUIVI, « Renommage des compose ») : le nom est apparié ; les `FROM` des Dockerfile ne le sont pas ; l'analyse lit deux dépendances (`pgvector/pgvector` `0.8.2-pg17`, `ollama/ollama` `0.32.0`) avec leur digest ; la réécriture du défaut interpolé ne change que la ligne visée (digest seul, ou tag et digest). **Non rejoué** : l'interrogation du registre Docker Hub et le choix du tag candidat (lus dans `docker/update_checker.rb`, non exécutés) : la première PR de Dependabot sur ces fichiers est la preuve de bout en bout, à surveiller le lundi suivant la fusion.

## Veille des vulnérabilités : ce qui est livré, et par quoi c'est suivi

Constat AUD-DEP-03 (audit du 10 octobre 2026). Le tableau dit, pour chaque famille de composants livrés, quel outil signale une vulnérabilité et à quel moment. Une ligne « non couvert » est une limite déclarée, pas un oubli.

| Composant livré | Montées proposées par | Vulnérabilités signalées par | Moment |
|---|---|---|---|
| Dépendances Maven du reactor | Dependabot (`maven`, `/`) | OSV-Scanner, job `Dependency vulnerability gate` de `pr-ci.yml` (check exigé) ; OWASP Dependency-Check à la demande (`code-audit.yml`) | chaque PR |
| Dépendances Gradle du plugin IntelliJ | Dependabot (`gradle`, `/minos-intellij`) | non couvert par le gate de PR (AUD-DEP-04, sprint 5) | — |
| Actions GitHub | Dependabot (`github-actions`) | épinglage par SHA (`check-workflow-pins.py`) ; pas de veille d'avis | chaque PR |
| Images de conteneur | Dependabot (`docker`, `docker-compose`, `/docker`) | épinglage par digest (`check-image-pins.py`) ; pas de veille d'avis sur le contenu des images | chaque PR |
| Verrous npm de scip-typescript et de scip-python (66 paquets) | personne : une montée est un changement de catalogue qualifié (ADR 0040) | OSV-Scanner, même job, par `--lockfile=package-lock.json:<chemin>` | chaque PR |
| Catalogue `embedded-tools.json` (7 providers, 9 artefacts, 2 composants assemblés) et les 74 jars de `scip-java-classpath.lock.json` | personne : changement de catalogue qualifié | `scripts/quality/check-embedded-tools-advisories.py` (API OSV, calendrier de support de Node.js), workflow `embedded-tools-advisories.yml` | chaque lundi, et à la demande |

### Verrous npm

OSV-Scanner reconnaît un verrou npm à son nom de fichier, `package-lock.json`. Les deux verrous livrés portent le nom de leur outil en préfixe (`scip-typescript-package-lock.json`, `scip-python-package-lock.json`) et restent à leur place : ils sont lus par chemin par les gestionnaires Java, le catalogue, l'image Docker et un lanceur. Ils sont donc passés un par un au scan (`scan-args` de `pr-ci.yml`). `scripts/quality/check-ci-wiring.py` calcule la liste des `*package-lock.json` rangés sous un `src/main/resources` et échoue si l'un d'eux manque aux arguments, ou si un argument nomme un fichier absent.

Dependabot n'a pas d'écosystème `npm`, volontairement : ces verrous **sont** l'arbre installé chez l'utilisateur, et une PR automatique en changerait le contenu sans la qualification du catalogue.

### Catalogue des outils embarqués

Le script lit le catalogue et une table qu'il porte (`WATCHES`) : pour chaque identifiant, les paquets à interroger (écosystème OSV, nom), un calendrier de support, ou la raison pour laquelle il n'est pas suivi. Un composant du catalogue absent de la table fait échouer le script, comme une entrée de table que le catalogue n'a plus : aucun composant n'est oublié en silence. L'auto-test (`--self-test`, réponses simulées, sans réseau) tourne dans le job `invariants` et le prouve sur le catalogue réel à chaque PR.

Le verrou du classpath de scip-java contient des noms de jars, pas des coordonnées : la table `CLASSPATH_GROUPS` du script donne le groupe Maven de chaque artefact (établie sur un cache Coursier dont les 74 jars avaient l'empreinte SHA-256 du verrou). Elle se met à jour avec le verrou, à chaque montée de scip-java ; un jar sans groupe fait échouer le script.

Sa sortie a toujours quatre sections : composants touchés par un avis, composants hors support, constats sous dérogation, composants **non couverts**. Codes de sortie : `0` rien à signaler ; `1` un composant est touché ou hors support, ou la table ou une dérogation est fausse ; `2` le service d'avis ou le calendrier n'a pas pu être lu. Un échec réseau n'est jamais lu comme « aucun avis ».

Non couverts, par construction (la sortie du script fait foi) :

| Composant | Ce qui n'est pas suivi | Raison |
|---|---|---|
| Coursier | tout | lanceur natif épinglé par un commit de `coursier/launchers` : le catalogue n'a pas de version publiée à comparer |
| scip-clang, rust-analyzer | tout | binaires téléchargés d'une page de releases : aucune base d'avis ne les décrit par un nom de paquet et une version |
| Node.js | les avis du runtime | OSV n'a pas d'écosystème pour un binaire de runtime ; seule la fin de support de la ligne de version est contrôlée |
| Apache Maven | les bibliothèques tierces de la distribution | seuls `maven-core`, `maven-compat` et `maven-embedder` sont interrogés |
| scip-dotnet, scip-go | les bibliothèques incluses dans le paquet ou le binaire | seul le paquet publié (NuGet, module Go) est interrogé |

Le workflow est planifié et n'est pas un check exigé : un avis publié en semaine ne doit pas rendre rouges les PR sans rapport. GitHub n'exécute un workflow planifié que depuis la branche par défaut ; avant la promotion vers `main`, il se lance à la main (`workflow_dispatch`).

### Traiter une alerte

La veille ne modifie rien. Quand le workflow est rouge :

1. Lire la sortie : la section dit le composant, l'avis (lien OSV) ou la date de fin de support. Un code `2` n'est pas une alerte : relancer le workflow.
2. Évaluer si l'avis touche l'usage que MINOS fait du composant (l'outil s'exécute dans le bac à sable, sans réseau, sur du code non fiable).
3. Corriger par une **montée du catalogue qualifiée** (skill `minos-dependency-update`, ADR 0040) : version, empreintes, verrou, `sync-tools-manifest.py`, qualification Windows et Docker. Un avis sur un jar du classpath de scip-java se corrige en montant scip-java, pas en remplaçant un jar.
4. Si aucun correctif n'existe ou si l'avis ne s'applique pas : inscrire une **dérogation datée** dans `scripts/quality/embedded-tools-advisory-waivers.json` (`component`, `finding` : identifiant de l'avis ou `end-of-support`, `reason`, `expires`) et le risque au registre (`docs/architecture/risks/register.md`). Une dérogation échue redevient un échec ; une dérogation qui ne correspond plus à aucun constat échoue aussi, pour être retirée.

## Téléchargements Docker Hub authentifiés

Docker Hub limite les téléchargements anonymes par adresse de runner. Le 9 octobre 2026, la limite (« 429 toomanyrequests ») a fait échouer les images de base (`golang`, `rust`, `eclipse-temurin`), `pgvector` des tests PostgreSQL et les qualifications Docker, contrôles obligatoires compris.

Les jobs Linux qui téléchargent des images passent par `docker/login-action` (épinglé par SHA) quand les secrets du dépôt existent :

| Secret | Contenu |
|---|---|
| `DOCKERHUB_USERNAME` | nom du compte Docker Hub |
| `DOCKERHUB_TOKEN` | jeton d'accès Docker Hub en **lecture seule** (*Account settings → Personal access tokens*, droits « Public Repo Read-only ») |

Jobs concernés : `Verify (ubuntu-24.04)` de `pr-ci.yml` (Testcontainers réutilise la connexion du démon Docker), `docker-release-validation.yml`, `release-promotion-gate.yml`, `docker-upgrade-qualification.yml` et le job PIT de `code-audit.yml`. Sans ces secrets (dépôt non configuré, PR depuis un fork), l'étape est sautée et les téléchargements restent anonymes : rien ne casse, la limite s'applique.

## Construction reproductible (AUD-DEP-11)

Deux constructions du même commit, sur le même système, donnent le même JAR ombré. `project.build.outputTimestamp` est écrit dans le POM racine (`2026-01-01T00:00:00Z`, valeur d'un build sans Git ou de développement) et les scripts qui construisent un livrable (`build-windows-distribution.ps1`, `qualify-docker-release.sh`) passent la date du commit. Maven 3.10, celui du wrapper, fixait déjà une date par défaut ; Maven 3.9, que l'enforcer accepte, non : le POM ne dépend plus de la version de Maven. `requirePluginVersions` refuse tout plugin sans version, phases `clean`, `deploy` et `site` comprises.

Vérifier soi-même (deux clones propres du commit, construction, comparaison des entrées) :

```text
python scripts/quality/check-reproducible-build.py twice --work <dossier vide>
```

`reproducible-build.yml` le fait sous Linux sur les PR qui touchent le build Maven et avant chaque publication (appelé par `release-windows.yml`). **Hors périmètre** : le zip, l'installateur et les images ne sont pas reproductibles bit à bit ; un JAR construit sous Windows n'est pas prouvé identique à un JAR construit sous Linux.

## Frontière avec la CI

Aucune modification de workflow GitHub Actions n'est incluse dans S5 en juillet 2026. L'épinglage immuable des actions, les checks distants et la branch protection restent dans M21-S2, explicitement en pause jusqu'en août.