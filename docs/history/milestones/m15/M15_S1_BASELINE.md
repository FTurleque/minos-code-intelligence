# M15-S1 — Baseline de non-régression

Statut : **EN COURS — le prochain run complet du head final doit fournir la preuve S1 définitive**

Issue principale : **#55**

## But

M15 modifie profondément la structure interne de MINOS. Avant le premier refactor, S1 doit capturer une référence reproductible permettant de démontrer que les étapes S2 à S11 ne modifient pas silencieusement les comportements déjà livrés.

La dernière qualification M14 enregistrait notamment **236 tests PASS**, mais cette preuve reste historique : elle ne valide pas automatiquement le head M15 courant.

## Runner unique

La qualification S1 complète se lance désormais avec une seule commande depuis la racine du dépôt :

```powershell
.\scripts\m15\run-s1.ps1
```

Le runner :

1. vérifie le worktree ;
2. fetch la branche `m15-s1-baseline` ;
3. se place sur cette branche si nécessaire ;
4. effectue uniquement un fast-forward vers le dernier head distant ;
5. exécute la baseline fonctionnelle exacte ;
6. rejoue M14 avec les providers Java et TypeScript ;
7. capture automatiquement la baseline de coût des requêtes répétées.

Le mode complet est le seul mode suffisant pour fermer S1.

## Capture fonctionnelle

Le script fonctionnel appelé par le runner est :

```powershell
.\scripts\m15\capture-baseline.ps1
```

Il impose :

- worktree Git propre ;
- résolution du SHA exact ;
- Java 24 via les helpers Windows MINOS ;
- Maven Wrapper du dépôt ;
- `clean verify` ;
- agrégation des rapports JUnit Surefire/Failsafe ;
- capture des nombres de sources principales et de test ;
- capture de la taille du reactor Maven ;
- replay M14 par `scripts/m14/validate-local.ps1` sauf désactivation explicite ;
- génération d'artefacts lisibles et machine-readable.

### Modes de diagnostic

Les options suivantes existent pour isoler un problème d'environnement :

```powershell
.\scripts\m15\run-s1.ps1 -SkipM14Replay
.\scripts\m15\run-s1.ps1 -SkipProviderReplays
.\scripts\m15\run-s1.ps1 -ValidateDocker
```

`-SkipM14Replay` et `-SkipProviderReplays` **ne suffisent pas à déclarer M15-S1 terminé**.

## Baseline de coût des requêtes répétées

Après un replay M14 complet, le runner appelle :

```powershell
.\scripts\m15\capture-query-baseline.ps1
```

Cette mesure utilise le projet `m14-java` réellement indexé par le replay M14 et le probe non-production :

```text
scripts/m15/M15RepeatedQueryProbe.java
```

Le probe n'est pas compilé dans MINOS et ne modifie aucun comportement du moteur. Il mesure le chemin S1 existant via `LocalProjectSymbolQuery` avant l'introduction du cache et des indexes de M15.

La métrique `active_snapshot_load_count` est rattachée explicitement au comportement du head S1 : `LocalProjectSymbolQuery.loadQueryStore()` appelle `loadActiveKnowledge()` une fois par invocation de requête mesurée. Le probe exclut de ce compteur la lecture directe utilisée uniquement pour découvrir les métadonnées du fixture.

## Artefacts produits

Après un run complet :

```text
target/m15-baseline/
├── baseline.json
├── baseline.md
├── maven-verify.log
├── m14-replay.log
├── query-baseline.json
├── query-baseline.md
└── query-baseline.log
```

`baseline.json` est la source machine-readable de la qualification fonctionnelle. `query-baseline.json` est la source machine-readable des mesures avant refactor.

## Données fonctionnelles capturées

```text
HEAD_SHA
UTC timestamp
Java version / JAVA_HOME
Maven Wrapper version
reactor module count
main Java source count
test Java source count
Maven verify status
JUnit report count
tests / failures / errors / skipped
M14 replay status
provider replay mode
Docker validation mode
failure diagnostic
```

## Mesures performance M15-S1

Sur le fixture Java stable produit par le replay M14, le runner enregistre :

```text
active_snapshot_load_count
first_query_latency_ms
repeated_query_latency_average_ms
repeated_query_latency_p50_ms
repeated_query_latency_p95_ms
heap_after_load_bytes
symbol_count
occurrence_count
relationship_count
```

Ces mesures ne sont pas les benchmarks de grande échelle M16. Leur rôle est uniquement de fournir un **avant/après M15** sur un fixture stable et de rendre visibles les effets de S7/S8 sur les rechargements de snapshot et le coût des requêtes répétées.

## Couverture de non-régression

Le replay M14 et les tests du reactor couvrent notamment :

- CLI structurante ;
- API publique ;
- catalogue MCP et serveur stdio ;
- provider TypeScript ;
- provider Java ;
- transition volontaire vers `STALE` après échec d'indexation ;
- recovery Java ;
- build de distribution Windows ;
- installation de la distribution ;
- `doctor` sur installation ;
- handshake MCP natif ;
- intégrations MCP natives Copilot/Claude/Codex.

Les sorties correspondantes sont conservées dans `maven-verify.log` et `m14-replay.log`.

## Gate S1

M15-S1 devient ✅ uniquement lorsque **le même head exact** satisfait :

- [ ] worktree propre ;
- [ ] Java 24 ;
- [ ] `./mvnw clean verify` PASS ;
- [ ] tests JUnit : 0 failure / 0 error ;
- [ ] replay M14 PASS ;
- [ ] sorties CLI/API/MCP de référence conservées ;
- [ ] baseline de requêtes répétées PASS ;
- [ ] métriques de volumes/latence/heap enregistrées ;
- [ ] `baseline.json`, `query-baseline.json` et résumés générés ;
- [ ] SHA exact inscrit dans la preuve finale de PR.

## Principe de validation

Une exécution réussie sur un SHA précédent ne transforme jamais un nouveau head en ✅.

La qualification finale est donc publiée dans la PR S1 **après** le dernier run complet, sans nouveau commit sur la branche. Toute modification ultérieure du head impose une nouvelle qualification explicite.
