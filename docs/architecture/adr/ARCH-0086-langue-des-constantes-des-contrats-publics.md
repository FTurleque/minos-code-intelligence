---
id: ARCH-0086
title: "Langue des constantes des contrats publics"
domain: architecture
rules: false
---

# Langue des constantes des contrats publics

- **Statut** : Accepted
- **Date** : 2026-11-17
- **Origine** : audit du 2026-11-15, constat AUD-QUA-03 ; changement OpenSpec `trancher-la-langue-du-contrat-de-l-explorateur`

## Context

Trois surfaces versionnées sérialisent, par `.name()`, les mêmes énumérations de `minos-application` : l'API HTTP `/api/v1/` de l'explorateur (`minos-explorer/src/main/java/com/minos/explorer/ExplorerApi.java`), l'API Java `com.minos.api.SemanticExplorationApi` et la sortie JSON de `minos semantic projection` (`minos-cli/src/main/java/com/minos/cli/command/SemanticProjectionCommand.java`).

Le contrat v1 mêle deux langues. Huit valeurs sont en français : l'état de projection `ABSENTE`, `PRETE`, `PERIMEE`, `ECHOUEE` (`SemanticProjectionService.State`) et la divergence `IDENTIQUE`, `DIFFERENTE`, `SOURCE_INTROUVABLE`, `NON_COMPARABLE` (`SemanticCorpusQueryService`). L'état d'index, les refus de préparation, les modes de recherche, les natures de score, la nature de document et les codes d'erreur sont en anglais.

Une surface publique de MINOS est versionnée et additive (`AGENTS.md`, section Architecture) : un client écrit contre v1 lit les mêmes valeurs tant que v1 est servie. Le contrat HTTP annonce qu'un changement incompatible publie un nouveau préfixe à côté de `/api/v1/` (`ExplorerContract.java`).

Options considérées :

- **A** : anglais pour toute constante nouvelle, les huit valeurs françaises de v1 figées en liste close ; leur forme anglaise sort avec la version suivante du contrat.
- **B** : A, plus un contrat v2 publié tout de suite, servi à côté de v1.
- **C** : renommer les huit valeurs dans v1 avant la première release qui contient l'explorateur.

## Decision

Option A, choisie par le propriétaire le 2026-11-17.

1. Toute constante nouvelle d'un contrat public de MINOS (valeur d'énumération sérialisée, code d'erreur) s'écrit en anglais, en majuscules, mots séparés par `_`.
2. Les huit valeurs françaises du contrat v1 de l'explorateur forment une liste close. Aucune n'est ajoutée, renommée ni retirée tant que v1 est servie. Un test de contrat par surface tient, champ par champ, les valeurs sérialisées, et la liste close d'une taille affirmée.
3. Leur équivalent anglais n'est publié que par la version suivante du contrat (préfixe `/api/v2/` pour l'API HTTP, valeurs additionnelles pour l'API Java et la CLI), quel que soit le motif de cette version.
4. Une erreur de l'API de l'explorateur porte un `code` pris dans une liste fermée tenue par un test ; son `message` est un diagnostic anglais destiné au développeur. La page de l'explorateur traduit le `code` par un libellé français et n'affiche jamais `message`.

Exclus : le renommage d'une valeur dans v1 (option C) ; la publication d'un contrat v2 au seul motif de la langue (option B).

## Do's and Don'ts

### Do

- Écrire en anglais toute valeur nouvelle d'une énumération sérialisée par une surface publique.
- Tenir les valeurs sérialisées de chaque champ par un test de contrat dans le module de la surface.
- Traduire un code d'erreur côté page, par une table de libellés.

### Don't

- Renommer, retirer ou ajouter une valeur française dans le contrat v1.
- Afficher `error.message` dans la page de l'explorateur.
- Émettre un code d'erreur absent de la liste fermée.

## Consequences

### Positives

- Aucune rupture pour un client de v1 ; l'écart de langue ne grandit plus.
- Une dérive des valeurs sérialisées échoue dans un test, avec le champ et la valeur nommés.

### Négatives

- Le contrat v1 reste bilingue tant qu'aucune v2 n'existe.

### Risques

- Une v2 publiée pour un autre motif doit reprendre les huit valeurs en anglais : la liste close du test de contrat le rappelle.

## Compliance and Enforcement

- Tests de contrat : `minos-explorer` (valeurs sérialisées et liste fermée des codes d'erreur), `minos-api` (`SemanticExplorationApiContractTest`), `minos-cli` (`SemanticProjectionCommandLineTest`) ; jobs `Verify` Ubuntu et Windows.
- Tests du front (`minos-explorer/src/test/js/`, gate `scripts/quality/check-explorer-front.py`) : chaque code de la liste fermée a un libellé, `error.message` n'apparaît pas dans la page.
- Revue : une énumération nouvelle sérialisée par une surface est en anglais.

## Mise en œuvre

Changement OpenSpec `trancher-la-langue-du-contrat-de-l-explorateur`, archivé le 2026-11-19 : `ExplorerContractValuesTest` (`minos-explorer`), `SemanticExplorationApiContractTest` (`minos-api`) et `SemanticProjectionCommandLineTest` (`minos-cli`) tiennent les valeurs sérialisées champ par champ et la liste close des huit valeurs françaises ; `ExplorerErrorCode` porte les douze codes d'erreur de la version 1 et `ExplorerApi.Answer.error` ne reçoit que ce type ; `app.js` traduit le code par la table `ERROR_LABELS` et n'affiche plus `error.message`.

## References

- `openspec/changes/trancher-la-langue-du-contrat-de-l-explorateur/`
- [ARCH-0082](ARCH-0082-surface-http-locale-optionnelle-de-consultation.md)
