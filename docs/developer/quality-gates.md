# Quality gates MINOS

MINOS mesure la couverture pour empêcher la régression des responsabilités critiques, sans transformer un pourcentage global en objectif produit.

Depuis M21, les gates historiques M15 sont conservés et complétés par des scopes ciblés pour les responsabilités M19/M20.

## JaCoCo

Le reactor exécute `jacoco:prepare-agent` et produit un rapport par module pendant `verify`. `minos-app` produit en plus le rapport agrégé :

```text
target/site/jacoco-aggregate/index.html
target/site/jacoco-aggregate/jacoco.xml
```

Le gate reproductible est :

```text
python scripts/quality/check-jacoco.py
```

Le résultat machine-readable M21 est écrit par défaut dans :

```text
target/m21-quality/jacoco-gate.json
```

## Baseline historique M15

| Scope | Ligne | Branche |
|---|---:|---:|
| domaine / invariants | 35 % | 20 % |
| persistance + cache + indexes | 50 % | 35 % |
| résolution projet | 70 % | 50 % |
| API publique | 30 % | 20 % |
| mapping MCP | 30 % | 20 % |

Ces seuils restent inchangés afin de conserver la non-régression historique.

## Extensions M21 — responsabilités M19/M20

| Scope | Responsabilités principales | Ligne | Branche |
|---|---|---:|---:|
| `program-graph-analysis` | composition Program Graph, provider relations, évaluation, traversée interprocédurale | 50 % | 30 % |
| `advanced-impact-security` | Impact v2 et recherche de chemins sécurité | 45 % | 25 % |
| `semantic-vector-store` | persistance locale reconstruisible des vecteurs | 45 % | 20 % |
| `semantic-hybrid-retrieval` | index sémantique, semantic search, hybrid search/context, évaluation | 50 % | 30 % |
| `advanced-public-api` | API Java M19/M20 et implémentations locales | 45 % | 25 % |
| `m19-m20-mcp-catalogue` | mapping du catalogue MCP incluant les surfaces M19/M20 | 50 % | 30 % |

Les seuils M21 initiaux sont volontairement ciblés et conservateurs. La première qualification locale mesure les valeurs réelles sur le même rapport agrégé ; les seuils peuvent ensuite être **relevés** lorsque des tests utiles le justifient. Une baisse exige une justification documentée dans la PR.

Le scope `semantic-vector-store` conserve notamment une branche initiale plus basse car le format binaire contient de nombreuses branches défensives de corruption/troncature qui ne doivent pas être couvertes artificiellement par des tests sans valeur. Les tests de robustesse utiles doivent faire monter ce seuil progressivement.

## Tests fonctionnels séparés

Une ligne couverte ne prouve pas un contrat fonctionnel. Les gates JaCoCo restent complémentaires des preuves suivantes :

- replays CLI/API/MCP ;
- fixtures providers et Program Graph ;
- précision/rappel et vérités terrain contrôlées ;
- promotion de snapshot et états STALE/recovery ;
- budgets de contexte ;
- packaging et smoke tests ;
- campagnes de performance M16/M21.

## Sonar

Le Quality Gate Sonar ne doit pas être interprété comme preuve suffisante lorsqu'il annonce une couverture `new code` non représentative des rapports JaCoCo réels. M21 doit aligner la publication des rapports et les critères Sonar avec les gates locaux avant de considérer Sonar comme porte autoritative.

Aucune action CI ou modification de workflow n'est réalisée dans ce travail avant août 2026 ; l'alignement Sonar distant reste donc différé avec M21-S2, tandis que les gates locaux M21 sont qualifiés immédiatement.

## Exclusions et limites

Aucune classe critique explicitement ciblée n'est exclue du gate. Les classes d'assemblage, DTO simples, renderers et adapters non listés restent visibles dans le rapport agrégé mais ne portent pas nécessairement de seuil individuel.

La règle durable reste : **ajouter des tests qui prouvent un comportement avant d'augmenter la couverture pour elle-même**.