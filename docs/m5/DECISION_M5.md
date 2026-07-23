# Porte de clôture M5 — Tests liés et dérivations explicables

Date : **23 juillet 2026**

Statut : **M5 IMPLÉMENTÉ — VALIDATION FINALE EN ATTENTE**

## État de la décision

Le périmètre fonctionnel M5 est implémenté, mais le jalon n'est pas encore
déclaré terminé. La validation finale `clean verify` et le replay des quatre
artefacts SCIP réels doivent réussir dans un environnement autorisant la lecture
de la configuration du JDK local.

## Critères implémentés

| Critère | État |
|---|---|
| Détection des tests liés | implémentée |
| Conventions de nommage | implémentées comme heuristique |
| Références directes | implémentées depuis les occurrences résolues |
| Appels de méthodes | implémentés uniquement depuis les faits `CALLS` |
| Proximité package/namespace | implémentée comme renforcement |
| Score de confiance | implémenté, déterministe et documenté |
| Raisons structurées | implémentées et rendues en TEXT/JSON |
| Persistance snapshot v2 | implémentée |
| `get_related_tests` métier | implémenté |
| `minos related-tests` | implémenté |

## Preuves acquises

La première suite ciblée M5 a validé 16 tests sans échec : dérivation,
ingestion, requête, mapping CLI et rendu. Elle couvre notamment le nommage seul,
la référence directe, le fait `CALLS`, le namespace des méthodes, le refus
d'attribution d'un fichier multi-conteneurs et le déterminisme.

Deux portes supplémentaires sont maintenant codées :

- replay des quatre index TypeScript versionnés, avec zéro test lié attendu sur
  la fixture sans source de test ;
- import SCIP, publication du snapshot, réouverture du registre/store et appel
  JSON de la CLI `related-tests`.

Ces deux portes n'ont pas encore été exécutées après leur ajout, car le sandbox
a refusé l'accès à
`C:\Users\fturl\.jdks\openjdk-24.0.1\conf\security\java.security` et la demande
d'exécution hors sandbox a été refusée par la limite d'usage de l'environnement.

## Commande de clôture

```powershell
.\mvnw.cmd clean verify
.\minos.cmd --help
```

La décision passera à **M5 TERMINÉ ET VALIDÉ LOCALEMENT** uniquement après le
succès de cette porte et la consignation des nombres finaux de sources, tests et
relations réelles.

## Limites assumées

- les relations sont des indices de liaison, pas une mesure de couverture ;
- une heuristique de nommage reste explicitement heuristique ;
- une ambiguïté entre plusieurs conteneurs empêche l'attribution par fichier ;
- les appels ne sont pas inférés lorsqu'ils ne sont pas fournis ;
- GitHub Actions reste hors de cette décision locale.
