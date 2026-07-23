# M3 — Normalisation des relations SCIP

Statut : **deuxième incrément validé localement**
Date : **23 juillet 2026**

## Objectif

Cet incrément transforme les relations portées par les `SymbolInformation`
SCIP en relations MINOS factuelles, interrogeables immédiatement dans un
`CodeKnowledgeStore`.

Le schéma officiel SCIP définit quatre drapeaux directionnels :
[`is_reference`, `is_implementation`, `is_type_definition` et
`is_definition`](https://github.com/scip-code/scip/blob/main/scip.proto).

## Mapping

| Drapeau SCIP | Kind MINOS | Interprétation |
|---|---|---|
| `is_reference` | `REFERENCES` | inclure la relation dans Find references |
| `is_implementation` | `IMPLEMENTS` | inclure la source dans Find implementations de la cible |
| `is_type_definition` | `TYPE_DEFINITION` | navigation vers la définition du type |
| `is_definition` | `DEFINITION` | définition déléguée ou multiple fournie par l'indexeur |

`IMPLEMENTS` décrit ici la sémantique de navigation du protocole. Ce kind ne
prouve pas qu'un mot-clé `implements` existe dans le code. Le même drapeau SCIP
peut représenter une implémentation d'interface, une sous-classe ou un override.
MINOS ne le transforme donc jamais en `EXTENDS` sans preuve plus précise.

Un message SCIP peut porter plusieurs drapeaux. MINOS produit alors une
`Relationship` par drapeau afin que `REFERENCES` et `IMPLEMENTS`, par exemple,
restent filtrables séparément.

## Résolution

Le normaliseur s'exécute après la passe des symboles :

1. la source est résolue par la clé de catalogue SCIP, avec portée documentaire
   pour les symboles locaux ;
2. la cible est recherchée dans le même catalogue normalisé ;
3. une cible trouvée devient un `CodeEntityRef(SYMBOL, id MINOS)` résolu ;
4. sinon, le nom qualifié ou le dernier descripteur SCIP récupérable est conservé
   comme `unresolvedTarget` ;
5. si aucune source MINOS ou aucune cible exploitable n'existe, le fait est
   compté comme ignoré.

Les identifiants de relation hachent le projet, l'ID source MINOS, le fournisseur,
la cible SCIP et le kind. Ils restent stables entre deux runs du même fournisseur
sans exposer l'identifiant SCIP brut.

Chaque relation possède :

- `InformationNature.FACTUAL` ;
- l'origine SCIP du symbole source ;
- une preuve structurée de poids `1.0` ;
- la déclaration source comme emplacement lorsqu'elle existe ;
- un statut `RESOLVED` ou `UNRESOLVED` explicite.

## Métriques d'ingestion

`ScipIngestionReport` distingue :

- les messages relationnels fournisseur ;
- le nombre total de drapeaux vrais ;
- les relations MINOS uniques ;
- les relations résolues et non résolues ;
- les faits ignorés ;
- les doublons coalescés.
- les dépendances dérivées, séparément des faits fournisseur.

Cette distinction est nécessaire puisqu'un message peut produire plusieurs
faits et que plusieurs entrées fournisseur peuvent décrire le même fait.

## Rejeu réel TypeScript

| Dataset | Messages | Faits | Normalisées | Résolues | Ignorées | Doublons |
|---|---:|---:|---:|---:|---:|---:|
| `typescript-simple` | 2 | 3 | 3 | 3 | 0 | 0 |
| `typescript-inheritance` | 11 | 14 | 14 | 14 | 0 | 0 |
| `typescript-modules` | 4 | 6 | 6 | 6 | 0 | 0 |
| `typescript-unresolved` | 2 | 3 | 3 | 3 | 0 | 0 |
| **Total** | **19** | **26** | **26** | **26** | **0** | **0** |

Les 26 faits correspondent à 19 `is_implementation` et 7 `is_reference`. Aucun
`is_type_definition` ou `is_definition` n'est présent dans ces quatre artefacts ;
leurs mappings sont couverts par le test synthétique à quatre drapeaux.

## Limites

- aucune occurrence n'est promue en `CALLS` ;
- `EXTENDS` n'est pas produit depuis `is_implementation` ;
- les snapshots historiques v1 ne contiennent que les symboles ; le format v2
  est requis pour persister ces relations ;
- une absence de fait `CALLS` reste une capacité fournisseur absente.

La clôture M3 persiste ces faits dans le snapshot v2, dérive les dépendances et
les expose dans la CLI. Voir [`DECISION_M3.md`](DECISION_M3.md).

## Validation

```text
.\mvnw.cmd clean verify
74 sources main compilées
31 sources test compilées
98 tests réussis
0 échec
0 erreur
0 skipped
BUILD SUCCESS
```
