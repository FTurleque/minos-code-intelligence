# M0 — Rapport E1 du backend mémoire MINOS

Date : **22 juillet 2026**

Statut : **E1 exécutée — baseline légère reproductible établie**

## 1. Objectif

E1 mesure le chemin léger actuel avant toute comparaison avec Glean :

```text
index.scip
  -> ScipIndexReader
  -> ScipIngestionAdapter
  -> InMemoryCodeKnowledgeStore
  -> SymbolQueryService
  -> find_symbol / find_usages
```

Le but n'est pas de promouvoir le store mémoire comme backend persistant. Il
s'agit de fixer une référence fonctionnelle et opérationnelle minimale à
laquelle les backends candidats devront être comparés avec les mêmes contrats
MINOS.

Le protocole a été documenté avant son implémentation dans
`docs/M0_PLAN_EXPERIMENTATIONS.md`. Le corpus et les requêtes sont versionnés
dans `benchmarks/m0/e1-in-memory.json`.

## 2. Environnement

```text
OS                         Windows 10.0.19045 x64
CPU                        AMD64 Family 25 Model 80, 16 processeurs logiques
RAM physique               51 421 794 304 octets
Java                       OpenJDK 24.0.1
commit benchmark           3b17f73d2c61655e16398bb377edfefe457742d9
backend                    InMemoryCodeKnowledgeStore
processus par dataset      1 JVM neuf
warmup par requête         100 itérations
mesures par requête        500 itérations
requêtes par dataset       3
```

Deux campagnes complètes indépendantes ont été exécutées depuis le même commit
propre. Chaque campagne contient 24 000 opérations mesurées, soit 48 000 sur
les deux runs, en plus des échauffements.

## 3. Méthode

Pour chaque dataset, le harness mesure séparément :

- lecture Protobuf de l'index ;
- ingestion et alimentation du store ;
- temps cumulé jusqu'au backend prêt ;
- `find_symbol` ;
- `find_usages` sur un symbole dont le nom exact correspond à la requête ;
- heap pendant lecture/ingestion, heap retenue après ingestion et heap pendant
  les requêtes ;
- taille de l'index et espace disque propre au store.

`find_symbol` conserve sa sémantique actuelle de recherche partielle. Le
symbole utilisé par `find_usages` est choisi parmi les résultats par égalité
exacte de `Symbol.name`, puis par identifiant déterministe en cas d'ambiguïté.

La représentation canonique des résultats est construite dans la fenêtre de
latence. Son SHA-256 est calculé après la mesure afin de vérifier le résultat
sans ajouter le coût du digest à la requête.

Après ingestion, le harness libère explicitement l'objet Protobuf `Index` et
la table temporaire des fichiers avant de mesurer la heap retenue et les
requêtes. Le chiffre retenu ne compte donc pas une seconde copie complète de
l'index fournisseur.

## 4. Corpus

| Dataset | Documents | Symboles normalisés | Occurrences | Index (octets) |
|---|---:|---:|---:|---:|
| `java-simple` | 6 | 32 | 128 | 13 196 |
| `java-24-smoke` | 2 | 10 | 22 | 3 049 |
| `ariane-chatbot` | 220 | 4 587 | 25 956 | 2 489 722 |
| `java-multi-module` | 5 | 21 | 94 | 10 987 |
| `typescript-simple` | 6 | 24 | 100 | 14 546 |
| `typescript-modules` | 4 | 19 | 67 | 10 611 |
| `typescript-inheritance` | 6 | 18 | 57 | 10 839 |
| `typescript-unresolved` | 1 | 9 | 18 | 3 790 |

Les huit index occupent ensemble 2 556 740 octets, soit 2,44 MiB. Ariane
constitue le seul dataset réel de taille significative ; les sept autres sont
des fixtures de précision et de qualification.

## 5. Temps de reconstruction et latences

Les valeurs séparées par `/` correspondent aux deux runs. Les p95 sont les
p95 agrégés des trois requêtes de chaque dataset, sérialisation canonique
comprise.

| Dataset | Backend prêt (ms) | `find_symbol` p95 (ms) | `find_usages` p95 (ms) |
|---|---:|---:|---:|
| `java-simple` | 182,820 / 181,803 | 0,021 / 0,019 | 0,042 / 0,029 |
| `java-24-smoke` | 142,766 / 168,206 | 0,027 / 0,023 | 0,033 / 0,023 |
| `ariane-chatbot` | 431,731 / 444,206 | 1,222 / 1,302 | 8,156 / 9,120 |
| `java-multi-module` | 156,564 / 153,791 | 0,025 / 0,023 | 0,027 / 0,031 |
| `typescript-simple` | 154,681 / 145,934 | 0,019 / 0,018 | 0,029 / 0,026 |
| `typescript-modules` | 154,152 / 153,330 | 0,030 / 0,018 | 0,056 / 0,021 |
| `typescript-inheritance` | 161,844 / 146,344 | 0,015 / 0,017 | 0,030 / 0,028 |
| `typescript-unresolved` | 134,954 / 135,356 | 0,028 / 0,025 | 0,019 / 0,013 |

Pires valeurs par requête individuelle sur les deux runs :

```text
find_symbol p95       1,443 ms   ChatService / Ariane
find_symbol max       2,638 ms
find_usages p95      10,249 ms   ChatService / Ariane
find_usages max      13,020 ms
```

Comparaison aux objectifs C0 :

| Opération | Objectif | Pire p95 E1 | Résultat |
|---|---:|---:|---|
| `find_symbol` | <= 100 ms | 1,443 ms | atteint |
| `find_usages` | <= 250 ms | 10,249 ms | atteint |

Les temps processus complets sont de 0,55 à 0,64 seconde pour la plupart des
fixtures et de 14,40 / 15,29 secondes pour Ariane. Ils incluent toutefois le
démarrage, l'ingestion, 600 opérations d'échauffement et 3 000 opérations
mesurées ; ils ne doivent pas être présentés comme temps de démarrage du
backend. La métrique pertinente pour la reconstruction est `backendReadyMs`.

## 6. Mémoire et disque

| Mesure heap | Fixtures hors Ariane | Ariane run 1 / run 2 |
|---|---:|---:|
| pic lecture + ingestion | 9,11 MiB | 62,36 / 62,33 MiB |
| retenue après ingestion | 2,21–2,29 MiB | 21,30 / 21,30 MiB |
| pic pendant les requêtes | 10,67–10,76 MiB | 53,75 / 53,75 MiB |

Le pic requête est une borne supérieure qui inclut le store, la sérialisation
canonique et les allocations du harness de mesure. La mémoire rapportée est la
heap Java, somme des pools heap ; elle n'est pas une mesure RSS complète du
processus ou du système.

`InMemoryCodeKnowledgeStore` ne produit aucun fichier :

```text
workingStoreDiskBytes = 0
```

Cette valeur n'inclut ni les index SCIP sources, ni les journaux du benchmark.
Un backend persistant devra distinguer de la même manière données du store,
index source et fichiers temporaires.

## 7. Déterminisme

Chaque opération-requête conserve le même nombre de résultats et le même digest
pendant ses 500 mesures :

```text
resultDigestStable = true sur 8 / 8 datasets
```

La comparaison des deux JVM indépendants donne :

```text
couples dataset / opération / requête comparés    48
écarts de compteur                                  0
écarts de digest                                    0
```

E1 confirme donc l'ordre déterministe exposé par le store et les services de
requêtes sur ce corpus.

## 8. Limites

- une seule machine Windows a été utilisée ;
- deux JVM indépendants vérifient la répétabilité fonctionnelle, pas une
  distribution statistique multi-machines ;
- la heap ne couvre pas la mémoire native, les stacks ou le RSS ;
- le temps CPU n'est pas encore mesuré ;
- le plus grand index ne contient que 25 956 occurrences ;
- le store effectue actuellement des scans linéaires, sans index spécialisé ;
- la sérialisation canonique du harness n'est pas encore la sérialisation d'une
  future API ou d'un serveur MCP ;
- E1 couvre seulement `find_symbol` et `find_usages`, seules requêtes
  implémentées par la baseline ;
- les performances ne corrigent pas les limites de précision fournisseur déjà
  mesurées, notamment kinds TypeScript, surcharges et relations `CALLS`.

Les résultats bruts restent localement sous :

```text
.minos-m0/benchmarks/e1-in-memory/
.minos-m0/benchmarks/e1-in-memory-repeat/
```

Ils sont ignorés par Git et reproductibles via le runner.

## 9. Verdict E1

```text
backend                       InMemoryCodeKnowledgeStore
find_symbol latency gate      PASS
find_usages latency gate      PASS
deterministic results         PASS
working store disk            0
persistence                   UNSUPPORTED
advanced graph queries        UNSUPPORTED
verdict E1                    BASELINE_LEGERE_VALIDEE
```

Le backend mémoire est une baseline M0 crédible : simple, locale, rapide et
sans coût disque propre sur le corpus actuel. Cette conclusion ne le transforme
pas en backend persistant du MVP et ne préjuge pas de la valeur éventuelle de
Glean pour les traversées, les requêtes dérivées ou les volumes supérieurs.

La comparaison suivante devra conserver exactement le manifeste, les contrats
MINOS et les digests attendus, puis mesurer le coût opérationnel supplémentaire
du backend candidat.
