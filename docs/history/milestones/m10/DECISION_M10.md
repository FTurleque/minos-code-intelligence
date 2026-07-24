# Décision M10 — Serveur MCP

Statut : **PRÉPARÉE — VALIDATION LOCALE FINALE EN ATTENTE**

## Question de décision

> Un client MCP standard peut-il découvrir et appeler les capacités MINOS M1–M9 via un serveur local fiable, borné et fournisseur-indépendant, sans divergence avec le cœur métier ?

## Réponse préparée

> **OUI, via un serveur MCP STDIO read-only qui délègue à la surface MINOS existante et conserve les mêmes bornes, preuves et limitations.**

Cette réponse ne devient finale qu’après validation locale du head exact M10.

## Éléments de preuve

### 1. Protocole standard

M10 s’appuie sur le SDK Java MCP officiel épinglé en version `2.0.0`.

Le serveur et le test d’intégration utilisent le transport STDIO du SDK, la négociation du protocole, `tools/list` et `tools/call`.

### 2. Frontière d’architecture

Les handlers MCP ne contiennent aucune analyse métier.

Ils réalisent uniquement :

```text
arguments MCP
  -> arguments CLI M9
  -> --format json
  -> MinosLauncher.run(...)
  -> CallToolResult
```

Les résultats restent donc produits par les mêmes services MINOS que la CLI validée en M9.

### 3. Catalogue explicite

Le serveur expose exactement 15 tools read-only couvrant :

- projet / structure ;
- statut d’index ;
- recherche compacte ;
- symboles ;
- usages ;
- implémentations ;
- callers / callees ;
- dépendances / dépendants ;
- tests liés ;
- contexte symbole ;
- contexte module ;
- architecture ;
- impact.

### 4. Bornes et validation

Les JSON Schemas MCP reprennent les bornes des contrats MINOS :

```text
search depth        0..3
items par nœud      0..50
context lines       0..50
max tokens          256..32768
impact depth        1..32
impact results      1..10000
```

Les propriétés inconnues sont rejetées (`additionalProperties=false`).

Le test protocolaire envoie volontairement `impact.depth=99` et exige un `CallToolResult.isError=true` produit par la validation du SDK avant le handler.

### 5. Replay réel

La qualification M10 reprend la fixture réelle TypeScript multi-module et son SCIP versionné.

Le test démarre un serveur enfant réel et vérifie :

```text
tools = 15
architecture modules = 3
impact GreetingPort = 2 impacts
related impacted tests = 1
```

Replay attendu :

```text
M10 MCP stdio: tools=15, project=<uuid>, snapshot=<snapshot>, architecture-modules=3, impact-root=GreetingPort
```

### 6. Packaging

Le build conserve le JAR CLI historique et produit :

```text
minos-code-intelligence-0.1.0-SNAPSHOT-all.jar
```

Le Shade Plugin fusionne les ressources `META-INF/services` afin de conserver les fournisseurs chargés par `ServiceLoader`.

## Limites conservées

M10 ne transforme pas les limites des jalons antérieurs en garanties nouvelles.

En particulier :

- `CALLS` reste dépendant de la qualité fournisseur ;
- les tests liés peuvent rester heuristiques avec confiance explicite ;
- l’architecture reste descriptive/factuelle ou dérivée selon M6 ;
- l’impact reste potentiel et non exhaustif au runtime ;
- le statut d’index n’invente aucune métadonnée absente ;
- aucun tool MCP ne lance une mutation ou une indexation.

## Pourquoi STDIO

STDIO correspond au besoin M10 : serveur local lancé comme sous-processus par un agent ou un IDE.

Il évite d’introduire prématurément :

- écoute réseau ;
- serveur HTTP ;
- authentification réseau ;
- framework web ;
- configuration de déploiement distant.

Une exposition distante pourra être évaluée lorsqu’un besoin produit l’exigera ; elle n’est pas nécessaire pour démontrer la porte MCP locale.

## Verdict

Sous réserve d’une porte locale finale verte sur le head exact :

> **ADOPTER le serveur MCP STDIO M10 comme couche officielle d’exposition agent de MINOS.**

M11 pourra ensuite ajouter une API externe avec ses propres DTO et contraintes de transport, sans déplacer cette responsabilité dans les handlers MCP.
