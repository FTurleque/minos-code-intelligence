# M2 — Résultats compacts de symboles

Statut : **incrément validé localement**

Date : **23 juillet 2026**

## Objectif

Cet incrément introduit `SymbolResult`, premier DTO public de requête MINOS.
Il sépare le résultat consommable de l'entité `Symbol` stockée et prépare les
sorties texte/JSON de la future CLI.

## Contrat

`SymbolResult` expose :

```text
id
symbolKey
identityQuality
projectId
moduleId?
fileId?
kind
name
qualifiedName?
signature?
language
location?
resolutionStatus
origin
external
generated
```

Le résultat conserve les informations nécessaires pour :

- distinguer et adresser un symbole ;
- expliquer la qualité de son identité ;
- afficher sa déclaration sans retourner le fichier complet ;
- filtrer par projet, module ou fichier ;
- exposer la résolution et la provenance ;
- distinguer les symboles externes et générés.

Il omet volontairement :

- les identifiants fournisseur opaques de `ProviderReference` ;
- le parent et les relations, qui relèvent de résultats spécialisés ;
- les occurrences et usages, qui relèvent du futur `UsageResult` ;
- le contenu source complet.

## Compatibilité

`SymbolQueryService` conserve les méthodes historiques retournant les entités
du domaine pour les expérimentations M0 :

```text
findSymbol(...)
findSymbols(...)
getFileSymbols(...)
```

Les nouvelles méthodes retournent des listes immuables de DTO :

```text
findSymbolResults(projectId, text, limit)
findSymbolResults(projectId, criteria)
getFileSymbolResults(projectId, fileId, limit)
```

Le classement et les limites restent ceux du `CodeKnowledgeStore`; le mapping
ne réordonne pas les résultats.

## Couverture

Les tests vérifient :

- le mapping complet de l'identité, du module, du fichier et de l'emplacement ;
- la conservation du statut de résolution et de la provenance ;
- l'absence de mutabilité de la liste retournée ;
- la recherche structurée combinée sous forme de DTO ;
- les symboles d'un fichier sous forme de DTO et dans l'ordre source ;
- la compatibilité des méthodes historiques avec l'ingestion SCIP.

Validation locale :

```text
.\mvnw.cmd clean verify
57 sources main compilées
22 sources test compilées
57 tests réussis
0 échec
0 erreur
BUILD SUCCESS
```

## Suite

Les rendus déterministes sont maintenant définis dans
[`SYMBOL_OUTPUT.md`](SYMBOL_OUTPUT.md). La commande `minos find-symbol` peut
désormais consommer ce contrat sans sérialiser directement les entités du
store.
