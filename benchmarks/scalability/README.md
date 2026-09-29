# Banc de scalabilité des snapshots (audit A6)

Mesures du lot A6 de `docs/audit/AUDIT-2026-09.md` (mémoire et scalabilité) : taille et composition
des snapshots persistés, ratio SCIP → snapshot, empreinte en tas, recherche hybride, analyse d'impact
et part de la découverte dans `LocalProjectArchitectureQuery`. Les résultats et leur lecture sont
consignés dans `docs/audit/ARCHI-SUIVI.md`, partie « Lot 3 — A6 ».

Le banc ne tourne jamais dans `mvn verify` : la classe
`minos-bootstrap/src/test/java/com/minos/bootstrap/scalability/SnapshotScalabilityBenchmark.java` est un
`main` des sources de test dont le nom ne correspond à aucun motif d'inclusion de Surefire (même
convention que `InMemoryBackendBenchmark` et `CodeSearchBenchmark`). Aucune donnée de mesure n'est
écrite dans le dépôt : le corpus et les résultats vont dans des répertoires passés en paramètre, hors
du dépôt (les scripts le vérifient).

## 1. Corpus réel

```powershell
.\benchmarks\scalability\prepare-corpus.ps1 -CorpusDirectory <hors-dépôt>\corpus [-Revision <sha>]
```

Le script indexe **ce dépôt** avec le runtime scip-java géré par MINOS (`minos tools install
scip-java` au préalable) :

1. copie des fichiers suivis de la révision (`git archive`) ;
2. corpus Java/Maven seulement : `fixtures/` (polyglotte) et `minos-intellij/` (Gradle) retirés, ainsi
   que `mvnw`, `mvnw.cmd` et `.mvn` (scip-java préfère `./mvnw`, qui n'est pas un exécutable Win32 ;
   le Maven géré par MINOS est utilisé, comme sur le runtime Linux) ;
3. `minos-app` construit dans son propre `target/` : dans le dépôt, il construit dans le `target/`
   racine, et son `clean` efface alors `target/scip-targetroot`, où scip-java agrège les modules
   (l'index ne contiendrait que `minos-app`) ;
4. appel direct du runner Windows géré (`scip-java-windows-runner.ps1`) : `minos index` l'exécute dans
   le bac à sable AppContainer non élevé, qui refuse un PowerShell installé sous `Program Files`.

Il écrit `minos-full.scip` et `corpus.txt` (révision, version de scip-java, taille, sha256). Deux
exécutions sur la même révision donnent la même taille à un octet près.

SCIP complémentaires, facultatifs, pour le ratio SCIP → snapshot : à déposer dans le répertoire du
corpus sous les noms de `scalability.json` (`ariane-chatbot.scip`, `nexus-context-engine.scip`, et
`minos-app-module.scip`, l'index obtenu sans l'étape 3). Les fixtures TypeScript du dépôt servent
toujours.

## 2. Mesures

```powershell
.\benchmarks\scalability\run-scalability-benchmark.ps1 -CorpusDirectory <corpus> -OutputDirectory <hors-dépôt>\run
```

Le script compile le harnais (`mvnw -pl minos-bootstrap -am test-compile`), lance la JVM avec les
paramètres de `scalability.json` et produit dans `<run>` : `result.tsv` (lignes
`METRIC section jeu mesure valeur`), `summary.json`, `environment.txt` (machine, JVM, corpus),
`stderr.txt`, ainsi que le `MINOS_HOME` jetable et le `user.home` de la JVM.

| Section | Jeu | Contenu |
|---|---|---|
| `ratio` | un par SCIP | pré-analyse, décodage, import sans persistance, import produit (publié ou refusé), taille V2, part des chaînes, taille UTF-8 et avec table de chaînes calculées, tas du snapshot |
| `size` | `file-f*`, `mem-k*` | même recensement sur les tranches et les répliques |
| `memory` | `file-f*` | chargement à froid de la vue de requête, construction des index, tas (vue, snapshot seul, index), poids estimé du cache |
| `hybrid` | `file-f*`, `mem-k*` | documents du corpus, première recherche, latence et allocations par requête, part de la normalisation (échantillonnage JFR) |
| `impact` | `file-f*`, `mem-k*` | coût fixe (racine sans arête entrante), trois racines à plus forte portée, `maxResults` 200/10 000/10, répartition JFR |
| `architecture` | `mem-k1` | découverte seule, intelligence complète, répartition JFR (Java et natif) |

Jeux de données :

- `file-fX` : tranche de fichiers du corpus réel (sélection stable par hachage du `fileId`, fraction X),
  publiée dans le magasin fichier, donc mesurée par le chemin produit (vue de requête, cache) ;
  `file-f1.00` est le corpus entier ;
- `mem-kN` : corpus réel entier répliqué N fois (identifiants préfixés `r<i>~`), servi par un magasin
  en mémoire du banc, hors persistance : il mesure ce que coûteraient les requêtes si le snapshot
  pouvait être chargé.

La configuration par défaut ne mesure que `mem-k1` : à partir de `mem-k2` (161 307 documents), le
corpus hybride n'est plus mis en cache et chaque recherche le reconstruit (environ 17 minutes). Pour
l'impact et les index sur les répliques, lancer une configuration dérivée avec `"replicas": [2, 3]`,
`"sections": ["impact", "memory"]`, `"fractions": [0.0]` et `"ratioScips": []` (environ 1 minute).

Le recensement des tailles parcourt le snapshot dans l'ordre des champs du codec V2 ; la taille prédite
est comparée à l'encodeur réel (`censusMatchesEncoder`) chaque fois que le snapshot tient sous le
plafond. Durée d'une exécution complète : plus d'une heure sur la machine de référence, dominée par la
construction des corpus hybrides (6 ms par document) et par la découverte du projet (36 s par appel).
