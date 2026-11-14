# Tâche 6.8 — mesures de la projection (relevé)

Protocole : `design.md`, « Performance et mesure ». **Aucun seuil n'est fixé par ce relevé** : les seuils et le
plafond de points par défaut sont des décisions du propriétaire (tâche 6.8, questions 1 et 2 du design). La
tâche ne se coche qu'une fois ces décisions prises et les paliers manquants relevés ou écartés.

## Ce qui a été mesuré

Poste Windows 10 (amd64, 16 processeurs logiques), Java 21.0.12, tas maximal de la JVM de test 12 Gio, commit de
la branche `feat/projeter-index-semantique-2d`, 2026-11-14. Corpus **synthétiques** : documents `CHUNK` dont le
vecteur est celui du provider `local-hash` à 256 dimensions sur un contenu fabriqué ; stockage local (fichier).
Options de préparation par défaut (`--max-points 100000`, `--seed 42`, `--kinds CHUNK`, `--max-memory-mb 256`).

Commande, rejouable :

```bash
./mvnw -B -ntp -pl minos-bootstrap -am test "-Dtest=SemanticProjectionMeasurementTest" "-Dminos.projection.measure=true" "-Dminos.projection.measure.sizes=1000,10000,25000" "-Dsurefire.failIfNoSpecifiedTests=false" "-Djacoco.skip=true"
```

Relevé écrit par le test dans `target/projection-measurements.md` :

| Documents | Dimensions | Préparation (ms) | Passes sur l'index | Mémoire de travail comptée (octets) | Pic de tas JVM (octets) | Fichier de cache (octets) | Charge JSON de tous les points (octets) | Statut + lecture de tous les points (ms) | Variance expliquée (axe 1, axe 2) |
|---|---|---|---|---|---|---|---|---|---|
| 1 000 | 256 | 655 | 43 | 4 708 956 | 118 886 616 | 67 505 | 126 264 | 29 | 0,0706 ; 0,0607 |
| 10 000 | 256 | 3 066 | 43 | 8 677 276 | 263 726 264 | 672 585 | 1 264 237 | 64 | 0,0695 ; 0,0619 |
| 25 000 | 256 | 7 017 | 43 | 15 290 556 | 285 496 840 | 1 680 905 | 3 160 867 | 107 | 0,0696 ; 0,0622 |

Lecture du relevé :

- La durée croît linéairement avec le corpus (environ 0,28 ms par document, pour 43 passes). Les 40 itérations
  d'ajustement sont toutes consommées : sur ces vecteurs, la base ne se stabilise pas avant le plafond.
- La mémoire de travail **comptée** reste loin du budget de 256 Mio et croît avec le nombre de points (clé, rang,
  coordonnées), pas avec la taille des vecteurs lus.
- Le **pic de tas JVM** n'est pas une borne : avec 12 Gio de tas autorisé, le ramasse-miettes laisse s'accumuler
  les lots déjà traités. Il n'a pas été mesuré sous un tas contraint.
- Le fichier de cache pèse environ 67 octets par point, la charge JSON environ 126 octets par point : par
  extrapolation linéaire, 100 000 points donneraient environ 6,7 Mo de cache et 12,6 Mo de JSON, lus en 20 pages
  de 5 000 points. **Extrapolation, non mesurée.**
- La variance expliquée par les deux axes est faible (environ 13 % au total) : sur des vecteurs `local-hash`
  synthétiques, la carte PCA résume mal le corpus. C'est le risque annoncé par le design, constaté ici sur un
  provider qui n'est pas un modèle de langage ; cela ne dit rien d'un modèle appris.

Relevé complémentaire, par les commandes réelles sur un `MINOS_HOME` jetable (index `local-hash` à 384
dimensions de la fixture `fixtures/typescript/typescript-modules`, 42 documents, trois natures) : préparation en
296 ms, 33 passes, fichier de 4 842 octets, variance expliquée 0,363 et 0,142.

## Ce qui n'a pas été mesuré

| Palier ou relevé du protocole | État | Raison |
|---|---|---|
| Index réel d'environ 25 000 documents (`docs/project/governance/STATUS.md:61`) | non mesuré | cet index a été produit sous Linux en conteneur ; le poste ne peut pas le reproduire. Le palier de 25 000 documents ci-dessus est synthétique |
| 100 000 documents sur PostgreSQL | non mesuré | aucun PostgreSQL/pgvector disponible sur le poste ; la lecture par lots PostgreSQL n'est exercée que par la CI (`PostgresSemanticDocumentReadsContractTest`) |
| Linux | non mesuré | mesures faites sous Windows seulement |
| Délai du premier rendu de la carte, fluidité du zoom et du déplacement | non mesuré | exige un navigateur réel ; à relever pendant le parcours de la tâche 6.7 |
| Préparation sous un tas JVM contraint | non mesuré | voir « pic de tas » |
| Modèle d'embeddings appris (Ollama) | non mesuré | aucun provider appris sur le poste |

## Propositions soumises au propriétaire

Ces valeurs sont des **propositions** ; aucune n'est appliquée comme seuil ni gardée par un test.

| # | Décision à prendre | Proposition | Fondement |
|---|---|---|---|
| 1 | Plafond de points par défaut (`--max-points`) | garder 100 000 **à titre provisoire**, à confirmer par un rendu réel de 100 000 points dans un navigateur | 25 000 points mesurés : 3,2 Mo de JSON, lecture en 107 ms ; 100 000 points extrapolés à 12,6 Mo, non rendus |
| 2 | Durée de préparation acceptable | ≤ 30 s pour 25 000 documents à 256 dimensions sur stockage local | mesuré : 7,0 s ; la marge couvre un poste plus lent et 768 dimensions (non mesuré) |
| 3 | Budget de mémoire par défaut (`--max-memory-mb`) | garder 256 | mémoire comptée mesurée : 15,3 Mo pour 25 000 points |
| 4 | Charge des points | ne pas ouvrir de format binaire tant que le rendu de 100 000 points en JSON n'est pas mesuré insuffisant | tâche 7.1 |
| 5 | Nombre maximal d'itérations (40) | à garder ; une baisse (par exemple 20) diviserait la durée par deux mais change les coordonnées publiées, donc la version de la méthode | 40 itérations toutes consommées sur les trois paliers |

Éléments pour la décision 7.1 (aucune option n'est implémentée) : la variance expliquée faible plaide pour
évaluer une méthode non linéaire **sur un modèle appris**, pas sur `local-hash` ; rien ne justifie WebGL ni la 3D
tant que le rendu Canvas n'est pas mesuré dans un navigateur.
