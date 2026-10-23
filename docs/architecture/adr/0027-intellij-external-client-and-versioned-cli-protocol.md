---
id: ADR-0027
title: "IntelliJ externe et protocole CLI versionné"
domain: architecture
rules: true
---

# ADR-0027 — IntelliJ externe et protocole CLI versionné

- Statut : Accepted
- Date : 2026-07-27
- Décision : M18

## Context

MINOS est qualifié avec Java 24. Les versions IntelliJ ciblées par M18 exécutent les plugins sur une plateforme Java 21. Faire dépendre directement le plugin des modules Maven MINOS couplerait les cycles de JVM, exposerait des classes internes et transformerait l'IDE en nouvelle composition du moteur.

M18 exige au contraire un plugin optionnel, sans duplication du métier et avec détection propre des incompatibilités de protocole.

## Decision

Le plugin `minos-intellij` est un **client externe** :

1. il est compilé indépendamment en Java 21 ;
2. il ne déclare aucune dépendance `com.minos:*` ;
3. il exécute la CLI MINOS locale dans des processus de fond ;
4. il exige d'abord `minos ide handshake --format json` ;
5. le protocole porte l'identifiant `minos-ide` et une version entière indépendante de `MinosApi.CONTRACT_VERSION` ;
6. les opérations métier réutilisent les commandes JSON stables existantes (`project`, `index-status`, `find-symbol`, `find-usages`, relations, `architecture`, `impact`, `index`, `doctor`) ;
7. M18 ajoute `git-activity` comme adapter CLI du `GitIntelligenceService` existant, sans réimplémentation Git dans le plugin.

Le protocole IDE v1 décrit donc à la fois le handshake et le sous-ensemble de commandes/schémas JSON autorisés pour le client IntelliJ.

## Do's and Don'ts

### Do

- Compiler le plugin indépendamment en Java 21 et exécuter la CLI MINOS locale dans des processus de fond.
- Exiger d’abord `minos ide handshake --format json`.
- Bloquer les requêtes et afficher les versions attendue et reçue lorsque `protocolVersion` diffère de `"1"`.
- Passer à une nouvelle version de protocole pour toute suppression ou tout renommage d’un champ consommé, tout changement sémantique ou tout changement de commande obligatoire.
- Lancer les processus hors EDT, avec délai maximum et possibilité d’annulation, en transmettant les arguments séparément.

### Don't

- Ne déclarer aucune dépendance `com.minos:*` dans le plugin.
- Ne jamais toucher directement à `MINOS_HOME`, aux snapshots ou au pointeur actif depuis le plugin.
- Ne pas réimplémenter Git dans le plugin.
- Ne pas créer de daemon long-lived avant preuve mesurée du besoin.

## Compatibilité

Le client M18 accepte uniquement `protocolVersion = "1"`. Une autre version bloque les requêtes et affiche une erreur de compatibilité contenant la version attendue et la version reçue.

L'évolution du protocole suit les règles suivantes :

- ajout de champ JSON optionnel : compatible dans la même version ;
- ajout de capability : compatible ;
- suppression/renommage d'un champ consommé ou changement sémantique : nouvelle version ;
- changement de commande obligatoire : nouvelle version ;
- `MinosApi` peut évoluer indépendamment tant que le protocole IDE reste stable.

## Sécurité et lifecycle

Le plugin ne touche jamais directement à `MINOS_HOME`, aux snapshots ou au pointeur actif. `index`/`reindex` passent par la CLI et réutilisent le lifecycle de staging/promotion atomique déjà qualifié.

Les processus sont lancés hors EDT, avec délai maximum et possibilité d'annulation. Les arguments sont transmis séparément à `ProcessBuilder`; aucune donnée du code source n'est interpolée dans une commande shell hors adaptation Windows nécessaire aux launchers `.cmd`/`.bat`.

## Consequences

### Positives

- frontière de compilation nette Java 21 / Java 24 ;
- aucune fuite des classes internes dans IntelliJ ;
- plugin installable/désinstallable sans effet sur MINOS ;
- contrat testable sans lancer un IDE ;
- même vérité métier que CLI/API/MCP.

### Coûts

- lancement de processus locaux pour les requêtes ;
- parsing JSON côté plugin ;
- un futur mode serveur long-lived pourra être ajouté si les mesures montrent que le coût de démarrage devient un goulot.

Ce dernier point est explicitement measurement-gated : M18 ne crée pas un daemon avant preuve du besoin.

## Compliance and Enforcement

- Workflow `.github/workflows/intellij-plugin.yml:79-85` (check exigé « IntelliJ plugin (gate) ») — refuse toute dépendance `implementation("com.minos:` dans `minos-intellij`.
- Gate `scripts/architecture/check-module-boundaries.py` (job `invariants`) — `minos-intellij` est une racine de sources externe (`EXTERNAL_SOURCE_ROOTS`) incluse dans la recherche de cycles de packages (A8).
- Règle Archgate `0027-intellij-external-client-and-versioned-cli-protocol.rules.ts` / `plugin-declares-no-minos-artifact` : refuse toute coordonnée `com.minos:*` (ou `group = "com.minos",`) dans les scripts Gradle, les verrous de dépendances, les catalogues de versions et les métadonnées de vérification de `minos-intellij` ; second exécutant, élargi, du grep `implementation("com.minos:` du workflow `intellij-plugin.yml` (lancée par `archgate check`, pas par la CI).
- Règle Archgate `0027-intellij-external-client-and-versioned-cli-protocol.rules.ts` / `plugin-imports-only-its-own-package` : refuse dans les sources de `minos-intellij` tout import d'un type `com.minos.*` hors de `com.minos.intellij` ; contrôle propre à Archgate, aucun gate Python ne le fait.
- Tests `IdeCommandTest` (`minos-cli`) — handshake JSON stable — et `MinosCliClientTest` (`minos-intellij`, `gradlew test` du même workflow).
- Revue : un champ consommé supprimé ou renommé, ou une commande obligatoire modifiée, s’accompagne d’une nouvelle version du protocole `minos-ide`.

## Mise en œuvre — note du 2026-10-13

La baseline du produit est passée de Java 24 à **Java 25 LTS** ([ADR 0059](archive/0059-migrer-le-socle-vers-java-25-lts.md)). La décision est inchangée : le plugin reste un client externe en Java 21, la frontière de processus sépare désormais Java 21 et Java 25.
