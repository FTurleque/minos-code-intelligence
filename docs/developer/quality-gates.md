# Quality gates M15

M15 mesure la couverture pour empêcher la régression des responsabilités critiques, sans transformer un pourcentage global en objectif produit.

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

Seuils M15 :

| Scope | Ligne | Branche |
|---|---:|---:|
| domaine / invariants | 35 % | 20 % |
| persistance + cache + indexes | 50 % | 35 % |
| résolution projet | 70 % | 50 % |
| API publique | 30 % | 20 % |
| mapping MCP | 30 % | 20 % |

Ces seuils sont volontairement ciblés. Les replays CLI/API/MCP, providers, promotion de snapshot, STALE/recovery et packaging restent des preuves distinctes : une ligne exécutée ne prouve pas un contrat fonctionnel.

## Exclusions et limites

Aucune classe critique n'est exclue du gate ciblé. Les classes d'assemblage, DTO simples, renderers et adapters non listés restent visibles dans le rapport agrégé mais ne portent pas de seuil M15 individuel.

Une hausse future des seuils doit suivre l'ajout de tests utiles. Une baisse nécessite une justification documentée dans la PR qui la propose.
