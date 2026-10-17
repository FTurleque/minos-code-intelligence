# ADR-0002 — Utiliser SCIP comme protocole d'interopérabilité sémantique privilégié

- Statut : **Acceptée**
- Date de décision : **22 juillet 2026**
- Validation opérationnelle des fournisseurs : **M0**

## Contexte

MINOS a besoin d'informations précises au niveau des symboles : définitions, références, implémentations et autres relations sémantiques.

Réimplémenter pour chaque langage un frontend complet, un résolveur de symboles et toute la logique de navigation sémantique consommerait une part très importante du projet avant même de produire la valeur propre à MINOS.

SCIP est un protocole d'indexation de Code Intelligence agnostique du langage basé sur Protobuf. Son écosystème fournit actuellement des indexeurs pour plusieurs familles de langages, notamment Java/Scala/Kotlin, TypeScript/JavaScript, Rust, C/C++, Python, Ruby, .NET, Dart et PHP.

Le protocole et les indexeurs restent néanmoins deux sujets distincts : accepter SCIP comme format privilégié ne signifie pas considérer tous les indexeurs SCIP comme équivalents ou suffisamment précis.

## Décision

SCIP est le **protocole d'interopérabilité sémantique privilégié de MINOS** lorsqu'un indexeur suffisamment fiable, maintenu et compatible avec les besoins du projet existe.

SCIP n'est **jamais obligatoire**.

```text
Indexeur sémantique spécifique au langage
              │
              ▼
            SCIP
              │
              ▼
    Adaptateur SCIP MINOS
              │
              ▼
      Modèle normalisé MINOS
```

Les services du cœur MINOS ne doivent pas exposer directement les types Protobuf SCIP.

Le `IndexerRegistry` doit permettre à des fournisseurs non-SCIP de fournir les mêmes capacités métier lorsque ceux-ci sont plus adaptés.

## Portée de la décision

Cette ADR valide :

- SCIP comme **format privilégié d'entrée sémantique** ;
- le principe d'un adaptateur SCIP possédé par MINOS ;
- l'absence de dépendance du domaine MINOS aux types SCIP ;
- la possibilité de combiner SCIP avec d'autres fournisseurs ;
- la qualification des fournisseurs par capacités et qualité réelle.

Cette ADR ne valide pas encore :

- `scip-java` comme fournisseur de production ;
- un autre indexeur SCIP particulier ;
- la précision du graphe d'appels ;
- le support d'un langage donné ;
- le stockage en aval ;
- Glean.

Ces points doivent être vérifiés pendant M0.

## Pourquoi SCIP est retenu

- évite de reconstruire un parser et un résolveur sémantique complets pour chaque langage ;
- fournit un format d'échange commun entre plusieurs écosystèmes ;
- représente les identités et occurrences de symboles nécessaires à la navigation sémantique ;
- sépare l'indexation du stockage et des requêtes MINOS ;
- peut alimenter plusieurs backends en aval ;
- correspond directement à l'exigence multi-langages de MINOS ;
- licence Apache-2.0 du protocole ;
- outils CLI utiles pour inspecter, tester, mesurer et valider les index.

## Limites assumées

SCIP n'est pas un Code Property Graph complet et ne garantit pas, à lui seul :

- un graphe d'appels complet ;
- le contrôle de flux ;
- le flux de données ;
- l'analyse de sécurité ;
- une couverture sémantique uniforme entre langages ;
- la résolution parfaite du comportement dynamique.

MINOS doit donc conserver son modèle `IndexerCapabilities` et pouvoir compléter SCIP avec d'autres moteurs.

## Qualification d'un fournisseur SCIP

Un indexeur SCIP n'est activable comme fournisseur MINOS qu'après qualification de capacités telles que :

```text
DEFINITIONS
REFERENCES
IMPLEMENTATIONS
INHERITANCE
CALL_RELATIONSHIPS
CROSS_FILE
CROSS_MODULE
EXTERNAL_SYMBOLS
INCREMENTAL_INDEXING
```

Chaque capacité doit être qualifiée au minimum comme :

```text
FULL
PARTIAL
EXPERIMENTAL
UNSUPPORTED
UNKNOWN
```

Le profil doit également conserver les mesures de précision, de performance et les limitations connues.

## Validation M0 requise

Sur des dépôts réels et des fixtures contrôlées, M0 doit mesurer :

- taux de réussite de l'indexation ;
- précision des symboles ;
- précision des références ;
- résolution des implémentations et héritages ;
- comportement multi-module ;
- symboles externes et dépendances manquantes ;
- taille d'index ;
- durée d'indexation ;
- fonctionnement hors ligne ;
- comportement en cas d'échec partiel ;
- capacités réellement produites par chaque indexeur.

Si un indexeur SCIP n'atteint pas le niveau attendu, MINOS doit pouvoir sélectionner un autre fournisseur sans modifier son domaine.

## Alternatives conservées

Selon les langages ou analyses :

- indexeurs Glean natifs ;
- LSIF ;
- Language Server Protocol ou outils dérivés ;
- API de compilateur ;
- AST ;
- Tree-sitter ;
- Kythe ;
- Code Property Graph / Joern ;
- analyseur spécialisé futur.

## Versions techniques vérifiées pour M0

Au **22 juillet 2026**, les versions ne doivent pas être confondues :

```text
SCIP CLI                         v0.7.1
Bindings Java du protocole       0.9.0
scip-java                        v0.13.1
```

- la CLI SCIP `v0.7.1` est la release publiée de l'outil `scip` ;
- le protocole fournit des bindings Java publiés sous `org.scip-code:scip-java-bindings:0.9.0` ;
- `scip-java v0.13.1` est la release du fournisseur JVM utilisée comme référence de qualification M0.

MINOS doit versionner séparément :

1. le protocole / ses bindings ;
2. la CLI d'inspection ;
3. chaque indexeur producteur.

Cette séparation évite de déduire à tort la compatibilité d'un indexeur à partir du numéro de version de la CLI SCIP.

## Sources techniques vérifiées

Sources consultées le 22 juillet 2026 :

- SCIP : https://github.com/scip-code/scip
- CLI SCIP : https://github.com/scip-code/scip/blob/main/docs/CLI.md
- bindings Java SCIP : https://github.com/scip-code/scip/tree/main/bindings/java
- scip-java : https://github.com/scip-code/scip-java
- release scip-java v0.13.1 : https://github.com/scip-code/scip-java/releases/tag/v0.13.1
