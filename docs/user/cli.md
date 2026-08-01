# Référence CLI MINOS

Le launcher stable est `com.minos.cli.MinosLauncher`.

Installation Windows :

```powershell
minos.cmd <commande>
```

Checkout source sur la ligne courante :

```powershell
java -jar .\target\minos-code-intelligence-1.0.1-SNAPSHOT-all.jar <commande>
```

`--help` reste la source de vérité exécutable pour les options exactes d'une commande.

## Formats

Les commandes structurées utilisent principalement :

```text
--format text
--format json
```

L'architecture ajoute :

```text
--format mermaid
--format dot
```

## Diagnostic et providers

```powershell
minos.cmd --version
minos.cmd doctor
minos.cmd providers --format json
minos.cmd tools list --format json
minos.cmd tools verify --format json
minos.cmd tools install scip-java
```

`doctor` distingue l'état du runtime MINOS embarqué et les toolchains/providers nécessaires aux projets analysés.

## Projets

```powershell
minos.cmd project add C:\workspace\my-project --name my-project
minos.cmd project list --format json
minos.cmd project inspect my-project --format json
minos.cmd index-status my-project --format json
```

## Indexation

```powershell
minos.cmd index my-project --dry-run --format json
minos.cmd index my-project --format json
minos.cmd index my-project --force-full --format json
```

Le lifecycle normal reste :

```text
discovery
→ provider negotiation
→ runtime check
→ plan FULL / INCREMENTAL / NONE
→ exécution provider
→ normalisation
→ staging
→ promotion atomique du snapshot
```

Un échec d'actualisation ne doit pas remplacer silencieusement le dernier snapshot actif valide.

## Recherche et symboles

```powershell
minos.cmd search my-project GreetingPort --format json
minos.cmd symbol my-project <symbol-id> --format json
minos.cmd usages my-project <symbol-id> --format json
minos.cmd implementations my-project <symbol-id> --format json
```

Les résultats structurés distinguent les faits, dérivations et heuristiques selon leur origine.

## Architecture

```powershell
minos.cmd architecture my-project --format json
minos.cmd architecture my-project --format mermaid
minos.cmd architecture my-project --format dot
```

Les rendus reflètent les arêtes présentes dans le snapshot ; MINOS ne complète pas artificiellement le graphe.

## Impact et tests liés

```powershell
minos.cmd impact my-project <symbol-id> --format json
minos.cmd related-tests my-project <symbol-id> --format json
```

L'impact est borné et explicable. Une relation potentielle n'est pas promue en preuve runtime.

## ProgramGraph avancé

Les commandes avancées M19/M22 ne produisent des résultats que si le provider actif déclare et prouve les capabilities nécessaires.

Le modèle couvre notamment :

- call graph ;
- CFG ;
- def-use/data-flow local ;
- propagation interprocédurale bornée ;
- primitives de sécurité/taint ;
- provenance, confiance et limitations.

Une capability absente n'est pas inventée.

## Recherche sémantique / hybride

La couche sémantique reste optionnelle. Les résultats vectoriels restent `HEURISTIC` et ne créent jamais une relation structurée.

Le profil learned local peut être activé explicitement avec les variables documentées dans `docs/developer/semantic-retrieval-2.md`.

## Remote / Distributed Indexing

Les commandes remote exigent une révision immuable/commit exact selon le contrat M25.

Le worker natif ne revendique pas une sandbox OS réelle. `DENY` reste fail-closed sans backend OS qualifié. Voir [Remote & Distributed Indexing](remote-indexing.md) et l'issue #98.

## Runtime Intelligence

```powershell
minos.cmd runtime import my-project --file .\runtime.tsv --format json
minos.cmd runtime sessions my-project --format json
minos.cmd runtime report my-project --session <session> --format json
minos.cmd runtime symbol my-project --symbol <symbol-id> --format json
```

Les observations sont `OBSERVED_PARTIAL`; leur absence ne prouve pas la non-exécution.

## Team / Hosted

Le mode Team/Hosted est opt-in et embarqué/local-first. Les secrets passent par l'environnement et ne doivent pas être placés dans des arguments ou documents de commande persistants.

Voir [Team / Hosted Mode](team-hosted-mode.md).

## MCP

```powershell
minos.cmd mcp
```

Pour Windows 1.0.1, le runtime packagé est qualifié par un vrai handshake MCP. Voir [Utiliser MINOS via MCP](mcp.md).

## Version source et release

Version de développement :

```text
1.0.1-SNAPSHOT
```

Un build de release utilise :

```text
-Drevision=<version>
```

La génération locale du candidat Windows 1.0.1 est documentée dans [Installation depuis les sources](installation.md). Elle ne publie ni tag ni GitHub Release.

## Catalogue exact

Le catalogue MCP et les facts calculables courants sont dérivés dans :

[`../generated/product-facts.md`](../generated/product-facts.md)

Pour une option précise qui aurait évolué, utiliser `minos.cmd <commande> --help` plutôt qu'une copie historique d'un ancien jalon.
