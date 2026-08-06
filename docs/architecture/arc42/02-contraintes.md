# Section 2 — Contraintes

> Preuves : `pom.xml` (versions enforcer), ADR-0004, ADR-0005, ADR-0017, ADR-0021, ADR-0035, ADR-0037,
> `minos-storage-postgresql/pom.xml` (Docker Testcontainers), ADR-0022.

---

## 2.1 Contraintes techniques imposées

| Réf | Contrainte | Nature | Preuve |
|-----|-----------|--------|--------|
| CT-1 | **Java 24** requis (range `[24,25)`) | Imposée — enforcer Maven | `pom.xml` `<requireJavaVersion>` |
| CT-2 | **Maven 3.9.x** requis (range `[3.9,4.0)`) | Imposée — enforcer Maven | `pom.xml` `<requireMavenVersion>` |
| CT-3 | **Reactor Maven multi-module** : 12 modules enfants + parent | Imposée — ADR-0022 | `pom.xml` `<modules>` |
| CT-4 | Direction de dépendances Maven stricte : `domain → engine → infra → adapters → services → surfaces → app` | Imposée — ADR-0022 | Structure `pom.xml` des modules |
| CT-5 | **SCIP** comme protocole d'interopérabilité d'indexation privilégié | Imposée — ADR-0002 | `minos-provider-scip/pom.xml` |
| CT-6 | **MCP STDIO read-only** — aucune mutation de projet via MCP | Imposée — ADR-0017 | `MinosMcpServer.java` |
| CT-7 | **Shaded JAR** produit par `minos-app` ; point d'entrée `com.minos.cli.MinosLauncher` | Imposée — ADR-0022 | `minos-app/pom.xml` shade plugin |
| CT-8 | **JGit** confiné à `minos-integration-git` | Imposée — ADR-0022 | `minos-integration-git/pom.xml` |
| CT-9 | **PostgreSQL / pgvector** : backend optionnel, qualifié par Testcontainers | Préférence qualifiée | `minos-storage-postgresql/pom.xml` |
| CT-10 | Aucun framework serveur (Spring, Quarkus…) dans le cœur | Imposée — ADR-0004 | Absence de dépendance framework dans `pom.xml` |

---

## 2.2 Contraintes métier

| Réf | Contrainte | Nature |
|-----|-----------|--------|
| CM-1 | **Local-first** : aucune donnée de code ne quitte le poste en mode standard | Imposée |
| CM-2 | Agnosticisme du langage et de l'indexeur : aucune décision par `if(java)…if(python)` dans le cœur | Imposée — ADR-0001 |
| CM-3 | Une relation retournée sans preuve (`Evidence`) est interdite dans les contrats publics | Imposée — ADR-0010, ADR-0015 |
| CM-4 | Aucune suppression d'un ADR accepté ; remplacements uniquement par nouvel ADR | Imposée — politique ADR |
| CM-5 | Le mode équipe est **opt-in** et n'affecte pas le mode local | Imposée — ADR-0035 |

---

## 2.3 Contraintes réglementaires et de confidentialité

| Réf | Contrainte | Nature |
|-----|-----------|--------|
| CR-1 | Le code source privé ne doit pas être envoyé à un service cloud sans consentement explicite | Imposée |
| CR-2 | Les clés de chiffrement tenant sont fournies en externe (`MINOS_TEAM_KEY_<KEY_ID>`) ; MINOS ne les génère pas | Imposée — ADR-0035 |
| CR-3 | La chaîne d'audit tenant est vérifiée par HMAC-SHA-256 ; les événements sont immuables | Imposée — ADR-0035 |

---

## 2.4 Contraintes organisationnelles

| Réf | Contrainte | Nature |
|-----|-----------|--------|
| CO-1 | Versions CI-friendly Maven : `${revision}`, override `-Drevision=<version>` en release | Imposée — ADR-0022 |
| CO-2 | Tests unitaires Surefire + tests d'intégration Failsafe qualifient chaque jalon | Imposée |
| CO-3 | Windows est la plateforme de qualification principale ; Linux doit également passer | Imposée — ADR-0035 |
| CO-4 | Le plugin IntelliJ est un client externe Java 21 isolé, pas un module interne | Imposée — ADR-0027 |

---

## 2.5 Préférences (non imposées)

- Utilisation de PostgreSQL/pgvector comme backend de stockage avancé (mesures requises avant promotion — ADR-0025).
- Recherche sémantique par vecteurs locale (optionnelle, reconstruisible — ADR-0029, ADR-0031).
- Mode Docker pour `minos mcp` (parité non encore acquise — ADR-0037).
