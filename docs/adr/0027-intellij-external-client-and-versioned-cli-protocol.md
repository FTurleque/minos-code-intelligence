# ADR-0027 — IntelliJ externe et protocole CLI versionné

- Statut : Accepted
- Date : 2026-07-27
- Décision : M18

## Contexte

MINOS est qualifié avec Java 24. Les versions IntelliJ ciblées par M18 exécutent les plugins sur une plateforme Java 21. Faire dépendre directement le plugin des modules Maven MINOS couplerait les cycles de JVM, exposerait des classes internes et transformerait l'IDE en nouvelle composition du moteur.

M18 exige au contraire un plugin optionnel, sans duplication du métier et avec détection propre des incompatibilités de protocole.

## Décision

Le plugin `minos-intellij` est un **client externe** :

1. il est compilé indépendamment en Java 21 ;
2. il ne déclare aucune dépendance `com.minos:*` ;
3. il exécute la CLI MINOS locale dans des processus de fond ;
4. il exige d'abord `minos ide handshake --format json` ;
5. le protocole porte l'identifiant `minos-ide` et une version entière indépendante de `MinosApi.CONTRACT_VERSION` ;
6. les opérations métier réutilisent les commandes JSON stables existantes (`project`, `index-status`, `find-symbol`, `find-usages`, relations, `architecture`, `impact`, `index`, `doctor`) ;
7. M18 ajoute `git-activity` comme adapter CLI du `GitIntelligenceService` existant, sans réimplémentation Git dans le plugin.

Le protocole IDE v1 décrit donc à la fois le handshake et le sous-ensemble de commandes/schémas JSON autorisés pour le client IntelliJ.

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

## Conséquences

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
