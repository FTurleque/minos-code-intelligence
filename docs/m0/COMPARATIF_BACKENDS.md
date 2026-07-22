# M0 — Comparatif des backends E2

Date : 22 juillet 2026

Statut : **DÉCISION M0 OBTENUE SUR LE CHEMIN PAR DÉFAUT**

## Décision

Le chemin par défaut du MVP reste un backend léger appartenant à MINOS,
derrière `CodeKnowledgeStore`.

Glean reste un backend avancé optionnel possible, mais C2 Thrift et C3 sidecar
ne sont pas poursuivis pendant M0. Une nouvelle expérimentation nécessitera un
cas d'usage mesurable que la baseline légère ne satisfait pas : traversée de
graphe complexe, gros volume, multi-dépôts ou dérivation spécialisée.

Cette décision ne transforme pas `InMemoryCodeKnowledgeStore` en stockage de
production. Il reste la baseline fonctionnelle et de performance. Le choix
d'un stockage embarqué persistant MINOS sera documenté avant son
industrialisation.

## Périmètre comparable

Les deux chemins utilisent le même `index.scip` `java-simple` et la même vérité
terrain. Glean exige en plus une copie des plages modernes vers les champs SCIP
historiques.

```text
Backend mémoire : index.scip -> ScipIngestionAdapter -> CodeKnowledgeStore
Glean C1        : index.scip -> copie compatible -> Glean DB -> Angle CLI
```

Les latences ne représentent pas exactement la même frontière : la baseline
mesure les opérations MINOS sérialisées dans une JVM déjà démarrée, tandis que
C1 mesure des prédicats Glean de niveau fournisseur. La durée processus Glean
inclut WSL et l'ouverture de base. Cette différence interdit de présenter les
valeurs comme un benchmark micro équivalent, mais elle mesure correctement le
coût utilisateur des deux chemins expérimentaux disponibles.

## Résultats fonctionnels sur `java-simple`

| Critère | Backend mémoire MINOS | Glean C1 |
|---|---:|---:|
| Symboles obligatoires présents | 13 / 13 | 13 / 13 |
| Kinds exacts | 12 / 13 | 9 / 13 |
| Cibles d'usage présentes | 5 / 5 | 5 / 5 |
| Implémentation d'interface attendue | 1 / 1 | 1 / 1 |
| Relations `CALLS` explicites | 0 / 3 | 0 / 3 |
| Non-résolution représentable | oui | non démontré par le schéma C1 |
| Clés de symboles dupliquées | 0 | 0 |

Glean ajoute un fait correct d'override de méthode et un langage de requête
relationnel. Sur cette fixture, il n'apporte toutefois aucune capacité MVP
obligatoire absente du chemin MINOS et dégrade la fidélité des kinds via son
convertisseur SCIP 0.2.0.1.

## Résultats opérationnels

| Mesure | Backend mémoire MINOS | Glean C1 |
|---|---:|---:|
| Préparation des données | 182,820 ms | 2 095 ms après copie compatible |
| Reconstruction indépendante | 182,820 ms au run mesuré | 2,01 s |
| Latence p95 symboles | 0,021 ms | 69,364 ms interne ; 807 ms processus |
| Latence p95 références | 0,042 ms | 72,890 ms interne ; 802 ms processus |
| Mémoire après ingestion | 2,29 MiB heap retenue | pas de processus idle en CLI |
| Pic requête | 10,76 MiB heap | 151,73 MiB RSS |
| Pic ingestion | 9,11 MiB heap | 199,55 MiB RSS |
| Disque du store | 0 | 426 KiB par base |
| Runtime | Java 24 | WSL2, Linux, GHC/Cabal, Glean, RocksDB |
| Fichiers d'installation locaux | Maven déjà requis | 4,52 GB de store Cabal + 170 MiB versionnés |
| Processus par requête | JVM du harness | 1 processus, 5 threads |

La base Glean est compacte pour la fixture, mais le coût d'installation et le
RSS sont très supérieurs à la baseline. Le premier build Cabal a demandé
environ 97 minutes avant d'échouer lors de la copie d'un outil interne ; la
publication du binaire principal a nécessité une reprise ciblée.

## Évaluation selon les critères M0

| Critère d'adoption Glean | Verdict |
|---|---|
| Valeur fonctionnelle supérieure | non démontrée pour le MVP |
| Installation automatisable | oui, mais toolchain Linux lourde |
| Expérience Windows crédible | WSL2 requis, non transparente |
| Compatibilité SCIP moderne | non, adaptateur historique requis |
| Communication maintenable | CLI mesurée ; Thrift/sidecar non justifiés |
| Démarrage acceptable | coût processus proche de 0,8 s au p95 |
| Empreinte justifiée | non sur `java-simple` |
| Reconstruction fiable | oui après correction de l'entrée |
| Isolation du domaine | oui, aucun type Glean dans le cœur |

## Conséquences

- `CodeKnowledgeStore` reste la frontière obligatoire.
- Le cœur continue sans type Glean, Angle ou Thrift.
- `InMemoryCodeKnowledgeStore` reste le groupe de contrôle, pas le stockage
  persistant final.
- Le prochain choix de stockage doit privilégier la distribution locale Java
  24 et les opérations MVP mesurées.
- Glean ne sera réouvert que par une exigence et un benchmark explicites.
- L'incompatibilité des plages SCIP doit être requalifiée si une nouvelle
  version Glean ou un nouveau convertisseur est évalué.

## Preuves

- `RAPPORT_GLEAN_C1.md` : installation, échecs conservés, ingestion, requêtes,
  mémoire et reconstruction Glean ;
- `RAPPORT_BACKEND_MEMOIRE_E1.md` : deux campagnes du backend mémoire sur huit
  index ;
- `RAPPORT_SCIP_JAVA_A1_A2.md` : vérité terrain `java-simple` et baseline
  SCIP/MINOS.
