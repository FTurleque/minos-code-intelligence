# M1.3 — IndexerRegistry et négociation de capacités

Statut : **implémenté — validation locale requise**

Date : **22 juillet 2026**

Suivi : issue #6.

## Objectif

M1.3 sépare strictement deux responsabilités :

1. **décrire et sélectionner** un indexeur compatible ;
2. **exécuter** cet indexeur.

Seule la première responsabilité appartient à cet incrément. Aucun processus `scip-java`, `scip-typescript` ou autre fournisseur n'est lancé par `IndexerRegistry`.

## Contrats

### `IndexerDescriptor`

Décrit un indexeur par des données MINOS :

- identifiant stable du fournisseur ;
- version ;
- nom d'affichage ;
- langages supportés ;
- systèmes de build qualifiés ;
- capacités positives ;
- niveau de qualification ;
- priorité ;
- limitations observées.

Une liste vide de systèmes de build signifie que M1 n'impose pas de build-system particulier au fournisseur.

### `IndexerCapability`

Capacités actuellement représentées :

```text
SYMBOLS
REFERENCES
IMPLEMENTATION_RELATIONS
STRUCTURAL_RELATIONS
MULTI_MODULE
TEST_SOURCES
PARTIAL_INDEX_ON_BUILD_FAILURE
```

Une capacité signifie « support observé/qualifié », pas « précision ou rappel parfaits ».

### `IndexingRequirements`

L'appelant fournit les capacités requises. La baseline M1 demande explicitement :

```text
SYMBOLS + REFERENCES
```

Les indexeurs `EXPERIMENTAL` sont exclus par défaut et doivent être autorisés explicitement.

### `IndexerNegotiationResult`

Le résultat conserve :

- une sélection par langage ;
- les langages non couverts ;
- toutes les évaluations utiles et leur motif.

Motifs actuels :

```text
SELECTED
REJECTED_BUILD_SYSTEM
REJECTED_MISSING_CAPABILITIES
REJECTED_EXPERIMENTAL
NOT_SELECTED_LOWER_PRIORITY
```

La sélection est déterministe : priorité décroissante, puis identifiant.

## Catalogue SCIP qualifié par M0

Les connaissances fournisseur sont confinées à `adapter.scip.ScipIndexerCatalog`.

### `scip-java 0.13.1`

Qualifié M1 sur :

- Java ;
- Maven ;
- symboles ;
- références ;
- relations d'implémentation ;
- multi-module ;
- sources de test.

Non déclaré :

- index partiel sur compilation cassée ;
- relation `CALLS` explicite ;
- kinds complets.

### `scip-typescript 0.4.0`

Qualifié M1 sur :

- TypeScript ;
- symboles ;
- références ;
- relations structurelles ;
- multi-projet ;
- sources de test ;
- index partiel malgré certaines erreurs TypeScript.

Le descriptor n'impose pas npm : dans D2, les références de projets sont portées par `tsconfig` et npm n'était pas une capacité de l'indexeur.

Non déclaré :

- distinction fiable `extends` / `implements` ;
- relation `CALLS` explicite ;
- kinds complets ;
- identités distinctes pour toutes les surcharges.

## Propriétés vérifiées par tests

`IndexerRegistryTest` couvre :

- rejet d'un identifiant d'indexeur dupliqué ;
- sélection déterministe du candidat compatible de priorité maximale ;
- explication des rejets de build et de capacités ;
- exclusion des indexeurs expérimentaux par défaut.

`ScipIndexerCatalogTest` couvre :

- sélection `scip-java` sur la fixture Maven multi-module ;
- sélection `scip-typescript` sur la fixture TypeScript multi-projet ;
- asymétrie M0 sur les builds cassés : Java refuse la capacité d'index partiel, TypeScript l'annonce ;
- absence volontaire de promesse `IMPLEMENTATION_RELATIONS` précise pour TypeScript.

Le test `ProviderBoundaryTest` continue d'interdire les types SCIP, Protobuf et Glean dans `orchestration`.

## Hors périmètre M1.3

- lancement des processus d'indexation ;
- état `QUEUED/RUNNING/...` ;
- promotion d'un index produit ;
- annulation ;
- timeout ;
- retry ;
- concurrence d'indexation ;
- choix de chemins d'outils exécutables ;
- installation automatique des indexeurs.

Ces responsabilités sont réservées à **M1.4 — cycle de vie et état d'index**.

## Validation locale attendue

Depuis la branche M1.3 :

```powershell
.\mvnw.cmd clean verify
```

La PR doit rester Draft jusqu'à un run vert sur son head exact.
