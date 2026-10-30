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

### Résultat de D1 (relevé le 2026-10-17, `MinosMcpServerIntegrationTest`, STDIO réel, SDK 2.0.1)

Au HEAD `396e301f`, avant tout code : le constat est **non reproduit pour l'exécution** et **confirmé pour la forme du refus**.

- **Refus : oui.** Le validateur du SDK (`io.modelcontextprotocol.util.ToolInputValidator`) est résolu et actif dans le classpath de test (même résolution que le jar distribué : aucune configuration ne le désactive) ; `limt` ne s'exécute pas.
- **Forme : un résultat d'outil en erreur** (`isError=true`), pas une erreur de protocole. Le message est celui du validateur, **dans la langue de la JVM** (en français sur ce poste), et il **reprend le nom de la propriété inconnue sans borne ni détecteur** : une clé de 10 000 caractères donne une réponse de 10 205 caractères ; une clé à forme de chemin absolu (`C:/Users/…`) est renvoyée telle quelle.
- **Journal : le même message est écrit en WARNING sur stderr** (`ToolInputValidator.validate`), donc le nom non borné y figure aussi. **La valeur d'un argument n'est reprise ni dans la réponse ni dans le journal** (cas `unknownOption` à valeur de chemin).
- **Test rouge** : `unknownArgumentIsRefusedOverStdioWithoutEchoingCallerValues` échoue au HEAD sur « réponse bornée » (longueur 10 205) ; le refus lui-même est déjà vrai.

**Non mesuré** : le jar ombré distribué n'a pas été lancé ; la résolution du validateur est établie sur le classpath de test de `minos-app`. Sans objet pour la suite : la validation du SDK est désormais désactivée explicitement, le refus ne dépend plus de sa résolution à l'exécution.

Requalification : AUD-SEC-09 passe de « confirmé hors SDK, probablement non reproductible » à **non reproduit en exécution (SDK), confirmé en exposition du nom d'argument** (exigence « Le refus d'un argument inconnu n'expose aucune valeur d'argument » : le nom doit être borné et soumis au détecteur, ce que le SDK ne fait pas).

### D2. Défense dans `MinosMcpTools` — **décision du propriétaire : option A** (2026-10-17)

**Option A (recommandée) : validation du SDK conservée et rejet des clés inconnues dans `MinosMcpTools`.** Chaque outil refuse une clé absente des propriétés de son schéma, par `clientError`, avec la clé bornée. Cohérent avec le précédent `:383-387` ; la garantie ne dépend plus du SDK ni de sa configuration ; un appel direct hors SDK est couvert. Coût : la liste des propriétés doit venir du schéma lui-même, sans seconde liste à maintenir.

**Option B : s'en remettre au SDK**, le test de bout en bout servant de garde lors d'une montée de version. Aucun code de production ; le défaut hors SDK subsiste et le format du refus n'appartient pas à MINOS.

**Option C : désactiver la validation du SDK et valider dans MINOS seul.** À ne retenir que si D1 montre que le journal du SDK contient une valeur d'argument et qu'il ne peut pas être maîtrisé autrement (D3).

**Mise en œuvre retenue** : option A, complétée par la désactivation de la validation du SDK (`validateToolInputs(false)` dans `MinosMcpServer`), seule façon de borner le nom rendu et journalisé (D3 : le message du SDK n'est pas maîtrisable ; réglage du niveau du journal écarté car le client reçoit aussi le message). `MinosMcpTools` refuse, avant l'opération, toute clé absente des propriétés du schéma de l'outil (`Tool.inputSchema().get("properties")`, calculé une fois à la construction du catalogue), par `IllegalArgumentException` rendue par `clientError` : nom borné à 64 caractères, premier nom inconnu en ordre naturel, détecteur `PublicErrorMessages.looksSensitive` appliqué. Les autres mots-clés du schéma (types, bornes, énumération, UUID) restent revérifiés par les extracteurs et le backend (`MinosMcpTools.java` extracteurs, `parseUuid`) : aucune règle du schéma n'est perdue par la désactivation (`required` : `required(...)`).

Si D1 est **rouge** (argument servi avec la valeur par défaut), l'option B disparaît.

### D3. Journal du refus — déclenché (voir résultat de D1)

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
