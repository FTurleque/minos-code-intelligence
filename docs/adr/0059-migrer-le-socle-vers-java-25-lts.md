# ADR 0059 — Migrer le socle MINOS vers Java 25 LTS et garder la baseline par un gate

Date : 2026-10-13. Statut : **Accepted**. Remplace : **ADR-0005** (Java 24).

## Contexte

L'ADR-0005 avait aligné MINOS sur Java 24 pendant M0, pour ne pas imposer un second JDK au poste de développement. Java 24 n'est pas une version LTS : sa dernière mise à jour est la 24.0.2 de juillet 2025 et son support s'est arrêté en septembre 2025. L'audit du 10 octobre 2026 (AUD-DEP-01) constate que tous les livrables — jar, images Docker, runtime de l'installateur Windows — tournent sur un JDK sans correctif de sécurité, et qu'aucun contrôle ne garde la version : elle était écrite dans une soixantaine de fichiers (POM, 10 workflows, 2 Dockerfiles, scripts à garde, documentation).

Mesures du 2026-10-13 :

- Temurin 25 est la LTS courante : dernier correctif 25.0.4.1+1 (août 2026), mises à jour annoncées jusqu'à septembre 2031 au moins (page de support d'Adoptium) ;
- images `eclipse-temurin:25.0.4.1_1-jre` et `-jdk` publiées, digests relevés par `docker buildx imagetools inspect` ;
- le plugin IntelliJ est lié à IntelliJ Platform 2026.1, qui exécute les plugins en Java 21.

## Décision

1. Le noyau, la CLI, le MCP, le stockage et les providers sont compilés pour **Java 25** (`maven.compiler.release`), et Maven Enforcer exige `[25,26)`. Les deux images Docker partent d'`eclipse-temurin:25` (tag et digest), le runtime de l'installateur Windows est produit par le JDK 25.
2. `maven.compiler.release` du POM racine est la **source de vérité**. `scripts/quality/check-toolchain-baseline.py` échoue quand l'enforcer, un `java-version:` de workflow, une image `eclipse-temurin`, `docs/TOOLCHAIN_POLICY.md`, `AGENTS.md` ou `openspec/config.yaml` énonce une autre version.
3. Le **plugin IntelliJ reste en Java 21**. Ses étapes de workflow sont une liste fermée du gate ; Java 21 ailleurs, ou toute troisième version, est refusé.
4. La politique de toolchain écrit la **date de fin de support** de la baseline et ouvre le changement suivant au plus tard douze mois avant. Une baseline hors support figure au registre des risques tant qu'elle n'est pas remplacée.
5. La règle `ignore` de Dependabot sur les montées majeures d'`eclipse-temurin` est **gardée** : le majeur du JDK suit cette politique, pas une proposition automatique.
6. Aucune API propre à Java 25 n'est adoptée par ce changement.

## Conséquences

- Une installation par jar exige un JDK 25 ; l'installateur Windows et les images Docker embarquent leur runtime et ne demandent rien à l'utilisateur.
- Le prérequis public du catalogue des indexeurs devient « Java 25 JDK » (golden de caractérisation mis à jour d'une valeur).
- Les scripts de jalons encore rejoués (`scripts/m24` à `m27`) exigent Java 25 ; les scripts de `scripts/history/` gardent leur texte d'époque et ne sont plus exécutables tels quels.
- La fixture `fixtures/java/java-24-smoke` est conservée : elle prouve l'indexation d'un **projet** compilé en Java 24, cas d'un utilisateur.
- Une montée de baseline redevient un changement d'une ligne suivi d'un gate rouge qui nomme chaque site : elle se fait dans une seule PR.
- Limite : le gate ne prouve ni la date écrite dans la politique, ni la qualification du JDK ; celle-ci reste portée par les jobs `Verify`, les workflows d'image et d'installateur.

## Alternatives écartées

- **Reporter** en inscrivant le risque au registre : le défaut de sécurité reste ouvert sans gain, la migration étant un changement de constante suivi d'une qualification.
- **Élargir l'enforcer à `[24,26)`** : contraire à la politique (« ne pas élargir une plage »), et un livrable construit en Java 24 resterait possible.
- **Suivre chaque version semestrielle** : six mois de support par version, incompatible avec le rythme de qualification Linux, Windows, Docker et installateur.

## Liens

- Audit : [`docs/audit/2026-10-10/findings.md`](../audit/2026-10-10/findings.md) (AUD-DEP-01).
- Politique : [`docs/TOOLCHAIN_POLICY.md`](../TOOLCHAIN_POLICY.md).
- ADR remplacé : [0005](0005-aligner-java-24-environnement-developpement.md) ; notes de mise en œuvre ajoutées aux ADR [0004](0004-stack-java-maven-core-sans-framework.md), [0021](0021-native-runtime-autonomous-indexing.md) et [0027](0027-intellij-external-client-and-versioned-cli-protocol.md).
