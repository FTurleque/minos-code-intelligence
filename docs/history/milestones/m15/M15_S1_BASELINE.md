# M15-S1 — Baseline de non-régression

Statut : **EN COURS — qualification exacte non encore exécutée sur le head final S1**

Issue principale : **#55**

## But

M15 modifie profondément la structure interne de MINOS. Avant le premier refactor, S1 doit capturer une référence reproductible permettant de démontrer que les étapes S2 à S11 ne modifient pas silencieusement les comportements déjà livrés.

La dernière qualification M14 enregistrait notamment **236 tests PASS**, mais cette preuve reste historique : elle ne valide pas automatiquement le head M15 courant.

## Outil de capture

Le script dédié est :

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

## Qualification exacte recommandée

Depuis PowerShell, à la racine du dépôt :

```powershell
$head = git rev-parse HEAD
.\scripts\m15\capture-baseline.ps1 -ExpectedHead $head
```

Avec validation Docker disponible :

```powershell
$head = git rev-parse HEAD
.\scripts\m15\capture-baseline.ps1 -ExpectedHead $head -ValidateDocker
```

### Modes de diagnostic

Les options suivantes existent pour isoler un problème d'environnement :

```powershell
-SkipM14Replay
-SkipProviderReplays
```

Elles **ne suffisent pas à déclarer M15-S1 terminé**. Une gate S1 complète doit conserver les replays nécessaires à la non-régression M14.

## Artefacts produits

Après exécution :

```text
target/m15-baseline/
├── baseline.json
├── baseline.md
├── maven-verify.log
└── m14-replay.log          # si le replay M14 est exécuté
```

`baseline.json` est la source machine-readable. `baseline.md` fournit le résumé humain.

## Données capturées

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

Le script initial capture la qualification fonctionnelle et la structure du build. Les mesures fines suivantes doivent être ajoutées avant fermeture de S1 ou au plus tard dans le premier PR qui introduit l'instrumentation nécessaire :

```text
active_snapshot_load_count
first_query_latency
repeated_query_latency
heap_after_load
symbol_count
occurrence_count
relationship_count
```

Ces mesures ne sont pas les benchmarks de grande échelle M16. Leur rôle est uniquement de fournir un **avant/après M15** sur un fixture stable.

## Gate S1

M15-S1 devient ✅ uniquement lorsque le head exact satisfait :

- [ ] worktree propre ;
- [ ] Java 24 ;
- [ ] `./mvnw clean verify` PASS ;
- [ ] tests JUnit : 0 failure / 0 error ;
- [ ] replay M14 PASS ;
- [ ] sorties CLI/API/MCP de référence conservées ;
- [ ] mesures de requêtes répétées capturées ;
- [ ] `baseline.json` et résumé final archivés comme preuve de jalon ;
- [ ] SHA exact inscrit dans la preuve finale.

## Principe de validation

Une exécution réussie sur un SHA précédent ne transforme jamais un nouveau head en ✅.

Toute modification postérieure à la qualification finale S1 impose soit :

1. de rejouer la qualification ; soit
2. de conserver S1 comme preuve historique et de qualifier explicitement le nouveau head dans l'étape M15 suivante.
