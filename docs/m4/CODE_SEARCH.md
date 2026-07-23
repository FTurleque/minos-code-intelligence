# M4 — Recherche unifiée de code

Statut : **validé localement**

Date : **23 juillet 2026**

## Objectif

`minos search` compose une réponse directement exploitable par un outil ou un
agent à partir du snapshot actif et des sources locales. La réponse rassemble,
pour chaque symbole racine :

- identité, nom, signature, kind, langage, emplacement et provenance ;
- relations entrantes et sortantes avec preuves ;
- usages résolus pertinents ;
- plage source locale autour de la déclaration ;
- métriques de volume et signal de troncature.

Le service reste indépendant de SCIP : il consomme `CodeKnowledgeStore`, le
modèle MINOS et un port `SourceReader`.

## Commande

```text
minos search <project> <query> [options]
```

Options principales :

| Option | Défaut | Bornes |
|---|---:|---:|
| `--limit` | 5 symboles | 1–20 |
| `--depth` | 1 | 0–3 |
| `--usages` | 3 par symbole | 0–50 |
| `--relationships` | 10 par nœud | 0–50 |
| `--context-lines` | 2 | 0–50 |
| `--max-tokens` | 4 000 | 256–32 768 |
| `--no-source` | faux | drapeau |
| `--format` | `text` | `text` ou `json` |

Les filtres `--qualified-name`, `--kind` et `--module` réutilisent le contrat
structuré M2.

## Traversée

La traversée relationnelle est une recherche en largeur :

1. le symbole demandé est la racine de profondeur 0 ;
2. ses relations directes sont de profondeur 1 ;
3. la cible d'une relation sortante ou la source d'une relation entrante est
   ajoutée à la frontière suivante lorsqu'elle est résolue ;
4. chaque entité et chaque relation ne sont visitées qu'une fois ;
5. la profondeur maximale et la limite par nœud sont appliquées avant
   exposition.

Chaque résultat relationnel conserve l'ancre visitée, la direction, la
profondeur, la nature, la confiance, l'origine et les preuves. Les cibles non
résolues restent visibles mais ne sont pas traversées.

## Troncature explicite

`truncated=true` est retourné si au moins une limite a retiré une information :

- symboles racine supplémentaires détectés par une lecture `limit + 1` ;
- relations ou usages au-delà de leur limite ;
- budget de tokens épuisé ;
- plage source réduite.

Le budget ne modifie jamais les faits conservés dans le snapshot. Il borne
uniquement la vue produite.

## Ordre de priorité

Sous contrainte de budget, MINOS conserve dans cet ordre :

1. le symbole racine et sa provenance ;
2. les relations et leurs preuves ;
3. les usages résolus ;
4. la plage source, réduite si nécessaire.

Le fichier complet n'est jamais inclus par `search`.
