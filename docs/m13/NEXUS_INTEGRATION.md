# M13 — Intégration NEXUS

Statut : **implémenté — validations finales inter-dépôts en attente**

Suivi MINOS : issue #37 / PR #38.

Compagnon NEXUS : `FTurleque/nexus-context-engine` issue #11 / PR #12.

## Objectif

Permettre à NEXUS de consommer la connaissance de code normalisée de MINOS pour enrichir sa recherche et sa construction de contexte, sans déplacer dans MINOS la responsabilité du ranking, de la sélection ou du budget de tokens.

## Frontière d’architecture

```text
MINOS — Java 24
  faits / symboles / relations / provenance / preuves
                    |
                    | contrat JSON local v1
                    v
NEXUS — Java 21
  import structurel -> recherche -> ranking -> sélection -> budget
```

Une dépendance Java directe est volontairement exclue : NEXUS maintient Java 21 comme niveau de compilation alors que MINOS impose Java 24. Le contrat inter-processus évite également un couplage binaire des modèles métier.

## Contrat MINOS

Version :

```text
contractVersion = 1
producer        = MINOS
```

Commande :

```powershell
java -Dminos.home=<home> -jar minos-code-intelligence-0.1.0-SNAPSHOT-all.jar `
  nexus-export --root <project-root>
```

La commande écrit **uniquement le JSON du contrat sur stdout**. Les erreurs utilisent stderr et les codes de sortie stables de la CLI MINOS.

### Projet

Le document exporté identifie :

- l’UUID projet MINOS ;
- le nom ;
- la racine canonique ;
- le snapshot actif.

L’export échoue si la racine n’est pas enregistrée dans MINOS ou si aucun snapshot actif n’existe.

### Symboles

MINOS exporte seulement les symboles :

- locaux ;
- dotés d’une localisation ;
- dont le fichier peut être rattaché de manière sûre à un fichier réel sous la racine projet.

Chaque symbole conserve notamment : identité MINOS, `symbolKey`, chemin relatif, module, kind, nom, qualified-name, signature, langue, lignes, statut de résolution, qualité d’identité, flag generated et origine.

### Résolution des `fileId`

Le modèle MINOS n’impose pas qu’un `fileId` soit un chemin. L’adaptateur SCIP produit notamment :

```text
file:<sha256(projectId + US + relativePath)>
```

M13 reconstruit donc un index local `fileId -> relativePath` en recalculant cette identité sur les fichiers réels du projet. Les fileId directement exprimés comme chemins restent supportés s’ils désignent un fichier réel sous la racine.

Aucun identifiant non résolu n’est transformé en faux chemin.

### Relations

Le contrat transporte les relations symbol → symbol locales et résolues avec :

- kind ;
- identités et qualified-names source/cible ;
- résolution ;
- nature factuelle/dérivée/heuristique ;
- confiance ;
- origine ;
- preuves structurées simplifiées.

Le contrat est volontairement plus riche que le modèle NEXUS actuel. Un consommateur peut ignorer un champ qu’il ne sait pas représenter, mais MINOS ne détruit pas cette information à la frontière.

## Limitations explicites

Selon le snapshot, l’export peut notamment signaler :

```text
SYMBOLS_TRUNCATED
RELATIONS_TRUNCATED
FILE_PATH_DISCOVERY_TRUNCATED
UNRESOLVED_FILE_IDS
EXTERNAL_SYMBOLS_OMITTED
SYMBOL_WITHOUT_LOCAL_LOCATION_OMITTED
UNRESOLVED_SYMBOL_FILE_ID_OMITTED
NON_SYMBOL_RELATIONS_OMITTED
NON_LOCAL_RELATIONS_OMITTED
UNRESOLVED_RELATION_FILE_ID_OMITTED
```

Les limitations décrivent une perte ou une borne de projection ; elles ne sont jamais transformées en garantie d’exhaustivité.

## Consommation NEXUS

La PR NEXUS compagnon ajoute `MinosCodeIndexImporter` derrière le contrat existant `CodeIndexImporter`.

Configuration :

```text
NEXUS_MINOS_JAR=<MINOS shaded jar>
NEXUS_MINOS_JAVA=<java 24 executable>
NEXUS_MINOS_HOME=<MINOS home>                  optionnel
NEXUS_MINOS_TIMEOUT_SECONDS=<1..300>           optionnel, défaut 20
```

Sans `NEXUS_MINOS_JAR`, l’intégration est désactivée. NEXUS continue à fonctionner avec ses analyseurs et imports existants.

Si un JAR MINOS est configuré, `NEXUS_MINOS_JAVA` est obligatoire : NEXUS ne suppose pas que son propre runtime Java 21 puisse exécuter MINOS.

### Mapping conservateur

NEXUS n’invente pas de correspondance pour les types qu’il ne sait pas représenter.

Symboles MINOS pris en charge :

```text
CLASS                         -> CLASS
INTERFACE / TRAIT             -> INTERFACE
RECORD                        -> RECORD
ENUM                          -> ENUM
ANNOTATION                    -> ANNOTATION
METHOD / FUNCTION             -> METHOD
CONSTRUCTOR                   -> CONSTRUCTOR
STRUCT / TYPE_ALIAS           -> TYPE
```

Les autres kinds sont ignorés.

Relations prises en charge :

```text
IMPORTS           -> IMPORTS
EXTENDS           -> EXTENDS
IMPLEMENTS        -> IMPLEMENTS
CALLS             -> CALLS
REFERENCES        -> REFERENCES
TYPE_DEFINITION   -> TYPE_DEFINITION
DEFINITION        -> DEFINITION_OF
```

Les relations non représentables (`DEPENDS_ON`, `RELATED_TEST`, etc.) restent dans le contrat MINOS mais ne sont pas converties arbitrairement côté NEXUS.

Seules les relations `RESOLVED` sont injectées dans le modèle NEXUS.

Pour une relation factuelle sans confiance explicite, NEXUS utilise `1.0`. Une relation dérivée/heuristique dépourvue de confiance est rejetée.

## Ordre des providers

NEXUS applique :

```text
1. MINOS importer
2. SCIP importer direct NEXUS
```

La persistance NEXUS déduplique déjà les symboles externes par fichier/kind/nom/ligne et les relations par kind/source/target. Lorsque MINOS fournit le même fait, sa provenance `minos` est donc conservée et SCIP ne complète que les faits absents.

Quand MINOS est désactivé, un importer vide reste dans le pipeline afin que les anciennes données `source_provider=minos` soient purgées lors d’une nouvelle indexation au lieu de devenir silencieusement périmées.

## Ce que M13 ne fait pas

M13 n’ajoute dans MINOS :

- aucun ranking de contexte NEXUS ;
- aucun budget de tokens ;
- aucun `ContextBundle` ;
- aucun type `com.nexus` ;
- aucune dépendance Maven vers NEXUS ;
- aucun accès réseau ;
- aucune exécution de modèle IA.

M13 ne modifie dans NEXUS ni les poids du ranking, ni `SearchService`, ni `DefaultContextBuilder`.

## Qualification

### MINOS

- test de version et frontière du contrat ;
- replay réel TypeScript ;
- vérification `GreetingPort` ;
- résolution réelle des `fileId` SCIP ;
- sortie CLI JSON déterministe ;
- échec explicite pour projet non enregistré.

Replay attendu :

```text
M13 MINOS export: contract=1, project=<uuid>, snapshot=<snapshot>, symbols=<n>, relations=<n>
```

### NEXUS

- intégration désactivée par défaut ;
- Java 24 explicitement requis lorsqu’un JAR est configuré ;
- validation du contrat et de la racine projet ;
- mapping conservateur ;
- test de processus local par JAR synthétique ;
- harness opt-in avec le vrai JAR MINOS.

Replay inter-dépôt attendu :

```text
M13 MINOS->NEXUS: symbols=<n>, relations=<n>, nexus-symbols=<n>, search=<n>
```

Le harness réel vérifie que `GreetingPort` est présent dans l’index NEXUS avec `sourceProvider=minos` et qu’une recherche NEXUS le retourne.

## Porte finale

M13 n’est validé que si :

1. le head exact MINOS passe `./mvnw clean verify` sous Java 24 ;
2. le head exact NEXUS passe sa validation cœur sous Java 21 ;
3. le harness réel inter-dépôt passe avec le JAR MINOS issu du head qualifié.

Aucune fusion M13 ne doit modifier un head déjà qualifié sans rejouer la porte correspondante.
