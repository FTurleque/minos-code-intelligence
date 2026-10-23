---
id: ADR-0059
title: "Migrer le socle MINOS vers Java 25 LTS et garder la baseline par un gate"
domain: general
rules: false
---

# ADR 0059 — Migrer le socle MINOS vers Java 25 LTS et garder la baseline par un gate

Date : 2026-10-13. Statut : **Accepted**. Remplace : **ADR-0005** (Java 24).

## Context

L'ADR-0005 avait aligné MINOS sur Java 24 pendant M0, pour ne pas imposer un second JDK au poste de développement. Java 24 n'est pas une version LTS : sa dernière mise à jour est la 24.0.2 de juillet 2025 et son support s'est arrêté en septembre 2025. L'audit du 10 octobre 2026 (AUD-DEP-01) constate que tous les livrables — jar, images Docker, runtime de l'installateur Windows — tournent sur un JDK sans correctif de sécurité, et qu'aucun contrôle ne garde la version : elle était écrite dans une soixantaine de fichiers (POM, 10 workflows, 2 Dockerfiles, scripts à garde, documentation).

Mesures du 2026-10-13 :

- Temurin 25 est la LTS courante : dernier correctif 25.0.4.1+1 (août 2026), mises à jour annoncées jusqu'à septembre 2031 au moins (page de support d'Adoptium) ;
- images `eclipse-temurin:25.0.4.1_1-jre` et `-jdk` publiées, digests relevés par `docker buildx imagetools inspect` ;
- le plugin IntelliJ est lié à IntelliJ Platform 2026.1, qui exécute les plugins en Java 21.

## Decision

1. Le noyau, la CLI, le MCP, le stockage et les providers sont compilés pour **Java 25** (`maven.compiler.release`), et Maven Enforcer exige `[25,26)`. Les deux images Docker partent d'`eclipse-temurin:25` (tag et digest), le runtime de l'installateur Windows est produit par le JDK 25.
2. `maven.compiler.release` du POM racine est la **source de vérité**. `scripts/quality/check-toolchain-baseline.py` échoue quand l'enforcer, un `java-version:` de workflow, une image `eclipse-temurin`, `docs/developer/TOOLCHAIN_POLICY.md`, `AGENTS.md` ou `openspec/config.yaml` énonce une autre version.
3. Le **plugin IntelliJ reste en Java 21**. Ses étapes de workflow sont une liste fermée du gate ; Java 21 ailleurs, ou toute troisième version, est refusé.
4. La politique de toolchain écrit la **date de fin de support** de la baseline et ouvre le changement suivant au plus tard douze mois avant. Une baseline hors support figure au registre des risques tant qu'elle n'est pas remplacée.
5. La règle `ignore` de Dependabot sur les montées majeures d'`eclipse-temurin` est **gardée** : le majeur du JDK suit cette politique, pas une proposition automatique.
6. Aucune API propre à Java 25 n'est adoptée par ce changement.

## Do's and Don'ts

### Do

- Compiler noyau, CLI, MCP, stockage et providers pour Java 25 ; Maven Enforcer exige `[25,26)`.
- Tenir `maven.compiler.release` du POM racine pour source de vérité, gardée par `scripts/quality/check-toolchain-baseline.py`.
- Garder le plugin IntelliJ en Java 21.
- Écrire la date de fin de support de la baseline et ouvrir le changement suivant au plus tard douze mois avant.

### Don't

- Ne pas laisser Dependabot proposer une montée majeure d'`eclipse-temurin`.
- N'adopter aucune API propre à Java 25 par ce changement.
- Ne pas élargir l'enforcer à `[24,26)`.
- Refuser Java 21 hors des étapes du plugin, et toute troisième version.

## Consequences

- Une installation par jar exige un JDK 25 ; l'installateur Windows et les images Docker embarquent leur runtime et ne demandent rien à l'utilisateur.
- Le prérequis public du catalogue des indexeurs devient « Java 25 JDK » (golden de caractérisation mis à jour d'une valeur).
- Les scripts de jalons encore rejoués (`scripts/m24` à `m27`) exigent Java 25 ; les scripts de `scripts/history/` gardent leur texte d'époque et ne sont plus exécutables tels quels.
- La fixture `fixtures/java/java-24-smoke` est conservée : elle prouve l'indexation d'un **projet** compilé en Java 24, cas d'un utilisateur.
- Une montée de baseline redevient un changement d'une ligne suivi d'un gate rouge qui nomme chaque site : elle se fait dans une seule PR.
- Limite : le gate ne prouve ni la date écrite dans la politique, ni la qualification du JDK ; celle-ci reste portée par les jobs `Verify`, les workflows d'image et d'installateur.

## Compliance and Enforcement

- Gate `scripts/quality/check-toolchain-baseline.py` (job `invariants`) et son auto-test `test_check_toolchain_baseline.py` — enforcer, `java-version:` des workflows, images `eclipse-temurin`, `TOOLCHAIN_POLICY.md`, `AGENTS.md` et `openspec/config.yaml` alignés sur `maven.compiler.release`.
- Maven Enforcer `requireJavaVersion` `[25,26)` (`pom.xml`) — tout build hors JDK 25 échoue.
- Revue : la règle `ignore` de Dependabot sur les majeurs d'`eclipse-temurin` reste en place ; la date de fin de support écrite dans la politique n'est pas prouvée par le gate.

## Alternatives écartées

- **Reporter** en inscrivant le risque au registre : le défaut de sécurité reste ouvert sans gain, la migration étant un changement de constante suivi d'une qualification.
- **Élargir l'enforcer à `[24,26)`** : contraire à la politique (« ne pas élargir une plage »), et un livrable construit en Java 24 resterait possible.
- **Suivre chaque version semestrielle** : six mois de support par version, incompatible avec le rythme de qualification Linux, Windows, Docker et installateur.

## References

- Audit : [`docs/project/audits/2026-10-10/findings.md`](../../project/audits/2026-10-10/findings.md) (AUD-DEP-01).
- Politique : [`docs/developer/TOOLCHAIN_POLICY.md`](../../developer/TOOLCHAIN_POLICY.md).
- ADR remplacé : [0005](0005-aligner-java-24-environnement-developpement.md) ; notes de mise en œuvre ajoutées aux ADR [0004](0004-stack-java-maven-core-sans-framework.md), [0021](0021-native-runtime-autonomous-indexing.md) et [0027](0027-intellij-external-client-and-versioned-cli-protocol.md).
