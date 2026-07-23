# Décision de clôture M2 — Intelligence des symboles

Date : **23 juillet 2026**

Statut : **M2 TERMINÉ ET VALIDÉ LOCALEMENT**

## Verdict

Le jalon M2 est clôturé. MINOS expose désormais une recherche normalisée,
compacte, persistante et invocable depuis un nouveau processus, sans dépendance
du consommateur envers SCIP ou le backend interne.

Les contraintes fournisseur observées en M0 restent explicites. Elles ne sont
ni masquées ni transformées en identités inventées.

## Porte de sortie

| Critère | Résultat |
|---|---|
| Modèle normalisé des symboles | satisfait |
| Identité stable et qualité explicite | satisfait avec fallback qualifié |
| Recherche lexicale et par nom qualifié | satisfaite |
| Filtres kind/module et symboles d'un fichier | satisfaits |
| Symboles externes et statuts non résolus | représentés et persistés |
| Résultat compact TEXT/JSON | satisfait |
| Backend rechargeable et snapshot actif | satisfait |
| `minos find-symbol <projet> <symbole>` | satisfait par le launcher local |
| Frontière fournisseur | satisfaite |
| Validation fixtures réelles | satisfaite avec limites documentées |

## Preuves techniques

```text
.\mvnw.cmd clean verify
69 sources main compilées
29 sources test compilées
86 tests réussis
0 échec
0 erreur
BUILD SUCCESS
```

Le test du launcher crée un registre et un snapshot, ferme les instances, puis
démarre un nouveau processus Java qui exécute `find-symbol` et relit le même
snapshot. Le wrapper Windows et le manifest du JAR ont également été vérifiés :

```text
.\minos.cmd --help
exit 0
```

GitHub Actions n'a pas été relancé et ne fait pas partie de cette preuve locale.

## Rejeu des index TypeScript locaux

Les quatre index réels générés précédemment ont été relus sur le code final :

| Fixture | Faits | Symboles | Occurrences | Résolues | Non résolues |
|---|---:|---:|---:|---:|---:|
| `typescript-simple` | 32 | 24 | 100 | 70 | 30 |
| `typescript-inheritance` | 25 | 18 | 57 | 39 | 18 |
| `typescript-modules` | 27 | 19 | 67 | 44 | 23 |
| `typescript-unresolved` | 10 | 9 | 18 | 14 | 4 |

Les recherches ciblées retrouvent `UserService.findUser`,
`UserService.constructor`, les trois méthodes `describe`, les symboles
multi-modules et `BrokenAdapter.execute`.

## Contraintes conservées

### Identité canonique

Aucun couple de fournisseurs indépendants n'est disponible pour le même
langage et les mêmes symboles. M2 ne promeut donc aucune identité à
`CANONICAL` sur cette seule base. Les symboles projet restent
`STRUCTURAL_FALLBACK` et les externes `PROVIDER_SCOPED_FALLBACK`. Une signature
fiable rend la clé structurelle indépendante du déplacement du fichier.

### Surcharges TypeScript

`scip-typescript 0.4.0` publie les deux déclarations et l'implémentation de
`GreetingService.greet` sous le même identifiant : trois définitions, deux
doublons de catalogue, aucune identité de surcharge distincte. MINOS conserve
ce fait fusionné au lieu d'inventer des identités. Le modèle, le store
persistant et les requêtes distinguent correctement les surcharges lorsqu'un
fournisseur fournit des signatures/identités distinctes, ce qui est vérifié par
les tests Java synthétiques et SCIP.

### Références non résolues TypeScript

La fixture `typescript-unresolved` conserve quatre occurrences non résolues.
`MissingClient` n'est pas catalogué comme symbole par l'index et la recherche
retourne donc zéro symbole, explicitement. Les statuts `UNRESOLVED` sont pris en
charge dans le modèle et le snapshot lorsque le fournisseur produit un fait de
symbole correspondant.

### Kinds TypeScript

Les kinds absents restent `OTHER`. Aucun kind n'est inféré sans preuve.

## Suite

Le prochain jalon est M3 — Intelligence des relations. La version suivante du
snapshot devra persister les occurrences et relations, puis exposer les
requêtes d'usages, implémentations, appels et dépendances selon les capacités
réellement qualifiées de chaque fournisseur.
