# M4 — Plages pertinentes et source complète explicite

Statut : **validé localement**

Date : **23 juillet 2026**

## Identifiants de fichiers

Lors d'un import SCIP persistant, un chemin documentaire relatif sûr devient
par défaut le `fileId` MINOS. Une correspondance explicite fournie par
l'appelant reste prioritaire. Les chemins absolus, vides ou remontant hors du
projet ne sont jamais promus ; l'identifiant opaque historique reste alors
utilisable pour les faits, mais aucune source locale n'est prétendue disponible.

Cette évolution ne change pas le format v2 : `fileId` était déjà une chaîne
persistée. Elle rend simplement les nouveaux snapshots compatibles avec la
lecture source M4.

## Plage pertinente

`LocalSourceReader` lit le fichier UTF-8 correspondant à l'emplacement du
symbole et retourne la déclaration avec le nombre demandé de lignes de
contexte. Si la plage dépasse le budget restant :

1. les lignes de contexte les plus éloignées sont retirées ;
2. la déclaration est conservée autant que possible ;
3. une ligne exceptionnellement longue est tronquée à une frontière UTF-16
   sûre ;
4. `truncated=true` signale la réduction.

La réponse indique la plage effective, le contenu, ses tokens estimés, le
nombre de lignes et le volume estimé du fichier complet.

## Source complète

Le contenu complet n'est récupéré que par une commande distincte :

```text
minos get-source <project> <file-id> [--format text|json]
```

La réponse porte `fullFile=true` et `truncated=false`. Une limite de sécurité de
16 MiB évite une allocation accidentelle déraisonnable ; dépasser cette limite
produit une erreur explicite.

## Confinement

La racine du projet et la cible sont résolues vers leurs chemins réels. Le
lecteur refuse :

- les chemins absolus ;
- `..` après normalisation ;
- une cible située hors de la racine ;
- un lien symbolique dont la cible réelle sort du projet ;
- un fichier absent ou non régulier pour `get-source`.

Aucun accès réseau, cloud ou LLM n'est utilisé.
