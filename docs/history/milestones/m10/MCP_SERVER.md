# M10 — Serveur MCP MINOS

Statut : **IMPLÉMENTÉ — VALIDATION LOCALE FINALE EN ATTENTE**

Base : M9 livré au merge `22afe31339dc3a75dc51c491a725330c6d433ecc`.

## 1. Objectif

M10 expose les capacités MINOS déjà validées à un client compatible Model Context Protocol.

Le serveur MCP reste volontairement une **couche d’exposition** :

```text
Client / Agent MCP
        │
        ▼
MinosMcpServer — STDIO
        │
        ▼
MinosMcpTools
        │
        ▼
Surface JSON M9
        │
        ▼
Services MINOS M1–M8
```

Aucune règle d’analyse de symboles, relations, architecture, tests liés ou impact n’est réimplémentée dans les handlers MCP.

## 2. SDK et transport

M10 utilise :

```text
MCP Java SDK officiel    2.0.0
Transport                STDIO
API serveur              synchrone
Framework web            aucun
```

Le transport STDIO est retenu parce que MINOS est local-first et doit pouvoir être lancé comme sous-processus par un agent ou un IDE sans ouvrir de port réseau.

Le serveur annonce uniquement la capacité `tools`.

## 3. Distribution

Le build Maven produit le JAR historique ainsi qu’un uber-JAR :

```text
target/minos-code-intelligence-0.1.0-SNAPSHOT.jar
target/minos-code-intelligence-0.1.0-SNAPSHOT-all.jar
```

Le JAR principal conserve :

```text
com.minos.cli.MinosLauncher
```

comme `Main-Class` afin de ne pas casser M9.

Le serveur MCP se lance explicitement avec :

```powershell
java -cp .\target\minos-code-intelligence-0.1.0-SNAPSHOT-all.jar com.minos.mcp.MinosMcpServer
```

Le Shade Plugin fusionne les ressources `META-INF/services` nécessaires aux implémentations chargées via `ServiceLoader`, notamment le mapper JSON du SDK MCP.

## 4. Home MINOS

Le serveur MCP applique la même priorité que la CLI :

```text
-Dminos.home=<path>
        ↓
MINOS_HOME=<path>
        ↓
%USERPROFILE%\.minos
```

Exemple PowerShell :

```powershell
$env:MINOS_HOME = 'N:\minos-data'
java -cp .\target\minos-code-intelligence-0.1.0-SNAPSHOT-all.jar com.minos.mcp.MinosMcpServer
```

Aucun message applicatif ne doit être écrit sur stdout : stdout appartient au protocole MCP. Les erreurs fatales de bootstrap sont écrites sur stderr.

## 5. Catalogue des tools

M10 expose exactement **15 tools read-only**.

| Tool | Capacité MINOS exposée |
|---|---|
| `minos_project_structure` | inspection projet, langages/builds, état et snapshot |
| `minos_index_status` | état d’index et métadonnées factuelles connues |
| `minos_search_code` | contexte compact M4 |
| `minos_find_symbols` | recherche de symboles M2 |
| `minos_find_usages` | usages M2/M3 |
| `minos_find_implementations` | relations `IMPLEMENTS` |
| `minos_find_callers` | relations `CALLS` entrantes lorsqu’elles existent |
| `minos_find_callees` | relations `CALLS` sortantes lorsqu’elles existent |
| `minos_dependencies` | dépendances `DEPENDS_ON` sortantes |
| `minos_dependents` | dépendances `DEPENDS_ON` entrantes |
| `minos_related_tests` | tests liés M5 avec preuves/confiance |
| `minos_symbol_context` | contexte compact à une racine |
| `minos_module_context` | contexte architectural M6 d’un module |
| `minos_architecture` | vue d’architecture composée M6 |
| `minos_impact` | analyse d’impact potentielle M8 |

Aucun tool d’écriture ou d’indexation n’est exposé dans M10.

## 6. Contrat de résultat

Chaque handler traduit les arguments MCP vers la CLI M9 en imposant :

```text
--format json
```

Le JSON renvoyé par MCP est donc celui de la surface M9 déjà qualifiée.

Un succès produit un `CallToolResult` contenant un `TextContent` JSON.

Une erreur d’usage ou d’exécution MINOS produit :

```text
CallToolResult.isError = true
```

avec le message stderr normalisé de la CLI.

Les erreurs de schema sont rejetées par le SDK MCP avant invocation du handler.

## 7. Schemas bornés

Les schemas ne donnent pas au client une surface plus large que les contrats métier existants.

### Recherche / contexte

```text
limit                 1..20 pour search
limit                 1..1000 pour symboles/relations
search depth          0..3
usages                0..50
relationships         0..50
contextLines          0..50
maxTokens             256..32768
```

### Impact

```text
depth                 1..32
limit                 1..10000
```

Les objets d’entrée déclarent :

```json
"additionalProperties": false
```

afin qu’une clé inconnue ne soit pas silencieusement acceptée.

## 8. Exemple de configuration client

La forme exacte du fichier de configuration dépend du client MCP. Le processus à lancer est :

```text
command = java
args    = -cp <minos-all.jar> com.minos.mcp.MinosMcpServer
env     = MINOS_HOME=<home MINOS>
```

Exemple conceptuel Windows :

```json
{
  "mcpServers": {
    "minos": {
      "command": "C:\\Program Files\\Java\\jdk-24\\bin\\java.exe",
      "args": [
        "-cp",
        "N:\\workspace-dev\\minos-code-intelligence\\target\\minos-code-intelligence-0.1.0-SNAPSHOT-all.jar",
        "com.minos.mcp.MinosMcpServer"
      ],
      "env": {
        "MINOS_HOME": "N:\\minos-data"
      }
    }
  }
}
```

Cette structure est illustrative ; les clés d’enregistrement d’un serveur MCP dépendent du client utilisé.

## 9. Qualification

### `MinosMcpToolsTest`

Vérifie :

- exactement 15 tools ;
- noms uniques et préfixés `minos_` ;
- traduction déterministe MCP → CLI JSON ;
- conversion d’une erreur CLI en erreur tool récupérable.

### `MinosMcpServerIntegrationTest`

Sur `fixtures/typescript/typescript-modules` :

1. crée un home MINOS temporaire ;
2. enregistre le projet via M9 ;
3. importe le SCIP réel via M9 ;
4. lance `MinosMcpServer` comme sous-processus Java ;
5. initialise un client du SDK MCP officiel via STDIO ;
6. liste les 15 tools ;
7. appelle `minos_architecture` ;
8. appelle `minos_impact` sur `GreetingPort` ;
9. vérifie le rejet protocolaire d’un `depth=99` ;
10. ferme proprement le client et le sous-processus.

Replay attendu :

```text
M10 MCP stdio: tools=15, project=<uuid>, snapshot=<snapshot>, architecture-modules=3, impact-root=GreetingPort
```

## 10. Frontières explicites

M10 ne revendique pas :

- de serveur HTTP de production ;
- d’authentification réseau ;
- de transport distant ;
- de mutation MCP ;
- d’indexation automatique via MCP ;
- de logique métier spécifique au protocole ;
- de garantie supérieure aux données et capacités déjà exposées par MINOS.

Ces éléments ne sont pas nécessaires pour la porte M10 locale et ne doivent pas élargir artificiellement le jalon.

## 11. Porte locale finale

```powershell
.\mvnw.cmd clean verify
```

Volumes attendus si le head reste inchangé :

```text
152 sources main
77 sources test
210 tests
```

La PR M10 reste Draft jusqu’à validation locale du head exact.
