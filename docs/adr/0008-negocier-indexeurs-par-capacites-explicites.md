# ADR-0008 — Négocier les indexeurs par capacités explicites

- Statut : **Proposé**
- Date : **22 juillet 2026**
- Jalon : M1

## Contexte

M0 a montré que deux indexeurs SCIP peuvent partager un protocole commun tout en ayant des comportements différents. `scip-java 0.13.1` est qualifié sur Maven multi-module et ne publie pas d'index final lorsque la compilation échoue. `scip-typescript 0.4.0` sait produire un index exploitable malgré certaines erreurs TypeScript, mais ses kinds, surcharges et relations structurelles ont des limites différentes.

Choisir un fournisseur uniquement à partir du langage ou de son nom masquerait ces différences et conduirait MINOS à inventer des garanties que les mesures ne démontrent pas.

## Décision proposée

1. chaque indexeur est décrit par un `IndexerDescriptor` indépendant de son API native ;
2. le descriptor expose version, langages, systèmes de build qualifiés, capacités positives, niveau de qualification, priorité et limitations ;
3. une capacité signifie seulement « scénario observé/qualifié », jamais « résultat sémantiquement complet » ;
4. `IndexerRegistry` sélectionne un indexeur séparément pour chaque langage détecté ;
5. la sélection exige explicitement les capacités demandées par l'appelant ;
6. les indexeurs expérimentaux sont exclus par défaut ;
7. les rejets restent observables : build non qualifié, capacité absente, fournisseur expérimental ou priorité inférieure ;
8. l'ordre de sélection est déterministe : priorité décroissante puis identifiant ;
9. la négociation ne lance aucun processus fournisseur ; l'exécution et son cycle de vie restent M1.4 ;
10. les connaissances propres à SCIP restent dans `adapter.scip` et sont adaptées vers les contrats `orchestration`.

## Catalogue M1 issu des preuves M0

### scip-java 0.13.1

Capacités annoncées :

- symboles ;
- références ;
- relations d'implémentation ;
- Maven multi-module ;
- sources de test.

Contraintes conservées : Maven uniquement pour le périmètre qualifié M0, aucun index final sur échec de compilation, aucune relation `CALLS` explicite et kinds partiellement non spécifiés.

### scip-typescript 0.4.0

Capacités annoncées :

- symboles ;
- références ;
- relations structurelles ;
- multi-projet ;
- sources de test ;
- index partiel malgré certains échecs de build/type-check.

Contraintes conservées : surcharges parfois fusionnées sous une identité fournisseur, kinds souvent non spécifiés, relations structurelles incomplètes et aucune relation `CALLS` explicite.

Le descriptor TypeScript n'impose pas npm : M0 a démontré que les références multi-projets sont portées par `tsconfig`, npm servant à l'installation et à la vérité terrain.

## Conséquences

- un projet multi-langages peut sélectionner plusieurs indexeurs sans créer de fournisseur global artificiel ;
- un appelant peut demander une capacité forte et obtenir explicitement « non couvert » plutôt qu'un faux succès ;
- un nouveau fournisseur peut être ajouté sans modifier le domaine de découverte ;
- les limites fournisseurs deviennent des données de sélection et de diagnostic ;
- M1.4 pourra exécuter uniquement un plan déjà négocié et observable.

## Validation attendue

L'ADR pourra passer à **Accepté** lorsque M1.3 aura confirmé :

- unicité des indexeurs enregistrés ;
- sélection déterministe par priorité ;
- refus explicables pour build/capacités/qualification ;
- sélection réelle `scip-java` sur la fixture Maven ;
- sélection réelle `scip-typescript` sur les fixtures TypeScript ;
- conservation de l'asymétrie d'index partiel Java/TypeScript ;
- absence de types SCIP dans `orchestration`.
