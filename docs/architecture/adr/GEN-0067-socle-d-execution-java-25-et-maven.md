---
id: GEN-0067
title: "Socle d'exécution : Java 25, Maven par le wrapper, sans framework serveur"
domain: general
rules: true
---

# Socle d'exécution : Java 25, Maven par le wrapper, sans framework serveur

- **Statut** : Accepted
- **Date** : 2026-10-23
- **Origine** : consolidation du registre (décision du propriétaire du 2026-10-23)
- **Remplace** : [ADR-0004](archive/0004-stack-java-maven-core-sans-framework.md), [ADR-0059](archive/0059-migrer-le-socle-vers-java-25-lts.md) (l'[ADR-0005](archive/0005-aligner-java-24-environnement-developpement.md), Java 24, est remplacé par l'ADR 0059 et archivé)
- **Remplacé par** : —

## Context

MINOS distingue le langage dans lequel il est développé des langages qu'il analyse : le cœur reste agnostique des langages analysés, même s'il est lui-même écrit en Java (ADR 0004 § Context).

Le socle répond à ces contraintes (ADR 0004 § Context) : Windows, Linux et macOS ; local-first ; bonne intégration Protobuf ; outillage mature ; performances suffisantes pour des services locaux ; maintenance longue durée ; tests et packaging reproductibles ; aucun framework serveur tant qu'une API réseau n'est pas réellement nécessaire.

Le JDK du produit doit recevoir des correctifs de sécurité. Une version non LTS n'est plus corrigée six mois après sa sortie, alors que le jar, les images Docker et le runtime de l'installateur Windows en dépendent ; sans contrôle, la version Java s'écrit dans des dizaines de fichiers (POM, workflows, Dockerfiles, scripts, documentation) qui divergent (ADR 0059 § Context). Le plugin IntelliJ est lié à IntelliJ Platform, qui exécute les plugins en Java 21 (ADR 0059 § Context).

Options écartées :

- **Un framework serveur dans le cœur** : temps de démarrage, dépendances, configuration, abstractions réseau inutiles et couplage prématuré (ADR 0004 § « Pourquoi pas de framework serveur dans le cœur »).
- **Reporter la montée du JDK** en inscrivant le risque au registre : le défaut de sécurité reste ouvert sans gain (ADR 0059 § Alternatives écartées).
- **Élargir l'enforcer à une plage de deux versions** : contraire à la politique de toolchain, un livrable construit sur l'ancienne version resterait possible (ADR 0059 § Alternatives écartées).
- **Suivre chaque version semestrielle** : six mois de support par version, incompatible avec le rythme de qualification Linux, Windows, Docker et installateur (ADR 0059 § Alternatives écartées).

Maven 3.9.x est retenu pour sa stabilité, son support des projets Java et de la CI, et parce que l'auteur le maîtrise ; une migration vers Maven 4 reste possible lorsqu'elle apportera un bénéfice réel (ADR 0004 § « Pourquoi Maven 3.9.x »). La comparaison initiale des langages (Java 21, 25, 26, Kotlin, Go, Rust) et l'alignement temporaire sur Java 24 sont l'historique des ADR archivés.

## Decision

1. **Java 25 pour le produit.** Le noyau, la CLI, le MCP, le stockage et les providers sont compilés pour Java 25 (`maven.compiler.release`), et Maven Enforcer exige `[25,26)`. Les images Docker partent d'`eclipse-temurin:25` (tag et digest) ; le runtime de l'installateur Windows est produit par le JDK 25 (ADR 0059 § 1).
2. **Une source de vérité gardée.** `maven.compiler.release` du POM racine est la source de vérité de la version Java. `scripts/quality/check-toolchain-baseline.py` échoue quand l'enforcer, un `java-version:` de workflow, une image `eclipse-temurin`, `docs/developer/TOOLCHAIN_POLICY.md`, `AGENTS.md` ou `openspec/config.yaml` énonce une autre version (ADR 0059 § 2).
3. **Plugin IntelliJ en Java 21.** Ses étapes de workflow sont une liste fermée du gate ; Java 21 ailleurs, ou toute troisième version, est refusé (ADR 0059 § 3). Le plugin reste un client externe du protocole CLI ([ADR 0027](ARCH-0027-intellij-external-client-and-versioned-cli-protocol.md)).
4. **Fin de support suivie.** La politique de toolchain écrit la date de fin de support de la baseline et ouvre le changement suivant au plus tard douze mois avant. Une baseline hors support figure au registre des risques tant qu'elle n'est pas remplacée (ADR 0059 § 4).
5. **Majeur du JDK hors propositions automatiques.** La règle `ignore` de Dependabot sur les montées majeures d'`eclipse-temurin` est gardée : le majeur du JDK suit cette politique (ADR 0059 § 5).
6. **Maven par le wrapper.** Le build utilise Apache Maven 3.9.x par le Maven Wrapper versionné (`mvnw`, `mvnw.cmd`), point d'entrée canonique du build (ADR 0004 § Decision et § Build ; `docs/developer/TOOLCHAIN_POLICY.md`).
7. **Aucun framework serveur dans le cœur.** Le domaine et les services ne dépendent d'aucun framework serveur (Spring, Quarkus, Micronaut). CLI, MCP et API restent des adaptateurs périphériques ; une couche d'exposition réseau future peut choisir son framework indépendamment, sans pénétrer le domaine. Les DTO et objets du domaine ne dépendent pas du framework d'exposition, les adaptateurs d'indexeurs externes sont isolés du domaine, et les outils externes restent exécutables comme processus séparés lorsque nécessaire (ADR 0004 § Framework et § « Règles courantes » 3 à 7). L'[ADR 0057](ARCH-0057-finaliser-les-frontieres-hexagonales.md) réaffirme cette exclusion pour les frontières hexagonales.

## Do's and Don'ts

### Do

- Compiler noyau, CLI, MCP, stockage et providers pour Java 25, avec l'enforcer `[25,26)`.
- Changer la version Java par `maven.compiler.release` du POM racine, puis aligner dans la même PR chaque site que nomme `check-toolchain-baseline.py`.
- Construire par le Maven Wrapper versionné (Maven 3.9.x).
- Garder le plugin IntelliJ en Java 21, dans les seules étapes listées par le gate.
- Écrire dans la politique de toolchain la date de fin de support de la baseline et ouvrir le changement suivant au plus tard douze mois avant.
- Garder CLI, MCP et API comme adaptateurs périphériques, et isoler du domaine les adaptateurs d'indexeurs externes.

### Don't

- Ne pas introduire Spring, Quarkus ou Micronaut dans le domaine ou les services.
- Ne pas faire dépendre les DTO et objets du domaine du framework d'exposition.
- Ne pas élargir la plage de l'enforcer au-delà d'une version majeure.
- Ne pas utiliser Java 21 hors des étapes du plugin, ni une troisième version de Java.
- Ne pas laisser Dependabot proposer une montée majeure d'`eclipse-temurin`.

## Consequences

### Positives

- Une montée de baseline est un changement d'une ligne suivi d'un gate rouge qui nomme chaque site : elle se fait dans une seule PR (ADR 0059 § Consequences).
- L'installateur Windows et les images Docker embarquent leur runtime et ne demandent rien à l'utilisateur (ADR 0059 § Consequences ; [ADR 0069](ARCH-0069-backends-mcp-natif-et-docker.md)).
- Le cœur reste léger à démarrer et à tester : aucune configuration ni abstraction réseau imposée par un framework (ADR 0004 § « Pourquoi pas de framework serveur dans le cœur »).

### Négatives

- Une installation par jar exige un JDK 25 (ADR 0059 § Consequences).
- Deux baselines Java coexistent dans le dépôt : 25 pour le produit, 21 pour le plugin (ADR 0059 § 3).

### Limites

- Le gate ne prouve ni la date écrite dans la politique, ni la qualification du JDK ; celle-ci reste portée par les jobs `Verify`, les workflows d'image et d'installateur (ADR 0059 § Consequences).

## Compliance and Enforcement

- Gate `scripts/quality/check-toolchain-baseline.py` (job `invariants`, `.github/workflows/pr-ci.yml:100`) et son auto-test `scripts/quality/test_check_toolchain_baseline.py` (`.github/workflows/pr-ci.yml:103`) — règles 4 à 7 : l'enforcer exige exactement `[N,N+1)` pour `N` = `maven.compiler.release` ; chaque `java-version:` de workflow vaut `N`, hors liste fermée des étapes Java 21 du plugin ; chaque `FROM eclipse-temurin:` a le majeur `N` ; `TOOLCHAIN_POLICY.md` énonce `N` et la date de fin de support, `AGENTS.md` et `openspec/config.yaml` énoncent `N`. Le même gate garde le Gradle du plugin et ses verrous de dépendances (règles 1 à 3 et 8 à 12), qui relèvent de la politique de toolchain ; il ne garde pas la version de Maven.
- Maven Enforcer (`pom.xml:215-216`) — `requireJavaVersion` `[25,26)` et `requireMavenVersion` `[3.9,4.0)` : un build hors de ces plages échoue.
- Aucun contrôle automatique de la version de Maven que télécharge le wrapper, ni de la règle `ignore` de Dependabot (`.github/dependabot.yml:68-71`).
- Revue : aucun framework serveur dans le domaine ou les services ; adaptateurs d'indexeurs isolés du domaine ; règle `ignore` des majeurs d'`eclipse-temurin` toujours présente ; date de fin de support de la politique relue contre la page de l'éditeur.
- Règle Archgate `GEN-0067-socle-d-execution-java-25-et-maven.rules.ts` / `enforcer-matches-java-release` : refuse une plage `requireJavaVersion` autre que `[N,N+1)`, `N` étant lu dans `maven.compiler.release` du POM racine ; second exécutant de `scripts/quality/check-toolchain-baseline.py` (règle 4).
- Règle Archgate `GEN-0067-socle-d-execution-java-25-et-maven.rules.ts` / `workflow-java-steps-match-release` : refuse un `java-version:` de workflow autre que `N` hors des listes fermées `PLUGIN_JAVA_STEPS` (Java 21 du plugin) et `PREVIOUS_JAVA_STEPS`, lues dans le gate, et toute étape en surnombre ; second exécutant de `scripts/quality/check-toolchain-baseline.py` (règle 5).
- Règle Archgate `GEN-0067-socle-d-execution-java-25-et-maven.rules.ts` / `temurin-images-match-release` : refuse un `FROM eclipse-temurin:` d'un autre majeur que `N` dans un Dockerfile du dépôt ; second exécutant de `scripts/quality/check-toolchain-baseline.py` (règle 6).
- Règle Archgate `GEN-0067-socle-d-execution-java-25-et-maven.rules.ts` / `documents-state-java-release` : refuse que `TOOLCHAIN_POLICY.md` (version, plage et date de fin de support), `AGENTS.md` ou `openspec/config.yaml` énonce une autre version que `N` ou ne l'énonce pas ; second exécutant de `scripts/quality/check-toolchain-baseline.py` (règle 7).
- Règle Archgate `GEN-0067-socle-d-execution-java-25-et-maven.rules.ts` / `no-server-framework-in-poms` : refuse un `groupId` Spring (`org.springframework*`), Quarkus (`io.quarkus*`) ou Micronaut (`io.micronaut*`) dans un `pom.xml` du dépôt ; contrôle propre à Archgate, aucun gate Python ne le fait.

## Écarts constatés

Relevés le 2026-10-23 (audit des ADR) ; la décision ci-dessus reste celle en vigueur tant qu'un amendement ou une correction du code n'a pas tranché. Écart non tranché par le propriétaire au 2026-10-23.

- La décision retient Apache Maven 3.9.x par le wrapper, comme la politique de toolchain ; le wrapper télécharge Maven 3.10.0, que l'enforcer `[3.9,4.0)` accepte, et `AGENTS.md` écrit « Maven 3.9+ » — preuve : `.mvn/wrapper/maven-wrapper.properties:3`, `pom.xml:216`, `docs/developer/TOOLCHAIN_POLICY.md:11`, `AGENTS.md:15`.

## References

- ADR remplacés : [0004](archive/0004-stack-java-maven-core-sans-framework.md) (décision C0 : Maven, cœur sans framework serveur), [0059](archive/0059-migrer-le-socle-vers-java-25-lts.md) (Java 25 LTS et gate de baseline) ; [0005](archive/0005-aligner-java-24-environnement-developpement.md) (Java 24, remplacé par 0059).
- ADR liés : [0027](ARCH-0027-intellij-external-client-and-versioned-cli-protocol.md) (plugin IntelliJ externe en Java 21), [0057](ARCH-0057-finaliser-les-frontieres-hexagonales.md) (aucun framework serveur), [0069](ARCH-0069-backends-mcp-natif-et-docker.md) (runtime embarqué de la distribution native).
- Politique : [`docs/developer/TOOLCHAIN_POLICY.md`](../../developer/TOOLCHAIN_POLICY.md).
- Audit : [`docs/project/audits/2026-10-10/findings.md`](../../project/audits/2026-10-10/findings.md) (AUD-DEP-01).
- Historique de C0 : [`docs/project/history/c0/VALIDATION_C0.md`](../../project/history/c0/VALIDATION_C0.md).
