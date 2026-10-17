# Décision M9 — CLI stabilisée

Date : **23 juillet 2026**

Statut : **DÉCISION PRÉPARÉE — VALIDATION LOCALE FINALE EN ATTENTE**

Suivi : issue #29.

## Question de porte

> MINOS expose-t-il son cœur déjà validé via une CLI cohérente, scriptable, documentée et stable, avec les mêmes résultats métier que les services sous-jacents ?

## Verdict préparé

> **OUI, sous la frontière d'exécution réellement disponible : la CLI stabilise l'administration, les requêtes M2–M8 et l'import SCIP explicite ; elle ne revendique pas encore un runner automatique d'indexeur externe.**

## Acquis

M9 stabilise :

```text
minos project add
minos project list
minos project inspect
minos inspect
minos index
minos index-status
minos search
minos find-symbol
minos get-source
minos find-usages
minos find-implementations
minos find-callers
minos find-callees
minos dependencies
minos dependents
minos related-tests
minos architecture
minos impact
```

Contrats transverses :

- `text` et `json` ;
- codes de sortie `0 / 1 / 2` ;
- erreurs sur stderr ;
- aide lazy ;
- sortie JSON sans texte parasite ;
- encodeur JSON partagé et déterministe ;
- bootstrap local via `minos.home`, `MINOS_HOME` ou `~/.minos`.

## Frontière d'indexation

Le cœur M1/M7 dispose de ports d'exécution et d'un lifecycle, mais le dépôt ne contient pas encore d'implémentation de production de `IndexerExecutor` lançant un processus `scip-java` ou `scip-typescript`.

M9 choisit donc une frontière vérifiable :

```text
artefact SCIP existant
    -> ScipSymbolSnapshotImporter
    -> normalisation MINOS
    -> FileSymbolSnapshotStore
    -> snapshot actif
```

Cette commande est une vraie indexation/import de connaissance, mais **pas** un faux runner de processus.

## État d'index

`index-status` et `inspect` utilisent le snapshot réellement relisible comme source de vérité :

```text
snapshot actif absent   -> NEVER_INDEXED
snapshot actif lisible  -> READY
```

La date/provider de dernier succès n'est exposée que lorsqu'un import CLI M9 aligné avec le snapshot actif l'a enregistrée. Les snapshots historiques n'obtiennent aucune date synthétique.

## Réutilisation métier

M9 ne réécrit pas :

- recherche M4 ;
- symboles/usages M2/M3 ;
- relations M3/M5 ;
- architecture M6 ;
- impact M8.

La CLI se contente d'adapter arguments, formats et erreurs vers les ports existants.

## Qualification

Les nouveaux tests couvrent :

- chaîne end-to-end projet → import SCIP → architecture → impact ;
- `index-status` après publication ;
- JSON machine-readable ;
- échappement JSON ;
- codes de sortie ;
- aide par commande sans création du home.

La suite historique reste la garde de non-régression des commandes déjà livrées.

## Limites assumées

Hors M9 :

- installation et lancement automatique des indexeurs ;
- MCP de production (M10) ;
- API réseau (M11) ;
- intelligence multi-dépôts/Git (M12) ;
- intégration NEXUS (M13).

## Validation attendue

La porte locale finale doit confirmer :

```powershell
.\mvnw.cmd clean verify
```

sur le head exact de la PR M9.

Après fusion :

- issue #29 → `completed` ;
- M9 → terminé, validé et livré ;
- M10 — Serveur MCP → prochain jalon.
