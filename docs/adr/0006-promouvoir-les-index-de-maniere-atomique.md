# ADR-0006 — Promouvoir les index fournisseur de manière atomique

- Statut : **Acceptée**
- Date de décision : **22 juillet 2026**

## Contexte

MINOS reconstruit ses connaissances depuis des index produits par des
fournisseurs externes. Une indexation peut échouer après avoir écrit des
artefacts intermédiaires.

L'expérience A5 avec `scip-java 0.13.1` le démontre concrètement : un module
Maven sain est compilé, un module suivant échoue sur une dépendance absente et
deux shards SCIP restent lisibles. L'agrégation finale n'est toutefois pas
exécutée et aucun `index.scip` n'est publié.

Ces shards ne sont pas équivalents à un index final : leurs identifiants ne
sont pas réécrits dans la forme fournisseur finale, les références entre
shards ne sont pas réconciliées et certaines métadonnées ou relations peuvent
manquer.

## Décision

La promotion d'un résultat d'indexation vers le snapshot actif MINOS est
**atomique**.

```text
run fournisseur réussi
  + artefact final lisible
  + ingestion MINOS réussie
  -> nouveau snapshot admissible à la promotion

run fournisseur échoué
  ou artefact final absent/invalide
  ou ingestion MINOS échouée
  -> snapshot actif précédent conservé
```

Les journaux, codes de phase et artefacts intermédiaires d'un run échoué sont
conservés séparément pour diagnostic. Ils ne deviennent pas des faits résolus
du snapshot actif.

## Invariants

1. Un échec ne réutilise jamais silencieusement un ancien fichier de sortie en
   le présentant comme le résultat du run courant.
2. Un index partiel ne remplace jamais un index valide.
3. La conservation des diagnostics ne change pas le statut d'échec du run.
4. La promotion ne dépend pas du langage ni du fournisseur.
5. Les types SCIP, shards ou statuts propres à un indexeur ne fuitent pas dans
   les contrats publics du domaine.

## Clarification M1 — projet multi-langages / multi-indexeurs

M1 étend explicitement l'invariant au **run projet complet**.

Un projet peut sélectionner plusieurs indexeurs, par exemple un indexeur Java et
un indexeur TypeScript. Le nouvel état actif ne peut pas être promu fournisseur
par fournisseur : tous les artefacts requis par le plan négocié appartiennent au
même run projet.

```text
sélections négociées complètes
  -> exécuter tous les indexeurs sélectionnés
  -> valider tous les artefacts finaux
  -> ingérer/stager un snapshot projet commun
  -> promouvoir ce snapshot une seule fois
```

Si un seul indexeur, le staging ou la promotion échoue, le snapshot actif
précédent reste inchangé. MINOS ne publie donc jamais silencieusement un mélange
« nouveau Java / ancien TypeScript » issu d'un même rafraîchissement.

Pendant un rafraîchissement, l'ancien snapshot peut rester disponible aux
requêtes. Après un échec de rafraîchissement, l'état projet peut être marqué
`STALE` tout en conservant explicitement cet ancien snapshot actif.

## Mode best-effort

MINOS n'implémente pas de récupération best-effort pendant cette baseline.

Un futur mode best-effort resterait possible, mais nécessiterait une décision
séparée et devrait exposer explicitement :

- son état incomplet ;
- les sources couvertes et absentes ;
- l'étape fournisseur interrompue ;
- les identités non réconciliées ;
- une origine et des preuves distinctes ;
- l'interdiction de promotion automatique comme snapshot sain.

## Conséquences

### Positives

- réponses stables pendant un échec d'indexation ;
- absence de mélange silencieux entre faits complets et partiels ;
- rollback implicite vers le dernier snapshot valide ;
- comportement identique pour Java, TypeScript et les futurs fournisseurs ;
- diagnostic conservé sans compromettre les requêtes.

### Négatives

- les modifications récentes restent invisibles tant qu'un run complet ne
  réussit pas ;
- des faits intermédiaires potentiellement utiles ne sont pas interrogés par le
  chemin standard ;
- la future implémentation persistante devra distinguer staging et snapshot
  actif.

## Portée M0 / M1

M0 valide l'invariant, les artefacts transactionnels et le comportement du
runner. M1 formalise le cycle de vie fournisseur-indépendant, l'état observable
et l'atomicité d'un run projet pouvant comporter plusieurs indexeurs.

La gestion persistante des snapshots, leur garbage collection et un éventuel
mode best-effort restent différés jusqu'au choix du backend de stockage.
