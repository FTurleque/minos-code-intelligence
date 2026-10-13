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

**Non mesuré** : le contenu du jar reconstruit au HEAD ; le rendu réel d'`ApacheNoticeResourceTransformer` sur ces dépendances (variantes `NOTICE`, `NOTICE.txt`, `NOTICE.md`). La tâche 1.1 le mesure.

### Écarts avec l'action proposée par l'audit

1. **`ApacheLicenseResourceTransformer` supprime les licences ; il ne les agrège pas.** Lu dans `maven-shade-plugin` 3.6.2 (`javap`) : `processResource` est vide et `hasTransformedResource` renvoie `false`. L'ajouter retirerait `META-INF/LICENSE` et `META-INF/LICENSE.txt` du jar, soit **l'inverse** du but. Il n'est pas retenu.
2. **`ApacheNoticeResourceTransformer` ajoute par défaut un en-tête** (« This product includes software developed at The Apache Software Foundation », nom de projet) : faux pour un logiciel propriétaire qui n'est pas un projet Apache. Il s'utilise avec `addHeader=false`.
3. **« Copier `supply-chain/THIRD-PARTY-NOTICES.txt` dans l'image » est inapplicable tel quel.** Les deux contextes de construction Docker (`scripts/ci/qualify-docker-release.sh:52-56` en CI, `docker/scripts/mcp-lifecycle.ps1:369-374` sur le poste de l'utilisateur) ne contiennent ni SBOM ni notices. Il faudrait générer le fichier dans deux chemins et sur deux systèmes ; extraire du jar est plus simple et ne peut pas diverger.
4. **L'index de coordonnées n'est pas une notice.** `supply-chain.md` le dit lui-même : le recopier dans l'image ne répondrait pas à l'article 4(d).

## Goals / Non-Goals

**Goals** : chaque `NOTICE` d'une dépendance redistribuée est présent dans le jar et dans les images ; les textes de licence aussi ; un test échoue si une dépendance nouvelle fait disparaître une notice.

**Non-Goals** : couvrir les outils non Maven de l'image ; produire un avis juridique ; changer le SBOM.

## Decisions

### D1. Agréger les `NOTICE` à l'ombrage, sans en-tête

`ApacheNoticeResourceTransformer` avec `addHeader=false` : les `META-INF/NOTICE*` des dépendances sont concaténés en un seul `META-INF/NOTICE`. Si la mesure de la tâche 1.1 montre qu'une variante de nom échappe au transformer, un `AppendingTransformer` par ressource la couvre.

### D2. Les licences : une par dépendance, pas d'écrasement — **décision en attente du propriétaire**

Les `META-INF/LICENSE*` homonymes s'écrasent aussi. Recommandation (option A) : produire à la construction un répertoire `META-INF/licenses/<groupe>-<artefact>/` qui garde le fichier de licence de chaque dépendance (plugin `license-maven-plugin` ou étape `maven-dependency-plugin` + assemblage, à choisir après mesure), et laisser `META-INF/LICENSE` porter la licence de MINOS.

Option B : s'en tenir aux notices (D1) et à l'index de coordonnées, et documenter la limite. Moins de build, mais les textes de licence restent partiels.

Une nouvelle extension de build se justifie par écrit (règle de chaîne d'approvisionnement) : version épinglée, raison dans le POM et dans `supply-chain.md`.

### D3. Les outils non Maven de l'image — **décision en attente du propriétaire**

Recommandation : hors de ce changement, inscrits comme limite dans `supply-chain.md` (les paquets npm portent leurs licences dans `node_modules`, les binaires dans leurs archives ; rien n'est retiré). À rouvrir si un inventaire de licences de l'image est demandé.

### D4. Les images extraient du jar

Chaque Dockerfile extrait `META-INF/NOTICE` et les licences du jar vers `/opt/minos/licenses/` dans l'étape qui copie le jar (`unzip` ou `jar xf`, selon ce que contient déjà l'image : l'image `-jre` n'a pas `jar`). La qualification vérifie la présence et la non-vacuité des fichiers.

### D5. La preuve est un test du jar réellement produit

Un `ShadedJarNoticesIT` (`minos-app`, à côté des `ShadedJar*IT` existants) ouvre le jar ombré et affirme : un `META-INF/NOTICE` qui contient le texte de chaque notice attendue ; pour chaque dépendance Apache-2.0 de la liste calculée depuis le classpath d'exécution, une notice ou une licence retrouvable ; aucun en-tête « developed at The Apache Software Foundation » attribué à MINOS.

## Direction des dépendances

Aucune dépendance de module ajoutée. Une extension de build éventuelle (D2-A) reste dans `minos-app`, assemblage final ; `check-module-boundaries.py` est rejoué (règles de mise en page de build).

## Qualification des capacités

- Notices des dépendances Java dans le jar et les images : **non qualifiée** au HEAD ; qualifiée par D5 et la tâche 3.
- Textes de licence par dépendance : **partielle** tant que D2 n'est pas tranchée.
- Notices des outils non Maven : **non supportée**, déclarée.

## Windows et Linux

Le jar est produit à l'identique ; le test d'intégration tourne dans les deux `Verify`. L'extraction dans l'image s'exécute pendant `docker build`, y compris sur le poste de l'utilisateur.

## Risks / Trade-offs

- La taille du jar augmente de quelques dizaines de kilo-octets.
- Un transformer mal configuré peut dupliquer ou tronquer : couvert par le test D5.
- Collision de lignes avec `separer-l-image-de-requete` (zone `COPY` du Dockerfile) : ordre dans `SUIVI.md`.
