# Proposal

## Why

Constat **AUD-PERF-13** (audit du 2026-11-15, sprint 1, sévérité faible, effort M). Le banc `benchmarks/scalability/` ne mesure ni la préparation de la projection 2D ni les routes de l'explorateur : aucun de ses fichiers ne contient `projection`, `explorer` ni `semantic`. Une mesure rejouable de la seule projection existe hors du banc (`SemanticProjectionMeasurementTest`, stockage local, 256 dimensions, tailles libres), mais elle ne couvre ni PostgreSQL, ni les routes de l'explorateur, ni la dimension d'un modèle réel. Les constats de performance AUD-PERF-01, AUD-PERF-02 et AUD-PERF-03 du même audit sont donc des estimations (« calculé et non mesuré », « borne théorique »), et le critère de sortie du sprint 4 — un gain mesuré avant et après — ne peut pas être tenu. Le projet s'est donné la règle inverse dans l'ADR DATA-0081 : une évolution se fonde « sur une mesure du corpus réel par le banc `benchmarks/scalability/`, jamais sur une estimation ».

Ce changement est le **prérequis du sprint 4** : il doit produire, avant toute correction, la référence à laquelle ce sprint se comparera, et dire précisément ce qu'elle contient (section « Ce que le sprint 4 pourra mesurer » du design).

## What Changes

- Un harnais de mesure sémantique, `main` des sources de test (jamais exécuté par `mvn verify`), mesure sur des index à 768 dimensions — 25 000 et 100 000 documents sous PostgreSQL ; 25 000 et 32 768 documents en stockage local (taille maximale que le magasin local décode en entier au HEAD), plus un jeu local de 100 000 documents qui constate le refus des routes : la préparation de la projection (durée, nombre de passes, vecteurs lus, mémoire de travail de pointe) et les routes de l'explorateur servies en HTTP réel sur la boucle locale (liste première page et page profonde, détail, voisins, état de projection, page de points, recherche) : latence p50 et p95, octets alloués par requête, tas retenu.
- Une configuration `benchmarks/scalability/semantic.json` et un script de lancement, sur le modèle des fichiers existants ; résultats au format `METRIC section jeu mesure valeur`, hors du dépôt.
- Une section impossible à mesurer (PostgreSQL injoignable, index au-dessus d'un plafond) est écrite dans le résultat avec sa raison ; elle n'est jamais omise.
- Un test de fumée exécute le harnais sur un jeu minuscule dans `mvn verify` et vérifie que chaque mesure attendue est émise, pour que le banc ne dérive pas du code.
- La référence obtenue sur la machine de mesure est consignée dans le suivi de l'audit, avec son environnement, avant le sprint 4.

Aucun code de production ne change. Aucun seuil de performance n'est introduit.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities
- `exploration-semantique` : trois exigences ajoutées (référence mesurée de la projection et des routes ; mesure impossible nommée ; harnais gardé par un test de fumée). Aucune exigence existante n'est modifiée.

## Hors périmètre

- Toute correction de performance : AUD-PERF-01, AUD-PERF-02, AUD-PERF-03 (sprint 4), AUD-PERF-04 (sprint 5).
- Tout seuil ou objectif chiffré de latence, et tout gate de CI sur une durée.
- La mesure de la page dans un navigateur (survol, tracé) : les compteurs du front sont dans `executer-le-front-de-l-explorateur-en-test` (AUD-TST-02).
- Un rejeu Linux des scripts du banc : le banc existant est propre à Windows ; le harnais Java ne l'est pas, mais seul le lancement Windows est scripté ici.
- L'exécution du banc en CI.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| Le banc ne contient aucun scénario sémantique | [Existant vérifié] | recherche de `projection`, `explorer`, `semantic` dans `benchmarks/` : 0 fichier ; sections `memory`, `hybrid`, `impact`, `architecture` : `benchmarks/scalability/scalability.json:28` |
| Une mesure de la projection seule existe : stockage local, 256 dimensions, relevé dans `target/projection-measurements.md` | [Existant vérifié] | `minos-bootstrap/src/test/java/com/minos/bootstrap/semantic/SemanticProjectionMeasurementTest.java:36-49` ; `docs/developer/semantic-explorer.md:174-176` |
| La synchronisation du produit refuse de construire un index au-delà de 192 Mio de vecteurs comptés à 8 octets par composante (32 768 documents à 768 dimensions) et de 250 000 documents, quel que soit le stockage | [Existant vérifié] | `minos-application/src/main/java/com/minos/application/semantic/SemanticIndexBudget.java:15-19`, `:59-75` ; appliqué par `SemanticIndexService.java:137-138` |
| La préparation rend déjà passes, durée et mémoire de travail de pointe | [Existant vérifié] | `minos-application/src/main/java/com/minos/application/semantic/SemanticProjectionService.java:85-89`, `:224-225` |
| Le provider déterministe accepte 768 dimensions | [Existant vérifié] | `minos-application/src/main/java/com/minos/application/semantic/LocalHashEmbeddingProvider.java:23-25` (32 à 4 096) |
| Un PostgreSQL de test se démarre sur le poste Windows | [Existant vérifié] | `scripts/dev/start-test-postgres.ps1`, `docs/developer/testing.md:60-70` |
| Tailles 25 000 et 100 000 documents, 768 dimensions | [Exigence] | action de AUD-PERF-13 |
| En stockage local, plus de 32 768 documents à 768 dimensions sont refusés par le décodage complet | [Existant vérifié] | `minos-storage/src/main/java/com/minos/storage/local/store/FileSemanticVectorStore.java:59`, `:691-699` (192 Mio à 8 octets par composante) |
| Jeu synthétique déterministe plutôt que corpus réel | [Proposition] | décision D2 du design |
| Valeurs de référence | [Question] | produites par la tâche manuelle 3.1 |

## Impact

- **Modules du reactor** : `minos-explorer` (sources de test seulement). Aucune dépendance Maven ajoutée : `minos-bootstrap` est déjà une dépendance `runtime` de `minos-explorer` (`minos-explorer/pom.xml:29-33`), ses dépendances de test sont `minos-engine` et son test-jar (`:36-47`). Le test-jar de `minos-storage`, où vit `PostgresTestSupport`, n'est pas ajouté : le harnais joint PostgreSQL par la configuration du produit (design, D8).
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune.
- **ADR** : aucun. DATA-0081 est appliqué, pas amendé.
- **Gates à rejouer** : `python scripts/architecture/check-module-boundaries.py` (classes de test ajoutées au package existant `com.minos.explorer`), `python scripts/quality/check-comment-language.py`, `python scripts/quality/check-milestone-identifiers.py`, `python .claude/scripts/run_gates.py` ; `./mvnw -B -ntp -pl minos-explorer -am test` ; scope JaCoCo `explorer-surface` s'il est déjà fusionné (le harnais est du code de test, non mesuré).
- **Plateformes** : harnais Java indépendant du système ; script de lancement Windows (PowerShell 7), comme le banc existant ; section PostgreSQL soumise à la présence d'un serveur.
- **Documentation durable et diagrammes** : `benchmarks/scalability/README.md`, `docs/developer/semantic-explorer.md`, `docs/architecture/diagrams/capacites/exploration-semantique.md`, `docs/project/audits/2026-11-15/SUIVI.md` (référence datée).

## Questions ouvertes

Non bloquantes, avec recommandation dans le design : D2 (jeu synthétique et contrôle sur corpus réel), D5 (emplacement de la référence), D9 (sort de la mesure existante).
