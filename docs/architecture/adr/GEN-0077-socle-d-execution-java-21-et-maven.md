---
id: GEN-0077
title: "Socle d'exécution : Java 21, Maven par le wrapper, sans framework serveur"
domain: general
rules: true
---

# Socle d'exécution : Java 21, Maven par le wrapper, sans framework serveur

- **Statut** : Accepted
- **Date** : 2026-11-06
- **Origine** : décision du propriétaire du 2026-11-06, après mesure d'une compilation et d'une exécution sous Java 21
- **Remplace** : [GEN-0067](archive/GEN-0067-socle-d-execution-java-25-et-maven.md)
- **Remplacé par** : —

## Context

MINOS distingue le langage dans lequel il est développé des langages qu'il analyse : le cœur reste agnostique des langages analysés, même s'il est lui-même écrit en Java ([ADR 0004](archive/0004-stack-java-maven-core-sans-framework.md) § Context).

Le socle répond à ces contraintes (ADR 0004 § Context) : Windows, Linux et macOS ; local-first ; bonne intégration Protobuf ; outillage mature ; performances suffisantes pour des services locaux ; maintenance longue durée ; tests et packaging reproductibles ; aucun framework serveur tant qu'une API réseau n'est pas réellement nécessaire.

Le JDK du produit doit recevoir des correctifs de sécurité. Une version non LTS n'est plus corrigée six mois après sa sortie, alors que le jar, les images Docker et le runtime de l'installateur Windows en dépendent ; sans contrôle, la version Java s'écrit dans des dizaines de fichiers (POM, workflows, Dockerfiles, scripts, documentation) qui divergent ([ADR 0059](archive/0059-migrer-le-socle-vers-java-25-lts.md) § Context). Le plugin IntelliJ est lié à IntelliJ Platform, qui exécute les plugins en Java 21 ([ADR 0027](ARCH-0027-intellij-external-client-and-versioned-cli-protocol.md)).

MINOS est lancé comme pair MCP en STDIO par MORPHEUS, dont le runtime Java embarqué est en version 21 : un jar compilé pour Java 25 (classes version 69) n'y démarre pas.

Faits mesurés le 2026-11-06 avec `maven.compiler.release` à 21 :

- la seule construction de langage de MINOS absente de Java 21 est la variable sans nom `_` (JEP 456, Java 22), présente à 62 endroits de 32 fichiers ; un nom ordinaire la remplace sans changer le comportement ;
- le reactor de 14 modules compile et ses 4127 tests passent sous le JDK 21, sans autre changement de code ;
- le jar ombré s'exécute sous un Java 21 réel : `initialize`, `tools/list` (31 outils) et `minos_index_status` répondent comme sous Java 25.

Non mesurés : une indexation réelle sous Java 21 (bac à sable Windows compris), les images Docker, le runtime de l'installateur Windows, la CI sous Linux.

Temurin 21 (LTS) reçoit des mises à jour au moins jusqu'en décembre 2029 ([Adoptium](https://adoptium.net/support/), lu le 2026-11-06).

Options écartées :

- **Un framework serveur dans le cœur** : temps de démarrage, dépendances, configuration, abstractions réseau inutiles et couplage prématuré (ADR 0004 § « Pourquoi pas de framework serveur dans le cœur »).
- **Garder Java 25** : le jar ne démarre pas sur le runtime Java 21 du pair MCP.
- **Reporter le contrôle de la baseline** en laissant la version Java s'écrire sans garde : les sites divergent (ADR 0059 § Context).
- **Élargir l'enforcer à une plage de deux versions** : contraire à la politique de toolchain, un livrable construit sur une autre version resterait possible (ADR 0059 § Alternatives écartées).
- **Suivre chaque version semestrielle** : six mois de support par version, incompatible avec le rythme de qualification Linux, Windows, Docker et installateur (ADR 0059 § Alternatives écartées).

Maven 3.9.x est retenu pour sa stabilité, son support des projets Java et de la CI, et parce que l'auteur le maîtrise ; une migration vers Maven 4 reste possible lorsqu'elle apportera un bénéfice réel (ADR 0004 § « Pourquoi Maven 3.9.x »).

## Decision

1. **Java 21 pour le produit.** Le noyau, la CLI, le MCP, le stockage et les providers sont compilés pour Java 21 (`maven.compiler.release`), et Maven Enforcer exige `[21,22)`. Les images Docker partent d'`eclipse-temurin:21` (tag et digest) ; le runtime de l'installateur Windows est produit par le JDK 21.
2. **Une source de vérité gardée.** `maven.compiler.release` du POM racine est la source de vérité de la version Java. `scripts/quality/check-toolchain-baseline.py` échoue quand l'enforcer, un `java-version:` de workflow, une image `eclipse-temurin`, `docs/developer/TOOLCHAIN_POLICY.md`, `AGENTS.md` ou `openspec/config.yaml` énonce une autre version.
3. **Plugin IntelliJ en Java 21.** Sa baseline suit IntelliJ Platform, pas le produit. Tant que le produit est en Java 21, ses étapes de workflow sont des étapes du produit ; si le produit passe à une autre version, elles forment une liste fermée du gate, et Java 21 ailleurs, ou toute troisième version, est refusé. Le plugin reste un client externe du protocole CLI ([ADR 0027](ARCH-0027-intellij-external-client-and-versioned-cli-protocol.md)).
4. **Fin de support suivie.** La politique de toolchain écrit la date de fin de support de la baseline et ouvre le changement suivant au plus tard douze mois avant. Une baseline hors support figure au registre des risques tant qu'elle n'est pas remplacée.
5. **Majeur du JDK hors propositions automatiques.** La règle `ignore` de Dependabot sur les montées majeures d'`eclipse-temurin` est gardée : le majeur du JDK suit cette politique.
6. **Maven par le wrapper.** Le build utilise Apache Maven 3.9.x par le Maven Wrapper versionné (`mvnw`, `mvnw.cmd`), point d'entrée canonique du build (ADR 0004 § Decision et § Build ; `docs/developer/TOOLCHAIN_POLICY.md`).
7. **Aucun framework serveur dans le cœur.** Le domaine et les services ne dépendent d'aucun framework serveur (Spring, Quarkus, Micronaut). CLI, MCP et API restent des adaptateurs périphériques ; une couche d'exposition réseau future peut choisir son framework indépendamment, sans pénétrer le domaine. Les DTO et objets du domaine ne dépendent pas du framework d'exposition, les adaptateurs d'indexeurs externes sont isolés du domaine, et les outils externes restent exécutables comme processus séparés lorsque nécessaire (ADR 0004 § Framework et § « Règles courantes » 3 à 7). L'[ADR 0057](ARCH-0057-finaliser-les-frontieres-hexagonales.md) réaffirme cette exclusion pour les frontières hexagonales.

## Do's and Don'ts

### Do

- Compiler noyau, CLI, MCP, stockage et providers pour Java 21, avec l'enforcer `[21,22)`.
- Changer la version Java par `maven.compiler.release` du POM racine, puis aligner dans la même PR chaque site que nomme `check-toolchain-baseline.py`.
- Construire par le Maven Wrapper versionné (Maven 3.9.x).
- Nommer `ignored` toute exception volontairement ignorée : la variable sans nom `_` n'existe qu'à partir de Java 22.
- Écrire dans la politique de toolchain la date de fin de support de la baseline et ouvrir le changement suivant au plus tard douze mois avant.
- Garder CLI, MCP et API comme adaptateurs périphériques, et isoler du domaine les adaptateurs d'indexeurs externes.

### Don't

- Ne pas introduire Spring, Quarkus ou Micronaut dans le domaine ou les services.
- Ne pas faire dépendre les DTO et objets du domaine du framework d'exposition.
- Ne pas élargir la plage de l'enforcer au-delà d'une version majeure.
- Ne pas employer de construction de langage ni d'API postérieures à Java 21 dans le produit.
- Ne pas laisser Dependabot proposer une montée majeure d'`eclipse-temurin`.

## Consequences

### Positives

- Le jar s'exécute sur le runtime Java 21 du pair MCP qui le lance.
- Une montée de baseline est un changement d'une ligne suivi d'un gate rouge qui nomme chaque site : elle se fait dans une seule PR.
- L'installateur Windows et les images Docker embarquent leur runtime et ne demandent rien à l'utilisateur ([ADR 0069](ARCH-0069-backends-mcp-natif-et-docker.md)).
- Le cœur reste léger à démarrer et à tester : aucune configuration ni abstraction réseau imposée par un framework (ADR 0004 § « Pourquoi pas de framework serveur dans le cœur »).
- Une seule baseline Java couvre le produit et le plugin.

### Négatives

- Une installation par jar exige un JDK 21.
- Le produit n'emploie ni les constructions de langage ni les API de Java 22 à 25.
- Le support de Temurin 21 s'achève avant celui de Temurin 25 : le changement de baseline suivant s'ouvre au plus tard en décembre 2028.

### Limites

- Le gate ne prouve ni la date écrite dans la politique, ni la qualification du JDK ; celle-ci reste portée par les jobs `Verify`, les workflows d'image et d'installateur.
- Les capacités qui dépendent du JDK d'exécution (indexation réelle par les providers, bac à sable) ne sont pas requalifiées sous Java 21 par ce changement.

## Compliance and Enforcement

- Gate `scripts/quality/check-toolchain-baseline.py` (job `invariants`, `.github/workflows/pr-ci.yml:100`) et son auto-test `scripts/quality/test_check_toolchain_baseline.py` (`.github/workflows/pr-ci.yml:103`) — règles 4 à 7 : l'enforcer exige exactement `[N,N+1)` pour `N` = `maven.compiler.release` ; chaque `java-version:` de workflow vaut `N`, hors listes fermées des étapes du plugin (quand `N` n'est pas 21) et des qualifications de montée ; chaque `FROM eclipse-temurin:` a le majeur `N` ; `TOOLCHAIN_POLICY.md` énonce `N` et la date de fin de support, `AGENTS.md` et `openspec/config.yaml` énoncent `N`. Le même gate garde le Gradle du plugin et ses verrous de dépendances (règles 1 à 3 et 8 à 12), qui relèvent de la politique de toolchain ; il ne garde pas la version de Maven.
- Compilation : `maven.compiler.release` à 21 refuse toute construction de langage ou API postérieure à Java 21.
- Maven Enforcer (`pom.xml:214-215`) — `requireJavaVersion` `[21,22)` et `requireMavenVersion` `[3.9,4.0)` : un build hors de ces plages échoue.
- Aucun contrôle automatique de la version de Maven que télécharge le wrapper, ni de la règle `ignore` de Dependabot (`.github/dependabot.yml:68-71`).
- Revue : aucun framework serveur dans le domaine ou les services ; adaptateurs d'indexeurs isolés du domaine ; règle `ignore` des majeurs d'`eclipse-temurin` toujours présente ; date de fin de support de la politique relue contre la page de l'éditeur.
- Règle Archgate `GEN-0077-socle-d-execution-java-21-et-maven.rules.ts` / `enforcer-matches-java-release` : refuse une plage `requireJavaVersion` autre que `[N,N+1)`, `N` étant lu dans `maven.compiler.release` du POM racine ; second exécutant de `scripts/quality/check-toolchain-baseline.py` (règle 4).
- Règle Archgate `GEN-0077-socle-d-execution-java-21-et-maven.rules.ts` / `workflow-java-steps-match-release` : refuse un `java-version:` de workflow autre que `N` hors des listes fermées `PLUGIN_JAVA_STEPS` (Java 21 du plugin, quand `N` n'est pas 21) et `PREVIOUS_JAVA_STEPS`, lues dans le gate, et toute étape en surnombre ; second exécutant de `scripts/quality/check-toolchain-baseline.py` (règle 5).
- Règle Archgate `GEN-0077-socle-d-execution-java-21-et-maven.rules.ts` / `temurin-images-match-release` : refuse un `FROM eclipse-temurin:` d'un autre majeur que `N` dans un Dockerfile du dépôt ; second exécutant de `scripts/quality/check-toolchain-baseline.py` (règle 6).
- Règle Archgate `GEN-0077-socle-d-execution-java-21-et-maven.rules.ts` / `documents-state-java-release` : refuse que `TOOLCHAIN_POLICY.md` (version, plage et date de fin de support), `AGENTS.md` ou `openspec/config.yaml` énonce une autre version que `N` ou ne l'énonce pas ; second exécutant de `scripts/quality/check-toolchain-baseline.py` (règle 7).
- Règle Archgate `GEN-0077-socle-d-execution-java-21-et-maven.rules.ts` / `no-server-framework-in-poms` : refuse un `groupId` Spring (`org.springframework*`), Quarkus (`io.quarkus*`) ou Micronaut (`io.micronaut*`) dans un `pom.xml` du dépôt ; contrôle propre à Archgate, aucun gate Python ne le fait.

## Écarts constatés

Relevé le 2026-10-23 (audit des ADR), non tranché par le propriétaire.

- La décision retient Apache Maven 3.9.x par le wrapper, comme la politique de toolchain ; le wrapper télécharge Maven 3.10.0, que l'enforcer `[3.9,4.0)` accepte, et `AGENTS.md` écrit « Maven 3.9+ » — preuve : `.mvn/wrapper/maven-wrapper.properties:3`, `pom.xml:215`, `docs/developer/TOOLCHAIN_POLICY.md:11`, `AGENTS.md:15`.

## References

- ADR remplacé : [GEN-0067](archive/GEN-0067-socle-d-execution-java-25-et-maven.md) (Java 25), lui-même issu de [0004](archive/0004-stack-java-maven-core-sans-framework.md) (décision C0 : Maven, cœur sans framework serveur) et [0059](archive/0059-migrer-le-socle-vers-java-25-lts.md) (gate de baseline).
- ADR liés : [0027](ARCH-0027-intellij-external-client-and-versioned-cli-protocol.md) (plugin IntelliJ externe en Java 21), [0057](ARCH-0057-finaliser-les-frontieres-hexagonales.md) (aucun framework serveur), [0069](ARCH-0069-backends-mcp-natif-et-docker.md) (runtime embarqué de la distribution native).
- Politique : [`docs/developer/TOOLCHAIN_POLICY.md`](../../developer/TOOLCHAIN_POLICY.md).
- Audit : [`docs/project/audits/2026-10-10/findings.md`](../../project/audits/2026-10-10/findings.md) (AUD-DEP-01).
- Historique de C0 : [`docs/project/history/c0/VALIDATION_C0.md`](../../project/history/c0/VALIDATION_C0.md).
