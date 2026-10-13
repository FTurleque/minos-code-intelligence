# Design

## Context

Sprint 3 de l'audit du 10 octobre 2026 : **AUD-DEP-16**. HEAD analysé : `2d1cc214` (`develop`).

## État vérifié au HEAD

### AUD-DEP-16 — confirmé ; « plausible » devient mesuré

- `minos-app/pom.xml:65-66` (l'audit citait `:64-67`) : deux transformers, `ServicesResourceTransformer` et `ManifestResourceTransformer`.
- Jar ombré local `target/minos-code-intelligence-1.3.0-SNAPSHOT-all.jar`, daté du 12 octobre (**non reconstruit au HEAD**) : un seul `META-INF/NOTICE` (738 octets, Jackson 2), un `META-INF/NOTICE.txt` (Commons Codec), un `META-INF/LICENSE`, un `META-INF/LICENSE.txt`. L'écrasement est observé.
- `supply-chain/THIRD-PARTY-NOTICES.txt` **n'existe pas dans le dépôt** : c'est une sortie de `scripts/release/generate-third-party-notices.py`, produite depuis le SBOM CycloneDX et appelée seulement par `scripts/release/build-windows-distribution.ps1:482-484`.
- `docker/Dockerfile.mcp:17` et `docker/Dockerfile.mcp.release:254` : `COPY` du jar, aucune notice.
- `docs/developer/supply-chain.md:52` : citation exacte de l'audit.
- Aucun test n'affirme le contenu `META-INF` du jar ombré.

**Non mesuré à la spécification** : le contenu du jar reconstruit au HEAD ; le rendu réel d'`ApacheNoticeResourceTransformer` sur ces dépendances (variantes `NOTICE`, `NOTICE.txt`, `NOTICE.md`). La tâche 1.1 l'a mesuré : voir la section suivante.

### Mesure au HEAD de la branche (tâche 1.1, 2026-10-13)

Jar reconstruit par `./mvnw.cmd -B -ntp -pl minos-app -am package -DskipTests` sous Temurin 25.0.4.1 : `target/minos-code-intelligence-1.3.0-SNAPSHOT-all.jar`, **18 660 952 octets**. Classpath d'exécution de `minos-app` (dépendances de test exclues) : 38 jars, dont 13 modules MINOS (aucun fichier de notice ni de licence) et 25 dépendances tierces.

| Dépendance | Notice fournie (octets) | Licences fournies (octets) |
|---|---|---|
| `checker-qual-3.55.1` | — | `META-INF/LICENSE.txt` (1126) |
| `commons-codec-1.22.1` | `META-INF/NOTICE.txt` (175) | `META-INF/LICENSE.txt` (11359) |
| `jackson-annotations-2.22` | `META-INF/NOTICE` (738) | `META-INF/LICENSE` (11358) |
| `jackson-core-2.22.3` | `META-INF/NOTICE` (1616) | `META-INF/LICENSE` (11358), `META-INF/FastDoubleParser-LICENSE` (3228), `META-INF/FastDoubleParser-ThirdParty-LICENSE` (4578), `META-INF/Schubfach-LICENSE` (1064) |
| `jackson-core-3.2.3` | `META-INF/NOTICE` (1610) | les quatre mêmes noms (11358, 3228, 4578, 1064) |
| `jackson-databind-2.22.3` | `META-INF/NOTICE` (738) | `META-INF/LICENSE` (11358) |
| `jackson-databind-3.2.3` | `META-INF/NOTICE` (732) | `META-INF/LICENSE` (11358) |
| `jackson-dataformat-yaml-3.2.3` | `META-INF/NOTICE` (681) | `META-INF/LICENSE` (319) |
| `jackson-datatype-jdk8-2.22.3` | `META-INF/NOTICE` (610) | `META-INF/LICENSE` (330) |
| `jackson-datatype-jsr310-2.22.3` | `META-INF/NOTICE` (610) | `META-INF/LICENSE` (331) |
| `org.eclipse.jgit-7.8.0.202609011348-r` | — | `about.html` (3796, à la racine du jar) |
| `postgresql-42.7.14` | — | `META-INF/LICENSE` (1708) et quatre `META-INF/licenses/com.ongres.*/<artefact>-<version>/META-INF/LICENSE` (1269 chacun) |
| `slf4j-api-2.0.20`, `slf4j-jdk14-2.0.20` | — | `META-INF/LICENSE.txt` (1159 chacun) |
| `itu-1.14.0`, `JavaEWAH-1.2.3`, `json-schema-validator-3.0.6`, `jspecify-1.0.0`, `mcp-2.0.1`, `mcp-core-2.0.1`, `mcp-json-jackson3-2.0.1`, `protobuf-java-4.34.2`, `reactive-streams-1.0.4`, `reactor-core-3.7.0`, `scip-java-bindings-0.10.0`, `snakeyaml-engine-3.0.1` | — | **aucun fichier** dans le jar de la dépendance |

Soit **9 notices** (deux noms : `META-INF/NOTICE` ×8, `META-INF/NOTICE.txt` ×1 ; aucun `NOTICE.md`) et **24 fichiers de licence** fournis par 13 dépendances ; 12 dépendances n'en fournissent aucun.

Ce que le jar du HEAD en gardait : `META-INF/NOTICE` (738 octets, une seule des huit notices Jackson), `META-INF/NOTICE.txt` (Commons Codec), `META-INF/LICENSE` (11358, la licence Apache d'un Jackson, présentée comme celle du jar), `META-INF/LICENSE.txt` (11359, Commons Codec), les trois `*-LICENSE` de jackson-core (une copie pour deux), `about.html` et les quatre licences déjà rangées par le pilote PostgreSQL. **7 notices sur 9 et 14 licences sur 24 étaient écrasées.**

Rendu d'`ApacheNoticeResourceTransformer` avec `addHeader=false` seul, mesuré sur ce classpath : le fichier commence par « Copyright 2007-, Tatu Saloranta » (la ligne de copyright de Jackson, prise pour celle du projet parce que `projectName` est vide) puis par « This product includes software developed at The Apache Software Foundation (http://www.apache.org/). », écrit au nom du projet ombré. `addHeader` ne retire que le cartouche « // NOTICE file corresponding to the section 4d… » : voir l'écart 5.

### Écarts avec l'action proposée par l'audit

1. **`ApacheLicenseResourceTransformer` supprime les licences ; il ne les agrège pas.** Lu dans `maven-shade-plugin` 3.6.2 (`javap`) : `processResource` est vide et `hasTransformedResource` renvoie `false`. L'ajouter retirerait `META-INF/LICENSE` et `META-INF/LICENSE.txt` du jar, soit **l'inverse** du but. Il n'est pas retenu.
2. **`ApacheNoticeResourceTransformer` ajoute par défaut un en-tête** (« This product includes software developed at The Apache Software Foundation », nom de projet) : faux pour un logiciel propriétaire qui n'est pas un projet Apache. Il s'utilise avec `addHeader=false`.
3. **« Copier `supply-chain/THIRD-PARTY-NOTICES.txt` dans l'image » est inapplicable tel quel.** Les deux contextes de construction Docker (`scripts/ci/qualify-docker-release.sh:52-56` en CI, `docker/scripts/mcp-lifecycle.ps1:369-374` sur le poste de l'utilisateur) ne contiennent ni SBOM ni notices. Il faudrait générer le fichier dans deux chemins et sur deux systèmes ; extraire du jar est plus simple et ne peut pas diverger.
4. **L'index de coordonnées n'est pas une notice.** `supply-chain.md` le dit lui-même : le recopier dans l'image ne répondrait pas à l'article 4(d).
5. **`addHeader=false` ne suffit pas** (relevé à l'implémentation : bytecode de `maven-shade-plugin` 3.6.2, puis jar produit). `processResource` ajoute sans condition, à la première ressource, le nom du projet suivi de « Copyright <années> <organisation> », puis `preamble3` suivi de l'organisation et de son adresse ; les valeurs par défaut sont « The Apache Software Foundation », « http://www.apache.org/ », 2006 et « This product includes software developed at ». Avec `projectName` vide, tout paragraphe d'une dépendance contenant « Copyright » est en outre pris pour le copyright du projet. Le transformer n'est utilisable pour MINOS qu'en renseignant ces cinq paramètres.

## Goals / Non-Goals

**Goals** : chaque `NOTICE` d'une dépendance redistribuée est présent dans le jar et dans les images ; les textes de licence aussi ; un test échoue si une dépendance nouvelle fait disparaître une notice.

**Non-Goals** : couvrir les outils non Maven de l'image ; produire un avis juridique ; changer le SBOM.

## Decisions

### D1. Agréger les `NOTICE` à l'ombrage, sans en-tête

`ApacheNoticeResourceTransformer` avec `addHeader=false` : les `META-INF/NOTICE*` des dépendances sont concaténés en un seul `META-INF/NOTICE`. Si la mesure de la tâche 1.1 montre qu'une variante de nom échappe au transformer, un `AppendingTransformer` par ressource la couvre.

**Mise en œuvre (2026-10-13).** Les deux variantes relevées (`NOTICE`, `NOTICE.txt`) sont reconnues par le transformer (comparaison sans casse de `META-INF/NOTICE`, `.txt`, `.md`) : aucun `AppendingTransformer` n'est nécessaire. À cause de l'écart 5, le transformer reçoit aussi `projectName` (« MINOS Code Intelligence »), `inceptionYear` (2026), `organizationName` (« Fabrice Turleque », le titulaire nommé par `LICENSE`), `organizationURL` (le dépôt) et `preamble3` (une phrase qui dit que MINOS est propriétaire et que les notices qui suivent sont celles des tiers). Le fichier produit commence donc par « MINOS Code Intelligence / Copyright 2026 Fabrice Turleque ». Le transformer regroupe les notices par paragraphes et retire les doublons (les huit notices Jackson partagent leurs premiers paragraphes) : `META-INF/NOTICE` contient chaque ligne de chaque notice, pas huit copies ; la copie intégrale de chaque notice est gardée par D2.

### D2. Les licences : une par dépendance, pas d'écrasement — **option A retenue (propriétaire, 2026-10-13)**

Les `META-INF/LICENSE*` homonymes s'écrasent aussi. Recommandation (option A) : produire à la construction un répertoire `META-INF/licenses/<groupe>-<artefact>/` qui garde le fichier de licence de chaque dépendance (plugin `license-maven-plugin` ou étape `maven-dependency-plugin` + assemblage, à choisir après mesure), et laisser `META-INF/LICENSE` porter la licence de MINOS.

Option B : s'en tenir aux notices (D1) et à l'index de coordonnées, et documenter la limite. Moins de build, mais les textes de licence restent partiels.

Une nouvelle extension de build se justifie par écrit (règle de chaîne d'approvisionnement) : version épinglée, raison dans le POM et dans `supply-chain.md`.

**Décision (2026-10-13) : option A**, une licence par dépendance, sans écrasement.

**Mise en œuvre.** Étape `maven-dependency-plugin` 3.11.0 (`unpack-dependencies`, phase `prepare-package`, portée `runtime`, groupe `com.minos` exclu) dans `minos-app` : elle copie, sans les modifier, les fichiers de licence et de notice de chaque dépendance (`META-INF/*LICENSE*`, `*LICENCE*`, `*NOTICE*`, `*COPYING*`, les mêmes à la racine, `about.html`) vers `META-INF/licenses/<artefact>-<version>/` des classes de `minos-app`, d'où l'ombrage les reprend. C'est l'étape « `maven-dependency-plugin` + assemblage » prévue ci-dessus ; `license-maven-plugin` n'est pas retenu (il télécharge des textes au lieu de garder ceux que la dépendance livre). Aucune dépendance de production n'est ajoutée. La licence de MINOS (`LICENSE` de la racine) devient `META-INF/LICENSE` par une ressource de `minos-app` ; les `META-INF/LICENSE.txt` et `LICENSE.md` de premier niveau, inattribuables, sont filtrés de l'ombrage puisque chaque dépendance garde le sien dans son répertoire.

Deux précisions par rapport au texte de l'option A : le répertoire s'appelle `<artefact>-<version>` (le nom du jar sans son extension, seule forme que le plugin sait produire à plat ; elle distingue `jackson-core-2.22.3` de `jackson-core-3.2.3`, que `<groupe>-<artefact>` aurait distingués par le groupe) et il reçoit aussi les notices, à côté des licences. Les licences que le pilote PostgreSQL range déjà sous `META-INF/licenses/com.ongres.*/` gardent leur chemin.

**Limite.** Douze dépendances ne livrent aucun fichier de licence dans leur jar (liste dans la mesure ci-dessus) : rien ne peut être conservé pour elles. Leurs POM déclarent Apache-2.0 (itu, JavaEWAH, json-schema-validator, jspecify, reactor-core, scip-java-bindings, snakeyaml-engine), MIT (mcp, mcp-core, mcp-json-jackson3) et MIT-0 (reactive-streams) ; le POM de protobuf-java n'en déclare pas lui-même (hérité de son parent, non lu). Elles restent couvertes par l'index de coordonnées issu du SBOM, pas par un texte.

### D3. Les outils non Maven de l'image — **hors périmètre (propriétaire, 2026-10-13)**

Recommandation : hors de ce changement, inscrits comme limite dans `supply-chain.md` (les paquets npm portent leurs licences dans `node_modules`, les binaires dans leurs archives ; rien n'est retiré). À rouvrir si un inventaire de licences de l'image est demandé.

**Décision (2026-10-13) : la recommandation est retenue.** Node, Go, Rust, .NET et les indexeurs de l'image restent hors de ce changement ; la limite est écrite dans `docs/developer/supply-chain.md`.

### D4. Les images extraient du jar

Chaque Dockerfile extrait `META-INF/NOTICE` et les licences du jar vers `/opt/minos/licenses/` dans l'étape qui copie le jar (`unzip` ou `jar xf`, selon ce que contient déjà l'image : l'image `-jre` n'a pas `jar`). La qualification vérifie la présence et la non-vacuité des fichiers.

**Mise en œuvre (2026-10-13).** Les deux Dockerfiles exécutent la même étape : `jar xf <jar> META-INF/NOTICE META-INF/LICENSE META-INF/licenses/` dans le répertoire cible, puis `test -s` sur la notice et sur la licence et la recherche d'au moins un fichier non vide sous `META-INF/licenses` (`jar xf` n'échoue pas sur une entrée absente). Les chemins relatifs du jar sont gardés : `/opt/minos/licenses/META-INF/NOTICE`, `/opt/minos/licenses/META-INF/LICENSE`, `/opt/minos/licenses/META-INF/licenses/<artefact>-<version>/`. `Dockerfile.mcp.release` est construit sur l'image JDK et extrait dans son étape finale. `Dockerfile.mcp` est construit sur l'image JRE, qui n'a ni `jar` ni `unzip` : il reçoit une étape `licenses` construite sur **le même épinglage JDK que l'image de release**, dont seul le répertoire extrait est copié (`COPY --from=licenses`). La qualification (`scripts/ci/qualify-docker-release.sh`) relève l'empreinte SHA-256 de chaque fichier de `/opt/minos/licenses` et la compare à celle de l'entrée du jar dont l'image est construite. **Docker ne démarre pas sur le poste de développement** : aucune image n'a été construite ; la preuve sur image est celle du gate « Provider-complete image exact-head » à la PR (tâche 3.2), pour l'image de release seulement, `Dockerfile.mcp` n'étant construit par aucun workflow.

### D5. La preuve est un test du jar réellement produit

Un `ShadedJarNoticesIT` (`minos-app`, à côté des `ShadedJar*IT` existants) ouvre le jar ombré et affirme : un `META-INF/NOTICE` qui contient le texte de chaque notice attendue ; pour chaque dépendance Apache-2.0 de la liste calculée depuis le classpath d'exécution, une notice ou une licence retrouvable ; aucun en-tête « developed at The Apache Software Foundation » attribué à MINOS.

**Mise en œuvre (2026-10-13).** Les dépendances attendues ne sont pas une liste écrite dans le test : ce sont les jars du classpath de Failsafe dont une classe se retrouve dans le jar ombré (les dépendances de test en sont donc exclues). Quatre assertions : chaque ligne de chaque notice est dans `META-INF/NOTICE` et aucune variante ne reste à côté ; aucune ligne écrite par l'ombrage (qui ne vient d'aucune dépendance) ne mentionne Apache ; chaque fichier de licence ou de notice d'une dépendance est gardé à l'identique sous `META-INF/licenses/<nom du jar>/` ; `META-INF/LICENSE` est la licence de MINOS. Le test échoue sur le jar du HEAD (trois assertions sur quatre) et sur la configuration `addHeader=false` seule (assertion d'attribution).

## Direction des dépendances

Aucune dépendance de module ajoutée. Une extension de build éventuelle (D2-A) reste dans `minos-app`, assemblage final ; `check-module-boundaries.py` est rejoué (règles de mise en page de build).

## Qualification des capacités

- Notices des dépendances Java dans le jar et les images : **non qualifiée** au HEAD ; qualifiée par D5 et la tâche 3.
- Textes de licence par dépendance : **qualifiée** pour les 13 dépendances qui livrent un fichier (D2, option A) ; **non supportée**, déclarée, pour les 12 qui n'en livrent pas.
- Notices des outils non Maven : **non supportée**, déclarée.

## Windows et Linux

Le jar est produit à l'identique ; le test d'intégration tourne dans les deux `Verify`. L'extraction dans l'image s'exécute pendant `docker build`, y compris sur le poste de l'utilisateur.

## Risks / Trade-offs

- La taille du jar augmente de quelques dizaines de kilo-octets (mesuré : 18 660 952 → 18 705 534 octets, +44 582).
- `Dockerfile.mcp` télécharge une seconde image (le JDK) pour sa seule étape d'extraction : coût réseau à la première construction sur le poste de l'utilisateur, aucun effet sur l'image finale.
- L'année de `META-INF/NOTICE` est calculée à la construction (« 2026 », puis « 2026-<année> ») : le fichier change au passage d'une année.
- Un transformer mal configuré peut dupliquer ou tronquer : couvert par le test D5.
- Collision de lignes avec `separer-l-image-de-requete` (zone `COPY` du Dockerfile) : ordre dans `SUIVI.md`.
