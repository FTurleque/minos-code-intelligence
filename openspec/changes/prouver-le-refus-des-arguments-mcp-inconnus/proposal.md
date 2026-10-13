# Proposal

## Why

**AUD-SEC-09** (faible, sprint 7 de l'audit du 10 octobre 2026). Chaque schéma d'outil MCP publie `"additionalProperties":false` (`McpToolSchemas.java:191`), mais les extracteurs d'arguments de `MinosMcpTools` lisent des clés nommées (`:366-437`) et ne comparent jamais les clés reçues au schéma. Un argument mal orthographié (`limt` pour `limit`) serait alors ignoré et la requête servie avec la valeur par défaut, sans que le client en soit averti.

La contre-vérification au HEAD nuance le constat : le SDK MCP 2.0.1 (`pom.xml:53`) embarque un validateur d'entrées activé par défaut, et `MinosMcpServer.java:56-61` ne le désactive pas. Le défaut n'est donc **confirmé que pour un appel direct de `MinosMcpTools` hors SDK** ; de bout en bout, il est **probablement non reproductible**. Rien ne le prouve : aucun test du dépôt n'envoie un argument inconnu par le transport STDIO, et la résolution effective d'un validateur à l'exécution n'a pas été observée.

Le contrat publié est donc tenu, au mieux, par un comportement par défaut d'une dépendance, sans test.

## What Changes

- **Preuve d'abord** : un test de bout en bout (STDIO, argument `limt`) établit si le refus a lieu, sous quelle forme, et ce que le journal en garde.
- **Code seulement si le test est rouge**, ou si le propriétaire retient une défense en profondeur dans `MinosMcpTools` (design, D2).
- **Le refus est confronté à l'exigence existante** de `surfaces-publiques` sur un journal sans valeur d'argument.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `surfaces-publiques` : deux exigences ajoutées (un argument absent du schéma publié est refusé de bout en bout ; ce refus ne journalise ni ne renvoie aucune valeur d'argument, et borne le nom rendu).

## Hors périmètre

- Changer un schéma d'outil, ajouter ou renommer un argument.
- La validation des autres mots-clés du schéma (types, bornes) : déjà revérifiée par les extracteurs (`MinosMcpTools.java:383-394`) ; non remise en cause.
- Monter ou remplacer le SDK MCP.
- Les autres constats du sprint 7.

## Impact

- **Modules du reactor** : `minos-mcp` (`MinosMcpTools`, éventuellement `MinosMcpServer`) ; `minos-app` (test d'intégration `MinosMcpServerIntegrationTest`).
- **Surfaces publiques** : MCP seulement. Refuser un argument inconnu **n'est pas une rupture** : le schéma publie déjà `additionalProperties:false`. Si le test montre que le refus existe déjà, aucun comportement ne change. CLI, API Java, IntelliJ, NEXUS : aucun changement.
- **ADR** : aucun nouvel ADR ; s'inscrit dans l'ADR 0017 (MCP STDIO en lecture seule, stdout réservé au protocole).
- **Gates à rejouer** : les scripts qui citent des littéraux de `MinosMcpTools`, `McpToolSchemas` ou `MinosMcpServer` — `scripts/remediation/check-mnd.py`, `scripts/remediation/check-p0-p2.py`, `scripts/remediation/check-post-mne.py`, `scripts/quality/check-runtime-dynamic-consistency.py` (`:114` cite `additionalProperties`), `scripts/quality/check-hosted-control-plane-consistency.py`, `scripts/docs/product-facts.py` ; `python scripts/quality/check-jacoco.py` (scopes `mcp-mapping` et `m19-m20-mcp-catalogue`).
- **Plateformes** : Windows et Linux, sans différence attendue.
