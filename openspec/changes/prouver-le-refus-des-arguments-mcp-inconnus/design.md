# Design

## Context

Sprint 7 de l'audit du 10 octobre 2026 : **AUD-SEC-09**. HEAD analysé : `949e1569` (`develop`). `MCP` = `minos-mcp/src/main/java/com/minos/mcp`.

## État vérifié au HEAD

### AUD-SEC-09 — confirmé hors SDK, **probablement non reproductible de bout en bout**

- `MCP/McpToolSchemas.java:187-192` : `objectSchema` termine tout schéma par `"additionalProperties":false` (`:191`).
- `MCP/MinosMcpTools.java:366-437` : `required`, `optionalString`, `optionalInteger`, `optionalDouble`, `optionalBoolean`, `stringValue` lisent `args.get(key)` ; `arguments()` (`:435-437`) rend la table reçue telle quelle. Aucune comparaison de l'ensemble des clés avec le schéma.
- `MCP/MinosMcpTools.java:383-387` : précédent écrit de défense en profondeur (« Never trusts the JSON Schema `maxLength` advertised to clients »).
- `pom.xml:53` : `mcp.sdk.version` = `2.0.1`. `MCP/MinosMcpServer.java:56-61` : le serveur est construit par `McpServer.sync(transport)…build()` sans réglage de validation.
- Test de bout en bout existant : `minos-app/src/test/java/com/minos/app/mcp/MinosMcpServerIntegrationTest.java` (appels `client.callTool`, `:73-98`) ; aucun cas d'argument inconnu. Dans `minos-mcp/src/test`, aucun test ne cite `limt` ni un argument hors schéma.

**Repris de la contre-vérification, non relu ici** : le SDK 2.0.1 contient `ToolInputValidator` et initialise `validateToolInputs` à vrai (bytecode lu par `javap`).

**Non vérifié** : aucun build ni test lancé ; qu'un validateur de schéma est effectivement **résolu à l'exécution** dans le jar distribué (présence d'une implémentation sur le classpath ombré) ; la forme exacte du refus du SDK (erreur de protocole ou résultat d'outil en erreur) ; le contenu du WARN que le SDK écrirait avec le message du validateur.

### Écarts avec l'action proposée par l'audit

1. **L'audit ignore le validateur du SDK.** Ajouter d'emblée un rejet dans `tool(…)` corrigerait un défaut qui n'est peut-être pas atteignable par un client. La preuve précède le code.
2. **« Ajouter un test de bout en bout » est la première action, pas la dernière.** C'est lui qui dit si le constat est « confirmé » ou « non reproduit ».
3. **Le refus du SDK a son propre format et son propre journal.** L'exigence existante « Un échec d'outil MCP est diagnostiquable par l'opérateur sans exposer son message » interdit qu'un journal contienne la valeur d'un argument : un WARN portant le message du validateur doit y être confronté. L'audit ne le relève pas.
4. **Le nom d'un argument inconnu est une donnée de l'appelant.** Le message proposé (`unknown MCP argument: <clé>`) doit borner la clé et passer par `clientError` (`MinosMcpTools.java:217-233`), qui applique `PublicErrorMessages.looksSensitive`.

## Goals / Non-Goals

**Goals** : savoir, par un test, si un argument inconnu est refusé de bout en bout ; garantir ce refus quel que soit le résultat ; qu'aucune valeur d'argument ne sorte par le journal ou la réponse ; que la garantie ne dépende plus d'un défaut silencieux d'une dépendance.

**Non-Goals** : revalider tout le schéma côté MINOS ; changer les schémas ; modifier le format des erreurs existantes.

## Decisions

### D1. Le test de bout en bout d'abord

`MinosMcpServerIntegrationTest` gagne un cas : appel d'un outil à argument `limit` avec `limt`, par le transport STDIO réel. Il relève trois faits : refus ou non ; forme du refus ; contenu de stderr. Le résultat est consigné ici et dans `SUIVI.md`, et requalifie le constat.

### D2. Défense dans `MinosMcpTools` — **décision en attente du propriétaire**

**Option A (recommandée) : validation du SDK conservée et rejet des clés inconnues dans `MinosMcpTools`.** Chaque outil refuse une clé absente des propriétés de son schéma, par `clientError`, avec la clé bornée. Cohérent avec le précédent `:383-387` ; la garantie ne dépend plus du SDK ni de sa configuration ; un appel direct hors SDK est couvert. Coût : la liste des propriétés doit venir du schéma lui-même, sans seconde liste à maintenir.

**Option B : s'en remettre au SDK**, le test de bout en bout servant de garde lors d'une montée de version. Aucun code de production ; le défaut hors SDK subsiste et le format du refus n'appartient pas à MINOS.

**Option C : désactiver la validation du SDK et valider dans MINOS seul.** À ne retenir que si D1 montre que le journal du SDK contient une valeur d'argument et qu'il ne peut pas être maîtrisé autrement (D3).

Si D1 est **rouge** (argument servi avec la valeur par défaut), l'option B disparaît.

### D3. Journal du refus

Si D1 montre un WARN du SDK contenant une valeur d'argument, la correction est, dans l'ordre de préférence : régler le niveau ou le contenu de ce journal ; sinon l'option C. Le nom de l'argument refusé peut être journalisé, borné ; jamais sa valeur.

### D4. Tests dans le module du code

Un rejet ajouté dans `minos-mcp` est testé dans `minos-mcp` (catalogue entier : chaque outil refuse une clé inconnue), en plus du test STDIO de `minos-app`, pour la couverture du code neuf par module.

## Direction des dépendances

Changement interne à `minos-mcp` (surface) ; le test d'intégration reste dans `minos-app`. Aucune arête nouvelle.

## Qualification des capacités

- Refus d'un argument hors schéma par le serveur MCP : aujourd'hui **non qualifié** (aucune preuve) ; devient qualifié par D1 et, selon D2, garanti par MINOS ou par le SDK.
- Appel direct de `MinosMcpTools` hors SDK : non supporté comme surface publique ; couvert seulement par l'option A ou C.

## Windows et Linux

Aucune différence attendue. Le test STDIO lit stderr du serveur ; les fins de ligne ne doivent pas entrer dans les assertions.

## Risks / Trade-offs

- Option A : double validation (SDK puis MINOS) ; le client reçoit le refus du premier qui s'applique, donc deux formats possibles selon le chemin — à documenter.
- Un client existant qui envoie par erreur un argument superflu aujourd'hui ignoré recevrait une erreur : c'est le contrat publié, mais le cas doit être écrit dans les notes de version si D1 est rouge.
- Dériver les propriétés du schéma sans analyser de JSON à chaque appel : calcul fait une fois à la construction du catalogue.
